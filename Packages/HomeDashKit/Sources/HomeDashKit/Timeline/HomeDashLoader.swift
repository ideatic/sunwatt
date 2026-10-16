import Foundation

/// Fetches both sources concurrently, saves good data to the store, falls back to cached data on failure.
public struct HomeDashLoader: Sendable {
    public let store: AppGroupStore
    /// Builds the solar source from settings; nil when the kiosk URL is not configured/invalid.
    public let makeSolar: @Sendable (HomeDashSettings) -> SolarDataSource?
    public let prices: PriceDataSource
    public let now: @Sendable () -> Date

    public init(
        store: AppGroupStore = .shared,
        makeSolar: @escaping @Sendable (HomeDashSettings) -> SolarDataSource? = HomeDashLoader.defaultSolar,
        prices: PriceDataSource = PVPCClient(),
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.store = store
        self.makeSolar = makeSolar
        self.prices = prices
        self.now = now
    }

    public static let defaultSolar: @Sendable (HomeDashSettings) -> SolarDataSource? = { settings in
        KioskEndpoint(parsing: settings.kioskURL).map { FusionSolarKioskClient(endpoint: $0) }
    }

    /// Never throws: errors become `SourceStatus` values.
    public func load() async -> HomeDashData {
        let settings = store.settings
        let solarSource = makeSolar(settings)
        let nowDate = now()
        async let solarResult = Self.fetchSolar(solarSource)
        async let priceResult = Self.fetchPrices(prices, includeTomorrow: settings.showTomorrowPrices, now: nowDate)

        var data = HomeDashData(solar: nil, prices: nil, solarStatus: .notConfigured, priceStatus: .fresh)

        if solarSource == nil {
            data.solarStatus = unconfiguredStatus(settings)
        } else {
            switch await solarResult {
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

        switch await priceResult {
        case .success(let series):
            store.savePrices(series)
            data.prices = series
            data.priceStatus = .fresh
        case .failure(let error):
            if let cached = store.loadPrices() {
                data.prices = cached
                let hasToday = cached.points.contains { Constants.madridCalendar.isDate($0.start, inSameDayAs: nowDate) }
                data.priceStatus = hasToday
                    ? .stale(since: cached.fetchedAt)
                    : .error(message: error.localizedDescription)
            } else {
                data.priceStatus = .error(message: error.localizedDescription)
            }
        }
        return data
    }

    /// Cached data only (no network), for placeholders/snapshots.
    public func cached() -> HomeDashData {
        let settings = store.settings
        let unavailable = SourceStatus.error(message: HomeDashError.unavailable.localizedDescription)
        var data = HomeDashData(solar: nil, prices: nil, solarStatus: unavailable, priceStatus: unavailable)
        if makeSolar(settings) == nil {
            data.solarStatus = unconfiguredStatus(settings)
        } else if let solar = store.loadSolar() {
            data.solar = solar
            data.solarStatus = .stale(since: solar.fetchedAt)
        }
        if let prices = store.loadPrices() {
            data.prices = prices
            data.priceStatus = .stale(since: prices.fetchedAt)
        }
        return data
    }

    private func unconfiguredStatus(_ settings: HomeDashSettings) -> SourceStatus {
        settings.kioskURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? .notConfigured
            : .error(message: HomeDashError.invalidKioskURL.localizedDescription)
    }

    private static func fetchSolar(_ source: SolarDataSource?) async -> Result<SolarSnapshot, Error> {
        guard let source else { return .failure(HomeDashError.notConfigured) }
        do { return .success(try await source.fetch()) } catch { return .failure(error) }
    }

    private static func fetchPrices(_ source: PriceDataSource, includeTomorrow: Bool, now: Date) async -> Result<PriceSeries, Error> {
        do {
            if let ranged = source as? PriceRangeDataSource {
                return .success(try await ranged.fetchTodayAndTomorrow(includeTomorrow: includeTomorrow))
            }
            return .success(try await source.fetch(day: now))
        } catch {
            return .failure(error)
        }
    }
}
