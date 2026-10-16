import Foundation

/// Host + `kk` token extracted from a FusionSolar kiosk URL.
/// Accepted inputs:
/// - Kiosk page URL: `https://<host>/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=XXXX` (kk is in the FRAGMENT)
/// - API URL: `https://<host>/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=XXXX` (kk is in the query)
/// Surrounding whitespace is ignored.
public struct KioskEndpoint: Hashable, Sendable {
    public var host: String
    public var kk: String

    public init(host: String, kk: String) {
        self.host = host
        self.kk = kk
    }

    /// Returns nil when the string is not a valid kiosk URL.
    public init?(parsing string: String) {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              components.scheme?.lowercased() == "https",
              let host = components.host, !host.isEmpty else { return nil }

        // kk lives in the query (API URL) or after the "?" inside the fragment (kiosk page URL).
        var items = components.queryItems ?? []
        if let fragment = components.percentEncodedFragment, let q = fragment.firstIndex(of: "?") {
            var fragmentComponents = URLComponents()
            fragmentComponents.percentEncodedQuery = String(fragment[fragment.index(after: q)...])
            items += fragmentComponents.queryItems ?? []
        }
        guard let kk = items.first(where: { $0.name == "kk" })?.value, !kk.isEmpty else { return nil }
        self.host = host
        self.kk = kk
    }

    /// `https://<host>/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=<kk>`
    public var apiURL: URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = "/rest/pvms/web/kiosk/v1/station-kiosk-file"
        // URLQueryItem leaves "+", "&", "=" unescaped in values; encode everything outside unreserved.
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let encoded = kk.addingPercentEncoding(withAllowedCharacters: allowed) ?? kk
        components.percentEncodedQuery = "kk=\(encoded)"
        return components.url ?? URL(string: "https://invalid.invalid")!
    }
}
