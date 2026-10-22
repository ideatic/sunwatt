import SwiftUI

/// A headline figure: the number big, bold and monospaced (SF Rounded), the unit smaller and lighter.
/// Example: "4,8 kW". The text color is inherited; the unit is always secondary.
public struct FigureText: View {
    public let value: String
    public let unit: String?
    public let size: CGFloat

    public init(value: String, unit: String? = nil, size: CGFloat) {
        self.value = value
        self.unit = unit
        self.size = size
    }

    public var body: some View {
        Group {
            if let unit {
                Text("\(number)\u{2009}\(unitText(unit))")
            } else {
                number
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.55)
        .contentTransition(.numericText())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: unit.map { "\(value) \($0)" } ?? value))
    }

    private var number: Text {
        Text(verbatim: value).font(Theme.Font.figure(size))
    }

    private func unitText(_ unit: String) -> Text {
        Text(verbatim: unit)
            .font(.system(size: max(10, size * 0.42), weight: .medium, design: .rounded))
            .foregroundStyle(.secondary)
    }
}
