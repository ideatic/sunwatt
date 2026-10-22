package com.ideatic.sunwatt.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.inverter.InverterFinder
import com.ideatic.sunwatt.core.models.LiveInverterReading
import com.ideatic.sunwatt.core.models.LocalInverterKind
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.ui.LocalAppViewModel
import com.ideatic.sunwatt.ui.components.AppSymbol
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.components.SymbolKind
import com.ideatic.sunwatt.ui.model.localInverterHost
import com.ideatic.sunwatt.ui.model.localInverterUnitID
import com.ideatic.sunwatt.ui.model.withLocalInverterHost
import com.ideatic.sunwatt.ui.model.withLocalInverterUnitID
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.appPalette
import kotlinx.coroutines.launch

private sealed interface InverterTest {
    data object Idle : InverterTest
    data object Testing : InverterTest
    data class Success(val match: InverterFinder.Match) : InverterTest
    data class Failure(val message: String) : InverterTest
}

private sealed interface InverterSearch {
    data object Idle : InverterSearch
    data object Searching : InverterSearch
    data object None : InverterSearch
    data class Several(val matches: List<InverterFinder.Match>) : InverterSearch
}

/**
 * "Live data at home": the address of the inverter on the home network (Modbus TCP or the Fronius Solar API), optional,
 * with a network search ("Find inverter") and a test. FusionSolar: the Huawei dongle; SolarEdge and PVOutput: a SunSpec
 * inverter. Saved when the user submits, leaves the field or the screen goes away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InverterLinkEditor(plantID: String, modifier: Modifier = Modifier) {
    val vm = LocalAppViewModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = appPalette
    val kind = vm.localInverterKind(plantID)
    val provider = vm.settingsFocusedOn(plantID).solarProvider
    var host by remember(plantID, kind) { mutableStateOf(vm.settingsFocusedOn(plantID).localInverterHost(kind)) }
    var test by remember(plantID, kind) { mutableStateOf<InverterTest>(InverterTest.Idle) }
    var search by remember(plantID, kind) { mutableStateOf<InverterSearch>(InverterSearch.Idle) }
    // Address the shown result belongs to; editing the field to anything else clears the result.
    var resultHost by remember(plantID, kind) { mutableStateOf<String?>(null) }
    val trimmed = host.trim()
    val valid = ModbusEndpoint.parse(trimmed, defaultPort = kind.defaultPort) != null
    val busy = search is InverterSearch.Searching || test is InverterTest.Testing

    val commit = {
        val value = host.trim()
        if (value != vm.settingsFocusedOn(plantID).localInverterHost(kind)) vm.updatePlant(plantID) { it.withLocalInverterHost(kind, value) }
    }
    // Fills in and saves the found address and unit ID, and shows its reading as the test result.
    fun choose(match: InverterFinder.Match) {
        search = InverterSearch.Idle
        resultHost = match.address
        host = match.address
        commit()
        val savedUnit = vm.settingsFocusedOn(plantID).localInverterUnitID(kind)
        if (savedUnit != null && match.unitID != savedUnit) vm.updatePlant(plantID) { it.withLocalInverterUnitID(kind, match.unitID) }
        test = InverterTest.Success(match)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Home, null, Modifier.size(18.dp), tint = palette.textPrimary)
            RoundedText(stringResource(R.string.app_live_data_at_home), 15, weight = FontWeight.Bold)
        }
        RoundedText(
            when (kind) {
                LocalInverterKind.HUAWEI -> stringResource(R.string.app_optional_if_modbus_tcp_is_set)
                LocalInverterKind.SUN_SPEC -> stringResource(R.string.app_optional_if_your_inverter_offers_sunspec, provider.displayName)
                LocalInverterKind.FRONIUS -> stringResource(R.string.app_sunwatt_reads_your_fronius_inverter_through)
            },
            13, color = palette.textSecondary,
        )
        FieldLabel(stringResource(if (kind == LocalInverterKind.HUAWEI) R.string.app_dongle_ip_address else R.string.app_inverter_ip_address))
        AppTextField(
            host,
            { host = it; if (it.trim() != resultHost) test = InverterTest.Idle },
            when (kind) {
                LocalInverterKind.HUAWEI -> "192.168.1.50"
                LocalInverterKind.SUN_SPEC -> "192.168.1.40"
                LocalInverterKind.FRONIUS -> "192.168.1.30"
            },
            monospace = true, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, onCommit = commit,
        )
        if (trimmed.isNotEmpty() && !valid) {
            ValidationRow(Tone.WARNING, SunWattError.InvalidInverterAddress.text()) { WarningIcon() }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SoftIconButton(
                stringResource(R.string.app_find_inverter), {
                    search = InverterSearch.Searching
                    test = InverterTest.Idle
                    scope.launch {
                        val matches = vm.findInverters(plantID)
                        when (matches.size) {
                            0 -> search = InverterSearch.None
                            1 -> choose(matches[0])
                            else -> search = InverterSearch.Several(matches)
                        }
                    }
                },
                compact = true, enabled = !busy,
                leading = { Icon(Icons.Filled.Search, null, Modifier.size(16.dp), tint = palette.accentText) },
            )
            SoftIconButton(
                stringResource(R.string.app_test_inverter), {
                    commit()
                    search = InverterSearch.Idle
                    test = InverterTest.Testing
                    val value = host
                    resultHost = value.trim()
                    scope.launch {
                        vm.testInverter(value, plantID).fold(
                            onSuccess = { choose(it) },
                            onFailure = { test = InverterTest.Failure(it.userMessage(context)) },
                        )
                    }
                },
                compact = true, enabled = valid && !busy,
                leading = { AppSymbol(SymbolKind.BOLT, palette.accentText, size = 16.dp) },
            )
        }
        SearchResult(search, kind) { choose(it) }
        TestResult(test)
    }
    CommitOnLeave(commit)
}

@Composable
private fun SearchResult(search: InverterSearch, kind: LocalInverterKind, onChoose: (InverterFinder.Match) -> Unit) {
    val palette = appPalette
    when (search) {
        InverterSearch.Idle -> Unit
        InverterSearch.Searching -> BusyRow(stringResource(R.string.app_searching_the_home_network))
        InverterSearch.None -> ResultBox(Tone.WARNING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Search, null, Modifier.size(16.dp), tint = palette.warning)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RoundedText(stringResource(R.string.app_no_inverter_found), 15, weight = FontWeight.Bold)
                    RoundedText(
                        stringResource(
                            when (kind) {
                                LocalInverterKind.HUAWEI -> R.string.android_check_that_this_device_is_on_99c894
                                LocalInverterKind.SUN_SPEC -> R.string.android_check_that_this_device_is_on_063c8c
                                LocalInverterKind.FRONIUS -> R.string.android_check_that_this_device_is_on_70b3ae
                            },
                        ),
                        13, color = palette.textSecondary,
                    )
                }
            }
        }
        is InverterSearch.Several -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            RoundedText(stringResource(R.string.app_several_inverters_answered_choose_yours), 13, weight = FontWeight.SemiBold)
            for (match in search.matches) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.fill)
                        .androidClickable { onChoose(match) }.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    RoundedText(match.model?.let { "$it · ${match.address}" } ?: match.address, 15, weight = FontWeight.SemiBold)
                    RoundedText(readingCaption(match.reading), 13, color = palette.textSecondary, tabularDigits = true)
                }
            }
        }
    }
}

@Composable
private fun TestResult(test: InverterTest) {
    val palette = appPalette
    when (test) {
        InverterTest.Idle -> Unit
        InverterTest.Testing -> BusyRow(stringResource(R.string.app_connecting))
        is InverterTest.Success -> ResultBox(Tone.SUCCESS) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                SuccessIcon()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RoundedText(stringResource(R.string.app_inverter_found), 15, weight = FontWeight.Bold)
                    test.match.model?.let { RoundedText(it, 13, weight = FontWeight.SemiBold) }
                    RoundedText(readingCaption(test.match.reading), 13, color = palette.textSecondary, tabularDigits = true)
                }
            }
        }
        is InverterTest.Failure -> FailureBox(test.message)
    }
}

/** Power now, and today's energy when the inverter counts it (SunSpec inverters only have a lifetime counter). */
@Composable
private fun readingCaption(reading: LiveInverterReading): String {
    val power = Format.kW(maxOf(reading.activePowerKW, 0.0))
    val daily = reading.dailyEnergyKWh ?: return stringResource(R.string.app_kw_now, power)
    return stringResource(R.string.app_kw_now_kwh_today, power, Format.kWh(daily))
}

private fun Modifier.androidClickable(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick))
