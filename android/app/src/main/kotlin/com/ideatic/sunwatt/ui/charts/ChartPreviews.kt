package com.ideatic.sunwatt.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ideatic.sunwatt.ui.theme.AppPalette
import java.time.LocalDate

private val sampleDay = LocalDate.of(2026, 10, 17)
private val sampleNow = ChartSamples.at(sampleDay, 13.5)

@Composable
private fun ChartSheet(dark: Boolean) {
    val palette = if (dark) AppPalette.dark else AppPalette.light
    val series = ChartSamples.priceSeries(sampleDay)
    val price = PriceChartModel.from(series, sampleNow, PriceWindow.Day, ChartVariant.APP)
    val solar = SolarChartModel.from(ChartSamples.solarSnapshot(sampleDay), sampleNow, ChartVariant.APP)
    val priceStyle = rememberChartStyle(ChartVariant.APP, dark)
    Column(Modifier.background(palette.card).padding(16.dp)) {
        SolarCurveChart(solar, priceStyle, null, {}, Modifier.fillMaxWidth().height(180.dp))
        PriceBarChart(price, priceStyle, null, {}, Modifier.fillMaxWidth().height(180.dp))
        PriceBarChart(price, priceStyle, 12, {}, Modifier.fillMaxWidth().height(180.dp))
    }
}

@Preview(name = "Charts light", widthDp = 380, heightDp = 620)
@Composable
fun ChartsLightPreview() = ChartSheet(dark = false)

@Preview(name = "Charts dark", widthDp = 380, heightDp = 620)
@Composable
fun ChartsDarkPreview() = ChartSheet(dark = true)
