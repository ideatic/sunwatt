import CryptoKit
import Foundation

/// Monitoring service the solar data comes from.
public enum SolarProvider: String, Codable, Sendable, CaseIterable, Identifiable {
    /// Huawei FusionSolar public kiosk link (`SunWattSettings.kioskURL`).
    case fusionSolar
    /// SolarEdge Monitoring API: site ID + API key.
    case solarEdge
    /// pvoutput.org: system ID + API key. Many inverter brands upload there.
    case pvOutput

    public var id: String { rawValue }

    /// Brand name, the same in every language.
    public var displayName: String {
        switch self {
        case .fusionSolar: "Huawei FusionSolar"
        case .solarEdge: "SolarEdge"
        case .pvOutput: "PVOutput"
        }
    }

    /// True when the provider is set up with an ID + API key (not a link).
    public var usesAPIKey: Bool { self != .fusionSolar }

    /// Solar data younger than this is reused instead of fetched, even on an explicit refresh.
    /// SolarEdge allows 300 requests a day per site and SunWatt sends 2 per fetch: one fetch every 20 minutes is at
    /// most 144 a day per device, so an iPhone and a Watch together stay under the limit. Its curve has 15-minute
    /// points, so nothing is lost. FusionSolar and PVOutput (60 an hour) need no limit.
    public var minimumFetchInterval: TimeInterval { self == .solarEdge ? 20 * 60 : 0 }
}

/// Site/system ID + API key of SolarEdge or PVOutput. Both values are trimmed; the ID must be digits only and the
/// key letters and digits only (8 to 128). The key is a secret: descriptions and reflection hide it.
public struct SolarAPICredentials: Hashable, Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public var siteID: String
    public var apiKey: String

    /// Returns nil when either value is not valid.
    public init?(siteID: String, apiKey: String) {
        let id = siteID.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...12).contains(id.count), id.allSatisfy({ $0.isASCII && $0.isNumber }),
              (8...128).contains(key.count), key.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return nil }
        self.siteID = id
        self.apiKey = key
    }

    public var description: String { "SolarAPICredentials(siteID: \(siteID), apiKey: ***)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror { Mirror(self, children: ["siteID": siteID, "apiKey": "***"], displayStyle: .struct) }
}

/// The solar source configured in settings, validated. Nil (via `init?(settings:)`) when not set up or invalid.
public enum SolarAccount: Hashable, Sendable {
    case fusionSolar(KioskEndpoint)
    /// FusionSolar with the inverter on the home network (Modbus TCP), plus the kiosk link when there is one.
    case huaweiLocal(HuaweiLocalEndpoint, kiosk: KioskEndpoint?)
    case solarEdge(SolarAPICredentials)
    case pvOutput(SolarAPICredentials)

    public init?(settings: SunWattSettings) {
        switch settings.solarProvider {
        case .fusionSolar:
            let kiosk = KioskEndpoint(parsing: settings.kioskURL)
            if let local = HuaweiLocalEndpoint(parsing: settings.huaweiLocalHost, unitID: settings.huaweiLocalUnitID) {
                self = .huaweiLocal(local, kiosk: kiosk)
            } else if let kiosk {
                self = .fusionSolar(kiosk)
            } else {
                return nil
            }
        case .solarEdge:
            guard let credentials = SolarAPICredentials(siteID: settings.solarSiteID, apiKey: settings.solarAPIKey) else { return nil }
            self = .solarEdge(credentials)
        case .pvOutput:
            guard let credentials = SolarAPICredentials(siteID: settings.solarSiteID, apiKey: settings.solarAPIKey) else { return nil }
            self = .pvOutput(credentials)
        }
    }

    /// The kiosk link of a FusionSolar account, if any.
    public var kioskEndpoint: KioskEndpoint? {
        switch self {
        case .fusionSolar(let endpoint): endpoint
        case .huaweiLocal(_, let kiosk): kiosk
        case .solarEdge, .pvOutput: nil
        }
    }

    public var provider: SolarProvider {
        switch self {
        case .fusionSolar, .huaweiLocal: .fusionSolar
        case .solarEdge: .solarEdge
        case .pvOutput: .pvOutput
        }
    }

    /// One-way identifier of the account for caches (never the secret itself). FusionSolar keeps
    /// `KioskEndpoint.cacheID`, so caches written before other providers existed stay valid.
    public var cacheID: String {
        switch self {
        case .fusionSolar(let endpoint):
            return endpoint.cacheID
        case .huaweiLocal(let local, let kiosk):
            // Same plant as the kiosk link: keep its cache when the inverter address is added.
            if let kiosk { return kiosk.cacheID }
            return SHA256.hash(data: Data("huaweiLocal\n\(local.host)".utf8)).map { String(format: "%02x", $0) }.joined()
        case .solarEdge(let credentials), .pvOutput(let credentials):
            let text = "\(provider.rawValue)\n\(credentials.siteID)\n\(credentials.apiKey)"
            return SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
        }
    }

    /// Client for this account.
    public func client(http: HTTPClient = URLSession.sunWatt) -> SolarDataSource {
        switch self {
        case .fusionSolar(let endpoint): FusionSolarKioskClient(endpoint: endpoint, http: http)
        case .huaweiLocal(let local, let kiosk):
            HuaweiLocalSolarClient(local: HuaweiModbusClient(endpoint: local), kiosk: kiosk.map { FusionSolarKioskClient(endpoint: $0, http: http) })
        case .solarEdge(let credentials): SolarEdgeClient(credentials: credentials, http: http)
        case .pvOutput(let credentials): PVOutputClient(credentials: credentials, http: http)
        }
    }
}

public extension SunWattSettings {
    /// True when the user typed anything for the selected provider (valid or not).
    var hasSolarInput: Bool {
        func filled(_ s: String) -> Bool { !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        return solarProvider.usesAPIKey ? filled(solarSiteID) || filled(solarAPIKey) : filled(kioskURL) || filled(huaweiLocalHost)
    }

    /// The error to show when there is input for the selected provider but it is not valid.
    var invalidSolarInputError: SunWattError {
        if solarProvider.usesAPIKey { return .invalidSolarCredentials }
        let hostFilled = !huaweiLocalHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        if hostFilled, HuaweiLocalEndpoint(parsing: huaweiLocalHost, unitID: huaweiLocalUnitID) == nil { return .invalidInverterAddress }
        return .invalidKioskURL
    }
}
