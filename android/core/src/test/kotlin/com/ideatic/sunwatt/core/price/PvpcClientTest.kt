package com.ideatic.sunwatt.core.price

import com.ideatic.sunwatt.core.Fixtures
import com.ideatic.sunwatt.core.StubHttpClient
import com.ideatic.sunwatt.core.StubHttpClient.Reply
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceGranularity
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.hourly
import com.ideatic.sunwatt.core.services.PvpcClient
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Wall-clock time in Europe/Madrid as an instant (like the Swift `madrid(...)` helper). */
internal fun madrid(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Instant =
    LocalDateTime.of(y, mo, d, h, mi).atZone(Constants.madrid).toInstant()

internal fun iso(text: String): Instant = checkNotNull(PvpcClient.parseDate(text)) { "bad date $text" }

internal fun pvpcSeries(name: String): PriceSeries = PvpcClient.parse(Fixtures.bytes(name), madrid(2026, 10, 17, 10))

class PvpcClientTest {
    private fun parse(name: String) = pvpcSeries(name)

    @Test
    fun parsesRealFixtures() {
        val day = parse("pvpc-2026-10-17.json")
        assertEquals(24, day.points.size)
        assertEquals(246.76, day.points[0].eurPerMWh)
        assertTrue(abs(day.points[0].eurPerKWh - 0.24676) < 1e-12)
        assertEquals(PriceGranularity.HOURLY, day.granularity)
        assertEquals(3600, day.points[0].durationSeconds)
        assertEquals(madrid(2026, 10, 17, 0, 0), day.points[0].start)
        assertEquals(madrid(2026, 10, 16, 20, 46).plusSeconds(35), day.sourceUpdatedAt)
        assertEquals(madrid(2026, 10, 17, 10), day.fetchedAt)
        assertEquals(PriceRegion.SPAIN, day.region)

        assertEquals(23, parse("pvpc-2026-03-29-dst23.json").points.size)
        assertEquals(25, parse("pvpc-2025-10-26-dst25.json").points.size)
    }

    @Test
    fun errorBodyIsUnavailable() {
        assertFailsWith<SunWattError.Unavailable> { parse("pvpc-error-unavailable.json") }
    }

    @Test
    fun malformedJsonIsBadResponse() {
        assertFailsWith<SunWattError.BadResponse> { PvpcClient.parse("not json".toByteArray(), Instant.EPOCH) }
        assertFailsWith<SunWattError.BadResponse> { PvpcClient.parse("[1]".toByteArray(), Instant.EPOCH) }
    }

    @Test
    fun missingPvpcEntryIsUnavailable() {
        val body = """{"included":[{"type":"Precio mercado spot","attributes":{"values":[]}}]}"""
        assertFailsWith<SunWattError.Unavailable> { PvpcClient.parse(body.toByteArray(), Instant.EPOCH) }
        val empty = """{"included":[{"type":"PVPC","attributes":{"values":[]}}]}"""
        assertFailsWith<SunWattError.Unavailable> { PvpcClient.parse(empty.toByteArray(), Instant.EPOCH) }
    }

    @Test
    fun parsesDatesWithoutFractionalSeconds() {
        val body = """{"included":[{"type":"PVPC","attributes":{"last-update":"2026-10-16T20:46:35+02:00","values":[{"value":100,"datetime":"2026-10-17T00:00:00+02:00"}]}}]}"""
        val s = PvpcClient.parse(body.toByteArray(), Instant.EPOCH)
        assertEquals(1, s.points.size)
        assertEquals(PriceGranularity.HOURLY, s.granularity)
        assertEquals(madrid(2026, 10, 17), s.points[0].start)
    }

    @Test
    fun quarterHourGranularity() {
        val s = parse("pvpc-quarter-hour-synthetic.json")
        assertEquals(96, s.points.size)
        assertEquals(PriceGranularity.QUARTER_HOURLY, s.granularity)
        assertEquals(900, s.points[0].durationSeconds)
        val hourly = s.hourly()
        assertEquals(24, hourly.size)
        assertTrue(hourly.all { it.durationSeconds == 3600L })
        // Hour 5: 100 + 50 + {0, 2, 4, 6} -> average 153.
        assertTrue(abs(hourly[5].eurPerMWh - 153) < 1e-9)
        assertEquals(madrid(2026, 10, 17, 5), hourly[5].start)
    }

    // fetch

    private fun client(stub: StubHttpClient, now: Instant = madrid(2026, 10, 17, 10)) = PvpcClient(stub, { now })

    @Test
    fun http500IsHttpError() = runTest {
        val stub = StubHttpClient()
        stub.on("apidatos", Reply.Ok("oops", status = 500))
        val error = assertFailsWith<SunWattError.Http> { client(stub).fetch(madrid(2026, 10, 17)) }
        assertEquals(500, error.status)
    }

    @Test
    fun errorBodyWithAnyStatusIsUnavailable() = runTest {
        val body = Fixtures.bytes("pvpc-error-unavailable.json")
        for (status in listOf(200, 502)) {
            val stub = StubHttpClient()
            stub.on("apidatos", Reply.Ok(body, status = status))
            assertFailsWith<SunWattError.Unavailable> { client(stub).fetch(madrid(2026, 10, 17)) }
        }
    }

    @Test
    fun networkFailureIsNetworkError() = runTest {
        val stub = StubHttpClient(Reply.Failure(IOException("offline")))
        assertFailsWith<SunWattError.Network> { client(stub).fetch(madrid(2026, 10, 17)) }
    }

    @Test
    fun fetchBuildsMadridDayUrl() = runTest {
        val stub = StubHttpClient()
        stub.on("apidatos", Reply.Ok(Fixtures.bytes("pvpc-2026-10-17.json")))
        val series = client(stub).fetch(madrid(2026, 10, 17, 18, 30))
        assertEquals(24, series.points.size)
        val url = stub.requestedUrls.first()
        assertTrue(url.startsWith("https://apidatos.ree.es/es/datos/mercados/precios-mercados-tiempo-real?"))
        assertTrue(url.contains("start_date=2026-10-17T00:00"))
        assertTrue(url.contains("end_date=2026-10-17T23:59"))
        assertTrue(url.contains("time_trunc=hour"))
    }

    private fun tomorrowBody(): String = Fixtures.text("pvpc-2026-10-17.json")
        .replace("2026-10-17", "2026-10-18")
        .replace("2026-10-16T20:46:35", "2026-10-17T20:46:35")

    @Test
    fun todayAndTomorrowMerged() = runTest {
        val stub = StubHttpClient()
        stub.on("start_date=2026-10-18", Reply.Ok(tomorrowBody()))
        stub.on("start_date=2026-10-17", Reply.Ok(Fixtures.bytes("pvpc-2026-10-17.json")))
        val now = madrid(2026, 10, 17, 10)
        val series = client(stub, now).fetchTodayAndTomorrow(includeTomorrow = true)
        assertEquals(48, series.points.size)
        assertEquals(series.points.sortedBy { it.start }, series.points)
        assertEquals(now, series.fetchedAt)
        assertEquals(PriceGranularity.HOURLY, series.granularity)
        assertEquals(madrid(2026, 10, 17, 20, 46).plusSeconds(35), series.sourceUpdatedAt)
        val urls = stub.requestedUrls
        assertEquals(2, urls.size)
        assertTrue(urls.any { it.contains("start_date=2026-10-17T00:00") && it.contains("end_date=2026-10-17T23:59") })
        assertTrue(urls.any { it.contains("start_date=2026-10-18T00:00") && it.contains("end_date=2026-10-18T23:59") })
    }

    @Test
    fun tomorrowFailureIsSwallowed() = runTest {
        val stub = StubHttpClient()
        stub.on("start_date=2026-10-18", Reply.Ok(Fixtures.bytes("pvpc-error-unavailable.json"), status = 502))
        stub.on("start_date=2026-10-17", Reply.Ok(Fixtures.bytes("pvpc-2026-10-17.json")))
        val series = client(stub).fetchTodayAndTomorrow(includeTomorrow = true)
        assertEquals(24, series.points.size)
        assertEquals(2, stub.requestedUrls.size)
    }

    @Test
    fun excludingTomorrowMakesOneRequest() = runTest {
        val stub = StubHttpClient()
        stub.on("start_date=2026-10-17", Reply.Ok(Fixtures.bytes("pvpc-2026-10-17.json")))
        val series = client(stub).fetchTodayAndTomorrow(includeTomorrow = false)
        assertEquals(24, series.points.size)
        assertEquals(1, stub.requestedUrls.size)
    }

    @Test
    fun todayFailureThrows() = runTest {
        val stub = StubHttpClient()
        stub.on("start_date=2026-10-18", Reply.Ok(tomorrowBody()))
        stub.on("start_date=2026-10-17", Reply.Ok(Fixtures.bytes("pvpc-error-unavailable.json"), status = 502))
        assertFailsWith<SunWattError.Unavailable> { client(stub).fetchTodayAndTomorrow(includeTomorrow = true) }
    }
}
