import SwiftUI
import WidgetKit

/// Small "data is older than expected" marker: clock symbol plus the time of the data. Meant to be quiet.
public struct StaleBadge: View {
    public let since: Date
    public var timeZone: TimeZone

    public init(since: Date, timeZone: TimeZone = Constants.madrid) {
        self.since = since
        self.timeZone = timeZone
    }

    public var body: some View {
        HStack(spacing: 3) {
            Image(systemName: "clock.arrow.circlepath")
                .imageScale(.small)
            Text(verbatim: Format.time(since, timeZone: timeZone))
                .monospacedDigit()
        }
        .font(.system(.caption2, design: .rounded).weight(.semibold))
        .foregroundStyle(.secondary)
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .background(.secondary.opacity(0.14), in: Capsule())
        .lineLimit(1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Data from \(Format.time(since, timeZone: timeZone))", tableName: "Widgets", bundle: .module))
    }
}

/// Plain "data as of" time, shown when the data is fresh.
struct DataTimeLabel: View {
    let time: Date
    var timeZone: TimeZone = Constants.madrid

    var body: some View {
        Text(verbatim: Format.time(time, timeZone: timeZone))
            .font(.system(.caption2, design: .rounded).weight(.medium))
            .monospacedDigit()
            .foregroundStyle(.secondary)
            .lineLimit(1)
    }
}

/// Data-time marker: a `StaleBadge` when stale, else a quiet time label (or nothing when `showFresh` is false).
struct DataTimeMarker: View {
    let time: Date?
    let stale: Bool
    var showFresh = true
    var timeZone: TimeZone = Constants.madrid

    var body: some View {
        if let time {
            if stale {
                StaleBadge(since: time, timeZone: timeZone)
            } else if showFresh {
                DataTimeLabel(time: time, timeZone: timeZone)
            }
        }
    }
}

/// Friendly full-area message for a source that has no data or is not set up.
public struct SourceStateView: View {
    public enum Kind: Sendable {
        case notConfigured
        case solarError
        case priceError
        /// Both sources are down.
        case unavailable
    }

    public let kind: Kind
    public var compact: Bool

    @Environment(\.widgetRenderingMode) private var mode

    public init(kind: Kind, compact: Bool = false) {
        self.kind = kind
        self.compact = compact
    }

    public var body: some View {
        VStack(spacing: compact ? Theme.Spacing.xs : Theme.Spacing.s) {
            Image(systemName: symbol)
                .font(.system(size: compact ? 18 : 26, weight: .semibold, design: .rounded))
                .foregroundStyle(tint)
                .widgetAccentable()
            message
                .font(.system(compact ? .caption2 : .footnote, design: .rounded).weight(.medium))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var symbol: String {
        kind == .notConfigured ? "gearshape" : "exclamationmark.triangle"
    }

    private var tint: AnyShapeStyle {
        guard mode == .fullColor else { return AnyShapeStyle(HierarchicalShapeStyle.primary) }
        return kind == .notConfigured ? AnyShapeStyle(HierarchicalShapeStyle.secondary) : AnyShapeStyle(Theme.Solar.orange)
    }

    private var message: Text {
        switch kind {
        case .notConfigured: wtext("Set up the kiosk in the app")
        case .solarError: wtext("Solar data unavailable")
        case .priceError: wtext("Prices unavailable")
        case .unavailable: wtext("Data unavailable")
        }
    }
}

/// Capsule with the current price, tinted by level (green / yellow / red).
public struct PricePill: View {
    public let eurPerKWh: Double?
    public let fraction: Double?
    public var showsUnit: Bool

    @Environment(\.widgetRenderingMode) private var mode

    public init(eurPerKWh: Double?, fraction: Double?, showsUnit: Bool = true) {
        self.eurPerKWh = eurPerKWh
        self.fraction = fraction
        self.showsUnit = showsUnit
    }

    public var body: some View {
        let palette = WidgetPalette(mode: mode)
        HStack(spacing: 5) {
            Image(systemName: Theme.Price.symbol)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(palette.priceColor(fraction: fraction))
            FigureText(value: Format.eurPerKWh(eurPerKWh), unit: showsUnit ? "€/kWh" : nil, size: 15)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background {
            Capsule().fill(background(palette))
        }
        .widgetAccentable()
    }

    private func background(_ palette: WidgetPalette) -> AnyShapeStyle {
        if palette.isFullColor, let fraction {
            return AnyShapeStyle(Theme.Price.color(fraction: fraction).opacity(0.20))
        }
        return AnyShapeStyle(Color.primary.opacity(0.12))
    }
}
