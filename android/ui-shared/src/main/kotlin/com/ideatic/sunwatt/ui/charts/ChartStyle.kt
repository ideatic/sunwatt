package com.ideatic.sunwatt.ui.charts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.ideatic.sunwatt.ui.theme.AppPalette
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.theme.SolarColors

/**
 * How a chart is drawn. [density] converts the dp values of the Swift charts to pixels (`resources.displayMetrics.density`
 * in a widget bitmap, `LocalDensity.density` in Compose). [dark] picks light or dark colours; widgets are always drawn
 * in full colour (no accented or vibrant mode on Android).
 */
data class ChartStyle(
    val variant: ChartVariant,
    val dark: Boolean,
    val density: Float,
    /** Locale of the axis numbers and callouts. */
    val locale: java.util.Locale = java.util.Locale.getDefault(),
)

/** ARGB colours of the charts (port of `WidgetPalette` full-colour values plus the `Color.primary` / `.secondary` ink). */
internal class ChartPalette(val dark: Boolean) {
    private val app = if (dark) AppPalette.dark else AppPalette.light

    /** `Color.primary`: outlines, rules, marker. */
    val ink: Int = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val axisLabel: Int = ink.withAlpha(0.6f)
    val grid: Int = ink.withAlpha(0.07f)
    val solarLine: Int = SolarColors.line.toArgb()
    val solarAmber: Int = SolarColors.amber.toArgb()
    val solarOrange: Int = SolarColors.orange.toArgb()
    val calloutFill: Int = app.calloutFill.toArgb()
    val hairline: Int = app.hairline.toArgb()
    val calloutText: Int = app.textPrimary.toArgb()
    val calloutSubtext: Int = app.textSecondary.toArgb()

    fun priceColor(fraction: Double): Int = PriceColors.color(fraction).toArgb()
    fun pastPriceColor(fraction: Double): Int = PriceColors.pastColor(PriceColors.color(fraction), dark).toArgb()
    val pastOpacity: Float = PriceColors.pastOpacity(dark)
}

internal fun Int.withAlpha(alpha: Float): Int =
    (this and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)

internal fun Color.argb(): Int = toArgb()
