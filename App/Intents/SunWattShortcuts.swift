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
                "Ask \(.applicationName) for the electricity price",
                "How much is electricity right now in \(.applicationName)",
                "Is electricity expensive now in \(.applicationName)",
                "What does power cost today in \(.applicationName)",
                "Check the electricity price with \(.applicationName)",
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
                "Ask \(.applicationName) how much solar I'm producing",
                "How are my solar panels doing in \(.applicationName)",
                "What are my panels producing right now in \(.applicationName)",
                "How much solar did I make today in \(.applicationName)",
                "Check my solar panels with \(.applicationName)",
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
                "Ask \(.applicationName) for the cheapest hours",
                "When should I run the washing machine in \(.applicationName)",
                "When is the cheapest time to charge my car in \(.applicationName)",
                "When should I run the dishwasher in \(.applicationName)",
                "Check the cheapest hours with \(.applicationName)",
            ],
            shortTitle: LocalizedStringResource("Cheapest hours", table: "ShortcutTitles"),
            systemImageName: "clock.badge.checkmark")
    }
}
