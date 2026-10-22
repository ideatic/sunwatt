package com.ideatic.sunwatt.core.price

import com.ideatic.sunwatt.core.Fixtures
import com.ideatic.sunwatt.core.StubHttpClient
import com.ideatic.sunwatt.core.StubHttpClient.Reply
import com.ideatic.sunwatt.core.models.PriceDayContexts
import com.ideatic.sunwatt.core.models.PriceGranularity
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.PriceSeriesBuilding
import com.ideatic.sunwatt.core.models.PriceStats
import com.ideatic.sunwatt.core.models.cheapestUpcoming
import com.ideatic.sunwatt.core.models.currentHour
import com.ideatic.sunwatt.core.models.dayContext
import com.ideatic.sunwatt.core.models.fraction
import com.ideatic.sunwatt.core.models.hourly
import com.ideatic.sunwatt.core.models.hoursAround
import com.ideatic.sunwatt.core.models.hoursOnDayOf
import com.ideatic.sunwatt.core.models.level
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.statsOnDayOf
import com.ideatic.sunwatt.core.services.PvpcClient
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hourlySeries(start: Instant, count: Int, region: PriceRegion = PriceRegion.SPAIN, price: (Int) -> Double) =
    PriceSeries(
        points = (0 until count).map { PricePoint(start.plusSeconds(it * 3600L), 3600, price(it)) },
        granularity = PriceGranularity.HOURLY,
        fetchedAt = start,
        region = region,
    )

private fun quarterSeries(start: Instant, count: Int, region: PriceRegion = PriceRegion.SPAIN, price: (Int) -> Double) =
    PriceSeries(
        points = (0 until count).map { PricePoint(start.plusSeconds(it * 900L), 900, price(it)) },
        granularity = PriceGranularity.QUARTER_HOURLY,
        fetchedAt = start,
        region = region,
    )

class PvpcParsingHardeningTest {
    private fun body(values: String) = """{"included":[{"type":"PVPC","attributes":{"values":[$values]}}]}""".toByteArray()

    @Test
    fun unparseablePointsAreSkipped() {
        val data = body(
            """
            {"value":100,"datetime":"2026-10-17T00:00:00.000+02:00"},
            {"value":"x","datetime":"2026-10-17T01:00:00.000+02:00"},
            {"value":120,"datetime":"garbage"},
            {"value":130},
            {"value":140,"datetime":"2026-10-17T04:00:00.000+02:00"}
            """.trimIndent(),
        )
        val s = PvpcClient.parse(data, Instant.EPOCH)
        assertEquals(listOf(100.0, 140.0), s.points.map { it.eurPerMWh })
        assertEquals(PriceRegion.SPAIN, s.region)
    }

    @Test
    fun allPointsUnparseableThrows() {
        val data = body("""{"value":"x","datetime":"nope"},{"value":1}""")
        assertFailsWith<SunWattError> { PvpcClient.parse(data, Instant.EPOCH) }
    }

    @Test
    fun dateParsingVariants() {
        val expected = Instant.ofEpochSecond(1_792_188_000) // 2026-10-16T22:00:00Z
        assertEquals(expected, PvpcClient.parseDate("2026-10-17T00:00:00.000+02:00"))
        assertEquals(expected, PvpcClient.parseDate("2026-10-17T00:00:00+02:00"))
        assertEquals(expected, PvpcClient.parseDate("2026-10-16T22:00:00Z"))
        assertEquals(expected, PvpcClient.parseDate("2026-10-16T22:00:00.000Z"))
        assertNull(PvpcClient.parseDate("not a date"))
    }

    @Test
    fun granularityFromMinimumGap() {
        // Hourly points with one extra 15-min neighbour: the minimum gap decides.
        val data = body(
            """
            {"value":1,"datetime":"2026-10-17T00:00:00+02:00"},
            {"value":2,"datetime":"2026-10-17T01:00:00+02:00"},
            {"value":3,"datetime":"2026-10-17T01:15:00+02:00"}
            """.trimIndent(),
        )
        val s = PvpcClient.parse(data, Instant.EPOCH)
        assertEquals(PriceGranularity.QUARTER_HOURLY, s.granularity)
        assertTrue(s.points.all { it.durationSeconds == 900L })
        val single = PvpcClient.parse(body("""{"value":1,"datetime":"2026-10-17T00:00:00+02:00"}"""), Instant.EPOCH)
        assertEquals(PriceGranularity.HOURLY, single.granularity)
    }

    @Test
    fun mergedSeriesIsQuarterHourlyWhenAnyPointIs() {
        val day1 = hourlySeries(iso("2026-10-16T22:00:00Z"), 24) { 100.0 }
        val day2 = quarterSeries(iso("2026-10-17T22:00:00Z"), 96) { 100.0 }
        val merged = PriceSeriesBuilding.merged(listOf(day1, day2), Instant.ofEpochSecond(5))
        assertEquals(PriceGranularity.QUARTER_HOURLY, merged.granularity)
        assertEquals(120, merged.points.size)
        assertEquals(Instant.ofEpochSecond(5), merged.fetchedAt)
        assertEquals(PriceGranularity.HOURLY, PriceSeriesBuilding.merged(listOf(day1), Instant.EPOCH).granularity)
    }

    @Test
    fun fetchDayUsesMadridDayOfTheInstant() = runTest {
        // 2026-10-17T22:30:00Z is already 2026-10-18 00:30 in Madrid (UTC+2).
        val now = iso("2026-10-17T22:30:00Z")
        val stub = StubHttpClient()
        stub.on("apidatos", Reply.Ok(Fixtures.bytes("pvpc-2026-10-17.json")))
        PvpcClient(stub, { now }).fetch(now)
        val url = stub.requestedUrls.first()
        assertTrue(url.contains("start_date=2026-10-18T00:00"))
        assertTrue(url.contains("end_date=2026-10-18T23:59"))

        val range = StubHttpClient()
        range.on("apidatos", Reply.Ok(Fixtures.bytes("pvpc-2026-10-17.json")))
        PvpcClient(range, { now }).fetchTodayAndTomorrow(includeTomorrow = true)
        val urls = range.requestedUrls
        assertEquals(2, urls.size)
        assertTrue(urls.any { it.contains("start_date=2026-10-18T00:00") })
        assertTrue(urls.any { it.contains("start_date=2026-10-19T00:00") })
    }

    @Test
    fun fetchDayAcrossFallBackBoundary() = runTest {
        // 2026-10-24T22:30:00Z = 2026-10-25 00:30 Madrid (still CEST).
        val now = iso("2026-10-24T22:30:00Z")
        val stub = StubHttpClient()
        stub.on("apidatos", Reply.Ok(Fixtures.bytes("pvpc-2026-10-17.json")))
        PvpcClient(stub, { now }).fetchTodayAndTomorrow(includeTomorrow = true)
        val urls = stub.requestedUrls
        assertTrue(urls.any { it.contains("start_date=2026-10-25T00:00") })
        assertTrue(urls.any { it.contains("start_date=2026-10-26T00:00") })
    }
}

class PriceAnalysisHardeningTest {
    private val t = madrid(2026, 10, 17, 12)

    @Test
    fun emptySeriesThroughEveryFunction() {
        val s = PriceSeries(emptyList(), PriceGranularity.HOURLY, Instant.EPOCH)
        val p = PricePoint(t, 3600, 100.0)
        assertTrue(s.hourly().isEmpty())
        assertNull(s.currentHour(t))
        assertTrue(s.hoursOnDayOf(t).isEmpty())
        assertTrue(s.hoursAround(t).isEmpty())
        assertNull(s.statsOnDayOf(t))
        assertNull(s.dayContext(t))
        assertEquals(0.5, s.fraction(p))
        assertEquals(PriceLevel.MEDIUM, s.level(p))
        assertTrue(s.cheapestUpcoming(t).isEmpty())
        assertTrue(PriceSeries(emptyList(), PriceGranularity.QUARTER_HOURLY, Instant.EPOCH).hourly().isEmpty())
    }

    @Test
    fun singlePointThroughEveryFunction() {
        val p = PricePoint(madrid(2026, 10, 17, 12), 3600, 150.0)
        val s = PriceSeries(listOf(p), PriceGranularity.HOURLY, Instant.EPOCH)
        val at = madrid(2026, 10, 17, 12, 30)
        assertEquals(listOf(p), s.hourly())
        assertEquals(p, s.currentHour(at))
        assertEquals(listOf(p), s.hoursOnDayOf(at))
        assertEquals(listOf(p), s.hoursAround(at))
        assertEquals(PriceStats(0.15, 0.15, 0.15), s.statsOnDayOf(at))
        assertEquals(0.5, s.fraction(p))
        assertEquals(PriceLevel.MEDIUM, s.level(p))
        assertEquals(listOf(p), s.cheapestUpcoming(at, 3))
        assertTrue(s.cheapestUpcoming(madrid(2026, 10, 17, 13), 3).isEmpty())
        val ctx = assertNotNull(s.dayContext(at))
        assertEquals(listOf(p), ctx.hours)
        assertEquals(0.5, ctx.fraction(p))
    }

    @Test
    fun allEqualPrices() {
        val s = hourlySeries(madrid(2026, 10, 17), 24) { 80.0 }
        val stats = assertNotNull(s.statsOnDayOf(madrid(2026, 10, 17, 9)))
        assertTrue(stats.min == stats.max && abs(stats.max - stats.average) < 1e-12)
        for (p in s.points) {
            assertEquals(0.5, s.fraction(p))
            assertEquals(PriceLevel.MEDIUM, s.level(p))
        }
        assertEquals((10..12).map { madrid(2026, 10, 17, it) }, s.cheapestUpcoming(madrid(2026, 10, 17, 10), 3).map { it.start })
    }

    @Test
    fun twoMergedDaysHaveSeparateStatsAndFractions() {
        val day1 = hourlySeries(madrid(2026, 10, 17), 24) { 100.0 + it }
        val day2 = hourlySeries(madrid(2026, 10, 18), 24) { 1000.0 + it * 10 }
        val s = PriceSeriesBuilding.merged(listOf(day1, day2), Instant.EPOCH)
        assertEquals(24, s.hoursOnDayOf(madrid(2026, 10, 17, 5)).size)
        assertEquals(24, s.hoursOnDayOf(madrid(2026, 10, 18, 5)).size)
        val a = assertNotNull(s.statsOnDayOf(madrid(2026, 10, 17, 5)))
        val b = assertNotNull(s.statsOnDayOf(madrid(2026, 10, 18, 5)))
        assertTrue(abs(a.min - 0.100) < 1e-12 && abs(a.max - 0.123) < 1e-12)
        assertTrue(abs(b.min - 1.0) < 1e-12 && abs(b.max - 1.23) < 1e-12)
        assertEquals(0.0, s.fraction(s.points[0]))
        assertEquals(1.0, s.fraction(s.points[23]))
        assertEquals(0.0, s.fraction(s.points[24]))
        assertEquals(1.0, s.fraction(s.points[47]))
        assertTrue(abs(s.fraction(s.points[12]) - 12.0 / 23.0) < 1e-9)
    }

    @Test
    fun dayBoundaries() {
        val s = hourlySeries(madrid(2026, 10, 17), 48) { it.toDouble() }
        val lastSecond = LocalDateTime.of(2026, 10, 17, 23, 59, 59).atZone(com.ideatic.sunwatt.core.models.Constants.madrid).toInstant()
        assertEquals(24, s.hoursOnDayOf(lastSecond).size)
        assertEquals(madrid(2026, 10, 17), s.hoursOnDayOf(lastSecond).first().start)
        assertEquals(24, s.hoursOnDayOf(madrid(2026, 10, 18, 0, 0)).size)
        assertEquals(madrid(2026, 10, 18), s.hoursOnDayOf(madrid(2026, 10, 18, 0, 0)).first().start)
        assertEquals(madrid(2026, 10, 17), s.hoursOnDayOf(madrid(2026, 10, 17, 0, 0)).first().start)
    }

    @Test
    fun dstDayStatsAndCheapestUpcomingAcrossRepeatedHour() {
        val s = pvpcSeries("pvpc-2025-10-26-dst25.json")
        val noon = madrid(2025, 10, 26, 12)
        val ctx = assertNotNull(s.dayContext(noon))
        assertEquals(25, ctx.hours.size)
        val prices = s.points.map { it.eurPerKWh }
        assertTrue(ctx.stats.min == prices.min() && ctx.stats.max == prices.max())
        assertTrue(abs(ctx.stats.average - prices.fold(0.0) { a, b -> a + b } / 25) < 1e-12)
        assertEquals(ctx.stats, s.statsOnDayOf(noon))

        // From before the repeated hour, asking for everything returns both 02:00 hours in order.
        val all = s.cheapestUpcoming(iso("2025-10-25T22:00:00Z"), 25)
        assertEquals(25, all.size)
        assertEquals(all.sortedBy { it.start }, all)
        val repeated = setOf(iso("2025-10-26T02:00:00+02:00"), iso("2025-10-26T02:00:00+01:00"))
        assertEquals(2, all.count { it.start in repeated })
        // From the second pass, the first 02:00 hour is gone.
        val later = s.cheapestUpcoming(iso("2025-10-26T02:30:00+01:00"), 25)
        assertTrue(later.none { it.start == iso("2025-10-26T02:00:00+02:00") })
        assertEquals(iso("2025-10-26T02:00:00+01:00"), later.first().start)
        assertEquals(25 - 3, later.size)
    }

    @Test
    fun quarterHourStatsAndCurrentHour() {
        val s = pvpcSeries("pvpc-quarter-hour-synthetic.json")
        val ctx = assertNotNull(s.dayContext(madrid(2026, 10, 17, 12)))
        assertEquals(24, ctx.hours.size)
        val hourlyPrices = s.hourly().map { it.eurPerKWh }
        assertEquals(hourlyPrices.min(), ctx.stats.min)
        assertEquals(hourlyPrices.max(), ctx.stats.max)
        // 05:40 sits in the 05:00 hour, whose price is the mean of its four quarters.
        val hour = assertNotNull(s.currentHour(madrid(2026, 10, 17, 5, 40)))
        assertEquals(madrid(2026, 10, 17, 5), hour.start)
        assertTrue(abs(hour.eurPerMWh - 153) < 1e-9)
        assertEquals(3600, hour.durationSeconds)
        assertTrue(s.fraction(hour) in 0.0..1.0)
    }

    @Test
    fun dayContextMatchesTheStandaloneFunctions() {
        val s = pvpcSeries("pvpc-2026-10-17.json")
        val at = madrid(2026, 10, 17, 3)
        val ctx = assertNotNull(s.dayContext(at))
        assertEquals(s.hoursOnDayOf(at), ctx.hours)
        assertEquals(s.statsOnDayOf(at), ctx.stats)
        for (p in ctx.hours) {
            assertEquals(s.fraction(p), ctx.fraction(p))
            assertEquals(s.level(p), ctx.level(p))
        }
    }

    @Test
    fun portugalUsesLisbonDayBoundaries() {
        // Lisbon is UTC+1 on 2026-10-17: its day starts at 23:00Z the evening before.
        val start = iso("2026-10-16T23:00:00Z")
        val pt = hourlySeries(start, 48, PriceRegion.PORTUGAL) { it.toDouble() }
        val justBeforeNextDay = iso("2026-10-17T22:59:59Z")
        assertEquals(24, pt.hoursOnDayOf(justBeforeNextDay).size)
        assertEquals(start, pt.hoursOnDayOf(justBeforeNextDay).first().start)
        val nextDay = iso("2026-10-17T23:00:00Z")
        assertEquals(24, pt.hoursOnDayOf(nextDay).size)
        assertEquals(nextDay, pt.hoursOnDayOf(nextDay).first().start)
        assertEquals(0.023, pt.statsOnDayOf(justBeforeNextDay)?.max)
        assertEquals(0.0, pt.fraction(pt.points[24]))

        // The same instants read as Madrid days (UTC+2) split at 22:00Z.
        val es = hourlySeries(start, 48, PriceRegion.SPAIN) { it.toDouble() }
        assertEquals(23, es.hoursOnDayOf(iso("2026-10-17T21:59:59Z")).size)
        assertEquals(iso("2026-10-17T22:00:00Z"), es.hoursOnDayOf(iso("2026-10-17T22:00:00Z")).first().start)
    }

    @Test
    fun regionTimeZones() {
        assertEquals("Europe/Lisbon", PriceRegion.PORTUGAL.timeZone.id)
        for (r in PriceRegion.entries.filter { it != PriceRegion.PORTUGAL }) {
            assertEquals("Europe/Madrid", r.timeZone.id)
        }
    }

    @Test
    fun dayContextsBuildsOneContextPerDay() {
        val day1 = hourlySeries(madrid(2026, 10, 17), 24) { 100.0 + it }
        val day2 = hourlySeries(madrid(2026, 10, 18), 24) { 1000.0 + it * 10 }
        val s = PriceSeriesBuilding.merged(listOf(day1, day2), Instant.EPOCH)
        val contexts = PriceDayContexts(s, s.points)
        assertEquals(2, contexts.dayCount)
        for (p in s.points) {
            assertEquals(s.fraction(p), contexts.fraction(p))
            assertEquals(s.level(p), contexts.level(p))
        }
        val unknown = PricePoint(madrid(2026, 10, 25), 3600, 1.0)
        assertEquals(0.5, contexts.fraction(unknown))
    }
}
