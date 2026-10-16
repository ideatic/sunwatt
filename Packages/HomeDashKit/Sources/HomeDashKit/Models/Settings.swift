import Foundation

/// What a widget shows (App Intent configuration).
public enum WidgetContent: String, Codable, Sendable, CaseIterable {
    case both
    case solarOnly
    case priceOnly
}

/// Which price points a widget chart shows.
public enum PriceWindow: String, Codable, Sendable, CaseIterable {
    /// Whole current day (Europe/Madrid).
    case wholeDay
    /// Previous 6 hours + next 12 hours.
    case around
}

/// User settings shared between app and widgets through the App Group.
public struct HomeDashSettings: Codable, Hashable, Sendable {
    public var kioskURL: String
    public var showTomorrowPrices: Bool

    public init(kioskURL: String = "", showTomorrowPrices: Bool = true) {
        self.kioskURL = kioskURL
        self.showTomorrowPrices = showTomorrowPrices
    }
}

public enum Constants {
    public static let appGroupID = "group.com.ideatic.homedash"
    public static let madrid = TimeZone(identifier: "Europe/Madrid")!
    /// Gregorian calendar fixed to Europe/Madrid. Use this for all day/hour math.
    public static var madridCalendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = madrid
        cal.locale = Locale(identifier: "es_ES")
        return cal
    }
    /// Data older than this is shown as stale.
    public static let staleAfter: TimeInterval = 45 * 60
    /// Widget reload request interval.
    public static let refreshInterval: TimeInterval = 15 * 60
}
