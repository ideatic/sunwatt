import SwiftUI

enum OnboardingPage: Int, CaseIterable, Hashable {
    case welcome, country, solar, widget, done
}

/// First-launch flow: welcome, country, solar kiosk, add widget, done. Pure content for one page plus the
/// bottom bar (no `TabView`), so snapshots render each page; `OnboardingScreen` adds paging.
struct OnboardingContent: View {
    let model: AppModel
    @Binding var page: OnboardingPage

    @Environment(\.appIdiom) private var idiom
    @Environment(\.appSnapshot) private var snapshot

    var body: some View {
        VStack(spacing: 0) {
            pager
            OnboardingBar(model: model, page: $page)
                .padding(.horizontal, Theme.Spacing.l + 4)
                .padding(.bottom, idiom == .desktop ? Theme.Spacing.xl : Theme.Spacing.m)
                .padding(.top, Theme.Spacing.s)
        }
        .frame(maxWidth: idiom == .desktop ? 600 : 720)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder
    private var pager: some View {
        if snapshot {
            OnboardingPageView(model: model, page: $page, current: page)
                .frame(maxHeight: .infinity, alignment: .top)
        } else {
            livePager
        }
    }

    @ViewBuilder
    private var livePager: some View {
        #if os(iOS)
        TabView(selection: $page) {
            ForEach(OnboardingPage.allCases, id: \.self) { item in
                ScrollView {
                    OnboardingPageView(model: model, page: $page, current: item)
                }
                .scrollBounceBehavior(.basedOnSize)
                .tag(item)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        #else
        ScrollView {
            OnboardingPageView(model: model, page: $page, current: page)
        }
        .scrollBounceBehavior(.basedOnSize)
        #endif
    }
}

private struct OnboardingBar: View {
    let model: AppModel
    @Binding var page: OnboardingPage
    @Environment(\.colorScheme) private var scheme
    @Environment(\.appIdiom) private var idiom

    var body: some View {
        let palette = AppPalette(scheme)
        VStack(spacing: Theme.Spacing.m) {
            HStack(spacing: 7) {
                ForEach(OnboardingPage.allCases, id: \.self) { item in
                    Capsule()
                        .fill(item == page ? AnyShapeStyle(Theme.Solar.gradient) : AnyShapeStyle(palette.hairline))
                        .frame(width: item == page ? 22 : 7, height: 7)
                }
            }
            .animation(.snappy, value: page)
            .accessibilityHidden(true)
            HStack(spacing: Theme.Spacing.m) {
                if page != .welcome, page != .done {
                    Button { go(-1) } label: { atext("Back") }
                        .buttonStyle(QuietButtonStyle())
                        .keyboardShortcut(.cancelAction)
                }
                Button { advance() } label: { primaryLabel }
                    .buttonStyle(PrimaryButtonStyle())
                    .keyboardShortcut(.defaultAction)
            }
            .frame(maxWidth: idiom == .desktop ? 360 : .infinity)
        }
    }

    private var primaryLabel: Text {
        switch page {
        case .welcome: atext("Get started")
        case .done: atext("Open SunWatt")
        case .solar where model.settings.kioskURL.isEmpty: atext("Skip, prices only")
        default: atext("Continue")
        }
    }

    private func advance() {
        if page == .done {
            model.completeOnboarding()
        } else if let next = OnboardingPage(rawValue: page.rawValue + 1) {
            withAnimation(.snappy) { page = next }
        }
    }

    private func go(_ delta: Int) {
        if let target = OnboardingPage(rawValue: page.rawValue + delta) {
            withAnimation(.snappy) { page = target }
        }
    }
}

private struct OnboardingPageView: View {
    let model: AppModel
    @Binding var page: OnboardingPage
    let current: OnboardingPage

    var body: some View {
        Group {
            switch current {
            case .welcome: WelcomePage()
            case .country: CountryPage(model: model)
            case .solar: SolarPage(model: model)
            case .widget: WidgetPage(model: model)
            case .done: DonePage(model: model)
            }
        }
        .padding(.horizontal, Theme.Spacing.l + 4)
        .padding(.top, Theme.Spacing.xl)
        .padding(.bottom, Theme.Spacing.m)
        .frame(maxWidth: .infinity, alignment: .top)
    }
}

// MARK: Pages

private struct PageTitle: View {
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            atext(title)
                .font(.system(size: 30, weight: .bold, design: .rounded))
                .fixedSize(horizontal: false, vertical: true)
            atext(subtitle)
                .font(.system(.body, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct WelcomePage: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(spacing: Theme.Spacing.xl) {
            ZStack {
                Circle()
                    .fill(RadialGradient(colors: [Theme.Solar.amber.opacity(0.35), .clear], center: .center, startRadius: 10, endRadius: 150))
                    .frame(width: 300, height: 300)
                AppIconView(size: 128, style: .badge)
                    .shadow(color: Theme.Solar.orange.opacity(0.45), radius: 24, y: 10)
            }
            .frame(height: 190)
            VStack(spacing: 8) {
                atext("Welcome to SunWatt")
                    .font(.system(size: 32, weight: .bold, design: .rounded))
                    .multilineTextAlignment(.center)
                atext("Your solar production and the price of electricity, always one glance away.")
                    .font(.system(.body, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            VStack(alignment: .leading, spacing: Theme.Spacing.m + 2) {
                feature(Theme.Solar.symbol, AnyShapeStyle(Theme.Solar.gradient), "Solar production", "Live from your Huawei FusionSolar plant.")
                feature(Theme.Price.symbol, AnyShapeStyle(Theme.Price.green), "Electricity prices", "Hour by hour, for Spain and seven more markets.")
                feature("square.grid.2x2.fill", AnyShapeStyle(Theme.Solar.orange), "Widgets", "For the Home Screen, the Lock Screen and the desktop.")
            }
            .appCard()
        }
    }

    private func feature(_ symbol: String, _ tint: AnyShapeStyle, _ title: LocalizedStringKey, _ detail: LocalizedStringKey) -> some View {
        HStack(spacing: Theme.Spacing.m) {
            SymbolTile(symbol: symbol, size: 42, tint: tint)
            VStack(alignment: .leading, spacing: 1) {
                atext(title).font(.system(.body, design: .rounded).weight(.semibold))
                atext(detail)
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct CountryPage: View {
    let model: AppModel

    var body: some View {
        let region = model.settings.priceRegion
        VStack(alignment: .leading, spacing: Theme.Spacing.l) {
            PageTitle(title: "Where do you buy electricity?", subtitle: "Pick your country to see its price in the app and the widget.")
            RegionList(selection: model.binding(\.priceRegion), showsNotes: true)
                .appCard(padding: Theme.Spacing.s)
            VStack(alignment: .leading, spacing: 2) {
                sourceAttribution(region)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, Theme.Spacing.xs)
        }
    }
}

private struct SolarPage: View {
    let model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.l) {
            PageTitle(title: "Connect your solar plant", subtitle: "Paste the kiosk link of your FusionSolar plant. You can also do this later.")
            VStack(alignment: .leading, spacing: Theme.Spacing.m) {
                KioskLinkEditor(model: model)
                Button { model.present(.kioskHelp) } label: {
                    Label { atext("How do I get the kiosk link?") } icon: { Image(systemName: "questionmark.circle") }
                }
                .buttonStyle(SoftButtonStyle())
            }
            .appCard()
        }
    }
}

private struct WidgetPage: View {
    let model: AppModel
    @Environment(\.appIdiom) private var idiom
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.l) {
            PageTitle(title: "Add the widget", subtitle: "The widget is the best part of SunWatt. Your system adds it, in a few taps.")
            ZStack {
                LinearGradient(
                    colors: [Color(red: 0.98, green: 0.62, blue: 0.40), Color(red: 0.55, green: 0.30, blue: 0.62), Color(red: 0.20, green: 0.22, blue: 0.48)],
                    startPoint: .topLeading, endPoint: .bottomTrailing)
                HStack(spacing: Theme.Spacing.m) {
                    MiniWidgetPreview(family: .small, width: 118, region: model.settings.priceRegion)
                    MiniWidgetPreview(family: .medium, width: 190, region: model.settings.priceRegion)
                }
            }
            .frame(height: 176)
            .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card + 2, style: .continuous))
            .accessibilityHidden(true)
            Button { model.present(.widgetGuide) } label: {
                Label { atext("Show me how") } icon: { Image(systemName: "plus.square.on.square") }
            }
            .buttonStyle(SoftButtonStyle())
            atext(idiom == .phone ? "Touch and hold the Home Screen, tap Edit, then Add Widget and search for SunWatt." : "Control-click the desktop, choose Edit Widgets… and search for SunWatt.")
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, Theme.Spacing.xs)
        }
    }
}

private struct DonePage: View {
    let model: AppModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = AppPalette(scheme)
        let region = model.settings.priceRegion
        let hasSolar = KioskInputState(model.settings.kioskURL) != .empty && model.isSolarConfigured
        VStack(spacing: Theme.Spacing.xl) {
            ZStack {
                Circle().fill(RadialGradient(colors: [palette.success.opacity(0.28), .clear], center: .center, startRadius: 5, endRadius: 110))
                    .frame(width: 220, height: 220)
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 84))
                    .foregroundStyle(Theme.Price.green, Theme.Price.green.opacity(0.2))
                    .symbolRenderingMode(.palette)
            }
            .frame(height: 150)
            VStack(spacing: 8) {
                atext("You are all set")
                    .font(.system(size: 32, weight: .bold, design: .rounded))
                atext("SunWatt will keep your widgets up to date.")
                    .font(.system(.body, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            VStack(alignment: .leading, spacing: Theme.Spacing.m) {
                summaryRow(symbol: Theme.Price.symbol, tint: AnyShapeStyle(Theme.Price.green)) {
                    Text("\(region.flag) \(regionName(region))")
                } detail: { tariffName(region) }
                summaryRow(symbol: Theme.Solar.symbol, tint: AnyShapeStyle(Theme.Solar.gradient)) {
                    atext(hasSolar ? "Solar plant connected" : "Prices only")
                } detail: {
                    atext(hasSolar ? "FusionSolar kiosk" : "You can add the solar plant later in Settings.")
                }
            }
            .appCard()
        }
    }

    private func summaryRow(symbol: String, tint: AnyShapeStyle, @ViewBuilder title: () -> some View, @ViewBuilder detail: () -> some View) -> some View {
        HStack(spacing: Theme.Spacing.m) {
            SymbolTile(symbol: symbol, size: 42, tint: tint)
            VStack(alignment: .leading, spacing: 1) {
                title().font(.system(.body, design: .rounded).weight(.semibold))
                detail()
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
    }
}

/// Onboarding with paging (swipe on iPhone, Back/Continue buttons everywhere).
struct OnboardingScreen: View {
    let model: AppModel
    @State private var page: OnboardingPage

    init(model: AppModel, startPage: OnboardingPage = .welcome) {
        self.model = model
        _page = State(initialValue: startPage)
    }

    var body: some View {
        OnboardingContent(model: model, page: $page)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(AppBackground())
    }
}
