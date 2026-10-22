package com.ideatic.sunwatt.ui.guide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.ui.components.AppSymbol
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.components.SymbolKind
import com.ideatic.sunwatt.ui.setup.ShrinkText
import com.ideatic.sunwatt.ui.theme.AppPalette
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.theme.SolarColors
import com.ideatic.sunwatt.ui.theme.Spacing

/** The widget on a wallpaper-like backdrop, so the guide and the onboarding show what the result looks like. Drawn, with sample figures. */
@Composable
fun WidgetStage(modifier: Modifier = Modifier, height: Dp = 188.dp, showsMedium: Boolean = true) {
    val card = AppPalette.light
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0.98f, 0.62f, 0.40f), Color(0.55f, 0.30f, 0.62f), Color(0.20f, 0.22f, 0.48f)))),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(180.dp).align(Alignment.TopStart).padding(start = 0.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            MiniCard(Modifier.size(width = 118.dp, height = 124.dp)) {
                AppSymbol(SymbolKind.SUN, SolarColors.gradient, size = 18.dp)
                RoundedText("5.3 kW", 22, weight = FontWeight.Bold, color = card.textPrimary, tabularDigits = true, maxLines = 1)
                RoundedText(stringResource(R.string.app_kwh_today, "16.2"), 10, color = card.textSecondary, maxLines = 1)
                Pill("0.018 €/kWh")
            }
            if (showsMedium) {
                MiniCard(Modifier.size(width = 170.dp, height = 96.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            AppSymbol(SymbolKind.SUN, SolarColors.gradient, size = 16.dp)
                            RoundedText("5.3 kW", 18, weight = FontWeight.Bold, color = card.textPrimary, tabularDigits = true, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Pill("0.018 €/kWh")
                            Bars()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniCard(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.94f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

@Composable
private fun Pill(text: String) {
    ShrinkText(
        text, 11f, weight = FontWeight.Bold, color = Color(0.07f, 0.4f, 0.2f),
        modifier = Modifier.clip(CircleShape).background(PriceColors.green.copy(alpha = 0.22f)).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun Bars() {
    Canvas(Modifier.width(80.dp).height(34.dp).padding(top = 4.dp)) {
        val n = 14
        val w = size.width / (n * 1.5f)
        for (i in 0 until n) {
            val f = 0.25f + 0.75f * kotlin.math.abs(kotlin.math.sin(i * 0.55f))
            drawRoundRect(
                PriceColors.color((i / (n - 1f)).toDouble()), Offset(i * w * 1.5f, size.height * (1 - f)), Size(w, size.height * f), CornerRadius(w / 3),
            )
        }
    }
}
