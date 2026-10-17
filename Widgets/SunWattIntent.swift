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

/// A plant to show, or "all plants in turn". Only matters with several plants; with one, every choice shows it.
struct PlantEntity: AppEntity {
    static let rotateID = "rotate"
    static let rotate = PlantEntity(id: rotateID, name: "")

    let id: String
    let name: String

    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Solar plant")
    static let defaultQuery = PlantQuery()

    var displayRepresentation: DisplayRepresentation {
        id == Self.rotateID ? DisplayRepresentation(title: "All plants, in turn") : DisplayRepresentation(title: "\(name)")
    }

    var choice: WidgetPlantChoice { id == Self.rotateID ? .rotate : .plant(id) }
}

struct PlantQuery: EntityQuery {
    func entities(for identifiers: [PlantEntity.ID]) async throws -> [PlantEntity] {
        all().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [PlantEntity] { all() }

    func defaultResult() async -> PlantEntity? { .rotate }

    /// "All plants, in turn" and every configured plant, named as in the app.
    private func all() -> [PlantEntity] {
        let store = AppGroupStore.shared
        let plants = store.settings.plants.map { plant in
            PlantEntity(id: plant.id, name: plant.displayName(stationName: store.loadSolar(plantID: plant.id)?.stationName))
        }
        return [.rotate] + plants
    }
}

struct SunWattIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "SunWatt"
    static let description = IntentDescription("Solar production and electricity price.")

    @Parameter(title: "Content", default: .both)
    var content: ContentOption

    @Parameter(title: "Price window", default: .wholeDay)
    var priceWindow: PriceWindowOption

    /// With several plants: one of them, or all in turn (default).
    @Parameter(title: "Solar plant")
    var plant: PlantEntity?
}
