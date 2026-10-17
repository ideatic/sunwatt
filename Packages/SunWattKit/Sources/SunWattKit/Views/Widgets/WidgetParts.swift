import SwiftUI
import WidgetKit

/// Soft container background. The extension passes it to `.containerBackground(for: .widget)`;
/// the snapshot frame draws it itself.
public struct WidgetBackground: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.widgetRenderingMode) private var mode

    public init() {}

    public var body: some View {
        ZStack {
            Rectangle().fill(Theme.Background.widget(scheme))
            if mode == .fullColor {
                RadialGradient(
                    colors: [Theme.Solar.amber.opacity(scheme == .dark ? 0.10 : 0.12), .clear],
                    center: .topLeading, startRadius: 0, endRadius: 220
                )
            }
        }
    }
}

/// Sun (amber gradient) while producing, dimmed moon when idle.
struct SolarIcon: View {
    let idle: Bool
    var size: CGFloat = 16
    @Environment(\.widgetRenderingMode) private var mode

    var body: some View {
        Image(systemName: idle ? Theme.Solar.nightSymbol : Theme.Solar.symbol)
            .font(.system(size: size, weight: .semibold))
            .symbolRenderingMode(.monochrome)
            .foregroundStyle(WidgetPalette(mode: mode).solarIcon(idle: idle))
            .widgetAccentable(!idle)
    }
}

/// Bolt tinted by the current price level.
struct PriceIcon: View {
    let fraction: Double?
    var size: CGFloat = 16
    @Environment(\.widgetRenderingMode) private var mode

    var body: some View {
        Image(systemName: Theme.Price.symbol)
            .font(.system(size: size, weight: .semibold))
            .foregroundStyle(WidgetPalette(mode: mode).priceColor(fraction: fraction))
            .widgetAccentable()
    }
}

/// Current power as a big figure, or "No production" when idle.
struct SolarFigure: View {
    let solar: SolarModel
    var size: CGFloat
    /// On the icon row of a medium widget "No production" must stay on one line.
    var singleLine = false

    var body: some View {
        if solar.isIdle {
            wtext("No production")
                .font(.system(size: size * 0.5, weight: .bold, design: .rounded))
                .foregroundStyle(.secondary)
                .lineLimit(singleLine ? 1 : 2)
                .minimumScaleFactor(0.7)
                .fixedSize(horizontal: false, vertical: true)
        } else {
            FigureText(value: Format.kW(solar.powerKW), unit: "kW", size: size)
                .widgetAccentable()
        }
    }
}

/// "23,4 kWh today"
struct SolarDailyCaption: View {
    let solar: SolarModel

    var body: some View {
        wtext("\(Format.kWh(solar.dailyKWh)) kWh today")
            .font(.system(.caption, design: .rounded).weight(.medium))
            .monospacedDigit()
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
    }
}

/// Current price as a big figure, or an em dash when the series ran out.
struct PriceFigure: View {
    let price: PriceModel
    var size: CGFloat
    var showsUnit = true

    var body: some View {
        FigureText(value: Format.eurPerKWh(price.current?.eurPerKWh), unit: showsUnit ? "€/kWh" : nil, size: size)
            .widgetAccentable()
    }
}

/// Colored dot + level word ("Cheap"), or "No current price".
struct LevelLabel: View {
    let price: PriceModel
    @Environment(\.widgetRenderingMode) private var mode

    var body: some View {
        HStack(spacing: 5) {
            if let level = price.level {
                Circle()
                    .fill(WidgetPalette(mode: mode).levelColor(level))
                    .frame(width: 7, height: 7)
                wtext(level.label)
            } else {
                wtext("No current price")
            }
        }
        .font(.system(.caption, design: .rounded).weight(.medium))
        .foregroundStyle(.secondary)
        .lineLimit(1)
        .minimumScaleFactor(0.8)
    }
}

/// Small section title above a chart.
struct ChartCaption: View {
    let title: LocalizedStringKey
    var trailing: String?

    var body: some View {
        HStack {
            // Section labels are small caps; units are never uppercased ("€/kWh", not "€/KWH").
            wtext(title).textCase(.uppercase)
            Spacer(minLength: 4)
            if let trailing { Text(verbatim: trailing).textCase(nil).minimumScaleFactor(0.8) }
        }
        .font(.system(.caption2, design: .rounded).weight(.semibold))
        .foregroundStyle(.secondary)
        .lineLimit(1)
    }
}

/// Mini price chart, or a quiet placeholder when there are no points.
struct PriceMiniChart: View {
    let price: PriceModel
    var style: PriceBarChart.Style = .mini

    var body: some View {
        if let series = price.series, !price.points.isEmpty {
            PriceBarChart(series: series, points: price.points, now: price.now, style: style)
        } else {
            Color.clear
        }
    }
}

/// Solar half of a medium widget or large header: header row, figure, daily energy, optional mini curve.
/// Name of the plant a widget shows, when there are several (rotating or chosen in the widget's settings).
struct PlantNameLabel: View {
    let name: String

    var body: some View {
        Text(verbatim: name)
            .font(.system(.caption2, design: .rounded).weight(.semibold))
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .truncationMode(.tail)
    }
}

struct SolarColumn: View {
    let solar: SolarModel
    let now: Date
    var figureSize: CGFloat = 32
    var showsChart = true
    /// Medium widget: the figure sits on the icon row and the daily energy shares a row with the data time,
    /// so the curve gets the height of the two rows saved.
    var compactHeader = false

    var body: some View {
        if solar.isAvailable {
            VStack(alignment: .leading, spacing: 0) {
                if compactHeader { compactRows } else { stackedRows }
                if showsChart, let snapshot = solar.snapshot {
                    SolarCurveChart(snapshot: snapshot, now: now, style: .mini)
                        .padding(.top, compactHeader ? 6 : 8)
                        .frame(maxHeight: .infinity)
                } else {
                    Spacer(minLength: 0)
                }
            }
        } else {
            VStack(alignment: .leading, spacing: 2) {
                if let name = solar.plantName { PlantNameLabel(name: name) }
                SourceStateView(kind: solar.stateKind, compact: true, lastUpdate: solar.snapshot?.fetchedAt)
            }
        }
    }

    @ViewBuilder
    private var stackedRows: some View {
        HStack(spacing: 4) {
            SolarIcon(idle: solar.isIdle)
            if let name = solar.plantName { PlantNameLabel(name: name) }
            Spacer(minLength: 2)
            DataTimeMarker(time: solar.dataTime, stale: solar.staleSince != nil)
        }
        .frame(height: 20)
        SolarFigure(solar: solar, size: figureSize)
            .padding(.top, 2)
        SolarDailyCaption(solar: solar)
            .padding(.top, 1)
    }

    @ViewBuilder
    private var compactRows: some View {
        HStack(alignment: .center, spacing: 4) {
            SolarIcon(idle: solar.isIdle)
            if let name = solar.plantName { PlantNameLabel(name: name) }
            Spacer(minLength: 4)
            SolarFigure(solar: solar, size: figureSize, singleLine: true)
                .layoutPriority(1)
        }
        HStack(spacing: 4) {
            DataTimeMarker(time: solar.dataTime, stale: solar.staleSince != nil)
            Spacer(minLength: 4)
            SolarDailyCaption(solar: solar)
        }
        .padding(.top, 1)
    }
}

/// Price half of a medium widget or large header: header row, figure, level, optional mini bars.
struct PriceColumn: View {
    let price: PriceModel
    var figureSize: CGFloat = 32
    var showsChart = true
    /// Medium widget: the figure sits on the icon row, so the bars get more height (see `SolarColumn`).
    var compactHeader = false

    var body: some View {
        if price.series != nil {
            VStack(alignment: .leading, spacing: 0) {
                if compactHeader { compactRows } else { stackedRows }
                if showsChart {
                    PriceMiniChart(price: price)
                        .padding(.top, compactHeader ? 6 : 8)
                        .frame(maxHeight: .infinity)
                } else {
                    Spacer(minLength: 0)
                }
            }
        } else {
            SourceStateView(kind: .priceError, compact: true, lastUpdate: price.series?.fetchedAt, timeZone: price.timeZone)
        }
    }

    @ViewBuilder
    private var stackedRows: some View {
        HStack(spacing: 4) {
            PriceIcon(fraction: price.fraction)
            Spacer(minLength: 2)
            if let since = price.staleSince {
                StaleBadge(since: since, timeZone: price.timeZone)
            }
        }
        .frame(height: 20)
        PriceFigure(price: price, size: figureSize)
            .padding(.top, 2)
        LevelLabel(price: price)
            .padding(.top, 1)
    }

    @ViewBuilder
    private var compactRows: some View {
        HStack(alignment: .center, spacing: 4) {
            PriceIcon(fraction: price.fraction)
            Spacer(minLength: 4)
            PriceFigure(price: price, size: figureSize)
                .layoutPriority(1)
        }
        // As in the solar column: data age on the left, what the figure means under it on the right.
        HStack(spacing: 4) {
            if let since = price.staleSince {
                StaleBadge(since: since, timeZone: price.timeZone)
            }
            Spacer(minLength: 4)
            LevelLabel(price: price)
        }
        .padding(.top, 1)
    }
}

/// Min / average / max prices of the day with level dots.
struct PriceStatsRow: View {
    let stats: PriceStats
    @Environment(\.widgetRenderingMode) private var mode

    var body: some View {
        HStack(alignment: .top, spacing: Theme.Spacing.m) {
            item("Min", stats.min, color: Theme.Price.green)
            item("Avg", stats.average, color: Theme.Price.yellow)
            item("Max", stats.max, color: Theme.Price.red)
        }
    }

    private func item(_ label: LocalizedStringKey, _ value: Double, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            wtext(label)
                .font(.system(.caption2, design: .rounded).weight(.medium))
                .foregroundStyle(.secondary)
            HStack(spacing: 4) {
                Circle()
                    .fill(mode == .fullColor ? AnyShapeStyle(color) : AnyShapeStyle(HierarchicalShapeStyle.secondary))
                    .frame(width: 6, height: 6)
                Text(verbatim: Format.eurPerKWh(value))
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .monospacedDigit()
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.8)
    }
}
