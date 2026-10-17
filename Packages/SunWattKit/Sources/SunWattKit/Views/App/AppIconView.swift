#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI

/// The SunWatt icon: a sun with a bolt on a warm gradient. Used in onboarding and rendered by the snapshot
/// tool into the app icon files (`SunWattSnapshots --icon <dir>`).
public struct AppIconView: View {
    public enum Style: Sendable {
        /// Full-bleed square (iOS app icon: the system applies the mask).
        case square
        /// Rounded square with the standard macOS margin and shadow, on a transparent canvas.
        case macOS
        /// Rounded square filling the frame, for in-app use.
        case badge
    }

    public var size: CGFloat
    public var style: Style

    public init(size: CGFloat, style: Style = .badge) {
        self.size = size
        self.style = style
    }

    public var body: some View {
        switch style {
        case .square:
            artwork(side: size)
                .frame(width: size, height: size)
        case .badge:
            artwork(side: size)
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: size * 0.2237, style: .continuous))
        case .macOS:
            let inner = size * 824 / 1024
            artwork(side: inner)
                .frame(width: inner, height: inner)
                .clipShape(RoundedRectangle(cornerRadius: inner * 0.2237, style: .continuous))
                .shadow(color: .black.opacity(0.28), radius: size * 0.012, y: size * 0.008)
                .frame(width: size, height: size)
        }
    }

    private func artwork(side s: CGFloat) -> some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 1.0, green: 0.80, blue: 0.30), Color(red: 1.0, green: 0.55, blue: 0.22), Color(red: 0.93, green: 0.36, blue: 0.20)],
                startPoint: .top, endPoint: .bottom)
            RadialGradient(
                colors: [Color.white.opacity(0.45), .clear],
                center: UnitPoint(x: 0.3, y: 0.18), startRadius: 0, endRadius: s * 0.75)

            // Rays
            ForEach(0..<12, id: \.self) { index in
                Capsule()
                    .fill(Color.white.opacity(index.isMultiple(of: 2) ? 0.95 : 0.7))
                    .frame(width: s * 0.062, height: s * (index.isMultiple(of: 2) ? 0.13 : 0.09))
                    .offset(y: -s * 0.355)
                    .rotationEffect(.degrees(Double(index) * 30))
            }

            // Sun disc
            Circle()
                .fill(LinearGradient(
                    colors: [Color.white, Color(red: 1.0, green: 0.95, blue: 0.80)],
                    startPoint: .top, endPoint: .bottom))
                .frame(width: s * 0.50, height: s * 0.50)
                .shadow(color: Color(red: 0.75, green: 0.25, blue: 0.05).opacity(0.35), radius: s * 0.03, y: s * 0.015)

            // Bolt
            Image(systemName: "bolt.fill")
                .font(.system(size: s * 0.30, weight: .bold))
                .foregroundStyle(LinearGradient(
                    colors: [Color(red: 1.0, green: 0.60, blue: 0.15), Color(red: 0.92, green: 0.30, blue: 0.16)],
                    startPoint: .top, endPoint: .bottom))
        }
        .frame(width: s, height: s)
    }
}
#endif
