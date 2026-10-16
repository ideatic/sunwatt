import Foundation
import Testing
@testable import HomeDashKit

@Suite struct KioskEndpointTests {
    @Test func kioskPageURLWithFragment() throws {
        let e = try #require(KioskEndpoint(parsing: "https://eu5.fusionsolar.example/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=AbC123"))
        #expect(e.host == "eu5.fusionsolar.example")
        #expect(e.kk == "AbC123")
        #expect(e.apiURL.absoluteString == "https://eu5.fusionsolar.example/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=AbC123")
    }

    @Test func apiURL() throws {
        let e = try #require(KioskEndpoint(parsing: "https://host.example/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=XYZ"))
        #expect(e == KioskEndpoint(host: "host.example", kk: "XYZ"))
    }

    @Test func whitespaceIsTrimmed() throws {
        let e = try #require(KioskEndpoint(parsing: "  \n https://host.example/x.html#/kiosk?kk=ABC \t\n"))
        #expect(e.kk == "ABC")
    }

    @Test func missingKK() {
        #expect(KioskEndpoint(parsing: "https://host.example/x.html#/kiosk") == nil)
        #expect(KioskEndpoint(parsing: "https://host.example/x.html#/kiosk?other=1") == nil)
        #expect(KioskEndpoint(parsing: "https://host.example/rest/a?x=1") == nil)
    }

    @Test func emptyKK() {
        #expect(KioskEndpoint(parsing: "https://host.example/x.html#/kiosk?kk=") == nil)
        #expect(KioskEndpoint(parsing: "https://host.example/rest/a?kk=") == nil)
    }

    @Test func noHost() {
        #expect(KioskEndpoint(parsing: "https:///x.html#/kiosk?kk=ABC") == nil)
        #expect(KioskEndpoint(parsing: "https://") == nil)
    }

    @Test func httpAndSchemelessRejected() {
        #expect(KioskEndpoint(parsing: "http://host.example/x.html#/kiosk?kk=ABC") == nil)
        #expect(KioskEndpoint(parsing: "host.example/x.html#/kiosk?kk=ABC") == nil)
    }

    @Test func garbage() {
        #expect(KioskEndpoint(parsing: "") == nil)
        #expect(KioskEndpoint(parsing: "   ") == nil)
        #expect(KioskEndpoint(parsing: "not a url at all") == nil)
        #expect(KioskEndpoint(parsing: "kk=ABC") == nil)
    }

    @Test func specialCharactersRoundTrip() throws {
        let original = KioskEndpoint(host: "host.example", kk: "a+b/c=d&e f%g")
        let url = original.apiURL
        #expect(url.absoluteString.contains("kk=a%2Bb%2Fc%3Dd%26e%20f%25g"))
        let parsed = try #require(KioskEndpoint(parsing: url.absoluteString))
        #expect(parsed == original)
        // Same through the fragment form.
        let page = try #require(KioskEndpoint(parsing: "https://host.example/x.html#/kiosk?kk=a%2Bb%2Fc%3Dd%26e%20f%25g"))
        #expect(page == original)
    }
}
