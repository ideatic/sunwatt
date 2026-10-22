package com.ideatic.sunwatt.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ideatic.sunwatt.core.models.HomeEnergy
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.ui.charts.ChartVariant
import com.ideatic.sunwatt.ui.charts.SolarChartModel
import com.ideatic.sunwatt.ui.charts.SolarCurveChart
import com.ideatic.sunwatt.ui.charts.rememberChartStyle
import com.ideatic.sunwatt.ui.components.CardHeader
import com.ideatic.sunwatt.ui.components.MiniStat
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.components.SolarSymbol
import com.ideatic.sunwatt.ui.components.StaleBadge
import com.ideatic.sunwatt.ui.theme.BigFigure
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.SunWattCard
import com.ideatic.sunwatt.ui.theme.appPalette
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/** Today's production: big power figure, energy today, month and year, home consumption and the curve. */
@Composable
fun SolarCard(
    solar: SolarSnapshot,
    now: Instant,
    isStale: Boolean,
    locale: Locale,
    wide: Boolean,
    modifier: Modifier = Modifier,
    /** Several plants: the plant's name, shown instead of the station name. */
    plantName: String? = null,
    initialSelection: Instant? = null,
) {
    val palette = appPalette
    SunWattCard(modifier) {
        CardHeader(
            title = stringResource(R.string.app_solar_production),
            subtitle = plantName ?: solar.stationName,
            symbol = { SolarSymbol(night = solar.isIdle, secondary = palette.textSecondary) },
            trailing = { TimeMarker(solar, isStale, locale) },
        )
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (solar.isIdle) {
                    RoundedText(stringResource(R.string.app_no_production), 30, weight = FontWeight.Bold, color = palette.textSecondary, maxLines = 1)
                } else {
                    BigFigure(Format.kW(solar.currentPowerKW, locale), "kW", figureSizeSp = 50, unitSizeSp = 18)
                }
                RoundedText(
                    stringResource(R.string.app_kwh_today, Format.kWh(solar.dailyEnergyKWh, locale)), 15,
                    weight = FontWeight.Medium, color = palette.textSecondary,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                solar.monthEnergyKWh?.let {
                    val energy = Format.energy(it, locale)
                    MiniStat(energy.value, energy.unit, stringResource(R.string.app_this_month), end = true)
                }
                solar.yearEnergyKWh?.let {
                    val energy = Format.energy(it, locale)
                    MiniStat(energy.value, energy.unit, stringResource(R.string.app_this_year), end = true)
                }
            }
        }
        // Only plants with a meter at home report consumption; export-only plants show nothing here.
        solar.home?.let { HomeEnergyRow(it, locale) }
        if (solar.curve.isNotEmpty()) {
            SolarChart(solar, now, initialSelection, Modifier.padding(top = Spacing.m).height(if (wide) 250.dp else 190.dp))
        }
    }
}

@Composable
private fun SolarChart(solar: SolarSnapshot, now: Instant, initialSelection: Instant?, modifier: Modifier) {
    val style = rememberChartStyle(ChartVariant.APP, appPalette.isDark)
    val model = remember(solar, now) { SolarChartModel.from(solar, now, ChartVariant.APP) }
    var selected by remember(model) { mutableStateOf(initialSelection?.let { model.nearest(it) }) }
    val description = stringResource(R.string.app_production_curve)
    SolarCurveChart(model, style, selected, { selected = it }, modifier.fillMaxWidth().semantics { contentDescription = description })
}

/** "As of 13:30", the stale badge, or the "Live" marker. */
@Composable
private fun TimeMarker(solar: SolarSnapshot, isStale: Boolean, locale: Locale) {
    val palette = appPalette
    when {
        isStale -> StaleBadge(solar.dataAsOf ?: solar.fetchedAt, locale = locale)
        solar.isLive == true -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(PriceColors.green))
            RoundedText(stringResource(R.string.app_live), 12, weight = FontWeight.SemiBold, color = palette.textSecondary)
        }
        solar.dataAsOf != null -> RoundedText(
            stringResource(R.string.app_as_of, Format.time(solar.dataAsOf!!, locale = locale)), 12,
            weight = FontWeight.Medium, color = palette.textSecondary, maxLines = 1, tabularDigits = true,
        )
    }
}

/** Below this (kW) the grid exchange counts as none. */
private const val IDLE_GRID = 0.05

/** What the house uses now, the grid exchange and today's share from the panels. Shown only with a meter at home. */
@Composable
private fun HomeEnergyRow(home: HomeEnergy, locale: Locale) {
    val palette = appPalette
    Column(Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalAlignment = Alignment.Top) {
            home.consumptionKW?.let { MiniStat(Format.kW(it, locale), "kW", stringResource(R.string.app_home_now)) }
            home.gridKW?.let { grid ->
                val caption = when {
                    grid > IDLE_GRID -> R.string.app_buying_from_the_grid
                    grid < -IDLE_GRID -> R.string.app_selling_to_the_grid
                    else -> R.string.app_grid
                }
                MiniStat(Format.kW(abs(grid), locale), "kW", stringResource(caption))
            }
        }
        home.dailyConsumptionKWh?.let { used ->
            val kWh = Format.kWh(used, locale)
            val share = home.selfSufficiency
            val text = if (share == null) {
                stringResource(R.string.app_used_kwh_today, kWh)
            } else {
                val percent = java.text.NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }.format(share)
                stringResource(R.string.app_used_kwh_today_from_your_panels, kWh, percent)
            }
            RoundedText(text, 13, weight = FontWeight.Medium, color = palette.textSecondary)
        }
    }
}
