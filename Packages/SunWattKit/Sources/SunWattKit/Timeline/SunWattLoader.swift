import Foundation

/// Fetches both sources concurrently, saves good data to the store, falls back to cached data on failure.
public struct SunWattLoader: Sendable {
    public let store: AppGroupStore
    /// Builds the solar source from settings; nil when the kiosk URL is not configured/invalid.
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
        KioskEndpoint(parsing: settings.kioskURL).map { FusionSolarKioskClient(endpoint: $0) }
    }

    public static let defaultPrices: @Sendable (PriceRegion) -> PriceDataSource = { region in
        PriceDataSourceFactory.client(for: region)
    }

    /// Never throws: errors become `SourceStatus` values.
    /// Each fetch must finish within `deadline` seconds (a widget refresh has a small budget); a source that is
    /// too slow counts as failed, so cached data is shown as stale. When the task is cancelled, returns
    /// `cached()` without saving anything.
    public func load(deadline: TimeInterval = 15) async -> SunWattData {
        if Task.isCancelled { return cached() }
        let settings = store.settings
        let solarSource = makeSolar(settings)
        let priceSource = makePrices(settings.priceRegion)
        let nowDate = now()
        async let solarResult = Self.fetchSolar(solarSource, deadline: deadline)
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
            case .success(let snapshot):
                store.saveSolar(snapshot)
                data.solar = snapshot
                data.solarStatus = .fresh
            case .failure(let error):
                if let cached = store.loadSolar() {
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
        } else if let solar = store.loadSolar() {
            data.solar = solar
            data.solarStatus = .stale(since: solar.fetchedAt)
        }
        if let prices = store.loadPrices(), prices.region == settings.priceRegion {
            data.prices = prices
            data.priceStatus = .stale(since: prices.fetchedAt)
        }
        return data
    }

    private func unconfiguredStatus(_ settings: SunWattSettings) -> SourceStatus {
        settings.kioskURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? .notConfigured
            : .error(message: SunWattError.invalidKioskURL.localizedDescription)
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

    private static func fetchSolar(_ source: SolarDataSource?, deadline: TimeInterval) async -> Result<SolarSnapshot, Error> {
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
