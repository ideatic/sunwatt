import SwiftUI
import Charts
import WidgetKit

/// Today's production curve: smooth area with a fading gradient, a thin line on top and a glowing dot at the
/// latest value. The X axis spans the daylight hours (see `ChartLayout.solarDomain`).
public struct SolarCurveChart: View {
    public enum Style: Sendable {
        /// Curve only, no axes. For small and medium widgets.
        case mini
        /// Hour axis, grid, current-time rule and peak label.
        case full
    }

    public let snapshot: SolarSnapshot
    public let now: Date
    public let style: Style

    @Environment(\.widgetRenderingMode) private var mode

    public init(snapshot: SolarSnapshot, now: Date, style: Style = .mini) {
        self.snapshot = snapshot
        self.now = now
        self.style = style
    }

    public var body: some View {
        let palette = WidgetPalette(mode: mode)
        let calendar = Constants.madridCalendar
        let domain = ChartLayout.solarDomain(curve: snapshot.curve, now: now, calendar: calendar)
        let points = ChartLayout.solarPoints(curve: snapshot.curve, domain: domain)
        let yMax = ChartLayout.solarYMax(peak: points.map(\.kW).max())
        let isFull = style == .full
        let dotPoint = snapshot.isIdle ? nil : points.last
        let peak = points.max { $0.kW < $1.kW }

        Chart {
            ForEach(points) { point in
                AreaMark(x: .value("Time", point.time), y: .value("kW", point.kW))
                    .interpolationMethod(.catmullRom)
                    .foregroundStyle(palette.solarArea)
            }
            ForEach(points) { point in
                LineMark(x: .value("Time", point.time), y: .value("kW", point.kW))
                    .interpolationMethod(.catmullRom)
                    .lineStyle(StrokeStyle(lineWidth: isFull ? 2 : 1.6, lineCap: .round, lineJoin: .round))
                    .foregroundStyle(palette.solarLine)
            }
            if isFull, domain.contains(now) {
                RuleMark(x: .value("Now", now))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    .foregroundStyle(Color.primary.opacity(0.35))
            }
            if isFull, let peak, peak.kW > 0.05, showsPeakLabel(peak: peak, latest: points.last) {
                PointMark(x: .value("Time", peak.time), y: .value("kW", peak.kW))
                    .symbolSize(0)
                    .annotation(position: .top, spacing: 2) {
                        Text(verbatim: "\(Format.kW(peak.kW)) kW")
                            .font(.system(.caption2, design: .rounded).weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(.secondary)
                    }
            }
            if let dot = dotPoint {
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(isFull ? 520 : 380)
                    .foregroundStyle(palette.solarGlow.opacity(0.16))
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(isFull ? 190 : 140)
                    .foregroundStyle(palette.solarGlow.opacity(0.35))
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(isFull ? 60 : 44)
                    .foregroundStyle(palette.solarLine)
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(isFull ? 16 : 11)
                    .foregroundStyle(.white)
            }
        }
        .chartXScale(domain: domain)
        .chartYScale(domain: 0...yMax)
        .chartYAxis {
            if isFull {
                AxisMarks(values: [0, yMax / 2]) { _ in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.10))
                }
            }
        }
        .chartXAxis {
            if isFull {
                AxisMarks(values: ChartLayout.axisHours(domain: domain, step: 3, calendar: calendar)) { value in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.08))
                    AxisValueLabel(anchor: .top) {
                        if let date = value.as(Date.self) {
                            Text(verbatim: Format.hour(date)).font(.system(.caption2, design: .rounded)).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .chartPlotStyle { plot in
            plot.overlay(alignment: .bottom) {
                if !isFull { Rectangle().fill(Color.primary.opacity(0.12)).frame(height: 0.5) }
            }
        }
        .accessibilityHidden(true)
    }

    /// The peak label only helps when the peak is not already where the glowing dot is.
    private func showsPeakLabel(peak: SolarPlotPoint, latest: SolarPlotPoint?) -> Bool {
        guard !snapshot.isIdle, let latest else { return true }
        return latest.time.timeIntervalSince(peak.time) > 90 * 60
    }
}
