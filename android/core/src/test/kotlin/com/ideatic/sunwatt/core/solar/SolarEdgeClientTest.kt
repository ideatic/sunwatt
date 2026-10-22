package com.ideatic.sunwatt.core.solar

import com.ideatic.sunwatt.core.Fixtures
import com.ideatic.sunwatt.core.StubHttpClient
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.SolarAPICredentials
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.services.SolarEdgeClient
import com.ideatic.sunwatt.core.services.SolarLocalTime
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EDGE_KEY = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
private val providerNow = madridAt(2026, 6, 1, 12, 35)
private val madridZone = Constants.madrid

private fun credentials(id: String = "1234567", key: String = EDGE_KEY): SolarAPICredentials = SolarAPICredentials.parse(id, key)!!

private fun edgeParsed(details: String = "solaredge-power-synthetic.json", at: Instant = providerNow): SolarSnapshot =
    SolarEdgeClient.parse(Fixtures.bytes("solaredge-overview-synthetic.json"), Fixtures.bytes(details), at, madridZone)

class SolarEdgeClientTest {
    @Test
    fun parsesOverviewInKilo() {
        val s = edgeParsed()
        assertEquals(4.2105, s.currentPowerKW!!, 1e-9)
        assertEquals(27.35, s.dailyEnergyKWh!!, 1e-9)
        assertEquals(3120.45, s.yearEnergyKWh!!, 1e-9)
        assertEquals(48210.345, s.cumulativeEnergyKWh!!, 1e-6)
        assertNull(s.stationName)
        assertEquals(providerNow, s.fetchedAt)
        assertEquals(ZonedDateTime.of(2026, 6, 1, 12, 28, 41, 0, madridZone).toInstant(), s.dataAsOf)
    }

    @Test
    fun parsesQuarterHourCurve() {
        val s = edgeParsed()
        assertEquals(96, s.curve.size)
        assertEquals(madridAt(2026, 6, 1, 0, 0), s.curve.first().time)
        assertNull(s.curve.first().kW)
        assertEquals(madridAt(2026, 6, 1, 12, 30), s.latestPoint?.time)
        assertEquals(4.830679, s.latestPoint!!.kW!!, 1e-9)
        assertEquals(27, s.curve.count { it.kW != null })
        assertTrue(s.curve.zipWithNext().all { (a, b) -> a.time < b.time })
    }

    @Test
    fun futurePointsLoseTheirValue() {
        val early = madridAt(2026, 6, 1, 9, 0)
        val s = edgeParsed(at = early)
        assertEquals(madridAt(2026, 6, 1, 9, 0), s.latestPoint?.time)
        // The last update time is never later than the fetch.
        assertEquals(early, s.dataAsOf)
    }

    @Test
    fun lastUpdateFromYesterdayIsIgnoredAfterMidnight() {
        // Overview last updated 2026-06-01 12:28; fetched at 00:30 the next day, before sunrise.
        val night = madridAt(2026, 6, 2, 0, 30)
        val empty = """{"power":{"timeUnit":"QUARTER_OF_AN_HOUR","unit":"W","values":[{"date":"2026-06-02 00:00:00","value":null}]}}"""
        val s = SolarEdgeClient.parse(Fixtures.bytes("solaredge-overview-synthetic.json"), empty.toByteArray(), night, madridZone)
        assertNull(s.dataAsOf)
    }

    @Test
    fun badBodiesThrow() {
        assertFailsWith<SunWattError.BadResponse> { SolarEdgeClient.parse("{}".toByteArray(), "{}".toByteArray(), providerNow, madridZone) }
        assertFailsWith<SunWattError.BadResponse> {
            SolarEdgeClient.parse(Fixtures.bytes("solaredge-overview-synthetic.json"), "[]".toByteArray(), providerNow, madridZone)
        }
    }

    @Test
    fun valueArraysWithAForeignElementReadAsEmpty() {
        val power = """{"power":{"unit":"W","values":[{"date":"2026-06-01 10:00:00","value":1000},7]}}"""
        val s = SolarEdgeClient.parse("""{"overview":{}}""".toByteArray(), power.toByteArray(), providerNow, madridZone)
        assertTrue(s.curve.isEmpty())
    }

    @Test
    fun kilowattAndMegawattUnits() {
        fun curveFor(unit: String): Double? {
            val power = """{"power":{"unit":"$unit","values":[{"date":"2026-06-01 10:00:00","value":2}]}}"""
            return SolarEdgeClient.parse("""{"overview":{}}""".toByteArray(), power.toByteArray(), providerNow, madridZone).curve.single().kW
        }
        assertEquals(0.002, curveFor("W"))
        assertEquals(2.0, curveFor("kW"))
        assertEquals(2000.0, curveFor("MW"))
    }

    @Test
    fun fetchRequestsOverviewAndTodaysPower() = runTest {
        val http = StubHttpClient()
        http.on("/overview", StubHttpClient.Reply.Ok(Fixtures.bytes("solaredge-overview-synthetic.json")))
        http.on("/powerDetails", StubHttpClient.Reply.Ok(Fixtures.bytes("solaredge-powerdetails-nometer-synthetic.json")))
        val s = SolarEdgeClient(credentials(), http, { providerNow }, madridZone).fetch()
        assertEquals(96, s.curve.size)
        val urls = http.requestedUrls
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.startsWith("https://monitoringapi.solaredge.com/site/1234567/") && it.contains("api_key=$EDGE_KEY") })
        val power = urls.first { URI(it).path.endsWith("/powerDetails") }
        val query = URI(power).query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("2026-06-01 00:00:00", query["startTime"])
        assertEquals("2026-06-01 23:59:59", query["endTime"])
    }

    @Test
    fun rejectedKeyIsAClearError() = runTest {
        for (status in listOf(401, 403)) {
            val http = StubHttpClient(StubHttpClient.Reply.Ok("Invalid token", status = status))
            assertFailsWith<SunWattError.SolarCredentialsRejected>("status $status") {
                SolarEdgeClient(credentials(), http, { providerNow }).fetch()
            }
        }
        val http = StubHttpClient(StubHttpClient.Reply.Ok(ByteArray(0), status = 429))
        assertFailsWith<SunWattError.RateLimited> { SolarEdgeClient(credentials(), http, { providerNow }).fetch() }
    }

    @Test
    fun networkErrorsNeverContainTheKey() = runTest {
        val leaky = IOException("failed: https://monitoringapi.solaredge.com/site/1/overview?api_key=$EDGE_KEY")
        val client = SolarEdgeClient(credentials(), StubHttpClient(StubHttpClient.Reply.Failure(leaky)), { providerNow })
        val e = assertFailsWith<SunWattError.Network> { client.fetch() }
        assertFalse(e.message.orEmpty().contains(EDGE_KEY))
        assertFalse(e.toString().contains(EDGE_KEY))
        assertContains(e.detail, "***")
        assertFalse(client.toString().contains(EDGE_KEY))
    }

    @Test
    fun cancellationPassesThrough() = runTest {
        val http = StubHttpClient(StubHttpClient.Reply.Failure(CancellationException("cancelled")))
        assertFailsWith<CancellationException> { SolarEdgeClient(credentials(), http, { providerNow }).fetch() }
    }

    @Test
    fun theKeyIsOnlyInTheQueryOfTheRequestsAndNeverPrinted() = runTest {
        val http = StubHttpClient()
        http.on("/overview", StubHttpClient.Reply.Ok(Fixtures.bytes("solaredge-overview-synthetic.json")))
        http.on("/powerDetails", StubHttpClient.Reply.Ok(Fixtures.bytes("solaredge-powerdetails-nometer-synthetic.json")))
        SolarEdgeClient(credentials(), http, { providerNow }, madridZone).fetch()
        assertTrue(http.requests.all { !it.toString().contains(EDGE_KEY) && it.headers.values.none { v -> v.contains(EDGE_KEY) } })
    }
}

class SolarEdgeHomeTest {
    private fun meterParsed(details: String) = edgeParsed(details)

    @Test
    fun meterGivesConsumptionAndGrid() {
        val s = meterParsed("solaredge-powerdetails-meter-synthetic.json")
        // Production still comes from the details.
        assertEquals(96, s.curve.size)
        assertEquals(madridAt(2026, 6, 1, 12, 30), s.latestPoint?.time)
        val home = assertNotNull(s.home)
        assertEquals(0.6, home.consumptionKW)
        assertEquals(-4.230679, home.gridKW!!, 1e-9)
        assertEquals(8.55, home.dailyConsumptionKWh!!, 1e-9)
        assertEquals(5.278748, home.dailyImportKWh!!, 1e-6)
        assertEquals(12.445927, home.dailyExportKWh!!, 1e-6)
        assertEquals(1 - 5.278748 / 8.55, home.selfSufficiency!!, 1e-6)
        assertEquals(96, home.curve.size)
    }

    @Test
    fun withoutMeterShowsNoConsumption() {
        val s = meterParsed("solaredge-powerdetails-nometer-synthetic.json")
        assertNull(s.home)
        assertEquals(96, s.curve.size)
        // The plain `power` body (older format) has no meters either.
        assertNull(meterParsed("solaredge-power-synthetic.json").home)
    }

    @Test
    fun fetchUsesPowerDetails() = runTest {
        val http = StubHttpClient()
        http.on("/overview", StubHttpClient.Reply.Ok(Fixtures.bytes("solaredge-overview-synthetic.json")))
        http.on("/powerDetails", StubHttpClient.Reply.Ok(Fixtures.bytes("solaredge-powerdetails-meter-synthetic.json")))
        val client = SolarEdgeClient(credentials("1", "ABCDEFGH12345678"), http, { providerNow }, madridZone)
        val s = client.fetch()
        assertNotNull(s.home)
        assertEquals(2, http.requestedUrls.size)
        assertTrue(http.requestedUrls.any { URI(it).path.endsWith("/powerDetails") })
    }

    @Test
    fun fetchFallsBackToPowerWithoutProductionMeter() = runTest {
        val http = StubHttpClient()
        http.on("/overview", StubHttpClient.Reply.Ok(Fixtures.bytes("solaredge-overview-synthetic.json")))
        http.on("/powerDetails", StubHttpClient.Reply.Ok("""{"powerDetails":{"timeUnit":"QUARTER_OF_AN_HOUR","unit":"W","meters":[]}}"""))
        http.on("/power", StubHttpClient.Reply.Ok(Fixtures.bytes("solaredge-power-synthetic.json")))
        val client = SolarEdgeClient(credentials("1", "ABCDEFGH12345678"), http, { providerNow }, madridZone)
        val s = client.fetch()
        assertEquals(96, s.curve.size)
        assertNull(s.home)
        assertEquals(3, http.requestedUrls.size)
    }

    @Test
    fun fallbackKeepsTheMeterReadingsForConsumption() = runTest {
        // A body with a Consumption meter but no Production meter: the curve comes from `power`, the home from the meters.
        val details = """{"powerDetails":{"unit":"W","meters":[{"type":"Consumption","values":[{"date":"2026-06-01 12:30:00","value":600}]}]}}"""
        val http = StubHttpClient()
        http.on("/overview", StubHttpClient.Reply.Ok(Fixtures.bytes("solaredge-overview-synthetic.json")))
        http.on("/powerDetails", StubHttpClient.Reply.Ok(details))
        http.on("/power", StubHttpClient.Reply.Ok(Fixtures.bytes("solaredge-power-synthetic.json")))
        val s = SolarEdgeClient(credentials(), http, { providerNow }, madridZone).fetch()
        assertEquals(96, s.curve.size)
        assertEquals(0.6, s.home?.consumptionKW)
    }
}

/** 4: the autumn DST day repeats 02:00-02:59 (Madrid, 2026-10-25). Each reading gets its own instant. */
class RepeatedHourTest {
    private val fetchedAt = madridAt(2026, 10, 25, 12)

    /** Local times as the provider sends them, oldest first: 01:45, 02:00 ... 02:45, 02:00 ... 02:45, 03:00. */
    private val wallTimes = listOf("01:45") + listOf("02:00", "02:15", "02:30", "02:45") + listOf("02:00", "02:15", "02:30", "02:45") + listOf("03:00")

    private fun expectStrictlyIncreasing(times: List<Instant>) {
        assertEquals(wallTimes.size, times.size)
        assertTrue(times.zipWithNext().all { (a, b) -> a < b })
        // 01:45 to 03:00 is 2 h 15 min of real time on this day.
        assertEquals(Duration.ofMinutes(135), Duration.between(times.first(), times.last()))
    }

    @Test
    fun solarEdgeCurve() {
        val values = wallTimes.mapIndexed { i, t -> """{"date":"2026-10-25 $t:00","value":$i}""" }.joinToString(",")
        val power = """{"power":{"unit":"W","values":[$values]}}"""
        val snapshot = SolarEdgeClient.parse("""{"overview":{}}""".toByteArray(), power.toByteArray(), fetchedAt, madridZone)
        expectStrictlyIncreasing(snapshot.curve.map { it.time })
        // The second 02:00 reading keeps its own value.
        assertEquals(5 * 0.001, snapshot.curve[5].kW)
    }

    @Test
    fun pvOutputHistory() {
        val body = wallTimes.mapIndexed { i, t -> "20261025,$t,${i * 10},NaN,${i * 100}" }.joinToString(";")
        val snapshot = com.ideatic.sunwatt.core.services.PvOutputClient.parse(body.toByteArray(), fetchedAt, madridZone)
        expectStrictlyIncreasing(snapshot.curve.map { it.time })
    }

    @Test
    fun realDuplicatesAreDropped() {
        val t = madridAt(2026, 6, 1, 12)
        val points = listOf(CurvePoint(t, 1.0), CurvePoint(t, 2.0), CurvePoint(t.plusSeconds(900), 3.0))
        val result = SolarLocalTime.inOrder(points, { it.time }, { p, time -> CurvePoint(time, p.kW) }, madridZone)
        assertEquals(listOf(1.0, 3.0), result.map { it.kW })
    }

    @Test
    fun aNewestFirstListIsTurnedAround() {
        val t = madridAt(2026, 6, 1, 12)
        val points = listOf(CurvePoint(t.plusSeconds(900), 2.0), CurvePoint(t, 1.0))
        val result = SolarLocalTime.inOrder(points, { it.time }, { p, time -> CurvePoint(time, p.kW) }, madridZone)
        assertEquals(listOf(1.0, 2.0), result.map { it.kW })
    }

    @Test
    fun localTimesThatDoNotExistOrDoNotParseAreNull() {
        // 2026-03-29 02:30 does not exist in Madrid.
        assertNull(SolarLocalTime.parse("2026-03-29 02:30:00", madridZone))
        assertNull(SolarLocalTime.parse("2026-02-30 10:00:00", madridZone))
        assertNull(SolarLocalTime.parse("2026-06-01T10:00:00", madridZone))
        assertEquals(madridAt(2026, 6, 1, 10, 5), SolarLocalTime.parse("2026-06-01 10:05", madridZone))
    }
}
