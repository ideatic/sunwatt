import AppIntents
import SunWattKit

/// What the widget shows. Mirrors `WidgetContent` from SunWattKit.
enum ContentOption: String, AppEnum {
    case both
    case solarOnly
    case priceOnly

    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Content")
    static let caseDisplayRepresentations: [ContentOption: DisplayRepresentation] = [
        .both: "Solar and price",
        .solarOnly: "Solar only",
        .priceOnly: "Price only",
    ]

    var content: WidgetContent {
        switch self {
        case .both: .both
        case .solarOnly: .solarOnly
        case .priceOnly: .priceOnly
        }
    }
}

/// Which hours the price chart shows. Mirrors `PriceWindow` from SunWattKit.
enum PriceWindowOption: String, AppEnum {
    case wholeDay
    case around

    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Price window")
    static let caseDisplayRepresentations: [PriceWindowOption: DisplayRepresentation] = [
        .wholeDay: "Whole day",
        .around: "6 h before, 12 h after",
    ]

    var window: PriceWindow {
        switch self {
        case .wholeDay: .wholeDay
        case .around: .around
        }
    }
}

struct SunWattIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "SunWatt"
    static let description = IntentDescription("Solar production and electricity price.")

    @Parameter(title: "Content", default: .both)
    var content: ContentOption

    @Parameter(title: "Price window", default: .wholeDay)
    var priceWindow: PriceWindowOption
}
