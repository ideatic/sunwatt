import Foundation

/// How a price bar is drawn relative to the current time.
public enum PriceBarState: Equatable, Sendable {
    case past
    case current
    case future

    public static func state(of point: PricePoint, now: Date) -> PriceBarState {
        if point.contains(now) { return .current }
        return point.end <= now ? .past : .future
    }

    /// Bar opacity: past dimmed, current full, future slightly softened.
    public var opacity: Double {
        switch self {
        case .past: Theme.Price.pastOpacity
        case .current: 1
        case .future: Theme.Price.futureOpacity
        }
    }
}

/// One plotted point of the production curve.
struct SolarPlotPoint: Identifiable, Hashable {
    let time: Date
    let kW: Double
    var id: Date { time }
}

/// Pure layout helpers for the charts (testable without rendering).
public enum ChartLayout {
    /// Hours shown on the solar X axis when the data does not ask for more (local daylight span).
    public static let defaultSolarHours = 6...21

    /// X domain of the production chart: 06:00-21:00 of the curve's day, widened to the whole hours that contain production.
    public static func solarDomain(
        curve: [CurvePoint],
        now: Date,
        calendar: Calendar = Constants.madridCalendar
    ) -> ClosedRange<Date> {
        let reference = curve.last { $0.kW != nil }?.time ?? now
        let dayStart = calendar.startOfDay(for: reference)
        var first = defaultSolarHours.lowerBound
        var last = defaultSolarHours.upperBound
        for point in curve {
            guard let kW = point.kW, kW > 0.05 else { continue }
            let hour = calendar.component(.hour, from: point.time)
            first = min(first, hour)
            last = max(last, hour + 1)
        }
        let lower = calendar.date(byAdding: .hour, value: max(first, 0), to: dayStart) ?? dayStart
        let upper = calendar.date(byAdding: .hour, value: min(last, 24), to: dayStart) ?? dayStart.addingTimeInterval(86_400)
        return lower...upper
    }

    /// Curve points inside `domain` with a value, negative values clamped to 0.
    static func solarPoints(curve: [CurvePoint], domain: ClosedRange<Date>) -> [SolarPlotPoint] {
        curve.compactMap { point in
            guard let kW = point.kW, domain.contains(point.time) else { return nil }
            return SolarPlotPoint(time: point.time, kW: max(0, kW))
        }
    }

    /// Y upper bound for the production chart, with headroom above the peak.
    public static func solarYMax(peak: Double?) -> Double {
        max(2, (peak ?? 0) * 1.25)
    }

    /// X domain of the price chart: first bar start to last bar end.
    public static func priceDomain(points: [PricePoint]) -> ClosedRange<Date>? {
        guard let first = points.first, let last = points.last else { return nil }
        return first.start...last.end
    }

    /// Y domain of the price chart in €/kWh: always includes 0 (negative wholesale prices extend below).
    public static func priceYDomain(points: [PricePoint]) -> ClosedRange<Double> {
        let values = points.map(\.eurPerKWh)
        let lo = min(0, values.min() ?? 0)
        let hi = max(values.max() ?? 0, lo + 0.001)
        return lo...(hi + (hi - lo) * 0.08)
    }

    /// Hour starts to label on the price X axis: every `step` hours of the local clock.
    public static func axisHours(points: [PricePoint], step: Int, calendar: Calendar) -> [Date] {
        points.map(\.start).filter { calendar.component(.hour, from: $0) % step == 0 }
    }

    /// Hour starts to label on the production X axis.
    public static func axisHours(domain: ClosedRange<Date>, step: Int, calendar: Calendar) -> [Date] {
        var result: [Date] = []
        var date = domain.lowerBound
        while date <= domain.upperBound {
            if calendar.component(.hour, from: date) % step == 0 { result.append(date) }
            date = date.addingTimeInterval(3600)
        }
        return result
    }
}
