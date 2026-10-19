import SwiftUI
import WidgetKit

/// Localized text from the package `Watch` catalog.
func watchText(_ key: LocalizedStringKey) -> Text {
    Text(key, tableName: "Watch", bundle: .module)
}

/// The watch app: a solar page per plant first (as in the iPhone app), then the price page, paged vertically with
/// the Digital Crown or a vertical swipe.
///
/// The pager sits at the root and is never rebuilt by the clock: each page has its own minute `TimelineView`. With
/// the whole pager inside a `TimelineView` (build 202610181936) the crown and swipes did nothing on a real watch.
public struct WatchRootView: View {
    /// A page of the watch app; tags are stable across data refreshes (plant ids, not positions).
    enum Page: Hashable {
        case solar(plantID: String)
        case price
    }

    private let entryAt: @MainActor (Date) -> SunWattEntry
    /// True: pages redraw every minute with `entryAt(now)`. False: a fixed entry (snapshots, previews).
    private let ticks: Bool
    @State private var page: Page?

    /// The live app. `entryAt` gives the data to show at a time; `startOnPrice` opens the price page first (used by
    /// the `-price` launch argument for screenshots).
    public init(entryAt: @escaping @MainActor (Date) -> SunWattEntry, startOnPrice: Bool = false) {
        self.entryAt = entryAt
        ticks = true
        _page = State(initialValue: startOnPrice ? .price : nil)
    }

    /// A fixed entry (snapshots and previews).
    public init(entry: SunWattEntry, startOnPrice: Bool = false) {
        entryAt = { _ in entry }
        ticks = false
        _page = State(initialValue: startOnPrice ? .price : nil)
    }

    public var body: some View {
        let data = entryAt(Date()).data
        #if os(watchOS)
        TabView(selection: Binding(get: { page ?? Self.firstPage(data) }, set: { page = $0 })) {
            if data.hasSeveralPlants {
                ForEach(data.allPlants, id: \.id) { plant in
                    solarPage(plantID: plant.id)
                }
            } else {
                // One plant: one fixed solar page, as always.
                solarPage(plantID: data.plantID)
            }
            timed { WatchPricePage(entry: $0) }
                .padding(.trailing, 10)
                .tag(Page.price)
        }
        .tabViewStyle(.verticalPage)
        #else
        timed { WatchPricePage(entry: $0) }
        #endif
    }

    /// The first solar page, or the price page when no solar plant is set up (prices only: no setup prompt first).
    nonisolated static func firstPage(_ data: SunWattData) -> Page {
        if !data.hasSeveralPlants, data.solarStatus == .notConfigured { return .price }
        return .solar(plantID: data.plantID)
    }

    private func solarPage(plantID: String) -> some View {
        timed { entry in
            WatchSolarPage(entry: SunWattEntry(date: entry.date, data: entry.data.showingPlant(id: plantID)))
        }
        .padding(.trailing, 10) // Clear of the vertical page indicator.
        .tag(Page.solar(plantID: plantID))
    }

    /// `content` with the entry for now, redrawn every minute in the live app.
    @ViewBuilder
    private func timed<Content: View>(@ViewBuilder _ content: @escaping (SunWattEntry) -> Content) -> some View {
        if ticks {
            TimelineView(.everyMinute) { context in content(entryAt(context.date)) }
        } else {
            content(entryAt(Date()))
        }
    }
}

/// Current price (big, coloured by level), today's bars with the current hour marked, cheapest next hours. Tapping a
/// bar shows that hour's price instead, like tapping a bar in the iPhone app; it goes back to now on a second tap,
/// a tap on the current hour, or after `selectionTimeout`.
public struct WatchPricePage: View {
    public let entry: SunWattEntry
    @State private var selected: PricePoint?

    static let selectionTimeout: Duration = .seconds(8)

    public init(entry: SunWattEntry) { self.entry = entry }

    public var body: some View {
        let base = PriceModel(entry: entry, window: .wholeDay)
        // A tapped hour that is no longer in today's bars (after midnight) is dropped.
        let tapped = selected.flatMap { point in base.points.contains(point) ? point : nil }
        let price = tapped.map { base.showing($0) } ?? base
        Group {
            if let series = price.series, price.isAvailable {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 4) {
                        PriceIcon(fraction: price.fraction, size: 13)
                        wtext(series.region.sourceLabel)
                            .font(Theme.Font.caption)
                            .foregroundStyle(.secondary)
                        Spacer(minLength: 2)
                        if let since = price.staleSince {
                            StaleBadge(since: since, timeZone: price.timeZone)
                        }
                    }
                    FigureText(value: Format.eurPerKWh(price.current?.eurPerKWh), unit: "€/kWh", size: 30)
                        .foregroundStyle(price.fraction.map { Theme.Price.color(fraction: $0) } ?? Color.primary)
                    HStack(spacing: 5) {
                        if let tapped {
                            // The tapped hour, so the figure is not read as the price now.
                            Text(verbatim: Format.hour(tapped.start, timeZone: series.region.timeZone))
                                .font(.system(.caption, design: .rounded).weight(.bold))
                        }
                        LevelLabel(price: price)
                    }
                    PriceBarChart(series: series, points: price.points, now: entry.date, style: .mini, marked: tapped)
                        .frame(height: 48)
                        .overlay { tapTarget(points: price.points) }
                        .padding(.top, 4)
                    cheapest(series)
                        .padding(.top, 4)
                }
            } else {
                SourceStateView(kind: .priceError, compact: true, lastUpdate: price.series?.fetchedAt, timeZone: price.timeZone)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .task(id: selected) {
            guard selected != nil else { return }
            try? await Task.sleep(for: Self.selectionTimeout)
            if !Task.isCancelled { selected = nil }
        }
    }

    /// Taps over the bars select an hour. Taps only: vertical swipes still page.
    private func tapTarget(points: [PricePoint]) -> some View {
        GeometryReader { geometry in
            Color.clear
                .contentShape(Rectangle())
                .gesture(SpatialTapGesture().onEnded { tap in
                    guard geometry.size.width > 0,
                          let point = Self.point(atFraction: Double(tap.location.x / geometry.size.width), of: points) else { return }
                    // The same bar again, or the current hour: back to now.
                    selected = (point == selected || point.contains(entry.date)) ? nil : point
                })
        }
    }

    /// The bar under a horizontal position (0 = left edge, 1 = right edge) of a chart without axes, whose x domain is
    /// `ChartLayout.priceDomain(points:)`.
    nonisolated static func point(atFraction fraction: Double, of points: [PricePoint]) -> PricePoint? {
        guard let domain = ChartLayout.priceDomain(points: points) else { return nil }
        let span = domain.upperBound.timeIntervalSince(domain.lowerBound)
        let date = domain.lowerBound.addingTimeInterval(span * min(max(fraction, 0), 0.9999))
        return points.first { $0.contains(date) }
    }

    @ViewBuilder
    private func cheapest(_ series: PriceSeries) -> some View {
        let hours = series.cheapestUpcoming(from: entry.date, count: 3)
        if !hours.isEmpty {
            let contexts = PriceDayContexts(series: series, points: hours)
            VStack(alignment: .leading, spacing: 2) {
                watchText("Cheapest hours")
                    .textCase(.uppercase)
                    .font(.system(.caption2, design: .rounded).weight(.semibold))
                    .foregroundStyle(.secondary)
                HStack(spacing: 4) {
                    ForEach(hours) { point in
                        chip(point, series: series, contexts: contexts)
                    }
                }
            }
        }
    }

    private func chip(_ point: PricePoint, series: PriceSeries, contexts: PriceDayContexts) -> some View {
        let isNow = point.contains(entry.date)
        return VStack(spacing: 0) {
            Group {
                if isNow {
                    watchText("Now")
                } else {
                    Text(verbatim: Format.hour(point.start, timeZone: series.region.timeZone))
                }
            }
            .font(.system(size: 11, weight: .semibold, design: .rounded))
            .foregroundStyle(.secondary)
            Text(verbatim: Format.eurPerKWh(point.eurPerKWh))
                .font(.system(size: 12, weight: .bold, design: .rounded))
                .monospacedDigit()
                .minimumScaleFactor(0.7)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 3)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Theme.Price.color(fraction: contexts.fraction(of: point)).opacity(0.22))
        )
    }
}

/// Current power, today's energy, production curve and data time.
/// Production now (big), today's energy and curve. Tapping the curve shows the power at that time instead, like the
/// price page; it goes back to now on a second tap of the same point, a tap on the latest point, or after
/// `WatchPricePage.selectionTimeout`.
public struct WatchSolarPage: View {
    public let entry: SunWattEntry
    @State private var selected: CurvePoint?

    /// `selected` starts the page with a time already tapped (snapshots).
    public init(entry: SunWattEntry, selected: CurvePoint? = nil) {
        self.entry = entry
        _selected = State(initialValue: selected)
    }

    public var body: some View {
        let solar = SolarModel(entry: entry)
        Group {
            if solar.isAvailable, let snapshot = solar.snapshot {
                // A tapped point that is no longer in the curve (new day, new data) is dropped.
                let tapped = selected.flatMap { point in snapshot.curve.contains(point) ? point : nil }
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 4) {
                        SolarIcon(idle: solar.isIdle && tapped == nil, size: 13)
                        // Several plants: the plant's name.
                        Group {
                            if let name = solar.plantName { Text(verbatim: name).lineLimit(1) } else { wtext("Production") }
                        }
                        .font(Theme.Font.caption)
                        .foregroundStyle(.secondary)
                        Spacer(minLength: 2)
                        DataTimeMarker(time: solar.dataTime, stale: solar.staleSince != nil)
                    }
                    if let tapped, let kW = tapped.kW {
                        FigureText(value: Format.kW(kW), unit: "kW", size: 32)
                            .foregroundStyle(Theme.Solar.amber)
                        HStack(spacing: 5) {
                            // The tapped time, so the figure is not read as the power now.
                            Text(verbatim: Format.time(tapped.time))
                                .font(.system(.caption, design: .rounded).weight(.bold))
                            SolarDailyCaption(solar: solar)
                        }
                    } else {
                        SolarFigure(solar: solar, size: 32)
                            .foregroundStyle(solar.isIdle ? AnyShapeStyle(.secondary) : AnyShapeStyle(Theme.Solar.amber))
                        SolarDailyCaption(solar: solar)
                    }
                    SolarCurveChart(snapshot: snapshot, now: entry.date, style: .mini, marked: tapped)
                        .overlay { tapTarget(snapshot: snapshot) }
                        .frame(maxHeight: .infinity)
                        .padding(.top, 6)
                        .padding(.bottom, 8)
                }
            } else if solar.phase == .notConfigured {
                VStack(spacing: 4) {
                    Image(systemName: "iphone.and.arrow.forward")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(Theme.Solar.amber)
                    watchText("Set up SunWatt on your iPhone")
                        .font(.system(.footnote, design: .rounded).weight(.semibold))
                        .multilineTextAlignment(.center)
                    watchText("Prices show Spain until then")
                        .font(Theme.Font.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                SourceStateView(kind: solar.stateKind, compact: true, lastUpdate: solar.snapshot?.fetchedAt)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .task(id: selected) {
            guard selected != nil else { return }
            try? await Task.sleep(for: WatchPricePage.selectionTimeout)
            if !Task.isCancelled { selected = nil }
        }
    }

    /// Taps over the curve select a time. Taps only: vertical swipes still page.
    private func tapTarget(snapshot: SolarSnapshot) -> some View {
        GeometryReader { geometry in
            Color.clear
                .contentShape(Rectangle())
                .gesture(SpatialTapGesture().onEnded { tap in
                    guard geometry.size.width > 0 else { return }
                    let point = Self.point(atFraction: Double(tap.location.x / geometry.size.width),
                                           curve: snapshot.curve, now: entry.date)
                    // Nothing measured there, the same point again, or the latest point: back to now.
                    let latest = snapshot.curve.last { $0.kW != nil }
                    selected = (point == nil || point == selected || point == latest) ? nil : point
                })
        }
    }

    /// The measured point nearest to a horizontal position (0 = left edge, 1 = right edge) of the mini chart, whose x
    /// domain is `ChartLayout.solarDomain`. Nil after the last measurement (the future) or without data.
    nonisolated static func point(atFraction fraction: Double, curve: [CurvePoint], now: Date) -> CurvePoint? {
        // Only points the chart draws (inside its domain).
        let domain = ChartLayout.solarDomain(curve: curve, now: now)
        let measured = curve.filter { $0.kW != nil && domain.contains($0.time) }
        guard let last = measured.last else { return nil }
        let span = domain.upperBound.timeIntervalSince(domain.lowerBound)
        let date = domain.lowerBound.addingTimeInterval(span * min(max(fraction, 0), 1))
        // A tap well right of the curve's end is on empty chart, not on the latest point.
        if date.timeIntervalSince(last.time) > 15 * 60 { return nil }
        return measured.min { abs($0.time.timeIntervalSince(date)) < abs($1.time.timeIntervalSince(date)) }
    }
}

/// Draws a watch screen or complication the way the watch would (rounded screen, black background) so it can be
/// rendered with `ImageRenderer`.
public struct WatchSnapshotView: View {
    public enum Kind: Sendable {
        case pricePage
        case solarPage
        /// The solar page with a time already tapped.
        case solarPageTapped(CurvePoint)
        case complication(WatchComplicationFamily)
        case solarComplication(WatchComplicationFamily)
    }

    public let entry: SunWattEntry
    public let kind: Kind
    public let scheme: ColorScheme
    public let locale: Locale
    public let renderingMode: WidgetRenderingMode

    public init(entry: SunWattEntry, kind: Kind, scheme: ColorScheme = .dark, locale: Locale = Format.locale,
                renderingMode: WidgetRenderingMode = .fullColor) {
        self.entry = entry
        self.kind = kind
        self.scheme = scheme
        self.locale = locale
        self.renderingMode = renderingMode
    }

    /// Apple Watch 46 mm screen in points.
    public static let screenSize = CGSize(width: 208, height: 248)
    static let padding: CGFloat = 20

    public static func imageSize(for kind: Kind) -> CGSize {
        switch kind {
        case .pricePage, .solarPage, .solarPageTapped:
            CGSize(width: screenSize.width + padding * 2, height: screenSize.height + padding * 2)
        case .complication(let family), .solarComplication(let family):
            CGSize(width: max(family.size.width, 120) + 40, height: max(family.size.height, 70) + 40)
        }
    }

    public var body: some View {
        let size = Self.imageSize(for: kind)
        Group {
            switch kind {
            case .pricePage:
                screen(WatchPricePage(entry: entry))
            case .solarPage:
                screen(WatchSolarPage(entry: entry))
            case .solarPageTapped(let point):
                screen(WatchSolarPage(entry: entry, selected: point))
            case .complication(let family):
                complication(family, solar: false)
            case .solarComplication(let family):
                complication(family, solar: true)
            }
        }
        .frame(width: size.width, height: size.height)
        .background(scheme == .dark ? Color(white: 0.04) : Color(white: 0.82))
        .environment(\.colorScheme, scheme)
        .environment(\.locale, locale)
        .environment(\.widgetRenderingMode, renderingMode)
    }

    private func screen(_ page: some View) -> some View {
        page
            .padding(.horizontal, 12)
            .padding(.top, 34)
            .padding(.bottom, 10)
            .frame(width: Self.screenSize.width, height: Self.screenSize.height)
            .background(scheme == .dark ? Color.black : Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 44, style: .continuous))
            .overlay(alignment: .topTrailing) {
                Text(verbatim: "10:09")
                    .font(.system(size: 14, weight: .semibold, design: .rounded))
                    .padding(.top, 10).padding(.trailing, 22)
            }
    }

    @ViewBuilder
    private func complication(_ family: WatchComplicationFamily, solar: Bool) -> some View {
        let tile = Color.white.opacity(scheme == .dark ? 0.16 : 0.55)
        let content = Group {
            if solar {
                WatchSolarComplicationView(entry: entry, family: family)
            } else {
                WatchComplicationView(entry: entry, family: family)
            }
        }
        switch family {
        case .circular:
            content
                .padding(2)
                .frame(width: family.size.width, height: family.size.height)
                .background(Circle().fill(tile))
        case .rectangular:
            content
                .padding(.horizontal, 8).padding(.vertical, 5)
                .frame(width: family.size.width, height: family.size.height)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(tile))
        case .inline:
            content
                .font(.system(size: 14, weight: .medium))
                .frame(width: family.size.width, height: family.size.height)
        case .corner:
            // Stand-in for the system corner: content plus an arc gauge label (the real arc is drawn by watchOS).
            ZStack {
                if solar {
                    WatchArc(fraction: WatchSolarGauge(entry: entry)?.fraction,
                             color: Theme.Solar.orange, tinted: renderingMode == .fullColor)
                } else {
                    let gauge = WatchCornerGauge(entry: entry)
                    WatchArc(fraction: gauge.map { $0.upper > $0.lower ? ($0.value - $0.lower) / ($0.upper - $0.lower) : 0.5 },
                             color: Theme.Price.color(fraction: gauge?.fraction ?? 0.5), tinted: renderingMode == .fullColor)
                }
                content
            }
            .frame(width: family.size.width, height: family.size.height)
        }
    }
}

/// Snapshot stand-in for the gauge `widgetLabel` of the corner complications.
struct WatchArc: View {
    let fraction: Double?
    let color: Color
    var tinted = true

    var body: some View {
        if let fraction {
            ZStack {
                Circle().trim(from: 0.55, to: 0.95)
                    .stroke(Color.primary.opacity(0.2), style: StrokeStyle(lineWidth: 5, lineCap: .round))
                Circle().trim(from: 0.55, to: 0.55 + max(0.004, 0.4 * min(max(fraction, 0), 1)))
                    .stroke(tinted ? color : Color.primary, style: StrokeStyle(lineWidth: 5, lineCap: .round))
            }
            .padding(2)
        }
    }
}
