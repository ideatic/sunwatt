import Testing
import SwiftUI
import Foundation
@testable import SunWattKit

// MARK: Catalog

private func appCatalog() throws -> [String: [String: Any]] {
    let url = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Sources/SunWattKit/Resources/App.xcstrings")
    let root = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    #expect(root["sourceLanguage"] as? String == "en")
    return try #require(root["strings"] as? [String: [String: Any]])
}

@Suite struct AppStringsTests {
    @Test func everyKeyHasAllSixTranslations() throws {
        let strings = try appCatalog()
        #expect(strings.count > 100)
        for (key, entry) in strings {
            let localizations = entry["localizations"] as? [String: Any] ?? [:]
            for language in L10n.languages where language != "en" {
                let unit = (localizations[language] as? [String: Any])?["stringUnit"] as? [String: Any]
                #expect(unit?["state"] as? String == "translated", "\(key) [\(language)] state")
                let value = unit?["value"] as? String ?? ""
                #expect(!value.trimmingCharacters(in: .whitespaces).isEmpty, "\(key) [\(language)] empty")
            }
        }
    }

    @Test func translationsKeepPlaceholdersAndBrandNames() throws {
        for (key, entry) in try appCatalog() {
            let localizations = entry["localizations"] as? [String: Any] ?? [:]
            for language in L10n.languages where language != "en" {
                let unit = (localizations[language] as? [String: Any])?["stringUnit"] as? [String: Any]
                let value = unit?["value"] as? String ?? ""
                for placeholder in ["%@", "%lld"] {
                    #expect(key.components(separatedBy: placeholder).count == value.components(separatedBy: placeholder).count,
                            "\(key) [\(language)] placeholder \(placeholder)")
                }
                for name in ["SunWatt", "FusionSolar"] where key.contains(name) {
                    #expect(value.contains(name), "\(key) [\(language)] lost \(name)")
                }
            }
        }
    }

    @Test func compiledBundlesResolveAppStrings() throws {
        let expected = ["es": "Ajustes", "de": "Einstellungen", "fr": "Réglages"]
        for (language, value) in expected {
            let path = try #require(L10n.bundle.path(forResource: language, ofType: "lproj"))
            let bundle = try #require(Bundle(path: path))
            let resolved = bundle.localizedString(forKey: "Settings", value: "\u{1}missing", table: "App")
            #expect(resolved == value, "Settings in \(language)")
        }
    }
}

// MARK: Model and launch options

@Suite struct AppModelTests {
    @Test func launchArgumentsAreParsedByHand() {
        let options = AppLaunchOptions(arguments: ["app", "-demo", "-screen", "widgetGuide", "-scenario", "night", "-widgets", "2"])
        #expect(options.demo)
        #expect(options.screen == .widgetGuide)
        #expect(options.scenario == "night")
        #expect(options.widgets == 2)
        let plain = AppLaunchOptions(arguments: ["app"])
        #expect(!plain.demo && plain.screen == nil && plain.scenario == "day")
        #expect(AppLaunchOptions(arguments: ["app", "-screen", "nope"]).screen == nil)
    }

    @Test func settingsDecodeOldJSONWithoutOnboardingFlag() throws {
        let json = Data(#"{"kioskURL":"x","showTomorrowPrices":false,"priceRegion":"portugal"}"#.utf8)
        let settings = try JSONDecoder().decode(SunWattSettings.self, from: json)
        #expect(settings.onboardingCompleted == false)
        #expect(settings.priceRegion == .portugal)
        var done = settings
        done.onboardingCompleted = true
        let roundTrip = try JSONDecoder().decode(SunWattSettings.self, from: JSONEncoder().encode(done))
        #expect(roundTrip.onboardingCompleted)
    }

    @Test func kioskInputValidation() {
        #expect(KioskInputState("") == .empty)
        #expect(KioskInputState("  \n") == .empty)
        #expect(KioskInputState("hello") == .invalid)
        #expect(KioskInputState("https://eu5.fusionsolar.huawei.com/x#/kiosk?kk=abc") == .valid(host: "eu5.fusionsolar.huawei.com"))
    }

    @MainActor @Test func demoModelUsesSampleDataAndNeverTouchesTheStore() async {
        let model = AppModel(launch: AppLaunchOptions(demo: true, screen: .dashboard, scenario: "day", widgets: 1))
        #expect(model.isDemo && model.hasLoaded)
        #expect(model.data?.solar != nil && model.data?.prices != nil)
        #expect(model.widgetCount == 1)
        #expect(!model.showsOnboarding)
        model.updateSettings { $0.priceRegion = .france }
        #expect(model.data?.prices?.region == .france)
        #expect(model.settings.priceRegion == .france)
        let before = AppGroupStore.shared.settings
        model.updateSettings { $0.showTomorrowPrices = false }
        #expect(AppGroupStore.shared.settings == before)
    }

    @MainActor @Test func onboardingScreenArgumentForcesOnboarding() {
        let model = AppModel(launch: AppLaunchOptions(demo: true, screen: .onboarding))
        #expect(model.showsOnboarding)
        model.completeOnboarding()
        #expect(!model.showsOnboarding)
    }

    @MainActor @Test func testConnectionRejectsInvalidURL() async {
        let model = AppModel(launch: AppLaunchOptions(demo: true))
        if case .failure = await model.testKiosk("nope") {} else { Issue.record("expected failure") }
        if case .success(let solar) = await model.testKiosk("https://h.example/x?kk=1") {
            #expect(solar.currentPowerKW != nil)
        } else { Issue.record("expected success in demo") }
    }

    @MainActor @Test func nestedSheetsAreIndependent() {
        let model = AppModel(launch: AppLaunchOptions(demo: true))
        model.present(.settings)
        model.nested = .kioskHelp
        #expect(model.sheet == .settings && model.nested == .kioskHelp)
    }
}

// MARK: Rendering

@MainActor
private func renderedSize(_ view: some View) -> CGSize? {
    let renderer = ImageRenderer(content: view)
    renderer.scale = 1
    guard let image = renderer.cgImage else { return nil }
    return CGSize(width: image.width, height: image.height)
}

@MainActor
@Test(arguments: AppSnapshotView.Kind.allCases, [AppSnapshotView.Device.phone, .mac])
func everyAppScreenRendersAtDeviceSize(kind: AppSnapshotView.Kind, device: AppSnapshotView.Device) {
    Format.locale = Locale(identifier: "de_DE")
    let view = AppSnapshotView(kind: kind, device: device, scheme: .dark, locale: Locale(identifier: "de_DE"))
    #expect(renderedSize(view) == AppSnapshotView.size(for: device))
}

@MainActor
@Test func appIconRendersSquare() {
    #expect(renderedSize(AppIconView(size: 256, style: .square)) == CGSize(width: 256, height: 256))
    #expect(renderedSize(AppIconView(size: 256, style: .macOS)) == CGSize(width: 256, height: 256))
}
