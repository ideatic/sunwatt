import SwiftUI
import AppKit
import ImageIO
import UniformTypeIdentifiers
import WidgetKit
import SunWattKit

// Renders every widget family x scheme x state to PNG files.
// Usage: swift run SunWattSnapshots <output-dir>
//        swift run SunWattSnapshots --app <output-dir>    app screens (iPhone and Mac window, light and dark)
//        swift run SunWattSnapshots --icon <output-dir>   app icon PNGs (1024 px)

@MainActor
struct Job {
    var name: String
    var entry: SunWattEntry
    var family: SunWattFamily
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
    try write(view, name: job.name, scale: 2, to: directory)
}

@MainActor
func write(_ view: some View, name: String, scale: CGFloat, to directory: URL) throws {
    let renderer = ImageRenderer(content: view)
    renderer.scale = scale
    guard let image = renderer.cgImage else {
        throw NSError(domain: "SunWattSnapshots", code: 1, userInfo: [NSLocalizedDescriptionKey: "No image for \(name)"])
    }
    let url = directory.appendingPathComponent("\(name).png")
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        throw NSError(domain: "SunWattSnapshots", code: 2, userInfo: [NSLocalizedDescriptionKey: "Cannot write \(url.path)"])
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else {
        throw NSError(domain: "SunWattSnapshots", code: 3, userInfo: [NSLocalizedDescriptionKey: "Cannot finalize \(url.path)"])
    }
}

@MainActor
func jobs() -> [Job] {
    var result: [Job] = []
    let schemes: [(String, ColorScheme)] = [("light", .light), ("dark", .dark)]
    let families: [SunWattFamily] = [.small, .medium, .large, .accessoryRectangular, .accessoryCircular]
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
    for family in [SunWattFamily.small, .medium] {
        result.append(Job(name: "\(family.rawValue)-day-light-solar", entry: day, family: family, content: .solarOnly))
        result.append(Job(name: "\(family.rawValue)-day-light-price", entry: day, family: family, content: .priceOnly))
    }
    for family in [SunWattFamily.accessoryRectangular, .accessoryCircular] {
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
    for family in [SunWattFamily.small, .medium, .accessoryRectangular, .accessoryCircular] {
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

// MARK: App screens and icon

@MainActor
func renderApp(to directory: URL) throws -> Int {
    typealias Kind = AppSnapshotView.Kind
    var count = 0
    func emit(_ kind: Kind, _ device: AppSnapshotView.Device, _ scheme: ColorScheme, locale: String = "es_ES", full: Bool = false) throws {
        Format.locale = Locale(identifier: locale)
        let view = AppSnapshotView(kind: kind, device: device, scheme: scheme, locale: Locale(identifier: locale), fullHeight: full)
        let lang = locale.hasPrefix("es") ? "" : "-\(locale.prefix(2))"
        let name = "\(device.rawValue)-\(kind.rawValue)-\(scheme == .dark ? "dark" : "light")\(lang)\(full ? "-full" : "")"
        try write(view, name: name, scale: 2, to: directory)
        count += 1
    }
    for kind in Kind.allCases {
        for device in [AppSnapshotView.Device.phone, .mac] {
            for scheme in [ColorScheme.light, .dark] { try emit(kind, device, scheme) }
        }
    }
    // iPad 13-inch: dashboard, settings, onboarding and widget guide, portrait and landscape.
    for device in [AppSnapshotView.Device.pad, .padLandscape] {
        for kind in [Kind.dashboardDay, .settings, .onboardingWelcome, .onboardingCountry, .onboardingSolar, .widgetGuide] {
            for scheme in [ColorScheme.light, .dark] { try emit(kind, device, scheme) }
        }
    }
    // Natural height of the long phone screens (the 852 pt frame clips them).
    for kind in [Kind.dashboardDay, .dashboardNight, .dashboardTomorrow, .settings, .widgetGuide, .kioskHelp] {
        for scheme in [ColorScheme.light, .dark] { try emit(kind, .phone, scheme, full: true) }
    }
    // Languages: English, long German strings, French (France prices).
    for (locale, kinds) in [
        ("en_GB", [Kind.dashboardDay, .settings, .onboardingWelcome, .onboardingCountry, .onboardingSolar, .widgetGuide, .kioskHelp]),
        ("de_DE", [Kind.dashboardDay, .settings, .onboardingCountry, .onboardingSolar, .widgetGuide, .kioskHelp]),
        ("fr_FR", [Kind.dashboardDay]),
    ] {
        for kind in kinds {
            try emit(kind, .phone, .light, locale: locale, full: kind == .dashboardDay || kind == .settings)
            try emit(kind, .mac, .light, locale: locale)
        }
    }
    return count
}

@MainActor
func renderIcon(to directory: URL) throws {
    try write(AppIconView(size: 1024, style: .square), name: "icon-ios-1024", scale: 1, to: directory)
    try write(AppIconView(size: 1024, style: .macOS), name: "icon-mac-1024", scale: 1, to: directory)
}

@MainActor
func main() {
    let arguments = CommandLine.arguments
    guard arguments.count >= 2 else {
        FileHandle.standardError.write(Data("Usage: SunWattSnapshots [--app|--icon] <output-dir>\n".utf8))
        exit(2)
    }
    let mode = arguments[1].hasPrefix("--") ? arguments[1] : nil
    let pathIndex = mode == nil ? 1 : 2
    guard arguments.count > pathIndex else {
        FileHandle.standardError.write(Data("Usage: SunWattSnapshots [--app|--icon] <output-dir>\n".utf8))
        exit(2)
    }
    let directory = URL(fileURLWithPath: arguments[pathIndex], isDirectory: true)
    do {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        switch mode {
        case "--app":
            let count = try renderApp(to: directory)
            print("Wrote \(count) app PNGs to \(directory.path)")
            return
        case "--icon":
            try renderIcon(to: directory)
            print("Wrote icon PNGs to \(directory.path)")
            return
        default:
            break
        }
        let all = jobs()
        for job in all { try render(job, to: directory) }
        print("Wrote \(all.count) PNGs to \(directory.path)")
    } catch {
        FileHandle.standardError.write(Data("Error: \(error.localizedDescription)\n".utf8))
        exit(1)
    }
}

MainActor.assumeIsolated { main() }
