package com.ideatic.sunwatt.ui.setup

import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.models.SolarProviderProfile
import com.ideatic.sunwatt.core.models.SolarAPICredentials
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.services.KioskEndpoint
import com.ideatic.sunwatt.core.services.NorthboundCredentials

// Pure rules of the solar setup screens (ports of the Swift `KioskInputState`, the editors' `input` and the provider
// binding). No Android types, so unit tests run them directly.

/** State of the kiosk link field. */
sealed interface KioskInputState {
    data object Empty : KioskInputState
    data class Valid(val host: String) : KioskInputState
    data object Invalid : KioskInputState

    val isValid: Boolean get() = this is Valid
}

fun kioskInputState(text: String): KioskInputState {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return KioskInputState.Empty
    val endpoint = KioskEndpoint.parse(trimmed) ?: return KioskInputState.Invalid
    return KioskInputState.Valid(endpoint.host)
}

/** State of a pair of credential fields (ID + key, or user name + system code). */
enum class CredentialInput { EMPTY, VALID, INVALID }

fun apiInputState(siteID: String, apiKey: String): CredentialInput {
    val id = siteID.trim()
    val key = apiKey.trim()
    if (id.isEmpty() && key.isEmpty()) return CredentialInput.EMPTY
    return if (SolarAPICredentials.parse(id, key) != null) CredentialInput.VALID else CredentialInput.INVALID
}

fun northboundInputState(host: String, userName: String, systemCode: String): CredentialInput {
    val user = userName.trim()
    val code = systemCode.trim()
    if (user.isEmpty() && code.isEmpty()) return CredentialInput.EMPTY
    return if (NorthboundCredentials.create(host, user, code) != null) CredentialInput.VALID else CredentialInput.INVALID
}

/**
 * These settings (focused on one plant) with [new] chosen. Choosing a provider that keeps its credentials in the ID and
 * key fields after another provider clears them: they never work on the other service, and the fields do not record
 * which service they belong to (also when switching through FusionSolar).
 */
fun SunWattSettings.choosingProvider(new: SolarProvider): SunWattSettings {
    val cleared = if (solarProvider != new && new.usesCredentialFields) copy(solarSiteID = "", solarAPIKey = "") else this
    return cleared.copy(solarProvider = new)
}

/** The blocks of the solar setup, in order. */
enum class SetupSection { KIOSK_EDITOR, KIOSK_HELP_BUTTON, NORTHBOUND_EDITOR, NORTHBOUND_HELP, API_EDITOR, PROVIDER_HELP, INVERTER }

/** Which blocks the setup of [provider] shows. [showsInverter]: settings has "Live data at home", onboarding keeps it short. */
fun setupSections(provider: SolarProvider, showsInverter: Boolean): List<SetupSection> {
    val inverter = if (showsInverter) listOf(SetupSection.INVERTER) else emptyList()
    return when (provider.profile.setup) {
        SolarProviderProfile.Setup.KIOSK_LINK -> listOf(SetupSection.KIOSK_EDITOR, SetupSection.KIOSK_HELP_BUTTON) + inverter
        SolarProviderProfile.Setup.NORTHBOUND_ACCOUNT -> listOf(SetupSection.NORTHBOUND_EDITOR, SetupSection.NORTHBOUND_HELP) + inverter
        // The inverter is the whole setup (also in onboarding).
        SolarProviderProfile.Setup.HOME_NETWORK -> listOf(SetupSection.INVERTER, SetupSection.PROVIDER_HELP)
        SolarProviderProfile.Setup.API_KEY -> listOf(SetupSection.API_EDITOR, SetupSection.PROVIDER_HELP) + inverter
    }
}
