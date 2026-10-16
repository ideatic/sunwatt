import Foundation

/// Pure timeline generation, testable without WidgetKit.
public enum TimelineBuilder {
    /// Entries: one at `now`, then one at every following Europe/Madrid hour start for `hours` hours,
    /// so the current-hour price and highlight advance without a fetch. DST-safe (23/25-hour days).
    public static func entries(for data: HomeDashData, now: Date, hours: Int = 24) -> [HomeDashEntry] {
        fatalError("Implemented by the store/timeline task")
    }

    /// When to ask WidgetKit for the next reload: `now + Constants.refreshInterval`.
    public static func nextReload(after now: Date) -> Date {
        fatalError("Implemented by the store/timeline task")
    }
}
