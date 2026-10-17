import SwiftUI

/// Chrome shared by the sheets (settings, widget guide, kiosk help): title, "Done", scrolling content on the
/// app background. Drawn by the app so it looks the same on both platforms and renders in snapshots.
struct SheetContainer<Content: View>: View {
    let title: LocalizedStringKey
    var onDone: (() -> Void)?
    @ViewBuilder var content: Content

    @Environment(\.appIdiom) private var idiom
    @Environment(\.appSnapshot) private var snapshot
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = AppPalette(scheme)
        VStack(spacing: 0) {
            if onDone != nil || idiom == .phone {
            HStack {
                atext(title)
                    .font(.system(.title3, design: .rounded).weight(.bold))
                    .lineLimit(1)
                Spacer()
                if let onDone {
                    Button(action: onDone) { atext("Done") }
                        .buttonStyle(SoftButtonStyle(compact: true))
                        .keyboardShortcut(.defaultAction)
                }
            }
            .padding(.horizontal, Theme.Spacing.l + 4)
            .padding(.top, idiom == .phone ? Theme.Spacing.xl : Theme.Spacing.l)
            .padding(.bottom, Theme.Spacing.m)
            Rectangle().fill(palette.hairline).frame(height: 0.5)
            }
            if snapshot {
                inner
            } else {
                ScrollView { inner }
            }
        }
        .background(AppBackground())
    }

    private var inner: some View {
        content
            .padding(Theme.Spacing.l + 2)
            .frame(maxWidth: idiom == .desktop ? 640 : 720)
            .frame(maxWidth: .infinity)
    }
}
