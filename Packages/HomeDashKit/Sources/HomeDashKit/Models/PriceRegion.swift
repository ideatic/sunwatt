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
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return cal
    }

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
