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
            var settings = store.settings
            // Links saved before the date was recorded: count from now (the expiry notice then comes a bit late at
            // worst, never early).
            if settings.kioskLinkSavedAt == nil, KioskEndpoint(parsing: settings.kioskURL) != nil {
                settings.kioskLinkSavedAt = Date()
                store.settings = settings
            }
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
        isLoading: Bool = false,
        home: HomeEnergy? = nil,
        kioskLinkExpired: Bool = false
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
            if let home { data?.solar?.home = home }
            if kioskLinkExpired {
                data?.solar = nil
                data?.solarStatus = .error(message: SunWattError.kioskLinkRejected.localizedDescription)
                data?.solarLinkExpired = true
            }
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

    /// Keeps the dashboard on the current time while the app is on screen: at each quarter hour (the shortest price
    /// slot) it moves `now` forward, so the current price changes with the hour, and it reloads data older than the
    /// widget refresh interval (new solar points, the next day's prices after midnight). Runs until cancelled.
    public func runClock() async {
        guard !isDemo else { return }
        while !Task.isCancelled {
            let date = Date()
            let next = Self.nextQuarterHour(after: date)
            try? await Task.sleep(for: .seconds(next.timeIntervalSince(date) + 1))
            if Task.isCancelled { return }
            await tick(at: Date())
        }
    }

    /// One clock step. Internal for tests.
    func tick(at date: Date) async {
        guard !isDemo else { return }
        now = date
        guard !showsOnboarding else { return }
        if let lastRefresh, date.timeIntervalSince(lastRefresh) < Constants.refreshInterval { return }
        await refresh(maxCacheAge: SunWattLoader.sharedCacheWindow)
    }

    /// The next :00, :15, :30 or :45 after `date`. Quarter hours are the same instant in every price time zone.
    static func nextQuarterHour(after date: Date) -> Date {
        let quarter: TimeInterval = 15 * 60
        return Date(timeIntervalSinceReferenceDate: (date.timeIntervalSinceReferenceDate / quarter).rounded(.down) * quarter + quarter)
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
        // A new kiosk link (any plant): its year starts now, and a closed expiry notice applies to the old link only.
        for var plant in new.plants {
            let before = old.plant(id: plant.id)
            guard plant.kioskURL != (before?.kioskURL ?? "") else { continue }
            plant.kioskLinkSavedAt = KioskEndpoint(parsing: plant.kioskURL) != nil ? Date() : nil
            plant.kioskExpiryNoticeDismissed = false
            new.updatePlant(plant)
        }
        guard new != old else { return }
        settings = new
        if isDemo {
            if new.priceRegion != old.priceRegion { applyDemo(scenarioName: demoScenario?.rawValue ?? "day") }
            return
        }
        store.settings = new
        onSettingsSaved?(new)
        let regionChanged = new.priceRegion != old.priceRegion
        // Another provider, another valid account, or input appearing/disappearing (the solar status changes
        // between "not set up" and "not valid"). Edits that stay invalid fetch nothing.
        let solarChanged = Self.solarFetchKeys(new) != Self.solarFetchKeys(old)
        let tomorrowChanged = new.showTomorrowPrices != old.showTomorrowPrices
        let needsFetch = regionChanged || solarChanged || tomorrowChanged
        // When data must be fetched, the widgets reload once the new data is stored (end of `refresh`): reloading
        // them now would make every widget fetch the same data at the same time.
        if !needsFetch { reloadWidgets() }
        if regionChanged || solarChanged { invalidateLoad() }
        if regionChanged {
            // Cached prices of another region do not apply: show placeholders until the new region loads.
            let cached = loader.cached()
            data = (cached.solar != nil || cached.prices != nil) ? cached : nil
            hasLoaded = false
        }
        if needsFetch { scheduleRefresh() }
    }

    /// What decides whether a settings change needs a solar fetch, per plant: its id, provider, valid account and
    /// whether anything was typed. Names and edits that stay invalid change nothing.
    private static func solarFetchKeys(_ settings: SunWattSettings) -> [String] {
        settings.plants.map { plant in
            let focused = settings.focused(on: plant)
            let account = SolarAccount(plant: plant).map { "\($0.cacheID)|\(String(describing: $0.localInverter))" } ?? "-"
            return "\(plant.id)|\(plant.provider.rawValue)|\(account)|\(focused.hasSolarInput)"
        }
    }

    // MARK: Plants

    /// The settings with plant `id` in the place of the first one (`SunWattSettings.focused(on:)`): the solar editors
    /// read these, so one editor serves every plant. The whole settings when there is no such plant.
    public func settings(focusedOn id: String) -> SunWattSettings {
        settings.plant(id: id).map { settings.focused(on: $0) } ?? settings
    }

    /// Changes plant `id` through its focused settings and saves it in its place.
    public func updatePlant(_ id: String, _ change: (inout SunWattSettings) -> Void) {
        guard let plant = settings.plant(id: id) else { return }
        var focused = settings.focused(on: plant)
        change(&focused)
        var edited = focused.primaryPlant
        edited.id = id
        updateSettings { $0.updatePlant(edited) }
    }

    /// Adds an empty plant (FusionSolar until the user picks another provider) and returns its id.
    @discardableResult
    public func addPlant() -> String {
        let plant = SolarPlant()
        updateSettings { $0.additionalPlants.append(plant) }
        return plant.id
    }

    /// Removes a further plant (the first one cannot be removed, only changed).
    public func removePlant(_ id: String) {
        updateSettings { $0.additionalPlants.removeAll { $0.id == id } }
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

    /// Fetches SolarEdge or PVOutput once with the typed ID and key (not necessarily saved).
    public func testSolarAPI(provider: SolarProvider, siteID: String, apiKey: String) async -> Result<SolarSnapshot, Error> {
        guard provider.usesAPIKey, let credentials = SolarAPICredentials(siteID: siteID, apiKey: apiKey) else {
            return .failure(SunWattError.invalidSolarCredentials)
        }
        if isDemo {
            try? await Task.sleep(for: .milliseconds(400))
            if let solar = SampleData.entry(.day).data.solar { return .success(solar) }
            return .failure(SunWattError.unavailable)
        }
        guard let account = SolarAccount.api(provider, credentials) else { return .failure(SunWattError.invalidSolarCredentials) }
        // The test is the fetch: the refresh scheduled by saving the fields would send the same requests again
        // (SolarEdge allows 300 a day). Its result is kept for the saved account, then the dashboard refreshes
        // without asking the provider again (see `SolarProvider.minimumFetchInterval`).
        pendingRefresh?.cancel()
        let result: Result<SolarSnapshot, Error>
        do {
            result = .success(try await account.client().fetch())
        } catch {
            result = .failure(error)
        }
        // Kept for the plant with this account (the cache ID leaves out the inverter address).
        if let plant = settings.plants.first(where: { SolarAccount(plant: $0)?.cacheID == account.cacheID }) {
            switch result {
            case .success(var snapshot):
                snapshot.endpointID = account.cacheID
                store.saveSolar(snapshot, plantID: plant.id)
                store.clearSolarFailure(accountID: account.cacheID)
            case .failure(let error):
                if account.provider.minimumFetchInterval > 0, !(error is CancellationError) {
                    store.noteSolarFailure(accountID: account.cacheID, at: Date(), message: error.localizedDescription)
                }
            }
            await refresh(maxCacheAge: SunWattLoader.sharedCacheWindow)
        }
        return result
    }

    // MARK: Inverter on the home network (Huawei dongle or SunSpec)

    /// What the selected provider reads on the home network: the Huawei dongle for FusionSolar, a SunSpec inverter
    /// for SolarEdge and PVOutput.
    public var localInverterKind: LocalInverterKind { localInverterKind(plant: SolarPlant.primaryID) }

    public func localInverterKind(plant id: String) -> LocalInverterKind {
        LocalInverterKind(provider: settings(focusedOn: id).solarProvider)
    }

    /// The settings that hold its address and unit ID.
    var localInverterHostKey: WritableKeyPath<SunWattSettings, String> { Self.hostKey(localInverterKind) }
    var localInverterUnitKey: WritableKeyPath<SunWattSettings, Int> { Self.unitKey(localInverterKind) }

    static func hostKey(_ kind: LocalInverterKind) -> WritableKeyPath<SunWattSettings, String> {
        switch kind {
        case .huawei: \.huaweiLocalHost
        case .sunSpec: \.sunSpecHost
        }
    }

    static func unitKey(_ kind: LocalInverterKind) -> WritableKeyPath<SunWattSettings, Int> {
        switch kind {
        case .huawei: \.huaweiLocalUnitID
        case .sunSpec: \.sunSpecUnitID
        }
    }

    /// Reads the inverter at `address` once: the saved unit ID first, then the kind's usual ones; for SunSpec without a
    /// port, 502 and then 1502 (SolarEdge). On success the address to save (with the port that answered) and the unit ID.
    public func testInverter(_ address: String, plant id: String = SolarPlant.primaryID) async -> Result<InverterFinder.Match, Error> {
        guard let endpoint = ModbusEndpoint(parsing: address) else { return .failure(SunWattError.invalidInverterAddress) }
        let kind = localInverterKind(plant: id)
        if isDemo {
            try? await Task.sleep(for: .milliseconds(400))
            return .success(Self.demoInverter(kind))
        }
        let ports = ModbusEndpoint.hasPort(address) ? [endpoint.port] : kind.ports
        var units = [settings(focusedOn: id)[keyPath: Self.unitKey(kind)]]
        units += kind.unitIDs.filter { !units.contains($0) }
        return await InverterFinder.identify(kind: kind, host: endpoint.host, ports: ports, unitIDs: units, makeTransport: makeModbusTransport)
    }

    /// "Find inverter": inverters of the selected provider's kind that answer on the home network.
    public func findInverters(plant id: String = SolarPlant.primaryID) async -> [InverterFinder.Match] {
        let kind = localInverterKind(plant: id)
        if isDemo {
            try? await Task.sleep(for: .milliseconds(800))
            return [Self.demoInverter(kind)]
        }
        return await makeInverterFinder(kind).find()
    }

    private static func demoInverter(_ kind: LocalInverterKind) -> InverterFinder.Match {
        switch kind {
        case .huawei:
            InverterFinder.Match(address: "192.168.1.50", unitID: 1, model: "SUN2000-6KTL-M1",
                                 reading: LiveInverterReading(activePowerKW: 2.9, dailyEnergyKWh: 5.1, totalEnergyKWh: 44150))
        case .sunSpec:
            InverterFinder.Match(address: "192.168.1.40:1502", unitID: 1, model: "SolarEdge SE5000H",
                                 reading: LiveInverterReading(activePowerKW: 2.9, totalEnergyKWh: 18420))
        }
    }

    /// Network scan for "Find inverter" (seam for tests).
    @ObservationIgnored var makeInverterFinder: @Sendable (LocalInverterKind) -> InverterFinder = { InverterFinder(kind: $0) }

    /// Transport for inverter reads (seam for tests); nil: each reader's own.
    @ObservationIgnored var makeModbusTransport: (@Sendable (ModbusEndpoint) -> ModbusTransport?)?

    /// While the app is on screen and the inverter answers, re-reads it every `liveInterval` seconds and updates the
    /// figures and the curve (nothing is stored; widgets keep their own refresh). Runs until cancelled.
    public func runLive() async {
        guard !isDemo else { return }
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(Self.liveInterval))
            if Task.isCancelled { return }
            await liveTick()
        }
    }

    static let liveInterval: TimeInterval = 10

    /// One live read. Internal for tests.
    func liveTick() async {
        guard !showsOnboarding, !isLoading, let account = SolarAccount(settings: settings),
              let reader = account.localReader(makeTransport: makeModbusTransport), data?.solarLinkExpired != true else { return }
        guard case .success(let reading) = await SunWattLoader.race(deadline: 5, { try await reader.readLive() }),
              SolarAccount(settings: settings) == account else { return }
        let date = Date()
        var snapshot = LocalInverterSolarClient.merge(reading, into: data?.solar, at: date, keepsBaseHome: account.provider.profile.keepsCloudHome)
        snapshot.endpointID = data?.solar?.endpointID
        if data == nil { data = SunWattData(solar: nil, prices: nil, solarStatus: .fresh, priceStatus: .stale(since: date)) }
        data?.solar = snapshot
        data?.solarStatus = .fresh
        now = date
    }

    // MARK: Kiosk link expiry

    /// When the kiosk link stops working at the latest (saved + 1 year). Set only while the "renew soon" notice
    /// should show: from 30 days before, unless the user closed it or the link already counts as expired.
    var kioskExpiryNoticeDate: Date? {
        guard settings.solarProvider.profile.setup == .kioskLink, !settings.kioskExpiryNoticeDismissed,
              !(data?.solarLinkExpired ?? false), KioskEndpoint(parsing: settings.kioskURL) != nil,
              let saved = settings.kioskLinkSavedAt,
              let expiry = Calendar.current.date(byAdding: .year, value: 1, to: saved),
              now >= expiry.addingTimeInterval(-Self.kioskNoticeLead) else { return nil }
        return expiry
    }

    /// How long before the expiry the notice appears.
    static let kioskNoticeLead: TimeInterval = 30 * 24 * 3600

    func dismissKioskExpiryNotice() {
        updateSettings { $0.kioskExpiryNoticeDismissed = true }
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
        return SolarAccount(settings: settings) != nil
    }
}
#endif
