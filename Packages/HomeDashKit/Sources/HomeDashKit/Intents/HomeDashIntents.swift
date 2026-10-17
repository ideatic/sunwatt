import AppIntents
import Foundation
import WidgetKit

/// Registers the package intents with the app and the widget extension (both list it in `includedPackages`).
public struct HomeDashKitIntentsPackage: AppIntentsPackage {}

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

/// Refreshes the data and reloads widgets and controls. Used as the tap action of the widgets and the controls.
public struct RefreshWidgetIntent: AppIntent {
    public static let title = LocalizedStringResource("Refresh", table: "Intents", bundle: #bundle)
    public static let description = IntentDescription(LocalizedStringResource("Updates the SunWatt widgets with the latest data.", table: "Intents", bundle: #bundle))
    public static let isDiscoverable = false
    public static let openAppWhenRun = false

    public init() {}

    public func perform() async throws -> some IntentResult {
        await Self.refresh(load: { _ = await HomeDashLoader().load(deadline: 10) }, reload: Self.reloadEverything)
        return .result()
    }

    /// The seam used by tests: load first (it saves to the store), then reload.
    static func refresh(load: () async -> Void, reload: () -> Void) async {
        await load()
        reload()
    }

    static func reloadEverything() {
        WidgetCenter.shared.reloadAllTimelines()
        ControlCenter.shared.reloadAllControls()
    }
}
