package com.ideatic.sunwatt.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.models.PriceDayContexts
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.PriceStats
import com.ideatic.sunwatt.core.models.cheapestUpcoming
import com.ideatic.sunwatt.core.models.currentHour
import com.ideatic.sunwatt.core.models.dayContext
import com.ideatic.sunwatt.core.models.hoursOnDayOf
import com.ideatic.sunwatt.ui.charts.ChartVariant
import com.ideatic.sunwatt.ui.charts.PriceBarChart
import com.ideatic.sunwatt.ui.charts.PriceChartModel
import com.ideatic.sunwatt.ui.charts.rememberChartStyle
import com.ideatic.sunwatt.ui.components.AppSegmented
import com.ideatic.sunwatt.ui.components.AppSymbol
import com.ideatic.sunwatt.ui.components.CardHeader
import com.ideatic.sunwatt.ui.components.LevelPill
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.components.StaleBadge
import com.ideatic.sunwatt.ui.components.SymbolKind
import com.ideatic.sunwatt.ui.text.displayNameRes
import com.ideatic.sunwatt.ui.text.tariffName
import com.ideatic.sunwatt.ui.theme.BigFigure
import com.ideatic.sunwatt.ui.theme.FigureStyles
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.SunWattCard
import com.ideatic.sunwatt.ui.theme.appPalette
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.util.Locale

private enum class DayChoice { TODAY, TOMORROW }

/** The price of electricity: current price, level, hourly bars, min/average/max and the cheapest upcoming hours. */
@Composable
fun PriceCard(
    series: PriceSeries,
    now: Instant,
    isStale: Boolean,
    locale: Locale,
    wide: Boolean,
    modifier: Modifier = Modifier,
    startsOnTomorrow: Boolean = false,
    initialSelection: Instant? = null,
) {
    val region = series.region
    val zone = region.timeZone
    val tomorrowStart = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    val hasTomorrow = series.hoursOnDayOf(tomorrowStart).isNotEmpty()
    var day by rememberSaveable { mutableStateOf(if (startsOnTomorrow) DayChoice.TOMORROW else DayChoice.TODAY) }
    val shown = if (hasTomorrow) day else DayChoice.TODAY
    val reference = if (shown == DayChoice.TODAY) now else tomorrowStart
    val hours = series.hoursOnDayOf(reference)
    val context = series.dayContext(reference)
    val current = if (shown == DayChoice.TODAY) series.currentHour(now) else null
    val fraction = current?.let { context?.fraction(it) }
    val level = current?.let { context?.level(it) }
    val palette = appPalette
    val androidContext = LocalContext.current

    SunWattCard(modifier) {
        CardHeader(
            title = region.tariffName(androidContext),
            subtitle = "${region.flag} ${stringResource(region.displayNameRes())}",
            symbol = { AppSymbol(SymbolKind.BOLT, fraction?.let { PriceColors.color(it) } ?: PriceColors.green, size = 20.dp) },
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    if (isStale) StaleBadge(series.fetchedAt, zone = zone, locale = locale)
                    if (level != null) LevelPill(level)
                }
            },
        )
        if (hasTomorrow) {
            AppSegmented(
                listOf(DayChoice.TODAY to stringResource(R.string.app_today), DayChoice.TOMORROW to stringResource(R.string.app_tomorrow)),
                shown, { day = it }, Modifier.padding(top = Spacing.m),
            )
        }
        Headline(context?.stats, current, shown == DayChoice.TOMORROW, locale, Modifier.padding(top = Spacing.m))
        if (hours.isNotEmpty()) {
            val style = rememberChartStyle(ChartVariant.APP, palette.isDark)
            val model = remember(series, hours, now) { PriceChartModel.from(series, hours, now, ChartVariant.APP) }
            var selected by remember(model, shown) {
                mutableStateOf(initialSelection?.takeIf { shown == DayChoice.TODAY || startsOnTomorrow }?.let { t -> hours.indexOfFirst { t in it } }?.takeIf { it >= 0 })
            }
            val description = stringResource(R.string.app_price_chart)
            PriceBarChart(
                model, style, selected, { selected = it },
                Modifier.padding(top = Spacing.m).fillMaxWidth().height(if (wide) 230.dp else 170.dp).semantics { contentDescription = description },
            )
        }
        context?.stats?.let { StatsRow(it, locale) }
        Cheapest(series, shown == DayChoice.TODAY, now, reference, tomorrowStart, locale)
    }
}

@Composable
private fun Headline(stats: PriceStats?, current: PricePoint?, tomorrow: Boolean, locale: Locale, modifier: Modifier) {
    when {
        current != null && stats != null -> Row(modifier.fillMaxWidth()) { BigFigure(Format.eurPerKWh(current.eurPerKWh, locale = locale), "€/kWh", figureSizeSp = 50, unitSizeSp = 18) }
        stats != null -> Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BigFigure(Format.eurPerKWh(stats.average, locale = locale), "€/kWh", figureSizeSp = 50, unitSizeSp = 18)
            RoundedText(
                stringResource(if (tomorrow) R.string.app_average_tomorrow else R.string.app_average_today), 15,
                weight = FontWeight.Medium, color = appPalette.textSecondary,
            )
        }
    }
}

@Composable
private fun StatsRow(stats: PriceStats, locale: Locale) {
    Row(Modifier.padding(top = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalAlignment = Alignment.Top) {
        StatItem(stringResource(R.string.app_min), stats.min, PriceColors.green, locale)
        StatItem(stringResource(R.string.app_average), stats.average, PriceColors.yellow, locale)
        StatItem(stringResource(R.string.app_max), stats.max, PriceColors.red, locale)
    }
}

@Composable
private fun StatItem(title: String, value: Double, color: androidx.compose.ui.graphics.Color, locale: Locale) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RoundedText(title, 12, weight = FontWeight.SemiBold, color = appPalette.textSecondary, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            androidx.compose.material3.Text(Format.eurPerKWh(value, locale = locale), style = FigureStyles.figure(17))
        }
    }
}

@Composable
private fun Cheapest(series: PriceSeries, today: Boolean, now: Instant, reference: Instant, tomorrowStart: Instant, locale: Locale) {
    val zone = series.region.timeZone
    val from = if (today) now else tomorrowStart
    val referenceDay = reference.atZone(zone).toLocalDate()
    val dayEnd = referenceDay.plusDays(1).atStartOfDay(zone).toInstant()
    val upcoming = series.cheapestUpcoming(from, 3, dayEnd).filter { it.start.atZone(zone).toLocalDate() == referenceDay }
    if (upcoming.isEmpty()) return
    val contexts = PriceDayContexts(series, upcoming)
    Column(Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        RoundedText(
            stringResource(if (today) R.string.app_cheapest_upcoming_hours else R.string.app_cheapest_hours_tomorrow), 12,
            weight = FontWeight.SemiBold, color = appPalette.textSecondary,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            for (point in upcoming) HourChip(point, contexts.fraction(point), series, locale, Modifier.weight(1f))
            // Fewer than three hours left today: keep the chips at the same width.
            repeat(3 - upcoming.size) { Box(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun HourChip(point: PricePoint, fraction: Double, series: PriceSeries, locale: Locale, modifier: Modifier) {
    val zone = series.region.timeZone
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(PriceColors.color(fraction).copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RoundedText("${Format.hour(point.start, zone)}–${Format.hour(point.end, zone)}", 13, weight = FontWeight.Bold, maxLines = 1)
        androidx.compose.material3.Text(
            Format.eurPerKWh(point.eurPerKWh, locale = locale), style = FigureStyles.figure(13, FontWeight.SemiBold),
            color = appPalette.textSecondary, maxLines = 1,
        )
    }
}
