import Foundation
import Testing
@testable import SunWattKit

@Suite struct IntentCatalogTests {
    private static let translated = ["es", "pt", "fr", "de", "it", "nl"]

    /// Root of the repository (tests run from the package checkout).
    private static var root: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
    }

    private func strings(at path: String) throws -> [String: Any] {
        let data = try Data(contentsOf: Self.root.appendingPathComponent(path))
        let json = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try #require(json["strings"] as? [String: Any])
    }

    private func checkComplete(_ path: String) throws {
        let catalog = try strings(at: path)
        #expect(!catalog.isEmpty, "\(path)")
        for (key, entry) in catalog {
            let localizations = ((entry as? [String: Any])?["localizations"] as? [String: Any]) ?? [:]
            for language in Self.translated {
                let unit = (localizations[language] as? [String: Any])?["stringUnit"] as? [String: Any]
                #expect(unit?["state"] as? String == "translated", "\(path) \(key) [\(language)] state")
                let value = (unit?["value"] as? String) ?? ""
                #expect(value.isEmpty == false, "\(path) \(key) [\(language)] empty")
            }
        }
    }

    @Test func intentsCatalogIsComplete() throws {
        try checkComplete("Packages/SunWattKit/Sources/SunWattKit/Resources/Intents.xcstrings")
    }

    @Test func shortcutsCatalogIsComplete() throws {
        try checkComplete("App/Intents/AppShortcuts.xcstrings")
        let catalog = try strings(at: "App/Intents/AppShortcuts.xcstrings")
        #expect(catalog.count == 6)
        for key in catalog.keys { #expect(key.contains("${applicationName}"), "\(key)") }
    }

    @Test func shortcutTitlesCatalogIsComplete() throws {
        try checkComplete("App/Intents/ShortcutTitles.xcstrings")
    }

    @Test func widgetCatalogIsComplete() throws {
        try checkComplete("Widgets/Resources/Localizable.xcstrings")
    }

    @Test func watchWidgetCatalogIsComplete() throws {
        try checkComplete("WatchWidgets/Resources/Localizable.xcstrings")
        let catalog = try strings(at: "WatchWidgets/Resources/Localizable.xcstrings")
        #expect(catalog["Solar today"] != nil)
        #expect(catalog["Today's solar production."] != nil)
    }

    @Test func formatPlaceholdersMatchInEveryLanguage() throws {
        let catalog = try strings(at: "Packages/SunWattKit/Sources/SunWattKit/Resources/Intents.xcstrings")
        for (key, entry) in catalog {
            let expected = key.components(separatedBy: "%@").count
            let localizations = ((entry as? [String: Any])?["localizations"] as? [String: Any]) ?? [:]
            for language in Self.translated {
                let value = ((localizations[language] as? [String: Any])?["stringUnit"] as? [String: Any])?["value"] as? String ?? ""
                #expect(value.components(separatedBy: "%@").count == expected, "\(key) [\(language)]")
            }
        }
    }

    @Test func everyCompiledLanguageBundleHasEveryIntentKey() throws {
        let catalog = try strings(at: "Packages/SunWattKit/Sources/SunWattKit/Resources/Intents.xcstrings")
        for language in Self.translated {
            let s = try #require(IntentStrings.language(language))
            for key in catalog.keys {
                let value = s.bundle.localizedString(forKey: key, value: "\u{1}missing", table: "Intents")
                #expect(value != "\u{1}missing" && !value.isEmpty, "\(key) missing in \(language)")
            }
        }
    }
}
