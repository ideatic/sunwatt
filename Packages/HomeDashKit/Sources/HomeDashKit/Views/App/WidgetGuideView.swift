import SwiftUI

/// A real widget view scaled down: the same `HomeDashWidgetView` the widget extension shows, with sample data.
struct MiniWidgetPreview: View {
    let family: HomeDashFamily
    var width: CGFloat
    var region: PriceRegion = .spain
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let size = family.size
        let scale = width / size.width
        let inner = HomeDashWidgetView(entry: SampleData.entry(.day, region: region), family: family)
            .padding(family.contentInset)
            .frame(width: size.width, height: size.height)
        Group {
            if family.isAccessory {
                let shape = family == .accessoryCircular
                    ? AnyShape(Circle())
                    : AnyShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                inner
                    .foregroundStyle(.white)
                    .background(shape.fill(Color.white.opacity(0.18)))
                    .clipShape(shape)
                    .environment(\.colorScheme, .dark)
            } else {
                inner
                    .background(WidgetBackground())
                    .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.widget, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: Theme.Radius.widget, style: .continuous)
                            .strokeBorder(Color.primary.opacity(scheme == .dark ? 0.12 : 0.06), lineWidth: 1))
                    .shadow(color: .black.opacity(0.18), radius: 8 / max(scale, 0.3), y: 4 / max(scale, 0.3))
            }
        }
        .scaleEffect(scale, anchor: .topLeading)
        .frame(width: width, height: size.height * scale, alignment: .topLeading)
        .accessibilityHidden(true)
    }
}

private enum GuideTab: Hashable { case homeScreen, lockScreen }

private struct GuideStep: Identifiable {
    let id: Int
    let symbol: String
    let title: LocalizedStringKey
    let detail: LocalizedStringKey
}

/// Step-by-step guide to adding the widget. iOS and macOS have no API to add a widget from an app, so this
/// shows the system steps with the real widget as a preview. Pure content; `WidgetGuideScreen` wraps it.
struct WidgetGuideContent: View {
    let model: AppModel
    var initialTab: Int = 0

    @State private var tab: GuideTab
    @Environment(\.appIdiom) private var idiom
    @Environment(\.colorScheme) private var scheme

    init(model: AppModel, initialTab: Int = 0) {
        self.model = model
        self.initialTab = initialTab
        _tab = State(initialValue: initialTab == 1 ? .lockScreen : .homeScreen)
    }

    var body: some View {
        let palette = AppPalette(scheme)
        VStack(alignment: .leading, spacing: Theme.Spacing.l) {
            VStack(alignment: .leading, spacing: 4) {
                atext(idiom == .phone
                      ? "The system adds widgets, not apps. It takes about 30 seconds."
                      : "macOS adds widgets from the desktop. It takes about 30 seconds.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let count = model.widgetCount, count > 0 {
                HStack(spacing: Theme.Spacing.s) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.success)
                    atext("Widget added").font(.system(.subheadline, design: .rounded).weight(.bold))
                    Spacer(minLength: 0)
                    atext("Active widgets: \(count)")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .appCard(padding: Theme.Spacing.m + 2)
            }

            WidgetStage(tab: idiom == .phone ? tab : nil)

            if idiom == .phone {
                AppSegmented(
                    options: [(GuideTab.homeScreen, "Home Screen"), (GuideTab.lockScreen, "Lock Screen")],
                    selection: $tab)
            }

            VStack(alignment: .leading, spacing: 0) {
                let steps = self.steps
                ForEach(steps) { step in
                    GuideStepRow(step: step)
                    if step.id != steps.last?.id {
                        Rectangle().fill(palette.hairline).frame(height: 0.5).padding(.leading, 40 + Theme.Spacing.m + 44)
                    }
                }
            }
            .appCard(padding: Theme.Spacing.m)

            if idiom == .desktop {
                HStack(alignment: .top, spacing: Theme.Spacing.m) {
                    SymbolTile(symbol: "calendar", size: 36)
                    atext("You can also click the date in the menu bar and choose Edit Widgets.")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, Theme.Spacing.xs)
            }
            atext("Widgets refresh about every 15 minutes. The system decides the exact moment.")
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, Theme.Spacing.xs)
        }
    }

    private var steps: [GuideStep] {
        if idiom == .desktop {
            return [
                GuideStep(id: 1, symbol: "cursorarrow.click.2", title: "Control-click the desktop", detail: "Choose Edit Widgets… from the menu."),
                GuideStep(id: 2, symbol: "magnifyingglass", title: "Search for SunWatt", detail: "Type the name in the search field at the top."),
                GuideStep(id: 3, symbol: "hand.draw.fill", title: "Drag the widget out", detail: "Drop it on the desktop or in Notification Center."),
            ]
        }
        switch tab {
        case .homeScreen:
            return [
                GuideStep(id: 1, symbol: "hand.tap.fill", title: "Touch and hold the Home Screen", detail: "Press an empty spot until the apps start to jiggle."),
                GuideStep(id: 2, symbol: "pencil.circle.fill", title: "Tap Edit", detail: "It is in the top-left corner. Then tap Add Widget."),
                GuideStep(id: 3, symbol: "magnifyingglass", title: "Search for SunWatt", detail: "Type the name in the search field."),
                GuideStep(id: 4, symbol: "square.grid.2x2.fill", title: "Pick a size", detail: "Swipe to choose small, medium or large."),
                GuideStep(id: 5, symbol: "plus.circle.fill", title: "Tap Add Widget", detail: "Place it where you like and tap Done."),
            ]
        case .lockScreen:
            return [
                GuideStep(id: 1, symbol: "hand.tap.fill", title: "Touch and hold the Lock Screen", detail: "Press the wallpaper until the Customize button appears."),
                GuideStep(id: 2, symbol: "lock.rectangle.fill", title: "Tap Customize", detail: "Then choose Lock Screen."),
                GuideStep(id: 3, symbol: "rectangle.dashed", title: "Tap the widget area", detail: "It is the row below the time."),
                GuideStep(id: 4, symbol: "magnifyingglass", title: "Choose SunWatt", detail: "Tap the widget to add it, then tap Done."),
            ]
        }
    }
}

/// The widget on a wallpaper-like backdrop, so the guide shows what the result looks like.
private struct WidgetStage: View {
    let tab: GuideTab?
    @Environment(\.appIdiom) private var idiom

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.98, green: 0.62, blue: 0.40), Color(red: 0.55, green: 0.30, blue: 0.62), Color(red: 0.20, green: 0.22, blue: 0.48)],
                startPoint: .topLeading, endPoint: .bottomTrailing)
            Circle().fill(Color.white.opacity(0.14)).frame(width: 180).offset(x: -110, y: -60)
            if tab == .lockScreen {
                VStack(spacing: Theme.Spacing.s) {
                    Text(verbatim: "13:40")
                        .font(.system(size: 54, weight: .semibold, design: .rounded))
                        .foregroundStyle(.white.opacity(0.92))
                    HStack(spacing: Theme.Spacing.s) {
                        MiniWidgetPreview(family: .accessoryRectangular, width: 160)
                        MiniWidgetPreview(family: .accessoryCircular, width: 70)
                    }
                }
            } else if idiom == .desktop {
                HStack(spacing: Theme.Spacing.l) {
                    MiniWidgetPreview(family: .small, width: 124)
                    MiniWidgetPreview(family: .medium, width: 264)
                }
            } else {
                HStack(alignment: .center, spacing: Theme.Spacing.m) {
                    MiniWidgetPreview(family: .small, width: 118)
                    MiniWidgetPreview(family: .medium, width: 118 * 364 / 170 * 0.78)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: tab == .lockScreen ? 176 : 188)
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card + 2, style: .continuous))
        .accessibilityHidden(true)
    }
}

private struct GuideStepRow: View {
    let step: GuideStep

    var body: some View {
        HStack(spacing: Theme.Spacing.m) {
            StepNumber(number: step.id)
                .frame(width: 28)
            SymbolTile(symbol: step.symbol, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                atext(step.title)
                    .font(.system(.body, design: .rounded).weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                atext(step.detail)
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, Theme.Spacing.s + 2)
        .accessibilityElement(children: .combine)
    }
}

struct WidgetGuideScreen: View {
    let model: AppModel
    var onDone: (() -> Void)?

    var body: some View {
        SheetContainer(title: "Add the widget", onDone: onDone) {
            WidgetGuideContent(model: model)
        }
    }
}
