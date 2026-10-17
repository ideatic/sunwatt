#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Result of "Test connection".
enum KioskTestState {
    case idle
    case testing
    case success(SolarSnapshot)
    case failure(String)
}

/// Country list used by settings and onboarding: flag, name, check mark. Drawn by the app (no `List`/`Picker`).
struct RegionList: View {
    @Binding var selection: PriceRegion
    var showsNotes = false
    @Environment(\.colorScheme) private var scheme
    @Environment(\.appIdiom) private var idiom

    var body: some View {
        let palette = AppPalette(scheme)
        let columns = idiom == .desktop && !showsNotes ? 2 : 1
        let all = PriceRegion.allCases
        let rows = stride(from: 0, to: all.count, by: columns).map { Array(all[$0..<min($0 + columns, all.count)]) }
        VStack(spacing: 2) {
            ForEach(rows.indices, id: \.self) { rowIndex in
              HStack(spacing: 2) {
                ForEach(rows[rowIndex]) { region in
                let isSelected = region == selection
                Button {
                    selection = region
                } label: {
                    HStack(spacing: Theme.Spacing.m) {
                        Text(verbatim: region.flag).font(.system(size: 26))
                        VStack(alignment: .leading, spacing: 0) {
                            regionName(region)
                                .font(.system(.body, design: .rounded).weight(isSelected ? .bold : .medium))
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                            if showsNotes {
                                tariffNote(region)
                                    .font(.system(.caption, design: .rounded))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.8)
                            }
                        }
                        Spacer(minLength: 0)
                        Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 20))
                            .foregroundStyle(isSelected ? AnyShapeStyle(Theme.Solar.gradient) : AnyShapeStyle(palette.hairline))
                    }
                    .padding(.horizontal, Theme.Spacing.m)
                    .padding(.vertical, showsNotes ? 8 : (idiom == .desktop ? 5 : 7))
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(isSelected ? Theme.Solar.amber.opacity(palette.isDark ? 0.16 : 0.18) : Color.clear))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
                }
                if rows[rowIndex].count < columns { Spacer(minLength: 0).frame(maxWidth: .infinity) }
              }
            }
        }
    }
}

/// All settings in cards. Pure content (bindings through the model), so snapshots can render it.
struct SettingsContent: View {
    let model: AppModel
    /// Fixed test state, for snapshots.
    var initialTest: KioskTestState = .idle

    @Environment(\.openURL) private var openURL
    @Environment(\.appIdiom) private var idiom
    @Environment(\.colorScheme) private var scheme
    @Environment(\.appSheetPresenter) private var presenter
    /// The plant whose settings are shown (several plants).
    @State private var editingPlant = SolarPlant.primaryID
    @State private var confirmsRemoval = false

    init(model: AppModel, initialTest: KioskTestState = .idle) {
        self.model = model
        self.initialTest = initialTest
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.l) {
            pricesCard
            solarCard
            widgetsCard
            if idiom != .desktop { languageCard }
            aboutCard
        }
    }

    private func present(_ sheet: AppSheet) {
        if let presenter { presenter(sheet) } else { model.present(sheet) }
    }

    // MARK: Prices

    private var pricesCard: some View {
        let region = model.settings.priceRegion
        return VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            CardHeader(
                symbol: Theme.Price.symbol, tint: AnyShapeStyle(Theme.Price.green),
                title: atext("Electricity prices"), subtitle: atext("Choose your country"))
            RegionList(selection: model.binding(\.priceRegion))
            VStack(alignment: .leading, spacing: 2) {
                tariffNote(region).font(.system(.footnote, design: .rounded).weight(.semibold))
                sourceAttribution(region)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, Theme.Spacing.xs)
            hairline
            AppToggleRow(
                title: "Show tomorrow's prices when available",
                detail: "Published around 20:30 in Spain and 13:00 CET in the other markets.",
                isOn: model.binding(\.showTomorrowPrices))
        }
        .appCard()
    }

    // MARK: Solar

    /// One plant: its settings, plus "Add another plant". Several: a tab per plant, its name, its settings, and
    /// "Remove this plant" for all but the first.
    private var solarCard: some View {
        let plants = model.settings.plants
        let plantID = plants.contains { $0.id == editingPlant } ? editingPlant : SolarPlant.primaryID
        let focused = model.settings(focusedOn: plantID)
        return VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            CardHeader(
                symbol: Theme.Solar.symbol, tint: AnyShapeStyle(Theme.Solar.gradient),
                title: atext("Solar production"), subtitle: Text(verbatim: focused.solarProvider.displayName))
            if plants.count > 1 {
                PlantTabs(plants: plants.map { PlantTabs.Tab(id: $0.id, name: plantName($0)) }, selection: $editingPlant)
                PlantNameEditor(model: model, plantID: plantID)
                    .id(plantID)
            }
            SolarSetupEditor(model: model, plantID: plantID, initialTest: initialTest)
                .id(plantID)
            hairline
            HStack(spacing: Theme.Spacing.s) {
                Button { editingPlant = model.addPlant() } label: {
                    Label { atext("Add another plant") } icon: { Image(systemName: "plus.circle") }
                }
                .buttonStyle(SoftButtonStyle(compact: true))
                if plantID != SolarPlant.primaryID {
                    Button(role: .destructive) { confirmsRemoval = true } label: {
                        Label { atext("Remove this plant") } icon: { Image(systemName: "trash") }
                    }
                    .buttonStyle(SoftButtonStyle(compact: true))
                }
            }
            .confirmationDialog(Text("Remove this plant?", tableName: "App", bundle: .module), isPresented: $confirmsRemoval, titleVisibility: .visible) {
                Button(role: .destructive) {
                    model.removePlant(plantID)
                    editingPlant = SolarPlant.primaryID
                } label: { atext("Remove this plant") }
            } message: {
                atext("Its settings and saved data are removed from this device.")
            }
        }
        .appCard()
    }

    /// The plant's name as everywhere else: the user's, the provider's station name, or the provider.
    private func plantName(_ plant: SolarPlant) -> String {
        let station = model.data?.allPlants.first { $0.id == plant.id }?.solar?.stationName
        return plant.displayName(stationName: station)
    }

    // MARK: Widgets

    private var widgetsCard: some View {
        let palette = AppPalette(scheme)
        return VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            CardHeader(
                symbol: "square.grid.2x2.fill", tint: AnyShapeStyle(Theme.Solar.gradient),
                title: atext("Widgets"))
            if let count = model.widgetCount, count > 0 {
                HStack(spacing: Theme.Spacing.s) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.success)
                    atext("Widget added").font(.system(.subheadline, design: .rounded).weight(.semibold))
                    Spacer(minLength: 0)
                    atext("Active widgets: \(count)")
                        .font(.system(.footnote, design: .rounded)).foregroundStyle(.secondary)
                }
            } else {
                atext("You have not added the widget yet.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            Button { present(.widgetGuide) } label: {
                Label { atext("How to add the widget") } icon: { Image(systemName: "plus.square.on.square") }
            }
            .buttonStyle(SoftButtonStyle())
        }
        .appCard()
    }

    // MARK: Language (iPhone: opens the app page in iOS Settings, where iOS offers per-app language)

    private var languageCard: some View {
        Button {
            #if canImport(UIKit) && !os(watchOS)
            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            #endif
        } label: {
            HStack(spacing: Theme.Spacing.m) {
                SymbolTile(symbol: "globe", size: 36)
                VStack(alignment: .leading, spacing: 1) {
                    atext("Language").font(.system(.body, design: .rounded).weight(.semibold)).foregroundStyle(.primary)
                    atext("Choose the app language in iOS Settings")
                        .font(.system(.footnote, design: .rounded)).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "arrow.up.forward.app").foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .appCard()
    }

    // MARK: About

    private var aboutCard: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            HStack(spacing: Theme.Spacing.m) {
                AppIconView(size: 44, style: .badge)
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: "SunWatt").font(.system(.headline, design: .rounded))
                    atext("Version \(AppVersion.text)").font(.system(.footnote, design: .rounded)).foregroundStyle(.secondary)
                }
            }
            hairline
            VStack(alignment: .leading, spacing: 5) {
                atext("Data sources").font(.system(.footnote, design: .rounded).weight(.bold))
                sourceLine("Red Eléctrica de España", "PVPC")
                sourceLine("Energy-Charts (Fraunhofer ISE)", "CC BY 4.0")
                sourceLine("Huawei FusionSolar", "Kiosk")
                sourceLine("SolarEdge", "Monitoring API")
                sourceLine("PVOutput", "API")
            }
            atext("SunWatt is an independent app. It is not affiliated with Huawei, SolarEdge, PVOutput, Red Eléctrica or Fraunhofer ISE.")
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .appCard()
    }

    private func sourceLine(_ name: String, _ detail: String) -> some View {
        HStack {
            Text(verbatim: name)
            Spacer(minLength: Theme.Spacing.s)
            Text(verbatim: detail).foregroundStyle(.secondary)
        }
        .font(.system(.footnote, design: .rounded))
    }

    private var hairline: some View {
        Rectangle().fill(AppPalette(scheme).hairline).frame(height: 0.5)
    }
}

/// Kiosk link field with validation, paste button and "Test connection". Used by settings and onboarding.
struct KioskLinkEditor: View {
    let model: AppModel
    let plantID: String
    @State private var test: KioskTestState
    /// The field text lives here and is saved to the settings only at the right moments (see `KioskURLDraft`).
    @State private var draft: KioskURLDraft
    @Environment(\.appSnapshot) private var snapshot
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase

    init(model: AppModel, plantID: String = SolarPlant.primaryID, initialTest: KioskTestState = .idle) {
        self.model = model
        self.plantID = plantID
        _test = State(initialValue: initialTest)
        _draft = State(initialValue: KioskURLDraft(saved: model.settings(focusedOn: plantID).kioskURL) { [weak model] value in
            model?.updatePlant(plantID) { $0.kioskURL = value }
        })
    }

    var body: some View {
        let palette = AppPalette(scheme)
        let input = draft.inputState
        VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            AppTextField(prompt: "https://…/kiosk?kk=…", text: draft.binding, onCommit: { draft.commitNow() })
            validationRow(input, palette: palette)
            HStack(spacing: Theme.Spacing.s) {
                pasteButton
                Button { runTest() } label: {
                    Label { atext("Test connection") } icon: { Image(systemName: "bolt.horizontal.circle") }
                }
                .buttonStyle(SoftButtonStyle(compact: true))
                .disabled(input == .empty || input == .invalid)
                .opacity(input == .empty || input == .invalid ? 0.5 : 1)
                Spacer(minLength: 0)
            }
            SolarTestResult(state: test)
        }
        .onDisappear { draft.commitNow() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { draft.commitNow() }
        }
    }

    @ViewBuilder
    private var pasteButton: some View {
        if snapshot {
            Label { atext("Paste") } icon: { Image(systemName: "doc.on.clipboard") }
                .font(.system(.footnote, design: .rounded).weight(.semibold))
                .foregroundStyle(AppPalette(scheme).accentText)
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(AppPalette(scheme).fill))
        } else {
            // The system paste button reads the clipboard only when tapped and never shows the paste prompt.
            PasteButton(payloadType: String.self) { strings in
                guard let text = strings.first else { return }
                draft.paste(text)
                test = .idle
            }
            .labelStyle(.titleAndIcon)
            .buttonBorderShape(.roundedRectangle(radius: 10))
            .tint(AppPalette(scheme).accentText)
        }
    }

    private func validationRow(_ input: KioskInputState, palette: AppPalette) -> some View {
        HStack(alignment: .top, spacing: 6) {
            switch input {
            case .empty:
                Image(systemName: "link").foregroundStyle(.secondary)
                atext("Paste the link you copied from the Kiosk page.").foregroundStyle(.secondary)
            case .valid(let host):
                Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.success)
                atext("Valid kiosk link (\(host))").foregroundStyle(palette.success)
            case .invalid:
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(palette.warning)
                atext("This is not a kiosk link. It must start with https:// and contain kk=.")
                    .foregroundStyle(palette.warning)
            }
        }
        .font(.system(.footnote, design: .rounded).weight(.medium))
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, Theme.Spacing.xs)
    }

    private func runTest() {
        test = .testing
        let text = draft.text
        Task {
            switch await model.testKiosk(text) {
            case .success(let solar): test = .success(solar)
            case .failure(let error): test = .failure(error.localizedDescription)
            }
        }
    }
}

enum AppVersion {
    static var text: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = info?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}

/// Settings sheet (iPhone) or Settings window content (Mac).
struct SettingsScreen: View {
    let model: AppModel
    var onDone: (() -> Void)?

    var body: some View {
        SheetContainer(title: "Settings", onDone: onDone) {
            SettingsContent(model: model)
        }
    }
}
#endif
