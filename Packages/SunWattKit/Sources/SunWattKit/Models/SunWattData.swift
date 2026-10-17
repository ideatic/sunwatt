import Foundation

/// Freshness of one data source inside an entry.
public enum SourceStatus: Hashable, Sendable, Codable {
    /// Data fetched in this load.
    case fresh
    /// Fetch failed (or skipped); showing cached data from `since`.
    case stale(since: Date)
    /// No data at all. `message` is user-facing and already localized.
    case error(message: String)
    /// Solar only: no kiosk URL configured.
    case notConfigured
}

/// Everything the UI needs at one moment: last good data for both sources plus their status.
public struct SunWattData: Hashable, Sendable, Codable {
    public var solar: SolarSnapshot?
    public var prices: PriceSeries?
    public var solarStatus: SourceStatus
    public var priceStatus: SourceStatus
    /// True when Huawei has rejected the kiosk link for at least `SunWattLoader.kioskExpiredAfter`: the link most
    /// likely expired. Views then ask the user to replace it. Not stored (worked out by the loader).
    public var solarLinkExpired = false

    private enum CodingKeys: String, CodingKey { case solar, prices, solarStatus, priceStatus }

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
public struct SunWattEntry: Hashable, Sendable {
    public var date: Date
    public var data: SunWattData

    public init(date: Date, data: SunWattData) {
        self.date = date
        self.data = data
    }

    /// True when the solar snapshot belongs to an earlier Europe/Madrid day than `date`: its curve and its
    /// "today" values describe yesterday. Compares calendar days, not 24-hour spans (DST days have 23/25 hours).
    public var isSolarFromEarlierDay: Bool {
        guard let solar = data.solar else { return false }
        let calendar = Constants.madridCalendar
        let reference = min(solar.fetchedAt, solar.dataAsOf ?? solar.fetchedAt)
        return calendar.startOfDay(for: date) > calendar.startOfDay(for: reference)
    }

    /// The snapshot views must show at `date`. After midnight a snapshot from the previous day loses its curve,
    /// the current power and the daily energy (they belong to yesterday), so the views show "no production" with the
    /// stale marker instead of yesterday's numbers as today's. Month, year and total energy are kept.
    public var solarForDisplay: SolarSnapshot? {
        guard var solar = data.solar, isSolarFromEarlierDay else { return data.solar }
        solar.curve = []
        solar.currentPowerKW = nil
        solar.dailyEnergyKWh = nil
        solar.dataAsOf = nil
        solar.home = nil
        return solar
    }

    /// Solar data is shown as stale when older than `Constants.staleAfter` at `date`, when its status is stale,
    /// or when it was fetched on an earlier Madrid day.
    public var isSolarStale: Bool {
        if case .stale = data.solarStatus { return true }
        if let solar = data.solar, date.timeIntervalSince(solar.fetchedAt) > Constants.staleAfter { return true }
        return isSolarFromEarlierDay
    }

    /// True when the price status is stale or the series has no point covering `date`.
    /// False when there are no prices at all: the view shows the status (error / empty) instead of a stale marker.
    public var isPriceStale: Bool {
        if case .stale = data.priceStatus { return true }
        if let prices = data.prices, !prices.points.contains(where: { $0.contains(date) }) { return true }
        return false
    }
}
