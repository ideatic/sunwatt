import SwiftUI

/// systemSmall: one headline figure, one supporting line, plus a price pill or a mini chart.
struct SmallWidgetView: View {
    let solar: SolarModel
    let price: PriceModel
    let content: WidgetContent
    let now: Date

    var body: some View {
        switch effectiveContent {
        case .solarOnly: solarLayout(chart: content == .solarOnly)
        case .priceOnly: priceLayout
        case .both: solarLayout(chart: false)
        }
    }

    /// `.both` falls back to whichever half is available.
    private var effectiveContent: WidgetContent {
        guard content == .both else { return content }
        if solar.isAvailable { return .both }
        return price.series != nil ? .priceOnly : .both
    }

    @ViewBuilder
    private func solarLayout(chart: Bool) -> some View {
        if solar.isAvailable {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 4) {
                    SolarIcon(idle: solar.isIdle, size: 18)
                    if let name = solar.plantName { PlantNameLabel(name: name) }
                    Spacer(minLength: 2)
                    DataTimeMarker(time: solar.dataTime, stale: solar.staleSince != nil, showFresh: false)
                }
                .frame(height: 22)
                SolarFigure(solar: solar, size: 40)
                    .padding(.top, 4)
                SolarDailyCaption(solar: solar)
                    .padding(.top, 2)
                if chart, let snapshot = solar.snapshot {
                    SolarCurveChart(snapshot: snapshot, now: now, style: .mini)
                        .padding(.top, 8)
                        .frame(maxHeight: .infinity)
                } else {
                    Spacer(minLength: 4)
                    if content == .both, price.series != nil {
                        PricePill(eurPerKWh: price.current?.eurPerKWh, fraction: price.fraction)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        } else {
            VStack(alignment: .leading, spacing: 2) {
                if let name = solar.plantName { PlantNameLabel(name: name) }
                SourceStateView(kind: solar.stateKind, lastUpdate: solar.snapshot?.fetchedAt)
            }
        }
    }

    @ViewBuilder
    private var priceLayout: some View {
        if price.series != nil {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 4) {
                    PriceIcon(fraction: price.fraction, size: 18)
                    Spacer(minLength: 2)
                    if let since = price.staleSince {
                        StaleBadge(since: since, timeZone: price.timeZone)
                    }
                }
                .frame(height: 22)
                PriceFigure(price: price, size: 36, showsUnit: false)
                    .padding(.top, 4)
                HStack(spacing: 6) {
                    Text(verbatim: "€/kWh")
                        .font(.system(.caption, design: .rounded).weight(.medium))
                        .foregroundStyle(.secondary)
                    LevelLabel(price: price)
                }
                .padding(.top, 2)
                PriceMiniChart(price: price)
                    .padding(.top, 8)
                    .frame(maxHeight: .infinity)
            }
        } else {
            SourceStateView(kind: .priceError)
        }
    }
}
