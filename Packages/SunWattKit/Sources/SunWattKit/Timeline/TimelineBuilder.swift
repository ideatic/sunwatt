import Foundation

/// Pure timeline generation, testable without WidgetKit.
public enum TimelineBuilder {
    /// Entries: one at `now`, then one at every following Europe/Madrid hour start for `hours` hours,
    /// so the current-hour price and highlight advance without a fetch. DST-safe (23/25-hour days).
    public static func entries(for data: SunWattData, now: Date, hours: Int = 24) -> [SunWattEntry] {
        var result = [SunWattEntry(date: now, data: data)]
        guard hours > 0,
              let firstBoundary = Constants.madridCalendar.dateInterval(of: .hour, for: now)?.end
        else { return result }
        // Madrid DST shifts are whole hours, so absolute 3600 s steps stay on wall-clock hour starts.
        for i in 0..<hours {
            result.append(SunWattEntry(date: firstBoundary.addingTimeInterval(Double(i) * 3600), data: data))
        }
        return result
    }

    /// When to ask WidgetKit for the next reload: `now + Constants.refreshInterval`.
    public static func nextReload(after now: Date) -> Date {
        now.addingTimeInterval(Constants.refreshInterval)
    }
}
