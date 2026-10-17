import SwiftUI
import AppKit
import ImageIO
import UniformTypeIdentifiers
import HomeDashKit

// Renders the watch app pages and every complication family to PNG files (light and dark).
// Usage: swift run HomeDashWatchSnapshots <output-dir>

@MainActor
func render(_ view: WatchSnapshotView, name: String, to directory: URL) throws {
    let renderer = ImageRenderer(content: view)
    renderer.scale = 3
    guard let image = renderer.cgImage else {
        throw NSError(domain: "HomeDashWatchSnapshots", code: 1, userInfo: [NSLocalizedDescriptionKey: "No image for \(name)"])
    }
    let url = directory.appendingPathComponent("\(name).png")
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        throw NSError(domain: "HomeDashWatchSnapshots", code: 2, userInfo: [NSLocalizedDescriptionKey: "Cannot write \(url.path)"])
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else {
        throw NSError(domain: "HomeDashWatchSnapshots", code: 3, userInfo: [NSLocalizedDescriptionKey: "Cannot finalize \(url.path)"])
    }
}

@MainActor
func main() {
    let arguments = CommandLine.arguments
    guard arguments.count >= 2 else {
        FileHandle.standardError.write(Data("Usage: HomeDashWatchSnapshots <output-dir>\n".utf8))
        exit(2)
    }
    let directory = URL(fileURLWithPath: arguments[1], isDirectory: true)
    do {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        Format.locale = Locale(identifier: "es_ES")
        let locale = Locale(identifier: "es_ES")

        var notConfigured = SampleData.entry(.day)
        notConfigured.data.solar = nil
        notConfigured.data.solarStatus = .notConfigured
        let scenarios: [(String, HomeDashEntry)] = [
            ("day", SampleData.entry(.day)),
            ("night", SampleData.entry(.night)),
            ("stale", SampleData.entry(.stale)),
            ("error", SampleData.entry(.error)),
            ("unconfigured", notConfigured),
        ]
        var count = 0
        for (schemeName, scheme) in [("light", ColorScheme.light), ("dark", ColorScheme.dark)] {
            for (name, entry) in scenarios {
                let pages: [(String, WatchSnapshotView.Kind)] = [("price", .pricePage), ("solar", .solarPage)]
                for (pageName, kind) in pages {
                    try render(WatchSnapshotView(entry: entry, kind: kind, scheme: scheme, locale: locale),
                               name: "app-\(pageName)-\(name)-\(schemeName)", to: directory)
                    count += 1
                }
                for family in WatchComplicationFamily.allCases {
                    try render(WatchSnapshotView(entry: entry, kind: .complication(family), scheme: scheme, locale: locale),
                               name: "complication-\(family.rawValue)-\(name)-\(schemeName)", to: directory)
                    count += 1
                }
            }
        }
        // One German page to check translations.
        Format.locale = Locale(identifier: "de_DE")
        try render(WatchSnapshotView(entry: notConfigured, kind: .solarPage, scheme: .dark, locale: Locale(identifier: "de_DE")),
                   name: "app-solar-unconfigured-dark-de", to: directory)
        try render(WatchSnapshotView(entry: SampleData.entry(.day), kind: .pricePage, scheme: .dark, locale: Locale(identifier: "de_DE")),
                   name: "app-price-day-dark-de", to: directory)
        print("Rendered \(count + 2) images to \(directory.path)")
    } catch {
        FileHandle.standardError.write(Data("error: \(error.localizedDescription)\n".utf8))
        exit(1)
    }
}

MainActor.assumeIsolated { main() }
