package com.ideatic.sunwatt.wear.demo

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.wear.complication.PriceComplicationModel
import com.ideatic.sunwatt.wear.complication.PriceComplicationService
import com.ideatic.sunwatt.wear.complication.SolarComplicationModel
import com.ideatic.sunwatt.wear.complication.SolarComplicationService

/**
 * Debug only: draws the data of one complication (extra `service` = `price` or `solar`) for one sample scenario (extra
 * `scenario`: `day`, `night`, `stale`, `error`, `unconfigured`) in its three types, the way a watch face draws them, so
 * the complications can be checked without setting up a watch face.
 */
class ComplicationPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario") ?: "day"
        val entry: SunWattEntry = if (scenario == "unconfigured") {
            SampleData.notConfiguredEntry()
        } else {
            SampleData.entry(SampleData.Scenario.entries.first { it.name.equals(scenario, ignoreCase = true) })
        }
        val price = intent.getStringExtra("service") != "solar"
        fun data(type: ComplicationType): ComplicationData? =
            if (price) {
                PriceComplicationService.build(this, type, PriceComplicationModel(entry))
            } else {
                SolarComplicationService.build(this, type, SolarComplicationModel(entry))
            }
        setContentView(PreviewView(this, listOf(ComplicationType.SHORT_TEXT, ComplicationType.RANGED_VALUE, ComplicationType.LONG_TEXT).map { data(it) }))
    }

    private class PreviewView(context: Context, private val data: List<ComplicationData?>) : View(context) {
        private val drawables = data.map { item ->
            ComplicationDrawable(context).apply {
                if (item != null) setComplicationData(item, false)
            }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val w = width
            // Short text and ranged value side by side, the long text below them.
            val size = (w * 0.26f).toInt()
            val top = (w * 0.30f).toInt()
            drawables[0].bounds = Rect(w / 2 - size - 12, top, w / 2 - 12, top + size)
            drawables[1].bounds = Rect(w / 2 + 12, top, w / 2 + 12 + size, top + size)
            drawables[2].bounds = Rect(w / 2 - (w * 0.34f).toInt(), top + size + 24, w / 2 + (w * 0.34f).toInt(), top + size + 24 + (size * 0.9f).toInt())
            for (d in drawables) d.draw(canvas)
        }
    }
}
