import Foundation

/// Which plant a widget shows when there are several.
public enum WidgetPlantChoice: Hashable, Sendable {
    /// Each plant in turn, switching every `TimelineBuilder.rotationInterval`.
    case rotate
    /// Always this plant (`SolarPlant.id`); the first one when it no longer exists.
    case plant(String)
}

/// Pure timeline generation, testable without WidgetKit.
public enum TimelineBuilder {
    /// How long a rotating widget shows one plant (the widget refresh interval).
    public static let rotationInterval: TimeInterval = Constants.refreshInterval

    /// Entries for a widget. One plant: `entries(for:now:hours:)`, unchanged. Several: a fixed plant gets the same
    /// hourly entries with that plant shown; rotating adds an entry at every quarter hour, each showing the plant whose
    /// turn it is. The turn depends only on the time, so every widget and every reload agree on it.
    public static func entries(for data: SunWattData, now: Date, plant choice: WidgetPlantChoice, hours: Int = 24) -> [SunWattEntry] {
        guard data.hasSeveralPlants else { return entries(for: data, now: now, hours: hours) }
        switch choice {
        case .plant(let id):
            let shown = data.allPlants.contains { $0.id == id } ? data.showingPlant(id: id) : data
            return entries(for: shown, now: now, hours: hours)
        case .rotate:
            let count = data.allPlants.count
            let interval = rotationInterval
            let first = (now.timeIntervalSinceReferenceDate / interval).rounded(.down)
            let steps = Int(Double(hours) * 3600 / interval)
            return (0...steps).map { step in
                let date = step == 0 ? now : Date(timeIntervalSinceReferenceDate: (first + Double(step)) * interval)
                return SunWattEntry(date: date, data: data.showingPlant(at: turn(at: date, plants: count)))
            }
        }
    }

    /// Index of the plant shown at `date` by a rotating widget.
    public static func turn(at date: Date, plants count: Int) -> Int {
        guard count > 1 else { return 0 }
        return Int((date.timeIntervalSinceReferenceDate / rotationInterval).rounded(.down)) % count
    }

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
