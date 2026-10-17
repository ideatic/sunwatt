import WidgetKit
import SwiftUI
import HomeDashKit

struct WatchTimelineEntry: TimelineEntry {
    let entry: HomeDashEntry
    var date: Date { entry.date }
}

struct WatchProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchTimelineEntry {
        WatchTimelineEntry(entry: SampleData.entry(.day))
    }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (WatchTimelineEntry) -> Void) {
        let data = HomeDashLoader().cached()
        // Gallery or empty cache: sample data.
        if context.isPreview || (data.solar == nil && data.prices == nil) {
            completion(WatchTimelineEntry(entry: SampleData.entry(.day)))
        } else {
            completion(WatchTimelineEntry(entry: HomeDashEntry(date: Date(), data: data)))
        }
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<WatchTimelineEntry>) -> Void) {
        Task {
            let data = await HomeDashLoader().load()
            let now = Date()
            let entries = TimelineBuilder.entries(for: data, now: now).map(WatchTimelineEntry.init)
            completion(Timeline(entries: entries, policy: .after(TimelineBuilder.nextReload(after: now))))
        }
    }
}

struct WatchComplicationEntryView: View {
    @Environment(\.widgetFamily) private var widgetFamily
    let entry: WatchTimelineEntry

    var body: some View {
        let family = WatchComplicationFamily(widgetFamily) ?? .circular
        switch family {
        case .corner:
            cornerBody
        case .circular:
            WatchComplicationView(entry: entry.entry, family: family)
                .containerBackground(for: .widget) { AccessoryWidgetBackground() }
        default:
            WatchComplicationView(entry: entry.entry, family: family)
                .containerBackground(for: .widget) { Color.clear }
        }
    }

    @ViewBuilder
    private var cornerBody: some View {
        let content = WatchCornerContent(entry: entry.entry)
            .containerBackground(for: .widget) { Color.clear }
        if let gauge = WatchCornerGauge(entry: entry.entry) {
            content.widgetLabel {
                Gauge(value: gauge.value, in: gauge.lower...gauge.upper) {
                    Text(verbatim: "€/kWh")
                } currentValueLabel: {
                    Text(verbatim: Format.eurPerKWh(gauge.value))
                } minimumValueLabel: {
                    Text(verbatim: Format.eurPerKWh(gauge.lower, decimals: 2))
                } maximumValueLabel: {
                    Text(verbatim: Format.eurPerKWh(gauge.upper, decimals: 2))
                }
                .tint(Gradient(colors: [Theme.Price.green, Theme.Price.yellow, Theme.Price.red]))
            }
        } else {
            content
        }
    }
}

struct HomeDashWatchWidget: Widget {
    let kind = "HomeDashWatchWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: WatchProvider()) { entry in
            WatchComplicationEntryView(entry: entry)
        }
        .configurationDisplayName("SunWatt")
        .description("Solar production and electricity price.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner])
    }
}

@main
struct HomeDashWatchWidgetBundle: WidgetBundle {
    var body: some Widget {
        HomeDashWatchWidget()
    }
}
