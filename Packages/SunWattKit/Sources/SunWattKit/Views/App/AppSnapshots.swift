#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI

/// App screens drawn for `ImageRenderer` (iPhone-size and Mac-window-size), with fake system chrome around them.
/// Used by `SunWattSnapshots --app` and the tests. The app itself never uses this.
public struct AppSnapshotView: View {
    public enum Kind: String, Sendable, CaseIterable {
        case dashboardDay
        case dashboardNight
        case dashboardError
        case dashboardStale
        case dashboardLoading
        case dashboardNoSolar
        case dashboardSelection
        case dashboardTomorrow
        case dashboardWidgetAdded
        case dashboardHome
        case dashboardKioskExpiring
        case dashboardKioskExpired
        case settings
        case settingsTest
        case settingsInvalid
        case settingsSolarEdge
        case settingsPlants
        case settingsFusionSolarAPI
        case dashboardPlants
        case onboardingWelcome
        case onboardingCountry
        case onboardingSolar
        case onboardingSolarAPI
        case onboardingWidget
        case onboardingDone
        case widgetGuide
        case widgetGuideLock
        case kioskHelp
    }

    public enum Device: String, Sendable {
        case phone, mac
        /// iPad 13-inch, portrait (1032 x 1376 pt) and landscape (1376 x 1032 pt).
        case pad = "ipad"
        case padLandscape = "ipad-landscape"
    }

    public let kind: Kind
    public let device: Device
    public let scheme: ColorScheme
    public let locale: Locale
    /// Natural height instead of the device height (phone only), to review long screens.
    public let fullHeight: Bool

    public init(kind: Kind, device: Device, scheme: ColorScheme, locale: Locale, fullHeight: Bool = false) {
        self.kind = kind
        self.device = device
        self.scheme = scheme
        self.locale = locale
        self.fullHeight = fullHeight
    }

    public static func size(for device: Device) -> CGSize {
        switch device {
        case .phone: CGSize(width: 393, height: 852)
        case .mac: CGSize(width: 900, height: 640)
        case .pad: CGSize(width: 1032, height: 1376)
        case .padLandscape: CGSize(width: 1376, height: 1032)
        }
    }

    private var idiom: AppIdiom {
        switch device {
        case .phone: .phone
        case .mac: .desktop
        case .pad, .padLandscape: .pad
        }
    }
    private var isSheet: Bool {
        switch kind {
        case .settings, .settingsTest, .settingsInvalid, .settingsSolarEdge, .settingsPlants, .settingsFusionSolarAPI, .widgetGuide, .widgetGuideLock, .kioskHelp: true
        default: false
        }
    }

    public var body: some View {
        let size = Self.size(for: device)
        Group {
            switch device {
            case .phone: phone(size: size)
            case .mac: mac(size: size)
            case .pad, .padLandscape: pad(size: size)
            }
        }
        .environment(\.appIdiom, idiom)
        .environment(\.appSnapshot, true)
        .environment(\.colorScheme, scheme)
        .environment(\.locale, locale)
        .tint(Theme.Solar.orange)
    }

    // MARK: Models

    private var model: AppModel {
        var settings = SunWattSettings(
            kioskURL: "https://eu5.fusionsolar.huawei.com/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=DEMO0000",
            priceRegion: .spain)
        if locale.identifier.hasPrefix("fr") { settings.priceRegion = .france }
        switch kind {
        case .dashboardNight:
            return AppModel(demo: .night, settings: settings, widgetCount: 0)
        case .dashboardError:
            return AppModel(demo: .error, settings: settings, widgetCount: nil)
        case .dashboardStale:
            return AppModel(demo: .stale, settings: settings, widgetCount: 0)
        case .dashboardLoading:
            return AppModel(demo: .day, settings: settings, widgetCount: 0, isLoading: true)
        case .dashboardNoSolar:
            settings.kioskURL = ""
            return AppModel(demo: .day, settings: settings, widgetCount: 0, solarConfigured: false)
        case .dashboardWidgetAdded:
            return AppModel(demo: .day, settings: settings, widgetCount: 2)
        case .dashboardKioskExpiring:
            // Saved 350 days before the sample time: the notice shows (15 days left).
            settings.kioskLinkSavedAt = SampleData.now.addingTimeInterval(-350 * 24 * 3600)
            return AppModel(demo: .day, settings: settings, widgetCount: 1)
        case .dashboardKioskExpired:
            return AppModel(demo: .day, settings: settings, widgetCount: 1, kioskLinkExpired: true)
        case .dashboardHome:
            // A plant with a meter at home: consumption, selling the surplus, today's share from the panels.
            let home = HomeEnergy(consumptionKW: 0.9, gridKW: -3.9, dailyConsumptionKWh: 6.4, dailyImportKWh: 2.1, dailyExportKWh: 9.8)
            return AppModel(demo: .day, settings: settings, widgetCount: 0, home: home)
        case .settingsInvalid:
            settings.kioskURL = "eu5.fusionsolar.huawei.com/kiosk"
            return AppModel(demo: .day, settings: settings, widgetCount: 1)
        case .settings, .settingsTest:
            return AppModel(demo: .day, settings: settings, widgetCount: 1)
        case .settingsSolarEdge:
            settings.solarProvider = .solarEdge
            settings.solarSiteID = "1234567"
            settings.solarAPIKey = "DEMO0KEY0DEMO0KEY0DEMO0KEY000000"
            return AppModel(demo: .day, settings: settings, widgetCount: 1)
        case .dashboardPlants:
            settings.primaryPlantName = "Home"
            settings.additionalPlants = Self.demoPlants
            return AppModel(demo: .day, settings: settings, widgetCount: 1)
        case .settingsPlants:
            settings.primaryPlantName = "Home"
            settings.additionalPlants = [Self.demoPlants[0]]
            return AppModel(demo: .day, settings: settings, widgetCount: 1)
        case .settingsFusionSolarAPI:
            Self.useDemoAPIAccount(&settings)
            return AppModel(demo: .day, settings: settings, widgetCount: 1)
        case .onboardingSolarAPI:
            Self.useDemoAPIAccount(&settings)
            return AppModel(demo: .day, settings: settings, widgetCount: 0, showsOnboarding: true)
        case .onboardingSolar:
            settings.kioskURL = ""
            return AppModel(demo: .day, settings: settings, widgetCount: 0, showsOnboarding: true)
        case .onboardingWelcome, .onboardingCountry, .onboardingWidget, .onboardingDone:
            return AppModel(demo: .day, settings: settings, widgetCount: 0, showsOnboarding: true)
        default:
            return AppModel(demo: .day, settings: settings, widgetCount: 0)
        }
    }

    /// Two further plants (demo names, a SolarEdge site and a FusionSolar API account).
    private static let demoPlants = [
        SolarPlant(id: "demo-barn", name: "Barn", provider: .solarEdge, siteID: "1234567", apiKey: "DEMO0KEY0DEMO0KEY0DEMO0KEY000000"),
        SolarPlant(id: "demo-beach", name: "Beach house", provider: .fusionSolarAPI, siteID: "demo_user", apiKey: "DEMO-system-code"),
    ]

    private static func useDemoAPIAccount(_ settings: inout SunWattSettings) {
        settings.kioskURL = ""
        settings.solarProvider = .fusionSolarAPI
        settings.solarSiteID = "demo_user"
        settings.solarAPIKey = "DEMO-system-code"
    }

    private var onboardingPage: OnboardingPage {
        switch kind {
        case .onboardingCountry: .country
        case .onboardingSolar, .onboardingSolarAPI: .solar
        case .onboardingWidget: .widget
        case .onboardingDone: .done
        default: .welcome
        }
    }

    private var isOnboarding: Bool { kind.rawValue.hasPrefix("onboarding") }

    // MARK: Screens

    @ViewBuilder
    private func screen(model: AppModel) -> some View {
        switch kind {
        case .dashboardSelection:
            DashboardContent(
                model: model,
                solarSelection: SampleData.now.addingTimeInterval(-3600),
                priceSelection: SampleData.now.addingTimeInterval(6 * 3600))
        case .dashboardTomorrow:
            DashboardContent(model: model, startsOnTomorrow: true)
        case .settings, .settingsInvalid, .settingsSolarEdge, .settingsPlants, .settingsFusionSolarAPI:
            SettingsContent(model: model)
        case .settingsTest:
            SettingsContent(model: model, initialTest: .success(SampleData.entry(.day).data.solar!))
        case .widgetGuide:
            WidgetGuideContent(model: model)
        case .widgetGuideLock:
            WidgetGuideContent(model: model, initialTab: 1)
        case .kioskHelp:
            KioskHelpContent()
        default:
            DashboardContent(model: model)
        }
    }

    private var sheetTitle: LocalizedStringKey {
        switch kind {
        case .widgetGuide, .widgetGuideLock: "Add the widget"
        case .kioskHelp: "Get the kiosk link"
        default: "Settings"
        }
    }

    private func phone(size: CGSize) -> some View {
        let model = self.model
        return VStack(spacing: 0) {
            StatusBarMock()
            ZStack(alignment: .top) {
                if isOnboarding {
                    OnboardingContent(model: model, page: .constant(onboardingPage))
                } else if isSheet {
                    SheetContainer(title: sheetTitle, onDone: {}) { screen(model: model) }
                        .overlay(alignment: .top) {
                            Capsule().fill(Color.secondary.opacity(0.4)).frame(width: 36, height: 5).padding(.top, 6)
                        }
                } else {
                    screen(model: model)
                        .padding(.horizontal, Theme.Spacing.l)
                        .padding(.vertical, Theme.Spacing.m)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: fullHeight ? nil : .infinity, alignment: .top)
        }
        .frame(width: size.width, height: fullHeight ? nil : size.height, alignment: .top)
        .fixedSize(horizontal: false, vertical: fullHeight)
        .background(AppBackground())
        .clipped()
    }

    private func mac(size: CGSize) -> some View {
        let model = self.model
        return VStack(spacing: 0) {
            TitleBarMock(title: macTitle, showsRefresh: !isOnboarding && !isSheet)
            Group {
                if isOnboarding {
                    OnboardingContent(model: model, page: .constant(onboardingPage))
                } else if isSheet {
                    ZStack {
                        DashboardContent(model: AppModel(demo: .day, widgetCount: 0))
                            .padding(Theme.Spacing.xl)
                            .frame(maxHeight: .infinity, alignment: .top)
                            .blur(radius: 2)
                        Color.black.opacity(scheme == .dark ? 0.45 : 0.25)
                        SheetContainer(title: sheetTitle, onDone: {}) { screen(model: model) }
                            .frame(width: 600, height: 590, alignment: .top)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .shadow(color: .black.opacity(0.35), radius: 24, y: 10)
                            .padding(.top, 20)
                    }
                } else {
                    screen(model: model)
                        .padding(Theme.Spacing.xl)
                        .frame(maxWidth: 1000)
                        .frame(width: size.width, alignment: .top)
                        .frame(height: size.height - 38, alignment: .top)
                }
            }
            .frame(width: size.width, height: size.height - 38, alignment: .top)
            .background(AppBackground())
            .clipped()
        }
        .frame(width: size.width, height: size.height)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.primary.opacity(0.18), lineWidth: 0.5))
    }

    private var macTitle: String { "SunWatt" }

    /// iPad: status bar, then the screen centered with a readable maximum width; sheets float over the dashboard.
    private func pad(size: CGSize) -> some View {
        let model = self.model
        let isLandscape = device == .padLandscape
        let body = size.height - 24
        return VStack(spacing: 0) {
            PadStatusBarMock()
            Group {
                if isOnboarding {
                    OnboardingContent(model: model, page: .constant(onboardingPage))
                } else if isSheet {
                    ZStack {
                        DashboardContent(model: AppModel(demo: .day, widgetCount: 0))
                            .padding(Theme.Spacing.xl)
                            .frame(maxWidth: 1100)
                            .frame(width: size.width, height: body, alignment: .top)
                            .blur(radius: 2)
                        Color.black.opacity(scheme == .dark ? 0.45 : 0.25)
                        SheetContainer(title: sheetTitle, onDone: {}) { screen(model: model) }
                            .frame(width: 704, height: isLandscape ? body - 120 : body - 220, alignment: .top)
                            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                            .shadow(color: .black.opacity(0.35), radius: 28, y: 10)
                    }
                } else {
                    screen(model: model)
                        .padding(.horizontal, Theme.Spacing.xl)
                        .padding(.vertical, Theme.Spacing.m)
                        .frame(maxWidth: 1100)
                        .frame(width: size.width, height: body, alignment: .top)
                }
            }
            .frame(width: size.width, height: body, alignment: .top)
            .background(AppBackground())
            .clipped()
        }
        .frame(width: size.width, height: size.height)
        .background(AppBackground())
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(Color.primary.opacity(0.18), lineWidth: 0.5))
    }
}

// MARK: Fake system chrome

private struct StatusBarMock: View {
    var body: some View {
        HStack {
            Text(verbatim: "9:41").font(.system(size: 16, weight: .semibold, design: .rounded))
            Spacer()
            HStack(spacing: 5) {
                Image(systemName: "cellularbars")
                Image(systemName: "wifi")
                Image(systemName: "battery.100percent")
            }
            .font(.system(size: 14, weight: .semibold))
        }
        .padding(.horizontal, 32)
        .frame(height: 54, alignment: .bottom)
        .padding(.bottom, 4)
    }
}

private struct PadStatusBarMock: View {
    var body: some View {
        HStack {
            Text(verbatim: "9:41").font(.system(size: 14, weight: .semibold, design: .rounded))
            Spacer()
            HStack(spacing: 5) {
                Image(systemName: "wifi")
                Image(systemName: "battery.100percent")
            }
            .font(.system(size: 13, weight: .semibold))
        }
        .padding(.horizontal, 28)
        .frame(height: 24)
    }
}

private struct TitleBarMock: View {
    let title: String
    let showsRefresh: Bool
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(Color(red: 1, green: 0.37, blue: 0.34)).frame(width: 12, height: 12)
            Circle().fill(Color(red: 1, green: 0.74, blue: 0.18)).frame(width: 12, height: 12)
            Circle().fill(Color(red: 0.16, green: 0.79, blue: 0.25)).frame(width: 12, height: 12)
            Spacer()
            Text(verbatim: title).font(.system(size: 13, weight: .semibold))
            Spacer()
            if showsRefresh {
                Image(systemName: "arrow.clockwise").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 38)
        .background(scheme == .dark ? Color(white: 0.16) : Color(white: 0.93))
        .overlay(alignment: .bottom) { Rectangle().fill(Color.primary.opacity(0.12)).frame(height: 0.5) }
    }
}
#endif
