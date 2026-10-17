import SwiftUI

/// The SunWatt widget content for one timeline entry. Takes everything as explicit input so the extension,
/// the snapshot harness and tests render the same view. Layout assumes the system's content margins are applied
/// outside (`SunWattFamily.contentInset`); the background is applied by the caller (`WidgetBackground`).
public struct SunWattWidgetView: View {
    public let entry: SunWattEntry
    public let family: SunWattFamily
    public let content: WidgetContent
    public let window: PriceWindow

    public init(entry: SunWattEntry, family: SunWattFamily, content: WidgetContent = .both, window: PriceWindow = .wholeDay) {
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
        // A circular gauge is smaller than its slot: centred, or it sits in the top-left of the circle.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: family == .accessoryCircular ? .center : .topLeading)
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
        let solarKind = solar.stateKind
        switch content {
        case .solarOnly: return solarDown ? solarKind : nil
        case .priceOnly: return priceDown ? .priceError : nil
        case .both:
            guard solarDown, priceDown else { return nil }
            return solar.phase == .notConfigured ? .priceError : .unavailable
        }
    }
}
