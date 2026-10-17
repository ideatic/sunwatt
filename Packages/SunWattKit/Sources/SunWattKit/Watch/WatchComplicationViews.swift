import SwiftUI
import WidgetKit

/// Complication families SunWatt supports on the watch.
public enum WatchComplicationFamily: String, CaseIterable, Sendable {
    case circular
    case rectangular
    case inline
    case corner

    /// Reference size in points (Apple Watch 46 mm).
    public var size: CGSize {
        switch self {
        case .circular: CGSize(width: 50, height: 50)
        case .rectangular: CGSize(width: 172, height: 76)
        case .inline: CGSize(width: 190, height: 24)
        case .corner: CGSize(width: 62, height: 62)
        }
    }

    #if os(watchOS)
    public init?(_ family: WidgetFamily) {
        switch family {
        case .accessoryCircular: self = .circular
        case .accessoryRectangular: self = .rectangular
        case .accessoryInline: self = .inline
        case .accessoryCorner: self = .corner
        default: return nil
        }
    }
    #endif
}

/// Complication content for one timeline entry. The extension adds the container background and, for the corner
/// family, the gauge label (see `WatchCornerGauge`).
public struct WatchComplicationView: View {
    public let entry: SunWattEntry
    public let family: WatchComplicationFamily

    public init(entry: SunWattEntry, family: WatchComplicationFamily) {
        self.entry = entry
        self.family = family
    }

    public var body: some View {
        switch family {
        case .circular:
            SunWattWidgetView(entry: entry, family: .accessoryCircular, content: .priceOnly)
        case .rectangular:
            SunWattWidgetView(entry: entry, family: .accessoryRectangular, content: .both)
        case .inline:
            WatchInlineView(entry: entry)
        case .corner:
            WatchCornerContent(entry: entry)
        }
    }
}

/// accessoryInline: "bolt 0,018 €/kWh".
public struct WatchInlineView: View {
    public let entry: SunWattEntry

    public init(entry: SunWattEntry) { self.entry = entry }

    public var body: some View {
        let price = PriceModel(entry: entry, window: .wholeDay)
        if price.series != nil, let current = price.current {
            Label {
                Text(verbatim: "\(Format.eurPerKWh(current.eurPerKWh)) €/kWh")
            } icon: {
                Image(systemName: Theme.Price.symbol)
            }
        } else {
            Label {
                wtext("Prices unavailable")
            } icon: {
                Image(systemName: "exclamationmark.triangle")
            }
        }
    }
}

/// Day range for the corner gauge.
public struct WatchCornerGauge: Sendable {
    public let value: Double
    public let lower: Double
    public let upper: Double
    public let fraction: Double

    public init?(entry: SunWattEntry) {
        let price = PriceModel(entry: entry, window: .wholeDay)
        guard price.series != nil, let stats = price.stats, let current = price.current else { return nil }
        lower = stats.min
        upper = max(stats.max, stats.min + 0.001)
        value = min(max(current.eurPerKWh, lower), upper)
        fraction = price.fraction ?? 0.5
    }
}

/// accessoryCorner content: the bolt and the price. The extension puts the gauge in `.widgetLabel`.
public struct WatchCornerContent: View {
    public let entry: SunWattEntry

    public init(entry: SunWattEntry) { self.entry = entry }

    public var body: some View {
        let price = PriceModel(entry: entry, window: .wholeDay)
        VStack(spacing: -1) {
            Image(systemName: price.series == nil ? "exclamationmark.triangle" : Theme.Price.symbol)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(price.series == nil ? AnyShapeStyle(.secondary) : AnyShapeStyle(Theme.Price.color(fraction: price.fraction ?? 0.5)))
            if price.series != nil {
                Text(verbatim: Format.eurPerKWh(price.current?.eurPerKWh))
                    .font(Theme.Font.figure(15))
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
            }
        }
        .widgetAccentable()
    }
}
