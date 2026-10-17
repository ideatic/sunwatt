import Foundation

/// URLs the widgets open the app with (`sunwatt://…`, scheme registered in project.yml).
public enum SunWattLink: Equatable, Sendable {
    /// A widget tap: open the app and load fresh data.
    case refresh

    public static let scheme = "sunwatt"

    /// The URL for `.refresh`. A fixed, valid URL.
    public static let refreshURL = URL(string: "\(scheme)://refresh")!

    /// Parses a URL the app was opened with. Other schemes and unknown hosts are nil.
    public init?(url: URL) {
        guard url.scheme?.lowercased() == Self.scheme else { return nil }
        switch url.host?.lowercased() {
        case "refresh": self = .refresh
        default: return nil
        }
    }
}
