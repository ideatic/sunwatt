import Foundation

/// Country / bidding zone whose electricity price is shown.
/// Spain uses the regulated retail PVPC price from Red Eléctrica; the others use day-ahead
/// wholesale prices from Energy-Charts (Fraunhofer ISE, CC BY 4.0).
public enum PriceRegion: String, Codable, Sendable, CaseIterable, Identifiable {
    case spain
    case portugal
    case france
    case germany
    case austria
    case belgium
    case netherlands
    case italyNorth

    public var id: String { rawValue }

    /// Everything SunWatt knows about the region. The one place to describe a new one (with its name in `displayName`
    /// and a contract case in `ProviderContractTests`); the properties below only read it.
    public var profile: PriceRegionProfile {
        switch self {
        case .spain: PriceRegionProfile(timeZone: Constants.madrid, flag: "🇪🇸", source: .redElectrica)
        case .portugal: PriceRegionProfile(timeZone: TimeZone(identifier: "Europe/Lisbon")!, flag: "🇵🇹", source: .energyCharts(zone: "PT"))
        // CET/CEST: the same rules as Europe/Madrid, so the Madrid calendar serves them.
        case .france: PriceRegionProfile(timeZone: Constants.madrid, flag: "🇫🇷", source: .energyCharts(zone: "FR"))
        case .germany: PriceRegionProfile(timeZone: Constants.madrid, flag: "🇩🇪", source: .energyCharts(zone: "DE-LU"))
        case .austria: PriceRegionProfile(timeZone: Constants.madrid, flag: "🇦🇹", source: .energyCharts(zone: "AT"))
        case .belgium: PriceRegionProfile(timeZone: Constants.madrid, flag: "🇧🇪", source: .energyCharts(zone: "BE"))
        case .netherlands: PriceRegionProfile(timeZone: Constants.madrid, flag: "🇳🇱", source: .energyCharts(zone: "NL"))
        case .italyNorth: PriceRegionProfile(timeZone: Constants.madrid, flag: "🇮🇹", source: .energyCharts(zone: "IT-North"))
        }
    }

    /// Local time zone of the market day.
    public var timeZone: TimeZone { profile.timeZone }

    /// Gregorian calendar in `timeZone`. Use for all price day/hour math.
    public var calendar: Calendar { Self.calendars[self] ?? Constants.madridCalendar }

    private static let calendars: [PriceRegion: Calendar] = Dictionary(uniqueKeysWithValues: allCases.map { region in
        let zone = region.timeZone
        if zone.identifier == Constants.madrid.identifier { return (region, Constants.madridCalendar) }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        return (region, calendar)
    })

    /// True for a retail tariff (PVPC); false for wholesale day-ahead prices.
    public var isRetailTariff: Bool { profile.source.isRetailTariff }

    /// Flag emoji for pickers.
    public var flag: String { profile.flag }
}

/// What distinguishes one price region from another (`PriceRegion.profile`).
public struct PriceRegionProfile: Sendable {
    /// Time zone of the market day: day and hour math, labels.
    public let timeZone: TimeZone
    /// Flag emoji for pickers.
    public let flag: String
    /// Who publishes the prices, and what kind of price they are.
    public let source: PriceSource
}

/// Who publishes a region's prices. Each source has a client that keeps the `PriceDataSource` contract.
public enum PriceSource: Hashable, Sendable {
    /// Red Eléctrica's regulated retail tariff (PVPC, Spain), `PVPCClient`: one request per market day.
    case redElectrica
    /// Energy-Charts (Fraunhofer ISE) day-ahead wholesale prices of a bidding zone, `EnergyChartsClient`. CC BY 4.0: the
    /// attribution must stay visible. Answers HTTP 429 to parallel requests: today and tomorrow in one request, and the
    /// loader's shared cache window keeps widgets from asking at once.
    case energyCharts(zone: String)

    /// True for a retail tariff; false for wholesale day-ahead prices (never presented as what the user pays).
    public var isRetailTariff: Bool {
        switch self {
        case .redElectrica: true
        case .energyCharts: false
        }
    }

    /// Publisher name (a proper noun, not translated).
    public var name: String {
        switch self {
        case .redElectrica: "Red Eléctrica"
        case .energyCharts: "Energy-Charts (Fraunhofer ISE)"
        }
    }

    /// Licence whose attribution must stay visible next to the prices; nil when none is required.
    public var license: String? {
        switch self {
        case .redElectrica: nil
        case .energyCharts: "CC BY 4.0"
        }
    }

    /// Client for `region`'s prices from this source.
    public func client(for region: PriceRegion, http: HTTPClient = URLSession.sunWatt) -> PriceDataSource {
        switch self {
        case .redElectrica: PVPCClient(http: http)
        case .energyCharts: EnergyChartsClient(region: region, http: http)
        }
    }
}

public extension PriceRegion {
    /// Localized country / zone name.
    var displayName: String {
        switch self {
        case .spain: L10n.string("Spain")
        case .portugal: L10n.string("Portugal")
        case .france: L10n.string("France")
        case .germany: L10n.string("Germany & Luxembourg")
        case .austria: L10n.string("Austria")
        case .belgium: L10n.string("Belgium")
        case .netherlands: L10n.string("Netherlands")
        case .italyNorth: L10n.string("Italy (North)")
        }
    }

    /// Name of the price shown: the PVPC tariff in Spain, the day-ahead wholesale price elsewhere.
    var tariffName: String {
        switch profile.source {
        case .redElectrica: "PVPC"
        case .energyCharts: L10n.string("Day-ahead price")
        }
    }

    /// Data provider name (a proper noun, not translated).
    var sourceName: String { profile.source.name }

    /// Energy-Charts bidding zone code; nil for a region served by another source.
    /// DE-LU, FR, PT, BE verified against the live API; NL, AT, IT-North see `EnergyChartsClient` notes.
    var energyChartsZone: String? {
        if case .energyCharts(let zone) = profile.source { zone } else { nil }
    }
}
