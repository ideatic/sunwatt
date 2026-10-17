#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI

private struct SheetPresenterKey: EnvironmentKey { static let defaultValue: (@MainActor (AppSheet) -> Void)? = nil }

extension EnvironmentValues {
    /// Where a settings screen presents the help sheets. Nil: the model's main sheet. The Settings window of
    /// the Mac app and the iPhone settings sheet set it so help opens over the screen that asked for it.
    var appSheetPresenter: (@MainActor (AppSheet) -> Void)? {
        get { self[SheetPresenterKey.self] }
        set { self[SheetPresenterKey.self] = newValue }
    }
}

/// Refresh button for toolbars and menus.
public struct AppRefreshButton: View {
    let model: AppModel
    var showsShortcut: Bool

    public init(model: AppModel, showsShortcut: Bool = true) {
        self.model = model
        self.showsShortcut = showsShortcut
    }

    public var body: some View {
        Button {
            Task { await model.refresh() }
        } label: {
            Label { atext("Refresh") } icon: { Image(systemName: "arrow.clockwise") }
        }
        .keyboardShortcut(showsShortcut ? KeyboardShortcut("r", modifiers: .command) : nil)
        .disabled(model.isLoading)
        .help(Text("Refresh", tableName: "App", bundle: .module))
    }
}

/// Root of the app window: onboarding or dashboard, with the sheets on top.
public struct AppRootView: View {
    @Bindable private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        Group {
            if model.showsOnboarding {
                OnboardingScreen(model: model)
            } else {
                DashboardScreen(model: model)
            }
        }
        .tint(Theme.Solar.orange)
        .sheet(item: $model.sheet) { sheet in
            SheetHost(model: model, sheet: sheet)
        }
        #if os(macOS)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                if !model.showsOnboarding { AppRefreshButton(model: model, showsShortcut: false) }
            }
        }
        .frame(minWidth: 760, minHeight: 560)
        #endif
        .task { await model.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.didBecomeActive() } }
        }
        // Restarted on every phase change; stops while the app is in the background.
        .task(id: scenePhase) {
            if scenePhase == .active {
                // The hour clock, and live inverter reads when the dongle is set up and answers.
                async let clock: Void = model.runClock()
                async let live: Void = model.runLive()
                _ = await (clock, live)
            }
        }
        .environment(\.appIdiom, AppIdiom.platformDefault)
    }
}

/// Content of a presented sheet, with its own nested sheet for help screens.
private struct SheetHost: View {
    @Bindable var model: AppModel
    let sheet: AppSheet

    var body: some View {
        Group {
            switch sheet {
            case .settings: SettingsScreen(model: model, onDone: { model.sheet = nil })
            case .widgetGuide: WidgetGuideScreen(model: model, onDone: { model.sheet = nil })
            case .kioskHelp: KioskHelpScreen(onDone: { model.sheet = nil })
            }
        }
        .environment(\.appSheetPresenter, { model.nested = $0 })
        .tint(Theme.Solar.orange)
        .sheet(item: $model.nested) { nested in
            Group {
                switch nested {
                case .widgetGuide: WidgetGuideScreen(model: model, onDone: { model.nested = nil })
                case .kioskHelp: KioskHelpScreen(onDone: { model.nested = nil })
                case .settings: SettingsScreen(model: model, onDone: { model.nested = nil })
                }
            }
            .tint(Theme.Solar.orange)
            #if os(macOS)
            .frame(minWidth: 560, idealWidth: 600, minHeight: 600, idealHeight: 680)
            #endif
        }
        #if os(macOS)
        .frame(minWidth: 560, idealWidth: 600, minHeight: 600, idealHeight: 680)
        #else
        .presentationDragIndicator(.visible)
        .presentationSizing(.page)
        #endif
    }
}

/// Content of the Settings window of the Mac app (⌘,). Help sheets open over this window.
public struct AppSettingsWindowView: View {
    @Bindable private var model: AppModel
    @State private var sheet: AppSheet?

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        SettingsScreen(model: model, onDone: nil)
            .environment(\.appSheetPresenter, { sheet = $0 })
            .tint(Theme.Solar.orange)
            .frame(width: 580, height: 700)
            .sheet(item: $sheet) { item in
                Group {
                    switch item {
                    case .widgetGuide: WidgetGuideScreen(model: model, onDone: { sheet = nil })
                    case .kioskHelp: KioskHelpScreen(onDone: { sheet = nil })
                    case .settings: EmptyView()
                    }
                }
                .tint(Theme.Solar.orange)
                .frame(minWidth: 560, idealWidth: 600, minHeight: 600, idealHeight: 680)
            }
    }
}
#endif
