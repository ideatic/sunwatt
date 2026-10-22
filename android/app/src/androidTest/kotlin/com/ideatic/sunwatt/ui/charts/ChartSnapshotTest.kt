package com.ideatic.sunwatt.ui.charts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import com.ideatic.sunwatt.core.models.PriceRegion
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

/**
 * Renders the charts to PNGs in `getExternalFilesDir("charts")` so they can be pulled and compared with the Apple
 * renders: `adb pull /sdcard/Android/data/com.ideatic.sunwatt/files/charts`.
 */
class ChartSnapshotTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val density = context.resources.displayMetrics.density
    private val day = LocalDate.of(2026, 10, 17)
    private val now = ChartSamples.at(day, 13.5)

    private fun px(dp: Int) = (dp * density).toInt()

    /** Draws [draw] over the card colour of the theme so light and dark renders read like the app. */
    private fun save(name: String, dark: Boolean, widthDp: Int, heightDp: Int, draw: (Canvas, RectF) -> Unit) {
        val bitmap = Bitmap.createBitmap(px(widthDp), px(heightDp), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) 0xFF1D1D1D.toInt() else 0xFFFFFFFF.toInt())
        draw(canvas, RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
        val dir = context.getExternalFilesDir("charts")!!
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun renderAll(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val series = ChartSamples.priceSeries(day)
        val solar = ChartSamples.solarSnapshot(day, 13.5)
        val night = ChartSamples.nightSnapshot(day)
        val sizes = listOf(
            Triple(ChartVariant.WIDGET_SMALL, 140, 70),
            Triple(ChartVariant.WIDGET_MEDIUM, 300, 70),
            Triple(ChartVariant.WIDGET_LARGE, 300, 110),
            Triple(ChartVariant.APP, 340, 220),
        )
        for ((variant, w, h) in sizes) {
            val style = ChartStyle(variant, dark, density)
            val name = variant.name.lowercase()
            val price = PriceChartModel.from(series, now, PriceWindow.Day, variant)
            save("price-$name-$mode", dark, w, h) { c, r -> PriceChartRenderer.draw(c, r, price, style) }
            val curve = SolarChartModel.from(solar, now, variant)
            save("solar-$name-$mode", dark, w, h) { c, r -> SolarChartRenderer.draw(c, r, curve, style) }
            val nightCurve = SolarChartModel.from(night, ChartSamples.at(day, 23.0), variant)
            save("solar-night-$name-$mode", dark, w, h) { c, r -> SolarChartRenderer.draw(c, r, nightCurve, style) }
        }
        // App selection states.
        val appStyle = ChartStyle(ChartVariant.APP, dark, density)
        val price = PriceChartModel.from(series, now, PriceWindow.Day, ChartVariant.APP)
        save("price-app-selected-$mode", dark, 340, 220) { c, r -> PriceChartRenderer.draw(c, r, price, appStyle, selected = 19) }
        val curve = SolarChartModel.from(solar, now, ChartVariant.APP)
        save("solar-app-selected-$mode", dark, 340, 220) { c, r -> SolarChartRenderer.draw(c, r, curve, appStyle, selected = curve.points[60]) }
        // A DST day (25 hours) and a 15-minute series shown hourly.
        val fall = LocalDate.of(2026, 10, 25)
        val dst = PriceChartModel.from(ChartSamples.priceSeries(fall), ChartSamples.at(fall, 12.0), PriceWindow.Day, ChartVariant.WIDGET_LARGE)
        save("price-dst25-$mode", dark, 300, 110) { c, r -> PriceChartRenderer.draw(c, r, dst, ChartStyle(ChartVariant.WIDGET_LARGE, dark, density)) }
        val quarter = PriceChartModel.from(ChartSamples.priceSeries(day, PriceRegion.PORTUGAL, quarterHourly = true), now, PriceWindow.Day, ChartVariant.WIDGET_LARGE)
        save("price-quarter-$mode", dark, 300, 110) { c, r -> PriceChartRenderer.draw(c, r, quarter, ChartStyle(ChartVariant.WIDGET_LARGE, dark, density)) }
        // Negative prices.
        val negative = PriceChartModel.from(ChartSamples.priceSeries(day) { if (it in 11..15) -0.02 else 0.08 + it * 0.002 }, now, PriceWindow.Day, ChartVariant.APP)
        save("price-negative-$mode", dark, 340, 220) { c, r -> PriceChartRenderer.draw(c, r, negative, appStyle) }
        // Bitmap path used by widgets.
        val bmp = PriceChartRenderer.bitmap(px(300), px(110), PriceChartModel.from(series, now, PriceWindow.Day, ChartVariant.WIDGET_LARGE), ChartStyle(ChartVariant.WIDGET_LARGE, dark, density))
        assertTrue(bmp.width == px(300))
    }

    @Test
    fun renderLight() = renderAll(dark = false)

    @Test
    fun renderDark() = renderAll(dark = true)
}
