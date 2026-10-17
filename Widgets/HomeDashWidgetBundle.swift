import AppIntents
import HomeDashKit
import WidgetKit
import SwiftUI

/// Tells App Intents to also read the intents defined in HomeDashKit.
struct HomeDashWidgetsIntentsPackage: AppIntentsPackage {
    static var includedPackages: [any AppIntentsPackage.Type] { [HomeDashKitIntentsPackage.self] }
}

@main
struct HomeDashWidgetBundle: WidgetBundle {
    var body: some Widget {
        HomeDashWidget()
        PriceControl()
        SolarControl()
    }
}
