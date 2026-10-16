import SwiftUI

/// systemLarge: header with both figures, full production curve, full price chart, footer with stats and update time.
struct LargeWidgetView: View {
    let solar: SolarModel
    let price: PriceModel
    let content: WidgetContent
    let now: Date
    let updated: Date?

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            header
                .frame(height: 76)
            if content != .priceOnly { solarChart }
            if content != .solarOnly { priceChart }
            footer
        }
    }

    @ViewBuilder
    private var header: some View {
        switch content {
        case .both:
            HStack(spacing: Theme.Spacing.m) {
                SolarColumn(solar: solar, now: now, figureSize: 34, showsChart: false)
                Divider().padding(.vertical, Theme.Spacing.xs)
                PriceColumn(price: price, figureSize: 34, showsChart: false)
            }
        case .solarOnly:
            SolarColumn(solar: solar, now: now, figureSize: 40, showsChart: false)
        case .priceOnly:
            PriceColumn(price: price, figureSize: 40, showsChart: false)
        }
    }

    private var solarChart: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
            ChartCaption(title: "Production")
            if solar.isAvailable, let snapshot = solar.snapshot {
                SolarCurveChart(snapshot: snapshot, now: now, style: .full)
            } else {
                Color.clear
            }
        }
        .frame(maxHeight: .infinity)
    }

    private var priceChart: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
            ChartCaption(title: "Price", trailing: "€/kWh")
            PriceMiniChart(price: price, style: .full)
        }
        .frame(maxHeight: .infinity)
    }

    private var footer: some View {
        HStack(alignment: .bottom) {
            if content != .solarOnly, let stats = price.stats {
                PriceStatsRow(stats: stats)
            } else if let peak = solar.snapshot?.peakKW {
                peakItem(peak)
            }
            Spacer(minLength: Theme.Spacing.s)
            VStack(alignment: .trailing, spacing: 1) {
                if content != .solarOnly, price.series != nil {
                    wtext(price.region.sourceLabel)
                        .textCase(.uppercase)
                }
                if let updated {
                    wtext("Updated \(Format.time(updated))")
                        .monospacedDigit()
                }
            }
            .font(.system(.caption2, design: .rounded).weight(.medium))
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
        }
        .padding(.top, Theme.Spacing.xs)
    }

    private func peakItem(_ peak: Double) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            wtext("Peak")
                .font(.system(.caption2, design: .rounded).weight(.medium))
                .foregroundStyle(.secondary)
            Text(verbatim: "\(Format.kW(peak)) kW")
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .monospacedDigit()
        }
    }
}
