package com.ideatic.sunwatt.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.setup.SoftIconButton
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.SunWattCard
import com.ideatic.sunwatt.ui.theme.appPalette
import com.ideatic.sunwatt.widget.WidgetPinning
import com.ideatic.sunwatt.widget.WidgetSize

/** One button per widget size; each asks the launcher to place that widget. Call only when [WidgetPinning.canPin]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PinWidgetButtons(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        for ((size, label) in listOf(
            WidgetSize.SMALL to stringResource(R.string.android_small),
            WidgetSize.MEDIUM to stringResource(R.string.android_medium),
            WidgetSize.LARGE to stringResource(R.string.android_large),
        )) {
            SoftIconButton(label, { WidgetPinning.pin(context, size) }, compact = true)
        }
    }
}

/** "Add a widget now": title, text and the size buttons, or an explanation when the launcher cannot pin widgets. */
@Composable
fun PinWidgetCard() {
    val context = LocalContext.current
    val palette = appPalette
    SunWattCard(padding = Spacing.m + 2.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            RoundedText(stringResource(R.string.android_add_a_widget_now), 16, weight = FontWeight.Bold)
            if (WidgetPinning.canPin(context)) {
                RoundedText(stringResource(R.string.android_choose_a_size_your_phone_asks), 13, color = palette.textSecondary)
                PinWidgetButtons()
            } else {
                RoundedText(stringResource(R.string.android_your_home_screen_cannot_add_widgets), 13, color = palette.textSecondary)
            }
        }
    }
}
