package com.ideatic.sunwatt.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.theme.SolarColors
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.PriceDayContexts
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.cheapestUpcoming
import com.ideatic.sunwatt.wear.R
import com.ideatic.sunwatt.wear.model.PriceModel
import com.ideatic.sunwatt.wear.model.SolarModel
import com.ideatic.sunwatt.wear.model.StateKind
import com.ideatic.sunwatt.wear.pages.WatchPages
import kotlinx.coroutines.delay

/**
 * Current price (big, coloured by level), today's bars with the current hour marked, cheapest next hours. Tapping a bar
 * shows that hour's price instead; it goes back to now on a second tap, a tap on the current hour, or after
 * [WatchPages.SELECTION_TIMEOUT] (DECISIONS D29).
 */
@Composable
fun WatchPricePage(entry: SunWattEntry) {
    var selected by remember { mutableStateOf<PricePoint?>(null) }
    val base = PriceModel.of(entry)
    // A tapped hour that is no longer in today's bars (after midnight) is dropped.
    val tapped = selected?.takeIf { it in base.points }
    val price = tapped?.let { base.showing(it) } ?: base
    LaunchedEffect(selected) {
        if (selected != null) {
            delay(WatchPages.SELECTION_TIMEOUT)
            selected = null
        }
    }
    PageFrame {
        val series = price.series
        if (series != null && price.isAvailable) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Glyph(GlyphShape.BOLT, price.fraction?.let { PriceColors.color(it) } ?: androidx.compose.ui.graphics.Color.White, 13)
                    CaptionText(stringResource(if (series.region.isRetailTariff) R.string.widgets_pvpc else R.string.widgets_day_ahead), Modifier.weight(1f))
                    price.staleSince?.let { DataTimeMarker(it, stale = true, zone = price.timeZone) }
                }
                FigureText(
                    Format.eurPerKWh(price.current?.eurPerKWh), "€/kWh", 28f,
                    price.fraction?.let { PriceColors.color(it) } ?: androidx.compose.ui.graphics.Color.White,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (tapped != null) {
                        // The tapped hour, so the figure is not read as the price now.
                        CaptionText(Format.hour(tapped.start, series.region.timeZone), color = androidx.compose.ui.graphics.Color.White, bold = true)
                    }
                    LevelLabel(price.level)
                }
                WatchPriceChart(
                    series, price.points, entry.date, tapped,
                    onTapFraction = { fraction ->
                        val point = WatchPages.pricePointAt(fraction, price.points)
                        if (point != null) {
                            // The same bar again, or the current hour: back to now.
                            selected = if (point == selected || entry.date in point) null else point
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(36.dp).padding(top = 4.dp),
                )
                Cheapest(series, entry)
            }
        } else {
            StateView(StateKind.PRICE_ERROR, price.series?.fetchedAt, price.timeZone)
        }
    }
}

@Composable
private fun Cheapest(series: PriceSeries, entry: SunWattEntry) {
    val hours = series.cheapestUpcoming(entry.date, 3)
    if (hours.isEmpty()) return
    val contexts = PriceDayContexts(series, hours)
    Column(Modifier.padding(top = 2.dp, start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            stringResource(R.string.watch_cheapest_hours).uppercase(), color = secondaryColor, maxLines = 1,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (point in hours) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(PriceColors.color(contexts.fraction(point)).copy(alpha = 0.22f)).padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (point.start <= entry.date && entry.date in point) stringResource(R.string.watch_now) else Format.hour(point.start, series.region.timeZone),
                        color = secondaryColor, maxLines = 1,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        Format.eurPerKWh(point.eurPerKWh), maxLines = 1, overflow = TextOverflow.Clip,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    )
                }
            }
        }
    }
}

/**
 * Production now (big), today's energy and curve. Tapping the curve shows the power at that time instead, like the price
 * page; it goes back to now on a second tap of the same point, a tap on the latest point, or after the selection timeout.
 */
@Composable
fun WatchSolarPage(entry: SunWattEntry) {
    var selected by remember { mutableStateOf<CurvePoint?>(null) }
    val solar = SolarModel(entry)
    LaunchedEffect(selected) {
        if (selected != null) {
            delay(WatchPages.SELECTION_TIMEOUT)
            selected = null
        }
    }
    PageFrame {
        val snapshot = solar.snapshot
        if (solar.isAvailable && snapshot != null) {
            // A tapped point that is no longer in the curve (new day, new data) is dropped.
            val tapped = selected?.takeIf { it in snapshot.curve }
            val amber = SolarColors.amber
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val idle = solar.isIdle && tapped == null
                    Glyph(if (idle) GlyphShape.MOON else GlyphShape.SUN, if (idle) secondaryColor else amber, 13)
                    // Several plants: the plant's name.
                    CaptionText(solar.plantName ?: stringResource(R.string.widgets_production), Modifier.weight(1f))
                    DataTimeMarker(solar.dataTime, solar.staleSince != null)
                }
                val tappedKW = tapped?.kW
                if (tapped != null && tappedKW != null) {
                    FigureText(Format.kW(tappedKW), "kW", 32f, amber)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        // The tapped time, so the figure is not read as the power now.
                        CaptionText(watchTime(tapped.time), color = androidx.compose.ui.graphics.Color.White, bold = true)
                        DailyCaption(solar)
                    }
                } else {
                    if (solar.isIdle) {
                        Text(
                            stringResource(R.string.widgets_no_production), color = secondaryColor, maxLines = 2,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        )
                    } else {
                        FigureText(Format.kW(solar.powerKW), "kW", 32f, amber)
                    }
                    DailyCaption(solar)
                }
                WatchSolarChart(
                    snapshot, entry.date, tapped,
                    onTapFraction = { fraction ->
                        val point = WatchPages.solarPointAt(fraction, snapshot.curve, entry.date)
                        val latest = snapshot.curve.lastOrNull { it.kW != null }
                        // Nothing measured there, the same point again, or the latest point: back to now.
                        selected = if (point == null || point == selected || point == latest) null else point
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
                )
            }
        } else {
            StateView(solar.stateKind, solar.snapshot?.fetchedAt)
        }
    }
}

@Composable
private fun DailyCaption(solar: SolarModel) {
    CaptionText(stringResource(R.string.widgets_kwh_today, Format.kWh(solar.dailyKWh)))
}
