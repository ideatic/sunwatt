package com.ideatic.sunwatt.core.price

import com.ideatic.sunwatt.core.Fixtures
import com.ideatic.sunwatt.core.StubHttpClient
import com.ideatic.sunwatt.core.StubHttpClient.Reply
import com.ideatic.sunwatt.core.models.PriceGranularity
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.hourly
import com.ideatic.sunwatt.core.models.hoursOnDayOf
import com.ideatic.sunwatt.core.models.statsOnDayOf
import com.ideatic.sunwatt.core.services.EnergyChartsClient
import com.ideatic.sunwatt.core.services.PriceDataSource
import com.ideatic.sunwatt.core.services.PvpcClient
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun json(unix: List<Long>, prices: List<String>, unit: String = "EUR / MWh"): ByteArray =
    """{"license_info":"x","unix_seconds":[${unix.joinToString(",")}],"price":[${prices.joinToString(",")}],"unit":"$unit","deprecated":false}"""
        .toByteArray()

private fun parse(data: ByteArray, region: PriceRegion, fetchedAt: Instant = Instant.EPOCH, window: Pair<Instant, Instant>? = null) =
    EnergyChartsClient.parse(data, region, fetchedAt, window)

class EnergyChartsParsingTest {
    @Test
    fun germanyFixture() {
        val s = parse(Fixtures.bytes("energycharts-DE-LU-2026-10-17.json"), PriceRegion.GERMANY, madrid(2026, 10, 17, 10))
        assertEquals(96, s.points.size)
        assertEquals(PriceGranularity.QUARTER_HOURLY, s.granularity)
        assertTrue(s.points.all { it.durationSeconds == 900L })
        assertEquals(PriceRegion.GERMANY, s.region)
        assertEquals(iso("2026-10-16T22:00:00Z"), s.points[0].start)
        assertEquals(madrid(2026, 10, 17), s.points[0].start)
        assertEquals(s.points.sortedBy { it.start }, s.points)
        assertEquals(24, s.hourly().size)
        assertEquals(24, s.hoursOnDayOf(madrid(2026, 10, 17, 12)).size)
        assertNotNull(s.statsOnDayOf(madrid(2026, 10, 17, 12)))
        assertNull(s.sourceUpdatedAt)
    }

    @Test
    fun portugalFixtureStartsAtLisbonMidnight() {
        val s = parse(Fixtures.bytes("energycharts-PT-2026-10-17.json"), PriceRegion.PORTUGAL)
        assertEquals(92, s.points.size)
        assertEquals(iso("2026-10-16T23:00:00Z"), s.points[0].start)
        assertEquals(PriceRegion.PORTUGAL, s.region)
        val zone = s.region.timeZone
        val first = s.points[0].start
        assertEquals(first, first.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant())
    }

    @Test
    fun otherZoneFixturesParse() {
        for ((name, region) in listOf("NL" to PriceRegion.NETHERLANDS, "AT" to PriceRegion.AUSTRIA, "IT-North" to PriceRegion.ITALY_NORTH)) {
            val s = parse(Fixtures.bytes("energycharts-$name-2026-10-17.json"), region)
            assertEquals(96, s.points.size)
            assertEquals(region, s.region)
        }
    }

    @Test
    fun springForwardDayHas92Points() {
        val s = parse(Fixtures.bytes("energycharts-DE-LU-2026-03-29-dst92.json"), PriceRegion.GERMANY)
        assertEquals(92, s.points.size)
        assertEquals(23, s.hoursOnDayOf(madrid(2026, 3, 29, 12)).size)
    }

    @Test
    fun nullPricesAreSkipped() {
        val s = parse(json(listOf(0, 900, 1800, 2700), listOf("1.5", "null", "3", "null")), PriceRegion.FRANCE)
        assertEquals(listOf(1.5, 3.0), s.points.map { it.eurPerMWh })
        assertEquals(listOf(0L, 1800L), s.points.map { it.start.epochSecond })
    }

    @Test
    fun hourlyResponseInfersHourlyDuration() {
        val s = parse(json(listOf(0, 3600, 7200), listOf("1", "2", "3")), PriceRegion.BELGIUM)
        assertEquals(PriceGranularity.HOURLY, s.granularity)
        assertTrue(s.points.all { it.durationSeconds == 3600L })
    }

    @Test
    fun otherUnitIsBadResponse() {
        for (unit in listOf("EUR / kWh", "USD / MWh", "")) {
            assertFailsWith<SunWattError.BadResponse>("unit $unit") { parse(json(listOf(0), listOf("1"), unit), PriceRegion.FRANCE) }
        }
        assertFailsWith<SunWattError> { parse("""{"unix_seconds":[0],"price":[1]}""".toByteArray(), PriceRegion.FRANCE) }
    }

    @Test
    fun unitSpacingAndCaseAreTolerated() {
        val s = parse(json(listOf(0), listOf("1"), "eur/MWh"), PriceRegion.FRANCE)
        assertEquals(1, s.points.size)
    }

    @Test
    fun emptyOrAllNullIsUnavailable() {
        assertFailsWith<SunWattError.Unavailable> { parse(json(emptyList(), emptyList()), PriceRegion.FRANCE) }
        assertFailsWith<SunWattError.Unavailable> { parse(json(listOf(0, 900), listOf("null", "null")), PriceRegion.FRANCE) }
    }

    @Test
    fun garbageIsBadResponse() {
        assertFailsWith<SunWattError> { parse("nope".toByteArray(), PriceRegion.FRANCE) }
        assertFailsWith<SunWattError> { parse("""{"detail":"x","unit":"EUR / MWh"}""".toByteArray(), PriceRegion.FRANCE) }
    }

    @Test
    fun windowTrimsPoints() {
        val data = json(listOf(0, 900, 1800, 2700), listOf("1", "2", "3", "4"))
        val window = Instant.ofEpochSecond(900) to Instant.ofEpochSecond(2700)
        val s = parse(data, PriceRegion.FRANCE, window = window)
        assertEquals(listOf(2.0, 3.0), s.points.map { it.eurPerMWh })
    }
}

class EnergyChartsFetchTest {
    private fun client(region: PriceRegion, stub: StubHttpClient, now: Instant) = EnergyChartsClient(region, stub, { now })

    @Test
    fun fetchBuildsLocalDayUrl() = runTest {
        val stub = StubHttpClient()
        stub.on("api.energy-charts.info", Reply.Ok(Fixtures.bytes("energycharts-DE-LU-2026-10-17.json")))
        val s = client(PriceRegion.GERMANY, stub, madrid(2026, 10, 17, 10)).fetch(madrid(2026, 10, 17, 18, 30))
        assertEquals(96, s.points.size)
        assertEquals("https://api.energy-charts.info/price?bzn=DE-LU&start=2026-10-17&end=2026-10-17", stub.requestedUrls.first())
    }

    @Test
    fun dayBoundaryDiffersBetweenPortugalAndGermany() = runTest {
        // 22:30Z: 23:30 on 17 Oct in Lisbon (UTC+1), 00:30 on 18 Oct in Berlin (UTC+2).
        val now = iso("2026-10-17T22:30:00Z")
        val pt = StubHttpClient()
        pt.on("api.energy-charts.info", Reply.Ok(Fixtures.bytes("energycharts-PT-2026-10-17.json")))
        client(PriceRegion.PORTUGAL, pt, now).fetch(now)
        assertEquals("https://api.energy-charts.info/price?bzn=PT&start=2026-10-17&end=2026-10-17", pt.requestedUrls.first())

        val de = StubHttpClient()
        de.on("api.energy-charts.info", Reply.Ok(Fixtures.bytes("energycharts-DE-LU-2026-10-17.json")))
        // The fixture holds 17 Oct; asking for the 18th gives nothing in the window.
        assertFailsWith<SunWattError.Unavailable> { client(PriceRegion.GERMANY, de, now).fetch(now) }
        assertEquals("https://api.energy-charts.info/price?bzn=DE-LU&start=2026-10-18&end=2026-10-18", de.requestedUrls.first())
    }

    @Test
    fun todayAndTomorrowIsOneRequest() = runTest {
        val now = iso("2026-10-16T10:00:00Z") // 12:00 Madrid, 11:00 Lisbon on 16 Oct
        val stub = StubHttpClient()
        stub.on("api.energy-charts.info", Reply.Ok(Fixtures.bytes("energycharts-PT-2026-10-16-to-17.json")))
        val both = client(PriceRegion.PORTUGAL, stub, now).fetchTodayAndTomorrow(includeTomorrow = true)
        assertEquals(1, stub.requestedUrls.size)
        assertEquals("https://api.energy-charts.info/price?bzn=PT&start=2026-10-16&end=2026-10-17", stub.requestedUrls.first())
        assertEquals(188, both.points.size)
        assertEquals(now, both.fetchedAt)
        assertEquals(PriceRegion.PORTUGAL, both.region)
        assertEquals(24, both.hoursOnDayOf(iso("2026-10-16T12:00:00Z")).size)

        val todayStub = StubHttpClient()
        todayStub.on("api.energy-charts.info", Reply.Ok(Fixtures.bytes("energycharts-PT-2026-10-16-to-17.json")))
        val only = client(PriceRegion.PORTUGAL, todayStub, now).fetchTodayAndTomorrow(includeTomorrow = false)
        assertEquals(96, only.points.size)
        assertEquals(iso("2026-10-16T22:45:00Z"), only.points.last().start)
        assertEquals(1, todayStub.requestedUrls.size)
    }

    @Test
    fun tomorrowNotPublishedStillGivesToday() = runTest {
        // The API answers an end date that is not published yet with today's data only.
        val stub = StubHttpClient()
        stub.on("api.energy-charts.info", Reply.Ok(Fixtures.bytes("energycharts-DE-LU-2026-10-17.json")))
        val s = client(PriceRegion.GERMANY, stub, madrid(2026, 10, 17, 10)).fetchTodayAndTomorrow(includeTomorrow = true)
        assertEquals(96, s.points.size)
        assertEquals(1, stub.requestedUrls.size)
    }

    @Test
    fun httpStatusMapping() = runTest {
        val now = madrid(2026, 10, 17, 10)
        val notFound = StubHttpClient()
        notFound.on("energy-charts", Reply.Ok("{}", status = 404))
        assertFailsWith<SunWattError.Unavailable> { client(PriceRegion.FRANCE, notFound, now).fetch(now) }
        assertFailsWith<SunWattError.Unavailable> { client(PriceRegion.FRANCE, notFound, now).fetchTodayAndTomorrow(true) }

        val limited = StubHttpClient()
        limited.on("energy-charts", Reply.Ok("slow down", status = 429))
        assertEquals(429, assertFailsWith<SunWattError.Http> { client(PriceRegion.FRANCE, limited, now).fetch(now) }.status)

        val broken = StubHttpClient()
        broken.on("energy-charts", Reply.Ok(ByteArray(0), status = 500))
        assertEquals(500, assertFailsWith<SunWattError.Http> { client(PriceRegion.FRANCE, broken, now).fetch(now) }.status)

        val emptyDay = StubHttpClient()
        emptyDay.on("energy-charts", Reply.Ok(json(emptyList(), emptyList()), status = 200))
        assertFailsWith<SunWattError.Unavailable> { client(PriceRegion.FRANCE, emptyDay, now).fetch(now) }

        val offline = StubHttpClient(Reply.Failure(IOException("offline")))
        assertFailsWith<SunWattError.Network> { client(PriceRegion.FRANCE, offline, now).fetch(now) }
    }

    @Test
    fun zoneCodes() {
        assertNull(PriceRegion.SPAIN.energyChartsZone)
        assertEquals("DE-LU", PriceRegion.GERMANY.energyChartsZone)
        assertEquals("FR", PriceRegion.FRANCE.energyChartsZone)
        assertEquals("PT", PriceRegion.PORTUGAL.energyChartsZone)
        assertEquals("BE", PriceRegion.BELGIUM.energyChartsZone)
        assertEquals("NL", PriceRegion.NETHERLANDS.energyChartsZone)
        assertEquals("AT", PriceRegion.AUSTRIA.energyChartsZone)
        assertEquals("IT-North", PriceRegion.ITALY_NORTH.energyChartsZone)
    }

    @Test
    fun spainIsNotServedByEnergyCharts() = runTest {
        assertFailsWith<SunWattError> { EnergyChartsClient(PriceRegion.SPAIN, StubHttpClient()).fetch(Instant.now()) }
    }

    @Test
    fun factoryPicksTheRightClient() {
        assertIs<PvpcClient>(PriceRegion.SPAIN.client())
        for (region in PriceRegion.entries.filter { it != PriceRegion.SPAIN }) {
            val client: PriceDataSource = region.client()
            assertEquals(region, assertIs<EnergyChartsClient>(client).region)
        }
    }

    @Test
    fun regionNames() {
        assertEquals("PVPC", PriceRegion.SPAIN.tariffName)
        assertEquals("Red Eléctrica", PriceRegion.SPAIN.sourceName)
        assertEquals("Energy-Charts (Fraunhofer ISE)", PriceRegion.GERMANY.sourceName)
        assertNull(PriceRegion.GERMANY.tariffName)
    }

    // Cases of the Swift KioskHardeningTests that concern the price clients.

    @Test
    fun cancelledStaysCancellation() = runTest {
        val stub = StubHttpClient(Reply.Failure(CancellationException("cancelled")))
        assertFailsWith<CancellationException> { PvpcClient(stub).fetch(Instant.now()) }
        assertFailsWith<CancellationException> { EnergyChartsClient(PriceRegion.FRANCE, stub).fetch(Instant.now()) }
        assertFailsWith<CancellationException> { EnergyChartsClient(PriceRegion.FRANCE, stub).fetchTodayAndTomorrow(true) }
        assertFailsWith<CancellationException> { PvpcClient(stub).fetchTodayAndTomorrow(true) }
    }

    @Test
    fun nonIoErrorsBecomeNetworkErrors() = runTest {
        val stub = StubHttpClient(Reply.Failure(IllegalStateException("boom")))
        assertEquals("boom", assertFailsWith<SunWattError.Network> { PvpcClient(stub).fetch(Instant.now()) }.detail)
        assertEquals(
            "boom",
            assertFailsWith<SunWattError.Network> { EnergyChartsClient(PriceRegion.AUSTRIA, stub).fetch(Instant.now()) }.detail,
        )
    }
}
