package com.ideatic.sunwatt.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.ui.LocalAppViewModel
import com.ideatic.sunwatt.ui.components.AppSymbol
import com.ideatic.sunwatt.ui.components.SymbolKind
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.appPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The editors of the solar setup. Typed text lives in the editor and is saved to the settings only at the right moments
// (never per keystroke): the user submits, leaves a field or the screen, or 0.8 s after the last keystroke when the
// values are valid. A secret (API key, system code) is never logged and is hidden in the field.

@Composable
private fun TestButton(enabled: Boolean, onClick: () -> Unit) {
    SoftIconButton(
        stringResource(R.string.app_test_connection), onClick, compact = true, enabled = enabled,
        leading = { AppSymbol(SymbolKind.BOLT, appPalette.accentText, size = 16.dp) },
    )
}

/** Kiosk link field with validation, paste button and "Test connection". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KioskLinkEditor(plantID: String, modifier: Modifier = Modifier, initialTest: SolarTestState = SolarTestState.Idle) {
    val vm = LocalAppViewModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = appPalette
    var test by remember(plantID) { mutableStateOf(initialTest) }
    val draft = remember(plantID) {
        KioskUrlDraft(vm.settingsFocusedOn(plantID).kioskURL, scope) { value -> vm.updatePlant(plantID) { it.copy(kioskURL = value) } }
    }
    val input = draft.inputState
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        AppTextField(
            draft.text, { draft.update(it); test = SolarTestState.Idle }, "https://…/kiosk?kk=…",
            monospace = true, singleLine = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
            onCommit = draft::commitNow,
        )
        when (input) {
            KioskInputState.Empty -> ValidationRow(Tone.NEUTRAL, stringResource(R.string.app_paste_the_link_you_copied_from)) { AppSymbol(SymbolKind.LINK, palette.textSecondary, size = 16.dp) }
            is KioskInputState.Valid -> ValidationRow(Tone.SUCCESS, stringResource(R.string.app_valid_kiosk_link, input.host)) { SuccessIcon() }
            KioskInputState.Invalid -> ValidationRow(Tone.WARNING, stringResource(R.string.app_this_is_not_a_kiosk_link)) { WarningIcon() }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SoftIconButton(stringResource(R.string.app_paste), {
                clipboardText(context)?.let { draft.paste(it); test = SolarTestState.Idle }
            }, compact = true)
            TestButton(enabled = input.isValid) {
                test = SolarTestState.Testing
                val text = draft.text
                scope.launch { test = vm.testKiosk(text).toTestState(context) }
            }
        }
        SolarTestResult(test)
    }
    CommitOnLeave(draft::commitNow)
}

/** Reads the clipboard text when the user taps Paste. */
private fun clipboardText(context: android.content.Context): String? {
    val manager = context.getSystemService(android.content.ClipboardManager::class.java) ?: return null
    return manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

/** Site/system ID and API key fields with validation and "Test connection" (SolarEdge, PVOutput). */
@Composable
fun SolarApiEditor(provider: SolarProvider, plantID: String, modifier: Modifier = Modifier, initialTest: SolarTestState = SolarTestState.Idle) {
    val vm = LocalAppViewModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var siteID by remember(plantID, provider) { mutableStateOf(vm.settingsFocusedOn(plantID).solarSiteID) }
    var apiKey by remember(plantID, provider) { mutableStateOf(vm.settingsFocusedOn(plantID).solarAPIKey) }
    var test by remember(plantID, provider) { mutableStateOf(initialTest) }
    val input = apiInputState(siteID, apiKey)

    // Saves both fields at once, only while this provider is still the selected one (switching providers clears the
    // fields; the editor that goes away must not write its old text back).
    val commit = {
        val saved = vm.settingsFocusedOn(plantID)
        val id = siteID.trim()
        val key = apiKey.trim()
        if (saved.solarProvider == provider && (id != saved.solarSiteID || key != saved.solarAPIKey)) {
            vm.updatePlant(plantID) { it.copy(solarSiteID = id, solarAPIKey = key) }
        }
    }
    // Valid input is saved shortly after the last keystroke, so the onboarding button stops offering "Skip, prices only".
    LaunchedEffect(siteID, apiKey) {
        if (input != CredentialInput.VALID) return@LaunchedEffect
        delay(KioskUrlDraft.DEFAULT_DEBOUNCE_MS)
        commit()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        FieldLabel(stringResource(if (provider == SolarProvider.SOLAR_EDGE) R.string.app_site_id else R.string.app_system_id))
        AppTextField(siteID, { siteID = it; test = SolarTestState.Idle }, "123456", onCommit = commit, keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
        FieldLabel(stringResource(R.string.app_api_key))
        AppTextField(
            apiKey, { apiKey = it; test = SolarTestState.Idle },
            if (provider == SolarProvider.SOLAR_EDGE) "ABCD1234…" else "a1b2c3d4…", onCommit = commit, isSecret = true,
        )
        when (input) {
            CredentialInput.EMPTY -> ValidationRow(Tone.NEUTRAL, stringResource(R.string.app_enter_the_id_and_the_api)) { KeyIcon() }
            CredentialInput.VALID -> ValidationRow(Tone.SUCCESS, stringResource(R.string.app_ready_test_the_connection_to_check)) { SuccessIcon() }
            CredentialInput.INVALID -> ValidationRow(Tone.WARNING, SunWattError.InvalidSolarCredentials.text()) { WarningIcon() }
        }
        Column(Modifier.padding(top = Spacing.xs)) {
            TestButton(enabled = input == CredentialInput.VALID) {
                commit()
                test = SolarTestState.Testing
                val id = siteID
                val key = apiKey
                scope.launch { test = vm.testSolarAPI(provider, id, key).toTestState(context) }
            }
        }
        SolarTestResult(test)
    }
    CommitOnLeave(commit)
}

@Composable
private fun KeyIcon() = Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = appPalette.textSecondary)

/** FusionSolar API account: user name, system code and server, with a connection test. Saved like the API editor. */
@Composable
fun NorthboundAccountEditor(plantID: String, modifier: Modifier = Modifier, initialTest: SolarTestState = SolarTestState.Idle) {
    val vm = LocalAppViewModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember(plantID) { mutableStateOf(vm.settingsFocusedOn(plantID).northboundHost) }
    var userName by remember(plantID) { mutableStateOf(vm.settingsFocusedOn(plantID).solarSiteID) }
    var systemCode by remember(plantID) { mutableStateOf(vm.settingsFocusedOn(plantID).solarAPIKey) }
    var test by remember(plantID) { mutableStateOf(initialTest) }
    val input = northboundInputState(host, userName, systemCode)

    // Saves the three fields at once, only while this is still the plant's provider.
    val commit = {
        val saved = vm.settingsFocusedOn(plantID)
        val server = host.trim()
        val user = userName.trim()
        val code = systemCode.trim()
        if (saved.solarProvider == SolarProvider.FUSION_SOLAR_API &&
            (server != saved.northboundHost || user != saved.solarSiteID || code != saved.solarAPIKey)
        ) {
            vm.updatePlant(plantID) { it.copy(northboundHost = server, solarSiteID = user, solarAPIKey = code) }
        }
    }
    LaunchedEffect(host, userName, systemCode) {
        if (input != CredentialInput.VALID) return@LaunchedEffect
        delay(KioskUrlDraft.DEFAULT_DEBOUNCE_MS)
        commit()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        FieldLabel(stringResource(R.string.app_user_name))
        AppTextField(userName, { userName = it; test = SolarTestState.Idle }, "api_user", onCommit = commit)
        FieldLabel(stringResource(R.string.app_password_system_code))
        AppTextField(systemCode, { systemCode = it; test = SolarTestState.Idle }, "••••••••", onCommit = commit, isSecret = true)
        FieldLabel(stringResource(R.string.app_server))
        AppTextField(host, { host = it; test = SolarTestState.Idle }, com.ideatic.sunwatt.core.models.NORTHBOUND_DEFAULT_HOST, onCommit = commit, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
        when (input) {
            CredentialInput.EMPTY -> ValidationRow(Tone.NEUTRAL, stringResource(R.string.app_enter_the_user_name_and_the)) { KeyIcon() }
            CredentialInput.VALID -> ValidationRow(Tone.SUCCESS, stringResource(R.string.app_ready_test_the_connection_to_check)) { SuccessIcon() }
            CredentialInput.INVALID -> ValidationRow(Tone.WARNING, SunWattError.InvalidNorthboundAccount.text()) { WarningIcon() }
        }
        Column(Modifier.padding(top = Spacing.xs)) {
            TestButton(enabled = input == CredentialInput.VALID) {
                commit()
                test = SolarTestState.Testing
                val server = host
                val user = userName
                val code = systemCode
                scope.launch { test = vm.testNorthbound(server, user, code).toTestState(context) }
            }
        }
        SolarTestResult(test)
    }
    CommitOnLeave(commit)
}
