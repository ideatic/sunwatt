#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI

/// Provider picker + the editor of the selected provider + its help. Used by settings and onboarding.
struct SolarSetupEditor: View {
    let model: AppModel
    /// The plant edited (`SolarPlant.id`); the first one by default.
    var plantID = SolarPlant.primaryID
    var initialTest: KioskTestState = .idle
    /// Settings shows the optional "Live data at home" section; onboarding keeps the first setup short.
    var showsInverter = true

    @Environment(\.openURL) private var openURL
    @Environment(\.colorScheme) private var scheme
    @Environment(\.appSheetPresenter) private var presenter

    var body: some View {
        let provider = model.settings(focusedOn: plantID).solarProvider
        VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            SolarProviderPicker(selection: providerBinding)
            if provider.pickerGroup == .fusionSolar {
                FusionSolarConnectionPicker(selection: providerBinding)
            }
            switch provider.profile.setup {
            case .kioskLink:
                KioskLinkEditor(model: model, plantID: plantID, initialTest: initialTest)
                hairline
                Button { present(.kioskHelp) } label: {
                    Label { atext("How do I get the kiosk link?") } icon: { Image(systemName: "questionmark.circle") }
                }
                .buttonStyle(SoftButtonStyle())
                if showsInverter {
                    hairline
                    InverterLinkEditor(model: model, plantID: plantID)
                }
            case .northboundAccount:
                NorthboundAccountEditor(model: model, plantID: plantID, initialTest: initialTest)
                hairline
                atext("Your installer creates an API account in FusionSolar (Company Management → Northbound Management) and gives you its user name and password (system code). Unlike a kiosk link it does not expire. The server is the one in your FusionSolar address, for example eu5.fusionsolar.huawei.com.")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if showsInverter {
                    hairline
                    InverterLinkEditor(model: model, plantID: plantID)
                        .id(provider)
                }
            case .apiKey:
                SolarAPIEditor(model: model, provider: provider, plantID: plantID, initialTest: initialTest)
                    .id(provider)
                hairline
                help(provider)
                if showsInverter {
                    hairline
                    InverterLinkEditor(model: model, plantID: plantID)
                        .id(provider)
                }
            }
        }
    }

    /// Choosing SolarEdge or PVOutput after another provider clears the ID and key: they never work on the other
    /// service, and the fields do not record which one they belong to (also when switching through FusionSolar).
    private var providerBinding: Binding<SolarProvider> {
        Binding(
            get: { model.settings(focusedOn: plantID).solarProvider },
            set: { new in
                model.updatePlant(plantID) { settings in
                    if settings.solarProvider != new, new.usesCredentialFields {
                        settings.solarSiteID = ""
                        settings.solarAPIKey = ""
                    }
                    settings.solarProvider = new
                }
            })
    }

    private func help(_ provider: SolarProvider) -> some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.s) {
            apiHelp(provider)
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if let url = provider.portalURL {
                Button { openURL(url) } label: {
                    Label { Text(verbatim: provider.displayName) } icon: { Image(systemName: "arrow.up.forward.app") }
                }
                .buttonStyle(SoftButtonStyle())
            }
        }
    }

    /// Where the ID and key are found, per provider (exhaustive: a new provider needs its own text).
    private func apiHelp(_ provider: SolarProvider) -> Text {
        switch provider {
        case .fusionSolar, .fusionSolarAPI: Text(verbatim: "")
        case .solarEdge: atext("In the SolarEdge monitoring portal open Admin, then Site Access and API Access. Accept the terms, then copy the API key and the site ID.")
        case .pvOutput: atext("On pvoutput.org open Settings. Turn on API Access, then copy the API Key and the System Id shown on that page.")
        }
    }

    private func present(_ sheet: AppSheet) {
        if let presenter { presenter(sheet) } else { model.present(sheet) }
    }

    private var hairline: some View {
        Rectangle().fill(AppPalette(scheme).hairline).frame(height: 0.5)
    }
}

/// Three buttons side by side: FusionSolar, SolarEdge, PVOutput.
struct SolarProviderPicker: View {
    @Binding var selection: SolarProvider
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = AppPalette(scheme)
        VStack(alignment: .leading, spacing: 6) {
            atext("Solar system")
                .font(.system(.footnote, design: .rounded).weight(.bold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, Theme.Spacing.xs)
            HStack(spacing: 6) {
                ForEach(SolarProvider.pickerCases) { provider in
                    let isSelected = provider == selection.pickerGroup
                    Button { if !isSelected { selection = provider } } label: {
                        Text(verbatim: provider.shortName)
                            .font(.system(.footnote, design: .rounded).weight(isSelected ? .bold : .medium))
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                            .background(
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .fill(isSelected ? Theme.Solar.amber.opacity(palette.isDark ? 0.22 : 0.24) : palette.fill))
                            .overlay(
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .strokeBorder(isSelected ? Theme.Solar.amber : palette.hairline, lineWidth: isSelected ? 1 : 0.5))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(verbatim: provider.displayName))
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                }
            }
        }
    }
}

/// Site/system ID and API key fields with validation and "Test connection" (SolarEdge, PVOutput).
/// The text is saved when the user submits, leaves a field or the view goes away, and 0.8 s after the last
/// keystroke when both values are valid; never per keystroke. Unsaved text is dropped when the provider changes.
struct SolarAPIEditor: View {
    let model: AppModel
    let provider: SolarProvider
    let plantID: String
    @State private var siteID: String
    @State private var apiKey: String
    @State private var test: KioskTestState
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase

    init(model: AppModel, provider: SolarProvider, plantID: String = SolarPlant.primaryID, initialTest: KioskTestState = .idle) {
        self.model = model
        self.provider = provider
        self.plantID = plantID
        let settings = model.settings(focusedOn: plantID)
        _siteID = State(initialValue: settings.solarSiteID)
        _apiKey = State(initialValue: settings.solarAPIKey)
        _test = State(initialValue: initialTest)
    }

    private enum Input { case empty, valid, invalid }

    private var input: Input {
        let id = siteID.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if id.isEmpty && key.isEmpty { return .empty }
        return SolarAPICredentials(siteID: id, apiKey: key) != nil ? .valid : .invalid
    }

    var body: some View {
        let palette = AppPalette(scheme)
        let input = input
        VStack(alignment: .leading, spacing: Theme.Spacing.s) {
            fieldLabel(idLabel)
            AppTextField(prompt: "123456", text: $siteID, onCommit: commit)
            fieldLabel("API key")
            AppTextField(prompt: keyPrompt, text: $apiKey, onCommit: commit, isSecret: true)
            validationRow(input, palette: palette)
            HStack(spacing: Theme.Spacing.s) {
                Button { runTest() } label: {
                    Label { atext("Test connection") } icon: { Image(systemName: "bolt.horizontal.circle") }
                }
                .buttonStyle(SoftButtonStyle(compact: true))
                .disabled(input != .valid)
                .opacity(input != .valid ? 0.5 : 1)
                Spacer(minLength: 0)
            }
            .padding(.top, Theme.Spacing.xs)
            SolarTestResult(state: test)
        }
        .onChange(of: siteID) { _, _ in test = .idle }
        .onChange(of: apiKey) { _, _ in test = .idle }
        // Valid input is saved shortly after the last keystroke (like the kiosk link), so the onboarding button
        // stops offering "Skip, prices only" and the widgets get the account without leaving the field.
        .task(id: "\(siteID)\n\(apiKey)") {
            guard self.input == .valid else { return }
            do { try await Task.sleep(for: KioskURLDraft.defaultDebounce) } catch { return }
            commit()
        }
        .onDisappear { commit() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { commit() }
        }
    }

    /// Field label and key example per provider (exhaustive: a new provider needs its own).
    private var idLabel: LocalizedStringKey {
        switch provider {
        case .fusionSolar, .fusionSolarAPI, .pvOutput: "System ID"
        case .solarEdge: "Site ID"
        }
    }

    private var keyPrompt: String {
        switch provider {
        case .fusionSolar, .fusionSolarAPI, .pvOutput: "a1b2c3d4…"
        case .solarEdge: "ABCD1234…"
        }
    }

    private func fieldLabel(_ key: LocalizedStringKey) -> some View {
        atext(key)
            .font(.system(.footnote, design: .rounded).weight(.semibold))
            .padding(.horizontal, Theme.Spacing.xs)
    }

    private func validationRow(_ input: Input, palette: AppPalette) -> some View {
        HStack(alignment: .top, spacing: 6) {
            switch input {
            case .empty:
                Image(systemName: "key").foregroundStyle(.secondary)
                atext("Enter the ID and the API key.").foregroundStyle(.secondary)
            case .valid:
                Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.success)
                atext("Ready. Test the connection to check it.").foregroundStyle(palette.success)
            case .invalid:
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(palette.warning)
                Text(verbatim: SunWattError.invalidSolarCredentials.localizedDescription).foregroundStyle(palette.warning)
            }
        }
        .font(.system(.footnote, design: .rounded).weight(.medium))
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, Theme.Spacing.xs)
    }

    /// Saves both fields at once, only while this provider is still the selected one (switching providers
    /// clears the fields; the editor that goes away must not write its old text back).
    private func commit() {
        let saved = model.settings(focusedOn: plantID)
        guard saved.solarProvider == provider else { return }
        let id = siteID.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard id != saved.solarSiteID || key != saved.solarAPIKey else { return }
        model.updatePlant(plantID) {
            $0.solarSiteID = id
            $0.solarAPIKey = key
        }
    }

    private func runTest() {
        commit()
        test = .testing
        let provider = provider, id = siteID, key = apiKey
        Task {
            switch await model.testSolarAPI(provider: provider, siteID: id, apiKey: key) {
            case .success(let solar): test = .success(solar)
            case .failure(let error): test = .failure(error.localizedDescription)
            }
        }
    }
}

/// "Live data at home": the address of the inverter on the home network (Modbus TCP), optional, with a network search
/// ("Find inverter") and a test. FusionSolar: the Huawei dongle; SolarEdge and PVOutput: a SunSpec inverter.
/// Saved when the user submits, leaves the field or the view goes away.
struct InverterLinkEditor: View {
    let model: AppModel
    let plantID: String
    @State private var host: String
    @State private var test: InverterTestState = .idle
    @State private var search: SearchState = .idle
    /// Address the shown result belongs to; editing the field to anything else clears the result.
    @State private var resultHost: String?
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase

    enum InverterTestState {
        case idle, testing
        case success(InverterFinder.Match)
        case failure(String)
    }

    enum SearchState {
        case idle, searching, none
        case several([InverterFinder.Match])
    }

    init(model: AppModel, plantID: String = SolarPlant.primaryID) {
        self.model = model
        self.plantID = plantID
        _host = State(initialValue: model.settings(focusedOn: plantID)[keyPath: AppModel.hostKey(model.localInverterKind(plant: plantID))])
    }

    private var kind: LocalInverterKind { model.localInverterKind(plant: plantID) }
    private var isHuawei: Bool { kind == .huawei }

    var body: some View {
        let palette = AppPalette(scheme)
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let valid = ModbusEndpoint(parsing: trimmed) != nil
        VStack(alignment: .leading, spacing: Theme.Spacing.s) {
            Label { atext("Live data at home") } icon: { Image(systemName: "house.fill") }
                .font(.system(.subheadline, design: .rounded).weight(.bold))
            Group {
                if isHuawei {
                    atext("Optional. If Modbus TCP is set to “Enable (unrestricted)” in the Huawei dongle, SunWatt reads the inverter directly while you are at home, every few seconds. Elsewhere it uses the kiosk link.")
                } else {
                    atext("Optional. If your inverter offers SunSpec over Modbus TCP (SolarEdge, SMA, Fronius and other brands; it may have to be turned on, for example by your installer), SunWatt reads it directly while you are at home, every few seconds. Elsewhere it uses \(model.settings(focusedOn: plantID).solarProvider.displayName).")
                }
            }
            .font(.system(.footnote, design: .rounded))
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            Group {
                if isHuawei { atext("Dongle IP address") } else { atext("Inverter IP address") }
            }
            .font(.system(.footnote, design: .rounded).weight(.semibold))
            .padding(.horizontal, Theme.Spacing.xs)
            AppTextField(prompt: isHuawei ? "192.168.1.50" : "192.168.1.40", text: $host, onCommit: commit)
            if !trimmed.isEmpty, !valid {
                HStack(alignment: .top, spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill")
                    Text(verbatim: SunWattError.invalidInverterAddress.localizedDescription)
                }
                .foregroundStyle(palette.warning)
                .font(.system(.footnote, design: .rounded).weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, Theme.Spacing.xs)
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Theme.Spacing.s) { buttons(valid: valid) }
                VStack(alignment: .leading, spacing: Theme.Spacing.s) { buttons(valid: valid) }
            }
            searchResult(palette: palette)
            result(palette: palette)
        }
        .onChange(of: host) { _, value in
            if value.trimmingCharacters(in: .whitespacesAndNewlines) != resultHost { test = .idle }
        }
        .onDisappear { commit() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { commit() }
        }
    }

    @ViewBuilder
    private func buttons(valid: Bool) -> some View {
        let busy: Bool = {
            if case .searching = search { return true }
            if case .testing = test { return true }
            return false
        }()
        Button { runSearch() } label: {
            Label { atext("Find inverter") } icon: { Image(systemName: "magnifyingglass") }
        }
        .buttonStyle(SoftButtonStyle(compact: true))
        .disabled(busy)
        .opacity(busy ? 0.5 : 1)
        Button { runTest() } label: {
            Label { atext("Test inverter") } icon: { Image(systemName: "bolt.horizontal.circle") }
        }
        .buttonStyle(SoftButtonStyle(compact: true))
        .disabled(!valid || busy)
        .opacity(valid && !busy ? 1 : 0.5)
    }

    @ViewBuilder
    private func searchResult(palette: AppPalette) -> some View {
        switch search {
        case .idle:
            EmptyView()
        case .searching:
            HStack(spacing: Theme.Spacing.s) {
                ProgressView().controlSize(.small)
                atext("Searching the home network…").font(.system(.footnote, design: .rounded)).foregroundStyle(.secondary)
            }
        case .none:
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(palette.warning)
                VStack(alignment: .leading, spacing: 2) {
                    atext("No inverter found").font(.system(.subheadline, design: .rounded).weight(.bold))
                    Group {
                        if isHuawei {
                            atext("Check that this device is on the home Wi-Fi, that Modbus TCP is enabled in the dongle and that SunWatt may use the local network (Settings → Privacy & Security → Local Network).")
                        } else {
                            atext("Check that this device is on the home Wi-Fi, that SunSpec over Modbus TCP is enabled in the inverter and that SunWatt may use the local network (Settings → Privacy & Security → Local Network).")
                        }
                    }
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(Theme.Spacing.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(palette.warning.opacity(0.12)))
        case .several(let matches):
            VStack(alignment: .leading, spacing: Theme.Spacing.s) {
                atext("Several inverters answered. Choose yours:")
                    .font(.system(.footnote, design: .rounded).weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                ForEach(matches, id: \.address) { match in
                    Button { choose(match) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: match.model.map { "\($0) · \(match.address)" } ?? match.address)
                                .font(.system(.subheadline, design: .rounded).weight(.semibold))
                            caption(match.reading)
                                .font(.system(.footnote, design: .rounded))
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(SoftButtonStyle(compact: true))
                }
            }
        }
    }

    @ViewBuilder
    private func result(palette: AppPalette) -> some View {
        switch test {
        case .idle:
            EmptyView()
        case .testing:
            HStack(spacing: Theme.Spacing.s) {
                ProgressView().controlSize(.small)
                atext("Connecting…").font(.system(.footnote, design: .rounded)).foregroundStyle(.secondary)
            }
        case .success(let match):
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.success)
                VStack(alignment: .leading, spacing: 2) {
                    atext("Inverter found").font(.system(.subheadline, design: .rounded).weight(.bold))
                    if let model = match.model {
                        Text(verbatim: model).font(.system(.footnote, design: .rounded).weight(.semibold))
                    }
                    caption(match.reading)
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
            }
            .padding(Theme.Spacing.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(palette.success.opacity(0.12)))
        case .failure(let message):
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "xmark.octagon.fill").foregroundStyle(palette.warning)
                VStack(alignment: .leading, spacing: 2) {
                    atext("Connection failed").font(.system(.subheadline, design: .rounded).weight(.bold))
                    Text(verbatim: message)
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(Theme.Spacing.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(palette.warning.opacity(0.12)))
        }
    }

    /// Power now, and today's energy when the inverter counts it (SunSpec inverters only have a lifetime counter).
    private func caption(_ reading: LiveInverterReading) -> Text {
        let power = Format.kW(max(reading.activePowerKW, 0))
        if let daily = reading.dailyEnergyKWh {
            return atext("\(power) kW now · \(Format.kWh(daily)) kWh today")
        }
        return atext("\(power) kW now")
    }

    private func commit() {
        let value = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = AppModel.hostKey(kind)
        guard value != model.settings(focusedOn: plantID)[keyPath: key] else { return }
        model.updatePlant(plantID) { $0[keyPath: key] = value }
    }

    private func runSearch() {
        search = .searching
        test = .idle
        Task {
            let matches = await model.findInverters(plant: plantID)
            switch matches.count {
            case 0: search = .none
            case 1: choose(matches[0])
            default: search = .several(matches)
            }
        }
    }

    /// Fills in and saves the found address and unit ID, and shows its reading as the test result.
    private func choose(_ match: InverterFinder.Match) {
        search = .idle
        resultHost = match.address
        host = match.address
        commit()
        let key = AppModel.unitKey(kind)
        if match.unitID != model.settings(focusedOn: plantID)[keyPath: key] { model.updatePlant(plantID) { $0[keyPath: key] = match.unitID } }
        test = .success(match)
    }

    private func runTest() {
        commit()
        search = .idle
        test = .testing
        let value = host
        resultHost = value.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            switch await model.testInverter(value, plant: plantID) {
            case .success(let match):
                // Saves the port that answered too (SunSpec without a port: 502 or 1502).
                choose(match)
            case .failure(let error):
                test = .failure(error.localizedDescription)
            }
        }
    }
}

/// Result of "Test connection", shared by the kiosk and the API editors.
struct SolarTestResult: View {
    let state: KioskTestState
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = AppPalette(scheme)
        switch state {
        case .idle:
            EmptyView()
        case .testing:
            HStack(spacing: Theme.Spacing.s) {
                ProgressView().controlSize(.small)
                atext("Connecting…").font(.system(.footnote, design: .rounded)).foregroundStyle(.secondary)
            }
        case .success(let solar):
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.success)
                    atext("Connected").font(.system(.subheadline, design: .rounded).weight(.bold))
                }
                if let name = solar.stationName {
                    resultRow("Plant", name)
                }
                resultRow("Power now", "\(Format.kW(solar.currentPowerKW)) kW")
                resultRow("Energy today", "\(Format.kWh(solar.dailyEnergyKWh)) kWh")
                resultRow("Data as of", solar.dataAsOf.map { Format.time($0) } ?? "–")
            }
            .padding(Theme.Spacing.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(palette.success.opacity(0.12)))
        case .failure(let message):
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "xmark.octagon.fill").foregroundStyle(palette.warning)
                VStack(alignment: .leading, spacing: 2) {
                    atext("Connection failed").font(.system(.subheadline, design: .rounded).weight(.bold))
                    Text(verbatim: message)
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(Theme.Spacing.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(palette.warning.opacity(0.12)))
        }
    }

    private func resultRow(_ title: LocalizedStringKey, _ value: String) -> some View {
        HStack {
            atext(title).foregroundStyle(.secondary)
            Spacer(minLength: Theme.Spacing.s)
            Text(verbatim: value).fontWeight(.semibold).monospacedDigit().lineLimit(1)
        }
        .font(.system(.footnote, design: .rounded))
    }
}
/// Several plants: one button per plant, the selected one filled. Scrolls sideways when the names do not fit.
struct PlantTabs: View {
    struct Tab: Identifiable {
        let id: String
        let name: String
    }

    let plants: [Tab]
    @Binding var selection: String
    @Environment(\.colorScheme) private var scheme
    @Environment(\.appSnapshot) private var snapshot

    var body: some View {
        let palette = AppPalette(scheme)
        // ImageRenderer draws no scroll views: snapshots lay the tabs out in a plain row.
        let row = HStack(spacing: Theme.Spacing.s) {
                ForEach(plants) { plant in
                    let isSelected = plant.id == selection
                    Button { selection = plant.id } label: {
                        Text(verbatim: plant.name)
                            .font(.system(.subheadline, design: .rounded).weight(isSelected ? .bold : .medium))
                            .lineLimit(1)
                            .padding(.horizontal, Theme.Spacing.m)
                            .padding(.vertical, 7)
                            .foregroundStyle(isSelected ? AnyShapeStyle(palette.accentText) : AnyShapeStyle(.secondary))
                            .background(Capsule().fill(isSelected ? palette.accentText.opacity(0.14) : palette.hairline.opacity(0.5)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                }
            }
        if snapshot {
            row.frame(maxWidth: .infinity, alignment: .leading).clipped()
        } else {
            ScrollView(.horizontal) { row }.scrollIndicators(.hidden)
        }
    }
}

/// The name of a plant (several plants). Saved when the user submits, leaves the field or the view goes away.
struct PlantNameEditor: View {
    let model: AppModel
    let plantID: String
    @State private var name: String
    @Environment(\.scenePhase) private var scenePhase

    init(model: AppModel, plantID: String) {
        self.model = model
        self.plantID = plantID
        _name = State(initialValue: model.settings.plant(id: plantID)?.name ?? "")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.s) {
            atext("Plant name")
                .font(.system(.footnote, design: .rounded).weight(.semibold))
                .padding(.horizontal, Theme.Spacing.xs)
            AppTextField(prompt: astring("For example, Home"), text: $name, onCommit: commit)
        }
        .onDisappear { commit() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { commit() }
        }
    }

    private func commit() {
        guard var plant = model.settings.plant(id: plantID) else { return }
        let value = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value != plant.name else { return }
        plant.name = value
        model.updateSettings { $0.updatePlant(plant) }
    }
}

/// FusionSolar: "Kiosk link" or "API account" (Northbound API), two ways to connect the same service.
struct FusionSolarConnectionPicker: View {
    @Binding var selection: SolarProvider
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = AppPalette(scheme)
        HStack(spacing: 4) {
            option(.fusionSolar, palette: palette) { atext("Kiosk link") }
            option(.fusionSolarAPI, palette: palette) { atext("API account") }
        }
        .padding(3)
        .background(Capsule().fill(palette.fill))
    }

    private func option(_ provider: SolarProvider, palette: AppPalette, label: () -> Text) -> some View {
        let isSelected = selection == provider
        return Button { selection = provider } label: {
            label()
                .font(.system(.footnote, design: .rounded).weight(isSelected ? .bold : .medium))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 7)
                .background(Capsule().fill(isSelected ? palette.card : Color.clear))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// FusionSolar API account: server, user name and system code, with a connection test. Saved like the API editor.
struct NorthboundAccountEditor: View {
    let model: AppModel
    let plantID: String
    @State private var host: String
    @State private var userName: String
    @State private var systemCode: String
    @State private var test: KioskTestState
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase

    init(model: AppModel, plantID: String = SolarPlant.primaryID, initialTest: KioskTestState = .idle) {
        self.model = model
        self.plantID = plantID
        let settings = model.settings(focusedOn: plantID)
        _host = State(initialValue: settings.northboundHost)
        _userName = State(initialValue: settings.solarSiteID)
        _systemCode = State(initialValue: settings.solarAPIKey)
        _test = State(initialValue: initialTest)
    }

    private enum Input { case empty, valid, invalid }

    private var input: Input {
        let user = userName.trimmingCharacters(in: .whitespacesAndNewlines)
        let code = systemCode.trimmingCharacters(in: .whitespacesAndNewlines)
        if user.isEmpty && code.isEmpty { return .empty }
        return NorthboundCredentials(host: host, userName: user, systemCode: code) != nil ? .valid : .invalid
    }

    var body: some View {
        let palette = AppPalette(scheme)
        let input = input
        VStack(alignment: .leading, spacing: Theme.Spacing.s) {
            fieldLabel("User name")
            AppTextField(prompt: "api_user", text: $userName, onCommit: commit)
            fieldLabel("Password (system code)")
            AppTextField(prompt: "••••••••", text: $systemCode, onCommit: commit, isSecret: true)
            fieldLabel("Server")
            AppTextField(prompt: NorthboundCredentials.defaultHost, text: $host, onCommit: commit)
            validationRow(input, palette: palette)
            HStack(spacing: Theme.Spacing.s) {
                Button { runTest() } label: {
                    Label { atext("Test connection") } icon: { Image(systemName: "bolt.horizontal.circle") }
                }
                .buttonStyle(SoftButtonStyle(compact: true))
                .disabled(input != .valid)
                .opacity(input != .valid ? 0.5 : 1)
                Spacer(minLength: 0)
            }
            .padding(.top, Theme.Spacing.xs)
            SolarTestResult(state: test)
        }
        .onChange(of: "\(host)\n\(userName)\n\(systemCode)") { _, _ in test = .idle }
        .task(id: "\(host)\n\(userName)\n\(systemCode)") {
            guard self.input == .valid else { return }
            do { try await Task.sleep(for: KioskURLDraft.defaultDebounce) } catch { return }
            commit()
        }
        .onDisappear { commit() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { commit() }
        }
    }

    private func fieldLabel(_ key: LocalizedStringKey) -> some View {
        atext(key)
            .font(.system(.footnote, design: .rounded).weight(.semibold))
            .padding(.horizontal, Theme.Spacing.xs)
    }

    private func validationRow(_ input: Input, palette: AppPalette) -> some View {
        HStack(alignment: .top, spacing: 6) {
            switch input {
            case .empty:
                Image(systemName: "key").foregroundStyle(.secondary)
                atext("Enter the user name and the password.").foregroundStyle(.secondary)
            case .valid:
                Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.success)
                atext("Ready. Test the connection to check it.").foregroundStyle(palette.success)
            case .invalid:
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(palette.warning)
                Text(verbatim: SunWattError.invalidNorthboundAccount.localizedDescription).foregroundStyle(palette.warning)
            }
        }
        .font(.system(.footnote, design: .rounded).weight(.medium))
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, Theme.Spacing.xs)
    }

    /// Saves the three fields at once, only while this is still the plant's provider.
    private func commit() {
        let saved = model.settings(focusedOn: plantID)
        guard saved.solarProvider == .fusionSolarAPI else { return }
        let server = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let user = userName.trimmingCharacters(in: .whitespacesAndNewlines)
        let code = systemCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard server != saved.northboundHost || user != saved.solarSiteID || code != saved.solarAPIKey else { return }
        model.updatePlant(plantID) {
            $0.northboundHost = server
            $0.solarSiteID = user
            $0.solarAPIKey = code
        }
    }

    private func runTest() {
        commit()
        test = .testing
        let server = host, user = userName, code = systemCode
        Task {
            switch await model.testNorthbound(host: server, userName: user, systemCode: code) {
            case .success(let solar): test = .success(solar)
            case .failure(let error): test = .failure(error.localizedDescription)
            }
        }
    }
}

#endif
