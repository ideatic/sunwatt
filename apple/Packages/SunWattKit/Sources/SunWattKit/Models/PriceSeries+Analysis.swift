import Foundation

/// Hourly points of one market day plus their statistics. Build it once per render with
/// `PriceSeries.dayContext(for:)` instead of calling `stats`/`fraction`/`level` per point.
public struct PriceDayContext: Hashable, Sendable {
    /// Hourly points of the day (23, 24 or 25 on DST days), sorted by start.
    public let hours: [PricePoint]
    public let stats: PriceStats

    public init(hours: [PricePoint], stats: PriceStats) {
        self.hours = hours
        self.stats = stats
    }

    /// Position 0...1 of `point` within the day's min...max (0.5 if flat).
    public func fraction(of point: PricePoint) -> Double {
        guard stats.max > stats.min else { return 0.5 }
        return Swift.min(1, Swift.max(0, (point.eurPerKWh - stats.min) / (stats.max - stats.min)))
    }

    /// Level of `point` relative to the day's range (lower third = low, upper third = high).
    public func level(of point: PricePoint) -> PriceLevel {
        PriceLevel.classify(fraction: fraction(of: point))
    }
}

/// Day contexts for the points of one render, built once: one `PriceDayContext` per market day that appears in
/// `points` (a two-day chart builds two). Gives the same answers as `PriceSeries.fraction(of:)` / `level(of:)`
/// without recomputing the hourly aggregation and the day statistics for every bar.
public struct PriceDayContexts: Sendable {
    private let calendar: Calendar
    private var contexts: [Date: PriceDayContext] = [:]

    public init(series: PriceSeries, points: [PricePoint]) {
        calendar = series.region.calendar
        for point in points {
            guard let key = dayKey(of: point.start), contexts[key] == nil else { continue }
            if let context = series.dayContext(for: point.start) { contexts[key] = context }
        }
    }

    private func dayKey(of date: Date) -> Date? {
        calendar.dateInterval(of: .day, for: date)?.start
    }

    /// Position 0...1 of `point` within its day's range; 0.5 when the day is unknown or flat.
    public func fraction(of point: PricePoint) -> Double {
        dayKey(of: point.start).flatMap { contexts[$0] }?.fraction(of: point) ?? 0.5
    }

    public func level(of point: PricePoint) -> PriceLevel {
        PriceLevel.classify(fraction: fraction(of: point))
    }

    /// Number of distinct days built (tests).
    var dayCount: Int { contexts.count }
}

/// Price analysis. All day math uses `region.calendar` (the market time zone of the series).
public extension PriceSeries {
    /// Points aggregated to hourly (average of the 15-min points in each hour). Identity when already hourly.
    func hourly() -> [PricePoint] {
        if granularity == .hourly { return points }
        var order: [Date] = []
        var sums: [Date: (sum: Double, n: Int)] = [:]
        for p in points {
            let hourStart = Self.hourStart(of: p.start)
            if sums[hourStart] == nil { order.append(hourStart) }
            let cur = sums[hourStart] ?? (0, 0)
            sums[hourStart] = (cur.sum + p.eurPerMWh, cur.n + 1)
        }
        return order.sorted().map {
            PricePoint(start: $0, duration: 3600, eurPerMWh: sums[$0]!.sum / Double(sums[$0]!.n))
        }
    }

    /// Hourly point that contains `date`.
    func currentHour(at date: Date) -> PricePoint? {
        hourly().first { $0.contains(date) }
    }

    /// Hourly points of the market day (in `region`'s time zone) containing `date` (23, 24 or 25 points).
    func hours(onDayOf date: Date) -> [PricePoint] {
        guard let day = region.calendar.dateInterval(of: .day, for: date) else { return [] }
        return hourly().filter { $0.start >= day.start && $0.start < day.end }
    }

    /// Hourly points from `before` hours before to `after` hours after the hour containing `date`
    /// (inclusive at both ends), aligned to hour starts.
    func hours(around date: Date, before: Int = 6, after: Int = 12) -> [PricePoint] {
        let current = Self.hourStart(of: date)
        let lower = current.addingTimeInterval(-Double(before) * 3600)
        let upper = current.addingTimeInterval(Double(after) * 3600)
        return hourly().filter { $0.start >= lower && $0.start <= upper }
    }

    /// Hours and statistics of the market day containing `date`, computed once. Nil when the series has no point that day.
    func dayContext(for date: Date) -> PriceDayContext? {
        let day = hours(onDayOf: date)
        guard let lo = day.map(\.eurPerKWh).min(), let hi = day.map(\.eurPerKWh).max() else { return nil }
        let average = day.reduce(0) { $0 + $1.eurPerKWh } / Double(day.count)
        return PriceDayContext(hours: day, stats: PriceStats(min: lo, max: hi, average: average))
    }

    /// Min/max/average €/kWh of the market day containing `date`.
    func stats(onDayOf date: Date) -> PriceStats? {
        dayContext(for: date)?.stats
    }

    /// Level of `point` relative to its day's range (lower third = low, upper third = high).
    /// Rebuilds the day context on every call: for many points use `PriceDayContexts` or `dayContext(for:)`.
    func level(of point: PricePoint) -> PriceLevel {
        PriceLevel.classify(fraction: fraction(of: point))
    }

    /// Position 0...1 of `point` within its day's min...max (0.5 if flat or unknown).
    /// Rebuilds the day context on every call: for many points use `PriceDayContexts` or `dayContext(for:)`.
    func fraction(of point: PricePoint) -> Double {
        dayContext(for: point.start)?.fraction(of: point) ?? 0.5
    }

    /// The `count` cheapest hourly points that start at or after the current hour, sorted by start time.
    /// `until`: only hours starting before it (e.g. the end of the shown day), so a cheaper next day does not push
    /// the rest of this day out of the ranking.
    func cheapestUpcoming(from date: Date, count: Int = 3, until: Date? = nil) -> [PricePoint] {
        guard count > 0 else { return [] }
        let current = Self.hourStart(of: date)
        let upcoming = hourly().filter { point in
            point.start >= current && (until.map { point.start < $0 } ?? true)
        }
        let cheapest = upcoming.sorted { ($0.eurPerMWh, $0.start) < ($1.eurPerMWh, $1.start) }.prefix(count)
        return cheapest.sorted { $0.start < $1.start }
    }

    /// Start of the absolute hour containing `date` (offsets of all supported regions are whole hours, so this is DST-safe).
    private static func hourStart(of date: Date) -> Date {
        Date(timeIntervalSince1970: (date.timeIntervalSince1970 / 3600).rounded(.down) * 3600)
    }
}

public extension PriceLevel {
    /// Classification by fraction of the day's range: < 1/3 low, > 2/3 high.
    static func classify(fraction: Double) -> PriceLevel {
        if fraction < 1.0 / 3.0 { return .low }
        if fraction > 2.0 / 3.0 { return .high }
        return .medium
    }
}
