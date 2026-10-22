package com.ideatic.sunwatt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Port of `Theme.Spacing` and `Theme.Radius`. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

object Radius {
    val bar = 3.dp
    val card = 20.dp
}

val LocalAppPalette = staticCompositionLocalOf { AppPalette.light }

/** The palette for the current theme. */
val appPalette: AppPalette
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current

private fun colorScheme(palette: AppPalette) = if (palette.isDark) {
    darkColorScheme(
        primary = SolarColors.amber,
        onPrimary = palette.onAccent,
        secondary = SolarColors.orange,
        background = palette.backgroundBottom,
        onBackground = palette.textPrimary,
        surface = palette.card,
        onSurface = palette.textPrimary,
        onSurfaceVariant = palette.textSecondary,
        surfaceVariant = palette.fill,
        outline = palette.hairline,
        error = PriceColors.red,
    )
} else {
    lightColorScheme(
        primary = SolarColors.orange,
        onPrimary = palette.onAccent,
        secondary = palette.accentText,
        background = palette.backgroundTop,
        onBackground = palette.textPrimary,
        surface = palette.card,
        onSurface = palette.textPrimary,
        onSurfaceVariant = palette.textSecondary,
        surfaceVariant = palette.fill,
        outline = palette.hairline,
        error = PriceColors.red,
    )
}

/** SunWatt theme. No dynamic colour: the palette is the brand (DECISIONS D33). */
@Composable
fun SunWattTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) AppPalette.dark else AppPalette.light
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(colorScheme = colorScheme(palette), typography = SunWattTypography, content = content)
    }
}

/** Page background: soft warm gradient with an amber glow in the top corner and a faint green one at the bottom. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = appPalette
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(palette.backgroundGradient)
                drawGlow(SolarColors.amber.copy(alpha = if (palette.isDark) 0.10f else 0.16f), Offset.Zero, 420.dp.toPx())
                drawGlow(PriceColors.green.copy(alpha = if (palette.isDark) 0.05f else 0.07f), Offset(size.width, size.height), 380.dp.toPx())
            },
    ) { content() }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlow(color: Color, center: Offset, radius: Float) {
    drawRect(Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius))
}
