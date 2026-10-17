import SwiftUI

/// systemMedium: solar on the left, price on the right, thin divider between. A single content fills the width.
/// Compact headers (figure on the icon row) leave most of the height to the charts.
struct MediumWidgetView: View {
    let solar: SolarModel
    let price: PriceModel
    let content: WidgetContent
    let now: Date

    var body: some View {
        switch content {
        case .both:
            HStack(spacing: Theme.Spacing.m) {
                SolarColumn(solar: solar, now: now, figureSize: 34, compactHeader: true)
                Divider().padding(.vertical, Theme.Spacing.xs)
                PriceColumn(price: price, figureSize: 34, compactHeader: true)
            }
        case .solarOnly:
            SolarColumn(solar: solar, now: now, figureSize: 40, compactHeader: true)
        case .priceOnly:
            PriceColumn(price: price, figureSize: 40, compactHeader: true)
        }
    }
}
