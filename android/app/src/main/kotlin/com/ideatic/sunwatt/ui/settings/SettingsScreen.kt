package com.ideatic.sunwatt.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.models.plants
import com.ideatic.sunwatt.ui.LocalAppViewModel
import com.ideatic.sunwatt.ui.components.AppSymbol
import com.ideatic.sunwatt.ui.components.CardHeader
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.components.SymbolKind
import com.ideatic.sunwatt.ui.help.KioskHelpScreen
import com.ideatic.sunwatt.ui.setup.Hairline
import com.ideatic.sunwatt.ui.setup.PlantNameEditor
import com.ideatic.sunwatt.ui.setup.PlantTab
import com.ideatic.sunwatt.ui.setup.PlantTabs
import com.ideatic.sunwatt.ui.setup.RegionList
import com.ideatic.sunwatt.ui.setup.ScreenOverlay
import com.ideatic.sunwatt.ui.setup.ScreenScaffold
import com.ideatic.sunwatt.ui.setup.SoftIconButton
import com.ideatic.sunwatt.ui.setup.SolarSetupEditor
import com.ideatic.sunwatt.ui.setup.SolarTestState
import com.ideatic.sunwatt.ui.setup.SymbolTile
import com.ideatic.sunwatt.ui.setup.priceAttribution
import com.ideatic.sunwatt.ui.setup.tariffNote
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.theme.SolarColors
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.SunWattCard
import com.ideatic.sunwatt.ui.theme.appPalette
import com.ideatic.sunwatt.ui.setup.PlusSquareSymbol

/**
 * All settings in cards: prices, solar (one editor per plant), widgets, language, about.
 *
 * @param onOpenKioskHelp extra: opens the kiosk help. Without it (default) the screen shows the help above itself.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenWidgetGuide: () -> Unit,
    modifier: Modifier = Modifier,
    initialTest: SolarTestState = SolarTestState.Idle,
    initialPlant: String = SolarPlant.PRIMARY_ID,
    onOpenKioskHelp: (() -> Unit)? = null,
) {
    // The kiosk help is drawn above the settings, which stay composed under it (typed text and the open plant survive).
    var showsKioskHelp by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        ScreenScaffold(stringResource(R.string.app_settings), onBack) {
            SettingsContent(onOpenWidgetGuide, onOpenKioskHelp ?: { showsKioskHelp = true }, initialTest, initialPlant)
        }
        if (showsKioskHelp) ScreenOverlay({ showsKioskHelp = false }) { KioskHelpScreen({ showsKioskHelp = false }) }
    }
}

@Composable
fun SettingsContent(
    onOpenWidgetGuide: () -> Unit,
    onOpenKioskHelp: () -> Unit,
    initialTest: SolarTestState = SolarTestState.Idle,
    initialPlant: String = SolarPlant.PRIMARY_ID,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        PricesCard()
        SolarCard(onOpenKioskHelp, initialTest, initialPlant)
        WidgetsCard(onOpenWidgetGuide)
        if (Build.VERSION.SDK_INT >= 33) LanguageCard()
        AboutCard()
    }
}

// region Prices

@Composable
private fun PricesCard() {
    val vm = LocalAppViewModel.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val palette = appPalette
    val region = ui.settings.priceRegion
    SunWattCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            CardHeader(
                title = stringResource(R.string.app_electricity_prices), subtitle = stringResource(R.string.app_choose_your_country),
                symbol = { AppSymbol(SymbolKind.BOLT, PriceColors.green, size = 22.dp) },
            )
            RegionList(region, { selected: PriceRegion -> vm.updateSettings { it.copy(priceRegion = selected) } })
            Column(Modifier.padding(horizontal = Spacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RoundedText(tariffNote(region), 13, weight = FontWeight.SemiBold)
                RoundedText(priceAttribution(region), 12, color = palette.textSecondary)
            }
            Hairline()
            ToggleRow(
                stringResource(R.string.app_show_tomorrow_s_prices_when_available), stringResource(R.string.app_published_around_20_30_in_spain),
                ui.settings.showTomorrowPrices,
            ) { checked -> vm.updateSettings { it.copy(showTomorrowPrices = checked) } }
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = appPalette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RoundedText(title, 16, weight = FontWeight.SemiBold)
            RoundedText(detail, 13, color = palette.textSecondary)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SolarColors.orange),
        )
    }
}

// endregion

// region Solar

/** One plant: its settings, plus "Add another plant". Several: a tab per plant, its name, its settings and "Remove this plant" for all but the first. */
@Composable
private fun SolarCard(onOpenKioskHelp: () -> Unit, initialTest: SolarTestState, initialPlant: String) {
    val vm = LocalAppViewModel.current
    val ui by vm.state.collectAsStateWithLifecycle()
    var editingPlant by rememberSaveable { mutableStateOf(initialPlant) }
    var confirmsRemoval by remember { mutableStateOf(false) }
    val plants = ui.settings.plants
    val plantID = if (plants.any { it.id == editingPlant }) editingPlant else SolarPlant.PRIMARY_ID
    val provider = vm.settingsFocusedOn(plantID).solarProvider
    SunWattCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            CardHeader(
                title = stringResource(R.string.app_solar_production), subtitle = provider.displayName,
                symbol = { AppSymbol(SymbolKind.SUN, SolarColors.gradient, size = 22.dp) },
            )
            if (plants.size > 1) {
                PlantTabs(
                    plants.map { plant ->
                        val station = ui.data?.allPlants?.firstOrNull { it.id == plant.id }?.solar?.stationName
                        PlantTab(plant.id, plant.displayName(station))
                    },
                    plantID, { editingPlant = it },
                )
                androidx.compose.runtime.key(plantID) { PlantNameEditor(plantID) }
            }
            androidx.compose.runtime.key(plantID) {
                SolarSetupEditor(plantID = plantID, initialTest = initialTest, onOpenKioskHelp = onOpenKioskHelp)
            }
            Hairline()
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                SoftIconButton(
                    stringResource(R.string.app_add_another_plant), { editingPlant = vm.addPlant() }, compact = true,
                    leading = { Icon(Icons.Filled.AddCircle, null, Modifier.size(16.dp), tint = appPalette.accentText) },
                )
                if (plantID != SolarPlant.PRIMARY_ID) {
                    SoftIconButton(
                        stringResource(R.string.app_remove_this_plant), { confirmsRemoval = true }, compact = true, tint = PriceColors.red,
                        leading = { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = PriceColors.red) },
                    )
                }
            }
        }
    }
    if (confirmsRemoval) {
        AlertDialog(
            onDismissRequest = { confirmsRemoval = false },
            title = { Text(stringResource(R.string.app_remove_this_plant)) },
            text = { Text(stringResource(R.string.app_its_settings_and_saved_data_are)) },
            confirmButton = {
                TextButton({
                    confirmsRemoval = false
                    vm.removePlant(plantID)
                    editingPlant = SolarPlant.PRIMARY_ID
                }) { Text(stringResource(R.string.app_remove_this_plant), color = PriceColors.red) }
            },
            dismissButton = { TextButton({ confirmsRemoval = false }) { Text(stringResource(R.string.app_back)) } },
        )
    }
}

// endregion

// region Widgets, language, about

@Composable
private fun WidgetsCard(onOpenWidgetGuide: () -> Unit) {
    val vm = LocalAppViewModel.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val palette = appPalette
    val count = ui.widgetCount
    SunWattCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            CardHeader(title = stringResource(R.string.app_widgets), symbol = { GridSymbol() })
            if (count != null && count > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = palette.success)
                    RoundedText(stringResource(R.string.app_widget_added), 15, weight = FontWeight.SemiBold)
                    Box(Modifier.weight(1f))
                    RoundedText(stringResource(R.string.app_active_widgets, count), 13, color = palette.textSecondary)
                }
            } else {
                RoundedText(stringResource(R.string.app_you_have_not_added_the_widget), 15, color = palette.textSecondary)
            }
            SoftIconButton(
                stringResource(R.string.app_how_to_add_the_widget), onOpenWidgetGuide, Modifier.fillMaxWidth(),
                leading = { PlusSquareSymbol(palette.accentText) },
            )
        }
    }
}

/** A 2x2 grid of rounded squares: the "widgets" symbol. */
@Composable
fun GridSymbol(size: androidx.compose.ui.unit.Dp = 22.dp) {
    val cell = size * 0.42f
    Column(verticalArrangement = Arrangement.spacedBy(size * 0.08f)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(size * 0.08f)) {
                repeat(2) { Box(Modifier.size(cell).clip(RoundedCornerShape(cell * 0.28f)).background(SolarColors.gradient)) }
            }
        }
    }
}

/** Opens the per-app language page of Android (API 33 and later). */
@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    val palette = appPalette
    SunWattCard {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { openLanguageSettings(context) },
            horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically,
        ) {
            SymbolTile(36.dp) { Icon(Icons.Filled.Settings, null, Modifier.size(20.dp), tint = palette.accentText) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                RoundedText(stringResource(R.string.app_language), 16, weight = FontWeight.SemiBold)
                RoundedText(stringResource(R.string.android_choose_the_app_language_in_android), 13, color = palette.textSecondary)
            }
        }
    }
}

private fun openLanguageSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        // The device has no per-app language page.
    }
}

/** "1.0 (1)": version name and build number from the package. */
internal fun appVersionText(context: Context): String {
    val info = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        null
    }
    return "${info?.versionName ?: "1.0"} (${info?.longVersionCode ?: 1})"
}

@Composable
private fun AboutCard() {
    val palette = appPalette
    val context = LocalContext.current
    SunWattCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(SolarColors.gradient),
                    contentAlignment = Alignment.Center,
                ) { AppSymbol(SymbolKind.SUN, Color.White, size = 30.dp) }
                Column {
                    RoundedText("SunWatt", 17, weight = FontWeight.Bold)
                    RoundedText(stringResource(R.string.app_version, appVersionText(context)), 13, color = palette.textSecondary)
                }
            }
            Hairline()
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                RoundedText(stringResource(R.string.app_data_sources), 13, weight = FontWeight.Bold)
                SourceLine("Red Eléctrica de España", "PVPC")
                SourceLine("Energy-Charts (Fraunhofer ISE)", "CC BY 4.0")
                SourceLine("Huawei FusionSolar", "Kiosk")
                SourceLine("SolarEdge", "Monitoring API")
                SourceLine("PVOutput", "API")
            }
            RoundedText(stringResource(R.string.app_sunwatt_is_an_independent_app_it), 12, color = palette.textSecondary)
        }
    }
}

@Composable
private fun SourceLine(name: String, detail: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        RoundedText(name, 13, Modifier.weight(1f))
        RoundedText(detail, 13, color = appPalette.textSecondary)
    }
}

// endregion
