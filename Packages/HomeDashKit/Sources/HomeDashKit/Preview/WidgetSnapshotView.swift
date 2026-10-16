import SwiftUI
import WidgetKit

/// Draws a widget the way the system would (rounded container, content margins, wallpaper) so it can be rendered
/// with `ImageRenderer` or shown in app previews. `containerBackground(for: .widget)` is a no-op outside WidgetKit,
/// so this view draws the background itself.
public struct WidgetSnapshotView: View {
    public let entry: HomeDashEntry
    public let family: HomeDashFamily
    public let content: WidgetContent
    public let window: PriceWindow
    public let scheme: ColorScheme
    public let renderingMode: WidgetRenderingMode
    public let locale: Locale

    public init(
        entry: HomeDashEntry,
        family: HomeDashFamily,
        content: WidgetContent = .both,
        window: PriceWindow = .wholeDay,
        scheme: ColorScheme = .light,
        renderingMode: WidgetRenderingMode = .fullColor,
        locale: Locale = Format.locale
    ) {
        self.entry = entry
        self.family = family
        self.content = content
        self.window = window
        self.scheme = scheme
        self.renderingMode = renderingMode
        self.locale = locale
    }

    /// Space around the widget in the rendered image.
    public static func canvasPadding(for family: HomeDashFamily) -> CGFloat {
        family.isAccessory ? 14 : 24
    }

    /// Size of the whole rendered image in points.
    public static func imageSize(for family: HomeDashFamily) -> CGSize {
        let pad = canvasPadding(for: family) * 2
        return CGSize(width: family.size.width + pad, height: family.size.height + pad)
    }

    public var body: some View {
        let pad = Self.canvasPadding(for: family)
        let inner = HomeDashWidgetView(entry: entry, family: family, content: content, window: window)
            .padding(family.contentInset)
            .frame(width: family.size.width, height: family.size.height)
        Group {
            if family.isAccessory {
                accessoryFrame(inner)
            } else {
                inner
                    .background(containerBackground)
                    .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.widget, style: .continuous))
                    .shadow(color: .black.opacity(scheme == .dark ? 0.5 : 0.12), radius: 10, y: 4)
            }
        }
        .padding(pad)
        .frame(width: Self.imageSize(for: family).width, height: Self.imageSize(for: family).height)
        .background(canvas)
        .environment(\.colorScheme, family.isAccessory ? .dark : scheme)
        .environment(\.widgetRenderingMode, renderingMode)
        .environment(\.locale, locale)
    }

    @ViewBuilder
    private var containerBackground: some View {
        if renderingMode == .fullColor {
            WidgetBackground()
        } else {
            // The system replaces the background with a tinted or clear one in these modes.
            Rectangle().fill(scheme == .dark ? Color(white: 0.16) : Color(white: 0.30))
        }
    }

    @ViewBuilder
    private func accessoryFrame(_ inner: some View) -> some View {
        let shape = family == .accessoryCircular
            ? AnyShape(Circle())
            : AnyShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        inner
            .foregroundStyle(.white)
            .background(shape.fill(Color.white.opacity(0.18)))
            .clipShape(shape)
    }

    private var canvas: some View {
        let colors: [Color] = {
            if family.isAccessory {
                return scheme == .dark
                    ? [Color(red: 0.10, green: 0.12, blue: 0.24), Color(red: 0.30, green: 0.18, blue: 0.35)]
                    : [Color(red: 0.36, green: 0.55, blue: 0.78), Color(red: 0.88, green: 0.62, blue: 0.52)]
            }
            return scheme == .dark
                ? [Color(white: 0.02), Color(white: 0.07)]
                : [Color(white: 0.84), Color(white: 0.93)]
        }()
        return LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}
