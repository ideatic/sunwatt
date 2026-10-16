import SwiftUI
import Charts
import WidgetKit

/// Hourly price bars: rounded, coloured green -> yellow -> red relative to the day's range. The current hour is
/// full opacity on a soft highlight column, past hours are dimmed.
public struct PriceBarChart: View {
    public enum Style: Sendable {
        /// Bars only, no axes. For small and medium widgets.
        case mini
        /// Hour axis, average line and current-time rule.
        case full
    }

    public let series: PriceSeries
    public let points: [PricePoint]
    public let now: Date
    public let style: Style

    @Environment(\.widgetRenderingMode) private var mode

    public init(series: PriceSeries, points: [PricePoint], now: Date, style: Style = .mini) {
        self.series = series
        self.points = points
        self.now = now
        self.style = style
    }

    public var body: some View {
        let palette = WidgetPalette(mode: mode)
        let calendar = series.region.calendar
        let isFull = style == .full
        let yDomain = ChartLayout.priceYDomain(points: points)
        let xDomain: ClosedRange<Date> = ChartLayout.priceDomain(points: points) ?? (now...now.addingTimeInterval(1))
        let inset = (points.first?.duration ?? 3600) * 0.09
        let average: Double? = points.isEmpty ? nil : points.map { $0.eurPerKWh }.reduce(0.0, +) / Double(points.count)

        Chart {
            ForEach(points.filter { $0.contains(now) }) { point in
                RectangleMark(
                    xStart: .value("From", point.start),
                    xEnd: .value("To", point.end),
                    yStart: .value("Floor", yDomain.lowerBound),
                    yEnd: .value("Ceiling", yDomain.upperBound)
                )
                .foregroundStyle(Color.primary.opacity(0.10))
                .cornerRadius(Theme.Radius.bar + 1)
            }
            ForEach(points) { point in
                RectangleMark(
                    xStart: .value("From", point.start.addingTimeInterval(inset)),
                    xEnd: .value("To", point.end.addingTimeInterval(-inset)),
                    yStart: .value("Zero", 0.0),
                    yEnd: .value("€/kWh", point.eurPerKWh)
                )
                .foregroundStyle(palette.priceColor(fraction: series.fraction(of: point)))
                .opacity(PriceBarState.state(of: point, now: now).opacity)
                .cornerRadius(Theme.Radius.bar)
            }
            if isFull, let average {
                RuleMark(y: .value("Average", average))
                    .lineStyle(StrokeStyle(lineWidth: 0.75, dash: [2, 3]))
                    .foregroundStyle(Color.primary.opacity(0.30))
            }
            if isFull, xDomain.contains(now) {
                RuleMark(x: .value("Now", now))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    .foregroundStyle(Color.primary.opacity(0.35))
            }
        }
        .chartXScale(domain: xDomain)
        .chartYScale(domain: yDomain)
        .chartYAxis(.hidden)
        .chartXAxis {
            if isFull {
                AxisMarks(values: ChartLayout.axisHours(points: points, step: 3, calendar: calendar)) { value in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.08))
                    AxisValueLabel(anchor: .top) {
                        if let date = value.as(Date.self) {
                            Text(verbatim: Format.hour(date, timeZone: series.region.timeZone))
                                .font(.system(.caption2, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .accessibilityHidden(true)
    }
}
