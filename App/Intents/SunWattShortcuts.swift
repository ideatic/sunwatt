import AppIntents
import SunWattKit

/// Tells App Intents to also read the intents defined in SunWattKit.
struct SunWattAppIntentsPackage: AppIntentsPackage {
    static var includedPackages: [any AppIntentsPackage.Type] { [SunWattKitIntentsPackage.self] }
}

/// Siri and Shortcuts entries. Phrases are localized in `AppShortcuts.xcstrings`. Siri only routes a request to an
/// app when the app name is in it, so every phrase has it; the variants cover how people actually ask (each language
/// adapts them to everyday wording, e.g. "la luz" in Spanish). `INAlternativeAppNames` (project.yml) adds spellings
/// Siri may hear for "SunWatt".
struct SunWattShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: GetCurrentPriceIntent(),
            phrases: [
                "What's the electricity price in \(.applicationName)",
                "Electricity price in \(.applicationName)",
                "How much does electricity cost now in \(.applicationName)",
                "Power price now in \(.applicationName)",
                "Is electricity cheap now in \(.applicationName)",
                "\(.applicationName) electricity price",
            ],
            shortTitle: LocalizedStringResource("Electricity price", table: "ShortcutTitles"),
            systemImageName: "bolt.fill")
        AppShortcut(
            intent: GetSolarProductionIntent(),
            phrases: [
                "Solar production in \(.applicationName)",
                "How much solar am I producing in \(.applicationName)",
                "How much are my solar panels producing in \(.applicationName)",
                "What's my solar production today in \(.applicationName)",
                "How much energy have my panels made today in \(.applicationName)",
                "\(.applicationName) solar production",
            ],
            shortTitle: LocalizedStringResource("Solar production", table: "ShortcutTitles"),
            systemImageName: "sun.max.fill")
        AppShortcut(
            intent: GetCheapestHoursIntent(),
            phrases: [
                "Cheapest hours in \(.applicationName)",
                "When is electricity cheapest in \(.applicationName)",
                "When is power cheapest today in \(.applicationName)",
                "Best time to use electricity in \(.applicationName)",
                "\(.applicationName) cheapest hours",
            ],
            shortTitle: LocalizedStringResource("Cheapest hours", table: "ShortcutTitles"),
            systemImageName: "clock.badge.checkmark")
    }
}
