import WidgetKit
import SwiftUI
import HomeDashKit

/// One timeline entry: the shared data plus the widget configuration.
struct HomeDashTimelineEntry: TimelineEntry {
    let entry: HomeDashEntry
    let content: WidgetContent
    let window: PriceWindow

    var date: Date { entry.date }
}

struct HomeDashProvider: AppIntentTimelineProvider {
    typealias Intent = HomeDashIntent
    typealias Entry = HomeDashTimelineEntry

    func placeholder(in context: Context) -> HomeDashTimelineEntry {
        HomeDashTimelineEntry(entry: SampleData.entry(.day), content: .both, window: .wholeDay)
    }

    func snapshot(for configuration: HomeDashIntent, in context: Context) async -> HomeDashTimelineEntry {
        let data = HomeDashLoader().cached()
        // Widget gallery or empty cache: show sample data.
        if context.isPreview || (data.solar == nil && data.prices == nil) {
            return HomeDashTimelineEntry(
                entry: SampleData.entry(.day), content: configuration.content.content, window: configuration.priceWindow.window)
        }
        return HomeDashTimelineEntry(
            entry: HomeDashEntry(date: Date(), data: data),
            content: configuration.content.content, window: configuration.priceWindow.window)
    }

    func timeline(for configuration: HomeDashIntent, in context: Context) async -> Timeline<HomeDashTimelineEntry> {
        let data = await HomeDashLoader().load()
        let now = Date()
        let entries = TimelineBuilder.entries(for: data, now: now).map {
            HomeDashTimelineEntry(entry: $0, content: configuration.content.content, window: configuration.priceWindow.window)
        }
        return Timeline(entries: entries, policy: .after(TimelineBuilder.nextReload(after: now)))
    }
}

struct HomeDashWidgetEntryView: View {
    @Environment(\.widgetFamily) private var widgetFamily
    let entry: HomeDashTimelineEntry

    var body: some View {
        let family = HomeDashFamily(widgetFamily) ?? .medium
        HomeDashWidgetView(entry: entry.entry, family: family, content: entry.content, window: entry.window)
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

struct HomeDashWidget: Widget {
    let kind = "HomeDashWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: HomeDashIntent.self, provider: HomeDashProvider()) { entry in
            HomeDashWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("HomeDash")
        .description("Solar production and electricity price.")
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
