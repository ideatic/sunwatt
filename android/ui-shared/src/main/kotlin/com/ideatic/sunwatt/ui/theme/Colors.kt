package com.ideatic.sunwatt.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.ideatic.sunwatt.core.models.PriceLevel

/** Port of the Apple `Theme.Solar` and `Theme.Price` tokens (`DesignSystem/Theme.swift`). */
object SolarColors {
    val amber = Color(red = 1.00f, green = 0.72f, blue = 0.20f)
    val orange = Color(red = 1.00f, green = 0.52f, blue = 0.24f)
    val gradient: Brush get() = Brush.verticalGradient(listOf(amber, orange))

    /** Area fill under the production curve, fading to transparent. */
    val areaFill: Brush
        get() = Brush.verticalGradient(listOf(orange.copy(alpha = 0.45f), amber.copy(alpha = 0.18f), amber.copy(alpha = 0f)))
    val line = orange
}

object PriceColors {
    val green = Color(red = 0.20f, green = 0.78f, blue = 0.45f)
    val yellow = Color(red = 0.98f, green = 0.78f, blue = 0.18f)
    val red = Color(red = 0.96f, green = 0.33f, blue = 0.29f)

    const val PAST_OPACITY = 0.42f
    const val FUTURE_OPACITY = 0.82f

    /** Dark mode needs a higher past opacity: over near-black, low-opacity red or amber turns brown. */
    const val PAST_OPACITY_DARK = 0.72f
    const val MIN_BAR_FRACTION_MINI = 0.07f
    const val MIN_BAR_FRACTION_FULL = 0.035f

    /** Continuous scale for a 0..1 fraction of the day's range: green, yellow, red. */
    fun color(fraction: Double): Color {
        val f = fraction.coerceIn(0.0, 1.0).toFloat()
        return if (f < 0.5f) lerp(green, yellow, f / 0.5f) else lerp(yellow, red, (f - 0.5f) / 0.5f)
    }

    fun color(level: PriceLevel): Color = when (level) {
        PriceLevel.LOW -> green
        PriceLevel.MEDIUM -> yellow
        PriceLevel.HIGH -> red
    }

    fun pastOpacity(dark: Boolean): Float = if (dark) PAST_OPACITY_DARK else PAST_OPACITY

    /** Colour of a past bar: in dark mode the hue is first blended toward a neutral grey. */
    fun pastColor(base: Color, dark: Boolean): Color = if (dark) lerp(base, Color(0.62f, 0.62f, 0.62f), 0.52f) else base
}

/** Port of `AppPalette` (`Views/App/AppTheme.swift`): the colours that depend on light or dark. */
data class AppPalette(
    val isDark: Boolean,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val card: Color,
    val cardStroke: Color,
    val cardShadow: Color,
    /** Inset fields, chips and secondary buttons sitting on a card. */
    val fill: Color,
    val hairline: Color,
    /** Orange used for text and symbols; darker in light mode to keep contrast on white. */
    val accentText: Color,
    val calloutFill: Color,
    /** Label colour on the amber primary button. */
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val textPrimary: Color,
    val textSecondary: Color,
) {
    val backgroundGradient: Brush get() = Brush.verticalGradient(listOf(backgroundTop, backgroundBottom))

    companion object {
        val light = AppPalette(
            isDark = false,
            backgroundTop = Color(0.975f, 0.965f, 0.945f),
            backgroundBottom = Color(0.94f, 0.93f, 0.91f),
            card = Color.White,
            cardStroke = Color.Black.copy(alpha = 0.05f),
            cardShadow = Color(0.4f, 0.28f, 0.1f).copy(alpha = 0.10f),
            fill = Color.Black.copy(alpha = 0.045f),
            hairline = Color.Black.copy(alpha = 0.08f),
            accentText = Color(0.74f, 0.32f, 0.0f),
            calloutFill = Color.White,
            onAccent = Color(0.22f, 0.09f, 0.0f),
            success = Color(0.10f, 0.58f, 0.30f),
            warning = Color(0.78f, 0.38f, 0.0f),
            textPrimary = Color(0.07f, 0.07f, 0.07f),
            textSecondary = Color(0.07f, 0.07f, 0.07f).copy(alpha = 0.6f),
        )

        val dark = AppPalette(
            isDark = true,
            backgroundTop = Color(0.055f, 0.055f, 0.055f),
            backgroundBottom = Color(0.02f, 0.02f, 0.02f),
            card = Color(0.115f, 0.115f, 0.115f),
            cardStroke = Color.White.copy(alpha = 0.08f),
            cardShadow = Color.Transparent,
            fill = Color.White.copy(alpha = 0.07f),
            hairline = Color.White.copy(alpha = 0.10f),
            accentText = SolarColors.amber,
            calloutFill = Color(0.20f, 0.20f, 0.20f),
            onAccent = Color(0.22f, 0.09f, 0.0f),
            success = PriceColors.green,
            warning = SolarColors.amber,
            textPrimary = Color.White,
            textSecondary = Color.White.copy(alpha = 0.6f),
        )
    }
}
