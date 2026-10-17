#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI

// Small reusable pieces of the app screens.

/// Section title above a card or inside it: symbol, small caps title, optional trailing view.
struct CardHeader<Trailing: View>: View {
    let symbol: String
    let tint: AnyShapeStyle
    let title: Text
    var subtitle: Text?
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: Theme.Spacing.s) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 0) {
                title
                    .font(.system(.subheadline, design: .rounded).weight(.bold))
                    .lineLimit(1)
                if let subtitle {
                    subtitle
                        .font(.system(.caption, design: .rounded).weight(.medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: Theme.Spacing.s)
            trailing
        }
    }
}

extension CardHeader where Trailing == EmptyView {
    init(symbol: String, tint: AnyShapeStyle, title: Text, subtitle: Text? = nil) {
        self.init(symbol: symbol, tint: tint, title: title, subtitle: subtitle) { EmptyView() }
    }
}

/// Segmented control drawn by the app (the system one does not render in snapshots and cannot match the style).
struct AppSegmented<Value: Hashable>: View {
    let options: [(value: Value, label: LocalizedStringKey)]
    @Binding var selection: Value
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = AppPalette(scheme)
        HStack(spacing: 2) {
            ForEach(options.indices, id: \.self) { index in
                let option = options[index]
                let isSelected = option.value == selection
                Button {
                    withAnimation(.snappy(duration: 0.2)) { selection = option.value }
                } label: {
                    atext(option.label)
                        .font(.system(.footnote, design: .rounded).weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(isSelected ? palette.onAccent : Color.secondary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .frame(maxWidth: .infinity)
                        .background {
                            if isSelected {
                                Capsule().fill(LinearGradient(
                                    colors: [Color(red: 1.0, green: 0.78, blue: 0.30), Color(red: 1.0, green: 0.62, blue: 0.24)],
                                    startPoint: .top, endPoint: .bottom))
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .padding(3)
        .background(Capsule().fill(palette.fill))
    }
}

/// Switch. Uses the system `Toggle` in the app and a drawn look-alike in snapshots.
struct AppSwitch: View {
    @Binding var isOn: Bool
    @Environment(\.appSnapshot) private var snapshot

    var body: some View {
        if snapshot {
            Capsule()
                .fill(isOn ? Theme.Solar.orange : Color.secondary.opacity(0.3))
                .frame(width: 51, height: 31)
                .overlay(alignment: isOn ? .trailing : .leading) {
                    Circle().fill(.white).frame(width: 27, height: 27).padding(2)
                        .shadow(color: .black.opacity(0.2), radius: 2, y: 1)
                }
        } else {
            Toggle(isOn: $isOn) { EmptyView() }
                .labelsHidden()
                .tint(Theme.Solar.orange)
                #if os(macOS)
                .toggleStyle(.switch)
                #endif
        }
    }
}

/// Row with a title, an explanation and a switch.
struct AppToggleRow: View {
    let title: LocalizedStringKey
    let detail: LocalizedStringKey
    @Binding var isOn: Bool

    var body: some View {
        HStack(alignment: .center, spacing: Theme.Spacing.m) {
            VStack(alignment: .leading, spacing: 3) {
                atext(title).font(.system(.body, design: .rounded).weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                atext(detail)
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: Theme.Spacing.s)
            AppSwitch(isOn: $isOn)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Multi-line URL field on an inset background. A drawn look-alike in snapshots.
struct AppTextField: View {
    let prompt: String
    @Binding var text: String
    /// Called when the user submits or leaves the field.
    var onCommit: (() -> Void)?
    @Environment(\.colorScheme) private var scheme
    @Environment(\.appSnapshot) private var snapshot
    @FocusState private var focused: Bool

    var body: some View {
        let palette = AppPalette(scheme)
        Group {
            if snapshot {
                Group {
                    if text.isEmpty {
                        Text(verbatim: prompt).foregroundStyle(.tertiary)
                    } else {
                        Text(verbatim: text).lineLimit(3)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                TextField(text: $text, prompt: Text(verbatim: prompt), axis: .vertical) { EmptyView() }
                    .lineLimit(1...3)
                    .focused($focused)
                    .onChange(of: focused) { _, isFocused in
                        if !isFocused { onCommit?() }
                    }
                    .onSubmit { onCommit?() }
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .textContentType(.URL)
                    #endif
                    #if os(macOS)
                    .textFieldStyle(.plain)
                    #endif
            }
        }
        .font(.system(.footnote, design: .monospaced))
        .padding(.horizontal, 12)
        .padding(.vertical, 11)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(palette.fill))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(palette.hairline, lineWidth: 0.5))
    }
}

/// "● Cheap" capsule tinted by the price level.
struct LevelPill: View {
    let level: PriceLevel

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(Theme.Price.color(for: level)).frame(width: 8, height: 8)
            atext(label)
                .font(.system(.footnote, design: .rounded).weight(.semibold))
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(Capsule().fill(Theme.Price.color(for: level).opacity(0.18)))
    }

    private var label: LocalizedStringKey {
        switch level {
        case .low: "Cheap"
        case .medium: "Normal"
        case .high: "Expensive"
        }
    }
}

/// A figure with a small caption below: "412,6 kWh" / "This month".
struct MiniStat: View {
    let value: String
    let unit: String
    let caption: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            FigureText(value: value, unit: unit, size: 17)
            atext(caption)
                .font(.system(.caption, design: .rounded).weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

/// Number inside a circle, for step lists.
struct StepNumber: View {
    let number: Int
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text(verbatim: "\(number)")
            .font(.system(.footnote, design: .rounded).weight(.heavy))
            .foregroundStyle(AppPalette(scheme).onAccent)
            .frame(width: 26, height: 26)
            .background(Circle().fill(LinearGradient(
                colors: [Color(red: 1.0, green: 0.78, blue: 0.30), Color(red: 1.0, green: 0.58, blue: 0.22)],
                startPoint: .top, endPoint: .bottom)))
            .accessibilityHidden(true)
    }
}

/// Rounded square with a symbol, the "illustration" of a step.
struct SymbolTile: View {
    let symbol: String
    var size: CGFloat = 44
    var tint: AnyShapeStyle = AnyShapeStyle(Theme.Solar.gradient)
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.46, weight: .semibold))
            .symbolRenderingMode(.monochrome)
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: size * 0.3, style: .continuous).fill(AppPalette(scheme).fill))
            .accessibilityHidden(true)
    }
}

/// Friendly inline card for a source that failed or is not set up.
struct StateCard: View {
    let symbol: String
    let title: Text
    let message: Text
    var actions: [StateAction] = []
    @Environment(\.colorScheme) private var scheme

    struct StateAction: Identifiable {
        let id = UUID()
        let label: LocalizedStringKey
        var prominent = false
        let run: @MainActor () -> Void
    }

    var body: some View {
        let palette = AppPalette(scheme)
        VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            HStack(alignment: .top, spacing: Theme.Spacing.m) {
                Image(systemName: symbol)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(palette.warning)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 3) {
                    title.font(.system(.headline, design: .rounded))
                    message
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if !actions.isEmpty {
                HStack(spacing: Theme.Spacing.s) {
                    ForEach(actions) { action in
                        if action.prominent {
                            Button { action.run() } label: { atext(action.label) }
                                .buttonStyle(PrimaryButtonStyle(compact: true))
                        } else {
                            Button { action.run() } label: { atext(action.label) }
                                .buttonStyle(SoftButtonStyle(compact: true))
                        }
                    }
                }
            }
        }
        .appCard()
    }
}

/// Region name as `Text`, so it follows the environment locale (`PriceRegion.displayName` is a `String` in the process language).
func regionName(_ region: PriceRegion) -> Text {
    let key: String
    switch region {
    case .spain: key = "Spain"
    case .portugal: key = "Portugal"
    case .france: key = "France"
    case .germany: key = "Germany & Luxembourg"
    case .austria: key = "Austria"
    case .belgium: key = "Belgium"
    case .netherlands: key = "Netherlands"
    case .italyNorth: key = "Italy (North)"
    }
    return Text(LocalizedStringKey(key), bundle: .module)
}

/// Tariff name as `Text`: "PVPC" in Spain, "Day-ahead price" elsewhere.
func tariffName(_ region: PriceRegion) -> Text {
    region.isRetailTariff ? Text(verbatim: "PVPC") : Text(LocalizedStringKey("Day-ahead price"), bundle: .module)
}

/// "Regulated PVPC price" / "Wholesale day-ahead price" note for a region.
func tariffNote(_ region: PriceRegion) -> Text {
    region.isRetailTariff ? atext("Regulated PVPC price") : atext("Wholesale day-ahead price")
}

/// Source line: "Prices: Red Eléctrica" or "Prices: Energy-Charts (Fraunhofer ISE), CC BY 4.0".
func sourceAttribution(_ region: PriceRegion) -> Text {
    region.isRetailTariff
        ? atext("Prices: \(region.sourceName)")
        : atext("Prices: \(region.sourceName), CC BY 4.0")
}
#endif
