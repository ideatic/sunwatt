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
        fatalError("Implemented by the store/timeline task")
    }

    /// Cached data only (no network), for placeholders/snapshots.
    public func cached() -> HomeDashData {
        fatalError("Implemented by the store/timeline task")
    }
}
