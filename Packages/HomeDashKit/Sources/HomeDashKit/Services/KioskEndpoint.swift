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
        fatalError("Implemented by the FusionSolar client task")
    }

    /// `https://<host>/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=<kk>`
    public var apiURL: URL {
        fatalError("Implemented by the FusionSolar client task")
    }
}
