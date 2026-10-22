package com.ideatic.sunwatt.core.data

import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.PriceGranularity
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.services.SunWattError
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Sample data built in code (no resources) for widget placeholders, previews and tests (port of the Swift
 * `SampleData`). The day is 2026-10-17 in Europe/Madrid; prices are the real PVPC values of that day.
 */
object SampleData {
    enum class Scenario {
        /** 13:40, producing ~5 kW, curve up to 13:30. */
        DAY,

        /** 22:10, no production, full-day curve ending ~19:45. */
        NIGHT,

        /** Like [DAY], but the solar data was fetched two hours earlier (status stale). */
        STALE,

        /** Both sources failed, nothing cached. */
        ERROR,
    }

    /** 2026-10-17 13:40 Europe/Madrid. */
    val now: Instant = date(13, 40)
    val nightNow: Instant = date(22, 10)

    /** PVPC EUR/MWh for 2026-10-17, hours 00...23 (copied from the real fixture `pvpc-2026-10-17.json`). */
    private val pvpcEurPerMWh = listOf(
        246.76, 233.00, 215.93, 191.83, 180.61, 172.48, 194.15, 224.46, 240.49, 176.68, 90.51, 19.74,
        18.15, 17.81, 18.04, 19.23, 22.76, 74.83, 155.24, 197.53, 198.48, 197.14, 186.96, 170.94,
    )

    /** Illustrative day-ahead wholesale prices (EUR/MWh) for a French Saturday, with a slightly negative midday hour. */
    private val dayAheadEurPerMWh = listOf(
        88.2, 82.5, 79.1, 76.4, 78.8, 86.3, 104.9, 128.4, 119.6, 92.7, 61.3, 38.5,
        24.2, 9.8, -3.4, 12.4, 41.9, 98.7, 143.5, 168.2, 152.9, 131.0, 112.6, 98.4,
    )

    /** One timeline entry for [scenario], with prices of [region]. */
    fun entry(scenario: Scenario, region: PriceRegion = PriceRegion.SPAIN): SunWattEntry = when (scenario) {
        Scenario.DAY -> SunWattEntry(
            now,
            SunWattData(
                solar = snapshot(date(13, 30), now.minusSeconds(120)), prices = prices(region, now.minusSeconds(180)),
                solarStatus = SourceStatus.Fresh, priceStatus = SourceStatus.Fresh,
            ),
        )
        Scenario.NIGHT -> SunWattEntry(
            nightNow,
            SunWattData(
                solar = snapshot(date(22, 5), nightNow.minusSeconds(90)), prices = prices(region, nightNow.minusSeconds(180)),
                solarStatus = SourceStatus.Fresh, priceStatus = SourceStatus.Fresh,
            ),
        )
        Scenario.STALE -> {
            val fetched = now.minusSeconds(2 * 3600)
            SunWattEntry(
                now,
                SunWattData(
                    solar = snapshot(date(11, 35), fetched), prices = prices(region, now.minusSeconds(180)),
                    solarStatus = SourceStatus.Stale(fetched), priceStatus = SourceStatus.Fresh,
                ),
            )
        }
        Scenario.ERROR -> {
            val status = SourceStatus.Error.of(SunWattError.Unavailable)
            SunWattEntry(now, SunWattData(solar = null, prices = null, solarStatus = status, priceStatus = status))
        }
    }

    /** Solar not configured, prices fine. */
    fun notConfiguredEntry(): SunWattEntry = SunWattEntry(
        now,
        SunWattData(
            solar = null, prices = prices(PriceRegion.SPAIN, now),
            solarStatus = SourceStatus.NotConfigured, priceStatus = SourceStatus.Fresh,
        ),
    )

    /** Price series of the sample day for [region] (PVPC for Spain, illustrative day-ahead otherwise). */
    fun prices(region: PriceRegion = PriceRegion.SPAIN, fetchedAt: Instant = now): PriceSeries {
        val values = if (region.isRetailTariff) pvpcEurPerMWh else dayAheadEurPerMWh
        val start = date(0, 0)
        val points = values.mapIndexed { hour, value -> PricePoint(start.plusSeconds(hour * 3600L), 3600, value) }
        return PriceSeries(points = points, granularity = PriceGranularity.HOURLY, fetchedAt = fetchedAt, region = region)
    }

    /** Production snapshot with 5-minute points from 00:00 up to [until] (inclusive); later points are absent. */
    fun snapshot(until: Instant, fetchedAt: Instant): SolarSnapshot {
        val dayStart = date(0, 0)
        val curve = mutableListOf<CurvePoint>()
        var minutes = 0
        while (!dayStart.plusSeconds(minutes * 60L).isAfter(until)) {
            curve += CurvePoint(dayStart.plusSeconds(minutes * 60L), power(minutes))
            minutes += 5
        }
        val energy = curve.fold(0.0) { sum, point -> sum + (point.kW ?: 0.0) } * 5.0 / 60.0
        val last = curve.lastOrNull()
        return SolarSnapshot(
            stationName = "Home", currentPowerKW = last?.kW, dailyEnergyKWh = Math.round(energy * 10) / 10.0,
            monthEnergyKWh = 412.6, yearEnergyKWh = 5120.3, curve = curve, fetchedAt = fetchedAt, dataAsOf = last?.time,
        )
    }

    /**
     * Smooth bell-shaped production (peak ~5.3 kW around 13:50) with one gentle, wide cloud dip in the afternoon; zero
     * outside 08:00-19:45. No high-frequency terms, so the curve has no teeth.
     */
    internal fun power(minute: Int): Double {
        val sunrise = 480.0
        val sunset = 1185.0
        val m = minute.toDouble()
        if (m <= sunrise || m >= sunset) return 0.0
        val x = (m - sunrise) / (sunset - sunrise)
        val bell = sin(PI * x).pow(1.35)
        val dip = 0.06 * exp(-((m - 950) / 45).pow(2))
        return Math.round(5.3 * bell * (1 - dip) * 100) / 100.0
    }

    fun date(hour: Int, minute: Int): Instant = ZonedDateTime.of(2026, 10, 17, hour, minute, 0, 0, Constants.madrid).toInstant()
}
