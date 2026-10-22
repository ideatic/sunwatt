package com.ideatic.sunwatt.ui.charts

import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.PriceGranularity
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Synthetic data for previews, snapshot tests and unit tests. Not real captures; no personal data. */
object ChartSamples {
    /** A day of Spanish-like prices: expensive nights, a cheap midday. [eurPerKWh] maps the local hour to a price. */
    fun priceSeries(
        day: LocalDate = LocalDate.of(2026, 10, 17),
        region: PriceRegion = PriceRegion.SPAIN,
        quarterHourly: Boolean = false,
        eurPerKWh: (Int) -> Double = ::typicalPrice,
    ): PriceSeries {
        val zone = region.timeZone
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val step = if (quarterHourly) 900L else 3600L
        val points = ArrayList<PricePoint>()
        var t = start
        while (t < end) {
            val hour = t.atZone(zone).hour
            points.add(PricePoint(t, step, eurPerKWh(hour) * 1000))
            t = t.plusSeconds(step)
        }
        return PriceSeries(
            points = points,
            granularity = if (quarterHourly) PriceGranularity.QUARTER_HOURLY else PriceGranularity.HOURLY,
            fetchedAt = start,
            region = region,
        )
    }

    fun typicalPrice(hour: Int): Double = when (hour) {
        in 0..2 -> 0.22 - hour * 0.01
        in 3..8 -> 0.16 + (hour - 3) * 0.012
        9 -> 0.08
        in 10..16 -> 0.018 + (hour - 10) * 0.0005
        17 -> 0.07
        else -> 0.21 + (hour % 3) * 0.015
    }

    /** Local time of day [hour] (fractions allowed) on [day] in [zone]. */
    fun at(day: LocalDate, hour: Double, zone: ZoneId = Constants.madrid): Instant =
        day.atStartOfDay(zone).toInstant().plusSeconds((hour * 3600).toLong())

    /** Production curve from 06:00 to [nowHour] every 5 minutes; null before sunrise when [nullsBeforeSunrise]. */
    fun solarSnapshot(
        day: LocalDate = LocalDate.of(2026, 10, 17),
        nowHour: Double = 13.5,
        nullsBeforeSunrise: Boolean = false,
    ): SolarSnapshot {
        val curve = ArrayList<CurvePoint>()
        var h = 6.0
        while (h <= nowHour + 1e-9) {
            val kW = if (h < 7.5) 0.0 else max(0.0, 6.6 * sin(PI * (h - 7.5) / 13.0))
            curve.add(CurvePoint(at(day, h), if (nullsBeforeSunrise && h < 6.5) null else kW))
            h += 5.0 / 60
        }
        val last = curve.lastOrNull { it.kW != null }
        return SolarSnapshot(
            currentPowerKW = last?.kW,
            dailyEnergyKWh = 16.2,
            curve = curve,
            fetchedAt = at(day, nowHour),
            dataAsOf = last?.time,
        )
    }

    /** Night: the curve ended in the evening and the plant is idle. */
    fun nightSnapshot(day: LocalDate = LocalDate.of(2026, 10, 17)): SolarSnapshot {
        val curve = solarSnapshot(day, 21.0).curve.map { if (it.time >= at(day, 19.5)) CurvePoint(it.time, 0.0) else it }
        return SolarSnapshot(currentPowerKW = 0.0, dailyEnergyKWh = 44.1, curve = curve, fetchedAt = at(day, 23.0), dataAsOf = curve.last().time)
    }
}
