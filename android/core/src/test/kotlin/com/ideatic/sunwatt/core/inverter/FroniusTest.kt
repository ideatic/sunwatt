package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.Fixtures
import com.ideatic.sunwatt.core.StubHttpClient
import com.ideatic.sunwatt.core.StubHttpClient.Reply
import com.ideatic.sunwatt.core.models.LocalInverterKind
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.HttpRequest
import com.ideatic.sunwatt.core.services.HttpResponse
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fronius Solar API on the home network: parsing, errors and "Find inverter". */
class FroniusTest {
    private val endpoint = ModbusEndpoint("192.168.1.30", 80)

    private fun stub(fixture: String): StubHttpClient =
        StubHttpClient().also { it.on("GetPowerFlowRealtimeData", Reply.Ok(Fixtures.bytes(fixture))) }

    private fun close(a: Double?, b: Double): Boolean = a != null && abs(a - b) < 0.0005

    @Test
    fun daytimeReadingWithMeter() {
        val reading = FroniusSolarAPIClient.parse(Fixtures.bytes("fronius-powerflow-day-synthetic.json"))
        assertTrue(close(reading.activePowerKW, 3.2109))
        assertTrue(close(reading.dailyEnergyKWh, 7.954))
        assertTrue(close(reading.totalEnergyKWh, 12345.678))
        // P_Grid -1100.5 W: feeding the grid.
        assertTrue(close(reading.meterActivePowerKW, 1.1005))
        assertTrue(close(reading.homeConsumptionKW, 2.1104))
    }

    /** GEN24 at night with a battery: no PV, no energy today, the battery covers most of the home. */
    @Test
    fun gen24NightWithBattery() {
        val reading = FroniusSolarAPIClient.parse(Fixtures.bytes("fronius-powerflow-gen24-night-synthetic.json"))
        assertEquals(0.0, reading.activePowerKW)
        assertNull(reading.dailyEnergyKWh)
        assertTrue(close(reading.totalEnergyKWh, 8765.4321))
        assertTrue(close(reading.meterActivePowerKW, -0.3502))
        // P_Load, not production + grid (that would miss the battery's 1.28 kW).
        assertTrue(close(reading.homeConsumptionKW, 1.6305))
        val snapshot = LocalInverterSolarClient.merge(reading, null, madrid(2026, 6, 1, 23, 10))
        assertTrue(close(snapshot.home?.consumptionKW, 1.6305))
        assertTrue(close(snapshot.home?.gridKW, 0.3502))
    }

    @Test
    fun readsWithOneGet() = runTest {
        val http = stub("fronius-powerflow-day-synthetic.json")
        val client = FroniusSolarAPIClient(endpoint, http)
        val (model, reading) = client.identifyLive()
        assertEquals("Fronius", model)
        assertEquals(reading, client.readLive())
        assertTrue(http.requests.size == 2 && http.requests.all { it.method == "GET" && it.body == null })
        assertEquals("http://192.168.1.30:80/solar_api/v1/GetPowerFlowRealtimeData.fcgi", http.requestedUrls.first())
    }

    /** No connection, another device on port 80, or the inverter busy: "no inverter there". */
    @Test
    fun anythingElseIsUnreachable() = runTest {
        val answers = listOf(
            Reply.Failure(IOException("connection refused")),
            Reply.Ok("<html>Router</html>", status = 404),
            Reply.Ok("<html>Router</html>"),
            Reply.Ok("""{"Head":{"Status":{"Code":8,"Reason":"busy"}},"Body":{"Data":{}}}"""),
            Reply.Ok("""{"Head":{"Status":{"Code":0}},"Body":{"Data":{}}}"""),
            Reply.Ok("[1,2]"),
        )
        for (answer in answers) {
            assertFailsWith<SunWattError.InverterUnreachable> { FroniusSolarAPIClient(endpoint, StubHttpClient(answer)).readLive() }
        }
        assertFailsWith<CancellationException> {
            FroniusSolarAPIClient(endpoint, StubHttpClient(Reply.Failure(CancellationException("cancelled")))).readLive()
        }
    }

    @Test
    fun cancellingAHangingRequestPassesCancellationThrough() = runTest {
        val job = async { FroniusSolarAPIClient(endpoint, StubHttpClient(Reply.Hang)).readLive() }
        testScheduler.advanceUntilIdle()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
    }

    @Test
    fun theKindUsesThePortOfTheSolarApi() {
        assertEquals(listOf(80), LocalInverterKind.FRONIUS.ports)
        assertEquals(80, LocalInverterKind.FRONIUS.defaultPort)
        assertEquals(FroniusSolarAPIClient.PORT, LocalInverterKind.FRONIUS.defaultPort)
        assertTrue(LocalInverterKind.FRONIUS.reader(endpoint, null, StubHttpClient()) is FroniusSolarAPIClient)
        assertTrue(LocalInverterKind.HUAWEI.reader(endpoint) is HuaweiModbusClient)
        assertTrue(LocalInverterKind.SUN_SPEC.reader(endpoint) is SunSpecClient)
        assertEquals(SunSpecClient.usualPorts, LocalInverterKind.SUN_SPEC.ports)
        assertEquals(SunSpecClient.usualUnitIDs, LocalInverterKind.SUN_SPEC.unitIDs)
    }

    @Test
    fun addressesUseThePortOfTheKind() {
        val plain = ModbusEndpoint.parse("192.168.1.30", defaultPort = 80)
        assertTrue(plain?.port == 80 && plain.text(80) == "192.168.1.30")
        val other = ModbusEndpoint.parse("192.168.1.30:8080", defaultPort = 80)
        assertTrue(other?.port == 8080 && other.text(80) == "192.168.1.30:8080")
        // Modbus addresses are unchanged.
        assertEquals(502, ModbusEndpoint.parse("192.168.1.40")?.port)
    }

    @Test
    fun identifyForFindInverter() = runTest {
        val http = stub("fronius-powerflow-day-synthetic.json")
        val match = InverterFinder.identify(LocalInverterKind.FRONIUS, "192.168.1.30", listOf(80), listOf(1), null, http).getOrThrow()
        assertTrue(match.address == "192.168.1.30" && match.model == "Fronius")
        assertTrue(close(match.reading.activePowerKW, 3.2109))

        val finder = InverterFinder(
            kind = LocalInverterKind.FRONIUS,
            candidates = { listOf("192.168.1.1", "192.168.1.30") },
            // The router answers on port 80 too, but not as a Solar API.
            probe = { _, port -> port == 80 },
            makeTransport = null,
            http = RoutedByHost("192.168.1.30", Fixtures.bytes("fronius-powerflow-day-synthetic.json")),
        )
        assertEquals(listOf("192.168.1.30"), finder.find().map { it.address })
    }

    /** At home the inverter's reading; away (no answer, no cloud) the loader keeps the last data. */
    @Test
    fun plantWithoutCloud() = runTest {
        val now = madrid(2026, 6, 1, 12, 35)
        val home = LocalInverterSolarClient(FroniusSolarAPIClient(endpoint, stub("fronius-powerflow-day-synthetic.json")), null, now = { now })
        val snapshot = home.fetch()
        assertTrue(snapshot.isLive == true && close(snapshot.currentPowerKW, 3.2109) && close(snapshot.dailyEnergyKWh, 7.954))
        val away = LocalInverterSolarClient(FroniusSolarAPIClient(endpoint, StubHttpClient()), null, now = { now })
        assertFailsWith<SunWattError.InverterUnreachable> { away.fetch() }
    }
}

/** The Solar API answer for one host; every other host answers like a router's web page. */
private class RoutedByHost(private val fronius: String, private val body: ByteArray) : HttpClient {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val isFronius = request.url.contains("://$fronius:")
        return if (isFronius) HttpResponse(200, body = body) else HttpResponse(404, body = "<html>Router</html>".toByteArray())
    }
}
