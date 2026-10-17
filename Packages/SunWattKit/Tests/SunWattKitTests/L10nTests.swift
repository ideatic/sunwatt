import Testing
import Foundation
@testable import SunWattKit

@Test func catalogCompilesIntoBundle() throws {
    let es = try #require(SunWattKit.L10n.bundle.path(forResource: "es", ofType: "lproj"))
    let bundle = try #require(Bundle(path: es))
    #expect(bundle.localizedString(forKey: "No production", value: nil, table: "Core") == "Sin producción")
}

@Suite struct CatalogCompletenessTests {
    private static let translated = ["es", "pt", "fr", "de", "it", "nl"]

    /// `Core.xcstrings` read from the source tree (tests run from the package checkout).
    private func catalog() throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/SunWattKit/Resources/Core.xcstrings")
        let data = try Data(contentsOf: url)
        let root = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try #require(root["strings"] as? [String: Any])
    }

    @Test func everyKeyHasAllSixTranslationsInTheCatalog() throws {
        let strings = try catalog()
        #expect(!strings.isEmpty)
        for (key, entry) in strings {
            let localizations = ((entry as? [String: Any])?["localizations"] as? [String: Any]) ?? [:]
            for language in Self.translated {
                let unit = (localizations[language] as? [String: Any])?["stringUnit"] as? [String: Any]
                #expect(unit?["state"] as? String == "translated", "\(key) [\(language)] state")
                let value = unit?["value"] as? String ?? ""
                #expect(!value.trimmingCharacters(in: .whitespaces).isEmpty, "\(key) [\(language)] empty")
            }
        }
    }

    /// Every key is managed by hand. Xcode marks keys it did not extract itself as stale, and in this package it
    /// cannot map the `atext`/`wtext` wrappers to their tables, so without "manual" it would mark them all.
    @Test func everyKeyIsManual() throws {
        for (key, entry) in try catalog() {
            #expect((entry as? [String: Any])?["extractionState"] as? String == "manual", "\(key)")
        }
    }

    @Test func everyCompiledLanguageBundleHasEveryKey() throws {
        let keys = try catalog().keys
        for language in Self.translated {
            let path = try #require(L10n.bundle.path(forResource: language, ofType: "lproj"), "\(language).lproj")
            let bundle = try #require(Bundle(path: path))
            for key in keys {
                let value = bundle.localizedString(forKey: key, value: "\u{1}missing", table: "Core")
                #expect(value != "\u{1}missing" && !value.isEmpty, "\(key) missing in \(language)")
            }
        }
    }

    @Test func regionNamesAreTranslated() throws {
        let path = try #require(L10n.bundle.path(forResource: "es", ofType: "lproj"))
        let es = try #require(Bundle(path: path))
        #expect(es.localizedString(forKey: "Spain", value: nil, table: "Core") == "España")
        #expect(es.localizedString(forKey: "Germany & Luxembourg", value: nil, table: "Core") == "Alemania y Luxemburgo")
        for region in PriceRegion.allCases { #expect(!region.displayName.isEmpty) }
    }
}
