#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI

/// Link button. The system `Link` in the app, a drawn look-alike in snapshots (links do not render there).
struct AppLink<Label: View>: View {
    let destination: URL
    @ViewBuilder var label: Label
    @Environment(\.appSnapshot) private var snapshot

    var body: some View {
        if snapshot {
            Button(action: {}) { label }
        } else {
            Link(destination: destination) { label }
        }
    }
}

/// How to get the kiosk link from the FusionSolar website. Pure content; `KioskHelpScreen` wraps it.
struct KioskHelpContent: View {
    static let portalURL = URL(string: "https://eu5.fusionsolar.huawei.com")!

    @Environment(\.colorScheme) private var scheme

    private struct Step: Identifiable {
        let id: Int
        let title: LocalizedStringKey
        let detail: LocalizedStringKey
    }

    private let steps: [Step] = [
        Step(id: 1, title: "Sign in to the FusionSolar website",
             detail: "Use a browser on a computer and the server your installer gave you, for example eu5.fusionsolar.huawei.com. The kiosk link is created on the website."),
        Step(id: 2, title: "Open your plant", detail: "Pick your plant from the list."),
        Step(id: 3, title: "Click Kiosk", detail: "It is at the top of the plant page."),
        Step(id: 4, title: "Turn on the kiosk view", detail: "Switch the toggle on."),
        Step(id: 5, title: "Copy the link and paste it here", detail: "Copy the link that appears, then paste it in the Settings of SunWatt."),
    ]

    var body: some View {
        let palette = AppPalette(scheme)
        VStack(alignment: .leading, spacing: Theme.Spacing.l) {
            atext("SunWatt reads your plant through the public kiosk link of FusionSolar. You create that link once, on the website.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            KioskIllustration()

            VStack(alignment: .leading, spacing: 0) {
                ForEach(steps) { step in
                    HStack(alignment: .top, spacing: Theme.Spacing.m) {
                        StepNumber(number: step.id)
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
                    .padding(.vertical, Theme.Spacing.s + 1)
                    .accessibilityElement(children: .combine)
                    if step.id != steps.count {
                        Rectangle().fill(palette.hairline).frame(height: 0.5).padding(.leading, 26 + Theme.Spacing.m)
                    }
                }
            }
            .appCard(padding: Theme.Spacing.m + 2)

            AppLink(destination: Self.portalURL) {
                Label { atext("Open FusionSolar") } icon: { Image(systemName: "safari") }
            }
            .buttonStyle(PrimaryButtonStyle())

            HStack(alignment: .top, spacing: Theme.Spacing.m) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(palette.warning)
                VStack(alignment: .leading, spacing: 3) {
                    atext("Keep the link private")
                        .font(.system(.subheadline, design: .rounded).weight(.bold))
                    atext("Anyone with this link can see your plant's data. Huawei refreshes kiosk data about every 30 minutes.")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(Theme.Spacing.m + 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).fill(palette.warning.opacity(0.12)))
        }
    }
}

/// Simplified drawing of a browser window with the Kiosk button highlighted. Generic on purpose: the real
/// FusionSolar layout changes between versions.
private struct KioskIllustration: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = AppPalette(scheme)
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                ForEach(0..<3, id: \.self) { _ in Circle().fill(palette.hairline).frame(width: 8, height: 8) }
                Text(verbatim: "eu5.fusionsolar.huawei.com")
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .frame(maxWidth: .infinity)
                    .background(Capsule().fill(palette.fill))
            }
            .padding(10)
            Rectangle().fill(palette.hairline).frame(height: 0.5)
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    ForEach(0..<3, id: \.self) { _ in
                        Capsule().fill(palette.fill).frame(width: 54, height: 22)
                    }
                    Label { Text(verbatim: "Kiosk").font(.system(.caption, design: .rounded).weight(.bold)) } icon: {
                        Image(systemName: "rectangle.on.rectangle")
                    }
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(Theme.Solar.gradient))
                    Spacer(minLength: 0)
                }
                HStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 6).fill(palette.fill).frame(height: 38)
                    RoundedRectangle(cornerRadius: 6).fill(palette.fill).frame(height: 38)
                }
            }
            .padding(12)
        }
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(palette.card))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(palette.hairline, lineWidth: 0.75))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityHidden(true)
    }
}

struct KioskHelpScreen: View {
    var onDone: (() -> Void)?

    var body: some View {
        SheetContainer(title: "Get the kiosk link", onDone: onDone) {
            KioskHelpContent()
        }
    }
}
#endif
