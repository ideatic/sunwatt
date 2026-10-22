package com.ideatic.sunwatt.ui.charts

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs

/** Style for the current theme and screen density. [dark] comes from the app palette. */
@Composable
fun rememberChartStyle(variant: ChartVariant, dark: Boolean): ChartStyle {
    val density = LocalDensity.current.density
    val locale = LocalConfiguration.current.locales[0]
    return remember(variant, dark, density, locale) { ChartStyle(variant, dark, density, locale) }
}

/**
 * Hourly price bars. [selected]: index of the touched bar (null: none); [onSelect] is called while a finger is down on
 * the chart (the bar under it) and with null on release. Selection only reacts in the [ChartVariant.APP] variant.
 */
@Composable
fun PriceBarChart(
    model: PriceChartModel,
    style: ChartStyle,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectable = model.variant == ChartVariant.APP
    Canvas(
        modifier.chartSelection(
            selectable, model, style,
            hit = { x, bounds -> PriceChartRenderer.barIndexAt(x, bounds, model, style) },
            onSelect = onSelect,
        ),
    ) {
        drawIntoCanvas { PriceChartRenderer.draw(it.nativeCanvas, RectF(0f, 0f, size.width, size.height), model, style, selected) }
    }
}

/** Today's production curve; selection works like [PriceBarChart], with the touched point. */
@Composable
fun SolarCurveChart(
    model: SolarChartModel,
    style: ChartStyle,
    selected: SolarPlotPoint?,
    onSelect: (SolarPlotPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectable = model.variant == ChartVariant.APP
    Canvas(
        modifier.chartSelection(
            selectable, model, style,
            hit = { x, bounds -> SolarChartRenderer.curvePointAt(x, bounds, model, style) },
            onSelect = onSelect,
        ),
    ) {
        drawIntoCanvas { SolarChartRenderer.draw(it.nativeCanvas, RectF(0f, 0f, size.width, size.height), model, style, selected) }
    }
}

/**
 * Press and drag selects through [hit]; release clears. A mostly vertical drag is left to the parent scroll, so a chart
 * inside the dashboard does not trap it.
 */
private fun <T : Any, M : Any> Modifier.chartSelection(
    enabled: Boolean,
    key: M,
    style: ChartStyle,
    hit: (Float, RectF) -> T?,
    onSelect: (T?) -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(key, style) {
        awaitSelectionGestures(hit, onSelect)
    }
}

private suspend fun <T : Any> PointerInputScope.awaitSelectionGestures(hit: (Float, RectF) -> T?, onSelect: (T?) -> Unit) {
    awaitEachGesture {
        val bounds = RectF(0f, 0f, size.width.toFloat(), size.height.toFloat())
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var horizontal = false
        onSelect(hit(down.position.x.coerceIn(0f, bounds.right), bounds))
        while (true) {
            val event = awaitPointerEvent()
            val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (!horizontal && abs(dy) > slop && abs(dy) > abs(dx)) break
            if (abs(dx) > slop) horizontal = true
            if (horizontal) change.consume()
            onSelect(hit(change.position.x.coerceIn(0f, bounds.right), bounds))
        }
        onSelect(null)
    }
}
