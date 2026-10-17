import SwiftUI
import Charts
import WidgetKit

/// Hourly price bars: rounded, coloured green -> yellow -> red relative to the day's range. The current hour is
/// full opacity with an outline and a small marker above it; past hours are dimmed (neutral blend in dark mode).
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
    @Environment(\.colorScheme) private var scheme

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
        let baseDomain = ChartLayout.priceYDomain(points: points)
        let span = baseDomain.upperBound - baseDomain.lowerBound
        let current = points.first { $0.contains(now) }
        // Headroom above the tallest bar for the "now" marker.
        let yDomain = baseDomain.lowerBound...(baseDomain.upperBound + (current == nil ? 0 : span * 0.14))
        let minHeight = span * (isFull ? Theme.Price.minBarFractionFull : Theme.Price.minBarFractionMini)
        let xDomain: ClosedRange<Date> = ChartLayout.priceDomain(points: points) ?? (now...now.addingTimeInterval(1))
        let inset = (points.first?.duration ?? 3600) * 0.09
        // Ring around the current bar; wider in the mini style, where bars are only a few points wide.
        let halo = (points.first?.duration ?? 3600) * (isFull ? 0.025 : 0.05)
        let average: Double? = points.isEmpty ? nil : points.map { $0.eurPerKWh }.reduce(0.0, +) / Double(points.count)
        let contexts = PriceDayContexts(series: series, points: points)

        Chart {
            if let current {
                // Band behind the current hour over the whole height: visible even when its bar is a sliver.
                RectangleMark(
                    xStart: .value("From", current.start.addingTimeInterval(inset - halo * 2)),
                    xEnd: .value("To", current.end.addingTimeInterval(-(inset - halo * 2))),
                    yStart: .value("Bottom", min(yDomain.lowerBound, -span * 0.03)),
                    yEnd: .value("Top", yDomain.upperBound)
                )
                .foregroundStyle(Color.primary.opacity(scheme == .dark ? 0.12 : 0.10))
                .cornerRadius(Theme.Radius.bar + 2)
                // Outline: a slightly larger rectangle behind the bar.
                RectangleMark(
                    xStart: .value("From", current.start.addingTimeInterval(inset - halo)),
                    xEnd: .value("To", current.end.addingTimeInterval(-(inset - halo))),
                    yStart: .value("Zero", min(0, barEnd(current, minHeight)) - span * (isFull ? 0.012 : 0.03)),
                    yEnd: .value("€/kWh", max(0, barEnd(current, minHeight)) + span * (isFull ? 0.012 : 0.03))
                )
                .foregroundStyle(Color.primary.opacity(0.85))
                .cornerRadius(Theme.Radius.bar + 1)
            }
            ForEach(points) { point in
                let state = PriceBarState.state(of: point, now: now)
                RectangleMark(
                    xStart: .value("From", point.start.addingTimeInterval(inset)),
                    xEnd: .value("To", point.end.addingTimeInterval(-inset)),
                    yStart: .value("Zero", 0.0),
                    yEnd: .value("€/kWh", barEnd(point, minHeight))
                )
                .foregroundStyle(barStyle(palette, point: point, state: state, contexts: contexts))
                .opacity(state == .past ? Theme.Price.pastOpacity(for: scheme) : state.opacity)
                .cornerRadius(Theme.Radius.bar)
            }
            if let current {
                PointMark(
                    x: .value("Now", current.start.addingTimeInterval(current.duration / 2)),
                    y: .value("Top", max(0, barEnd(current, minHeight)) + span * (isFull ? 0.012 : 0.03))
                )
                .symbolSize(0)
                .annotation(position: .top, spacing: 1) {
                    Image(systemName: "arrowtriangle.down.fill")
                        .font(.system(size: isFull ? 7 : 8))
                        .foregroundStyle(Color.primary)
                }
            }
            if isFull, let average {
                RuleMark(y: .value("Average", average))
                    .lineStyle(StrokeStyle(lineWidth: 0.75, dash: [2, 3]))
                    .foregroundStyle(Color.primary.opacity(0.30))
            }
        }
        .chartXScale(domain: xDomain)
        .chartYScale(domain: yDomain)
        .chartYAxis {
            if isFull {
                AxisMarks(position: .trailing, values: ChartLayout.priceTicks(yMax: baseDomain.upperBound)) { value in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.07))
                    AxisValueLabel(anchor: .leading) {
                        // No "0" label: the baseline grid line already shows it.
                        if let v = value.as(Double.self), v != 0 {
                            let isTop = v == ChartLayout.priceTicks(yMax: baseDomain.upperBound).last
                            Text(verbatim: ChartLayout.axisNumber(v, decimals: 2) + (isTop && v > 0 ? " €" : ""))
                                .font(.system(.caption2, design: .rounded))
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                                .frame(width: 34, alignment: .trailing)
                        }
                    }
                }
            }
        }
        .chartXAxis {
            if isFull {
                AxisMarks(values: ChartLayout.axisHours(points: points, step: 3, calendar: calendar)) { value in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.07))
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

    /// Bar end value with a minimum visible height (very cheap hours keep a sliver of their color).
    private func barEnd(_ point: PricePoint, _ minHeight: Double) -> Double {
        let value = point.eurPerKWh
        return value >= 0 ? max(value, minHeight) : min(value, -minHeight)
    }

    private func barStyle(_ palette: WidgetPalette, point: PricePoint, state: PriceBarState, contexts: PriceDayContexts) -> AnyShapeStyle {
        let fraction = contexts.fraction(of: point)
        guard palette.isFullColor, state == .past else { return palette.priceColor(fraction: fraction) }
        return AnyShapeStyle(Theme.Price.pastColor(Theme.Price.color(fraction: fraction), scheme: scheme))
    }
}
