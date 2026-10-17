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

    /// Local time zone of the market day.
    public var timeZone: TimeZone {
        switch self {
        case .portugal: TimeZone(identifier: "Europe/Lisbon")!
        default: Constants.madrid // CET/CEST, same rules as Europe/Paris, Berlin, Rome, ...
        }
    }

    /// Gregorian calendar in `timeZone`. Use for all price day/hour math.
    public var calendar: Calendar {
        self == .portugal ? Self.lisbonCalendar : Constants.madridCalendar
    }

    private static let lisbonCalendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Lisbon")!
        return cal
    }()

    /// True for a retail tariff (PVPC); false for wholesale day-ahead prices.
    public var isRetailTariff: Bool { self == .spain }

    /// Flag emoji for pickers.
    public var flag: String {
        switch self {
        case .spain: "🇪🇸"
        case .portugal: "🇵🇹"
        case .france: "🇫🇷"
        case .germany: "🇩🇪"
        case .austria: "🇦🇹"
        case .belgium: "🇧🇪"
        case .netherlands: "🇳🇱"
        case .italyNorth: "🇮🇹"
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
        isRetailTariff ? "PVPC" : L10n.string("Day-ahead price")
    }

    /// Data provider name (a proper noun, not translated).
    var sourceName: String {
        isRetailTariff ? "Red Eléctrica" : "Energy-Charts (Fraunhofer ISE)"
    }

    /// Energy-Charts bidding zone code. `nil` for Spain (served by Red Eléctrica).
    /// DE-LU, FR, PT, BE verified against the live API; NL, AT, IT-North see `EnergyChartsClient` notes.
    var energyChartsZone: String? {
        switch self {
        case .spain: nil
        case .portugal: "PT"
        case .france: "FR"
        case .germany: "DE-LU"
        case .austria: "AT"
        case .belgium: "BE"
        case .netherlands: "NL"
        case .italyNorth: "IT-North"
        }
    }
}
