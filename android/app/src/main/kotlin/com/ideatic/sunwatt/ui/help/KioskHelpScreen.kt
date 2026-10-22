package com.ideatic.sunwatt.ui.help

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.models.NORTHBOUND_DEFAULT_HOST
import com.ideatic.sunwatt.ui.components.PrimaryButton
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.setup.Hairline
import com.ideatic.sunwatt.ui.setup.ScreenScaffold
import com.ideatic.sunwatt.ui.setup.StepNumber
import com.ideatic.sunwatt.ui.setup.openUrl
import com.ideatic.sunwatt.ui.theme.SolarColors
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.SunWattCard
import com.ideatic.sunwatt.ui.theme.appPalette

private class HelpStep(@StringRes val title: Int, @StringRes val detail: Int)

private val steps = listOf(
    HelpStep(R.string.app_sign_in_to_the_fusionsolar_website, R.string.app_use_a_browser_on_a_computer),
    HelpStep(R.string.app_open_your_plant, R.string.app_pick_your_plant_from_the_list),
    HelpStep(R.string.app_click_kiosk, R.string.app_it_is_at_the_top_of),
    HelpStep(R.string.app_turn_on_the_kiosk_view, R.string.app_switch_the_toggle_on),
    HelpStep(R.string.app_copy_the_link_and_paste_it, R.string.app_copy_the_link_that_appears_then),
)

/** How to get the kiosk link from the FusionSolar website. */
@Composable
fun KioskHelpScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    ScreenScaffold(stringResource(R.string.app_get_the_kiosk_link), onBack, modifier) { KioskHelpContent() }
}

@Composable
fun KioskHelpContent() {
    val palette = appPalette
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        RoundedText(stringResource(R.string.app_sunwatt_reads_your_plant_through_the), 15, color = palette.textSecondary)
        KioskIllustration()
        SunWattCard(padding = Spacing.m + 2.dp) {
            steps.forEachIndexed { index, step ->
                Row(Modifier.padding(vertical = Spacing.s + 1.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.Top) {
                    StepNumber(index + 1)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        RoundedText(stringResource(step.title), 16, weight = FontWeight.SemiBold)
                        RoundedText(stringResource(step.detail), 13, color = palette.textSecondary)
                    }
                }
                if (index != steps.lastIndex) Hairline(Modifier.padding(start = 26.dp + Spacing.m))
            }
        }
        PrimaryButton(stringResource(R.string.app_open_fusionsolar), { openUrl(context, "https://$NORTHBOUND_DEFAULT_HOST") })
        val shape = RoundedCornerShape(22.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(palette.warning.copy(alpha = 0.12f)).padding(Spacing.m + 2.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Icon(Icons.Filled.Lock, null, Modifier.size(20.dp), tint = palette.warning)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                RoundedText(stringResource(R.string.app_keep_the_link_private), 15, weight = FontWeight.Bold)
                RoundedText(stringResource(R.string.app_anyone_with_this_link_can_see), 13, color = palette.textSecondary)
            }
        }
    }
}

/** Simplified drawing of a browser window with the Kiosk button highlighted. Generic on purpose: the real FusionSolar layout changes between versions. */
@Composable
private fun KioskIllustration() {
    val palette = appPalette
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(palette.card).border(BorderStroke(0.75.dp, palette.hairline), shape)) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { Box(Modifier.size(8.dp).clip(CircleShape).background(palette.hairline)) }
            Box(Modifier.weight(1f).clip(CircleShape).background(palette.fill).padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(
                    NORTHBOUND_DEFAULT_HOST, fontFamily = FontFamily.Monospace, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    color = palette.textSecondary, maxLines = 1,
                )
            }
        }
        Hairline()
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { Box(Modifier.size(width = 54.dp, height = 22.dp).clip(CircleShape).background(palette.fill)) }
                Box(Modifier.clip(CircleShape).background(SolarColors.gradient).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    RoundedText("Kiosk", 12, weight = FontWeight.Bold, color = palette.onAccent)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) { Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(6.dp)).background(palette.fill)) }
            }
        }
    }
}
