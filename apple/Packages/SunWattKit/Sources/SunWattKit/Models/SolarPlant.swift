import Foundation

/// One solar plant: its provider, how SunWatt reaches it, and optionally its inverter on the home network.
///
/// The first plant (`SunWattSettings.primaryPlant`, id `SolarPlant.primaryID`) lives in the settings fields that
/// existed before several plants were possible, so settings, secrets and caches of a single-plant install stay where
/// they were. Further plants are `SunWattSettings.additionalPlants`. The kiosk link and the API key are secrets:
/// descriptions and reflection hide them, and the store keeps them in the keychain.
public struct SolarPlant: Hashable, Sendable, Identifiable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public static let primaryID = "primary"

    /// `primaryID` for the first plant, a UUID string for the others. Never shown.
    public var id: String
    /// Name chosen by the user; empty: the station name the provider reports, else the provider's name.
    public var name: String
    public var provider: SolarProvider
    public var kioskURL: String
    /// SolarEdge site ID or PVOutput system ID.
    public var siteID: String
    public var apiKey: String
    public var kioskLinkSavedAt: Date?
    public var kioskExpiryNoticeDismissed: Bool
    public var huaweiLocalHost: String
    public var huaweiLocalUnitID: Int
    public var sunSpecHost: String
    public var sunSpecUnitID: Int
    /// FusionSolar API account: the server; empty: `NorthboundCredentials.defaultHost`.
    public var northboundHost: String
    /// Fronius: the inverter's address on the home network.
    public var froniusHost: String

    public init(
        id: String = UUID().uuidString,
        name: String = "",
        provider: SolarProvider = .fusionSolar,
        kioskURL: String = "",
        siteID: String = "",
        apiKey: String = "",
        kioskLinkSavedAt: Date? = nil,
        kioskExpiryNoticeDismissed: Bool = false,
        huaweiLocalHost: String = "",
        huaweiLocalUnitID: Int = 1,
        sunSpecHost: String = "",
        sunSpecUnitID: Int = 1,
        northboundHost: String = "",
        froniusHost: String = ""
    ) {
        self.id = id
        self.name = name
        self.provider = provider
        self.kioskURL = kioskURL
        self.siteID = siteID
        self.apiKey = apiKey
        self.kioskLinkSavedAt = kioskLinkSavedAt
        self.kioskExpiryNoticeDismissed = kioskExpiryNoticeDismissed
        self.huaweiLocalHost = huaweiLocalHost
        self.huaweiLocalUnitID = huaweiLocalUnitID
        self.sunSpecHost = sunSpecHost
        self.sunSpecUnitID = sunSpecUnitID
        self.northboundHost = northboundHost
        self.froniusHost = froniusHost
    }

    public var isPrimary: Bool { id == Self.primaryID }

    /// The name to show: the user's, else `stationName` (what the provider calls the plant), else the provider.
    public func displayName(stationName: String?) -> String {
        let own = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !own.isEmpty { return own }
        if let stationName, !stationName.isEmpty { return stationName }
        return provider.displayName
    }

    public var description: String {
        "SolarPlant(id: \(id), name: \(name), provider: \(provider.rawValue), kioskURL: \(kioskURL.isEmpty ? "" : "***"), apiKey: \(apiKey.isEmpty ? "" : "***"))"
    }

    public var debugDescription: String { description }

    public var customMirror: Mirror {
        Mirror(self, children: ["id": id, "name": name, "provider": provider, "kioskURL": "***", "apiKey": "***"], displayStyle: .struct)
    }
}

extension SolarPlant: Codable {
    private enum CodingKeys: String, CodingKey {
        case id, name, provider, kioskURL, siteID, apiKey, kioskLinkSavedAt, kioskExpiryNoticeDismissed
        case huaweiLocalHost, huaweiLocalUnitID, sunSpecHost, sunSpecUnitID, northboundHost, froniusHost
    }

    /// Missing keys (older or newer versions) fall back to defaults.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        provider = (try? c.decodeIfPresent(SolarProvider.self, forKey: .provider)) ?? .fusionSolar
        kioskURL = try c.decodeIfPresent(String.self, forKey: .kioskURL) ?? ""
        siteID = try c.decodeIfPresent(String.self, forKey: .siteID) ?? ""
        apiKey = try c.decodeIfPresent(String.self, forKey: .apiKey) ?? ""
        kioskLinkSavedAt = try c.decodeIfPresent(Date.self, forKey: .kioskLinkSavedAt)
        kioskExpiryNoticeDismissed = try c.decodeIfPresent(Bool.self, forKey: .kioskExpiryNoticeDismissed) ?? false
        huaweiLocalHost = try c.decodeIfPresent(String.self, forKey: .huaweiLocalHost) ?? ""
        huaweiLocalUnitID = try c.decodeIfPresent(Int.self, forKey: .huaweiLocalUnitID) ?? 1
        sunSpecHost = try c.decodeIfPresent(String.self, forKey: .sunSpecHost) ?? ""
        sunSpecUnitID = try c.decodeIfPresent(Int.self, forKey: .sunSpecUnitID) ?? 1
        northboundHost = try c.decodeIfPresent(String.self, forKey: .northboundHost) ?? ""
        froniusHost = try c.decodeIfPresent(String.self, forKey: .froniusHost) ?? ""
    }
}

public extension SunWattSettings {
    /// The first plant, read from and written to the original settings fields.
    var primaryPlant: SolarPlant {
        get {
            SolarPlant(
                id: SolarPlant.primaryID, name: primaryPlantName, provider: solarProvider, kioskURL: kioskURL,
                siteID: solarSiteID, apiKey: solarAPIKey, kioskLinkSavedAt: kioskLinkSavedAt,
                kioskExpiryNoticeDismissed: kioskExpiryNoticeDismissed, huaweiLocalHost: huaweiLocalHost,
                huaweiLocalUnitID: huaweiLocalUnitID, sunSpecHost: sunSpecHost, sunSpecUnitID: sunSpecUnitID,
                northboundHost: northboundHost, froniusHost: froniusHost)
        }
        set {
            primaryPlantName = newValue.name
            solarProvider = newValue.provider
            kioskURL = newValue.kioskURL
            solarSiteID = newValue.siteID
            solarAPIKey = newValue.apiKey
            kioskLinkSavedAt = newValue.kioskLinkSavedAt
            kioskExpiryNoticeDismissed = newValue.kioskExpiryNoticeDismissed
            huaweiLocalHost = newValue.huaweiLocalHost
            huaweiLocalUnitID = newValue.huaweiLocalUnitID
            sunSpecHost = newValue.sunSpecHost
            sunSpecUnitID = newValue.sunSpecUnitID
            northboundHost = newValue.northboundHost
            froniusHost = newValue.froniusHost
        }
    }

    /// Every plant, the first one first. Always at least one (the first, possibly not set up).
    var plants: [SolarPlant] { [primaryPlant] + additionalPlants }

    /// True with more than one plant: widgets rotate, the dashboard pages, Siri gives a total.
    var hasSeveralPlants: Bool { !additionalPlants.isEmpty }

    /// The plant with this id, if any.
    func plant(id: String) -> SolarPlant? {
        plants.first { $0.id == id }
    }

    /// Replaces the plant with the same id (the first one or an additional one).
    mutating func updatePlant(_ plant: SolarPlant) {
        if plant.isPrimary {
            primaryPlant = plant
        } else if let index = additionalPlants.firstIndex(where: { $0.id == plant.id }) {
            additionalPlants[index] = plant
        }
    }

    /// These settings with `plant` in the place of the first one: everything written for "the" solar plant
    /// (accounts, loader, editors) works on any plant this way.
    func focused(on plant: SolarPlant) -> SunWattSettings {
        var copy = self
        copy.primaryPlant = plant
        return copy
    }
}
