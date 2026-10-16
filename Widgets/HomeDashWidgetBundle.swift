import WidgetKit
import SwiftUI
import HomeDashKit

@main
struct HomeDashWidgetBundle: WidgetBundle {
    var body: some Widget {
        PlaceholderWidget()
    }
}

struct PlaceholderEntry: TimelineEntry {
    let date: Date
}

struct PlaceholderProvider: TimelineProvider {
    func placeholder(in context: Context) -> PlaceholderEntry { PlaceholderEntry(date: .now) }
    func getSnapshot(in context: Context, completion: @escaping (PlaceholderEntry) -> Void) { completion(PlaceholderEntry(date: .now)) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<PlaceholderEntry>) -> Void) {
        completion(Timeline(entries: [PlaceholderEntry(date: .now)], policy: .after(.now.addingTimeInterval(Constants.refreshInterval))))
    }
}

struct PlaceholderWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "HomeDashPlaceholder", provider: PlaceholderProvider()) { _ in
            Text("HomeDash").containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("HomeDash")
    }
}
