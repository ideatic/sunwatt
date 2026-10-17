import Foundation

/// What a widget shows (App Intent configuration).
public enum WidgetContent: String, Codable, Sendable, CaseIterable {
    case both
    case solarOnly
    case priceOnly
}

/// Which price points a widget chart shows.
public enum PriceWindow: String, Codable, Sendable, CaseIterable {
    /// Whole current day (market time zone of the price region).
    case wholeDay
    /// Previous 6 hours + next 12 hours.
    case around
}

/// User settings shared between app and widgets through the App Group.
public struct SunWattSettings: Codable, Hashable, Sendable {
    public var kioskURL: String
    public var showTomorrowPrices: Bool
    public var priceRegion: PriceRegion
    /// True once the user finished (or skipped) the first-launch onboarding in the app.
    public var onboardingCompleted: Bool

    public init(
        kioskURL: String = "",
        showTomorrowPrices: Bool = true,
        priceRegion: PriceRegion = .spain,
        onboardingCompleted: Bool = false
    ) {
        self.kioskURL = kioskURL
        self.showTomorrowPrices = showTomorrowPrices
        self.priceRegion = priceRegion
        self.onboardingCompleted = onboardingCompleted
    }

    private enum CodingKeys: String, CodingKey { case kioskURL, showTomorrowPrices, priceRegion, onboardingCompleted }

    /// Missing keys (older stored settings) fall back to defaults.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kioskURL = try c.decodeIfPresent(String.self, forKey: .kioskURL) ?? ""
        showTomorrowPrices = try c.decodeIfPresent(Bool.self, forKey: .showTomorrowPrices) ?? true
        priceRegion = try c.decodeIfPresent(PriceRegion.self, forKey: .priceRegion) ?? .spain
        onboardingCompleted = try c.decodeIfPresent(Bool.self, forKey: .onboardingCompleted) ?? false
    }
}

public enum Constants {
    public static let appGroupID = "group.com.ideatic.sunwatt"
    public static let madrid = TimeZone(identifier: "Europe/Madrid")!
    /// Gregorian calendar fixed to Europe/Madrid. Use this for all day/hour math.
    public static let madridCalendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = madrid
        cal.locale = Locale(identifier: "es_ES")
        return cal
    }()
    /// Data older than this is shown as stale.
    public static let staleAfter: TimeInterval = 45 * 60
    /// Widget reload request interval.
    public static let refreshInterval: TimeInterval = 15 * 60
}
