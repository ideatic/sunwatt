import Foundation

/// One PVPC price interval.
public struct PricePoint: Codable, Hashable, Sendable, Identifiable {
    /// Start of the interval (absolute instant; the API sends an explicit UTC offset).
    public var start: Date
    /// Interval length in seconds: 3600 (hourly) or 900 (15-min).
    public var duration: TimeInterval
    /// Price in €/MWh, as sent by REE.
    public var eurPerMWh: Double

    public init(start: Date, duration: TimeInterval, eurPerMWh: Double) {
        self.start = start
        self.duration = duration
        self.eurPerMWh = eurPerMWh
    }

    public var id: Date { start }
    public var end: Date { start.addingTimeInterval(duration) }
    /// Price in €/kWh (what the UI shows).
    public var eurPerKWh: Double { eurPerMWh / 1000 }
    public func contains(_ date: Date) -> Bool { date >= start && date < end }
}

public enum PriceGranularity: String, Codable, Sendable {
    case hourly
    case quarterHourly
}

/// PVPC prices for one or more consecutive days (today, plus tomorrow when published).
public struct PriceSeries: Codable, Hashable, Sendable {
    /// Sorted by `start`, no duplicates.
    public var points: [PricePoint]
    public var granularity: PriceGranularity
    /// When this app fetched the data.
    public var fetchedAt: Date
    /// `last-update` reported by REE, if any.
    public var sourceUpdatedAt: Date?

    public init(points: [PricePoint], granularity: PriceGranularity, fetchedAt: Date, sourceUpdatedAt: Date? = nil) {
        self.points = points
        self.granularity = granularity
        self.fetchedAt = fetchedAt
        self.sourceUpdatedAt = sourceUpdatedAt
    }
}

/// Price level relative to a day's min...max range.
public enum PriceLevel: String, Codable, Sendable, CaseIterable {
    case low
    case medium
    case high
}

public struct PriceStats: Codable, Hashable, Sendable {
    public var min: Double
    public var max: Double
    public var average: Double
    public init(min: Double, max: Double, average: Double) {
        self.min = min
        self.max = max
        self.average = average
    }
}
