import Foundation

/// Fetches both sources concurrently, saves good data to the store, falls back to cached data on failure.
public struct SunWattLoader: Sendable {
    public let store: AppGroupStore
    /// Builds the solar source from settings; nil when the selected solar provider is not configured or invalid.
    public let makeSolar: @Sendable (SunWattSettings) -> SolarDataSource?
    /// The inverter on the home network alone (no cloud request), read while the cloud source is rate-limited
    /// (`SolarProvider.minimumFetchInterval`); nil without one.
    public let makeLiveSolar: @Sendable (SunWattSettings) -> SolarDataSource?
    /// Builds the price source for the region chosen in settings.
    public let makePrices: @Sendable (PriceRegion) -> PriceDataSource
    public let now: @Sendable () -> Date

    /// Price source for the region currently stored in settings.
    public var prices: PriceDataSource { makePrices(store.settings.priceRegion) }

    public init(
        store: AppGroupStore = .shared,
        makeSolar: @escaping @Sendable (SunWattSettings) -> SolarDataSource? = SunWattLoader.defaultSolar,
        makePrices: @escaping @Sendable (PriceRegion) -> PriceDataSource = SunWattLoader.defaultPrices,
        now: @escaping @Sendable () -> Date = { Date() },
        makeLiveSolar: @escaping @Sendable (SunWattSettings) -> SolarDataSource? = SunWattLoader.defaultLiveSolar
    ) {
        self.store = store
        self.makeSolar = makeSolar
        self.makeLiveSolar = makeLiveSolar
        self.makePrices = makePrices
        self.now = now
    }

    /// Uses one fixed price source whatever the region setting is (tests).
    public init(
        store: AppGroupStore = .shared,
        makeSolar: @escaping @Sendable (SunWattSettings) -> SolarDataSource? = SunWattLoader.defaultSolar,
        prices: PriceDataSource,
        now: @escaping @Sendable () -> Date = { Date() },
        makeLiveSolar: @escaping @Sendable (SunWattSettings) -> SolarDataSource? = SunWattLoader.defaultLiveSolar
    ) {
        self.init(store: store, makeSolar: makeSolar, makePrices: { _ in prices }, now: now, makeLiveSolar: makeLiveSolar)
    }

    public static let defaultSolar: @Sendable (SunWattSettings) -> SolarDataSource? = { settings in
        SolarAccount(settings: settings)?.client()
    }

    public static let defaultLiveSolar: @Sendable (SunWattSettings) -> SolarDataSource? = { settings in
        SolarAccount(settings: settings)?.liveOnlyClient()
    }

    public static let defaultPrices: @Sendable (PriceRegion) -> PriceDataSource = { region in
        PriceDataSourceFactory.client(for: region)
    }

    /// Cache window for callers that fire together (widget timelines, controls, intents, automatic app refreshes).
    /// After a refresh or a settings change every widget reloads at once; with this window only the first one
    /// fetches and the others read what it stored (Energy-Charts answers HTTP 429 to parallel requests).
    public static let sharedCacheWindow: TimeInterval = 120

    /// Never throws: errors become `SourceStatus` values.
    /// Each fetch must finish within `deadline` seconds (a widget refresh has a small budget); a source that is
    /// too slow counts as failed, so cached data is shown as stale. When the task is cancelled, returns
    /// `cached()` without saving anything.
    ///
    /// `maxCacheAge`: when greater than 0 and the store holds data fetched less than this many seconds ago for
    /// every source in use, that data is returned as `.fresh` without any network request. Solar counts only if
    /// the solar account is configured, belongs to the stored snapshot and was fetched on the current Madrid day;
    /// prices count only for the current region and when a point covers now. 0 (default) always fetches.
    public func load(deadline: TimeInterval = 15, maxCacheAge: TimeInterval = 0) async -> SunWattData {
        if Task.isCancelled { return cached() }
        let settings = store.settings
        let nowDate = now()
        if maxCacheAge > 0, let recent = recentCache(settings: settings, maxAge: maxCacheAge, now: nowDate) {
            return recent
        }
        let priceSource = makePrices(settings.priceRegion)
        async let priceResult = Self.fetchPrices(priceSource, includeTomorrow: settings.showTomorrowPrices, deadline: deadline)
        // Every plant at once, each with its own cache, limits and failures.
        let plants = settings.plants
        let loader = self
        let solarResults = await withTaskGroup(of: (Int, PlantSolarData?).self) { group in
            for (index, plant) in plants.enumerated() {
                group.addTask { (index, await loader.loadPlant(plant, settings: settings, deadline: deadline, now: nowDate)) }
            }
            var results = [PlantSolarData?](repeating: nil, count: plants.count)
            for await (index, result) in group { results[index] = result }
            return results
        }
        let priceOutcome = await priceResult
        // A nil plant result: that plant's fetch was cancelled.
        if Task.isCancelled || solarResults.contains(where: { $0 == nil }) || Self.isCancellation(priceOutcome) {
            return cached()
        }

        var data = SunWattData(solar: nil, prices: nil, solarStatus: .notConfigured, priceStatus: .fresh)
        data.setPlants(solarResults.compactMap { $0 })

        let cachedPrices = store.loadPrices().flatMap { $0.region == settings.priceRegion ? $0 : nil }
        switch priceOutcome {
        case .success(let fetched):
            let series = settings.showTomorrowPrices ? Self.keepingFutureHours(fetched, from: cachedPrices, now: nowDate) : fetched
            store.savePrices(series)
            data.prices = series
            data.priceStatus = .fresh
        case .failure(let error):
            if let cachedPrices {
                data.prices = cachedPrices
                let calendar = cachedPrices.region.calendar
                let hasToday = cachedPrices.points.contains { calendar.isDate($0.start, inSameDayAs: nowDate) }
                data.priceStatus = hasToday
                    ? .stale(since: cachedPrices.fetchedAt)
                    : .error(message: error.localizedDescription)
            } else {
                data.priceStatus = .error(message: error.localizedDescription)
            }
        }
        return data
    }

    /// One plant's part of a load: fetch (or reuse, under the provider's limit), store, fall back to its cache.
    /// `settings` are the whole settings; the plant is put in the place of the first one, so every helper written for
    /// "the" plant works on it. Nil when cancelled.
    private func loadPlant(_ plant: SolarPlant, settings: SunWattSettings, deadline: TimeInterval, now nowDate: Date) async -> PlantSolarData? {
        let settings = settings.focused(on: plant)
        let plantID = plant.id
        guard let solarSource = makeSolar(settings) else {
            return PlantSolarData(id: plantID, name: plant.displayName(stationName: nil), solar: nil, status: unconfiguredStatus(settings))
        }
        let reusedSolar = recentSolar(settings: settings, plantID: plantID, now: nowDate)
        // A rate-limited provider that failed moments ago is not asked again yet: its failure is reused.
        let recentFailure = reusedSolar != nil ? nil : recentSolarFailure(settings: settings, now: nowDate)
        // While the cloud is not asked, the inverter on the home network still is (live data at home).
        let cloudFetched = reusedSolar == nil && recentFailure == nil
        let liveSource = cloudFetched ? nil : makeLiveSolar(settings)
        let outcome = await Self.fetchSolar(solarSource, reusing: reusedSolar, failure: recentFailure, live: liveSource, deadline: deadline)
        if Task.isCancelled || Self.isCancellation(outcome) { return nil }

        let account = SolarAccount(settings: settings)
        var result = PlantSolarData(id: plantID, name: "", solar: nil, status: .fresh)
        switch outcome {
        case .success(var snapshot):
            if let link = account?.kioskEndpoint?.cacheID { store.clearKioskRejection(linkID: link) }
            if cloudFetched, let account {
                if snapshot.isLive == true, snapshot.cloudFetchedAt == nil, account.provider.minimumFetchInterval > 0 {
                    // Only the inverter answered: the cloud failed and is not asked again for its interval.
                    store.noteSolarFailure(accountID: account.cacheID, at: nowDate, message: SunWattError.unavailable.localizedDescription)
                } else {
                    store.clearSolarFailure(accountID: account.cacheID)
                }
            }
            if snapshot.isLive == true, let cached = cachedSolar(for: settings, plantID: plantID) {
                snapshot = Self.fillingIn(snapshot, from: cached)
            }
            snapshot.endpointID = Self.endpointID(for: settings)
            store.saveSolar(snapshot, plantID: plantID)
            result.solar = snapshot
            result.status = .fresh
        case .failure(let error):
            if recentFailure == nil, let account, account.provider.minimumFetchInterval > 0 {
                store.noteSolarFailure(accountID: account.cacheID, at: nowDate, message: error.localizedDescription)
            }
            if (error as? SunWattError) == .kioskLinkRejected, let link = account?.kioskEndpoint?.cacheID {
                store.noteKioskRejected(linkID: link, at: nowDate)
            }
            if isKioskLinkExpired(settings: settings, now: nowDate) {
                // Rejected for a while: the link most likely expired. Ask for a new one instead of showing old data.
                result.status = .error(message: SunWattError.kioskLinkRejected.localizedDescription)
                result.linkExpired = true
            } else if let cached = cachedSolar(for: settings, plantID: plantID) {
                // Also covers a cache from a previous Madrid day: its curve belongs to another day.
                result.solar = cached
                result.status = .stale(since: cached.fetchedAt)
            } else {
                result.status = .error(message: error.localizedDescription)
            }
        }
        result.name = plant.displayName(stationName: result.solar?.stationName)
        return result
    }

    /// Cached data only (no network), for placeholders and snapshots.
    /// Prices cached for a different region than the current setting count as absent.
    public func cached() -> SunWattData {
        let settings = store.settings
        let unavailable = SourceStatus.error(message: SunWattError.unavailable.localizedDescription)
        var data = SunWattData(solar: nil, prices: nil, solarStatus: unavailable, priceStatus: unavailable)
        data.setPlants(settings.plants.map { cachedPlant($0, settings: settings) })
        if let prices = store.loadPrices(), prices.region == settings.priceRegion {
            data.prices = prices
            data.priceStatus = .stale(since: prices.fetchedAt)
        }
        return data
    }

    private func cachedPlant(_ plant: SolarPlant, settings: SunWattSettings) -> PlantSolarData {
        let settings = settings.focused(on: plant)
        var result = PlantSolarData(id: plant.id, name: "", solar: nil, status: .error(message: SunWattError.unavailable.localizedDescription))
        if makeSolar(settings) == nil {
            result.status = unconfiguredStatus(settings)
        } else if let solar = cachedSolar(for: settings, plantID: plant.id) {
            result.solar = solar
            result.status = .stale(since: solar.fetchedAt)
        }
        if isKioskLinkExpired(settings: settings, now: now()) {
            result.solar = nil
            result.status = .error(message: SunWattError.kioskLinkRejected.localizedDescription)
            result.linkExpired = true
        }
        result.name = plant.displayName(stationName: result.solar?.stationName)
        return result
    }

    /// A live inverter reading without the kiosk part (kiosk unreachable): today's earlier curve, the plant name and
    /// month/year totals come from the stored snapshot.
    static func fillingIn(_ live: SolarSnapshot, from cached: SolarSnapshot) -> SolarSnapshot {
        var result = live
        let calendar = Constants.madridCalendar
        let firstLive = live.curve.first?.time ?? live.fetchedAt
        let earlier = cached.curve.filter { calendar.isDate($0.time, inSameDayAs: live.fetchedAt) && $0.time < firstLive && $0.kW != nil }
        if live.curve.filter({ $0.kW != nil }).count <= 1, !earlier.isEmpty {
            result.curve = earlier + live.curve
        }
        result.stationName = result.stationName ?? cached.stationName
        // SunSpec has no daily counter: the previous figure plus what the lifetime counter grew (never less than the
        // cloud's, which lags).
        let derived = LocalInverterSolarClient.dailyEnergy(lifetimeKWh: live.cumulativeEnergyKWh, at: live.fetchedAt, previous: cached)
        result.dailyEnergyKWh = [live.dailyEnergyKWh, derived].compactMap { $0 }.max()
        result.cloudFetchedAt = live.cloudFetchedAt ?? (cached.isLive == true ? cached.cloudFetchedAt : cached.fetchedAt)
        if result.home == nil, cached.home != nil, live.cloudFetchedAt == nil {
            // Inverter alone (cloud not asked): keep the cloud's consumption figures until the next cloud fetch.
            result.home = cached.home
        }
        if calendar.isDate(cached.fetchedAt, equalTo: live.fetchedAt, toGranularity: .month) {
            result.monthEnergyKWh = result.monthEnergyKWh ?? cached.monthEnergyKWh
        }
        if calendar.isDate(cached.fetchedAt, equalTo: live.fetchedAt, toGranularity: .year) {
            result.yearEnergyKWh = result.yearEnergyKWh ?? cached.yearEnergyKWh
        }
        return result
    }

    /// How long Huawei must keep rejecting the kiosk link before SunWatt calls it expired. One rejection can be a
    /// passing problem on Huawei's side; an expired link is rejected on every refresh.
    public static let kioskExpiredAfter: TimeInterval = 30 * 60

    /// True when the FusionSolar link in `settings` has been rejected for at least `kioskExpiredAfter`.
    func isKioskLinkExpired(settings: SunWattSettings, now: Date) -> Bool {
        guard let endpoint = SolarAccount(settings: settings)?.kioskEndpoint,
              let since = store.kioskRejectedSince(linkID: endpoint.cacheID) else { return false }
        return now.timeIntervalSince(since) >= Self.kioskExpiredAfter
    }

    /// Identifier of the solar account in `settings`; nil when it is not set up or not valid.
    static func endpointID(for settings: SunWattSettings) -> String? {
        SolarAccount(settings: settings)?.cacheID
    }

    /// The stored solar snapshot of the plant, only when it was fetched from the solar account now in `settings`.
    /// A snapshot without an identifier (written before it existed) never matches.
    private func cachedSolar(for settings: SunWattSettings, plantID: String) -> SolarSnapshot? {
        guard let id = Self.endpointID(for: settings), let solar = store.loadSolar(plantID: plantID), solar.endpointID == id else { return nil }
        return solar
    }

    /// The stored snapshot when the provider limits how often it may be fetched (`SolarProvider.minimumFetchInterval`)
    /// and it is younger than that limit, from the current Madrid day.
    private func recentSolar(settings: SunWattSettings, plantID: String, now: Date) -> SolarSnapshot? {
        guard let provider = SolarAccount(settings: settings)?.provider, provider.minimumFetchInterval > 0,
              let solar = cachedSolar(for: settings, plantID: plantID) else { return nil }
        // A live snapshot is new at every inverter read; the limit counts from the last cloud fetch.
        let cloudTime = solar.isLive == true ? (solar.cloudFetchedAt ?? .distantPast) : solar.fetchedAt
        let age = now.timeIntervalSince(cloudTime)
        guard age >= 0, age < provider.minimumFetchInterval,
              Constants.madridCalendar.isDate(cloudTime, inSameDayAs: now) else { return nil }
        return solar
    }

    /// The last failure of a rate-limited provider (SolarEdge) when it is younger than its fetch interval: refreshes
    /// in between show it (with cached data as stale) instead of spending more of the daily requests.
    private func recentSolarFailure(settings: SunWattSettings, now: Date) -> Error? {
        guard let account = SolarAccount(settings: settings), account.provider.minimumFetchInterval > 0,
              let failure = store.solarFailure(accountID: account.cacheID) else { return nil }
        let age = now.timeIntervalSince(failure.at)
        guard age >= 0, age < account.provider.minimumFetchInterval else { return nil }
        return StoredFailure(message: failure.message)
    }

    /// Stored data of every source in use (every plant and the prices), fetched less than `maxAge` seconds ago; nil
    /// when any part is missing or older.
    private func recentCache(settings: SunWattSettings, maxAge: TimeInterval, now: Date) -> SunWattData? {
        func isRecent(_ fetchedAt: Date) -> Bool {
            let age = now.timeIntervalSince(fetchedAt)
            return age >= 0 && age < maxAge
        }
        var plants: [PlantSolarData] = []
        for plant in settings.plants {
            let focused = settings.focused(on: plant)
            guard makeSolar(focused) != nil else {
                plants.append(PlantSolarData(id: plant.id, name: plant.displayName(stationName: nil), solar: nil, status: unconfiguredStatus(focused)))
                continue
            }
            // Solar the provider would not fetch again yet (SolarEdge: 20 min) counts as recent too; otherwise every
            // widget of a SolarEdge user would miss the shared window and fetch prices at the same time (HTTP 429).
            // A live snapshot (at home) only counts for the window itself, so widgets keep reading the inverter.
            let interval = SolarAccount(settings: focused)?.provider.minimumFetchInterval ?? 0
            guard let solar = cachedSolar(for: focused, plantID: plant.id) else { return nil }
            let solarMaxAge = solar.isLive == true ? maxAge : max(maxAge, interval)
            guard now.timeIntervalSince(solar.fetchedAt) >= 0, now.timeIntervalSince(solar.fetchedAt) < solarMaxAge,
                  Constants.madridCalendar.isDate(solar.fetchedAt, inSameDayAs: now) else { return nil }
            plants.append(PlantSolarData(id: plant.id, name: plant.displayName(stationName: solar.stationName), solar: solar, status: .fresh))
        }
        guard let prices = store.loadPrices(), prices.region == settings.priceRegion, isRecent(prices.fetchedAt),
              prices.points.contains(where: { $0.contains(now) }) else { return nil }
        var data = SunWattData(solar: nil, prices: prices, solarStatus: .notConfigured, priceStatus: .fresh)
        data.setPlants(plants)
        return data
    }

    private func unconfiguredStatus(_ settings: SunWattSettings) -> SourceStatus {
        settings.hasSolarInput
            ? .error(message: settings.invalidSolarInputError.localizedDescription)
            : .notConfigured
    }

    /// A transient failure of the tomorrow request must not erase tomorrow prices fetched earlier:
    /// keeps points of `old` (same region) that start after the new series and have not ended yet.
    static func keepingFutureHours(_ new: PriceSeries, from old: PriceSeries?, now: Date) -> PriceSeries {
        guard let old, old.region == new.region, let lastStart = new.points.last?.start else { return new }
        let extra = old.points.filter { $0.start > lastStart && $0.end > now }
        guard !extra.isEmpty else { return new }
        var merged = PriceSeries.merged(
            [new, PriceSeries(points: extra, granularity: old.granularity, fetchedAt: new.fetchedAt, region: new.region)],
            fetchedAt: new.fetchedAt
        )
        merged.sourceUpdatedAt = new.sourceUpdatedAt
        return merged
    }

    private static func isCancellation<T>(_ result: Result<T, Error>) -> Bool {
        if case .failure(let error) = result, error is CancellationError { return true }
        return false
    }

    /// `reusing`: a recent snapshot returned instead of fetching (see `recentSolar`); `failure`: a recent failure
    /// returned instead of fetching (see `recentSolarFailure`).
    /// `live`: the inverter alone, tried first when `recent` or `failure` is used instead of the cloud.
    private static func fetchSolar(_ source: SolarDataSource?, reusing recent: SolarSnapshot?, failure: Error?, live: SolarDataSource?,
                                   deadline: TimeInterval) async -> Result<SolarSnapshot, Error> {
        if let live, recent != nil || failure != nil,
           case .success(let snapshot) = await race(deadline: deadline, { try await live.fetch() }) {
            return .success(snapshot)
        }
        if let recent { return .success(recent) }
        if let failure { return .failure(failure) }
        guard let source else { return .failure(SunWattError.notConfigured) }
        return await race(deadline: deadline) { try await source.fetch() }
    }

    private static func fetchPrices(_ source: PriceDataSource, includeTomorrow: Bool, deadline: TimeInterval) async -> Result<PriceSeries, Error> {
        await race(deadline: deadline) { try await source.fetchTodayAndTomorrow(includeTomorrow: includeTomorrow) }
    }

    /// Runs `work`; gives up after `deadline` seconds with a timeout error and cancels it. Returns as soon as
    /// either side finishes, even if `work` ignores cancellation. Cancelling the caller returns `CancellationError`.
    static func race<T: Sendable>(deadline: TimeInterval, _ work: @escaping @Sendable () async throws -> T) async -> Result<T, Error> {
        let gate = RaceGate<T>()
        let job = Task { () -> Void in
            do { gate.finish(.success(try await work())) } catch { gate.finish(.failure(error)) }
        }
        let timer = Task { () -> Void in
            do {
                try await Task.sleep(for: .seconds(deadline))
                gate.finish(.failure(SunWattError.network(URLError(.timedOut).localizedDescription)))
            } catch {}
        }
        return await withTaskCancellationHandler {
            let result = await gate.result()
            job.cancel()
            timer.cancel()
            return result
        } onCancel: {
            gate.finish(.failure(CancellationError()))
            job.cancel()
            timer.cancel()
        }
    }
}

/// A failure recorded earlier, shown again with its original message.
struct StoredFailure: LocalizedError, Sendable {
    let message: String
    var errorDescription: String? { message }
}

/// One-shot result holder: the first `finish` wins, `result()` suspends until it arrives.
private final class RaceGate<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Result<T, Error>?
    private var waiter: CheckedContinuation<Result<T, Error>, Never>?

    func finish(_ result: Result<T, Error>) {
        lock.lock()
        guard value == nil else { lock.unlock(); return }
        value = result
        let pending = waiter
        waiter = nil
        lock.unlock()
        pending?.resume(returning: result)
    }

    func result() async -> Result<T, Error> {
        await withCheckedContinuation { continuation in
            lock.lock()
            if let value {
                lock.unlock()
                continuation.resume(returning: value)
            } else {
                waiter = continuation
                lock.unlock()
            }
        }
    }
}
