package com.ideatic.sunwatt.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.ui.LocalAppViewModel
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.theme.SolarColors
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.appPalette

/**
 * Provider picker, the editor of the selected provider and its help. Used by settings and onboarding.
 *
 * @param plantID the plant edited; the first one by default.
 * @param showsInverter settings shows the optional "Live data at home" section; onboarding keeps the first setup short.
 * @param initialTest a fixed test result, for screenshots.
 */
@Composable
fun SolarSetupEditor(
    modifier: Modifier = Modifier,
    plantID: String = com.ideatic.sunwatt.core.models.SolarPlant.PRIMARY_ID,
    showsInverter: Boolean = true,
    initialTest: SolarTestState = SolarTestState.Idle,
    onOpenKioskHelp: () -> Unit = {},
) {
    val vm = LocalAppViewModel.current
    val context = LocalContext.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val provider = remember(ui.settings, plantID) { vm.settingsFocusedOn(plantID).solarProvider }
    val choose = { new: SolarProvider -> vm.updatePlant(plantID) { it.choosingProvider(new) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SolarProviderPicker(selection = provider, onSelect = choose)
        if (provider.pickerGroup == SolarProvider.FUSION_SOLAR) FusionSolarConnectionPicker(provider, choose)
        // A key per provider: the editors of another provider start from its saved values.
        key(provider) {
            for (section in setupSections(provider, showsInverter)) {
                when (section) {
                    SetupSection.KIOSK_EDITOR -> KioskLinkEditor(plantID, initialTest = initialTest)
                    SetupSection.KIOSK_HELP_BUTTON -> {
                        Hairline()
                        SoftIconButton(
                            stringResource(R.string.app_how_do_i_get_the_kiosk), onOpenKioskHelp, Modifier.fillMaxWidth(),
                            leading = { Icon(Icons.Filled.Info, null, Modifier.size(18.dp), tint = appPalette.accentText) },
                        )
                    }
                    SetupSection.NORTHBOUND_EDITOR -> NorthboundAccountEditor(plantID, initialTest = initialTest)
                    SetupSection.NORTHBOUND_HELP -> {
                        Hairline()
                        RoundedText(stringResource(R.string.app_your_installer_creates_an_api_account), 13, color = appPalette.textSecondary)
                    }
                    SetupSection.API_EDITOR -> SolarApiEditor(provider, plantID, initialTest = initialTest)
                    SetupSection.PROVIDER_HELP -> {
                        Hairline()
                        ProviderHelp(provider) { url -> openUrl(context, url) }
                    }
                    SetupSection.INVERTER -> {
                        // Only the home-network provider has the inverter as its whole setup: no rule above it.
                        if (setupSections(provider, showsInverter).first() != SetupSection.INVERTER) Hairline()
                        InverterLinkEditor(plantID)
                    }
                }
            }
        }
    }
}

/** Where the ID and key are found, per provider (exhaustive: a new provider needs its own text), and the portal link. */
@Composable
private fun ProviderHelp(provider: SolarProvider, openUrl: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        val text = when (provider) {
            SolarProvider.FUSION_SOLAR, SolarProvider.FUSION_SOLAR_API -> ""
            SolarProvider.FRONIUS -> stringResource(R.string.app_fronius_offers_no_free_cloud_access)
            SolarProvider.SOLAR_EDGE -> stringResource(R.string.app_in_the_solaredge_monitoring_portal_open)
            SolarProvider.PV_OUTPUT -> stringResource(R.string.app_on_pvoutput_org_open_settings_turn)
        }
        if (text.isNotEmpty()) RoundedText(text, 13, color = appPalette.textSecondary)
        provider.portalUrl?.let { url ->
            SoftIconButton(provider.displayName, { openUrl(url) }, Modifier.fillMaxWidth())
        }
    }
}

/** One button per provider, side by side: FusionSolar, SolarEdge, PVOutput, Fronius. */
@Composable
fun SolarProviderPicker(selection: SolarProvider, onSelect: (SolarProvider) -> Unit, modifier: Modifier = Modifier) {
    val palette = appPalette
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(stringResource(R.string.app_solar_system))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (provider in SolarProvider.pickerCases) {
                val selected = provider == selection.pickerGroup
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier.weight(1f)
                        .clip(shape)
                        .background(if (selected) SolarColors.amber.copy(alpha = if (palette.isDark) 0.22f else 0.24f) else palette.fill)
                        .border(BorderStroke(if (selected) 1.dp else 0.5.dp, if (selected) SolarColors.amber else palette.hairline), shape)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) onSelect(provider) })
                        .semantics { contentDescription = provider.displayName }
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ShrinkText(provider.shortName, 13f, weight = if (selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

/** FusionSolar: "Kiosk link" or "API account" (Northbound API), two ways to connect the same service. */
@Composable
fun FusionSolarConnectionPicker(selection: SolarProvider, onSelect: (SolarProvider) -> Unit, modifier: Modifier = Modifier) {
    val palette = appPalette
    Row(modifier.fillMaxWidth().clip(CircleShape).background(palette.fill).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((provider, label) in listOf(
            SolarProvider.FUSION_SOLAR to stringResource(R.string.app_kiosk_link),
            SolarProvider.FUSION_SOLAR_API to stringResource(R.string.app_api_account),
        )) {
            val selected = selection == provider
            Box(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (selected) palette.card else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(provider) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                RoundedText(label, 13, weight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
