package com.ideatic.sunwatt.core.solar

import com.ideatic.sunwatt.core.Fixtures
import com.ideatic.sunwatt.core.StubHttpClient
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.services.FusionSolarKioskClient
import com.ideatic.sunwatt.core.services.KioskEndpoint
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wraps an inner JSON string the way the kiosk does: HTML-escaped inside `data`. */
internal fun kioskBody(inner: String, success: Boolean = true): ByteArray {
    val escaped = inner.replace("&", "&amp;").replace("\"", "&quot;")
    return """{"data":${jsonString(escaped)},"success":$success,"failCode":0}""".toByteArray()
}

private fun jsonString(text: String): String = buildString {
    append('"')
    for (c in text) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}

internal fun kioskCurveJson(labels: List<String>, values: List<String>): String {
    val x = labels.joinToString(",") { "\"$it\"" }
    val y = values.joinToString(",") { "\"$it\"" }
    return "{\"powerCurve\":{\"xAxis\":[$x],\"activePower\":[$y]}}"
}

private fun labels288(): List<String> = (0 until 288).map { "%02d:%02d".format(it / 12, (it % 12) * 5) }

private val day = madridAt(2026, 10, 17, 9, 0)

private fun parse(body: ByteArray, fetchedAt: Instant = day, on: Instant = day): SolarSnapshot =
    FusionSolarKioskClient.parse(body, fetchedAt, on)

class KioskParsingTest {
    @Test
    fun nightFixture() {
        val fetched = madridAt(2026, 10, 17, 1, 20)
        val s = parse(Fixtures.bytes("kiosk-night.json"), fetched)
        assertEquals(288, s.curve.size)
        assertTrue(s.curve.all { it.kW == null })
        assertEquals(0.0, s.currentPowerKW)
        assertEquals(0.0, s.dailyEnergyKWh)
        assertNull(s.dataAsOf)
        assertEquals(fetched, s.fetchedAt)
        assertEquals("Demo Station", s.stationName)
        assertEquals(madridAt(2026, 10, 17, 0, 0), s.curve.first().time)
        assertEquals(madridAt(2026, 10, 17, 23, 55), s.curve.last().time)
        assertTrue(s.isIdle)
        assertTrue(s.monthEnergyKWh != null && s.yearEnergyKWh != null && s.cumulativeEnergyKWh != null)
    }

    @Test
    fun syntheticDayFixture() {
        val s = parse(Fixtures.bytes("kiosk-day-synthetic.json"), madridAt(2026, 10, 17, 13, 40))
        assertEquals(288, s.curve.size)
        assertEquals(4.812, s.currentPowerKW)
        assertEquals(14.37, s.dailyEnergyKWh)
        assertEquals(697.33, s.monthEnergyKWh)
        assertEquals(17056.36, s.yearEnergyKWh)
        assertEquals(44159.44, s.cumulativeEnergyKWh)
        assertEquals(madridAt(2026, 10, 17, 13, 30), s.dataAsOf)
        assertEquals(madridAt(2026, 10, 17, 13, 30), s.latestPoint?.time)
        val peak = assertNotNull(s.peakKW)
        assertTrue(peak > 4.9 && peak <= 5.0)
        assertNull(s.curve.first { it.time == madridAt(2026, 10, 17, 7, 55) }.kW)
        assertNotNull(s.curve.first { it.time == madridAt(2026, 10, 17, 8, 0) }.kW)
        assertTrue(s.curve.filter { it.time > madridAt(2026, 10, 17, 13, 30) }.all { it.kW == null })
        assertFalse(s.isIdle)
    }

    @Test
    fun stringsAndNumbersInRealKpi() {
        val inner = """{"realKpi":{"realTimePower":"4.81","dailyEnergy":12.5,"monthEnergy":"300","yearEnergy":null,"cumulativeEnergy":"n/a"}}"""
        val s = parse(kioskBody(inner))
        assertEquals(4.81, s.currentPowerKW)
        assertEquals(12.5, s.dailyEnergyKWh)
        assertEquals(300.0, s.monthEnergyKWh)
        assertNull(s.yearEnergyKWh)
        assertNull(s.cumulativeEnergyKWh)
        assertTrue(s.curve.isEmpty())
    }

    @Test
    fun nonDecimalTextAndBooleansAreNotNumbers() {
        val inner = """{"realKpi":{"realTimePower":"1.5f","dailyEnergy":true,"monthEnergy":"Infinity","yearEnergy":"NaN","cumulativeEnergy":" 7 "}}"""
        val s = parse(kioskBody(inner))
        assertNull(s.currentPowerKW)
        assertNull(s.dailyEnergyKWh)
        assertNull(s.monthEnergyKWh)
        assertNull(s.yearEnergyKWh)
        assertEquals(7.0, s.cumulativeEnergyKWh)
    }

    @Test
    fun missingMarkersBecomeNull() {
        val inner = """{"powerCurve":{"xAxis":["10:00","10:05","10:10","10:15","10:20","10:25"],"activePower":[1.5,"2.25","-","--","",null]}}"""
        val s = parse(kioskBody(inner), madridAt(2026, 10, 17, 12, 0))
        assertEquals(listOf(1.5, 2.25, null, null, null, null), s.curve.map { it.kW })
        assertEquals(madridAt(2026, 10, 17, 10, 5), s.dataAsOf)
    }

    @Test
    fun negativeNightPowerReadsAsZero() {
        val inner = """{"realKpi":{"realTimePower":-0.02,"dailyEnergy":-1},"powerCurve":{"xAxis":["01:00"],"activePower":[-0.5]}}"""
        val s = parse(kioskBody(inner))
        assertEquals(0.0, s.currentPowerKW)
        assertEquals(0.0, s.dailyEnergyKWh)
        assertEquals(0.0, s.curve.single().kW)
    }

    @Test
    fun successFalseThrows() {
        assertFailsWith<SunWattError.KioskLinkRejected> { parse(kioskBody("{}", success = false)) }
    }

    @Test
    fun missingDataThrows() {
        assertFailsWith<SunWattError.BadResponse> { parse("""{"success":true,"failCode":0}""".toByteArray()) }
    }

    @Test
    fun malformedInnerThrows() {
        assertFailsWith<SunWattError.BadResponse> { parse("""{"data":"this is &quot;not json","success":true,"failCode":0}""".toByteArray()) }
        assertFailsWith<SunWattError.BadResponse> { parse("garbage".toByteArray()) }
    }

    @Test
    fun springForwardDay() {
        // 2026-03-29: 02:00-02:59 does not exist in Madrid.
        val labels = listOf("01:55", "02:00", "02:30", "03:00", "03:05")
        val body = kioskBody(kioskCurveJson(labels, listOf("1", "2", "3", "4", "5")))
        val s = parse(body, day, madridAt(2026, 3, 29, 12, 0))
        assertEquals(3, s.curve.size)
        assertEquals(listOf(1.0, 4.0, 5.0), s.curve.map { it.kW })
        assertTrue(s.curve.zipWithNext().all { (a, b) -> a.time < b.time })
        assertEquals(madridAt(2026, 3, 29, 1, 55), s.curve[0].time)
        assertEquals(madridAt(2026, 3, 29, 3, 0), s.curve[1].time)
    }

    @Test
    fun fallBackDay() {
        // 2026-10-25: 02:00-02:59 happens twice. A repeated label maps to the later instant.
        val labels = listOf("01:55", "02:00", "02:30", "02:55", "02:00", "02:30", "03:00")
        val body = kioskBody(kioskCurveJson(labels, listOf("1", "2", "3", "4", "5", "6", "7")))
        val noon = madridAt(2026, 10, 25, 12, 0)
        val s = parse(body, noon, noon)
        assertEquals(7, s.curve.size)
        assertTrue(s.curve.zipWithNext().all { (a, b) -> a.time < b.time })
        assertEquals(Duration.ofHours(1), Duration.between(s.curve[1].time, s.curve[4].time))
        assertEquals(madridAt(2026, 10, 25, 2, 0), s.curve[1].time)
        assertEquals(madridAt(2026, 10, 25, 3, 0), s.curve.last().time)
        assertEquals((1..7).map { it.toDouble() }, s.curve.map { it.kW })
    }

    @Test
    fun fullDayLabelsOnDstDaysDoNotCrash() {
        val body = kioskBody(kioskCurveJson(labels288(), List(288) { "1.0" }))
        for (d in listOf(madridAt(2026, 3, 29, 12, 0), madridAt(2026, 10, 25, 12, 0))) {
            val s = parse(body, d, d)
            assertTrue(s.curve.isNotEmpty())
            assertTrue(s.curve.zipWithNext().all { (a, b) -> a.time < b.time })
        }
    }

    // Hardening: parsing

    @Test
    fun neitherKpiNorCurveIsBadResponse() {
        for (inner in listOf("{}", """{"stationOverview":{"stationName":"x"}}""", """{"realKpi":"nope"}""")) {
            assertFailsWith<SunWattError.BadResponse>(inner) { parse(kioskBody(inner)) }
        }
    }

    @Test
    fun curveFromYesterdayNearMidnightIsDiscarded() {
        val fetched = madridAt(2026, 10, 17, 0, 10)
        val s = parse(kioskBody(kioskCurveJson(labels288(), List(288) { "1.5" })), fetched, fetched)
        assertEquals(288, s.curve.size)
        // Up to fetchedAt + 5 min (00:15) is kept; everything later has no value.
        assertEquals(madridAt(2026, 10, 17, 0, 15), s.curve.filter { it.kW != null }.map { it.time }.last())
        assertTrue(s.curve.filter { it.time > madridAt(2026, 10, 17, 0, 15) }.all { it.kW == null })
        assertEquals(madridAt(2026, 10, 17, 0, 15), s.dataAsOf)
    }

    @Test
    fun allFutureValuesLeaveNoDataAsOf() {
        val fetched = madridAt(2026, 10, 17, 0, 10)
        val s = parse(kioskBody(kioskCurveJson(listOf("05:00", "05:05"), listOf("1", "2"))), fetched, fetched)
        assertEquals(2, s.curve.size)
        assertNull(s.dataAsOf)
        assertNull(s.latestPoint)
    }

    @Test
    fun exactPointCountsOnDstDays() {
        val body = kioskBody(kioskCurveJson(labels288(), List(288) { "1.0" }))
        val spring = madridAt(2026, 3, 29, 23, 0)
        assertEquals(276, parse(body, spring, spring).curve.size)
        val fall = madridAt(2026, 10, 25, 23, 0)
        assertEquals(288, parse(body, fall, fall).curve.size)
        val normal = madridAt(2026, 10, 17, 23, 55)
        assertEquals(288, parse(body, normal, normal).curve.size)
    }

    @Test
    fun badLabelsAreSkipped() {
        val late = madridAt(2026, 10, 17, 23)
        val body = kioskBody(kioskCurveJson(listOf("10:00", "24:00", "ab", "10:5", "10:61", "", "10:10"), listOf("1", "2", "3", "4", "5", "6", "7")))
        val s = parse(body, late, late)
        assertEquals(listOf(1.0, 4.0, 7.0), s.curve.map { it.kW })
        assertEquals(listOf(madridAt(2026, 10, 17, 10, 0), madridAt(2026, 10, 17, 10, 5), madridAt(2026, 10, 17, 10, 10)), s.curve.map { it.time })
    }

    @Test
    fun labelValueMismatchUsesMinimumCount() {
        val late = madridAt(2026, 10, 17, 23)
        val moreLabels = kioskBody(kioskCurveJson(listOf("10:00", "10:05", "10:10"), listOf("1", "2")))
        assertEquals(listOf(1.0, 2.0), parse(moreLabels, late, late).curve.map { it.kW })
        val moreValues = kioskBody(kioskCurveJson(listOf("10:00", "10:05"), listOf("1", "2", "3")))
        assertEquals(listOf(1.0, 2.0), parse(moreValues, late, late).curve.map { it.kW })
    }
}

class KioskFetchTest {
    private val endpoint = KioskEndpoint("host.example", "TOKEN+1")
    private val fixedNow = madridAt(2026, 10, 17, 14, 0)
    private val secretEndpoint = KioskEndpoint("host.example", "SECRETKK")

    private fun client(http: StubHttpClient, on: KioskEndpoint = endpoint) = FusionSolarKioskClient(on, http, { fixedNow })

    @Test
    fun fetchRequestsApiUrlAndParses() = runTest {
        val http = StubHttpClient()
        http.on("station-kiosk-file", StubHttpClient.Reply.Ok(Fixtures.bytes("kiosk-day-synthetic.json")))
        val s = client(http).fetch()
        assertEquals(listOf(endpoint.apiURL), http.requestedUrls)
        assertEquals("https://host.example/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=TOKEN%2B1", http.requestedUrls.first())
        assertEquals(fixedNow, s.fetchedAt)
        assertEquals(madridAt(2026, 10, 17, 13, 30), s.dataAsOf)
        assertEquals(madridAt(2026, 10, 17, 0, 0), s.curve.first().time)
    }

    @Test
    fun http500() = runTest {
        val http = StubHttpClient()
        http.on("station-kiosk-file", StubHttpClient.Reply.Ok(ByteArray(0), status = 500))
        val e = assertFailsWith<SunWattError.Http> { client(http).fetch() }
        assertEquals(500, e.status)
    }

    @Test
    fun ioErrorBecomesNetwork() = runTest {
        val http = StubHttpClient(StubHttpClient.Reply.Failure(IOException("Unable to resolve host")))
        val e = assertFailsWith<SunWattError.Network> { client(http).fetch() }
        assertTrue(e.detail.isNotEmpty())
    }

    @Test
    fun successFalseFromServer() = runTest {
        val http = StubHttpClient()
        http.on("station-kiosk-file", StubHttpClient.Reply.Ok(kioskBody("{}", success = false)))
        assertFailsWith<SunWattError.KioskLinkRejected> { client(http).fetch() }
    }

    @Test
    fun rejectionAndForbiddenStatusesThrowLinkRejected() = runTest {
        for (status in listOf(401, 403, 404)) {
            val http = StubHttpClient(StubHttpClient.Reply.Ok(ByteArray(0), status = status))
            assertFailsWith<SunWattError.KioskLinkRejected>("status $status") { client(http).fetch() }
        }
        val http = StubHttpClient(StubHttpClient.Reply.Ok(ByteArray(0), status = 500))
        assertEquals(500, assertFailsWith<SunWattError.Http> { client(http).fetch() }.status)
    }

    @Test
    fun cancellationPassesThrough() = runTest {
        val http = StubHttpClient(StubHttpClient.Reply.Failure(CancellationException("cancelled")))
        assertFailsWith<CancellationException> { client(http).fetch() }
    }

    @Test
    fun anyOtherExceptionBecomesNetwork() = runTest {
        val http = StubHttpClient(StubHttpClient.Reply.Failure(IllegalStateException("boom")))
        assertEquals("boom", assertFailsWith<SunWattError.Network> { client(http).fetch() }.detail)
    }

    @Test
    fun errorsNeverContainTheToken() = runTest {
        val url = secretEndpoint.apiURL
        val failures = listOf(
            IOException("timed out: $url"),
            IOException("Unable to resolve host"),
            IllegalStateException("failed $url"),
        )
        for (failure in failures) {
            val e = assertFailsWith<SunWattError> { client(StubHttpClient(StubHttpClient.Reply.Failure(failure)), secretEndpoint).fetch() }
            assertFalse(e.message.orEmpty().contains("SECRETKK"))
            assertFalse(e.toString().contains("SECRETKK"))
        }
        val bodies = listOf(
            StubHttpClient.Reply.Ok("x", status = 503),
            StubHttpClient.Reply.Ok("""{"success":false,"kk":"SECRETKK"}"""),
        )
        for (reply in bodies) {
            val e = assertFailsWith<SunWattError> { client(StubHttpClient(reply), secretEndpoint).fetch() }
            assertFalse(e.message.orEmpty().contains("SECRETKK"))
            assertFalse(e.toString().contains("SECRETKK"))
        }
        assertFalse(client(StubHttpClient(), secretEndpoint).toString().contains("SECRETKK"))
        assertContains(client(StubHttpClient(), secretEndpoint).toString(), "***")
    }

    @Test
    fun theRequestAsksForJsonAndIsAGet() = runTest {
        val http = StubHttpClient()
        http.on("station-kiosk-file", StubHttpClient.Reply.Ok(Fixtures.bytes("kiosk-day-synthetic.json")))
        client(http).fetch()
        val request = http.requests.single()
        assertEquals("GET", request.method)
        assertEquals("application/json", request.headers["Accept"])
    }
}
