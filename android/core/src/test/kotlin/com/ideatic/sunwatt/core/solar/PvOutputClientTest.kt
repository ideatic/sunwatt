package com.ideatic.sunwatt.core.solar

import com.ideatic.sunwatt.core.Fixtures
import com.ideatic.sunwatt.core.StubHttpClient
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.SolarAPICredentials
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.services.PvOutputClient
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PV_KEY = "0123456789abcdef0123456789abcdef01234567"
private val pvNow = madridAt(2026, 6, 1, 12, 35)
private val pvZone = Constants.madrid

private fun pvCredentials(id: String = "42"): SolarAPICredentials = SolarAPICredentials.parse(id, PV_KEY)!!

private fun pvParsed(fixture: String = "pvoutput-status-synthetic.txt", at: Instant = pvNow): SolarSnapshot =
    PvOutputClient.parse(Fixtures.bytes(fixture), at, pvZone)

class PvOutputClientTest {
    @Test
    fun parsesStatusHistory() {
        val s = pvParsed()
        assertEquals(67, s.curve.size)
        assertEquals(madridAt(2026, 6, 1, 7, 0), s.curve.first().time)
        assertEquals(4.831, s.currentPowerKW!!, 1e-9)
        assertEquals(15.318, s.dailyEnergyKWh!!, 1e-9)
        assertEquals(madridAt(2026, 6, 1, 12, 30), s.dataAsOf)
        assertNull(s.stationName)
    }

    @Test
    fun missingPowerUsesTheAverage() {
        val s = pvParsed()
        // Record 20 (08:40) has "NaN" instantaneous power and a numeric average.
        assertEquals(madridAt(2026, 6, 1, 8, 40), s.curve[20].time)
        assertNotNull(s.curve[20].kW)
    }

    @Test
    fun oldLatestStatusMeansNoPowerNow() {
        val s = pvParsed(at = madridAt(2026, 6, 1, 14, 0))
        assertEquals(0.0, s.currentPowerKW)
        assertEquals(15.318, s.dailyEnergyKWh!!, 1e-9)
    }

    @Test
    fun futureStatusesAreIgnored() {
        val s = pvParsed(at = madridAt(2026, 6, 1, 10, 0))
        assertEquals(madridAt(2026, 6, 1, 10, 5), s.latestPoint?.time)
        assertNull(s.curve.last().kW)
        assertTrue((s.dailyEnergyKWh ?: 99.0) < 15.318)
    }

    @Test
    fun garbageThrows() {
        assertFailsWith<SunWattError.BadResponse> { PvOutputClient.parse("hello".toByteArray(), pvNow, pvZone) }
        assertFailsWith<SunWattError.BadResponse> {
            PvOutputClient.parse("20261301,10:00,1,1,1;20260601,25:00,1,1,1".toByteArray(), pvNow, pvZone)
        }
    }

    @Test
    fun fetchSendsKeyInHeadersOnly() = runTest {
        val http = StubHttpClient(StubHttpClient.Reply.Ok(Fixtures.bytes("pvoutput-status-synthetic.txt")))
        val s = PvOutputClient(pvCredentials(), http, { pvNow }, pvZone).fetch()
        assertEquals(67, s.curve.size)
        val request = http.requests.single()
        val uri = URI(request.url)
        assertEquals("pvoutput.org", uri.host)
        assertEquals("/service/r2/getstatus.jsp", uri.path)
        assertFalse(request.url.contains(PV_KEY))
        assertEquals(PV_KEY, request.headers["X-Pvoutput-Apikey"])
        assertEquals("42", request.headers["X-Pvoutput-SystemId"])
        assertEquals("text/plain", request.headers["Accept"])
        assertFalse(request.toString().contains(PV_KEY))
    }

    @Test
    fun noStatusYetIsADayWithoutProduction() = runTest {
        val http = StubHttpClient(StubHttpClient.Reply.Ok(Fixtures.bytes("pvoutput-no-status.txt"), status = 400))
        val s = PvOutputClient(pvCredentials(), http, { pvNow }).fetch()
        assertTrue(s.currentPowerKW == 0.0 && s.dailyEnergyKWh == 0.0)
        assertTrue(s.curve.isEmpty() && s.dataAsOf == null)
        assertTrue(s.isIdle)
    }

    @Test
    fun errorsAreMapped() = runTest {
        val cases = listOf(
            Triple(401, "Unauthorized 401: Invalid API Key", SunWattError.SolarCredentialsRejected),
            Triple(403, "Forbidden 403: Read access denied", SunWattError.SolarCredentialsRejected),
            Triple(403, "Forbidden 403: Exceeded 60 requests per hour", SunWattError.RateLimited),
            Triple(429, "", SunWattError.RateLimited),
            Triple(400, "Bad request 400: Invalid system id", SunWattError.Http(400)),
        )
        for ((status, body, expected) in cases) {
            val http = StubHttpClient(StubHttpClient.Reply.Ok(body, status = status))
            val e = assertFailsWith<SunWattError>("$status $body") { PvOutputClient(pvCredentials(), http, { pvNow }).fetch() }
            assertEquals(expected, e, "$status $body")
        }
    }

    @Test
    fun networkErrorsNeverContainTheKey() = runTest {
        val leaky = IOException("failed: pvoutput.org key $PV_KEY")
        val client = PvOutputClient(pvCredentials(), StubHttpClient(StubHttpClient.Reply.Failure(leaky)), { pvNow })
        val e = assertFailsWith<SunWattError.Network> { client.fetch() }
        assertFalse(e.message.orEmpty().contains(PV_KEY))
        assertFalse(e.toString().contains(PV_KEY))
        assertFalse(client.toString().contains(PV_KEY))
    }

    @Test
    fun cancellationPassesThrough() = runTest {
        val http = StubHttpClient(StubHttpClient.Reply.Failure(CancellationException("cancelled")))
        assertFailsWith<CancellationException> { PvOutputClient(pvCredentials(), http, { pvNow }).fetch() }
    }
}

class PvOutputHomeTest {
    @Test
    fun consumptionColumns() {
        val s = pvParsed("pvoutput-status-consumption-synthetic.txt")
        val home = assertNotNull(s.home)
        assertEquals(0.7, home.consumptionKW)
        assertEquals(-4.131, home.gridKW!!, 1e-9)
        assertEquals(3.908, home.dailyConsumptionKWh!!, 1e-9)
        assertEquals(0.216875, home.dailyImportKWh!!, 1e-6)
        assertEquals(11.483167, home.dailyExportKWh!!, 1e-6)
        assertEquals(67, home.curve.size)
    }

    @Test
    fun withoutConsumptionShowsNone() {
        assertNull(pvParsed().home)
    }

    @Test
    fun oldConsumptionIsNotNow() {
        val s = pvParsed("pvoutput-status-consumption-synthetic.txt", madridAt(2026, 6, 1, 14, 0))
        assertNull(s.home?.consumptionKW)
        assertNull(s.home?.gridKW)
        assertNotNull(s.home?.dailyConsumptionKWh)
    }

    @Test
    fun kioskHasNoConsumption() {
        val s = com.ideatic.sunwatt.core.services.FusionSolarKioskClient.parse(Fixtures.bytes("kiosk-night.json"), pvNow, pvNow)
        assertNull(s.home)
    }
}
