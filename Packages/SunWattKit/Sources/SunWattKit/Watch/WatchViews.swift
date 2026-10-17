import SwiftUI

/// Localized text from the package `Watch` catalog.
func watchText(_ key: LocalizedStringKey) -> Text {
    Text(key, tableName: "Watch", bundle: .module)
}

/// The watch app: price page and solar page, paged vertically with the Digital Crown.
public struct WatchRootView: View {
    public let entry: SunWattEntry
    @State private var page: Int

    /// `startOnSolar` opens the solar page first (used by the `-solar` launch argument for screenshots).
    public init(entry: SunWattEntry, startOnSolar: Bool = false) {
        self.entry = entry
        _page = State(initialValue: startOnSolar ? 1 : 0)
    }

    public var body: some View {
        #if os(watchOS)
        TabView(selection: $page) {
            // Trailing space keeps content clear of the vertical page indicator.
            WatchPricePage(entry: entry).padding(.trailing, 10).tag(0)
            WatchSolarPage(entry: entry).padding(.trailing, 10).tag(1)
        }
        .tabViewStyle(.verticalPage)
        #else
        WatchPricePage(entry: entry)
        #endif
    }
}

/// Current price (big, coloured by level), today's bars with the current hour marked, cheapest next hours.
public struct WatchPricePage: View {
    public let entry: SunWattEntry

    public init(entry: SunWattEntry) { self.entry = entry }

    public var body: some View {
        let price = PriceModel(entry: entry, window: .wholeDay)
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
                    LevelLabel(price: price)
                    PriceBarChart(series: series, points: price.points, now: entry.date, style: .mini)
                        .frame(height: 48)
                        .padding(.top, 4)
                    cheapest(series)
                        .padding(.top, 4)
                }
            } else {
                SourceStateView(kind: .priceError, compact: true, lastUpdate: price.series?.fetchedAt, timeZone: price.timeZone)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    @ViewBuilder
    private func cheapest(_ series: PriceSeries) -> some View {
        let hours = series.cheapestUpcoming(from: entry.date, count: 3)
        if !hours.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                watchText("Cheapest hours")
                    .textCase(.uppercase)
                    .font(.system(.caption2, design: .rounded).weight(.semibold))
                    .foregroundStyle(.secondary)
                HStack(spacing: 4) {
                    ForEach(hours) { point in
                        chip(point, series: series)
                    }
                }
            }
        }
    }

    private func chip(_ point: PricePoint, series: PriceSeries) -> some View {
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
                .fill(Theme.Price.color(fraction: series.fraction(of: point)).opacity(0.22))
        )
    }
}

/// Current power, today's energy, production curve and data time.
public struct WatchSolarPage: View {
    public let entry: SunWattEntry

    public init(entry: SunWattEntry) { self.entry = entry }

    public var body: some View {
        let solar = SolarModel(entry: entry)
        Group {
            if solar.isAvailable, let snapshot = solar.snapshot {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 4) {
                        SolarIcon(idle: solar.isIdle, size: 13)
                        wtext("Production")
                            .font(Theme.Font.caption)
                            .foregroundStyle(.secondary)
                        Spacer(minLength: 2)
                        DataTimeMarker(time: solar.dataTime, stale: solar.staleSince != nil)
                    }
                    SolarFigure(solar: solar, size: 32)
                        .foregroundStyle(solar.isIdle ? AnyShapeStyle(.secondary) : AnyShapeStyle(Theme.Solar.amber))
                    SolarDailyCaption(solar: solar)
                    SolarCurveChart(snapshot: snapshot, now: entry.date, style: .mini)
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
                SourceStateView(kind: .solarError, compact: true, lastUpdate: solar.snapshot?.fetchedAt)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// Draws a watch screen or complication the way the watch would (rounded screen, black background) so it can be
/// rendered with `ImageRenderer`.
public struct WatchSnapshotView: View {
    public enum Kind: Sendable {
        case pricePage
        case solarPage
        case complication(WatchComplicationFamily)
    }

    public let entry: SunWattEntry
    public let kind: Kind
    public let scheme: ColorScheme
    public let locale: Locale

    public init(entry: SunWattEntry, kind: Kind, scheme: ColorScheme = .dark, locale: Locale = Format.locale) {
        self.entry = entry
        self.kind = kind
        self.scheme = scheme
        self.locale = locale
    }

    /// Apple Watch 46 mm screen in points.
    public static let screenSize = CGSize(width: 208, height: 248)
    static let padding: CGFloat = 20

    public static func imageSize(for kind: Kind) -> CGSize {
        switch kind {
        case .pricePage, .solarPage:
            CGSize(width: screenSize.width + padding * 2, height: screenSize.height + padding * 2)
        case .complication(let family):
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
            case .complication(let family):
                complication(family)
            }
        }
        .frame(width: size.width, height: size.height)
        .background(scheme == .dark ? Color(white: 0.04) : Color(white: 0.82))
        .environment(\.colorScheme, scheme)
        .environment(\.locale, locale)
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
    private func complication(_ family: WatchComplicationFamily) -> some View {
        let tile = Color.white.opacity(scheme == .dark ? 0.16 : 0.55)
        switch family {
        case .circular:
            WatchComplicationView(entry: entry, family: family)
                .padding(2)
                .frame(width: family.size.width, height: family.size.height)
                .background(Circle().fill(tile))
        case .rectangular:
            WatchComplicationView(entry: entry, family: family)
                .padding(.horizontal, 8).padding(.vertical, 5)
                .frame(width: family.size.width, height: family.size.height)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(tile))
        case .inline:
            WatchComplicationView(entry: entry, family: family)
                .font(.system(size: 14, weight: .medium))
                .frame(width: family.size.width, height: family.size.height)
        case .corner:
            // Stand-in for the system corner: content plus an arc gauge label (the real arc is drawn by watchOS).
            ZStack {
                WatchCornerGaugeArc(entry: entry)
                WatchComplicationView(entry: entry, family: family)
            }
            .frame(width: family.size.width, height: family.size.height)
        }
    }
}

/// Snapshot stand-in for the gauge `widgetLabel` of the corner complication.
struct WatchCornerGaugeArc: View {
    let entry: SunWattEntry

    var body: some View {
        if let gauge = WatchCornerGauge(entry: entry) {
            let t = gauge.upper > gauge.lower ? (gauge.value - gauge.lower) / (gauge.upper - gauge.lower) : 0.5
            ZStack {
                Circle().trim(from: 0.55, to: 0.95)
                    .stroke(Color.primary.opacity(0.2), style: StrokeStyle(lineWidth: 5, lineCap: .round))
                Circle().trim(from: 0.55, to: 0.55 + max(0.004, 0.4 * t))
                    .stroke(Theme.Price.color(fraction: gauge.fraction), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            }
            .rotationEffect(.degrees(0))
            .padding(2)
        }
    }
}
