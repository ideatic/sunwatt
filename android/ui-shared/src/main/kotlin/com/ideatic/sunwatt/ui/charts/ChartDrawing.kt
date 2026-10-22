package com.ideatic.sunwatt.ui.charts

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Plot area of a chart inside its bounds (axis labels sit in the gutters around it). */
internal class ChartFrame(val bounds: RectF, val plot: RectF, val dp: Float)

internal object ChartDrawing {
    /** Right edge of the plot: the full variants keep a gutter for the Y axis labels (pure, for hit tests). */
    fun plotRight(boundsRight: Float, variant: ChartVariant, density: Float): Float =
        if (!variant.isFull) boundsRight else boundsRight - (if (variant == ChartVariant.APP) 46f else 38f) * density

    fun frame(bounds: RectF, variant: ChartVariant, density: Float, topInsetDp: Float): ChartFrame {
        if (!variant.isFull) return ChartFrame(bounds, RectF(bounds), density)
        val plot = RectF(bounds.left, bounds.top + topInsetDp * density, plotRight(bounds.right, variant, density), bounds.bottom - 18f * density)
        return ChartFrame(bounds, plot, density)
    }

    fun xOf(frame: ChartFrame, time: Instant, start: Instant, end: Instant): Float =
        xOfSeconds(frame, time.epochSecond.toDouble(), start, end)

    fun xOfSeconds(frame: ChartFrame, seconds: Double, start: Instant, end: Instant): Float {
        val span = (end.epochSecond - start.epochSecond).toDouble().coerceAtLeast(1.0)
        return (frame.plot.left + (seconds - start.epochSecond) / span * frame.plot.width()).toFloat()
    }

    fun yOf(frame: ChartFrame, value: Double, min: Double, max: Double): Float {
        val span = (max - min).coerceAtLeast(1e-9)
        return (frame.plot.bottom - (value - min) / span * frame.plot.height()).toFloat()
    }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    fun stroke(color: Int, widthPx: Float, dash: FloatArray? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = widthPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        if (dash != null) pathEffect = DashPathEffect(dash, 0f)
    }

    fun text(color: Int, sizeDp: Float, dp: Float, bold: Boolean = false, medium: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizeDp * dp
            typeface = when {
                bold -> Typeface.create("sans-serif-medium", Typeface.BOLD)
                medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else -> Typeface.create("sans-serif", Typeface.NORMAL)
            }
        }

    fun rounded(canvas: Canvas, r: RectF, radius: Float, paint: Paint) {
        val rr = min(radius, min(r.width(), r.height()) / 2f).coerceAtLeast(0f)
        canvas.drawRoundRect(r, rr, rr, paint)
    }

    /** Diameter of a Swift Charts circle symbol of area [symbolSize]. */
    fun symbolDiameter(symbolSize: Float): Float = 2f * sqrt(symbolSize / Math.PI.toFloat())

    fun circle(canvas: Canvas, x: Float, y: Float, symbolSize: Float, dp: Float, paint: Paint) =
        canvas.drawCircle(x, y, symbolDiameter(symbolSize) * dp / 2f, paint)

    /** Dashed vertical or horizontal rule. */
    fun line(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) = canvas.drawLine(x0, y0, x1, y1, paint)

    /** Right-aligned label centred on [y], ending at [right]. */
    fun drawYLabel(canvas: Canvas, label: String, right: Float, y: Float, paint: Paint) {
        paint.textAlign = Paint.Align.RIGHT
        // Glyphs such as the euro sign overhang their advance: keep 3 dp inside the bounds.
        val m = paint.fontMetrics
        canvas.drawText(label, right - 3f * (paint.textSize / 11f), y - (m.ascent + m.descent) / 2f, paint)
    }

    /**
     * X axis labels centred under their tick, shifted to stay inside the bounds and skipped when they would touch the
     * previous label.
     */
    fun drawXLabels(
        canvas: Canvas,
        frame: ChartFrame,
        labels: List<Pair<Float, String>>,
        paint: Paint,
    ) {
        paint.textAlign = Paint.Align.LEFT
        val m = paint.fontMetrics
        val baseline = frame.plot.bottom + 4f * frame.dp - m.ascent
        var lastRight = Float.NEGATIVE_INFINITY
        for ((x, label) in labels) {
            val w = paint.measureText(label)
            val left = (x - w / 2f).coerceIn(frame.bounds.left, max(frame.bounds.left, frame.bounds.right - w))
            if (left < lastRight + 4f * frame.dp) continue
            canvas.drawText(label, left, baseline, paint)
            lastRight = left + w
        }
    }

    /** Floating label over the chart, centred on [anchorX], bottom at [bottomY], kept inside [frame] bounds. */
    fun drawCallout(
        canvas: Canvas,
        frame: ChartFrame,
        palette: ChartPalette,
        callout: ChartCallout,
        anchorX: Float,
        bottomY: Float,
    ) {
        val dp = frame.dp
        val title = text(palette.calloutText, 13f, dp, bold = true)
        val detail = text(palette.calloutSubtext, 11f, dp, medium = true)
        val dot = if (callout.tintFraction != null) 7f * dp + 5f * dp else 0f
        val w = max(title.measureText(callout.title) + dot, detail.measureText(callout.detail)) + 18f * dp
        val h = (title.fontSpacing + detail.fontSpacing) + 10f * dp - 4f * dp
        val left = (anchorX - w / 2f).coerceIn(frame.bounds.left, max(frame.bounds.left, frame.plot.right - w))
        val top = max(frame.bounds.top, bottomY - h)
        val r = RectF(left, top, left + w, top + h)
        rounded(canvas, r, 9f * dp, fill(palette.calloutFill))
        rounded(canvas, r, 9f * dp, stroke(palette.hairline, 0.5f * dp))
        var tx = r.left + 9f * dp
        val m = title.fontMetrics
        val titleBase = r.top + 5f * dp - m.ascent
        if (callout.tintFraction != null) {
            canvas.drawCircle(tx + 3.5f * dp, titleBase + (m.ascent + m.descent) / 2f + 1f * dp, 3.5f * dp, fill(palette.priceColor(callout.tintFraction)))
            tx += dot
        }
        canvas.drawText(callout.title, tx, titleBase, title)
        val dm = detail.fontMetrics
        canvas.drawText(callout.detail, r.left + 9f * dp, titleBase + m.descent + 1f * dp - dm.ascent, detail)
    }

    /** Catmull-Rom spline through the points as cubic Béziers (what Swift Charts' `.catmullRom` draws). */
    fun splinePath(xs: FloatArray, ys: FloatArray): Path {
        val path = Path()
        if (xs.isEmpty()) return path
        path.moveTo(xs[0], ys[0])
        for (i in 0 until xs.size - 1) {
            val p0 = max(i - 1, 0)
            val p3 = min(i + 2, xs.size - 1)
            path.cubicTo(
                xs[i] + (xs[i + 1] - xs[p0]) / 6f, ys[i] + (ys[i + 1] - ys[p0]) / 6f,
                xs[i + 1] - (xs[p3] - xs[i]) / 6f, ys[i + 1] - (ys[p3] - ys[i]) / 6f,
                xs[i + 1], ys[i + 1],
            )
        }
        return path
    }
}
