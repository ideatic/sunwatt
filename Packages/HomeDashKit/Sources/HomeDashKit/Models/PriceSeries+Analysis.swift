import Foundation

/// Price analysis. All day math uses `Constants.madridCalendar`.
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

    /// Hourly points of the Europe/Madrid day containing `date` (23, 24 or 25 points).
    func hours(onDayOf date: Date) -> [PricePoint] {
        guard let day = Constants.madridCalendar.dateInterval(of: .day, for: date) else { return [] }
        return hourly().filter { $0.start >= day.start && $0.start < day.end }
    }

    /// Hourly points in [`date` - before hours, `date` + after hours], aligned to hour starts.
    func hours(around date: Date, before: Int = 6, after: Int = 12) -> [PricePoint] {
        let current = Self.hourStart(of: date)
        let lower = current.addingTimeInterval(-Double(before) * 3600)
        let upper = current.addingTimeInterval(Double(after) * 3600)
        return hourly().filter { $0.start >= lower && $0.start <= upper }
    }

    /// Min/max/average €/kWh of the Europe/Madrid day containing `date`.
    func stats(onDayOf date: Date) -> PriceStats? {
        let day = hours(onDayOf: date).map(\.eurPerKWh)
        guard let lo = day.min(), let hi = day.max() else { return nil }
        return PriceStats(min: lo, max: hi, average: day.reduce(0, +) / Double(day.count))
    }

    /// Level of `point` relative to the day's range (lower third = low, upper third = high).
    func level(of point: PricePoint) -> PriceLevel {
        PriceLevel.classify(fraction: fraction(of: point))
    }

    /// Position 0...1 of `point` within its day's min...max (0.5 if flat).
    func fraction(of point: PricePoint) -> Double {
        guard let s = stats(onDayOf: point.start), s.max > s.min else { return 0.5 }
        return Swift.min(1, Swift.max(0, (point.eurPerKWh - s.min) / (s.max - s.min)))
    }

    /// The `count` cheapest hourly points that start at or after the current hour, sorted by start time.
    func cheapestUpcoming(from date: Date, count: Int = 3) -> [PricePoint] {
        guard count > 0 else { return [] }
        let current = Self.hourStart(of: date)
        let upcoming = hourly().filter { $0.start >= current }
        let cheapest = upcoming.sorted { ($0.eurPerMWh, $0.start) < ($1.eurPerMWh, $1.start) }.prefix(count)
        return cheapest.sorted { $0.start < $1.start }
    }

    /// Start of the absolute hour containing `date` (Madrid offsets are whole hours, so this is DST-safe).
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
