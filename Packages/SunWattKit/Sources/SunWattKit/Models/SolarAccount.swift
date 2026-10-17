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

    /// Everything SunWatt knows about the provider. The one place to describe a new one (with its client and its
    /// `SolarAccount` case); the properties below only read it.
    public var profile: SolarProviderProfile {
        switch self {
        case .fusionSolar:
            SolarProviderProfile(
                displayName: "Huawei FusionSolar", shortName: "FusionSolar", setup: .kioskLink, portalURL: nil,
                minimumFetchInterval: 0, localInverter: .huawei, keepsCloudHome: false)
        case .solarEdge:
            // 300 requests a day per site, 2 per fetch: one fetch every 20 minutes is at most 144 a day per device, so
            // an iPhone and a Watch together stay under the limit. Its curve has 15-minute points, so nothing is lost.
            SolarProviderProfile(
                displayName: "SolarEdge", shortName: "SolarEdge", setup: .apiKey,
                portalURL: URL(string: "https://monitoring.solaredge.com"),
                minimumFetchInterval: 20 * 60, localInverter: .sunSpec, keepsCloudHome: true)
        case .pvOutput:
            // 60 requests an hour: no limit needed at SunWatt's refresh rate.
            SolarProviderProfile(
                displayName: "PVOutput", shortName: "PVOutput", setup: .apiKey,
                portalURL: URL(string: "https://pvoutput.org/account.jsp"),
                minimumFetchInterval: 0, localInverter: .sunSpec, keepsCloudHome: true)
        }
    }

    /// Brand name, the same in every language.
    public var displayName: String { profile.displayName }
    /// Short name for the provider picker (three fit side by side on a phone).
    public var shortName: String { profile.shortName }
    /// Where the user finds the ID and API key.
    public var portalURL: URL? { profile.portalURL }
    /// True when the provider is set up with an ID + API key (not a link).
    public var usesAPIKey: Bool { profile.setup == .apiKey }
    /// Solar data younger than this is reused instead of fetched, even on an explicit refresh.
    public var minimumFetchInterval: TimeInterval { profile.minimumFetchInterval }
}

/// What distinguishes one solar provider from another (`SolarProvider.profile`).
public struct SolarProviderProfile: Sendable {
    /// How the user connects the account.
    public enum Setup: Sendable {
        /// A public read-only link (FusionSolar kiosk). Links expire; the app warns before and after.
        case kioskLink
        /// An ID + API key (`SolarAPICredentials`), kept in the keychain.
        case apiKey
    }

    /// Brand name, the same in every language.
    public let displayName: String
    /// Short name for the provider picker.
    public let shortName: String
    public let setup: Setup
    /// Where the user finds the ID and API key; nil when the app explains it itself.
    public let portalURL: URL?
    /// The provider's request limit as SunWatt applies it: solar data (or a failure) younger than this is reused
    /// instead of fetched, even on an explicit refresh. 0: no limit. The inverter on the home network is still read.
    public let minimumFetchInterval: TimeInterval
    /// Which inverter protocol "Live data at home" speaks for this provider.
    public let localInverter: LocalInverterKind
    /// Keep the cloud's home consumption next to live inverter data that has no meter reading (the provider's own
    /// meter measures it). False: the inverter's meter is the only source (Huawei).
    public let keepsCloudHome: Bool
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
    /// `local`: a SunSpec inverter on the home network (optional), read for live data while it answers.
    case solarEdge(SolarAPICredentials, local: ModbusEndpoint? = nil)
    case pvOutput(SolarAPICredentials, local: ModbusEndpoint? = nil)

    /// The first plant's account.
    public init?(settings: SunWattSettings) {
        self.init(plant: settings.primaryPlant)
    }

    public init?(plant: SolarPlant) {
        switch plant.provider {
        case .fusionSolar:
            let kiosk = KioskEndpoint(parsing: plant.kioskURL)
            if let local = HuaweiLocalEndpoint(parsing: plant.huaweiLocalHost, unitID: plant.huaweiLocalUnitID) {
                self = .huaweiLocal(local, kiosk: kiosk)
            } else if let kiosk {
                self = .fusionSolar(kiosk)
            } else {
                return nil
            }
        case .solarEdge, .pvOutput:
            guard let credentials = SolarAPICredentials(siteID: plant.siteID, apiKey: plant.apiKey) else { return nil }
            // An address that does not parse is ignored, like the Huawei one: the cloud keeps working.
            let local = ModbusEndpoint(parsing: plant.sunSpecHost, unitID: plant.sunSpecUnitID)
            guard let account = Self.api(plant.provider, credentials, local: local) else { return nil }
            self = account
        }
    }

    /// The account of an ID + API key provider; nil for a provider set up otherwise.
    public static func api(_ provider: SolarProvider, _ credentials: SolarAPICredentials, local: ModbusEndpoint? = nil) -> SolarAccount? {
        switch provider {
        case .fusionSolar: nil
        case .solarEdge: .solarEdge(credentials, local: local)
        case .pvOutput: .pvOutput(credentials, local: local)
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

    /// The inverter read on the home network, if set up: the Huawei dongle or a SunSpec inverter.
    public var localInverter: ModbusEndpoint? {
        switch self {
        case .fusionSolar: nil
        case .huaweiLocal(let local, _): local
        case .solarEdge(_, let local), .pvOutput(_, let local): local
        }
    }

    /// Reader for the inverter on the home network, if set up.
    public func localReader(makeTransport: (@Sendable (ModbusEndpoint) -> ModbusTransport?)? = nil) -> (any LocalInverterReader)? {
        switch self {
        case .fusionSolar: nil
        case .huaweiLocal(let local, _): HuaweiModbusClient(endpoint: local, makeTransport: makeTransport)
        case .solarEdge(_, let local), .pvOutput(_, let local): local.map { SunSpecClient(endpoint: $0, makeTransport: makeTransport) }
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
        case .solarEdge(let credentials, _), .pvOutput(let credentials, _):
            // The inverter address is not part of it: adding one keeps the cloud cache.
            let text = "\(provider.rawValue)\n\(credentials.siteID)\n\(credentials.apiKey)"
            return SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
        }
    }

    /// Client for this account.
    public func client(http: HTTPClient = URLSession.sunWatt) -> SolarDataSource {
        switch self {
        case .fusionSolar(let endpoint): FusionSolarKioskClient(endpoint: endpoint, http: http)
        case .huaweiLocal(let local, let kiosk):
            LocalInverterSolarClient(local: HuaweiModbusClient(endpoint: local), kiosk: kiosk.map { FusionSolarKioskClient(endpoint: $0, http: http) })
        case .solarEdge(let credentials, let local):
            withLocal(local, cloud: SolarEdgeClient(credentials: credentials, http: http))
        case .pvOutput(let credentials, let local):
            withLocal(local, cloud: PVOutputClient(credentials: credentials, http: http))
        }
    }

    /// The inverter alone, without the cloud source: used while the cloud may not be asked again yet
    /// (`SolarProvider.minimumFetchInterval`). Nil without an inverter on the home network.
    public func liveOnlyClient() -> SolarDataSource? {
        localReader().map { LocalInverterSolarClient(local: $0, cloud: nil, keepsCloudHome: provider.profile.keepsCloudHome) }
    }

    private func withLocal(_ local: ModbusEndpoint?, cloud: SolarDataSource) -> SolarDataSource {
        guard let local else { return cloud }
        return LocalInverterSolarClient(local: provider.profile.localInverter.reader(local, makeTransport: nil), cloud: cloud,
                                        keepsCloudHome: provider.profile.keepsCloudHome)
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
