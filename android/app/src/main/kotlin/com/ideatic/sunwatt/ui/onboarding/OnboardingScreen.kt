package com.ideatic.sunwatt.ui.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import com.ideatic.sunwatt.ui.setup.ShrinkText
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.models.hasSolarInput
import com.ideatic.sunwatt.ui.LocalAppViewModel
import com.ideatic.sunwatt.ui.components.AppSymbol
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.components.SymbolKind
import com.ideatic.sunwatt.ui.guide.PinWidgetButtons
import com.ideatic.sunwatt.ui.guide.WidgetGuideScreen
import com.ideatic.sunwatt.widget.WidgetPinning
import com.ideatic.sunwatt.ui.guide.WidgetStage
import com.ideatic.sunwatt.ui.help.KioskHelpScreen
import com.ideatic.sunwatt.ui.settings.GridSymbol
import com.ideatic.sunwatt.ui.setup.RegionList
import com.ideatic.sunwatt.ui.setup.ScreenOverlay
import com.ideatic.sunwatt.ui.setup.SoftIconButton
import com.ideatic.sunwatt.ui.setup.SolarSetupEditor
import com.ideatic.sunwatt.ui.setup.SolarTestState
import com.ideatic.sunwatt.ui.setup.SymbolTile
import com.ideatic.sunwatt.ui.setup.priceAttribution
import com.ideatic.sunwatt.ui.text.displayNameRes
import com.ideatic.sunwatt.ui.text.tariffName
import com.ideatic.sunwatt.ui.theme.AppBackground
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.theme.SolarColors
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.SunWattCard
import com.ideatic.sunwatt.ui.theme.appPalette
import com.ideatic.sunwatt.ui.setup.PlusSquareSymbol
import kotlinx.coroutines.launch

/**
 * First-launch flow: welcome, country, solar, widget, done. Pages swipe sideways; Back and Continue buttons below.
 *
 * @param onFinished called when the user finishes or skips; the caller saves `onboardingCompleted = true`.
 * @param startPage extra: the page shown first (screenshots).
 * @param initialTest extra: a fixed test result of the solar page (screenshots).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    startPage: OnboardingPage = OnboardingPage.WELCOME,
    initialTest: SolarTestState = SolarTestState.Idle,
) {
    val vm = LocalAppViewModel.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = startPage.ordinal) { OnboardingPage.entries.size }
    val scope = rememberCoroutineScope()
    val page = OnboardingPage.entries[pager.currentPage]
    // The widget guide and the kiosk help are drawn above the flow, which stays composed under them (typed text survives).
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }

    Box(modifier) {
        AppBackground {
            Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 0, verticalAlignment = Alignment.Top) { index ->
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = Spacing.l + 4.dp).padding(top = Spacing.xl, bottom = Spacing.m)) {
                            when (OnboardingPage.entries[index]) {
                                OnboardingPage.WELCOME -> WelcomePage()
                                OnboardingPage.COUNTRY -> CountryPage()
                                OnboardingPage.SOLAR -> SolarPage(initialTest) { overlay = "kiosk" }
                                OnboardingPage.WIDGET -> WidgetPage { overlay = "guide" }
                                OnboardingPage.DONE -> DonePage()
                            }
                        }
                    }
                }
                Bar(page, ui.settings.hasSolarInput,
                    onBack = { page.previous?.let { scope.launch { pager.animateScrollToPage(it.ordinal) } } },
                    onPrimary = {
                        val next = page.next
                        if (next == null) onFinished() else scope.launch { pager.animateScrollToPage(next.ordinal) }
                    })
            }
        }
        when (overlay) {
            "kiosk" -> ScreenOverlay({ overlay = null }) { KioskHelpScreen({ overlay = null }) }
            "guide" -> ScreenOverlay({ overlay = null }) { WidgetGuideScreen({ overlay = null }) }
        }
    }
}

@Composable
private fun Bar(page: OnboardingPage, hasSolarInput: Boolean, onBack: () -> Unit, onPrimary: () -> Unit) {
    val palette = appPalette
    Column(
        Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = Spacing.l + 4.dp).padding(top = Spacing.s, bottom = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            for (item in OnboardingPage.entries) {
                val selected = item == page
                Box(
                    Modifier.size(width = if (selected) 22.dp else 7.dp, height = 7.dp).clip(CircleShape)
                        .background(if (selected) SolarColors.gradient else Brush.linearGradient(listOf(palette.hairline, palette.hairline))),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            if (page.hasBack) {
                RoundedText(
                    stringResource(R.string.app_back), 17, weight = FontWeight.SemiBold, color = palette.accentText,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack).padding(horizontal = Spacing.s, vertical = 12.dp),
                )
            }
            Box(
                Modifier.weight(1f).heightIn(min = 50.dp).clip(RoundedCornerShape(16.dp)).background(Brush.verticalGradient(listOf(Color(1.0f, 0.78f, 0.30f), Color(1.0f, 0.62f, 0.24f))))
                    .clickable(role = Role.Button, onClick = onPrimary).padding(horizontal = 20.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { ShrinkText(stringResource(primaryLabel(page, hasSolarInput)), 17f, weight = FontWeight.Bold, color = palette.onAccent) }
        }
    }
}

// region Pages

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundedText(title, 30, weight = FontWeight.Bold)
        RoundedText(subtitle, 16, color = appPalette.textSecondary)
    }
}

/** The app icon: amber badge with the sun. */
@Composable
private fun AppBadge(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(SolarColors.gradient),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.5f).clip(CircleShape).background(Color(1f, 0.97f, 0.92f)), contentAlignment = Alignment.Center) {
            AppSymbol(SymbolKind.BOLT, SolarColors.orange, size = size * 0.3f)
        }
    }
}

@Composable
private fun WelcomePage() {
    val palette = appPalette
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(190.dp), contentAlignment = Alignment.Center) { AppBadge(128.dp) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RoundedText(stringResource(R.string.app_welcome_to_sunwatt), 32, weight = FontWeight.Bold, textAlign = TextAlign.Center)
            RoundedText(stringResource(R.string.app_your_solar_production_and_the_price), 16, color = palette.textSecondary, textAlign = TextAlign.Center)
        }
        SunWattCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m + 2.dp)) {
                Feature(stringResource(R.string.app_solar_production), stringResource(R.string.app_live_from_huawei_fusionsolar_solaredge_or)) {
                    AppSymbol(SymbolKind.SUN, SolarColors.gradient, size = 24.dp)
                }
                Feature(stringResource(R.string.app_electricity_prices), stringResource(R.string.app_hour_by_hour_for_spain_and)) {
                    AppSymbol(SymbolKind.BOLT, PriceColors.green, size = 22.dp)
                }
                Feature(stringResource(R.string.app_widgets), stringResource(R.string.android_for_the_home_screen_and_quick)) { GridSymbol() }
            }
        }
    }
}

@Composable
private fun Feature(title: String, detail: String, symbol: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        SymbolTile(42.dp, content = symbol)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            RoundedText(title, 16, weight = FontWeight.SemiBold)
            RoundedText(detail, 13, color = appPalette.textSecondary)
        }
    }
}

@Composable
private fun CountryPage() {
    val vm = LocalAppViewModel.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val region = ui.settings.priceRegion
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        PageTitle(stringResource(R.string.app_where_do_you_buy_electricity), stringResource(R.string.app_pick_your_country_to_see_its))
        SunWattCard(padding = Spacing.s) {
            RegionList(region, { selected -> vm.updateSettings { it.copy(priceRegion = selected) } }, showsNotes = true)
        }
        RoundedText(priceAttribution(region), 12, Modifier.padding(horizontal = Spacing.xs), color = appPalette.textSecondary)
    }
}

@Composable
private fun SolarPage(initialTest: SolarTestState, onOpenKioskHelp: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        PageTitle(stringResource(R.string.app_connect_your_solar_plant), stringResource(R.string.app_choose_your_monitoring_system_and_connect))
        SunWattCard {
            SolarSetupEditor(showsInverter = false, initialTest = initialTest, onOpenKioskHelp = onOpenKioskHelp)
        }
    }
}

@Composable
private fun WidgetPage(onShowGuide: () -> Unit) {
    val palette = appPalette
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        PageTitle(stringResource(R.string.app_add_the_widget), stringResource(R.string.app_the_widget_is_the_best_part))
        WidgetStage(height = 164.dp)
        SoftIconButton(
            stringResource(R.string.app_show_me_how), onShowGuide, Modifier.fillMaxWidth(),
            leading = { PlusSquareSymbol(palette.accentText) },
        )
        if (WidgetPinning.canPin(LocalContext.current)) PinWidgetButtons()
        RoundedText(stringResource(R.string.android_touch_and_hold_the_home_screen), 13, Modifier.padding(horizontal = Spacing.xs), color = palette.textSecondary)
    }
}

@Composable
private fun DonePage() {
    val vm = LocalAppViewModel.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = appPalette
    val region = ui.settings.priceRegion
    val hasSolar = ui.settings.hasSolarInput && ui.isSolarConfigured
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(150.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(84.dp), tint = PriceColors.green)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RoundedText(stringResource(R.string.app_you_are_all_set), 32, weight = FontWeight.Bold)
            RoundedText(stringResource(R.string.app_sunwatt_will_keep_your_widgets_up), 16, color = palette.textSecondary, textAlign = TextAlign.Center)
        }
        SunWattCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Feature("${region.flag} ${stringResource(region.displayNameRes())}", region.tariffName(context)) {
                    AppSymbol(SymbolKind.BOLT, PriceColors.green, size = 22.dp)
                }
                Feature(
                    stringResource(if (hasSolar) R.string.app_solar_plant_connected else R.string.app_prices_only),
                    if (hasSolar) ui.settings.solarProvider.displayName else stringResource(R.string.app_you_can_add_the_solar_plant),
                ) { AppSymbol(SymbolKind.SUN, SolarColors.gradient, size = 24.dp) }
            }
        }
    }
}

// endregion
