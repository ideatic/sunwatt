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
    /// Huawei FusionSolar Northbound API ("API account"): user name + system code of an account the installer creates.
    /// Shown as the second way to connect FusionSolar, not as its own button.
    case fusionSolarAPI
    /// A Fronius inverter read through its Solar API on the home network (`SunWattSettings.froniusHost`). No cloud:
    /// Fronius's Solar.web API is paid. Away from home the last data stays on screen.
    case fronius

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
        case .fusionSolarAPI:
            // Huawei limits how often each interface may be called (HTTP 200 with failCode 407) and allows one session
            // per account; a fetch is 3 to 5 calls. Every 10 minutes keeps an iPhone and a Watch inside the limits.
            SolarProviderProfile(
                displayName: "Huawei FusionSolar API", shortName: "FusionSolar", setup: .northboundAccount,
                portalURL: URL(string: "https://\(NorthboundCredentials.defaultHost)"),
                minimumFetchInterval: 10 * 60, localInverter: .huawei, keepsCloudHome: false, pickerGroup: .fusionSolar)
        case .fronius:
            // The inverter on the home network only: no request limit, no cloud consumption to keep.
            SolarProviderProfile(
                displayName: "Fronius", shortName: "Fronius", setup: .homeNetwork, portalURL: nil,
                minimumFetchInterval: 0, localInverter: .fronius, keepsCloudHome: false)
        }
    }

    /// The providers with their own button in the picker (the others are a second way to connect one of them).
    public static var pickerCases: [SolarProvider] { allCases.filter { $0.pickerGroup == $0 } }

    /// The picker button that shows as selected for this provider.
    public var pickerGroup: SolarProvider { profile.pickerGroup ?? self }

    /// Brand name, the same in every language.
    public var displayName: String { profile.displayName }
    /// Short name for the provider picker (four fit side by side on a phone).
    public var shortName: String { profile.shortName }
    /// Where the user finds the ID and API key.
    public var portalURL: URL? { profile.portalURL }
    /// True when the provider is set up with an ID + API key (not a link).
    public var usesAPIKey: Bool { profile.setup == .apiKey }
    /// True when the provider's credentials are kept in `solarSiteID` and `solarAPIKey` (ID + key, or user name +
    /// system code): switching between such providers clears them.
    public var usesCredentialFields: Bool {
        switch profile.setup {
        case .apiKey, .northboundAccount: true
        case .kioskLink, .homeNetwork: false
        }
    }
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
        /// A user name + system code (`NorthboundCredentials`) on a FusionSolar server; the code in the keychain.
        case northboundAccount
        /// Only the inverter's address on the home network (Fronius); no account, no secret.
        case homeNetwork
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
    /// The picker button this provider belongs to when it is another way to connect that provider; nil: its own.
    public let pickerGroup: SolarProvider?

    init(displayName: String, shortName: String, setup: Setup, portalURL: URL?, minimumFetchInterval: TimeInterval,
         localInverter: LocalInverterKind, keepsCloudHome: Bool, pickerGroup: SolarProvider? = nil) {
        self.displayName = displayName
        self.shortName = shortName
        self.setup = setup
        self.portalURL = portalURL
        self.minimumFetchInterval = minimumFetchInterval
        self.localInverter = localInverter
        self.keepsCloudHome = keepsCloudHome
        self.pickerGroup = pickerGroup
    }
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
    /// FusionSolar kiosk link; `local`: the Huawei dongle on the home network (optional). At least one of the two is
    /// set (`init(plant:)` makes sure): the dongle alone is enough at home.
    case fusionSolar(KioskEndpoint?, local: ModbusEndpoint? = nil)
    /// `local`: a SunSpec inverter on the home network (optional), read for live data while it answers.
    case solarEdge(SolarAPICredentials, local: ModbusEndpoint? = nil)
    case pvOutput(SolarAPICredentials, local: ModbusEndpoint? = nil)
    /// FusionSolar Northbound API account; `local`: the Huawei dongle on the home network (optional).
    case fusionSolarAPI(NorthboundCredentials, local: ModbusEndpoint? = nil)
    /// A Fronius inverter's Solar API on the home network (port 80 unless the address names another).
    case fronius(local: ModbusEndpoint)

    /// The first plant's account.
    public init?(settings: SunWattSettings) {
        self.init(plant: settings.primaryPlant)
    }

    public init?(plant: SolarPlant) {
        switch plant.provider {
        case .fusionSolar:
            let kiosk = KioskEndpoint(parsing: plant.kioskURL)
            let local = HuaweiLocalEndpoint(parsing: plant.huaweiLocalHost, unitID: plant.huaweiLocalUnitID)
            guard kiosk != nil || local != nil else { return nil }
            self = .fusionSolar(kiosk, local: local)
        case .solarEdge, .pvOutput:
            guard let credentials = SolarAPICredentials(siteID: plant.siteID, apiKey: plant.apiKey) else { return nil }
            // An address that does not parse is ignored, like the Huawei one: the cloud keeps working.
            let local = ModbusEndpoint(parsing: plant.sunSpecHost, unitID: plant.sunSpecUnitID)
            guard let account = Self.api(plant.provider, credentials, local: local) else { return nil }
            self = account
        case .fusionSolarAPI:
            guard let credentials = NorthboundCredentials(host: plant.northboundHost, userName: plant.siteID, systemCode: plant.apiKey) else { return nil }
            self = .fusionSolarAPI(credentials, local: HuaweiLocalEndpoint(parsing: plant.huaweiLocalHost, unitID: plant.huaweiLocalUnitID))
        case .fronius:
            guard let local = ModbusEndpoint(parsing: plant.froniusHost, defaultPort: FroniusSolarAPIClient.port) else { return nil }
            self = .fronius(local: local)
        }
    }

    /// The account of an ID + API key provider; nil for a provider set up otherwise.
    public static func api(_ provider: SolarProvider, _ credentials: SolarAPICredentials, local: ModbusEndpoint? = nil) -> SolarAccount? {
        switch provider {
        case .fusionSolar, .fusionSolarAPI, .fronius: nil
        case .solarEdge: .solarEdge(credentials, local: local)
        case .pvOutput: .pvOutput(credentials, local: local)
        }
    }

    /// The kiosk link of a FusionSolar account, if any.
    public var kioskEndpoint: KioskEndpoint? {
        switch self {
        case .fusionSolar(let kiosk, _): kiosk
        case .solarEdge, .pvOutput, .fusionSolarAPI, .fronius: nil
        }
    }

    /// The inverter read on the home network, if set up: the Huawei dongle, a SunSpec inverter or a Fronius inverter.
    public var localInverter: ModbusEndpoint? {
        switch self {
        case .fusionSolar(_, let local), .solarEdge(_, let local), .pvOutput(_, let local), .fusionSolarAPI(_, let local): local
        case .fronius(let local): local
        }
    }

    /// Reader for the inverter on the home network, if set up.
    public func localReader(makeTransport: (@Sendable (ModbusEndpoint) -> ModbusTransport?)? = nil) -> (any LocalInverterReader)? {
        localInverter.map { provider.profile.localInverter.reader($0, makeTransport: makeTransport) }
    }

    public var provider: SolarProvider {
        switch self {
        case .fusionSolar: .fusionSolar
        case .solarEdge: .solarEdge
        case .pvOutput: .pvOutput
        case .fusionSolarAPI: .fusionSolarAPI
        case .fronius: .fronius
        }
    }

    /// One-way identifier of the account for caches (never the secret itself). FusionSolar keeps
    /// `KioskEndpoint.cacheID`, so caches written before other providers existed stay valid.
    public var cacheID: String {
        switch self {
        case .fusionSolar(let kiosk, let local):
            // Same plant as the kiosk link: keep its cache when the inverter address is added.
            if let kiosk { return kiosk.cacheID }
            return SHA256.hash(data: Data("huaweiLocal\n\(local?.host ?? "")".utf8)).map { String(format: "%02x", $0) }.joined()
        case .solarEdge(let credentials, _), .pvOutput(let credentials, _):
            // The inverter address is not part of it: adding one keeps the cloud cache.
            let text = "\(provider.rawValue)\n\(credentials.siteID)\n\(credentials.apiKey)"
            return SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
        case .fusionSolarAPI(let credentials, _):
            let text = "\(provider.rawValue)\n\(credentials.host)\n\(credentials.userName)\n\(credentials.systemCode)"
            return SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
        case .fronius(let local):
            let text = "\(provider.rawValue)\n\(local.host)\n\(local.port)"
            return SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
        }
    }

    /// Client for this account.
    public func client(http: HTTPClient = URLSession.sunWatt) -> SolarDataSource {
        switch self {
        case .fusionSolar(let kiosk, let local):
            withLocal(local, cloud: kiosk.map { FusionSolarKioskClient(endpoint: $0, http: http) })
        case .solarEdge(let credentials, let local):
            withLocal(local, cloud: SolarEdgeClient(credentials: credentials, http: http))
        case .pvOutput(let credentials, let local):
            withLocal(local, cloud: PVOutputClient(credentials: credentials, http: http))
        case .fusionSolarAPI(let credentials, let local):
            withLocal(local, cloud: NorthboundClient(credentials: credentials, http: http))
        case .fronius(let local):
            withLocal(local, cloud: nil)
        }
    }

    /// The inverter alone, without the cloud source: used while the cloud may not be asked again yet
    /// (`SolarProvider.minimumFetchInterval`). Nil without an inverter on the home network.
    public func liveOnlyClient() -> SolarDataSource? {
        localReader().map { LocalInverterSolarClient(local: $0, cloud: nil, keepsCloudHome: provider.profile.keepsCloudHome) }
    }

    /// The cloud source, with the inverter on the home network in front of it when one is set up. `cloud` is nil for a
    /// FusionSolar plant set up with the dongle alone and for Fronius.
    private func withLocal(_ local: ModbusEndpoint?, cloud: SolarDataSource?) -> SolarDataSource {
        if let local {
            return LocalInverterSolarClient(local: provider.profile.localInverter.reader(local, makeTransport: nil), cloud: cloud,
                                            keepsCloudHome: provider.profile.keepsCloudHome)
        }
        return cloud ?? NotConfiguredSolarSource()
    }
}

/// Stands in for an account with neither a cloud source nor an inverter, which `SolarAccount.init(plant:)` never
/// builds; the loader shows "not configured".
private struct NotConfiguredSolarSource: SolarDataSource {
    func fetch() async throws -> SolarSnapshot { throw SunWattError.notConfigured }
}

public extension SunWattSettings {
    /// True when the user typed anything for the selected provider (valid or not).
    var hasSolarInput: Bool {
        func filled(_ s: String) -> Bool { !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        switch solarProvider.profile.setup {
        case .kioskLink: return filled(kioskURL) || filled(huaweiLocalHost)
        case .apiKey, .northboundAccount: return filled(solarSiteID) || filled(solarAPIKey)
        case .homeNetwork: return filled(froniusHost)
        }
    }

    /// The error to show when there is input for the selected provider but it is not valid.
    var invalidSolarInputError: SunWattError {
        switch solarProvider.profile.setup {
        case .apiKey: return .invalidSolarCredentials
        case .northboundAccount: return .invalidNorthboundAccount
        case .homeNetwork: return .invalidInverterAddress
        case .kioskLink: break
        }
        let hostFilled = !huaweiLocalHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        if hostFilled, HuaweiLocalEndpoint(parsing: huaweiLocalHost, unitID: huaweiLocalUnitID) == nil { return .invalidInverterAddress }
        return .invalidKioskURL
    }
}
