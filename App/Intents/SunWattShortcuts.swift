import AppIntents
import SunWattKit

/// Tells App Intents to also read the intents defined in SunWattKit.
struct SunWattAppIntentsPackage: AppIntentsPackage {
    static var includedPackages: [any AppIntentsPackage.Type] { [SunWattKitIntentsPackage.self] }
}

/// Siri and Shortcuts entries. Phrases are localized in `AppShortcuts.xcstrings`.
struct SunWattShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: GetCurrentPriceIntent(),
            phrases: [
                "What's the electricity price in \(.applicationName)",
                "Electricity price in \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Electricity price", table: "ShortcutTitles"),
            systemImageName: "bolt.fill")
        AppShortcut(
            intent: GetSolarProductionIntent(),
            phrases: [
                "Solar production in \(.applicationName)",
                "How much solar am I producing in \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Solar production", table: "ShortcutTitles"),
            systemImageName: "sun.max.fill")
        AppShortcut(
            intent: GetCheapestHoursIntent(),
            phrases: [
                "Cheapest hours in \(.applicationName)",
                "When is electricity cheapest in \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Cheapest hours", table: "ShortcutTitles"),
            systemImageName: "clock.badge.checkmark")
    }
}
