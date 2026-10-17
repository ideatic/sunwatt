import Foundation

/// Fetches both sources concurrently, saves good data to the store, falls back to cached data on failure.
public struct SunWattLoader: Sendable {
    public let store: AppGroupStore
    /// Builds the solar source from settings; nil when the selected solar provider is not configured or invalid.
    public let makeSolar: @Sendable (SunWattSettings) -> SolarDataSource?
    /// Builds the price source for the region chosen in settings.
    public let makePrices: @Sendable (PriceRegion) -> PriceDataSource
    public let now: @Sendable () -> Date

    /// Price source for the region currently stored in settings.
    public var prices: PriceDataSource { makePrices(store.settings.priceRegion) }

    public init(
        store: AppGroupStore = .shared,
        makeSolar: @escaping @Sendable (SunWattSettings) -> SolarDataSource? = SunWattLoader.defaultSolar,
        makePrices: @escaping @Sendable (PriceRegion) -> PriceDataSource = SunWattLoader.defaultPrices,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.store = store
        self.makeSolar = makeSolar
        self.makePrices = makePrices
        self.now = now
    }

    /// Uses one fixed price source whatever the region setting is (tests).
    public init(
        store: AppGroupStore = .shared,
        makeSolar: @escaping @Sendable (SunWattSettings) -> SolarDataSource? = SunWattLoader.defaultSolar,
        prices: PriceDataSource,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.init(store: store, makeSolar: makeSolar, makePrices: { _ in prices }, now: now)
    }

    public static let defaultSolar: @Sendable (SunWattSettings) -> SolarDataSource? = { settings in
        SolarAccount(settings: settings)?.client()
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
        let solarSource = makeSolar(settings)
        let nowDate = now()
        if maxCacheAge > 0,
           let recent = recentCache(settings: settings, solarConfigured: solarSource != nil, maxAge: maxCacheAge, now: nowDate) {
            return recent
        }
        let priceSource = makePrices(settings.priceRegion)
        let reusedSolar = solarSource == nil ? nil : recentSolar(settings: settings, now: nowDate)
        async let solarResult = Self.fetchSolar(solarSource, reusing: reusedSolar, deadline: deadline)
        async let priceResult = Self.fetchPrices(priceSource, includeTomorrow: settings.showTomorrowPrices, deadline: deadline)
        let solarOutcome = await solarResult
        let priceOutcome = await priceResult
        if Task.isCancelled || Self.isCancellation(solarOutcome) || Self.isCancellation(priceOutcome) {
            return cached()
        }

        var data = SunWattData(solar: nil, prices: nil, solarStatus: .notConfigured, priceStatus: .fresh)

        if solarSource == nil {
            data.solarStatus = unconfiguredStatus(settings)
        } else {
            switch solarOutcome {
            case .success(var snapshot):
                store.clearKioskRejection()
                if snapshot.isLive == true, let cached = cachedSolar(for: settings) {
                    snapshot = Self.fillingIn(snapshot, from: cached)
                }
                snapshot.endpointID = Self.endpointID(for: settings)
                store.saveSolar(snapshot)
                data.solar = snapshot
                data.solarStatus = .fresh
            case .failure(let error):
                if (error as? SunWattError) == .kioskLinkRejected, let id = Self.endpointID(for: settings) {
                    store.noteKioskRejected(linkID: id, at: nowDate)
                }
                if isKioskLinkExpired(settings: settings, now: nowDate) {
                    // Rejected for a while: the link most likely expired. Ask for a new one instead of showing old data.
                    data.solarStatus = .error(message: SunWattError.kioskLinkRejected.localizedDescription)
                    data.solarLinkExpired = true
                } else if let cached = cachedSolar(for: settings) {
                    // Also covers a cache from a previous Madrid day: its curve belongs to another day.
                    data.solar = cached
                    data.solarStatus = .stale(since: cached.fetchedAt)
                } else {
                    data.solarStatus = .error(message: error.localizedDescription)
                }
            }
        }

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

    /// Cached data only (no network), for placeholders and snapshots.
    /// Prices cached for a different region than the current setting count as absent.
    public func cached() -> SunWattData {
        let settings = store.settings
        let unavailable = SourceStatus.error(message: SunWattError.unavailable.localizedDescription)
        var data = SunWattData(solar: nil, prices: nil, solarStatus: unavailable, priceStatus: unavailable)
        if makeSolar(settings) == nil {
            data.solarStatus = unconfiguredStatus(settings)
        } else if let solar = cachedSolar(for: settings) {
            data.solar = solar
            data.solarStatus = .stale(since: solar.fetchedAt)
        }
        if isKioskLinkExpired(settings: settings, now: now()) {
            data.solar = nil
            data.solarStatus = .error(message: SunWattError.kioskLinkRejected.localizedDescription)
            data.solarLinkExpired = true
        }
        if let prices = store.loadPrices(), prices.region == settings.priceRegion {
            data.prices = prices
            data.priceStatus = .stale(since: prices.fetchedAt)
        }
        return data
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

    /// The stored solar snapshot, only when it was fetched from the solar account now in `settings`.
    /// A snapshot without an identifier (written before it existed) never matches.
    private func cachedSolar(for settings: SunWattSettings) -> SolarSnapshot? {
        guard let id = Self.endpointID(for: settings), let solar = store.loadSolar(), solar.endpointID == id else { return nil }
        return solar
    }

    /// The stored snapshot when the provider limits how often it may be fetched (`SolarProvider.minimumFetchInterval`)
    /// and it is younger than that limit, from the current Madrid day.
    private func recentSolar(settings: SunWattSettings, now: Date) -> SolarSnapshot? {
        guard let provider = SolarAccount(settings: settings)?.provider, provider.minimumFetchInterval > 0,
              let solar = cachedSolar(for: settings) else { return nil }
        let age = now.timeIntervalSince(solar.fetchedAt)
        guard age >= 0, age < provider.minimumFetchInterval,
              Constants.madridCalendar.isDate(solar.fetchedAt, inSameDayAs: now) else { return nil }
        return solar
    }

    /// Stored data of every source in use, fetched less than `maxAge` seconds ago; nil when any part is missing or older.
    private func recentCache(settings: SunWattSettings, solarConfigured: Bool, maxAge: TimeInterval, now: Date) -> SunWattData? {
        func isRecent(_ fetchedAt: Date) -> Bool {
            let age = now.timeIntervalSince(fetchedAt)
            return age >= 0 && age < maxAge
        }
        var data = SunWattData(solar: nil, prices: nil, solarStatus: .notConfigured, priceStatus: .fresh)
        if solarConfigured {
            guard let solar = cachedSolar(for: settings), isRecent(solar.fetchedAt),
                  Constants.madridCalendar.isDate(solar.fetchedAt, inSameDayAs: now) else { return nil }
            data.solar = solar
            data.solarStatus = .fresh
        } else {
            data.solarStatus = unconfiguredStatus(settings)
        }
        guard let prices = store.loadPrices(), prices.region == settings.priceRegion, isRecent(prices.fetchedAt),
              prices.points.contains(where: { $0.contains(now) }) else { return nil }
        data.prices = prices
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

    /// `reusing`: a recent snapshot returned instead of fetching (see `recentSolar`).
    private static func fetchSolar(_ source: SolarDataSource?, reusing recent: SolarSnapshot?, deadline: TimeInterval) async -> Result<SolarSnapshot, Error> {
        if let recent { return .success(recent) }
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
