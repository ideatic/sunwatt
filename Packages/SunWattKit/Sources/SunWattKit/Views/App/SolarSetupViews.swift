#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI

extension SolarProvider {
    /// Short name for the provider picker (three fit side by side on a phone).
    var shortName: String {
        switch self {
        case .fusionSolar: "FusionSolar"
        case .solarEdge: "SolarEdge"
        case .pvOutput: "PVOutput"
        }
    }

    /// Where the user finds the ID and API key.
    var portalURL: URL? {
        switch self {
        case .fusionSolar: nil
        case .solarEdge: URL(string: "https://monitoring.solaredge.com")
        case .pvOutput: URL(string: "https://pvoutput.org/account.jsp")
        }
    }
}

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
            switch provider {
            case .fusionSolar:
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
            case .solarEdge, .pvOutput:
                SolarAPIEditor(model: model, provider: provider, initialTest: initialTest)
                    .id(provider)
                hairline
                help(provider)
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
            atext(provider == .solarEdge
                  ? "In the SolarEdge monitoring portal open Admin, then Site Access and API Access. Accept the terms, then copy the API key and the site ID."
                  : "On pvoutput.org open Settings. Turn on API Access, then copy the API Key and the System Id shown on that page.")
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
            fieldLabel(provider == .solarEdge ? "Site ID" : "System ID")
            AppTextField(prompt: "123456", text: $siteID, onCommit: commit)
            fieldLabel("API key")
            AppTextField(prompt: provider == .solarEdge ? "ABCD1234…" : "a1b2c3d4…", text: $apiKey, onCommit: commit)
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

/// "Live data at home": the Huawei dongle's address on the home network (Modbus TCP), optional, with a test.
/// Saved when the user submits, leaves the field or the view goes away.
struct InverterLinkEditor: View {
    let model: AppModel
    @State private var host: String
    @State private var test: InverterTestState = .idle
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase

    enum InverterTestState {
        case idle, testing
        case success(HuaweiInverterReading)
        case failure(String)
    }

    init(model: AppModel) {
        self.model = model
        _host = State(initialValue: model.settings.huaweiLocalHost)
    }

    var body: some View {
        let palette = AppPalette(scheme)
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let valid = HuaweiLocalEndpoint(parsing: trimmed) != nil
        VStack(alignment: .leading, spacing: Theme.Spacing.s) {
            Label { atext("Live data at home") } icon: { Image(systemName: "house.fill") }
                .font(.system(.subheadline, design: .rounded).weight(.bold))
            atext("Optional. If Modbus TCP is set to “Enable (unrestricted)” in the Huawei dongle, SunWatt reads the inverter directly while you are at home, every few seconds. Elsewhere it uses the kiosk link.")
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            atext("Dongle IP address")
                .font(.system(.footnote, design: .rounded).weight(.semibold))
                .padding(.horizontal, Theme.Spacing.xs)
            AppTextField(prompt: "192.168.1.50", text: $host, onCommit: commit)
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
            Button { runTest() } label: {
                Label { atext("Test inverter") } icon: { Image(systemName: "bolt.horizontal.circle") }
            }
            .buttonStyle(SoftButtonStyle(compact: true))
            .disabled(!valid)
            .opacity(valid ? 1 : 0.5)
            result(palette: palette)
        }
        .onChange(of: host) { _, _ in test = .idle }
        .onDisappear { commit() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { commit() }
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
        case .success(let reading):
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.success)
                VStack(alignment: .leading, spacing: 2) {
                    atext("Inverter found").font(.system(.subheadline, design: .rounded).weight(.bold))
                    atext("\(Format.kW(max(reading.activePowerKW, 0))) kW now · \(Format.kWh(reading.dailyEnergyKWh)) kWh today")
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

    private func commit() {
        let value = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value != model.settings.huaweiLocalHost else { return }
        model.updateSettings { $0.huaweiLocalHost = value }
    }

    private func runTest() {
        commit()
        test = .testing
        let value = host
        Task {
            switch await model.testInverter(value) {
            case .success(let (reading, unit)):
                test = .success(reading)
                if unit != model.settings.huaweiLocalUnitID { model.updateSettings { $0.huaweiLocalUnitID = unit } }
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
