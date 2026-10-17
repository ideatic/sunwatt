#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI
import Charts

// App versions of the widget charts: same look (`ChartLayout`, `Theme`), plus selection. Touch and drag on
// iOS, hover on macOS (`chartXSelection`), with a callout showing the exact value and time.

/// Small floating label used by both charts.
struct ChartCallout: View {
    let title: String
    let detail: String
    var tint: Color?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = AppPalette(scheme)
        VStack(alignment: .leading, spacing: 1) {
            HStack(spacing: 5) {
                if let tint { Circle().fill(tint).frame(width: 7, height: 7) }
                Text(verbatim: title)
                    .font(.system(.footnote, design: .rounded).weight(.bold))
                    .monospacedDigit()
            }
            Text(verbatim: detail)
                .font(.system(.caption2, design: .rounded).weight(.medium))
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
        .lineLimit(1)
        .fixedSize()
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background {
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(palette.calloutFill)
                .shadow(color: .black.opacity(palette.isDark ? 0.5 : 0.16), radius: 6, y: 2)
        }
        .overlay {
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .strokeBorder(palette.hairline, lineWidth: 0.5)
        }
    }
}

/// Today's production curve with hour axis, "now" rule and selection.
struct InteractiveSolarChart: View {
    let snapshot: SolarSnapshot
    let now: Date
    /// Preselected time, for snapshots.
    var initialSelection: Date?

    @State private var selection: Date?
    @Environment(\.colorScheme) private var scheme

    init(snapshot: SolarSnapshot, now: Date, initialSelection: Date? = nil) {
        self.snapshot = snapshot
        self.now = now
        self.initialSelection = initialSelection
        _selection = State(initialValue: initialSelection)
    }

    var body: some View {
        let calendar = Constants.madridCalendar
        let domain = ChartLayout.solarDomain(curve: snapshot.curve, now: now, calendar: calendar)
        let points = ChartLayout.solarPoints(curve: snapshot.curve, domain: domain)
        let peak = points.map(\.kW).max()
        let yMax = max(2.4, (peak ?? 0) * 1.5)
        let dot = snapshot.isIdle ? nil : points.last
        let selected = nearest(to: selection, in: points)

        Chart {
            ForEach(points) { point in
                AreaMark(x: .value("Time", point.time), y: .value("kW", point.kW))
                    .interpolationMethod(.catmullRom)
                    .foregroundStyle(Theme.Solar.areaFill)
            }
            ForEach(points) { point in
                LineMark(x: .value("Time", point.time), y: .value("kW", point.kW))
                    .interpolationMethod(.catmullRom)
                    .lineStyle(StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
                    .foregroundStyle(Theme.Solar.line)
            }
            if selected == nil, domain.contains(now) {
                RuleMark(x: .value("Now", now))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    .foregroundStyle(Color.primary.opacity(0.30))
            }
            if selected == nil, let dot {
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(560).foregroundStyle(Theme.Solar.amber.opacity(0.16))
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(200).foregroundStyle(Theme.Solar.amber.opacity(0.35))
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(64).foregroundStyle(Theme.Solar.line)
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(18).foregroundStyle(.white)
            }
            if let selected {
                RuleMark(x: .value("Selected", selected.time))
                    .lineStyle(StrokeStyle(lineWidth: 1))
                    .foregroundStyle(Color.primary.opacity(0.30))
                    .annotation(
                        position: .top, spacing: 2,
                        overflowResolution: .init(x: .fit(to: .chart), y: .fit(to: .chart))
                    ) {
                        ChartCallout(
                            title: "\(Format.kW(selected.kW)) kW",
                            detail: Format.time(selected.time))
                    }
                PointMark(x: .value("Time", selected.time), y: .value("kW", selected.kW))
                    .symbolSize(150).foregroundStyle(Theme.Solar.amber.opacity(0.35))
                PointMark(x: .value("Time", selected.time), y: .value("kW", selected.kW))
                    .symbolSize(60).foregroundStyle(Theme.Solar.line)
                PointMark(x: .value("Time", selected.time), y: .value("kW", selected.kW))
                    .symbolSize(16).foregroundStyle(.white)
            }
        }
        .chartXSelection(value: $selection)
        .chartXScale(domain: domain)
        .chartYScale(domain: 0...yMax)
        .chartYAxis {
            AxisMarks(values: [0, yMax / 2]) { _ in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.10))
            }
        }
        .chartXAxis {
            AxisMarks(values: ChartLayout.axisHours(domain: domain, step: 3, calendar: calendar)) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.08))
                AxisValueLabel(anchor: .top) {
                    if let date = value.as(Date.self) {
                        Text(verbatim: Format.hour(date)).font(.system(.caption2, design: .rounded)).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .accessibilityLabel(Text("Production curve", tableName: "App", bundle: .module))
    }

    /// The plotted point closest in time to `date`, within 20 minutes.
    private func nearest(to date: Date?, in points: [SolarPlotPoint]) -> SolarPlotPoint? {
        guard let date, let best = points.min(by: { abs($0.time.timeIntervalSince(date)) < abs($1.time.timeIntervalSince(date)) }),
              abs(best.time.timeIntervalSince(date)) <= 20 * 60 else { return nil }
        return best
    }
}

/// Hourly price bars for one day with average line, "now" highlight and selection.
struct InteractivePriceChart: View {
    let series: PriceSeries
    let points: [PricePoint]
    let now: Date
    var initialSelection: Date?

    @State private var selection: Date?

    init(series: PriceSeries, points: [PricePoint], now: Date, initialSelection: Date? = nil) {
        self.series = series
        self.points = points
        self.now = now
        _selection = State(initialValue: initialSelection)
    }

    var body: some View {
        let calendar = series.region.calendar
        let timeZone = series.region.timeZone
        let base = ChartLayout.priceYDomain(points: points)
        let yDomain = base.lowerBound...(base.upperBound + (base.upperBound - base.lowerBound) * 0.38)
        let xDomain: ClosedRange<Date> = ChartLayout.priceDomain(points: points) ?? (now...now.addingTimeInterval(1))
        let inset = (points.first?.duration ?? 3600) * 0.09
        let average: Double? = points.isEmpty ? nil : points.map(\.eurPerKWh).reduce(0, +) / Double(points.count)
        let selected = selection.flatMap { date in points.first { $0.contains(date) } }

        Chart {
            ForEach(points.filter { $0.contains(now) }) { point in
                RectangleMark(
                    xStart: .value("From", point.start), xEnd: .value("To", point.end),
                    yStart: .value("Floor", yDomain.lowerBound), yEnd: .value("Ceiling", yDomain.upperBound)
                )
                .foregroundStyle(Color.primary.opacity(0.08))
                .cornerRadius(Theme.Radius.bar + 1)
            }
            ForEach(points) { point in
                RectangleMark(
                    xStart: .value("From", point.start.addingTimeInterval(inset)),
                    xEnd: .value("To", point.end.addingTimeInterval(-inset)),
                    yStart: .value("Zero", 0.0),
                    yEnd: .value("€/kWh", point.eurPerKWh)
                )
                .foregroundStyle(Theme.Price.color(fraction: series.fraction(of: point)))
                .opacity(opacity(for: point, selected: selected))
                .cornerRadius(Theme.Radius.bar)
            }
            if let average {
                RuleMark(y: .value("Average", average))
                    .lineStyle(StrokeStyle(lineWidth: 0.75, dash: [2, 3]))
                    .foregroundStyle(Color.primary.opacity(0.30))
            }
            if selected == nil, xDomain.contains(now) {
                RuleMark(x: .value("Now", now))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    .foregroundStyle(Color.primary.opacity(0.35))
            }
            if let selected {
                RuleMark(x: .value("Selected", selected.start.addingTimeInterval(selected.duration / 2)))
                    .lineStyle(StrokeStyle(lineWidth: 0.5))
                    .foregroundStyle(Color.clear)
                    .annotation(
                        position: .top, spacing: 2,
                        overflowResolution: .init(x: .fit(to: .chart), y: .fit(to: .chart))
                    ) {
                        ChartCallout(
                            title: "\(Format.eurPerKWh(selected.eurPerKWh)) €/kWh",
                            detail: "\(Format.time(selected.start, timeZone: timeZone))–\(Format.time(selected.end, timeZone: timeZone))",
                            tint: Theme.Price.color(fraction: series.fraction(of: selected)))
                    }
            }
        }
        .chartXSelection(value: $selection)
        .chartXScale(domain: xDomain)
        .chartYScale(domain: yDomain)
        .chartYAxis(.hidden)
        .chartXAxis {
            AxisMarks(values: ChartLayout.axisHours(points: points, step: 3, calendar: calendar)) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.08))
                AxisValueLabel(anchor: .top) {
                    if let date = value.as(Date.self) {
                        Text(verbatim: Format.hour(date, timeZone: timeZone))
                            .font(.system(.caption2, design: .rounded)).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .accessibilityLabel(Text("Price chart", tableName: "App", bundle: .module))
    }

    private func opacity(for point: PricePoint, selected: PricePoint?) -> Double {
        if let selected { return point.id == selected.id ? 1 : 0.38 }
        return PriceBarState.state(of: point, now: now).opacity
    }
}
#endif
