import WidgetKit
import SwiftUI
import SunWattKit
import os

/// One timeline entry: the shared data plus the widget configuration.
struct SunWattTimelineEntry: TimelineEntry {
    let entry: SunWattEntry
    let content: WidgetContent
    let window: PriceWindow

    var date: Date { entry.date }
}

struct SunWattProvider: AppIntentTimelineProvider {
    typealias Intent = SunWattIntent
    typealias Entry = SunWattTimelineEntry

    func placeholder(in context: Context) -> SunWattTimelineEntry {
        SunWattTimelineEntry(entry: SampleData.entry(.day), content: .both, window: .wholeDay)
    }

    func snapshot(for configuration: SunWattIntent, in context: Context) async -> SunWattTimelineEntry {
        let data = SunWattLoader().cached()
        // Widget gallery or empty cache: show sample data.
        if context.isPreview || (data.solar == nil && data.prices == nil) {
            return SunWattTimelineEntry(
                entry: SampleData.entry(.day), content: configuration.content.content, window: configuration.priceWindow.window)
        }
        let now = Date()
        let entry = TimelineBuilder.entries(for: data, now: now, plant: plantChoice(configuration), hours: 0).first
            ?? SunWattEntry(date: now, data: data)
        return SunWattTimelineEntry(entry: entry, content: configuration.content.content, window: configuration.priceWindow.window)
    }

    func timeline(for configuration: SunWattIntent, in context: Context) async -> Timeline<SunWattTimelineEntry> {
        let store = AppGroupStore.shared
        // Where the settings come from and whether solar is set up; never the link or key itself.
        Logger(subsystem: "com.ideatic.sunwatt", category: "widget").notice(
            "Timeline: app group \(store.usesAppGroup, privacy: .public), solar set up \(SolarAccount(settings: store.settings) != nil, privacy: .public)")
        let data = await SunWattLoader().load(maxCacheAge: SunWattLoader.sharedCacheWindow)
        let now = Date()
        // Several plants: rotating (default) or the plant chosen in the widget's settings. One plant: as always.
        let entries = TimelineBuilder.entries(for: data, now: now, plant: plantChoice(configuration)).map {
            SunWattTimelineEntry(entry: $0, content: configuration.content.content, window: configuration.priceWindow.window)
        }
        return Timeline(entries: entries, policy: .after(TimelineBuilder.nextReload(after: now)))
    }

    private func plantChoice(_ configuration: SunWattIntent) -> WidgetPlantChoice {
        configuration.plant?.choice ?? .rotate
    }
}

struct SunWattWidgetEntryView: View {
    @Environment(\.widgetFamily) private var widgetFamily
    let entry: SunWattTimelineEntry

    var body: some View {
        let family = SunWattFamily(widgetFamily) ?? .medium
        // A tap opens the app, which loads fresh data and reloads the widgets (`SunWattLink.refreshURL`). An in-place
        // refresh only dimmed the widget for a moment and did not show what was happening.
        SunWattWidgetView(entry: entry.entry, family: family, content: entry.content, window: entry.window)
            .widgetURL(SunWattLink.refreshURL)
            .containerBackground(for: .widget) {
                if family == .accessoryCircular {
                    AccessoryWidgetBackground()
                } else if family == .accessoryRectangular {
                    Color.clear
                } else {
                    WidgetBackground()
                }
            }
    }
}

struct SunWattWidget: Widget {
    let kind = "SunWattWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: SunWattIntent.self, provider: SunWattProvider()) { entry in
            SunWattWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("SunWatt")
        .description("Solar production and electricity price. Tap to open the app with fresh data.")
        .supportedFamilies(Self.families)
    }

    private static var families: [WidgetFamily] {
        #if os(iOS)
        [.systemSmall, .systemMedium, .systemLarge, .accessoryRectangular, .accessoryCircular]
        #else
        [.systemSmall, .systemMedium, .systemLarge]
        #endif
    }
}
