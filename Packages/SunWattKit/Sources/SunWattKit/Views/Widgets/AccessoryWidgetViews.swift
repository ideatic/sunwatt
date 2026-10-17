import SwiftUI
import WidgetKit

/// accessoryRectangular (lock screen): one row per source, small icons.
struct AccessoryRectangularView: View {
    let solar: SolarModel
    let price: PriceModel
    let content: WidgetContent
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if content != .priceOnly { solarRows }
            if content != .solarOnly { priceRows }
            if content == .priceOnly, let series = price.series, !price.points.isEmpty {
                PriceBarChart(series: series, points: price.points, now: now, style: .mini)
                    .frame(maxHeight: .infinity)
            }
            if content == .solarOnly, solar.isAvailable, let snapshot = solar.snapshot {
                SolarCurveChart(snapshot: snapshot, now: now, style: .mini)
                    .frame(maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var solarRows: some View {
        HStack(spacing: 4) {
            SolarIcon(idle: solar.isIdle, size: 13)
            if !solar.isAvailable {
                wtext("Solar data unavailable")
                    .font(.system(.caption, design: .rounded).weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            } else if solar.isIdle {
                wtext("No production")
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            } else {
                FigureText(value: Format.kW(solar.powerKW), unit: "kW", size: 18)
                    .widgetAccentable()
            }
            Spacer(minLength: 0)
            if let name = solar.plantName { PlantNameLabel(name: name).layoutPriority(-1) }
            if solar.staleSince != nil, let time = solar.dataTime {
                Image(systemName: "clock.arrow.circlepath").imageScale(.small).foregroundStyle(.secondary)
                    .accessibilityLabel(Text(verbatim: Format.time(time)))
            }
        }
        if solar.isAvailable, content == .both {
            wtext("\(Format.kWh(solar.dailyKWh)) kWh today")
                .font(.system(.caption2, design: .rounded).weight(.medium))
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }

    @ViewBuilder
    private var priceRows: some View {
        HStack(spacing: 4) {
            PriceIcon(fraction: price.fraction, size: 13)
            if price.series == nil {
                wtext("Prices unavailable")
                    .font(.system(.caption, design: .rounded).weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            } else {
                FigureText(value: Format.eurPerKWh(price.current?.eurPerKWh), unit: "€/kWh", size: 18)
                    .widgetAccentable()
            }
            Spacer(minLength: 0)
            if price.staleSince != nil {
                Image(systemName: "clock.arrow.circlepath").imageScale(.small).foregroundStyle(.secondary)
            }
        }
    }
}

/// accessoryCircular: gauge of the current price within the day's min...max (power within 0...peak for solar only).
struct AccessoryCircularView: View {
    let solar: SolarModel
    let price: PriceModel
    let content: WidgetContent

    var body: some View {
        if content == .solarOnly {
            solarGauge
        } else {
            priceGauge
        }
    }

    @ViewBuilder
    private var priceGauge: some View {
        if let stats = price.stats, price.series != nil {
            let upper = max(stats.max, stats.min + 0.001)
            let value = min(max(price.current?.eurPerKWh ?? stats.min, stats.min), upper)
            Gauge(value: value, in: stats.min...upper) {
                Image(systemName: Theme.Price.symbol)
            } currentValueLabel: {
                Text(verbatim: Format.eurPerKWh(price.current?.eurPerKWh))
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.6)
            }
            .gaugeStyle(.accessoryCircular)
            .widgetAccentable()
        } else {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var solarGauge: some View {
        if solar.isAvailable {
            let peak = max(solar.snapshot?.peakKW ?? 0, 1)
            let value = min(max(solar.powerKW ?? 0, 0), peak)
            Gauge(value: value, in: 0...peak) {
                Image(systemName: solar.isIdle ? Theme.Solar.nightSymbol : Theme.Solar.symbol)
            } currentValueLabel: {
                Text(verbatim: Format.kW(solar.powerKW))
                    .font(.system(size: 16, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.6)
            }
            .gaugeStyle(.accessoryCircular)
            .widgetAccentable()
        } else {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.secondary)
        }
    }
}
