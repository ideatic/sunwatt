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
    case solarEdge(SolarAPICredentials)
    case pvOutput(SolarAPICredentials)

    public init?(settings: SunWattSettings) {
        switch settings.solarProvider {
        case .fusionSolar:
            guard let endpoint = KioskEndpoint(parsing: settings.kioskURL) else { return nil }
            self = .fusionSolar(endpoint)
        case .solarEdge:
            guard let credentials = SolarAPICredentials(siteID: settings.solarSiteID, apiKey: settings.solarAPIKey) else { return nil }
            self = .solarEdge(credentials)
        case .pvOutput:
            guard let credentials = SolarAPICredentials(siteID: settings.solarSiteID, apiKey: settings.solarAPIKey) else { return nil }
            self = .pvOutput(credentials)
        }
    }

    public var provider: SolarProvider {
        switch self {
        case .fusionSolar: .fusionSolar
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
        case .solarEdge(let credentials), .pvOutput(let credentials):
            let text = "\(provider.rawValue)\n\(credentials.siteID)\n\(credentials.apiKey)"
            return SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
        }
    }

    /// Client for this account.
    public func client(http: HTTPClient = URLSession.sunWatt) -> SolarDataSource {
        switch self {
        case .fusionSolar(let endpoint): FusionSolarKioskClient(endpoint: endpoint, http: http)
        case .solarEdge(let credentials): SolarEdgeClient(credentials: credentials, http: http)
        case .pvOutput(let credentials): PVOutputClient(credentials: credentials, http: http)
        }
    }
}

public extension SunWattSettings {
    /// True when the user typed anything for the selected provider (valid or not).
    var hasSolarInput: Bool {
        func filled(_ s: String) -> Bool { !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        return solarProvider.usesAPIKey ? filled(solarSiteID) || filled(solarAPIKey) : filled(kioskURL)
    }

    /// The error to show when there is input for the selected provider but it is not valid.
    var invalidSolarInputError: SunWattError {
        solarProvider.usesAPIKey ? .invalidSolarCredentials : .invalidKioskURL
    }
}
