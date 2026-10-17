import SwiftUI
import WidgetKit

/// Widget families SunWatt supports. Package-level mirror of `WidgetFamily` so views and the snapshot harness
/// do not depend on `@Environment(\.widgetFamily)`.
public enum SunWattFamily: String, CaseIterable, Sendable {
    case small
    case medium
    case large
    case accessoryRectangular
    case accessoryCircular

    /// Reference size in points (iPhone home screen and lock screen).
    public var size: CGSize {
        switch self {
        case .small: CGSize(width: 170, height: 170)
        case .medium: CGSize(width: 364, height: 170)
        case .large: CGSize(width: 364, height: 382)
        case .accessoryRectangular: CGSize(width: 172, height: 76)
        case .accessoryCircular: CGSize(width: 76, height: 76)
        }
    }

    public var isAccessory: Bool {
        self == .accessoryRectangular || self == .accessoryCircular
    }

    /// Default system content margin. WidgetKit applies it in the extension; the snapshot frame applies it in snapshots.
    public var contentInset: CGFloat {
        switch self {
        case .small, .medium, .large: Theme.Spacing.l
        case .accessoryRectangular: 6
        case .accessoryCircular: 4
        }
    }

    /// Maps a WidgetKit family; nil for families SunWatt does not support.
    public init?(_ family: WidgetFamily) {
        switch family {
        case .systemSmall: self = .small
        case .systemMedium: self = .medium
        case .systemLarge: self = .large
        #if os(iOS)
        case .accessoryRectangular: self = .accessoryRectangular
        case .accessoryCircular: self = .accessoryCircular
        #endif
        default: return nil
        }
    }
}
