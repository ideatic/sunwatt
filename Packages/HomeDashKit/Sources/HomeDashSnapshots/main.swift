import SwiftUI
import AppKit
import ImageIO
import UniformTypeIdentifiers
import WidgetKit
import HomeDashKit

// Renders every widget family x scheme x state to PNG files.
// Usage: swift run HomeDashSnapshots <output-dir>

@MainActor
struct Job {
    var name: String
    var entry: HomeDashEntry
    var family: HomeDashFamily
    var content: WidgetContent = .both
    var window: PriceWindow = .wholeDay
    var scheme: ColorScheme = .light
    var mode: WidgetRenderingMode = .fullColor
    var locale = "es_ES"
}

@MainActor
func render(_ job: Job, to directory: URL) throws {
    Format.locale = Locale(identifier: job.locale)
    let view = WidgetSnapshotView(
        entry: job.entry, family: job.family, content: job.content, window: job.window,
        scheme: job.scheme, renderingMode: job.mode, locale: Locale(identifier: job.locale)
    )
    let renderer = ImageRenderer(content: view)
    renderer.scale = 2
    guard let image = renderer.cgImage else {
        throw NSError(domain: "HomeDashSnapshots", code: 1, userInfo: [NSLocalizedDescriptionKey: "No image for \(job.name)"])
    }
    let url = directory.appendingPathComponent("\(job.name).png")
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        throw NSError(domain: "HomeDashSnapshots", code: 2, userInfo: [NSLocalizedDescriptionKey: "Cannot write \(url.path)"])
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else {
        throw NSError(domain: "HomeDashSnapshots", code: 3, userInfo: [NSLocalizedDescriptionKey: "Cannot finalize \(url.path)"])
    }
}

@MainActor
func jobs() -> [Job] {
    var result: [Job] = []
    let schemes: [(String, ColorScheme)] = [("light", .light), ("dark", .dark)]
    let families: [HomeDashFamily] = [.small, .medium, .large, .accessoryRectangular, .accessoryCircular]
    let states = SampleData.Scenario.allCases

    // Main set: every family x scheme x state (es_ES).
    for family in families {
        for (schemeName, scheme) in schemes {
            for state in states {
                result.append(Job(
                    name: "\(family.rawValue)-\(state.rawValue)-\(schemeName)",
                    entry: SampleData.entry(state), family: family, scheme: scheme))
            }
        }
    }

    // Content variants (small + medium, light).
    let day = SampleData.entry(.day)
    for family in [HomeDashFamily.small, .medium] {
        result.append(Job(name: "\(family.rawValue)-day-light-solar", entry: day, family: family, content: .solarOnly))
        result.append(Job(name: "\(family.rawValue)-day-light-price", entry: day, family: family, content: .priceOnly))
    }
    for family in [HomeDashFamily.accessoryRectangular, .accessoryCircular] {
        result.append(Job(name: "\(family.rawValue)-day-dark-solar", entry: day, family: family, content: .solarOnly, scheme: .dark))
        result.append(Job(name: "\(family.rawValue)-day-dark-price", entry: day, family: family, content: .priceOnly, scheme: .dark))
    }
    // Price window "around" and single content on large (light).
    result.append(Job(name: "large-day-light-around", entry: day, family: .large, window: .around))
    result.append(Job(name: "large-day-light-solar", entry: day, family: .large, content: .solarOnly))
    result.append(Job(name: "large-day-light-price", entry: day, family: .large, content: .priceOnly))
    // Solar not configured, prices fine.
    result.append(Job(name: "medium-notconfigured-light", entry: SampleData.notConfiguredEntry(), family: .medium))
    result.append(Job(name: "small-notconfigured-light", entry: SampleData.notConfiguredEntry(), family: .small))

    // Rendering modes (dark). The system tint is not simulated: these show the monochrome hierarchy.
    for family in [HomeDashFamily.small, .medium, .accessoryRectangular, .accessoryCircular] {
        result.append(Job(name: "\(family.rawValue)-day-dark-accented", entry: day, family: family, scheme: .dark, mode: .accented))
        result.append(Job(name: "\(family.rawValue)-day-dark-vibrant", entry: day, family: family, scheme: .dark, mode: .vibrant))
    }

    // Languages.
    result.append(Job(name: "medium-day-light-en", entry: day, family: .medium, locale: "en_GB"))
    result.append(Job(name: "large-day-dark-en", entry: day, family: .large, scheme: .dark, locale: "en_GB"))
    result.append(Job(name: "medium-day-light-de", entry: day, family: .medium, locale: "de_DE"))
    result.append(Job(name: "large-day-light-de", entry: day, family: .large, locale: "de_DE"))
    result.append(Job(name: "medium-day-light-fr", entry: SampleData.entry(.day, region: .france), family: .medium, locale: "fr_FR"))
    result.append(Job(name: "large-day-light-fr", entry: SampleData.entry(.day, region: .france), family: .large, locale: "fr_FR"))
    return result
}

@MainActor
func main() {
    let arguments = CommandLine.arguments
    guard arguments.count >= 2 else {
        FileHandle.standardError.write(Data("Usage: HomeDashSnapshots <output-dir>\n".utf8))
        exit(2)
    }
    let directory = URL(fileURLWithPath: arguments[1], isDirectory: true)
    do {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let all = jobs()
        for job in all { try render(job, to: directory) }
        print("Wrote \(all.count) PNGs to \(directory.path)")
    } catch {
        FileHandle.standardError.write(Data("Error: \(error.localizedDescription)\n".utf8))
        exit(1)
    }
}

MainActor.assumeIsolated { main() }
