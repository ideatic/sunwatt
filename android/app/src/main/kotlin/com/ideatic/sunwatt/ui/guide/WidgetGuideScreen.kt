package com.ideatic.sunwatt.ui.guide

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon as MaterialIcon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.tile.PriceTileService
import com.ideatic.sunwatt.tile.SolarTileService
import com.ideatic.sunwatt.ui.LocalAppViewModel
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.setup.Hairline
import com.ideatic.sunwatt.ui.setup.ScreenScaffold
import com.ideatic.sunwatt.ui.setup.SoftIconButton
import com.ideatic.sunwatt.ui.setup.StepNumber
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.SunWattCard
import com.ideatic.sunwatt.ui.theme.appPalette

/** One step of the guide. */
private class Step(val title: @Composable () -> String, val detail: @Composable () -> String)

private val steps = listOf(
    Step({ stringResource(R.string.app_touch_and_hold_the_home_screen_d42da1) }, { stringResource(R.string.android_press_an_empty_spot_until_a) }),
    Step({ stringResource(R.string.android_tap_widgets) }, { stringResource(R.string.android_it_is_in_the_menu_that) }),
    Step({ stringResource(R.string.app_search_for_sunwatt) }, { stringResource(R.string.app_type_the_name_in_the_search_6c225c) }),
    Step({ stringResource(R.string.app_pick_a_size) }, { stringResource(R.string.app_swipe_to_choose_small_medium_or) }),
    Step({ stringResource(R.string.android_drag_it_to_the_home_screen) }, { stringResource(R.string.android_touch_and_hold_the_widget_then) }),
)

/**
 * How to add the widgets. Android lets an app ask the launcher to place a widget, so the guide offers a button per size
 * (when the launcher supports it) next to the manual steps, and the Quick Settings tiles (add buttons on Android 13+).
 */
@Composable
fun WidgetGuideScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    ScreenScaffold(stringResource(R.string.app_add_the_widget), onBack, modifier) { WidgetGuideContent() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetGuideContent() {
    val vm = LocalAppViewModel.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = appPalette
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        RoundedText(stringResource(R.string.app_the_system_adds_widgets_not_apps), 15, color = palette.textSecondary)
        val count = ui.widgetCount
        if (count != null && count > 0) {
            SunWattCard(padding = Spacing.m + 2.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    MaterialIcon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = palette.success)
                    RoundedText(stringResource(R.string.app_widget_added), 15, weight = FontWeight.Bold)
                    Box(Modifier.weight(1f))
                    RoundedText(stringResource(R.string.app_active_widgets, count), 13, color = palette.textSecondary)
                }
            }
        }
        WidgetStage()
        PinWidgetCard()
        SunWattCard(padding = Spacing.m) {
            RoundedText(stringResource(R.string.app_home_screen), 13, Modifier.padding(start = Spacing.xs, bottom = Spacing.xs), weight = FontWeight.Bold, color = palette.textSecondary)
            steps.forEachIndexed { index, step ->
                Row(Modifier.padding(vertical = Spacing.s + 2.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.Top) {
                    StepNumber(index + 1, Modifier.padding(top = 2.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        RoundedText(step.title(), 16, weight = FontWeight.SemiBold)
                        RoundedText(step.detail(), 13, color = palette.textSecondary)
                    }
                }
                if (index != steps.lastIndex) Hairline(Modifier.padding(start = 26.dp + Spacing.m))
            }
        }
        RoundedText(stringResource(R.string.app_more_ways_to_use_sunwatt), 15, Modifier.padding(horizontal = Spacing.xs), weight = FontWeight.Bold)
        TilesCard(context)
        RoundedText(stringResource(R.string.app_widgets_refresh_about_every_15_minutes), 12, Modifier.padding(horizontal = Spacing.xs), color = palette.textSecondary)
    }
}

/** The Quick Settings tiles: Android 13 and later can ask the system to add them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TilesCard(context: Context) {
    val palette = appPalette
    SunWattCard(padding = Spacing.m + 2.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            RoundedText(stringResource(R.string.android_quick_settings_tiles), 16, weight = FontWeight.Bold)
            RoundedText(stringResource(R.string.android_current_price_and_solar_power_in), 13, color = palette.textSecondary)
            if (Build.VERSION.SDK_INT >= 33) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    SoftIconButton(stringResource(R.string.android_add_the_price_tile), { requestTile(context, PriceTileService::class.java, R.drawable.widget_ic_bolt) }, compact = true)
                    SoftIconButton(stringResource(R.string.android_add_the_solar_tile), { requestTile(context, SolarTileService::class.java, R.drawable.widget_ic_sun) }, compact = true)
                }
            } else {
                RoundedText(stringResource(R.string.android_swipe_down_twice_from_the_top), 13, color = palette.textSecondary)
            }
        }
    }
}

/** Asks the system to add a tile (the user confirms in a system dialog). The label is the one of the service in the manifest. */
private fun requestTile(context: Context, service: Class<*>, icon: Int) {
    if (Build.VERSION.SDK_INT < 33) return
    val component = ComponentName(context, service)
    val label = context.packageManager.getServiceInfo(component, 0).loadLabel(context.packageManager)
    context.getSystemService(StatusBarManager::class.java)
        ?.requestAddTileService(component, label, Icon.createWithResource(context, icon), context.mainExecutor) { }
}
