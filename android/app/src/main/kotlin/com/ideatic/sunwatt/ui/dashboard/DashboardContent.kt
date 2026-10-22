package com.ideatic.sunwatt.ui.dashboard

import android.content.Context
import android.icu.text.ListFormatter
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.SolarProviderProfile
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.models.plant
import com.ideatic.sunwatt.core.models.plants
import com.ideatic.sunwatt.core.models.primaryPlant
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.ui.components.AppIconBadge
import com.ideatic.sunwatt.ui.components.AppSymbol
import com.ideatic.sunwatt.ui.components.PrimaryButton
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.components.SoftButton
import com.ideatic.sunwatt.ui.guide.PinWidgetButtons
import com.ideatic.sunwatt.widget.WidgetPinning
import com.ideatic.sunwatt.ui.components.StateAction
import com.ideatic.sunwatt.ui.components.StateCard
import com.ideatic.sunwatt.ui.components.CardHeader
import com.ideatic.sunwatt.ui.components.SolarSymbol
import com.ideatic.sunwatt.ui.components.SymbolKind
import com.ideatic.sunwatt.ui.model.AppUiState
import com.ideatic.sunwatt.ui.model.DashboardPreselection
import com.ideatic.sunwatt.ui.text.TextRes
import com.ideatic.sunwatt.ui.text.textRes
import com.ideatic.sunwatt.ui.theme.BigFigure
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.theme.SolarColors
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.SunWattCard
import com.ideatic.sunwatt.ui.theme.appPalette
import com.ideatic.sunwatt.ui.setup.PlusSquareSymbol
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** What the dashboard can ask the app to do. */
class DashboardActions(
    val onOpenSettings: () -> Unit,
    val onOpenWidgetGuide: () -> Unit,
    val onRefresh: () -> Unit,
    val onDismissKioskNotice: () -> Unit,
    /** Opens the "Get the kiosk link" screen. */
    val onOpenKioskHelp: () -> Unit = {},
)

/**
 * The dashboard without scrolling or pull-to-refresh, so a test can draw it at full height. Solar and price sit side by
 * side when there is room (tablets), stacked on a phone.
 */
@Composable
fun DashboardContent(
    state: AppUiState,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
    preselection: DashboardPreselection = DashboardPreselection(),
) {
    val locale = LocalConfiguration.current.locales[0]
    val placeholder = state.entry == null
    val entry = state.entry ?: SampleData.entry(SampleData.Scenario.DAY, state.settings.priceRegion).let { SunWattEntry(state.now, it.data) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        val twoColumns = maxWidth >= 640.dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            DashboardHeader(state, wide, locale, actions)
            state.kioskExpiryNoticeDate?.let { KioskExpiryNotice(it, locale, actions) }
            if (twoColumns) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    Column(Modifier.weight(1f)) { SolarSection(entry, state, placeholder, wide, locale, actions, preselection) }
                    Column(Modifier.weight(1f)) { PriceSection(entry, state, placeholder, wide, locale, actions, preselection) }
                }
            } else {
                SolarSection(entry, state, placeholder, wide, locale, actions, preselection)
                PriceSection(entry, state, placeholder, wide, locale, actions, preselection)
            }
            WidgetStatusCard(state.widgetCount, locale, actions)
            DashboardFooter(entry, state.settings, showsUpdated = !placeholder, locale)
        }
    }
}

// region Header and footer

@Composable
private fun DashboardHeader(state: AppUiState, wide: Boolean, locale: Locale, actions: DashboardActions) {
    val palette = appPalette
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        AppIconBadge(40.dp)
        Column(Modifier.weight(1f)) {
            RoundedText(dateText(state.now, locale), if (wide) 28 else 20, weight = FontWeight.Bold, maxLines = 1)
            RoundedText("SunWatt", 13, weight = FontWeight.SemiBold, color = palette.textSecondary)
        }
        if (state.isLoading && state.hasLoaded) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = palette.textSecondary)
        val settingsLabel = stringResource(R.string.app_settings)
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(palette.card).border(BorderStroke(0.75.dp, palette.cardStroke), CircleShape)
                .clickable(role = Role.Button, onClick = actions.onOpenSettings),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Settings, settingsLabel, Modifier.size(20.dp), tint = palette.accentText) }
    }
}

/** "Saturday, 17 October" in the user's language, first letter capitalized (Madrid time, like the solar labels). */
internal fun dateText(now: Instant, locale: Locale, zone: ZoneId = Constants.madrid): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
    val text = java.time.format.DateTimeFormatter.ofPattern(pattern, locale).format(now.atZone(zone))
    return text.replaceFirstChar { it.titlecase(locale) }
}

@Composable
private fun DashboardFooter(entry: SunWattEntry, settings: SunWattSettings, showsUpdated: Boolean, locale: Locale) {
    val palette = appPalette
    val updated = entry.data.lastUpdated
    val region = entry.data.prices?.region ?: settings.priceRegion
    val providers = settings.plants.map { it.provider }.distinct().map { it.displayName }
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (showsUpdated && updated != null) {
            RoundedText(stringResource(R.string.app_updated, Format.time(updated, locale = locale)), 12, weight = FontWeight.SemiBold, color = palette.textSecondary, textAlign = TextAlign.Center)
        }
        RoundedText(sourceAttribution(region), 11, color = palette.textSecondary, textAlign = TextAlign.Center)
        RoundedText(stringResource(R.string.app_solar, ListFormatter.getInstance(locale).format(providers)), 11, color = palette.textSecondary, textAlign = TextAlign.Center)
    }
}

/** "Prices: Red Eléctrica" or "Prices: Energy-Charts (Fraunhofer ISE), CC BY 4.0" (the licence attribution must stay visible). */
@Composable
internal fun sourceAttribution(region: PriceRegion): String =
    if (region.isRetailTariff) stringResource(R.string.app_prices, region.sourceName) else stringResource(R.string.app_prices_cc_by_4_0, region.sourceName)

/** "Your kiosk link may stop working around …", from 30 days before the link's first anniversary. */
@Composable
private fun KioskExpiryNotice(expiry: Instant, locale: Locale, actions: DashboardActions) {
    val palette = appPalette
    val pattern = DateFormat.getBestDateTimePattern(locale, "dMMMMy")
    val date = java.time.format.DateTimeFormatter.ofPattern(pattern, locale).format(expiry.atZone(ZoneId.systemDefault()))
    SunWattCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(28.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) { AppSymbol(SymbolKind.LINK, palette.warning, size = 22.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                RoundedText(stringResource(R.string.app_your_kiosk_link_may_stop_working, date), 15)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    PrimaryButton(stringResource(R.string.app_renew_link), actions.onOpenSettings, compact = true)
                    SoftButton(stringResource(R.string.app_how_do_i_get_it), actions.onOpenKioskHelp, compact = true)
                }
            }
            Icon(
                Icons.Filled.Close, stringResource(R.string.app_done),
                Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button, onClick = actions.onDismissKioskNotice).padding(6.dp),
                tint = palette.textSecondary,
            )
        }
    }
}

// endregion

// region Solar

@Composable
private fun SolarSection(
    entry: SunWattEntry, state: AppUiState, placeholder: Boolean, wide: Boolean, locale: Locale,
    actions: DashboardActions, preselection: DashboardPreselection,
) {
    if (placeholder) return SolarSkeleton(wide)
    if (entry.data.hasSeveralPlants) {
        PlantPager(entry) { page -> PlantSection(page, state, wide, locale, actions, preselection) }
    } else {
        PlantSection(entry, state, wide, locale, actions, preselection)
    }
}

@Composable
private fun PlantSection(
    entry: SunWattEntry, state: AppUiState, wide: Boolean, locale: Locale,
    actions: DashboardActions, preselection: DashboardPreselection,
) {
    val palette = appPalette
    val data = entry.data
    if (data.solarLinkExpired) {
        StateCard(
            title = stringResource(R.string.app_kiosk_link_expired),
            message = SunWattError.KioskLinkRejected.textRes().text(),
            actions = listOf(
                StateAction(stringResource(R.string.app_replace_link), true, actions.onOpenSettings),
                StateAction(stringResource(R.string.app_how_do_i_get_it), false, actions.onOpenKioskHelp),
            ),
            symbol = { AppSymbol(SymbolKind.LINK, palette.warning, size = 22.dp) },
        )
        return
    }
    val status = data.solarStatus
    val solar = entry.solarForDisplay
    when {
        status is SourceStatus.NotConfigured -> SolarSetupCard(actions, showsKioskHelp(state, data.plantID))
        status is SourceStatus.Error && data.solar == null -> {
            val plant = state.settings.plant(data.plantID) ?: state.settings.primaryPlant
            val message = status.text()
            StateCard(
                title = stringResource(R.string.app_solar_data_unavailable),
                // Several plants: which one.
                message = data.plantName?.let { "$it: $message" } ?: message,
                actions = listOf(
                    StateAction(stringResource(R.string.app_try_again), true, actions.onRefresh),
                    StateAction(
                        stringResource(if (plant.provider.profile.setup == SolarProviderProfile.Setup.KIOSK_LINK) R.string.app_check_kiosk_link else R.string.app_settings),
                        false, actions.onOpenSettings,
                    ),
                ),
                symbol = { WarningSymbol() },
            )
        }
        solar != null -> SolarCard(
            solar, entry.date, entry.isSolarStale, locale, wide, plantName = data.plantName, initialSelection = preselection.solar,
        )
        else -> SolarSetupCard(actions, showsKioskHelp(state, data.plantID))
    }
}

/** Apple shows "How do I get it?" on the set-up card only while the selected provider is the kiosk link. */
private fun showsKioskHelp(state: AppUiState, plantID: String?): Boolean {
    val plant = plantID?.let { state.settings.plant(it) } ?: state.settings.primaryPlant
    return plant.provider.profile.setup == SolarProviderProfile.Setup.KIOSK_LINK
}

@Composable
private fun SolarSetupCard(actions: DashboardActions, showsKioskHelp: Boolean) {
    SunWattCard {
        CardHeader(title = stringResource(R.string.app_solar_production), symbol = { SolarSymbol(false, appPalette.textSecondary) })
        Column(Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RoundedText(stringResource(R.string.app_add_your_solar_plant), 20, weight = FontWeight.Bold)
            RoundedText(stringResource(R.string.app_connect_huawei_fusionsolar_solaredge_or_pvoutput), 15, color = appPalette.textSecondary)
        }
        Row(Modifier.padding(top = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PrimaryButton(stringResource(R.string.app_set_up_solar), actions.onOpenSettings, compact = true)
            if (showsKioskHelp) SoftButton(stringResource(R.string.app_how_do_i_get_it), actions.onOpenKioskHelp, compact = true)
        }
    }
}

/** Several plants: one page per plant, swiped sideways, with page dots below. */
@Composable
private fun PlantPager(entry: SunWattEntry, page: @Composable (SunWattEntry) -> Unit) {
    val plants = entry.data.allPlants
    val pager = rememberPagerState { plants.size }
    val palette = appPalette
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        // Room for the card shadow inside the clipped pager.
        HorizontalPager(pager, Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { index ->
            Box(Modifier.padding(vertical = 8.dp)) { page(SunWattEntry(entry.date, entry.data.showingPlant(plants[index].id))) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 0.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            for (i in plants.indices) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(palette.textPrimary.copy(alpha = if (i == pager.currentPage) 0.75f else 0.2f)))
            }
        }
    }
}

@Composable
private fun SolarSkeleton(wide: Boolean) {
    SunWattCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            SkeletonBar(24.dp, 24.dp, Modifier.background(SolarColors.amber.copy(alpha = 0.12f)))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { SkeletonBar(118.dp, 12.dp); SkeletonBar(34.dp, 9.dp) }
            Box(Modifier.weight(1f))
            SkeletonBar(80.dp, 9.dp)
        }
        Row(Modifier.padding(top = Spacing.m), verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { SkeletonBar(92.dp, 46.dp, strong = true); SkeletonBar(88.dp, 10.dp) }
            Box(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBar(70.dp, 18.dp, strong = true); SkeletonBar(50.dp, 9.dp); SkeletonBar(70.dp, 18.dp, strong = true); SkeletonBar(50.dp, 9.dp)
            }
        }
        SkeletonBlock(Modifier.padding(top = Spacing.m).fillMaxWidth().height(if (wide) 250.dp else 190.dp), 12.dp)
    }
}

@Composable
internal fun PriceSkeleton(wide: Boolean) {
    SunWattCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            SkeletonBar(24.dp, 24.dp, Modifier.background(PriceColors.green.copy(alpha = 0.16f)))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { SkeletonBar(30.dp, 12.dp); SkeletonBar(60.dp, 9.dp) }
            Box(Modifier.weight(1f))
            Box(Modifier.size(80.dp, 26.dp).clip(CircleShape).background(PriceColors.green.copy(alpha = 0.18f)))
        }
        SkeletonBar(80.dp, 36.dp, Modifier.padding(top = Spacing.m), strong = true)
        SkeletonBlock(Modifier.padding(top = Spacing.m).fillMaxWidth().height(if (wide) 230.dp else 170.dp), 12.dp)
        Row(Modifier.padding(top = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
            for (color in listOf(PriceColors.green, PriceColors.yellow, PriceColors.red)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SkeletonBar(30.dp, 8.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color)); SkeletonBar(44.dp, 16.dp, strong = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(width: Dp, height: Dp, modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(modifier.size(width, height).clip(RoundedCornerShape(4.dp)).background(appPalette.textPrimary.copy(alpha = if (strong) 0.13f else 0.07f)))
}

@Composable
private fun SkeletonBlock(modifier: Modifier, corner: Dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(appPalette.textPrimary.copy(alpha = 0.07f)))
}

// endregion

// region Price

@Composable
private fun PriceSection(
    entry: SunWattEntry, state: AppUiState, placeholder: Boolean, wide: Boolean, locale: Locale,
    actions: DashboardActions, preselection: DashboardPreselection,
) {
    if (placeholder) return PriceSkeleton(wide)
    val series = entry.data.prices
    if (series != null) {
        PriceCard(
            series, entry.date, entry.isPriceStale, locale, wide,
            startsOnTomorrow = preselection.startsOnTomorrow, initialSelection = preselection.price,
        )
    } else {
        val status = entry.data.priceStatus
        StateCard(
            title = stringResource(R.string.app_prices_unavailable),
            message = (status as? SourceStatus.Error)?.text() ?: stringResource(R.string.app_data_not_available_yet),
            actions = listOf(StateAction(stringResource(R.string.app_try_again), true, actions.onRefresh)),
            symbol = { WarningSymbol() },
        )
    }
}

@Composable
private fun WarningSymbol() {
    Icon(Icons.Filled.Warning, null, Modifier.size(24.dp), tint = appPalette.warning)
}

/** The text of a failed source: localized when the error is known, the original message otherwise. */
@Composable
internal fun SourceStatus.Error.text(): String = error?.textRes()?.text() ?: message

// endregion

// region Widget status

@Composable
private fun WidgetStatusCard(count: Int?, locale: Locale, actions: DashboardActions) {
    val palette = appPalette
    if (count != null && count > 0) {
        SunWattCard {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(30.dp), tint = palette.success)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RoundedText(stringResource(R.string.app_widget_added), 17, weight = FontWeight.SemiBold)
                    RoundedText(stringResource(R.string.app_active_widgets, count), 15, color = palette.textSecondary)
                }
                SoftButton(stringResource(R.string.app_add_another), actions.onOpenWidgetGuide, compact = true)
            }
        }
    } else {
        SunWattCard {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                MiniWidgetPreview(locale)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RoundedText(stringResource(R.string.app_add_the_widget), 20, weight = FontWeight.Bold)
                    RoundedText(stringResource(R.string.app_prices_and_solar_production_on_your), 15, color = palette.textSecondary)
                }
            }
            if (WidgetPinning.canPin(LocalContext.current)) {
                // The launcher can place a widget: size buttons first, the guide as a secondary action.
                PinWidgetButtons(Modifier.padding(top = Spacing.m))
                SoftButton(stringResource(R.string.app_show_me_how), actions.onOpenWidgetGuide, Modifier.padding(top = Spacing.s), compact = true)
            } else {
                PrimaryButton(
                    stringResource(R.string.app_show_me_how), actions.onOpenWidgetGuide, Modifier.padding(top = Spacing.m),
                    leading = { PlusSquareSymbol(palette.onAccent) },
                )
            }
        }
    }
}

/** A small drawn copy of the home screen widget, with sample figures. */
@Composable
private fun MiniWidgetPreview(locale: Locale) {
    val palette = appPalette
    val sample = remember(locale) { SampleData.entry(SampleData.Scenario.DAY) }
    val solar = sample.data.solar!!
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.size(96.dp).androidxShadow(shape).clip(shape).background(palette.backgroundGradient).border(0.75.dp, palette.cardStroke, shape).padding(9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppSymbol(SymbolKind.SUN, SolarColors.gradient, size = 13.dp)
            Box(Modifier.weight(1f))
            RoundedText(Format.time(solar.dataAsOf!!, locale = locale), 8, weight = FontWeight.Medium, color = palette.textSecondary)
        }
        Column {
            BigFigure(Format.kW(solar.currentPowerKW, locale), "kW", figureSizeSp = 25, unitSizeSp = 10)
            RoundedText(stringResource(R.string.app_kwh_today, Format.kWh(solar.dailyEnergyKWh, locale)), 7, weight = FontWeight.Medium, color = palette.textSecondary, maxLines = 1)
        }
        Row(
            Modifier.clip(CircleShape).background(PriceColors.green.copy(alpha = 0.2f)).padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            AppSymbol(SymbolKind.BOLT, PriceColors.green, size = 8.dp)
            RoundedText(Format.eurPerKWh(0.018, locale = locale), 9, weight = FontWeight.Bold, maxLines = 1, tabularDigits = true)
        }
    }
}

private fun Modifier.androidxShadow(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.then(Modifier.shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f)))

// endregion
