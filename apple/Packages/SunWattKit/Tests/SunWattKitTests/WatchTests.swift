import Testing
import Foundation
import SwiftUI
@testable import SunWattKit

@Suite struct WatchSettingsSyncTests {
    @Test func contextRoundTripsSettings() throws {
        let settings = SunWattSettings(kioskURL: "https://example.invalid/kiosk", showTomorrowPrices: false, priceRegion: .germany)
        let context = WatchSettingsSync.context(for: settings)
        #expect(WatchSettingsSync.settings(from: context) == settings)
    }

    @Test func contextsOfEqualSettingsDifferInTimestamp() {
        let settings = SunWattSettings()
        let a = WatchSettingsSync.context(for: settings, sentAt: Date(timeIntervalSince1970: 1))
        let b = WatchSettingsSync.context(for: settings, sentAt: Date(timeIntervalSince1970: 2))
        #expect((a[WatchSettingsSync.sentAtKey] as? Double) != (b[WatchSettingsSync.sentAtKey] as? Double))
    }

    @Test func missingOrBrokenContextGivesNil() {
        #expect(WatchSettingsSync.settings(from: [:]) == nil)
        #expect(WatchSettingsSync.settings(from: [WatchSettingsSync.settingsKey: Data("nope".utf8)]) == nil)
    }
}

@Suite struct WatchCatalogTests {
    @Test func watchCatalogHasAllSixTranslations() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/SunWattKit/Resources/Watch.xcstrings")
        let root = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let strings = try #require(root["strings"] as? [String: Any])
        #expect(!strings.isEmpty)
        for (key, entry) in strings {
            let localizations = ((entry as? [String: Any])?["localizations"] as? [String: Any]) ?? [:]
            for language in ["es", "pt", "fr", "de", "it", "nl"] {
                let unit = (localizations[language] as? [String: Any])?["stringUnit"] as? [String: Any]
                #expect(unit?["state"] as? String == "translated", "\(key) [\(language)]")
                let value = unit?["value"] as? String ?? ""
                #expect(value.isEmpty == false, "\(key) [\(language)] empty")
            }
        }
    }
}

@MainActor
@Suite struct WatchRenderTests {
    @Test func everyKindRendersInEveryScenario() {
        let kinds: [WatchSnapshotView.Kind] = [.pricePage, .solarPage] + WatchComplicationFamily.allCases.map { .complication($0) }
        for scenario in SampleData.Scenario.allCases {
            for kind in kinds {
                let renderer = ImageRenderer(content: WatchSnapshotView(entry: SampleData.entry(scenario), kind: kind))
                #expect(renderer.cgImage != nil, "\(scenario) \(kind)")
            }
        }
    }

    @Test func cornerGaugeStaysInsideRange() throws {
        let gauge = try #require(WatchCornerGauge(entry: SampleData.entry(.day)))
        #expect(gauge.lower <= gauge.value && gauge.value <= gauge.upper)
        #expect(WatchCornerGauge(entry: SampleData.entry(.error)) == nil)
    }
}
