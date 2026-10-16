import Foundation

/// Freshness of one data source inside an entry.
public enum SourceStatus: Hashable, Sendable, Codable {
    /// Data fetched in this load.
    case fresh
    /// Fetch failed (or skipped); showing cached data from `since`.
    case stale(since: Date)
    /// No data at all. `message` is user-facing (Spanish).
    case error(message: String)
    /// Solar only: no kiosk URL configured.
    case notConfigured
}

/// Everything the UI needs at one moment: last good data for both sources plus their status.
public struct HomeDashData: Hashable, Sendable, Codable {
    public var solar: SolarSnapshot?
    public var prices: PriceSeries?
    public var solarStatus: SourceStatus
    public var priceStatus: SourceStatus

    public init(solar: SolarSnapshot?, prices: PriceSeries?, solarStatus: SourceStatus, priceStatus: SourceStatus) {
        self.solar = solar
        self.prices = prices
        self.solarStatus = solarStatus
        self.priceStatus = priceStatus
    }

    /// Most recent successful fetch time across both sources.
    public var lastUpdated: Date? {
        [solar?.fetchedAt, prices?.fetchedAt].compactMap { $0 }.max()
    }
}

/// One widget timeline entry. Views derive the current hour from `date`.
public struct HomeDashEntry: Hashable, Sendable {
    public var date: Date
    public var data: HomeDashData

    public init(date: Date, data: HomeDashData) {
        self.date = date
        self.data = data
    }

    /// Solar data is shown as stale when older than `Constants.staleAfter` at `date`, or when its status is stale.
    public var isSolarStale: Bool {
        if case .stale = data.solarStatus { return true }
        if let solar = data.solar, date.timeIntervalSince(solar.fetchedAt) > Constants.staleAfter { return true }
        return false
    }

    /// True when the price status is stale or the series has no point covering `date`.
    public var isPriceStale: Bool {
        if case .stale = data.priceStatus { return true }
        if let prices = data.prices, !prices.points.contains(where: { $0.contains(date) }) { return true }
        return false
    }
}
