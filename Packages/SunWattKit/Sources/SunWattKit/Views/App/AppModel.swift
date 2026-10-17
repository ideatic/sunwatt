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
///   `-widgets <n>`               demo: number of SunWatt widgets "added" (default 0)
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

    public private(set) var settings: SunWattSettings
    /// Last loaded data. Nil until something (cache or network) is available.
    public private(set) var data: SunWattData?
    /// The "current time" the dashboard is drawn for. `Date()` when live, the sample time in demo.
    public private(set) var now: Date
    public private(set) var isLoading = false
    /// False until the first load finished (or demo): the dashboard shows placeholders instead of an error.
    public private(set) var hasLoaded: Bool
    /// Number of SunWatt widgets the user has placed. Nil when unknown.
    public private(set) var widgetCount: Int?
    public var sheet: AppSheet?
    /// Help sheet shown over `sheet` (e.g. the kiosk guide opened from Settings).
    public var nested: AppSheet?
    public var showsOnboarding: Bool
    /// Called after saved settings change (the iPhone app forwards them to the watch).
    @ObservationIgnored public var onSettingsSaved: (@MainActor (SunWattSettings) -> Void)?

    /// Seams for tests: the app talks to WidgetKit through these.
    @ObservationIgnored var reloadWidgets: @MainActor () -> Void = { WidgetCenter.shared.reloadAllTimelines() }
    @ObservationIgnored var countWidgets: @MainActor () async throws -> Int = { try await WidgetCenter.shared.currentConfigurations().count }

    @ObservationIgnored private let store: AppGroupStore
    @ObservationIgnored private let loader: SunWattLoader
    /// The load in flight. A new refresh, or a change of region or kiosk link, cancels it.
    @ObservationIgnored private var loadTask: Task<SunWattData, Never>?
    /// Counts started loads; a finished load applies its result only if no newer load (or invalidation) happened.
    @ObservationIgnored private var loadGeneration = 0
    @ObservationIgnored private var pendingRefresh: Task<Void, Never>?
    @ObservationIgnored private var lastRefresh: Date?
    @ObservationIgnored private var demoScenario: SampleData.Scenario?
    @ObservationIgnored private var demoNotConfigured = false

    public init(launch: AppLaunchOptions = .current, store: AppGroupStore = .shared, loader: SunWattLoader? = nil) {
        self.launch = launch
        self.store = store
        self.loader = loader ?? SunWattLoader(store: store)
        self.now = Date()
        if launch.demo {
            let settings = SunWattSettings(
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
            let cached = self.loader.cached()
            if cached.solar != nil || cached.prices != nil { self.data = cached }
        }
    }

    /// Demo model for snapshots and previews: fixed data, nothing is read from or written to the store.
    public convenience init(
        demo scenario: SampleData.Scenario,
        settings: SunWattSettings = SunWattSettings(),
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
        let entry: SunWattEntry
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
            let message = SunWattError.unavailable.localizedDescription
            data?.solarStatus = .error(message: message)
            data?.priceStatus = .error(message: message)
        }
        now = entry.date
    }

    /// Loads both sources (saving to the store), then asks the widgets to reload.
    /// A refresh that starts while another load runs cancels that load and takes over, so the result always
    /// belongs to the latest settings. `maxCacheAge` is passed to `SunWattLoader.load`: 0 (default) always fetches.
    public func refresh(maxCacheAge: TimeInterval = 0) async {
        if isDemo {
            applyDemo(scenarioName: demoScenario?.rawValue ?? "day")
            return
        }
        loadTask?.cancel()
        loadGeneration += 1
        let generation = loadGeneration
        isLoading = true
        let loader = loader
        let task = Task { await loader.load(maxCacheAge: maxCacheAge) }
        loadTask = task
        let loaded = await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
        // Superseded by a newer refresh or a settings change: that one owns `isLoading` and the result.
        guard generation == loadGeneration else { return }
        loadTask = nil
        isLoading = false
        guard !Task.isCancelled else { return }
        data = loaded
        now = Date()
        hasLoaded = true
        lastRefresh = Date()
        reloadWidgets()
        await refreshWidgetCount()
    }

    /// Drops the load in flight (its result would belong to old settings).
    private func invalidateLoad() {
        loadTask?.cancel()
        loadTask = nil
        loadGeneration += 1
        isLoading = false
    }

    /// First load: cached data shows at once, then the refresh runs. Data the widgets stored a moment ago is reused.
    public func start() async {
        await refreshWidgetCount()
        if !isDemo, !showsOnboarding { await refresh(maxCacheAge: SunWattLoader.sharedCacheWindow) }
    }

    /// Called when the app becomes active: re-checks the widgets, refreshes data older than 5 minutes.
    public func didBecomeActive() async {
        await refreshWidgetCount()
        guard !isDemo, !showsOnboarding else { return }
        if let lastRefresh, Date().timeIntervalSince(lastRefresh) < 300 { return }
        await refresh(maxCacheAge: SunWattLoader.sharedCacheWindow)
    }

    /// Reads the widgets the user has placed. A failure means "unknown", not zero.
    public func refreshWidgetCount() async {
        if isDemo { return }
        do {
            widgetCount = try await countWidgets()
        } catch {
            widgetCount = nil
        }
    }

    // MARK: Settings

    /// Applies `change`, saves to the App Group, reloads the widgets and (after a pause) the data.
    public func updateSettings(_ change: (inout SunWattSettings) -> Void) {
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
        let regionChanged = new.priceRegion != old.priceRegion
        let kioskChanged = new.kioskURL != old.kioskURL
            && (KioskInputState(new.kioskURL) != .invalid || KioskInputState(old.kioskURL) != .invalid)
        let tomorrowChanged = new.showTomorrowPrices != old.showTomorrowPrices
        let needsFetch = regionChanged || kioskChanged || tomorrowChanged
        // When data must be fetched, the widgets reload once the new data is stored (end of `refresh`): reloading
        // them now would make every widget fetch the same data at the same time.
        if !needsFetch { reloadWidgets() }
        if regionChanged || kioskChanged { invalidateLoad() }
        if regionChanged {
            // Cached prices of another region do not apply: show placeholders until the new region loads.
            let cached = loader.cached()
            data = (cached.solar != nil || cached.prices != nil) ? cached : nil
            hasLoaded = false
        }
        if needsFetch { scheduleRefresh() }
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
        guard let endpoint = KioskEndpoint(parsing: text) else { return .failure(SunWattError.invalidKioskURL) }
        if isDemo {
            try? await Task.sleep(for: .milliseconds(400))
            if let solar = SampleData.entry(.day).data.solar { return .success(solar) }
            return .failure(SunWattError.unavailable)
        }
        do {
            return .success(try await FusionSolarKioskClient(endpoint: endpoint).fetch())
        } catch {
            return .failure(error)
        }
    }

    // MARK: Derived

    /// Binding helper for the settings screens.
    func binding<Value>(_ keyPath: WritableKeyPath<SunWattSettings, Value>) -> Binding<Value> {
        Binding(
            get: { self.settings[keyPath: keyPath] },
            set: { newValue in self.updateSettings { $0[keyPath: keyPath] = newValue } }
        )
    }

    var entry: SunWattEntry? {
        data.map { SunWattEntry(date: now, data: $0) }
    }

    var isSolarConfigured: Bool {
        if isDemo { return !demoNotConfigured }
        return KioskEndpoint(parsing: settings.kioskURL) != nil
    }
}
#endif
