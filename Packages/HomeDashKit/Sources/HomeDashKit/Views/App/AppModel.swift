#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI
import Observation
import WidgetKit

/// Screens that can be requested with the `-screen` launch argument.
public enum AppScreen: String, Sendable, CaseIterable {
    case onboarding
    case dashboard
    case settings
    case widgetGuide
    case kioskHelp
}

/// Launch arguments of the app. Parsed by hand: `-demo` has no value, so `UserDefaults` argument parsing
/// would swallow the next token.
///   `-demo`                      use `SampleData` instead of network and store
///   `-screen <name>`             open a screen directly (see `AppScreen`)
///   `-scenario day|night|stale|error|notConfigured`   demo data (default `day`)
///   `-widgets <n>`               demo: number of HomeDash widgets "added" (default 0)
public struct AppLaunchOptions: Sendable {
    public var demo: Bool
    public var screen: AppScreen?
    public var scenario: String
    public var widgets: Int

    public init(demo: Bool = false, screen: AppScreen? = nil, scenario: String = "day", widgets: Int = 0) {
        self.demo = demo
        self.screen = screen
        self.scenario = scenario
        self.widgets = widgets
    }

    public init(arguments: [String]) {
        func value(after flag: String) -> String? {
            guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { return nil }
            return arguments[index + 1]
        }
        self.init(
            demo: arguments.contains("-demo"),
            screen: value(after: "-screen").flatMap(AppScreen.init(rawValue:)),
            scenario: value(after: "-scenario") ?? "day",
            widgets: value(after: "-widgets").flatMap(Int.init) ?? 0
        )
    }

    public static var current: AppLaunchOptions { AppLaunchOptions(arguments: CommandLine.arguments) }
}

/// Sheets presented over the app.
public enum AppSheet: String, Identifiable, Sendable {
    case settings
    case widgetGuide
    case kioskHelp

    public var id: String { rawValue }
}

/// State of the kiosk URL text field.
enum KioskInputState: Equatable {
    case empty
    case valid(host: String)
    case invalid

    init(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            self = .empty
        } else if let endpoint = KioskEndpoint(parsing: trimmed) {
            self = .valid(host: endpoint.host)
        } else {
            self = .invalid
        }
    }
}

/// Everything the app screens read and change. One instance per app; views get it by parameter.
@MainActor @Observable
public final class AppModel {
    public let launch: AppLaunchOptions
    public var isDemo: Bool { launch.demo }

    public private(set) var settings: HomeDashSettings
    /// Last loaded data. Nil until something (cache or network) is available.
    public private(set) var data: HomeDashData?
    /// The "current time" the dashboard is drawn for. `Date()` when live, the sample time in demo.
    public private(set) var now: Date
    public private(set) var isLoading = false
    /// False until the first load finished (or demo): the dashboard shows placeholders instead of an error.
    public private(set) var hasLoaded: Bool
    /// Number of HomeDash widgets the user has placed. Nil when unknown.
    public private(set) var widgetCount: Int?
    public var sheet: AppSheet?
    /// Help sheet shown over `sheet` (e.g. the kiosk guide opened from Settings).
    public var nested: AppSheet?
    public var showsOnboarding: Bool
    /// Called after saved settings change (the iPhone app forwards them to the watch).
    @ObservationIgnored public var onSettingsSaved: (@MainActor (HomeDashSettings) -> Void)?

    @ObservationIgnored private let store: AppGroupStore
    @ObservationIgnored private let loader: HomeDashLoader
    @ObservationIgnored private var pendingRefresh: Task<Void, Never>?
    @ObservationIgnored private var lastRefresh: Date?
    @ObservationIgnored private var demoScenario: SampleData.Scenario?
    @ObservationIgnored private var demoNotConfigured = false

    public init(launch: AppLaunchOptions = .current, store: AppGroupStore = .shared) {
        self.launch = launch
        self.store = store
        self.loader = HomeDashLoader(store: store)
        self.now = Date()
        if launch.demo {
            let settings = HomeDashSettings(
                kioskURL: "https://eu5.fusionsolar.huawei.com/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=DEMO0000",
                priceRegion: .spain)
            self.settings = settings
            self.hasLoaded = true
            self.widgetCount = launch.widgets
            self.showsOnboarding = launch.screen == .onboarding
            self.sheet = Self.sheet(for: launch.screen)
            applyDemo(scenarioName: launch.scenario)
        } else {
            let settings = store.settings
            self.settings = settings
            self.hasLoaded = false
            self.showsOnboarding = launch.screen == .onboarding || (launch.screen == nil && !settings.onboardingCompleted)
            self.sheet = Self.sheet(for: launch.screen)
            let cached = loader.cached()
            if cached.solar != nil || cached.prices != nil { self.data = cached }
        }
    }

    /// Demo model for snapshots and previews: fixed data, nothing is read from or written to the store.
    public convenience init(
        demo scenario: SampleData.Scenario,
        settings: HomeDashSettings = HomeDashSettings(),
        widgetCount: Int? = 0,
        solarConfigured: Bool = true,
        showsOnboarding: Bool = false,
        isLoading: Bool = false
    ) {
        self.init(launch: AppLaunchOptions(demo: true, screen: showsOnboarding ? .onboarding : .dashboard, scenario: scenario.rawValue, widgets: widgetCount ?? 0))
        self.settings = settings
        self.widgetCount = widgetCount
        self.demoNotConfigured = !solarConfigured
        self.showsOnboarding = showsOnboarding
        self.sheet = nil
        if isLoading {
            self.data = nil
            self.hasLoaded = false
            self.isLoading = true
        } else {
            applyDemo(scenarioName: scenario.rawValue)
        }
    }

    private static func sheet(for screen: AppScreen?) -> AppSheet? {
        switch screen {
        case .settings: .settings
        case .widgetGuide: .widgetGuide
        case .kioskHelp: .kioskHelp
        default: nil
        }
    }

    // MARK: Data

    private func applyDemo(scenarioName: String) {
        let scenario = SampleData.Scenario(rawValue: scenarioName)
        demoScenario = scenario ?? .day
        demoNotConfigured = demoNotConfigured || scenarioName == "notConfigured"
        let entry: HomeDashEntry
        if demoNotConfigured {
            var base = SampleData.entry(.day, region: settings.priceRegion)
            base.data.solar = nil
            base.data.solarStatus = .notConfigured
            entry = base
        } else {
            entry = SampleData.entry(scenario ?? .day, region: settings.priceRegion)
        }
        data = entry.data
        if scenario == .error {
            let message = HomeDashError.unavailable.localizedDescription
            data?.solarStatus = .error(message: message)
            data?.priceStatus = .error(message: message)
        }
        now = entry.date
    }

    /// Loads both sources (saving to the store), then asks the widgets to reload.
    public func refresh() async {
        if isDemo {
            applyDemo(scenarioName: demoScenario?.rawValue ?? "day")
            return
        }
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        let loaded = await loader.load()
        guard !Task.isCancelled else { return }
        data = loaded
        now = Date()
        hasLoaded = true
        lastRefresh = Date()
        WidgetCenter.shared.reloadAllTimelines()
        await refreshWidgetCount()
    }

    /// First load: cached data shows at once, then the network refresh runs.
    public func start() async {
        await refreshWidgetCount()
        if !isDemo, !showsOnboarding { await refresh() }
    }

    /// Called when the app becomes active: re-checks the widgets, refreshes data older than 5 minutes.
    public func didBecomeActive() async {
        await refreshWidgetCount()
        guard !isDemo, !showsOnboarding else { return }
        if let lastRefresh, Date().timeIntervalSince(lastRefresh) < 300 { return }
        await refresh()
    }

    /// Reads the widgets the user has placed. A failure means "unknown", not zero.
    public func refreshWidgetCount() async {
        if isDemo { return }
        do {
            widgetCount = try await WidgetCenter.shared.currentConfigurations().count
        } catch {
            widgetCount = nil
        }
    }

    // MARK: Settings

    /// Applies `change`, saves to the App Group, reloads the widgets and (after a pause) the data.
    public func updateSettings(_ change: (inout HomeDashSettings) -> Void) {
        let old = settings
        var new = old
        change(&new)
        guard new != old else { return }
        settings = new
        if isDemo {
            if new.priceRegion != old.priceRegion { applyDemo(scenarioName: demoScenario?.rawValue ?? "day") }
            return
        }
        store.settings = new
        onSettingsSaved?(new)
        WidgetCenter.shared.reloadAllTimelines()
        let regionChanged = new.priceRegion != old.priceRegion
        let kioskChanged = new.kioskURL != old.kioskURL
            && (KioskInputState(new.kioskURL) != .invalid || KioskInputState(old.kioskURL) != .invalid)
        let tomorrowChanged = new.showTomorrowPrices != old.showTomorrowPrices
        if regionChanged {
            // Cached prices of another region do not apply: show placeholders until the new region loads.
            let cached = loader.cached()
            data = (cached.solar != nil || cached.prices != nil) ? cached : nil
            hasLoaded = false
        }
        if regionChanged || kioskChanged || tomorrowChanged { scheduleRefresh() }
    }

    /// Waits for the user to stop changing settings (Energy-Charts rate-limits quick repeated requests).
    private func scheduleRefresh() {
        pendingRefresh?.cancel()
        pendingRefresh = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.2))
            guard !Task.isCancelled, let self else { return }
            await self.refresh()
        }
    }

    public func completeOnboarding() {
        updateSettings { $0.onboardingCompleted = true }
        showsOnboarding = false
        Task { await refresh() }
    }

    public func present(_ sheet: AppSheet) { self.sheet = sheet }

    /// Fetches the kiosk once with the typed URL (not necessarily saved) and returns the parsed snapshot.
    public func testKiosk(_ text: String) async -> Result<SolarSnapshot, Error> {
        guard let endpoint = KioskEndpoint(parsing: text) else { return .failure(HomeDashError.invalidKioskURL) }
        if isDemo {
            try? await Task.sleep(for: .milliseconds(400))
            if let solar = SampleData.entry(.day).data.solar { return .success(solar) }
            return .failure(HomeDashError.unavailable)
        }
        do {
            return .success(try await FusionSolarKioskClient(endpoint: endpoint).fetch())
        } catch {
            return .failure(error)
        }
    }

    // MARK: Derived

    /// Binding helper for the settings screens.
    func binding<Value>(_ keyPath: WritableKeyPath<HomeDashSettings, Value>) -> Binding<Value> {
        Binding(
            get: { self.settings[keyPath: keyPath] },
            set: { newValue in self.updateSettings { $0[keyPath: keyPath] = newValue } }
        )
    }

    var entry: HomeDashEntry? {
        data.map { HomeDashEntry(date: now, data: $0) }
    }

    var isSolarConfigured: Bool {
        if isDemo { return !demoNotConfigured }
        return KioskEndpoint(parsing: settings.kioskURL) != nil
    }
}
#endif
