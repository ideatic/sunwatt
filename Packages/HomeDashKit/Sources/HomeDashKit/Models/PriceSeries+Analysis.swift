import Foundation

/// Price analysis. All day math uses `Constants.madridCalendar`.
public extension PriceSeries {
    /// Points aggregated to hourly (average of the 15-min points in each hour). Identity when already hourly.
    func hourly() -> [PricePoint] {
        fatalError("Implemented by the PVPC client task")
    }

    /// Hourly point that contains `date`.
    func currentHour(at date: Date) -> PricePoint? {
        fatalError("Implemented by the PVPC client task")
    }

    /// Hourly points of the Europe/Madrid day containing `date` (23, 24 or 25 points).
    func hours(onDayOf date: Date) -> [PricePoint] {
        fatalError("Implemented by the PVPC client task")
    }

    /// Hourly points in [`date` - before hours, `date` + after hours], aligned to hour starts.
    func hours(around date: Date, before: Int = 6, after: Int = 12) -> [PricePoint] {
        fatalError("Implemented by the PVPC client task")
    }

    /// Min/max/average €/kWh of the Europe/Madrid day containing `date`.
    func stats(onDayOf date: Date) -> PriceStats? {
        fatalError("Implemented by the PVPC client task")
    }

    /// Level of `point` relative to the day's range (lower third = low, upper third = high).
    func level(of point: PricePoint) -> PriceLevel {
        fatalError("Implemented by the PVPC client task")
    }

    /// Position 0...1 of `point` within its day's min...max (0.5 if flat).
    func fraction(of point: PricePoint) -> Double {
        fatalError("Implemented by the PVPC client task")
    }

    /// The `count` cheapest hourly points that start at or after the current hour, sorted by start time.
    func cheapestUpcoming(from date: Date, count: Int = 3) -> [PricePoint] {
        fatalError("Implemented by the PVPC client task")
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
