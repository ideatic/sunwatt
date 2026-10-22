import AppIntents
import Foundation

/// Registers the package intents with the app and the widget extension (both list it in `includedPackages`).
public struct SunWattKitIntentsPackage: AppIntentsPackage {}

/// "What is the electricity price now?"
public struct GetCurrentPriceIntent: AppIntent {
    public static let title = LocalizedStringResource("Electricity price now", table: "Intents", bundle: #bundle)
    public static let description = IntentDescription(
        LocalizedStringResource("Tells you the current electricity price and how it compares with the rest of the day.", table: "Intents", bundle: #bundle))

    public init() {}

    public func perform() async throws -> some IntentResult & ReturnsValue<Double?> & ProvidesDialog & ShowsSnippetView {
        let data = await IntentDataSource().data(for: .price)
        let now = Date()
        let answer = PriceAnswer.make(from: data, now: now)
        return .result(
            value: answer.eurPerKWh,
            dialog: IntentDialog(stringLiteral: answer.dialog),
            view: PriceSnippetView(answer: answer, series: data.prices, now: now))
    }
}

/// "How much solar am I producing?"
public struct GetSolarProductionIntent: AppIntent {
    public static let title = LocalizedStringResource("Solar production now", table: "Intents", bundle: #bundle)
    public static let description = IntentDescription(LocalizedStringResource("Tells you how much your solar panels produce now and today.", table: "Intents", bundle: #bundle))

    public init() {}

    public func perform() async throws -> some IntentResult & ReturnsValue<Double?> & ProvidesDialog & ShowsSnippetView {
        let data = await IntentDataSource().data(for: .solar)
        let now = Date()
        let answer = SolarAnswer.make(from: data, now: now)
        return .result(
            value: answer.currentKW,
            dialog: IntentDialog(stringLiteral: answer.dialog),
            view: SolarSnippetView(answer: answer, snapshot: data.solar, now: now))
    }
}

/// "When is electricity cheapest?"
public struct GetCheapestHoursIntent: AppIntent {
    public static let title = LocalizedStringResource("Cheapest hours", table: "Intents", bundle: #bundle)
    public static let description = IntentDescription(LocalizedStringResource("Tells you the next cheapest hours to use electricity.", table: "Intents", bundle: #bundle))

    public init() {}

    public func perform() async throws -> some IntentResult & ProvidesDialog & ShowsSnippetView {
        let data = await IntentDataSource().data(for: .price)
        let answer = CheapestHoursAnswer.make(from: data, now: Date())
        return .result(
            dialog: IntentDialog(stringLiteral: answer.dialog),
            view: CheapestHoursSnippetView(answer: answer, timeZone: data.prices?.region.timeZone ?? Constants.madrid))
    }
}
