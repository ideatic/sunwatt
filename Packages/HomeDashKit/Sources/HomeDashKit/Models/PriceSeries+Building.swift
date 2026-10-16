import Foundation

/// Helpers the price clients share to build series.
extension PriceSeries {
    /// Granularity from the smallest gap between consecutive sorted instants: 15 min or less is quarter-hourly.
    static func granularity(of starts: [Date]) -> PriceGranularity {
        var minGap = TimeInterval.infinity
        for (a, b) in zip(starts, starts.dropFirst()) { minGap = Swift.min(minGap, b.timeIntervalSince(a)) }
        return minGap <= 900 ? .quarterHourly : .hourly
    }

    /// Points from sorted, unique `(start, €/MWh)` pairs, all with the duration implied by the granularity.
    static func makePoints(_ values: [(Date, Double)]) -> [PricePoint] {
        let duration: TimeInterval = granularity(of: values.map(\.0)) == .quarterHourly ? 900 : 3600
        return values.map { PricePoint(start: $0.0, duration: duration, eurPerMWh: $0.1) }
    }

    /// `yyyy-MM-dd` of `date` in `calendar`'s time zone.
    static func dayString(of date: Date, in calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Merges series of the same region: sorted, no duplicate starts (first wins).
    /// Quarter-hourly when any point lasts 900 s or less.
    static func merged(_ series: [PriceSeries], fetchedAt: Date) -> PriceSeries {
        var byStart: [Date: PricePoint] = [:]
        for p in series.flatMap(\.points) where byStart[p.start] == nil { byStart[p.start] = p }
        let points = byStart.values.sorted { $0.start < $1.start }
        let quarter = points.contains { $0.duration <= 900 }
        return PriceSeries(
            points: points,
            granularity: quarter ? .quarterHourly : .hourly,
            fetchedAt: fetchedAt,
            sourceUpdatedAt: series.compactMap(\.sourceUpdatedAt).max(),
            region: series.first?.region ?? .spain
        )
    }
}
