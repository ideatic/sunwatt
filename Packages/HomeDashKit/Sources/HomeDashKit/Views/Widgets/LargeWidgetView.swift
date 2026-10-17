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

/// Large widget with nothing to show: the message sits where the figures would be and the chart sections keep
/// their place as dimmed skeletons, so the widget keeps its shape.
struct LargeUnavailableView: View {
    let kind: SourceStateView.Kind
    let content: WidgetContent
    var lastUpdate: Date?

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            SourceStateView(kind: kind, lastUpdate: lastUpdate)
                .frame(height: 150)
            if content != .priceOnly {
                section("Production") { SolarSkeleton() }
            }
            if content != .solarOnly {
                section("Price", unit: "€/kWh") { PriceSkeleton() }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func section(_ title: LocalizedStringKey, unit: String? = nil, @ViewBuilder _ chart: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
            ChartCaption(title: title, trailing: unit)
            chart()
        }
        .frame(maxHeight: .infinity)
        .opacity(0.45)
    }
}

/// Placeholder for the production chart: faint grid and a flat dashed curve.
private struct SolarSkeleton: View {
    var body: some View {
        GeometryReader { proxy in
            ZStack {
                ForEach(0..<3, id: \.self) { row in
                    Rectangle().fill(Color.primary.opacity(0.07)).frame(height: 0.5)
                        .position(x: proxy.size.width / 2, y: proxy.size.height * CGFloat(row) / 2)
                }
                Path { path in
                    path.move(to: CGPoint(x: 0, y: proxy.size.height - 1))
                    path.addCurve(
                        to: CGPoint(x: proxy.size.width, y: proxy.size.height - 1),
                        control1: CGPoint(x: proxy.size.width * 0.3, y: proxy.size.height * 0.2),
                        control2: CGPoint(x: proxy.size.width * 0.7, y: proxy.size.height * 0.2))
                }
                .stroke(Color.primary.opacity(0.22), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [4, 4]))
            }
        }
    }
}

/// Placeholder for the price chart: 24 even rounded bars.
private struct PriceSkeleton: View {
    private let heights: [CGFloat] = [0.7, 0.65, 0.6, 0.55, 0.5, 0.5, 0.6, 0.7, 0.75, 0.55, 0.35, 0.2,
                                      0.15, 0.15, 0.15, 0.18, 0.25, 0.5, 0.7, 0.8, 0.8, 0.75, 0.7, 0.65]

    var body: some View {
        GeometryReader { proxy in
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(heights.indices, id: \.self) { index in
                    RoundedRectangle(cornerRadius: Theme.Radius.bar)
                        .fill(Color.primary.opacity(0.18))
                        .frame(height: max(3, proxy.size.height * heights[index]))
                }
            }
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
    }
}
