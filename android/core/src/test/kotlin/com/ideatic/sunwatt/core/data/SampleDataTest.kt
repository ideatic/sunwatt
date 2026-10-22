package com.ideatic.sunwatt.core.data

import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.PriceGranularity
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarSnapshot
import java.time.Instant
import java.time.ZonedDateTime

/** Test helpers shared by the data, timeline and store tests (the Swift `TestHelpers` and `StoreTests` helpers). */
fun madrid(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, Constants.madrid).toInstant()

const val TEST_KIOSK_URL = "https://example.test/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=TESTKK"
const val TEST_KIOSK_ID = "kiosk-cache-id"

fun sampleSolar(fetchedAt: Instant = madrid(2026, 6, 1, 12), endpointID: String? = TEST_KIOSK_ID) = SolarSnapshot(
    stationName = "Casa", currentPowerKW = 2.5, dailyEnergyKWh = 10.2,
    curve = listOf(CurvePoint(madrid(2026, 6, 1, 11, 55), 2.4), CurvePoint(madrid(2026, 6, 1, 12), null)),
    fetchedAt = fetchedAt, dataAsOf = madrid(2026, 6, 1, 11, 55), endpointID = endpointID,
)

fun samplePrices(day: Instant = madrid(2026, 6, 1), fetchedAt: Instant = madrid(2026, 6, 1, 12)) = PriceSeries(
    points = (0 until 24).map { PricePoint(day.plusSeconds(it * 3600L), 3600, 100.0 + it) },
    granularity = PriceGranularity.HOURLY, fetchedAt = fetchedAt, sourceUpdatedAt = day,
)

fun hourlySeries(start: Instant, hours: Int, region: PriceRegion = PriceRegion.SPAIN, price: (Int) -> Double) = PriceSeries(
    points = (0 until hours).map { PricePoint(start.plusSeconds(it * 3600L), 3600, price(it)) },
    granularity = PriceGranularity.HOURLY, fetchedAt = start, region = region,
)
