package com.ideatic.sunwatt.ui.charts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.ui.charts.ChartDrawing.circle
import com.ideatic.sunwatt.ui.charts.ChartDrawing.fill
import com.ideatic.sunwatt.ui.charts.ChartDrawing.stroke

/**
 * Draws today's production curve on a [Canvas]: smooth area with a fading gradient (full variants), the line, a glowing
 * dot at the latest value, the dashed "now" rule, the peak label, the hour axis and the kW scale. Port of
 * `SolarCurveChart` and `InteractiveSolarChart`.
 */
object SolarChartRenderer {
    private const val TOP_INSET_DP = 12f

    /** Draws [model] inside [bounds] (pixels). [selected]: the touched point in the app variant. */
    fun draw(canvas: Canvas, bounds: RectF, model: SolarChartModel, style: ChartStyle, selected: SolarPlotPoint? = null) {
        val palette = ChartPalette(style.dark)
        val frame = ChartDrawing.frame(bounds, model.variant, style.density, TOP_INSET_DP)
        val dp = frame.dp
        val plot = frame.plot
        val isFull = model.isFull
        fun x(p: SolarPlotPoint) = ChartDrawing.xOf(frame, p.time, model.xStart, model.xEnd)
        fun y(v: Double) = ChartDrawing.yOf(frame, v, 0.0, model.yMax)

        if (isFull) {
            val grid = stroke(palette.grid, 0.5f * dp)
            for (t in model.xLabels) {
                val gx = ChartDrawing.xOf(frame, t, model.xStart, model.xEnd)
                canvas.drawLine(gx, plot.top, gx, plot.bottom, grid)
            }
            for (v in model.yTicks) canvas.drawLine(plot.left, y(v), plot.right, y(v), grid)
        } else {
            canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, stroke(palette.ink.withAlpha(0.12f), 0.5f * dp))
        }

        val xs = FloatArray(model.points.size) { x(model.points[it]) }
        val ys = FloatArray(model.points.size) { y(model.points[it].kW) }
        if (xs.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(RectF(plot.left, bounds.top, plot.right + 2f * dp, plot.bottom + 1f * dp))
            val line = ChartDrawing.splinePath(xs, ys)
            if (isFull) {
                // The area ends in a vertical edge under the latest point.
                val area = Path(line)
                area.lineTo(xs.last(), plot.bottom)
                area.lineTo(xs.first(), plot.bottom)
                area.close()
                val top = ys.min()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.FILL
                    shader = LinearGradient(
                        0f, top, 0f, plot.bottom,
                        intArrayOf(palette.solarOrange.withAlpha(0.45f), palette.solarAmber.withAlpha(0.18f), palette.solarAmber.withAlpha(0f)),
                        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawPath(area, paint)
            }
            canvas.drawPath(line, stroke(palette.solarLine, (if (isFull) 2f else 1.8f) * dp))
            canvas.restore()
        }

        if (isFull && selected == null && model.nowInDomain) {
            val nx = ChartDrawing.xOfSeconds(frame, model.now.epochSecond.toDouble(), model.xStart, model.xEnd)
            canvas.drawLine(nx, plot.top, nx, plot.bottom, stroke(palette.ink.withAlpha(0.35f), 1f * dp, floatArrayOf(3f * dp, 3f * dp)))
        }

        val label = ChartDrawing.text(palette.axisLabel, 11f, dp)
        if (selected == null) {
            model.peak?.let { peak ->
                val paint = ChartDrawing.text(palette.axisLabel, 11f, dp, bold = true)
                paint.textAlign = Paint.Align.CENTER
                val s = "${Format.kW(peak.kW, style.locale)} kW"
                val half = paint.measureText(s) / 2f
                val cx = x(peak).coerceIn(bounds.left + half, bounds.right - half)
                canvas.drawText(s, cx, y(peak.kW) - 2f * dp - paint.fontMetrics.descent, paint)
            }
        }
        model.marked?.let { m ->
            val mx = x(m)
            canvas.drawLine(mx, plot.top, mx, plot.bottom, stroke(palette.ink.withAlpha(0.35f), 1f * dp, floatArrayOf(2f * dp, 2f * dp)))
        }
        if (selected != null) {
            val sx = x(selected)
            canvas.drawLine(sx, plot.top, sx, plot.bottom, stroke(palette.ink.withAlpha(0.30f), 1f * dp))
            drawDot(canvas, sx, y(selected.kW), palette, dp, 150f, 60f, 16f, glow2 = null)
        } else {
            model.dot?.let {
                if (isFull) drawDot(canvas, x(it), y(it.kW), palette, dp, 190f, 60f, 16f, glow2 = 520f)
                else drawDot(canvas, x(it), y(it.kW), palette, dp, 140f, 44f, 11f, glow2 = 380f)
            }
        }

        if (isFull) {
            val top = model.yTicks.lastOrNull()
            for (v in model.yTicks) {
                val s = ChartLayout.axisNumber(v, 1, style.locale) + if (v == top && v > 0) " kW" else ""
                ChartDrawing.drawYLabel(canvas, s, bounds.right, y(v), label)
            }
            ChartDrawing.drawXLabels(
                canvas, frame,
                model.xLabels.map { ChartDrawing.xOf(frame, it, model.xStart, model.xEnd) to Format.hour(it, model.zone) },
                label,
            )
        }
        if (selected != null) {
            ChartDrawing.drawCallout(canvas, frame, palette, model.callout(selected, style.locale), x(selected), plot.top + 2f * dp)
        }
    }

    /** Glow, halo, dot and white centre (Swift `symbolSize` areas in pt²). */
    private fun drawDot(
        canvas: Canvas, cx: Float, cy: Float, palette: ChartPalette, dp: Float,
        halo: Float, dot: Float, centre: Float, glow2: Float?,
    ) {
        if (glow2 != null) circle(canvas, cx, cy, glow2, dp, fill(palette.solarAmber.withAlpha(0.16f)))
        circle(canvas, cx, cy, halo, dp, fill(palette.solarAmber.withAlpha(0.35f)))
        circle(canvas, cx, cy, dot, dp, fill(palette.solarLine))
        circle(canvas, cx, cy, centre, dp, fill(0xFFFFFFFF.toInt()))
    }

    /** The plotted point nearest to pixel [x], within 20 minutes, or null outside the plot's horizontal range. */
    fun curvePointAt(x: Float, bounds: RectF, model: SolarChartModel, style: ChartStyle): SolarPlotPoint? =
        curvePointAt(x, bounds.left, bounds.right, model, style.density)

    /** Same with the horizontal bounds as plain numbers (JVM tests). */
    fun curvePointAt(x: Float, boundsLeft: Float, boundsRight: Float, model: SolarChartModel, density: Float): SolarPlotPoint? {
        val right = ChartDrawing.plotRight(boundsRight, model.variant, density)
        if (x < boundsLeft || x > right || right <= boundsLeft) return null
        val seconds = model.xStart.epochSecond + ((x - boundsLeft) / (right - boundsLeft) * (model.xEnd.epochSecond - model.xStart.epochSecond)).toLong()
        return model.nearest(java.time.Instant.ofEpochSecond(seconds))
    }

    fun bitmap(widthPx: Int, heightPx: Int, model: SolarChartModel, style: ChartStyle, selected: SolarPlotPoint? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), model, style, selected)
        return bitmap
    }
}
