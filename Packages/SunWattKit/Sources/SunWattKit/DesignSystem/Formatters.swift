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

    /// Energy with its unit: kWh as `kWh(_:)` below 10.000 kWh, else MWh with 1 decimal ("17,1" MWh), so totals such
    /// as a year's production stay short.
    public static func energy(_ kWh: Double?) -> (value: String, unit: String) {
        guard let kWh, abs(kWh) >= 10_000 else { return (Self.kWh(kWh), "kWh") }
        return ((kWh / 1000).formatted(.number.locale(locale).precision(.fractionLength(1))), "MWh")
    }

    /// "0,147" €/kWh (3 decimals by default).
    public static func eurPerKWh(_ value: Double?, decimals: Int = 3) -> String {
        guard let value else { return "–" }
        // A value that rounds to zero must not print as "-0,000".
        let scale = pow(10.0, Double(decimals))
        let normalized = (value * scale).rounded() == 0 ? 0 : value
        return normalized.formatted(.number.locale(locale).precision(.fractionLength(decimals)))
    }

    /// "14:30" in `timeZone` (Europe/Madrid by default).
    public static func time(_ date: Date, timeZone: TimeZone = Constants.madrid) -> String {
        var style = Date.FormatStyle(date: .omitted, time: .shortened, locale: locale, timeZone: timeZone)
        style = style.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)
        return date.formatted(style)
    }

    /// "14 h" hour label in `timeZone` (Europe/Madrid by default).
    public static func hour(_ date: Date, timeZone: TimeZone = Constants.madrid) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return "\(cal.component(.hour, from: date)) h"
    }
}
