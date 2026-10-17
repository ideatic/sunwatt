#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI

/// Provider picker + the editor of the selected provider + its help. Used by settings and onboarding.
struct SolarSetupEditor: View {
    let model: AppModel
    var initialTest: KioskTestState = .idle
    /// Settings shows the optional "Live data at home" section; onboarding keeps the first setup short.
    var showsInverter = true

    @Environment(\.openURL) private var openURL
    @Environment(\.colorScheme) private var scheme
    @Environment(\.appSheetPresenter) private var presenter

    var body: some View {
        let provider = model.settings.solarProvider
        VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            SolarProviderPicker(selection: providerBinding)
            switch provider.profile.setup {
            case .kioskLink:
                KioskLinkEditor(model: model, initialTest: initialTest)
                hairline
                Button { present(.kioskHelp) } label: {
                    Label { atext("How do I get the kiosk link?") } icon: { Image(systemName: "questionmark.circle") }
                }
                .buttonStyle(SoftButtonStyle())
                if showsInverter {
                    hairline
                    InverterLinkEditor(model: model)
                }
            case .apiKey:
                SolarAPIEditor(model: model, provider: provider, initialTest: initialTest)
                    .id(provider)
                hairline
                help(provider)
                if showsInverter {
                    hairline
                    InverterLinkEditor(model: model)
                        .id(provider)
                }
            }
        }
    }

    /// Choosing SolarEdge or PVOutput after another provider clears the ID and key: they never work on the other
    /// service, and the fields do not record which one they belong to (also when switching through FusionSolar).
    private var providerBinding: Binding<SolarProvider> {
        Binding(
            get: { model.settings.solarProvider },
            set: { new in
                model.updateSettings { settings in
                    if settings.solarProvider != new, new.usesAPIKey {
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
        case .fusionSolar: Text(verbatim: "")
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
                ForEach(SolarProvider.allCases) { provider in
                    let isSelected = provider == selection
                    Button { selection = provider } label: {
                        Text(verbatim: provider.shortName)
                            .font(.system(.footnote, design: .rounded).weight(isSelected ? .bold : .medium))
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
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
    @State private var siteID: String
    @State private var apiKey: String
    @State private var test: KioskTestState
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase

    init(model: AppModel, provider: SolarProvider, initialTest: KioskTestState = .idle) {
        self.model = model
        self.provider = provider
        _siteID = State(initialValue: model.settings.solarSiteID)
        _apiKey = State(initialValue: model.settings.solarAPIKey)
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
            AppTextField(prompt: keyPrompt, text: $apiKey, onCommit: commit)
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
        case .fusionSolar, .pvOutput: "System ID"
        case .solarEdge: "Site ID"
        }
    }

    private var keyPrompt: String {
        switch provider {
        case .fusionSolar, .pvOutput: "a1b2c3d4…"
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
        guard model.settings.solarProvider == provider else { return }
        let id = siteID.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard id != model.settings.solarSiteID || key != model.settings.solarAPIKey else { return }
        model.updateSettings {
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

    init(model: AppModel) {
        self.model = model
        _host = State(initialValue: model.settings[keyPath: model.localInverterHostKey])
    }

    private var isHuawei: Bool { model.localInverterKind == .huawei }

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
                    atext("Optional. If your inverter offers SunSpec over Modbus TCP (SolarEdge, SMA, Fronius and other brands; it may have to be turned on, for example by your installer), SunWatt reads it directly while you are at home, every few seconds. Elsewhere it uses \(model.settings.solarProvider.displayName).")
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
        let key = model.localInverterHostKey
        guard value != model.settings[keyPath: key] else { return }
        model.updateSettings { $0[keyPath: key] = value }
    }

    private func runSearch() {
        search = .searching
        test = .idle
        Task {
            let matches = await model.findInverters()
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
        let key = model.localInverterUnitKey
        if match.unitID != model.settings[keyPath: key] { model.updateSettings { $0[keyPath: key] = match.unitID } }
        test = .success(match)
    }

    private func runTest() {
        commit()
        search = .idle
        test = .testing
        let value = host
        resultHost = value.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            switch await model.testInverter(value) {
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
#endif
