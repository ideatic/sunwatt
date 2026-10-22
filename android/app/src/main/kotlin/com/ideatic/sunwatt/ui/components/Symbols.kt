package com.ideatic.sunwatt.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ideatic.sunwatt.ui.theme.SolarColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The few symbols the app draws itself (Apple SF Symbols have no Android twin in the core icon set). */
enum class SymbolKind { SUN, MOON, BOLT, LINK, CLOCK, CLOCK_BACK }

/** A symbol filled with [brush]; it fills a square of [size]. */
@Composable
fun AppSymbol(kind: SymbolKind, brush: Brush, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) { drawSymbol(kind, brush, this.size.minDimension) }
}

@Composable
fun AppSymbol(kind: SymbolKind, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) =
    AppSymbol(kind, Brush.linearGradient(listOf(color, color)), modifier, size)

/** The sun symbol of the solar card: amber to orange. */
@Composable
fun SolarSymbol(night: Boolean, secondary: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    if (night) AppSymbol(SymbolKind.MOON, secondary, modifier, size) else AppSymbol(SymbolKind.SUN, SolarColors.gradient, modifier, size)
}

private fun DrawScope.drawSymbol(kind: SymbolKind, brush: Brush, s: Float) {
    when (kind) {
        SymbolKind.SUN -> {
            val c = Offset(s / 2, s / 2)
            drawCircle(brush, radius = s * 0.21f, center = c)
            val stroke = s * 0.085f
            for (i in 0 until 8) {
                val a = i * PI / 4
                val from = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (s * 0.33f)
                val to = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (s * 0.47f)
                drawLine(brush, from, to, strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
        SymbolKind.MOON -> {
            val moon = Path().apply { addOval(Rect(Offset(s * 0.18f, s * 0.14f), Size(s * 0.66f, s * 0.66f))) }
            val bite = Path().apply { addOval(Rect(Offset(s * 0.38f, s * 0.04f), Size(s * 0.62f, s * 0.62f))) }
            drawPath(Path.combine(PathOperation.Difference, moon, bite), brush)
            drawCircle(brush, radius = s * 0.045f, center = Offset(s * 0.7f, s * 0.78f))
            drawCircle(brush, radius = s * 0.03f, center = Offset(s * 0.88f, s * 0.5f))
        }
        SymbolKind.BOLT -> {
            val p = Path().apply {
                moveTo(s * 0.58f, s * 0.04f)
                lineTo(s * 0.2f, s * 0.56f)
                lineTo(s * 0.46f, s * 0.56f)
                lineTo(s * 0.38f, s * 0.96f)
                lineTo(s * 0.8f, s * 0.4f)
                lineTo(s * 0.53f, s * 0.4f)
                close()
            }
            drawPath(p, brush, style = Fill)
            drawPath(p, brush, style = Stroke(s * 0.05f, join = StrokeJoin.Round))
        }
        SymbolKind.LINK -> rotate(-45f) {
            val w = s * 0.5f
            val h = s * 0.26f
            val stroke = Stroke(s * 0.085f, cap = StrokeCap.Round)
            drawRoundRect(brush, Offset(s / 2 - w * 0.92f, s / 2 - h / 2), Size(w, h), CornerRadius(h / 2), style = stroke)
            drawRoundRect(brush, Offset(s / 2 - w * 0.08f, s / 2 - h / 2), Size(w, h), CornerRadius(h / 2), style = stroke)
        }
        SymbolKind.CLOCK, SymbolKind.CLOCK_BACK -> {
            val c = Offset(s / 2, s / 2)
            val stroke = Stroke(s * 0.11f, cap = StrokeCap.Round)
            drawCircle(brush, radius = s * 0.4f, center = c, style = stroke)
            drawLine(brush, c, c + Offset(0f, -s * 0.22f), strokeWidth = s * 0.11f, cap = StrokeCap.Round)
            drawLine(brush, c, c + Offset(s * 0.16f, s * 0.08f), strokeWidth = s * 0.11f, cap = StrokeCap.Round)
        }
    }
}
