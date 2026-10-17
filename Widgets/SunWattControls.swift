import AppIntents
import SunWattKit
import SwiftUI
import WidgetKit

/// Value of a control: already localized text.
struct SunWattControlValue: Sendable {
    var title: String
    var subtitle: String

    init(_ content: ControlContent) {
        title = content.title
        subtitle = content.subtitle
    }
}

/// Cache first, network only when the cache has no price for the current hour.
struct PriceControlProvider: ControlValueProvider {
    var previewValue: SunWattControlValue {
        SunWattControlValue(ControlContent.price(from: SampleData.entry(.day).data, now: SampleData.now))
    }

    func currentValue() async throws -> SunWattControlValue {
        let data = await IntentDataSource().data(for: .price)
        return SunWattControlValue(ControlContent.price(from: data, now: Date()))
    }
}

struct SolarControlProvider: ControlValueProvider {
    var previewValue: SunWattControlValue {
        SunWattControlValue(ControlContent.solar(from: SampleData.entry(.day).data, now: SampleData.now))
    }

    func currentValue() async throws -> SunWattControlValue {
        let data = await IntentDataSource().data(for: .solar)
        return SunWattControlValue(ControlContent.solar(from: data, now: Date()))
    }
}

/// Control Center / Lock Screen: current price. Tapping refreshes the data in place (no app launch).
struct PriceControl: ControlWidget {
    static let kind = "com.ideatic.sunwatt.price-control"

    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Self.kind, provider: PriceControlProvider()) { value in
            ControlWidgetButton(action: RefreshWidgetIntent()) {
                Label(value.title, systemImage: "bolt.fill")
                Text(value.subtitle)
            }
        }
        .displayName("Electricity price")
        .description("Shows the current electricity price. Tap to refresh.")
    }
}

/// Control: current solar production.
struct SolarControl: ControlWidget {
    static let kind = "com.ideatic.sunwatt.solar-control"

    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Self.kind, provider: SolarControlProvider()) { value in
            ControlWidgetButton(action: RefreshWidgetIntent()) {
                Label(value.title, systemImage: "sun.max.fill")
                Text(value.subtitle)
            }
        }
        .displayName("Solar production")
        .description("Shows the current solar production. Tap to refresh.")
    }
}
