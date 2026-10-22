package com.ideatic.sunwatt.wear.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.ui.charts.ChartStyle
import com.ideatic.sunwatt.ui.charts.ChartVariant
import com.ideatic.sunwatt.ui.charts.PriceChartModel
import com.ideatic.sunwatt.ui.charts.PriceChartRenderer
import com.ideatic.sunwatt.ui.charts.SolarChartModel
import com.ideatic.sunwatt.ui.charts.SolarChartRenderer
import java.time.Instant

/** The watch is always dark and shows the small widget look: no axes, no callout (the watch has no room). */
@Composable
private fun rememberWatchChartStyle(): ChartStyle {
    val density = LocalDensity.current.density
    val locale = LocalConfiguration.current.locales[0]
    return remember(density, locale) { ChartStyle(ChartVariant.WIDGET_SMALL, dark = true, density = density, locale = locale) }
}

/**
 * The mini price chart: the `WIDGET_SMALL` look of the shared [PriceChartRenderer], with the current (or [marked]) hour
 * outlined and a marker above it. Taps are reported as a fraction of the width (0 = left edge); drags are left to the
 * pager.
 */
@Composable
fun WatchPriceChart(
    series: PriceSeries,
    points: List<PricePoint>,
    now: Instant,
    marked: PricePoint?,
    onTapFraction: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = rememberWatchChartStyle()
    val model = remember(series, points, now, marked) { PriceChartModel.from(series, points, now, ChartVariant.WIDGET_SMALL, marked) }
    Canvas(
        modifier.pointerInput(points) {
            detectTapGestures { offset -> if (size.width > 0) onTapFraction((offset.x / size.width).toDouble()) }
        },
    ) {
        drawIntoCanvas { PriceChartRenderer.draw(it.nativeCanvas, RectF(0f, 0f, size.width, size.height), model, style) }
    }
}

/**
 * The mini production curve: the `WIDGET_SMALL` look of the shared [SolarChartRenderer], a glowing dot at the latest value
 * (or at the [marked] point, with a dashed rule). Taps are reported as a fraction of the width.
 */
@Composable
fun WatchSolarChart(
    snapshot: SolarSnapshot,
    now: Instant,
    marked: CurvePoint?,
    onTapFraction: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = rememberWatchChartStyle()
    val model = remember(snapshot, now, marked) { SolarChartModel.from(snapshot, now, ChartVariant.WIDGET_SMALL, marked) }
    Canvas(
        modifier.pointerInput(snapshot.curve) {
            detectTapGestures { offset -> if (size.width > 0) onTapFraction((offset.x / size.width).toDouble()) }
        },
    ) {
        drawIntoCanvas { SolarChartRenderer.draw(it.nativeCanvas, RectF(0f, 0f, size.width, size.height), model, style) }
    }
}
