import SwiftUI

/// systemMedium: solar on the left, price on the right, thin divider between. A single content fills the width.
struct MediumWidgetView: View {
    let solar: SolarModel
    let price: PriceModel
    let content: WidgetContent
    let now: Date

    var body: some View {
        switch content {
        case .both:
            HStack(spacing: Theme.Spacing.m) {
                SolarColumn(solar: solar, now: now, figureSize: 34)
                Divider().padding(.vertical, Theme.Spacing.xs)
                PriceColumn(price: price, figureSize: 34)
            }
        case .solarOnly:
            SolarColumn(solar: solar, now: now, figureSize: 40)
        case .priceOnly:
            PriceColumn(price: price, figureSize: 40)
        }
    }
}
