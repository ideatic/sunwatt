#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

// App-only design tokens and helpers. They sit on top of `Theme` (shared with the widgets) and add what a
// full-screen app needs: adaptive page background, cards, buttons and environment switches.

/// Which layout the app screens use. Derived from the platform; snapshots set it explicitly.
enum AppIdiom: Sendable {
    case phone
    case pad
    case desktop

    static var platformDefault: AppIdiom {
        #if os(macOS)
        .desktop
        #else
        UIDevice.current.userInterfaceIdiom == .pad ? .pad : .phone
        #endif
    }
}

private struct AppIdiomKey: EnvironmentKey { static let defaultValue = AppIdiom.platformDefault }
private struct AppSnapshotKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    var appIdiom: AppIdiom {
        get { self[AppIdiomKey.self] }
        set { self[AppIdiomKey.self] = newValue }
    }

    /// True while a screen is rendered with `ImageRenderer`. AppKit-backed controls (text fields, lists,
    /// segmented pickers, links) do not render there, so the app draws look-alikes instead.
    var appSnapshot: Bool {
        get { self[AppSnapshotKey.self] }
        set { self[AppSnapshotKey.self] = newValue }
    }
}

/// Localized text from the package `App` catalog. Follows `\.locale` of the environment.
func atext(_ key: LocalizedStringKey) -> Text {
    Text(key, tableName: "App", bundle: .module)
}

/// Localized `String` from the `App` catalog (process language).
func astring(_ key: String.LocalizationValue) -> String {
    L10n.string(key, table: "App")
}

/// Colors that depend on the color scheme. Resolved explicitly from the environment so `ImageRenderer`
/// output is deterministic in light and dark.
struct AppPalette {
    let scheme: ColorScheme

    init(_ scheme: ColorScheme) { self.scheme = scheme }

    var isDark: Bool { scheme == .dark }

    var backgroundGradient: LinearGradient {
        isDark
            ? LinearGradient(colors: [Color(white: 0.055), Color(white: 0.02)], startPoint: .top, endPoint: .bottom)
            : LinearGradient(colors: [Color(red: 0.975, green: 0.965, blue: 0.945), Color(red: 0.94, green: 0.93, blue: 0.91)], startPoint: .top, endPoint: .bottom)
    }

    var card: Color { isDark ? Color(white: 0.115) : .white }
    var cardStroke: Color { isDark ? Color.white.opacity(0.08) : Color.black.opacity(0.05) }
    var cardShadow: Color { isDark ? .clear : Color(red: 0.4, green: 0.28, blue: 0.1).opacity(0.10) }
    /// Inset fields, chips and secondary buttons sitting on a card.
    var fill: Color { isDark ? Color.white.opacity(0.07) : Color.black.opacity(0.045) }
    var hairline: Color { isDark ? Color.white.opacity(0.10) : Color.black.opacity(0.08) }
    /// Orange used for text and symbols; darker in light mode to keep contrast on white.
    var accentText: Color { isDark ? Theme.Solar.amber : Color(red: 0.74, green: 0.32, blue: 0.0) }
    var calloutFill: Color { isDark ? Color(white: 0.20) : .white }
    /// Label color on the amber primary button.
    var onAccent: Color { Color(red: 0.22, green: 0.09, blue: 0.0) }
    var success: Color { isDark ? Theme.Price.green : Color(red: 0.10, green: 0.58, blue: 0.30) }
    var warning: Color { isDark ? Theme.Solar.amber : Color(red: 0.78, green: 0.38, blue: 0.0) }
}

/// Page background: soft warm gradient with an amber glow in the top corner.
struct AppBackground: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = AppPalette(scheme)
        ZStack {
            Rectangle().fill(palette.backgroundGradient)
            RadialGradient(
                colors: [Theme.Solar.amber.opacity(palette.isDark ? 0.10 : 0.16), .clear],
                center: .topLeading, startRadius: 0, endRadius: 420)
            RadialGradient(
                colors: [Theme.Price.green.opacity(palette.isDark ? 0.05 : 0.07), .clear],
                center: .bottomTrailing, startRadius: 0, endRadius: 380)
        }
        .ignoresSafeArea()
    }
}

/// Rounded card on the page background.
struct AppCardModifier: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    var padding: CGFloat

    func body(content: Content) -> some View {
        let palette = AppPalette(scheme)
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: Theme.Radius.card + 2, style: .continuous)
                    .fill(palette.card)
                    .shadow(color: palette.cardShadow, radius: 14, y: 5)
            }
            .overlay {
                RoundedRectangle(cornerRadius: Theme.Radius.card + 2, style: .continuous)
                    .strokeBorder(palette.cardStroke, lineWidth: 0.75)
            }
    }
}

extension View {
    func appCard(padding: CGFloat = Theme.Spacing.l + 2) -> some View {
        modifier(AppCardModifier(padding: padding))
    }
}

// MARK: Buttons

/// Amber gradient button with a dark label (white on amber would not pass contrast).
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.colorScheme) private var scheme
    var compact = false

    func makeBody(configuration: Configuration) -> some View {
        let palette = AppPalette(scheme)
        configuration.label
            .font(.system(compact ? .subheadline : .body, design: .rounded).weight(.bold))
            .foregroundStyle(palette.onAccent)
            .padding(.horizontal, compact ? 14 : 20)
            .padding(.vertical, compact ? 9 : 14)
            .frame(maxWidth: compact ? nil : .infinity)
            .background(
                RoundedRectangle(cornerRadius: compact ? 12 : 16, style: .continuous)
                    .fill(LinearGradient(
                        colors: [Color(red: 1.0, green: 0.76, blue: 0.26), Color(red: 1.0, green: 0.56, blue: 0.22)],
                        startPoint: .top, endPoint: .bottom))
            )
            .overlay {
                RoundedRectangle(cornerRadius: compact ? 12 : 16, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.35), lineWidth: 0.75)
            }
            .opacity(configuration.isPressed ? 0.8 : 1)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .contentShape(Rectangle())
    }
}

/// Quiet button on a card: inset fill, accent text.
struct SoftButtonStyle: ButtonStyle {
    @Environment(\.colorScheme) private var scheme
    var compact = false
    var tint: Color?

    func makeBody(configuration: Configuration) -> some View {
        let palette = AppPalette(scheme)
        configuration.label
            .font(.system(compact ? .footnote : .subheadline, design: .rounded).weight(.semibold))
            .foregroundStyle(tint ?? palette.accentText)
            .padding(.horizontal, compact ? 12 : 16)
            .padding(.vertical, compact ? 7 : 11)
            .frame(maxWidth: compact ? nil : .infinity)
            .background(RoundedRectangle(cornerRadius: compact ? 10 : 14, style: .continuous).fill(palette.fill))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .contentShape(Rectangle())
    }
}

/// Plain text-like button (back, skip).
struct QuietButtonStyle: ButtonStyle {
    @Environment(\.colorScheme) private var scheme

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(.body, design: .rounded).weight(.semibold))
            .foregroundStyle(AppPalette(scheme).accentText)
            .opacity(configuration.isPressed ? 0.6 : 1)
            .contentShape(Rectangle())
    }
}
#endif
