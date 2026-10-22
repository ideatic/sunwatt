package com.ideatic.sunwatt.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.ui.unit.DpSize
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.ui.charts.ChartStyle
import com.ideatic.sunwatt.ui.charts.ChartVariant
import com.ideatic.sunwatt.ui.charts.PriceChartModel
import com.ideatic.sunwatt.ui.charts.PriceChartRenderer
import com.ideatic.sunwatt.ui.charts.SolarChartModel
import com.ideatic.sunwatt.ui.charts.SolarChartRenderer
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Charts of the widgets as bitmaps (RemoteViews cannot draw them). Each bitmap is drawn at the size of its slot in
 * pixels and is kept small: RemoteViews has a memory limit for bitmaps, so the long side is capped at [MAX_SIDE_PX] and
 * the drawing scales down with it. The colours follow the system theme at draw time (a bitmap cannot change with the
 * theme later; the next refresh redraws it).
 */
object WidgetCharts {
    private const val MAX_SIDE_PX = 900

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private class Slot(val widthPx: Int, val heightPx: Int, val density: Float)

    private fun slot(context: Context, size: DpSize): Slot? {
        val density = context.resources.displayMetrics.density
        var width = size.width.value * density
        var height = size.height.value * density
        if (width < 8f || height < 8f) return null
        val scale = min(1f, MAX_SIDE_PX / max(width, height))
        width *= scale
        height *= scale
        return Slot(width.toInt(), height.toInt(), density * scale)
    }

    /**
     * Draws into a bitmap of the slot. The drawing area keeps a margin all round: the baseline of the production curve and
     * the thin bars of cheap hours sit on the bottom edge and would be cut in half by the bitmap border.
     */
    private fun draw(slot: Slot, block: (Canvas, RectF) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(slot.widthPx, slot.heightPx, Bitmap.Config.ARGB_8888)
        val margin = 2f * slot.density
        block(Canvas(bitmap), RectF(margin, 0f, slot.widthPx - margin, slot.heightPx - margin))
        return bitmap
    }

    fun price(context: Context, model: PriceModel, size: DpSize, variant: ChartVariant): Bitmap? {
        val series = model.series ?: return null
        if (model.points.isEmpty()) return null
        val slot = slot(context, size) ?: return null
        val chart = PriceChartModel.from(series, model.points, model.now, variant)
        val style = ChartStyle(variant, isDark(context), slot.density, java.util.Locale.getDefault())
        return draw(slot) { canvas, bounds -> PriceChartRenderer.draw(canvas, bounds, chart, style) }
    }

    fun solar(context: Context, snapshot: SolarSnapshot, now: Instant, size: DpSize, variant: ChartVariant): Bitmap? {
        val slot = slot(context, size) ?: return null
        val chart = SolarChartModel.from(snapshot, now, variant)
        val style = ChartStyle(variant, isDark(context), slot.density, java.util.Locale.getDefault())
        return draw(slot) { canvas, bounds -> SolarChartRenderer.draw(canvas, bounds, chart, style) }
    }
}
