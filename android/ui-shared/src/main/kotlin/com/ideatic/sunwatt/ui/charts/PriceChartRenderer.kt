package com.ideatic.sunwatt.ui.charts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.charts.ChartDrawing.fill
import com.ideatic.sunwatt.ui.charts.ChartDrawing.rounded
import com.ideatic.sunwatt.ui.charts.ChartDrawing.stroke
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the hourly price bars on a [Canvas]: rounded bars coloured green, yellow, red by the day's range; past bars faint;
 * the current hour outlined with a marker above it; minimum bar height; dashed average line; Y scale on the right in the
 * full variants. Port of `PriceBarChart` and `InteractivePriceChart`. The same code draws in Compose (through
 * `drawIntoCanvas`) and into a [Bitmap] for Glance widgets.
 */
object PriceChartRenderer {
    private const val BAR_INSET = 0.09

    /** Draws [model] inside [bounds] (pixels). [selected]: index of the touched bar in the app variant. */
    fun draw(canvas: Canvas, bounds: RectF, model: PriceChartModel, style: ChartStyle, selected: Int? = null) {
        val palette = ChartPalette(style.dark)
        val frame = ChartDrawing.frame(bounds, model.variant, style.density, topInsetDp = 4f)
        val dp = frame.dp
        val plot = frame.plot
        val isFull = model.isFull
        val isApp = model.variant == ChartVariant.APP
        val bars = model.bars
        val duration = (bars.firstOrNull()?.point?.durationSeconds ?: 3600L).toDouble()
        val inset = duration * BAR_INSET
        // Ring around the current bar; wider in the mini variants, where bars are only a few points wide.
        val halo = duration * (if (isFull) 0.025 else 0.05)
        fun x(seconds: Double) = ChartDrawing.xOfSeconds(frame, seconds, model.xStart, model.xEnd)
        fun y(value: Double) = ChartDrawing.yOf(frame, value, model.yMin, model.yMax)

        // Grid and axes.
        if (isFull) {
            val grid = stroke(palette.grid, 0.5f * dp)
            for (t in model.xLabels) canvas.drawLine(x(t.epochSecond.toDouble()), plot.top, x(t.epochSecond.toDouble()), plot.bottom, grid)
            for (v in model.yTicks) canvas.drawLine(plot.left, y(v), plot.right, y(v), grid)
        }

        val current = model.currentIndex?.let { bars.getOrNull(it) }
        val outlined = if (isApp) selected?.let { bars.getOrNull(it) } ?: current else current
        // Band behind the current hour over the whole height (large widget only; the mini looks use outline + marker).
        if (model.variant == ChartVariant.WIDGET_LARGE && current != null) {
            val p = current.point
            val d = inset - halo * 2
            val band = RectF(
                x(p.start.epochSecond + d), plot.top,
                x(p.end.epochSecond - d), y(min(model.yMin, -model.span * 0.03)),
            )
            canvas.save()
            canvas.clipRect(plot)
            rounded(canvas, band, (3f + 2f) * dp, fill(palette.ink.withAlpha(if (style.dark) 0.12f else 0.10f)))
            canvas.restore()
        }
        // Outline: a slightly larger rectangle behind the bar. At least 0.75 dp wide so it reads on narrow bars.
        if (outlined != null) {
            val p = outlined.point
            val pad = model.span * (if (isFull) 0.012 else 0.03)
            val d = inset - halo
            val r = RectF(
                x(p.start.epochSecond + d), y(max(0.0, outlined.end) + pad),
                x(p.end.epochSecond - d), y(min(0.0, outlined.end) - pad),
            )
            val minPad = 0.75f * dp
            r.inset(min(0f, (x(p.start.epochSecond + inset) - r.left) - minPad), 0f)
            rounded(canvas, r, (3f + 1f) * dp, fill(palette.ink.withAlpha(if (isApp) 0.6f else 0.85f)))
        }

        // Bars.
        for ((i, bar) in bars.withIndex()) {
            val p = bar.point
            val r = RectF(
                x(p.start.epochSecond + inset), min(y(0.0), y(bar.end)),
                x(p.end.epochSecond - inset), max(y(0.0), y(bar.end)),
            )
            val color = if (bar.state == PriceBarState.PAST) palette.pastPriceColor(bar.fraction) else palette.priceColor(bar.fraction)
            val alpha = when {
                isApp && selected != null -> if (i == selected) 1f else 0.38f
                bar.state == PriceBarState.PAST -> palette.pastOpacity
                bar.state == PriceBarState.CURRENT -> 1f
                else -> PriceColors.FUTURE_OPACITY
            }
            rounded(canvas, r, 3f * dp, fill(color.withAlpha(alpha)))
        }

        // Marker above the current bar (hidden while a bar is selected in the app).
        if (current != null && (selected == null || !isApp)) {
            val p = current.point
            val cx = x(p.start.epochSecond + duration / 2)
            val top = y(max(0.0, current.end) + model.span * (if (isFull) 0.012 else 0.03))
            drawMarker(canvas, cx, top - 1f * dp, (if (isFull) 7f else 8f) * dp, palette.ink)
        }

        if (isFull && model.average != null) {
            val avgY = y(model.average)
            canvas.drawLine(plot.left, avgY, plot.right, avgY, stroke(palette.ink.withAlpha(0.30f), 0.75f * dp, floatArrayOf(2f * dp, 3f * dp)))
        }

        // Axis labels.
        if (isFull) {
            val label = ChartDrawing.text(palette.axisLabel, 11f, dp)
            val top = model.yTicks.lastOrNull()
            for (v in model.yTicks) {
                // No "0" label: the baseline grid line already shows it.
                if (v == 0.0) continue
                val s = ChartLayout.axisNumber(v, 2, style.locale) + if (v == top && v > 0) " €" else ""
                ChartDrawing.drawYLabel(canvas, s, frame.bounds.right, y(v), label)
            }
            ChartDrawing.drawXLabels(
                canvas, frame,
                model.xLabels.map { x(it.epochSecond.toDouble()) to Format.hour(it, model.zone) },
                label,
            )
        }

        // Selection callout.
        if (isApp && selected != null) {
            val bar = bars.getOrNull(selected)
            val callout = model.callout(selected, style.locale)
            if (bar != null && callout != null) {
                val cx = x(bar.point.start.epochSecond + duration / 2)
                ChartDrawing.drawCallout(canvas, frame, palette, callout, cx, y(max(0.0, bar.end) + model.span * 0.012) - 3f * dp)
            }
        }
    }

    /** Bar index under pixel [x] inside [bounds], or null outside the plot's horizontal range (edges inclusive). */
    fun barIndexAt(x: Float, bounds: RectF, model: PriceChartModel, style: ChartStyle): Int? =
        barIndexAt(x, bounds.left, bounds.right, model, style.density)

    /** Same with the horizontal bounds as plain numbers (JVM tests). */
    fun barIndexAt(x: Float, boundsLeft: Float, boundsRight: Float, model: PriceChartModel, density: Float): Int? {
        if (model.bars.isEmpty()) return null
        val left = boundsLeft
        val right = ChartDrawing.plotRight(boundsRight, model.variant, density)
        if (x < left || x > right || right <= left) return null
        val seconds = model.xStart.epochSecond + (x - left) / (right - left) * (model.xEnd.epochSecond - model.xStart.epochSecond)
        // The right edge belongs to the last bar; a gap between bars (missing hour) takes the next bar.
        val found = model.bars.indexOfFirst { seconds >= it.point.start.epochSecond && seconds < it.point.end.epochSecond }
        if (found >= 0) return found
        val next = model.bars.indexOfFirst { it.point.start.epochSecond >= seconds }
        return if (next >= 0) next else model.bars.lastIndex
    }

    fun bitmap(widthPx: Int, heightPx: Int, model: PriceChartModel, style: ChartStyle, selected: Int? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), model, style, selected)
        return bitmap
    }

    /** Small filled triangle pointing down, its tip at ([cx], [tipY]). */
    private fun drawMarker(canvas: Canvas, cx: Float, tipY: Float, size: Float, color: Int) {
        val path = Path().apply {
            moveTo(cx - size * 0.5f, tipY - size * 0.8f)
            lineTo(cx + size * 0.5f, tipY - size * 0.8f)
            lineTo(cx, tipY)
            close()
        }
        canvas.drawPath(path, fill(color))
    }
}
