package com.ideatic.sunwatt.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The small symbols of the pages, drawn from circles, lines and polygons on a 24 x 24 grid (the complications use
 * `res/drawable/ic_*.xml`). Vector paths drawn below their intrinsic size lost half of their outline on the Wear OS 6
 * emulator, so these are plain shapes.
 */
enum class GlyphShape { BOLT, SUN, MOON, WARNING, SETTINGS, HISTORY }

@Composable
fun Glyph(shape: GlyphShape, tint: Color, size: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size.dp)) {
        val u = this.size.width / 24f
        when (shape) {
            GlyphShape.BOLT -> drawPath(
                polygon(u, 14f to 2f, 6f to 13f, 11.5f to 13f, 10f to 22f, 18f to 10.5f, 12.5f to 10.5f), tint,
            )
            GlyphShape.SUN -> {
                drawCircle(tint, 5.2f * u, Offset(12f * u, 12f * u))
                rays(tint, u, 8.2f, 11f)
            }
            GlyphShape.MOON -> {
                drawCircle(tint, 9f * u, Offset(12f * u, 12f * u))
                // The page is black: a black disc cut into the white one leaves the crescent.
                drawCircle(Color.Black, 7.6f * u, Offset(16.2f * u, 8.2f * u))
            }
            GlyphShape.WARNING -> {
                drawPath(polygon(u, 12f to 2.5f, 22.5f to 20.5f, 1.5f to 20.5f), tint)
                drawLine(Color.Black, Offset(12f * u, 9f * u), Offset(12f * u, 14.2f * u), 2.2f * u, StrokeCap.Round)
                drawCircle(Color.Black, 1.2f * u, Offset(12f * u, 17.4f * u))
            }
            GlyphShape.SETTINGS -> {
                drawCircle(tint, 6.2f * u, Offset(12f * u, 12f * u), style = Stroke(3f * u))
                rays(tint, u, 9.2f, 11.5f)
            }
            GlyphShape.HISTORY -> {
                drawCircle(tint, 9f * u, Offset(12f * u, 12f * u), style = Stroke(2.2f * u))
                drawLine(tint, Offset(12f * u, 12f * u), Offset(12f * u, 6.8f * u), 2.2f * u, StrokeCap.Round)
                drawLine(tint, Offset(12f * u, 12f * u), Offset(15.6f * u, 14f * u), 2.2f * u, StrokeCap.Round)
            }
        }
    }
}

private fun polygon(u: Float, vararg points: Pair<Float, Float>): Path = Path().apply {
    moveTo(points[0].first * u, points[0].second * u)
    for (i in 1 until points.size) lineTo(points[i].first * u, points[i].second * u)
    close()
}

/** Eight short rounded rays around the centre of the grid, from radius [from] to [to]. */
private fun DrawScope.rays(tint: Color, u: Float, from: Float, to: Float) {
    for (i in 0 until 8) {
        val angle = i * PI.toFloat() / 4f
        drawLine(
            tint, Offset((12f + from * cos(angle)) * u, (12f + from * sin(angle)) * u),
            Offset((12f + to * cos(angle)) * u, (12f + to * sin(angle)) * u), 2f * u, StrokeCap.Round,
        )
    }
}
