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

/// "Data as of" time for fresh data: a tiny clock plus the time, no pill. The stale state uses `StaleBadge`
/// (pill with `clock.arrow.circlepath`), so the two read as one family.
struct DataTimeLabel: View {
    let time: Date
    var timeZone: TimeZone = Constants.madrid

    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: "clock")
                .font(.system(size: 9, weight: .medium))
            Text(verbatim: Format.time(time, timeZone: timeZone))
                .monospacedDigit()
        }
        .font(.system(.caption2, design: .rounded).weight(.medium))
        .foregroundStyle(.secondary)
        .lineLimit(1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Data from \(Format.time(time, timeZone: timeZone))", tableName: "Widgets", bundle: .module))
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

/// Friendly full-area message for a source that has no data or is not set up: symbol, title, one-line hint and,
/// when a cached time exists, "Last update 11:35".
public struct SourceStateView: View {
    public enum Kind: Sendable {
        case notConfigured
        case solarError
        /// FusionSolar keeps rejecting the kiosk link: it most likely expired.
        case linkExpired
        case priceError
        /// Both sources are down.
        case unavailable
    }

    public let kind: Kind
    public var compact: Bool
    public var lastUpdate: Date?
    public var timeZone: TimeZone

    @Environment(\.widgetRenderingMode) private var mode

    public init(kind: Kind, compact: Bool = false, lastUpdate: Date? = nil, timeZone: TimeZone = Constants.madrid) {
        self.kind = kind
        self.compact = compact
        self.lastUpdate = lastUpdate
        self.timeZone = timeZone
    }

    public var body: some View {
        VStack(spacing: compact ? 3 : Theme.Spacing.xs) {
            Image(systemName: symbol)
                .font(.system(size: compact ? 18 : 28, weight: .semibold, design: .rounded))
                .foregroundStyle(tint)
                .widgetAccentable()
                .padding(.bottom, compact ? 1 : 3)
            title
                .font(.system(compact ? .caption : .subheadline, design: .rounded).weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(2)
            hint
                .font(.system(.caption2, design: .rounded).weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(compact ? 3 : 2)
            if let lastUpdate {
                wtext("Last update \(Format.time(lastUpdate, timeZone: timeZone))")
                    .font(.system(.caption2, design: .rounded).weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .padding(.top, 1)
            }
        }
        .multilineTextAlignment(.center)
        .minimumScaleFactor(0.8)
        .padding(.horizontal, compact ? 0 : Theme.Spacing.xs)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var symbol: String {
        switch kind {
        case .notConfigured: "gearshape"
        case .linkExpired: "link"
        case .unavailable: "wifi.exclamationmark"
        default: "exclamationmark.triangle"
        }
    }

    private var tint: AnyShapeStyle {
        guard mode == .fullColor else { return AnyShapeStyle(HierarchicalShapeStyle.primary) }
        return kind == .notConfigured ? AnyShapeStyle(HierarchicalShapeStyle.secondary) : AnyShapeStyle(Theme.Solar.orange)
    }

    private var title: Text {
        switch kind {
        case .notConfigured: wtext("Solar not set up")
        case .solarError: wtext("Solar data unavailable")
        case .linkExpired: wtext("Kiosk link expired")
        case .priceError: wtext("Prices unavailable")
        case .unavailable: wtext("Data unavailable")
        }
    }

    private var hint: Text {
        switch kind {
        case .notConfigured: wtext("Set up solar in SunWatt")
        case .linkExpired: wtext("Open SunWatt to replace it")
        default: wtext("Open SunWatt to check the connection")
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
