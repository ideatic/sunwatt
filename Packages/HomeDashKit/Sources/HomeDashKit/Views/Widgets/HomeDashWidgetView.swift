import SwiftUI

/// The HomeDash widget content for one timeline entry. Takes everything as explicit input so the extension,
/// the snapshot harness and tests render the same view. Layout assumes the system's content margins are applied
/// outside (`HomeDashFamily.contentInset`); the background is applied by the caller (`WidgetBackground`).
public struct HomeDashWidgetView: View {
    public let entry: HomeDashEntry
    public let family: HomeDashFamily
    public let content: WidgetContent
    public let window: PriceWindow

    public init(entry: HomeDashEntry, family: HomeDashFamily, content: WidgetContent = .both, window: PriceWindow = .wholeDay) {
        self.entry = entry
        self.family = family
        self.content = content
        self.window = window
    }

    public var body: some View {
        let solar = SolarModel(entry: entry)
        let price = PriceModel(entry: entry, window: window)
        Group {
            switch family {
            case .accessoryRectangular:
                AccessoryRectangularView(solar: solar, price: price, content: content, now: entry.date)
            case .accessoryCircular:
                AccessoryCircularView(solar: solar, price: price, content: content)
            default:
                if let kind = fullStateKind(solar: solar, price: price) {
                    if family == .large {
                        LargeUnavailableView(kind: kind, content: content, lastUpdate: entry.data.lastUpdated)
                    } else {
                        SourceStateView(kind: kind, lastUpdate: entry.data.lastUpdated)
                    }
                } else {
                    systemBody(solar: solar, price: price)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    @ViewBuilder
    private func systemBody(solar: SolarModel, price: PriceModel) -> some View {
        switch family {
        case .small:
            SmallWidgetView(solar: solar, price: price, content: content, now: entry.date)
        case .medium:
            MediumWidgetView(solar: solar, price: price, content: content, now: entry.date)
        default:
            LargeWidgetView(solar: solar, price: price, content: content, now: entry.date, updated: entry.data.lastUpdated)
        }
    }

    /// When every source the widget shows is unavailable: one friendly message for the whole widget.
    private func fullStateKind(solar: SolarModel, price: PriceModel) -> SourceStateView.Kind? {
        let solarDown = !solar.isAvailable
        let priceDown = price.series == nil
        let solarKind: SourceStateView.Kind = solar.phase == .notConfigured ? .notConfigured : .solarError
        switch content {
        case .solarOnly: return solarDown ? solarKind : nil
        case .priceOnly: return priceDown ? .priceError : nil
        case .both:
            guard solarDown, priceDown else { return nil }
            return solar.phase == .notConfigured ? .priceError : .unavailable
        }
    }
}
