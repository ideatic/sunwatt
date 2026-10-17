import SwiftUI

/// HomeDash design tokens. Use these instead of literal values in views.
public enum Theme {
    // MARK: Spacing
    public enum Spacing {
        public static let xxs: CGFloat = 2
        public static let xs: CGFloat = 4
        public static let s: CGFloat = 8
        public static let m: CGFloat = 12
        public static let l: CGFloat = 16
        public static let xl: CGFloat = 24
    }

    // MARK: Radii
    public enum Radius {
        public static let bar: CGFloat = 3
        public static let pill: CGFloat = 999
        public static let card: CGFloat = 20
        /// Widget corner radius used by the snapshot harness (system draws the real one).
        public static let widget: CGFloat = 22
    }

    // MARK: Solar identity (warm amber -> soft orange)
    public enum Solar {
        public static let amber = Color(red: 1.00, green: 0.72, blue: 0.20)
        public static let orange = Color(red: 1.00, green: 0.52, blue: 0.24)
        public static let gradient = LinearGradient(colors: [amber, orange], startPoint: .top, endPoint: .bottom)
        /// Area fill under the production curve, fading to transparent.
        public static let areaFill = LinearGradient(
            colors: [orange.opacity(0.45), amber.opacity(0.18), amber.opacity(0.0)],
            startPoint: .top, endPoint: .bottom
        )
        public static let line = orange
        public static let idle = Color.secondary
        public static let symbol = "sun.max.fill"
        public static let nightSymbol = "moon.stars.fill"
    }

    // MARK: Price identity (green -> yellow -> red)
    public enum Price {
        public static let green = Color(red: 0.20, green: 0.78, blue: 0.45)
        public static let yellow = Color(red: 0.98, green: 0.78, blue: 0.18)
        public static let red = Color(red: 0.96, green: 0.33, blue: 0.29)
        public static let symbol = "bolt.fill"

        /// Continuous scale for a 0...1 fraction of the day's range.
        public static func color(fraction: Double) -> Color {
            let f = min(max(fraction, 0), 1)
            if f < 0.5 { return mix(green, yellow, f / 0.5) }
            return mix(yellow, red, (f - 0.5) / 0.5)
        }

        public static func color(for level: PriceLevel) -> Color {
            switch level {
            case .low: green
            case .medium: yellow
            case .high: red
            }
        }

        public static let pastOpacity: Double = 0.42
        public static let futureOpacity: Double = 0.82
        /// Dark mode needs a higher past opacity and a neutral blend: over near-black, low-opacity red or amber turns brown.
        public static let pastOpacityDark: Double = 0.72

        public static func pastOpacity(for scheme: ColorScheme) -> Double {
            scheme == .dark ? pastOpacityDark : pastOpacity
        }

        /// Dimmed color of a past bar: in dark mode the hue is blended toward a neutral grey first.
        public static func pastColor(_ base: Color, scheme: ColorScheme) -> Color {
            scheme == .dark ? mix(base, Color(white: 0.62), 0.52) : base
        }

        /// Smallest bar height as a fraction of the Y span, so very cheap hours still show their color.
        public static let minBarFractionMini: Double = 0.07
        public static let minBarFractionFull: Double = 0.035
    }

    // MARK: Backgrounds
    public enum Background {
        /// Soft widget background; subtle in both light and dark.
        public static func widget(_ scheme: ColorScheme) -> LinearGradient {
            switch scheme {
            case .dark:
                LinearGradient(colors: [Color(white: 0.13), Color(white: 0.08)], startPoint: .topLeading, endPoint: .bottomTrailing)
            default:
                LinearGradient(colors: [Color(white: 1.0), Color(red: 0.96, green: 0.95, blue: 0.93)], startPoint: .topLeading, endPoint: .bottomTrailing)
            }
        }
    }

    // MARK: Typography (SF Rounded for figures)
    public enum Font {
        public static func figure(_ size: CGFloat, weight: SwiftUI.Font.Weight = .bold) -> SwiftUI.Font {
            .system(size: size, weight: weight, design: .rounded).monospacedDigit()
        }
        public static func unit(_ size: CGFloat) -> SwiftUI.Font {
            .system(size: size, weight: .semibold, design: .rounded)
        }
        public static let caption = SwiftUI.Font.system(.caption2, design: .rounded).weight(.medium)
        public static let label = SwiftUI.Font.system(.footnote, design: .rounded).weight(.semibold)
    }

    static func mix(_ a: Color, _ b: Color, _ t: Double) -> Color {
        let ra = a.resolve(in: EnvironmentValues())
        let rb = b.resolve(in: EnvironmentValues())
        let t = Float(min(max(t, 0), 1))
        return Color(
            red: Double(ra.red + (rb.red - ra.red) * t),
            green: Double(ra.green + (rb.green - ra.green) * t),
            blue: Double(ra.blue + (rb.blue - ra.blue) * t)
        )
    }
}
