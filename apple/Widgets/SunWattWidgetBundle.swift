import AppIntents
import SunWattKit
import WidgetKit
import SwiftUI

/// Tells App Intents to also read the intents defined in SunWattKit.
struct SunWattWidgetsIntentsPackage: AppIntentsPackage {
    static var includedPackages: [any AppIntentsPackage.Type] { [SunWattKitIntentsPackage.self] }
}

@main
struct SunWattWidgetBundle: WidgetBundle {
    var body: some Widget {
        SunWattWidget()
        PriceControl()
        SolarControl()
    }
}
