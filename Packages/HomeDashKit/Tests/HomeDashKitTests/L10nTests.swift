import Testing
import Foundation
@testable import HomeDashKit

@Test func catalogCompilesIntoBundle() throws {
    let es = try #require(HomeDashKit.L10n.bundle.path(forResource: "es", ofType: "lproj"))
    let bundle = try #require(Bundle(path: es))
    #expect(bundle.localizedString(forKey: "No production", value: nil, table: nil) == "Sin producción")
}
