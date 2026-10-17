import SwiftUI
import AppKit
import ImageIO
import UniformTypeIdentifiers
import WidgetKit
import SunWattKit

// App Store screenshots: a caption over widgets or an app screen, at Apple's pixel sizes, one folder per language in
// the fastlane deliver layout (<dir>/<locale>/<n>_<device>_<shot>.png). Plan and captions: AppStore/SCREENSHOTS.md.
// Demo data only. PNGs are written without alpha (App Store Connect rejects it).

/// One language of the store listing: fastlane folder, renderer locale and the market its screens show.
struct StoreLanguage {
    let folder: String
    let locale: String
    let region: PriceRegion

    static let all: [StoreLanguage] = [
        StoreLanguage(folder: "en-US", locale: "en_US", region: .spain),
        StoreLanguage(folder: "es-ES", locale: "es_ES", region: .spain),
        StoreLanguage(folder: "pt-PT", locale: "pt_PT", region: .portugal),
        StoreLanguage(folder: "fr-FR", locale: "fr_FR", region: .france),
        StoreLanguage(folder: "de-DE", locale: "de_DE", region: .germany),
        StoreLanguage(folder: "it", locale: "it_IT", region: .italyNorth),
        StoreLanguage(folder: "nl-NL", locale: "nl_NL", region: .netherlands),
    ]
}

/// Store devices: canvas in points and the scale that gives Apple's pixel size.
enum StoreDevice: String, CaseIterable {
    /// iPhone 6.9": 1320 x 2868 px.
    case iphone = "iphone69"
    /// iPad 13": 2064 x 2752 px.
    case ipad = "ipad13"
    /// Mac: 2880 x 1800 px.
    case mac

    var canvas: CGSize {
        switch self {
        case .iphone: CGSize(width: 440, height: 956)
        case .ipad: CGSize(width: 1032, height: 1376)
        case .mac: CGSize(width: 1440, height: 900)
        }
    }

    var scale: CGFloat { self == .iphone ? 3 : 2 }

    var appDevice: AppSnapshotView.Device {
        switch self {
        case .iphone: .phone
        case .ipad: .pad
        case .mac: .mac
        }
    }

    /// Each slide shows a different surface. The Mac has no Lock Screen; the watch pairs with the iPhone only; on the
    /// iPad the setup screen is a small card lost in the big screen.
    var shots: [StoreShot] {
        switch self {
        case .iphone: StoreShot.allCases
        case .ipad: [.solar, .price, .lock, .app]
        case .mac: [.solar, .price, .app, .setup]
        }
    }
}

/// The screens in store order (AppStore/SCREENSHOTS.md). Solar first: it is the main feature.
enum StoreShot: String, CaseIterable {
    case solar, price, lock, watch, app, setup


    func caption(_ folder: String) -> String {
        Self.captions[rawValue]?[folder] ?? Self.captions[rawValue]?["en-US"] ?? ""
    }

    /// Max 40 characters each (AppStore/SCREENSHOTS.md).
    static let captions: [String: [String: String]] = [
        "price": ["en-US": "Electricity price at a glance", "es-ES": "El precio de la luz, de un vistazo",
                  "pt-PT": "O preço da luz num relance", "fr-FR": "Le prix de l'élec d'un coup d'œil",
                  "de-DE": "Strompreis auf einen Blick", "it": "Il prezzo della luce a colpo d'occhio",
                  "nl-NL": "Stroomprijs in één oogopslag"],
        "solar": ["en-US": "Your solar output, live", "es-ES": "Tu producción solar, en directo",
                  "pt-PT": "A tua produção solar, ao vivo", "fr-FR": "Votre production solaire en direct",
                  "de-DE": "Ihre Solarleistung live", "it": "La tua produzione solare, live",
                  "nl-NL": "Je zonne-opbrengst, live"],
        "watch": ["en-US": "Also on your Apple Watch", "es-ES": "También en tu Apple Watch", "pt-PT": "Também no teu Apple Watch", "fr-FR": "Aussi sur votre Apple Watch",
                  "de-DE": "Auch auf Ihrer Apple Watch", "it": "Anche sul tuo Apple Watch", "nl-NL": "Ook op je Apple Watch"],
        "lock": ["en-US": "On Lock Screen and Control Center", "es-ES": "En bloqueo y Centro de control",
                 "pt-PT": "No ecrã bloqueado e Centro de Controlo", "fr-FR": "Écran verrouillé et Centre de contrôle",
                 "de-DE": "Sperrbildschirm & Kontrollzentrum", "it": "Blocco e Centro di Controllo",
                 "nl-NL": "Toegangsscherm en Bedieningscentrum"],
        "app": ["en-US": "Charts you can explore", "es-ES": "Gráficas que puedes explorar",
                "pt-PT": "Gráficos que podes explorar", "fr-FR": "Des graphiques interactifs",
                "de-DE": "Interaktive Diagramme", "it": "Grafici interattivi", "nl-NL": "Interactieve grafieken"],
        "setup": ["en-US": "For Huawei, SolarEdge and PVOutput", "es-ES": "Para Huawei, SolarEdge y PVOutput", "pt-PT": "Para Huawei, SolarEdge e PVOutput", "fr-FR": "Pour Huawei, SolarEdge et PVOutput",
                  "de-DE": "Für Huawei, SolarEdge und PVOutput", "it": "Per Huawei, SolarEdge e PVOutput", "nl-NL": "Voor Huawei, SolarEdge en PVOutput"],
    ]
}

/// One store screenshot: background, caption, then the content scaled to fit the space below.
struct StoreScreenshotView: View {
    let shot: StoreShot
    let device: StoreDevice
    let language: StoreLanguage

    var body: some View {
        let canvas = device.canvas
        let captionSize: CGFloat = device == .iphone ? 40 : 56
        VStack(spacing: 0) {
            Text(verbatim: shot.caption(language.folder))
                .font(.system(size: captionSize, weight: .bold, design: .rounded))
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
                .foregroundStyle(Color(red: 0.16, green: 0.10, blue: 0.05))
                .padding(.horizontal, canvas.width * 0.08)
                .padding(.top, canvas.height * (device == .mac ? 0.06 : 0.065))
                .frame(maxWidth: .infinity)
            GeometryReader { proxy in
                let natural = contentSize
                let scale = min(proxy.size.width / natural.width, proxy.size.height / natural.height)
                // Devices hang from the top; floating widgets sit in the middle of the space.
                let centered = shot == .price || shot == .solar || shot == .watch
                content
                    .frame(width: natural.width, height: natural.height)
                    .scaleEffect(scale, anchor: centered ? .center : .top)
                    .frame(width: proxy.size.width, height: proxy.size.height, alignment: centered ? .center : .top)
            }
            .padding(.top, canvas.height * 0.035)
            .padding(.horizontal, canvas.width * 0.07)
            .padding(.bottom, canvas.height * (device == .mac ? 0.06 : 0.05))
        }
        .frame(width: canvas.width, height: canvas.height)
        .background(background)
        .environment(\.locale, Locale(identifier: language.locale))
    }

    /// AI-made backdrop from AppStore/art (no text, no UI), the gradient below when the file is missing. Every slide
    /// uses the day light so the set reads as one; the Lock Screen keeps its night look inside the phone.
    @ViewBuilder
    private var background: some View {
        let name = "bg-day-\(device == .mac ? "landscape" : "portrait")"
        if let image = NSImage(contentsOf: Self.artDirectory.appendingPathComponent("\(name).png")) {
            Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
                .frame(width: device.canvas.width, height: device.canvas.height).clipped()
        } else {
            gradient
        }
    }

    static let artDirectory = URL(filePath: #filePath).deletingLastPathComponent()
        .appending(path: "../../../../AppStore/art").standardized

    private var gradient: some View {
        let colors: [Color] = [Color(red: 1.00, green: 0.93, blue: 0.78), Color(red: 1.00, green: 0.80, blue: 0.62)]
        return LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom)
    }

    private var entry: SunWattEntry { SampleData.entry(.day, region: language.region) }

    // MARK: Content

    private var contentSize: CGSize {
        switch shot {
        // The Mac canvas is wide and short: widgets side by side; iPhone and iPad stack them.
        case .price: device == .mac ? CGSize(width: 364 + 24 + 170, height: 382) : CGSize(width: 364, height: 382 + 16 + 170)
        case .solar: device == .mac ? CGSize(width: 364 + 24 + 170, height: 170 * 2 + 16) : CGSize(width: 364, height: 170 * 3 + 16 * 2)
        case .lock: framedSize(CGSize(width: 393, height: 852))
        case .watch: CGSize(width: Self.watchSize.width + Self.watchShift.width, height: Self.watchSize.height + Self.watchShift.height)
        case .app, .setup:
            device == .mac ? AppSnapshotView.size(for: .mac) : framedSize(AppSnapshotView.size(for: device.appDevice))
        }
    }

    @ViewBuilder
    private var content: some View {
        switch shot {
        case .price:
            if device == .mac {
                HStack(alignment: .top, spacing: 24) {
                    card(.large, .both)
                    VStack(spacing: 42) {
                        card(.small, .priceOnly)
                        card(.small, .both)
                    }
                }
            } else {
                VStack(spacing: 16) {
                    card(.large, .both)
                    HStack(spacing: 24) {
                        card(.small, .priceOnly)
                        card(.small, .both)
                    }
                }
            }
        case .solar:
            if device == .mac {
                HStack(alignment: .top, spacing: 24) {
                    VStack(spacing: 16) {
                        card(.medium, .solarOnly)
                        card(.medium, .both)
                    }
                    VStack(spacing: 16) {
                        card(.small, .solarOnly)
                        card(.small, .both)
                    }
                }
            } else {
                VStack(spacing: 16) {
                    card(.medium, .solarOnly)
                    HStack(spacing: 24) {
                        card(.small, .solarOnly)
                        card(.small, .both)
                    }
                    card(.medium, .both)
                }
            }
        case .watch:
            watches
        case .app:
            appScreen(.dashboardSelection)
        case .setup:
            appScreen(.onboardingSolar)
        case .lock:
            lockScreen
        }
    }

    private func card(_ family: SunWattFamily, _ content: WidgetContent) -> some View {
        SunWattWidgetView(entry: entry, family: family, content: content)
            .padding(family.contentInset)
            .frame(width: family.size.width, height: family.size.height)
            .background(WidgetBackground())
            .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.widget, style: .continuous))
            .shadow(color: Color(red: 0.45, green: 0.22, blue: 0.05).opacity(0.18), radius: 18, y: 10)
            .environment(\.colorScheme, .light)
            .environment(\.widgetRenderingMode, .fullColor)
    }

    // MARK: App screens

    private static let bezel: CGFloat = 12

    private func framedSize(_ size: CGSize) -> CGSize {
        CGSize(width: size.width + Self.bezel * 2, height: size.height + Self.bezel * 2)
    }

    @ViewBuilder
    private func appScreen(_ kind: AppSnapshotView.Kind) -> some View {
        let screen = AppSnapshotView(
            kind: kind, device: device.appDevice, scheme: .light, locale: Locale(identifier: language.locale),
            priceRegion: language.region)
        if device == .mac {
            // The Mac renderer already draws a window.
            screen.shadow(color: .black.opacity(0.25), radius: 24, y: 10)
        } else {
            let radius: CGFloat = device == .iphone ? 52 : 34
            screen
                .overlay(alignment: .top) { if device == .iphone { dynamicIsland } }
                .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
                .modifier(DeviceFrame(radius: radius, bezel: Self.bezel))
        }
    }

    @ViewBuilder
    private var lockWallpaper: some View {
        if let image = NSImage(contentsOf: Self.artDirectory.appendingPathComponent("bg-night-portrait.png")) {
            Image(nsImage: image).resizable().aspectRatio(contentMode: .fill).frame(width: 393, height: 852).clipped()
        } else {
            LinearGradient(
                colors: [Color(red: 0.12, green: 0.16, blue: 0.36), Color(red: 0.55, green: 0.30, blue: 0.40)],
                startPoint: .top, endPoint: .bottom)
        }
    }

    private var dynamicIsland: some View {
        Capsule().fill(Color.black).frame(width: 122, height: 35).padding(.top, 11)
    }

    // MARK: Apple Watch

    private static let watchBezel: CGFloat = 14
    private static var watchSize: CGSize {
        let screen = WatchSnapshotView.screenSize
        return CGSize(width: screen.width + watchBezel * 2 + 8, height: screen.height + watchBezel * 2)
    }
    /// The solar watch sits behind, to the right and lower, so both screens read.
    private static let watchShift = CGSize(width: 120, height: 230)

    private var watches: some View {
        ZStack(alignment: .topLeading) {
            watch(WatchSolarPage(entry: entry))
                .offset(x: Self.watchShift.width, y: Self.watchShift.height)
            watch(WatchPricePage(entry: entry))
        }
        // `offset` does not move the layout box: claim the space of both watches, or the back one spills out.
        .frame(width: Self.watchSize.width + Self.watchShift.width, height: Self.watchSize.height + Self.watchShift.height,
               alignment: .topLeading)
    }

    /// Apple Watch stand-in: the real watch page on a black screen, a dark case and the Digital Crown.
    private func watch(_ page: some View) -> some View {
        let screen = WatchSnapshotView.screenSize
        return page
            .padding(.horizontal, 12)
            .padding(.top, 34)
            .padding(.bottom, 10)
            .frame(width: screen.width, height: screen.height)
            .background(Color.black)
            .overlay(alignment: .topTrailing) {
                Text(SampleData.now.formatted(
                    Date.FormatStyle(locale: Locale(identifier: language.locale), timeZone: TimeZone(identifier: "Europe/Madrid")!)
                        .hour(.defaultDigits(amPM: .omitted)).minute(.twoDigits)))
                    .font(.system(size: 14, weight: .semibold, design: .rounded))
                    .padding(.top, 10).padding(.trailing, 22)
            }
            .clipShape(RoundedRectangle(cornerRadius: 40, style: .continuous))
            .environment(\.colorScheme, .dark)
            .foregroundStyle(.white)
            .modifier(DeviceFrame(radius: 40, bezel: Self.watchBezel))
            .overlay(alignment: .trailing) {
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .fill(LinearGradient(colors: [Color(white: 0.55), Color(white: 0.25)], startPoint: .top, endPoint: .bottom))
                    .frame(width: 8, height: 44)
                    .offset(x: 6, y: -50)
            }
            .padding(.trailing, 8)
    }

    // MARK: Lock Screen

    private var lockScreen: some View {
        let locale = Locale(identifier: language.locale)
        let date = SampleData.now.formatted(.dateTime.weekday(.wide).day().month(.wide).locale(locale))
        return VStack(spacing: 0) {
            Text(verbatim: date)
                .font(.system(size: 22, weight: .semibold, design: .rounded))
                .padding(.top, 70)
            // Lock Screen clock: no AM/PM, 12- or 24-hour as the locale prefers ("1:40" / "13:40").
            Text(verbatim: SampleData.now.formatted(
                Date.FormatStyle(locale: locale, timeZone: TimeZone(identifier: "Europe/Madrid")!)
                    .hour(.defaultDigits(amPM: .omitted)).minute(.twoDigits)))
                .font(.system(size: 96, weight: .bold, design: .rounded))
                .monospacedDigit()
            HStack(spacing: 14) {
                accessory(.accessoryCircular, .priceOnly)
                accessory(.accessoryRectangular, .both)
                accessory(.accessoryCircular, .solarOnly)
            }
            .padding(.top, 10)
            Spacer()
            controlCenter
                .padding(.horizontal, 22)
                .padding(.bottom, 40)
        }
        .foregroundStyle(.white)
        .frame(width: 393, height: 852)
        .background(lockWallpaper)
        .overlay(alignment: .top) { dynamicIsland }
        .clipShape(RoundedRectangle(cornerRadius: 52, style: .continuous))
        .modifier(DeviceFrame(radius: 52, bezel: Self.bezel))
        .environment(\.colorScheme, .dark)
    }

    /// Control Center stand-in: the two SunWatt controls (price, solar) as 2x1 tiles on a frosted panel.
    private var controlCenter: some View {
        let now = entry.date
        let price = entry.data.prices?.points.first { $0.start <= now && now < $0.start.addingTimeInterval($0.duration) }
        return HStack(spacing: 12) {
            controlTile(symbol: Theme.Price.symbol, tint: Theme.Price.green,
                        value: Format.eurPerKWh(price?.eurPerKWh), unit: "€/kWh")
            controlTile(symbol: Theme.Solar.symbol, tint: Theme.Solar.amber,
                        value: Format.kW(entry.data.solar?.currentPowerKW), unit: "kW")
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 34, style: .continuous).fill(Color.white.opacity(0.10)))
    }

    private func controlTile(symbol: String, tint: Color, value: String, unit: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
                .background(Circle().fill(Color.white.opacity(0.9)))
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: "SunWatt").font(.system(size: 12, weight: .medium, design: .rounded)).opacity(0.7)
                Text(verbatim: "\(value) \(unit)").font(.system(size: 15, weight: .bold, design: .rounded)).monospacedDigit()
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .frame(maxWidth: .infinity, minHeight: 66)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Color.white.opacity(0.18)))
    }

    private func accessory(_ family: SunWattFamily, _ content: WidgetContent) -> some View {
        let shape = family == .accessoryCircular
            ? AnyShape(Circle())
            : AnyShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        return SunWattWidgetView(entry: entry, family: family, content: content)
            .padding(family.contentInset)
            .frame(width: family.size.width, height: family.size.height)
            .foregroundStyle(.white)
            .background(shape.fill(Color.white.opacity(0.18)))
            .clipShape(shape)
            .environment(\.widgetRenderingMode, .vibrant)
    }
}

/// Renders the store set for `languages` (all when empty) into `directory/<folder>/`.
@MainActor
func renderStore(to directory: URL, languages filter: [String]) throws -> Int {
    let languages = filter.isEmpty ? StoreLanguage.all : StoreLanguage.all.filter { filter.contains($0.folder) }
    guard !languages.isEmpty else {
        throw NSError(domain: "SunWattSnapshots", code: 4, userInfo: [
            NSLocalizedDescriptionKey: "Unknown language. Use: \(StoreLanguage.all.map(\.folder).joined(separator: " "))"])
    }
    var count = 0
    for language in languages {
        Format.locale = Locale(identifier: language.locale)
        let folder = directory.appendingPathComponent(language.folder, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for device in StoreDevice.allCases {
            for (index, shot) in device.shots.enumerated() {
                let view = StoreScreenshotView(shot: shot, device: device, language: language)
                try writeOpaque(view, name: "\(index + 1)_\(device.rawValue)_\(shot.rawValue)", scale: device.scale, to: folder)
                count += 1
            }
        }
    }
    return count
}

/// Like `write`, but flattens the image onto an opaque RGB bitmap: App Store Connect rejects PNGs with alpha.
@MainActor
func writeOpaque(_ view: some View, name: String, scale: CGFloat, to directory: URL) throws {
    let renderer = ImageRenderer(content: view)
    renderer.scale = scale
    guard let image = renderer.cgImage,
          let context = CGContext(
            data: nil, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
    else {
        throw NSError(domain: "SunWattSnapshots", code: 1, userInfo: [NSLocalizedDescriptionKey: "No image for \(name)"])
    }
    context.setFillColor(CGColor(gray: 1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: image.width, height: image.height))
    context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    guard let flat = context.makeImage() else {
        throw NSError(domain: "SunWattSnapshots", code: 1, userInfo: [NSLocalizedDescriptionKey: "No image for \(name)"])
    }
    let url = directory.appendingPathComponent("\(name).png")
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        throw NSError(domain: "SunWattSnapshots", code: 2, userInfo: [NSLocalizedDescriptionKey: "Cannot write \(url.path)"])
    }
    CGImageDestinationAddImage(destination, flat, nil)
    guard CGImageDestinationFinalize(destination) else {
        throw NSError(domain: "SunWattSnapshots", code: 3, userInfo: [NSLocalizedDescriptionKey: "Cannot finalize \(url.path)"])
    }
}

/// Black bezel with a thin titanium edge and a soft, warm drop shadow.
struct DeviceFrame: ViewModifier {
    let radius: CGFloat
    let bezel: CGFloat

    func body(content: Content) -> some View {
        content
            .padding(bezel)
            .background(RoundedRectangle(cornerRadius: radius + bezel, style: .continuous).fill(Color.black))
            .overlay(
                RoundedRectangle(cornerRadius: radius + bezel, style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            colors: [Color(white: 0.78), Color(white: 0.38), Color(white: 0.70)],
                            startPoint: .topLeading, endPoint: .bottomTrailing),
                        lineWidth: 2.5))
            .shadow(color: Color(red: 0.35, green: 0.16, blue: 0.04).opacity(0.28), radius: 30, y: 18)
    }
}
