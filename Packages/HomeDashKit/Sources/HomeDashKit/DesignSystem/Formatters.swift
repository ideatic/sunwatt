import Foundation

/// Formatting for all UI figures. Follows the user's locale (Spanish: comma decimal separator). Times in the data time zone.
public enum Format {
    /// Overridable for snapshots/tests; defaults to the user's current locale.
    nonisolated(unsafe) public static var locale = Locale.autoupdatingCurrent

    /// "4,8" (1 decimal for < 10 kW, 0 decimals above).
    public static func kW(_ value: Double?) -> String {
        guard let value else { return "–" }
        let digits = abs(value) < 10 ? 1 : 0
        return value.formatted(.number.locale(locale).precision(.fractionLength(digits)))
    }

    /// "23,4" kWh (1 decimal under 1000, grouped integer above).
    public static func kWh(_ value: Double?) -> String {
        guard let value else { return "–" }
        let digits = abs(value) < 1000 ? 1 : 0
        return value.formatted(.number.locale(locale).precision(.fractionLength(digits)))
    }

    /// "0,147" €/kWh (3 decimals by default).
    public static func eurPerKWh(_ value: Double?, decimals: Int = 3) -> String {
        guard let value else { return "–" }
        return value.formatted(.number.locale(locale).precision(.fractionLength(decimals)))
    }

    /// "14:30" in Europe/Madrid.
    public static func time(_ date: Date) -> String {
        var style = Date.FormatStyle(date: .omitted, time: .shortened, locale: locale, timeZone: Constants.madrid)
        style = style.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)
        return date.formatted(style)
    }

    /// "14 h" hour label in Europe/Madrid.
    public static func hour(_ date: Date) -> String {
        let h = Constants.madridCalendar.component(.hour, from: date)
        return "\(h) h"
    }
}
