import SwiftUI
import WidgetKit
import HomeDashKit

/// Holds the data the watch app shows. Fetches over the watch's own network with `HomeDashLoader`.
@MainActor
@Observable
final class WatchModel {
    private(set) var data: HomeDashData
    /// Fixed time for `-demo` (sample data is dated); nil uses the clock.
    private let fixedDate: Date?
    private let loader = HomeDashLoader()

    init(demo: Bool) {
        if demo {
            let entry = SampleData.entry(.day)
            data = entry.data
            fixedDate = entry.date
        } else {
            data = HomeDashLoader().cached()
            fixedDate = nil
        }
    }

    var isDemo: Bool { fixedDate != nil }

    func entry(at date: Date) -> HomeDashEntry {
        HomeDashEntry(date: fixedDate ?? date, data: data)
    }

    func refresh() async {
        guard !isDemo else { return }
        let fresh = await loader.load()
        if !Task.isCancelled { data = fresh }
        WidgetCenter.shared.reloadAllTimelines()
    }
}

@main
struct HomeDashWatchApp: App {
    @State private var model = WatchModel(demo: CommandLine.arguments.contains("-demo"))
    @Environment(\.scenePhase) private var scenePhase

    init() {
        WatchSettingsReceiver.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            TimelineView(.everyMinute) { context in
                WatchRootView(entry: model.entry(at: context.date), startOnSolar: CommandLine.arguments.contains("-solar"))
            }
            .task(id: scenePhase) {
                guard scenePhase == .active else { return }
                // Refresh on activation, then every refresh interval while the app stays on screen.
                while !Task.isCancelled {
                    await model.refresh()
                    try? await Task.sleep(for: .seconds(Constants.refreshInterval))
                }
            }
            .task {
                WatchSettingsReceiver.shared.onSettingsChange {
                    Task { @MainActor in await model.refresh() }
                }
            }
        }
    }
}
