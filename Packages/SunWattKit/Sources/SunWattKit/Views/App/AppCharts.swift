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
        // Same Y scale as the widget `.full` chart, plus headroom for the selection callout.
        let yMax = ChartLayout.solarYMax(peak: peak)
        let callout = yMax * 1.18
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
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                    .foregroundStyle(Theme.Solar.line)
            }
            if selected == nil, domain.contains(now) {
                RuleMark(x: .value("Now", now))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    .foregroundStyle(Color.primary.opacity(0.35))
            }
            if selected == nil, let peak = points.max(by: { $0.kW < $1.kW }), peak.kW > 0.05,
               dot.map({ $0.time.timeIntervalSince(peak.time) > 90 * 60 }) ?? true {
                PointMark(x: .value("Time", peak.time), y: .value("kW", peak.kW))
                    .symbolSize(0)
                    .annotation(position: .top, spacing: 2) {
                        Text(verbatim: "\(Format.kW(peak.kW)) kW")
                            .font(.system(.caption2, design: .rounded).weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(.secondary)
                    }
            }
            if selected == nil, let dot {
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(520).foregroundStyle(Theme.Solar.amber.opacity(0.16))
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(190).foregroundStyle(Theme.Solar.amber.opacity(0.35))
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(60).foregroundStyle(Theme.Solar.line)
                PointMark(x: .value("Time", dot.time), y: .value("kW", dot.kW))
                    .symbolSize(16).foregroundStyle(.white)
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
        .chartYScale(domain: 0...callout)
        .chartYAxis {
            let ticks = ChartLayout.solarTicks(yMax: yMax)
            AxisMarks(position: .trailing, values: ticks) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.07))
                AxisValueLabel(anchor: .leading) {
                    if let v = value.as(Double.self) {
                        Text(verbatim: ChartLayout.axisNumber(v, decimals: 1) + (v == ticks.last && v > 0 ? " kW" : ""))
                            .font(.system(.caption2, design: .rounded))
                            .monospacedDigit()
                            .foregroundStyle(.secondary)
                            .lineLimit(1).frame(width: 46, alignment: .trailing)
                    }
                }
            }
        }
        .chartXAxis {
            AxisMarks(values: ChartLayout.axisHours(domain: domain, step: 3, calendar: calendar)) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.07))
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

/// Hourly price bars for one day with average line, outlined "now" bar and selection. Same look as the
/// widget `.full` chart (`PriceBarChart`): current bar at full opacity with an outline and a marker above it,
/// dimmed past bars (neutral blend in dark mode), minimum bar height, trailing Y axis from `ChartLayout`.
struct InteractivePriceChart: View {
    let series: PriceSeries
    let points: [PricePoint]
    let now: Date
    var initialSelection: Date?

    @State private var selection: Date?
    @Environment(\.colorScheme) private var scheme

    init(series: PriceSeries, points: [PricePoint], now: Date, initialSelection: Date? = nil) {
        self.series = series
        self.points = points
        self.now = now
        _selection = State(initialValue: initialSelection)
    }

    var body: some View {
        let calendar = series.region.calendar
        let timeZone = series.region.timeZone
        let baseDomain = ChartLayout.priceYDomain(points: points)
        let span = baseDomain.upperBound - baseDomain.lowerBound
        let current = points.first { $0.contains(now) }
        let selected = selection.flatMap { date in points.first { $0.contains(date) } }
        // Headroom above the tallest bar for the "now" marker and the selection callout.
        let headroom = selected == nil ? (current == nil ? 0 : 0.14) : 0.38
        let yDomain = baseDomain.lowerBound...(baseDomain.upperBound + span * headroom)
        let minHeight = span * Theme.Price.minBarFractionFull
        let xDomain: ClosedRange<Date> = ChartLayout.priceDomain(points: points) ?? (now...now.addingTimeInterval(1))
        let inset = (points.first?.duration ?? 3600) * 0.09
        let halo = (points.first?.duration ?? 3600) * 0.025
        let average: Double? = points.isEmpty ? nil : points.map(\.eurPerKWh).reduce(0, +) / Double(points.count)
        let outlined = selected ?? current
        let ticks = ChartLayout.priceTicks(yMax: baseDomain.upperBound)
        let contexts = PriceDayContexts(series: series, points: points)

        Chart {
            if let outlined {
                RectangleMark(
                    xStart: .value("From", outlined.start.addingTimeInterval(inset - halo)),
                    xEnd: .value("To", outlined.end.addingTimeInterval(-(inset - halo))),
                    yStart: .value("Zero", min(0, barEnd(outlined, minHeight)) - span * 0.012),
                    yEnd: .value("€/kWh", max(0, barEnd(outlined, minHeight)) + span * 0.012)
                )
                .foregroundStyle(Color.primary.opacity(0.6))
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
                .foregroundStyle(barColor(point, state: state, contexts: contexts))
                .opacity(opacity(for: point, state: state, selected: selected))
                .cornerRadius(Theme.Radius.bar)
            }
            if selected == nil, let current {
                PointMark(
                    x: .value("Now", current.start.addingTimeInterval(current.duration / 2)),
                    y: .value("Top", max(0, barEnd(current, minHeight)) + span * 0.012)
                )
                .symbolSize(0)
                .annotation(position: .top, spacing: 1) {
                    Image(systemName: "arrowtriangle.down.fill")
                        .font(.system(size: 7))
                        .foregroundStyle(Color.primary)
                }
            }
            if let average {
                RuleMark(y: .value("Average", average))
                    .lineStyle(StrokeStyle(lineWidth: 0.75, dash: [2, 3]))
                    .foregroundStyle(Color.primary.opacity(0.30))
            }
            if let selected {
                PointMark(
                    x: .value("Selected", selected.start.addingTimeInterval(selected.duration / 2)),
                    y: .value("Top", max(0, barEnd(selected, minHeight)) + span * 0.012)
                )
                .symbolSize(0)
                .annotation(
                    position: .top, spacing: 3,
                    overflowResolution: .init(x: .fit(to: .chart), y: .fit(to: .chart))
                ) {
                    ChartCallout(
                        title: "\(Format.eurPerKWh(selected.eurPerKWh)) €/kWh",
                        detail: "\(Format.time(selected.start, timeZone: timeZone))–\(Format.time(selected.end, timeZone: timeZone))",
                        tint: Theme.Price.color(fraction: contexts.fraction(of: selected)))
                }
            }
        }
        .chartXSelection(value: $selection)
        .chartXScale(domain: xDomain)
        .chartYScale(domain: yDomain)
        .chartYAxis {
            AxisMarks(position: .trailing, values: ticks) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.07))
                AxisValueLabel(anchor: .leading) {
                    // No "0" label: the baseline grid line already shows it.
                    if let v = value.as(Double.self), v != 0 {
                        Text(verbatim: ChartLayout.axisNumber(v, decimals: 2) + (v == ticks.last && v > 0 ? " €" : ""))
                            .font(.system(.caption2, design: .rounded))
                            .monospacedDigit()
                            .foregroundStyle(.secondary)
                            .lineLimit(1).frame(width: 46, alignment: .trailing)
                    }
                }
            }
        }
        .chartXAxis {
            AxisMarks(values: ChartLayout.axisHours(points: points, step: 3, calendar: calendar)) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5)).foregroundStyle(Color.primary.opacity(0.07))
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

    /// Bar end value with a minimum visible height (very cheap hours keep a sliver of their color).
    private func barEnd(_ point: PricePoint, _ minHeight: Double) -> Double {
        let value = point.eurPerKWh
        return value >= 0 ? max(value, minHeight) : min(value, -minHeight)
    }

    private func barColor(_ point: PricePoint, state: PriceBarState, contexts: PriceDayContexts) -> Color {
        let base = Theme.Price.color(fraction: contexts.fraction(of: point))
        return state == .past ? Theme.Price.pastColor(base, scheme: scheme) : base
    }

    private func opacity(for point: PricePoint, state: PriceBarState, selected: PricePoint?) -> Double {
        if let selected { return point.id == selected.id ? 1 : 0.38 }
        return state == .past ? Theme.Price.pastOpacity(for: scheme) : state.opacity
    }
}
#endif
