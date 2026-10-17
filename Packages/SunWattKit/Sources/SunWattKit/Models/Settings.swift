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
    /// Where the solar data comes from. FusionSolar reads `kioskURL`; SolarEdge and PVOutput read the ID and key.
    public var solarProvider: SolarProvider
    /// SolarEdge site ID or PVOutput system ID.
    public var solarSiteID: String
    /// SolarEdge or PVOutput API key. A secret, like the kiosk link: never log it.
    public var solarAPIKey: String
    /// When the current kiosk link was saved in SunWatt. Kiosk links expire one year after Huawei creates them, so
    /// this gives a latest-possible expiry (the link may be older). Nil without a link.
    public var kioskLinkSavedAt: Date?
    /// The user closed the "link expires soon" notice for the current link.
    public var kioskExpiryNoticeDismissed: Bool
    /// FusionSolar only, optional: IP address or host name of the Huawei dongle on the home network (Modbus TCP).
    /// When it answers, SunWatt shows live data from the inverter; elsewhere it uses the kiosk link.
    public var huaweiLocalHost: String
    /// Modbus unit ID of the inverter behind the dongle (found by "Test inverter"; usually 1).
    public var huaweiLocalUnitID: Int
    /// SolarEdge and PVOutput only, optional: address of a SunSpec inverter on the home network ("host" or
    /// "host:port", e.g. SolarEdge "192.168.1.40:1502"). When it answers, SunWatt shows its live power.
    public var sunSpecHost: String
    /// Modbus unit ID of the SunSpec inverter (found by "Test inverter"; 1, SMA 126).
    public var sunSpecUnitID: Int
    /// Name of the first plant (the fields above), shown when there are several. Empty: the provider's station name.
    public var primaryPlantName: String
    /// Further plants (`SolarPlant`). Empty for a single plant: then nothing in the UI changes.
    public var additionalPlants: [SolarPlant]

    public init(
        kioskURL: String = "",
        showTomorrowPrices: Bool = true,
        priceRegion: PriceRegion = .spain,
        onboardingCompleted: Bool = false,
        solarProvider: SolarProvider = .fusionSolar,
        solarSiteID: String = "",
        solarAPIKey: String = "",
        kioskLinkSavedAt: Date? = nil,
        kioskExpiryNoticeDismissed: Bool = false,
        huaweiLocalHost: String = "",
        huaweiLocalUnitID: Int = 1,
        sunSpecHost: String = "",
        sunSpecUnitID: Int = 1,
        primaryPlantName: String = "",
        additionalPlants: [SolarPlant] = []
    ) {
        self.kioskURL = kioskURL
        self.showTomorrowPrices = showTomorrowPrices
        self.priceRegion = priceRegion
        self.onboardingCompleted = onboardingCompleted
        self.solarProvider = solarProvider
        self.solarSiteID = solarSiteID
        self.solarAPIKey = solarAPIKey
        self.kioskLinkSavedAt = kioskLinkSavedAt
        self.kioskExpiryNoticeDismissed = kioskExpiryNoticeDismissed
        self.huaweiLocalHost = huaweiLocalHost
        self.huaweiLocalUnitID = huaweiLocalUnitID
        self.sunSpecHost = sunSpecHost
        self.sunSpecUnitID = sunSpecUnitID
        self.primaryPlantName = primaryPlantName
        self.additionalPlants = additionalPlants
    }

    private enum CodingKeys: String, CodingKey {
        case kioskURL, showTomorrowPrices, priceRegion, onboardingCompleted, solarProvider, solarSiteID, solarAPIKey
        case kioskLinkSavedAt, kioskExpiryNoticeDismissed, huaweiLocalHost, huaweiLocalUnitID, sunSpecHost, sunSpecUnitID
        case primaryPlantName, additionalPlants
    }

    /// Missing keys (older stored settings) fall back to defaults.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kioskURL = try c.decodeIfPresent(String.self, forKey: .kioskURL) ?? ""
        showTomorrowPrices = try c.decodeIfPresent(Bool.self, forKey: .showTomorrowPrices) ?? true
        priceRegion = try c.decodeIfPresent(PriceRegion.self, forKey: .priceRegion) ?? .spain
        onboardingCompleted = try c.decodeIfPresent(Bool.self, forKey: .onboardingCompleted) ?? false
        // An unknown provider (settings from a newer app version) falls back to FusionSolar.
        solarProvider = (try? c.decodeIfPresent(SolarProvider.self, forKey: .solarProvider)) ?? .fusionSolar
        solarSiteID = try c.decodeIfPresent(String.self, forKey: .solarSiteID) ?? ""
        solarAPIKey = try c.decodeIfPresent(String.self, forKey: .solarAPIKey) ?? ""
        kioskLinkSavedAt = try c.decodeIfPresent(Date.self, forKey: .kioskLinkSavedAt)
        kioskExpiryNoticeDismissed = try c.decodeIfPresent(Bool.self, forKey: .kioskExpiryNoticeDismissed) ?? false
        huaweiLocalHost = try c.decodeIfPresent(String.self, forKey: .huaweiLocalHost) ?? ""
        huaweiLocalUnitID = try c.decodeIfPresent(Int.self, forKey: .huaweiLocalUnitID) ?? 1
        sunSpecHost = try c.decodeIfPresent(String.self, forKey: .sunSpecHost) ?? ""
        sunSpecUnitID = try c.decodeIfPresent(Int.self, forKey: .sunSpecUnitID) ?? 1
        primaryPlantName = try c.decodeIfPresent(String.self, forKey: .primaryPlantName) ?? ""
        // A plant that cannot be read is dropped rather than losing every setting.
        additionalPlants = (try? c.decodeIfPresent([SolarPlant].self, forKey: .additionalPlants)) ?? []
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
