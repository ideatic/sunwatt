import SwiftUI
import WidgetKit

// Watch-only complication views: the colored price gauge and the "Solar today" complication.

/// accessoryCircular price gauge for the watch: the current price within today's min...max. In full color the ring
/// is tinted green -> yellow -> red; in accented and vibrant modes the system drops the tint, so the ring position
/// and the accentable value carry the information. Watch specific on purpose: the iPhone Lock Screen keeps the
/// shared `AccessoryCircularView`.
public struct WatchPriceCircularView: View {
    public let entry: SunWattEntry
    @Environment(\.widgetRenderingMode) private var mode

    public init(entry: SunWattEntry) { self.entry = entry }

    public var body: some View {
        if let gauge = WatchCornerGauge(entry: entry) {
            Gauge(value: gauge.value, in: gauge.lower...gauge.upper) {
                Image(systemName: Theme.Price.symbol)
            } currentValueLabel: {
                Text(verbatim: Format.eurPerKWh(gauge.value))
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                    .widgetAccentable()
            }
            .gaugeStyle(.accessoryCircular)
            .tint(mode == .fullColor
                  ? Gradient(colors: [Theme.Price.green, Theme.Price.yellow, Theme.Price.red])
                  : Gradient(colors: [.white]))
            .widgetAccentable()
        } else {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.secondary)
        }
    }
}

/// Ring of the solar circular and corner gauges: current power within 0...today's peak (at least 1 kW, so a dull
/// morning does not fill the ring). Power vs peak reads best at a glance ("how good is it right now"); the day
/// progress of the curve would show the same thing every day. At night the ring is empty.
public struct WatchSolarGauge: Sendable {
    public let value: Double
    public let upper: Double
    public let fraction: Double

    public init?(entry: SunWattEntry) {
        let solar = SolarModel(entry: entry)
        guard solar.isAvailable else { return nil }
        upper = max(solar.snapshot?.peakKW ?? 0, 1)
        value = min(max(solar.powerKW ?? 0, 0), upper)
        fraction = value / upper
    }
}

/// What the solar complication shows for one entry.
enum WatchSolarState: Equatable {
    case notConfigured
    case error
    /// Today's energy and power are known (`idle` at night or without production).
    case data(idle: Bool, stale: Bool)
    /// The snapshot belongs to yesterday: nothing for today yet.
    case noDataYet
}

extension WatchSolarState {
    init(entry: SunWattEntry) {
        let solar = SolarModel(entry: entry)
        switch solar.phase {
        case .notConfigured: self = .notConfigured
        case .error: self = .error
        case .active, .idle:
            self = entry.isSolarFromEarlierDay ? .noDataYet : .data(idle: solar.isIdle, stale: solar.staleSince != nil)
        }
    }
}

/// Content of the "Solar today" complication for one entry. The extension adds the container background and the
/// corner gauge label (see `WatchSolarGauge`).
public struct WatchSolarComplicationView: View {
    public let entry: SunWattEntry
    public let family: WatchComplicationFamily

    public init(entry: SunWattEntry, family: WatchComplicationFamily) {
        self.entry = entry
        self.family = family
    }

    public var body: some View {
        let solar = SolarModel(entry: entry)
        let state = WatchSolarState(entry: entry)
        switch family {
        case .circular: WatchSolarCircular(entry: entry, solar: solar, state: state)
        case .rectangular: WatchSolarRectangular(entry: entry, solar: solar, state: state)
        case .inline: WatchSolarInline(solar: solar, state: state)
        case .corner: WatchSolarCornerContent(solar: solar, state: state)
        }
    }
}

/// Symbol for the state: sun by day, moon at night, warning on error (stale data shows a clock next to the time).
private func solarSymbol(_ state: WatchSolarState) -> String {
    switch state {
    case .notConfigured: "iphone.and.arrow.forward"
    case .error: "exclamationmark.triangle"
    case .noDataYet: Theme.Solar.nightSymbol
    case .data(let idle, _):
        idle ? Theme.Solar.nightSymbol : Theme.Solar.symbol
    }
}

/// Energy figure: today's kWh, "0" when there is no data for today yet.
private func energyText(_ solar: SolarModel, _ state: WatchSolarState) -> String {
    state == .noDataYet ? Format.kWh(0) : Format.kWh(solar.dailyKWh)
}

/// Solar symbol tinted amber in full color, primary otherwise.
private struct WatchSolarSymbol: View {
    let state: WatchSolarState
    var size: CGFloat = 13
    @Environment(\.widgetRenderingMode) private var mode

    var body: some View {
        let muted: Bool = {
            switch state {
            case .notConfigured, .error: true
            case .noDataYet: true
            case .data(let idle, let stale): idle || stale
            }
        }()
        Image(systemName: solarSymbol(state))
            .font(.system(size: size, weight: .semibold))
            .symbolRenderingMode(.monochrome)
            .foregroundStyle(muted
                ? AnyShapeStyle(HierarchicalShapeStyle.secondary)
                : (mode == .fullColor ? AnyShapeStyle(Theme.Solar.gradient) : AnyShapeStyle(HierarchicalShapeStyle.primary)))
            .widgetAccentable(!muted)
    }
}

struct WatchSolarCircular: View {
    let entry: SunWattEntry
    let solar: SolarModel
    let state: WatchSolarState
    @Environment(\.widgetRenderingMode) private var mode

    var body: some View {
        switch state {
        case .notConfigured:
            WatchSolarSymbol(state: state, size: 22)
                .accessibilityLabel(watchText("Set up on iPhone"))
        case .error:
            WatchSolarSymbol(state: state, size: 22)
                .accessibilityLabel(wtext("Solar data unavailable"))
        case .noDataYet, .data:
            let gauge = WatchSolarGauge(entry: entry)
            Gauge(value: state == .noDataYet ? 0 : (gauge?.value ?? 0), in: 0...(gauge?.upper ?? 1)) {
                WatchSolarSymbol(state: state, size: 10)
            } currentValueLabel: {
                VStack(spacing: -2) {
                    Text(verbatim: energyText(solar, state))
                        .font(.system(size: 15, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .minimumScaleFactor(0.6)
                        .lineLimit(1)
                    Text(verbatim: "kWh")
                        .font(.system(size: 8, weight: .semibold, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .widgetAccentable()
            }
            .gaugeStyle(.accessoryCircular)
            .tint(mode == .fullColor
                  ? Gradient(colors: [Theme.Solar.amber, Theme.Solar.orange])
                  : Gradient(colors: [.white]))
            .widgetAccentable()
        }
    }
}

struct WatchSolarRectangular: View {
    let entry: SunWattEntry
    let solar: SolarModel
    let state: WatchSolarState

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            switch state {
            case .notConfigured:
                row(symbol: 15) { watchText("Set up on iPhone").font(.system(.footnote, design: .rounded).weight(.semibold)) }
                Spacer(minLength: 0)
            case .error:
                row(symbol: 15) {
                    wtext("Solar data unavailable")
                        .font(.system(.footnote, design: .rounded).weight(.medium))
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            case .noDataYet:
                row(symbol: 15) { watchText("No data yet").font(.system(size: 15, weight: .bold, design: .rounded)) }
                Spacer(minLength: 0)
            case .data(_, let stale):
                row(symbol: 15) {
                    wtext("\(Format.kWh(solar.dailyKWh)) kWh today")
                        .font(.system(size: 15, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .widgetAccentable()
                }
                HStack(spacing: 4) {
                    Text(verbatim: "\(Format.kW(solar.powerKW)) kW")
                        .monospacedDigit()
                    Spacer(minLength: 0)
                    if let time = solar.dataTime {
                        if stale {
                            Image(systemName: "clock.arrow.circlepath").imageScale(.small)
                        }
                        Text(verbatim: Format.time(time)).monospacedDigit()
                    }
                }
                .font(.system(.caption2, design: .rounded).weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                if let snapshot = solar.snapshot {
                    SolarCurveChart(snapshot: snapshot, now: entry.date, style: .mini)
                        .frame(maxHeight: .infinity)
                        .padding(.top, 2)
                } else {
                    Spacer(minLength: 0)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private func row<Content: View>(symbol: CGFloat, @ViewBuilder _ content: () -> Content) -> some View {
        HStack(spacing: 5) {
            WatchSolarSymbol(state: state, size: symbol)
            content().lineLimit(1).minimumScaleFactor(0.7)
        }
    }
}

struct WatchSolarInline: View {
    let solar: SolarModel
    let state: WatchSolarState

    var body: some View {
        Label {
            switch state {
            case .notConfigured: watchText("Set up on iPhone")
            case .error: watchText("Solar unavailable")
            case .noDataYet: watchText("No data yet")
            case .data: wtext("\(Format.kWh(solar.dailyKWh)) kWh today")
            }
        } icon: {
            Image(systemName: solarSymbol(state))
        }
    }
}

/// accessoryCorner content: the symbol and today's kWh. The extension puts the power gauge in `.widgetLabel`.
public struct WatchSolarCornerContent: View {
    let solar: SolarModel
    let state: WatchSolarState

    public init(entry: SunWattEntry) {
        solar = SolarModel(entry: entry)
        state = WatchSolarState(entry: entry)
    }

    init(solar: SolarModel, state: WatchSolarState) {
        self.solar = solar
        self.state = state
    }

    public var body: some View {
        VStack(spacing: -1) {
            WatchSolarSymbol(state: state, size: 13)
            switch state {
            case .data, .noDataYet:
                // Fixed width so large totals ("123,4") shrink instead of being cut by the corner.
                Text(verbatim: energyText(solar, state))
                    .font(Theme.Font.figure(15))
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .frame(maxWidth: 30)
            default:
                EmptyView()
            }
        }
        .widgetAccentable()
    }
}
