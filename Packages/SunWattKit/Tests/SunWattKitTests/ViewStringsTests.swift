import Testing
import Foundation
@testable import SunWattKit

private struct Catalog: Decodable {
    struct Localization: Decodable {
        struct Unit: Decodable { let state: String; let value: String }
        let stringUnit: Unit?
    }
    struct Entry: Decodable {
        let localizations: [String: Localization]?
    }
    let sourceLanguage: String
    let strings: [String: Entry]
}

private func loadWidgetsCatalog() throws -> Catalog {
    let url = try #require(L10n.bundle.url(forResource: "Widgets", withExtension: "xcstrings")
        ?? sourceCatalogURL())
    return try JSONDecoder().decode(Catalog.self, from: Data(contentsOf: url))
}

/// SwiftPM compiles the catalog into .lproj folders, so read the source file next to the sources instead.
private func sourceCatalogURL() -> URL? {
    URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Sources/SunWattKit/Resources/Widgets.xcstrings")
}

@Test func widgetsCatalogHasAllTranslations() throws {
    let catalog = try loadWidgetsCatalog()
    #expect(catalog.sourceLanguage == "en")
    #expect(!catalog.strings.isEmpty)
    for (key, entry) in catalog.strings {
        for language in L10n.languages where language != "en" {
            let unit = entry.localizations?[language]?.stringUnit
            #expect(unit != nil, "Missing \(language) for \"\(key)\"")
            #expect(unit?.state == "translated", "\(language) not translated for \"\(key)\"")
            #expect(!(unit?.value.isEmpty ?? true), "Empty \(language) for \"\(key)\"")
        }
    }
}

@Test func widgetsCatalogKeepsFormatSpecifiers() throws {
    let catalog = try loadWidgetsCatalog()
    for (key, entry) in catalog.strings where key.contains("%@") {
        for (language, localization) in entry.localizations ?? [:] {
            #expect(localization.stringUnit?.value.contains("%@") == true, "\(language) lost %@ in \"\(key)\"")
        }
    }
}

@Test func widgetStringsResolveInGerman() throws {
    let path = try #require(L10n.bundle.path(forResource: "de", ofType: "lproj"))
    let bundle = try #require(Bundle(path: path))
    #expect(bundle.localizedString(forKey: "No production", value: nil, table: "Widgets") == "Keine Erzeugung")
}
