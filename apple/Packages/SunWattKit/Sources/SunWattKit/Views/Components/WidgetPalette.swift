import SwiftUI
import WidgetKit

/// Colors per WidgetKit rendering mode. Full color uses the SunWatt identity colors; accented and vibrant use
/// only the foreground hierarchy (opacity), so nothing relies on hue.
struct WidgetPalette {
    let mode: WidgetRenderingMode

    var isFullColor: Bool { mode == .fullColor }

    func solarIcon(idle: Bool) -> AnyShapeStyle {
        if idle { return AnyShapeStyle(HierarchicalShapeStyle.secondary) }
        return isFullColor ? AnyShapeStyle(Theme.Solar.gradient) : AnyShapeStyle(HierarchicalShapeStyle.primary)
    }

    func priceColor(fraction: Double?) -> AnyShapeStyle {
        // Chart marks use an explicit Color.primary: hierarchical styles resolve to the accent color inside Charts.
        guard isFullColor, let fraction else { return AnyShapeStyle(Color.primary) }
        return AnyShapeStyle(Theme.Price.color(fraction: fraction))
    }

    func levelColor(_ level: PriceLevel?) -> AnyShapeStyle {
        guard isFullColor, let level else { return AnyShapeStyle(HierarchicalShapeStyle.secondary) }
        return AnyShapeStyle(Theme.Price.color(for: level))
    }

    var solarLine: AnyShapeStyle {
        isFullColor ? AnyShapeStyle(Theme.Solar.line) : AnyShapeStyle(Color.primary)
    }

    var solarArea: AnyShapeStyle {
        isFullColor
            ? AnyShapeStyle(Theme.Solar.areaFill)
            : AnyShapeStyle(LinearGradient(
                colors: [Color.primary.opacity(0.35), Color.primary.opacity(0.0)],
                startPoint: .top, endPoint: .bottom))
    }

    var solarGlow: AnyShapeStyle {
        isFullColor ? AnyShapeStyle(Theme.Solar.amber) : AnyShapeStyle(Color.primary)
    }
}
