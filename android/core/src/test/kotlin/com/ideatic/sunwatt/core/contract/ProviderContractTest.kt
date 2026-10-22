package com.ideatic.sunwatt.core.contract

import com.ideatic.sunwatt.core.Fixtures
import com.ideatic.sunwatt.core.StubHttpClient
import com.ideatic.sunwatt.core.StubHttpClient.Reply
import com.ideatic.sunwatt.core.inverter.FakeModbusTransport
import com.ideatic.sunwatt.core.inverter.HangingModbusTransport
import com.ideatic.sunwatt.core.inverter.ModbusFrameFailure
import com.ideatic.sunwatt.core.models.LocalInverterKind
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.PriceSource
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.services.EnergyChartsClient
import com.ideatic.sunwatt.core.services.PvpcClient
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The contracts written on `SolarDataSource`, `PriceDataSource` and `LocalInverterReader`, checked against every
 * provider (twin of `ProviderContractTests.swift`). The tables are in `ContractCases.kt`.
 */
class ProviderContractTest {
    // Coverage: a new entry cannot be added without a case.

    @Test
    fun everySolarProviderHasACase() {
        assertEquals(SolarProvider.entries.toSet(), SolarContractCase.all.map { it.account.provider }.toSet())
    }

    @Test
    fun everyPriceRegionHasACase() {
        assertEquals(PriceRegion.entries.toSet(), PriceContractCase.all.map { it.region }.toSet())
    }

    @Test
    fun everyInverterKindHasACase() {
        assertEquals(LocalInverterKind.entries.toSet(), InverterContractCase.all.map { it.kind }.toSet())
    }

    @Test
    fun everyInverterCaseBuildsTheReaderOfItsKind() {
        val endpoint = ModbusEndpoint("x")
        for (case in InverterContractCase.all) {
            val expected = when (case) {
                is InverterContractCase.Modbus -> case.make(endpoint, { null })
                is InverterContractCase.Http -> case.make(endpoint, StubHttpClient())
            }
            assertEquals(expected::class, case.kind.reader(endpoint, { null }, StubHttpClient())::class, case.name)
        }
    }

    // Solar

    private fun checkSnapshotShape(case: SolarContractCase, s: SolarSnapshot) {
        val name = case.name
        assertEquals(case.now, s.fetchedAt, "$name: fetchedAt is the client's clock")
        // Today's curve: sorted, no duplicate times, one local day.
        val times = s.curve.map { it.time }
        assertTrue(times.zipWithNext().all { (a, b) -> a < b }, "$name: curve sorted, no duplicates")
        if (times.isNotEmpty()) {
            assertTrue(Duration.between(times.first(), times.last()) <= Duration.ofHours(25), "$name: curve spans one day")
        }
        // Never negative.
        assertTrue(s.curve.all { (it.kW ?: 0.0) >= 0 }, "$name: curve not negative")
        for ((label, value) in listOf(
            "current" to s.currentPowerKW, "today" to s.dailyEnergyKWh, "month" to s.monthEnergyKWh,
            "year" to s.yearEnergyKWh, "total" to s.cumulativeEnergyKWh,
        )) {
            assertTrue((value ?: 0.0) >= 0, "$name: $label not negative")
        }
        // Fields the loader owns.
        assertNull(s.endpointID, "$name: endpointID is set by the loader")
        assertEquals(case.expectsLive, s.isLive == true, "$name: isLive")
        if (!case.expectsLive) assertNull(s.cloudFetchedAt, "$name: cloudFetchedAt only on live snapshots")
    }

    private fun assertSecretFree(name: String, error: Throwable, secret: String) {
        assertFalse(error.message.orEmpty().contains(secret), "$name: message leaks the secret")
        assertFalse(error.toString().contains(secret), "$name: toString leaks the secret")
    }

    private val httpSolar get() = SolarContractCase.all.filter { it.routes != null }

    @Test
    fun solarSnapshotShape() = runTest {
        for (case in SolarContractCase.all) checkSnapshotShape(case, case.make(case.stub(), case.now).fetch())
    }

    /** A network failure whose text carries the secret: `Network`, secret redacted. */
    @Test
    fun solarNetworkFailureIsNetworkErrorWithoutSecrets() = runTest {
        for (case in httpSolar) {
            val secret = case.secret ?: fail("${case.name}: an HTTP provider needs a secret")
            val http = StubHttpClient(Reply.Failure(IOException("request for $secret failed")))
            try {
                case.make(http, case.now).fetch()
                fail("${case.name}: expected an error")
            } catch (e: SunWattError) {
                assertIs<SunWattError.Network>(e, "${case.name}: expected Network, got $e")
                assertSecretFree(case.name, e, secret)
            }
        }
    }

    /** 500 is `Http`; 401 (and 403 where it means the same) is the provider's account error; an unreadable 200 body is a `SunWattError`. */
    @Test
    fun solarServerErrorsAndBadBodies() = runTest {
        for (case in httpSolar) {
            val name = case.name
            try {
                case.make(StubHttpClient(Reply.Ok("oops", status = 500)), case.now).fetch()
                fail("$name: expected an error for 500")
            } catch (e: SunWattError) {
                assertEquals(SunWattError.Http(500), e, "$name: 500")
            }
            val rejected = case.rejected ?: fail("$name: an HTTP provider needs an account error")
            for (status in case.rejectedStatuses) {
                try {
                    case.make(StubHttpClient(Reply.Ok(ByteArray(0), status = status)), case.now).fetch()
                    fail("$name: expected an error for $status")
                } catch (e: SunWattError) {
                    // PVOutput answers a 403 that says "exceeded" with RateLimited; an empty body is a wrong key.
                    assertEquals(rejected, e, "$name: $status")
                }
            }
            try {
                case.make(StubHttpClient(Reply.Ok("<html>maintenance</html>")), case.now).fetch()
                fail("$name: expected an error for a body that cannot be read")
            } catch (e: SunWattError) {
                case.secret?.let { assertSecretFree(name, e, it) }
            }
        }
    }

    /** Cancellation is never wrapped, also while the request hangs. */
    @Test
    fun solarCancellationIsNotWrapped() = runTest {
        for (case in httpSolar) {
            assertFailsWith<CancellationException>(case.name) {
                case.make(StubHttpClient(Reply.Failure(CancellationException("cancelled"))), case.now).fetch()
            }
            val job = async(start = CoroutineStart.UNDISPATCHED) { case.make(StubHttpClient(Reply.Hang), case.now).fetch() }
            job.cancel()
            assertFailsWith<CancellationException>(case.name) { job.await() }
        }
    }

    // Prices

    private fun checkShape(s: PriceSeries, source: PriceContractCase) {
        val name = source.name
        assertTrue(s.points.isNotEmpty(), name)
        assertEquals(source.region, s.region, name)
        assertEquals(PriceContractCase.now, s.fetchedAt, name)
        assertTrue(s.points.all { it.durationSeconds == 900L || it.durationSeconds == 3600L }, "$name: duration 15 or 60 min")
        assertTrue(s.points.zipWithNext().all { (a, b) -> a.end <= b.start }, "$name: sorted, not overlapping")
    }

    @Test
    fun priceSeriesShape() = runTest {
        for (source in PriceContractCase.all) {
            val client = source.make(source.stub(), PriceContractCase.now)
            checkShape(client.fetch(PriceContractCase.now), source)
            checkShape(client.fetchTodayAndTomorrow(includeTomorrow = false), source)
        }
    }

    @Test
    fun priceTomorrowFailingKeepsToday() = runTest {
        for (source in PriceContractCase.all.filter { it.separateTomorrowRequest }) {
            val http = StubHttpClient()
            http.on("2026-10-18", Reply.Ok("oops", status = 500))
            http.on(source.match, Reply.Ok(Fixtures.bytes(source.fixture)))
            checkShape(source.make(http, PriceContractCase.now).fetchTodayAndTomorrow(includeTomorrow = true), source)
        }
    }

    @Test
    fun priceErrors() = runTest {
        val now = PriceContractCase.now
        for (source in PriceContractCase.all) {
            val name = source.name
            // No connection.
            assertIs<SunWattError.Network>(
                assertFailsWith<SunWattError>(name) {
                    source.make(StubHttpClient(Reply.Failure(IOException("Unable to resolve host"))), now).fetch(now)
                },
                name,
            )
            // Server error.
            val http = assertFailsWith<SunWattError.Http>(name) {
                source.make(StubHttpClient(Reply.Ok("oops", status = 500)), now).fetch(now)
            }
            assertEquals(500, http.status, name)
            // Body that is not the expected JSON.
            assertFailsWith<SunWattError>(name) {
                source.make(StubHttpClient(Reply.Ok("<html>maintenance</html>")), now).fetch(now)
            }
            // Cancellation passes through, never wrapped.
            assertFailsWith<CancellationException>(name) {
                source.make(StubHttpClient(Reply.Failure(CancellationException("cancelled"))), now).fetch(now)
            }
        }
    }

    // Price regions: every region's profile matches its client, and every kind of source has a contract case.

    /** Exhaustive: a new kind of source must be named here (and get a [PriceContractCase]). */
    private fun kind(source: PriceSource): String = when (source) {
        PriceSource.RedElectrica -> "Red Eléctrica"
        is PriceSource.EnergyCharts -> "Energy-Charts"
    }

    @Test
    fun priceProfileMatchesTheClient() {
        for (region in PriceRegion.entries) {
            val profile = region.profile
            assertTrue(profile.flag.isNotEmpty(), region.rawValue)
            assertEquals(profile.source.isRetailTariff, region.isRetailTariff, region.rawValue)
            val client = region.client()
            when (val source = profile.source) {
                PriceSource.RedElectrica -> assertIs<PvpcClient>(client)
                is PriceSource.EnergyCharts -> {
                    assertEquals(region, assertIs<EnergyChartsClient>(client).region)
                    val day = PriceContractCase.now.atZone(region.timeZone).toLocalDate()
                    assertTrue(EnergyChartsClient.url(region, day, day).contains("bzn=${source.zone}&"))
                    // Wholesale prices carry their licence; the app shows it next to them.
                    assertEquals("CC BY 4.0", source.license)
                }
            }
        }
    }

    @Test
    fun everyKindOfPriceSourceHasAContractCase() {
        val used = PriceRegion.entries.map { kind(it.profile.source) }.toSet()
        val covered = PriceContractCase.all.map { kind(it.region.profile.source) }.toSet()
        assertEquals(used, covered)
    }

    // Inverters

    /** Reading: finite power, energy null or not negative, identifyLive = readLive + model; read functions only. */
    @Test
    fun modbusReadersReadWithReadFunctionsOnlyAndClose() = runTest {
        for (case in InverterContractCase.modbus) {
            val transport = FakeModbusTransport(case.registers)
            val reader = case.make(ModbusEndpoint("x"), { transport })
            val reading = reader.readLive()
            assertTrue(reading.activePowerKW.isFinite(), "${case.name}: finite power")
            assertTrue((reading.dailyEnergyKWh ?: 0.0) >= 0 && (reading.totalEnergyKWh ?: 0.0) >= 0, "${case.name}: energy not negative")
            assertTrue(transport.closed, "${case.name}: one connection per call, closed afterwards")
            assertTrue(transport.requests.isNotEmpty() && transport.requests.all { it[7].toInt() == 0x03 }, "${case.name}: read holding registers only")
            assertEquals(reading, reader.identifyLive().reading, "${case.name}: identifyLive reads the same")
        }
    }

    /** Errors: unreachable, no transport, wrong unit ID is a frame failure. */
    @Test
    fun modbusReadersMapErrors() = runTest {
        val endpoint = ModbusEndpoint("x")
        for (case in InverterContractCase.modbus) {
            assertFailsWith<SunWattError.InverterUnreachable>(case.name) {
                case.make(endpoint, { FakeModbusTransport(emptyMap(), reachable = false) }).readLive()
            }
            // No transport (a platform that cannot open sockets).
            assertFailsWith<SunWattError.InverterUnreachable>(case.name) { case.make(endpoint, { null }).readLive() }
            // Another unit ID answered with Modbus exceptions: the caller may try the next one.
            assertFailsWith<ModbusFrameFailure>(case.name) {
                case.make(endpoint, { FakeModbusTransport(case.registers, unit = 7) }).readLive()
            }
        }
    }

    /** Cancelling while the device hangs: CancellationException, and the connection is closed. */
    @Test
    fun modbusReadersPassCancellationThroughAndClose() = runTest {
        for (case in InverterContractCase.modbus) {
            val transport = HangingModbusTransport()
            val job = async { case.make(ModbusEndpoint("x"), { transport }).readLive() }
            testScheduler.advanceUntilIdle()
            job.cancel()
            assertFailsWith<CancellationException>(case.name) { job.await() }
            assertTrue(transport.closed, "${case.name}: closed on cancellation")
        }
    }

    /** The frames the Modbus readers send are valid read requests (function 3, 12 bytes, header length 6). */
    @Test
    fun modbusRequestsAreWellFormedReads() = runTest {
        for (case in InverterContractCase.modbus) {
            val transport = FakeModbusTransport(case.registers)
            case.make(ModbusEndpoint("x"), { transport }).readLive()
            for (request in transport.requests) {
                assertEquals(12, request.size, case.name)
                assertContentEquals(byteArrayOf(0, 0, 0, 6), request.copyOfRange(2, 6), case.name)
            }
        }
    }

    /** Reading: finite power, energy null or not negative, one GET per call, no body. */
    @Test
    fun httpReadersSendOneGet() = runTest {
        for (case in InverterContractCase.http) {
            val http = froniusStub(case.fixture)
            val reader = case.make(ModbusEndpoint("x", 80), http)
            val reading = reader.readLive()
            assertTrue(reading.activePowerKW.isFinite(), "${case.name}: finite power")
            assertTrue(
                (reading.dailyEnergyKWh ?: 0.0) >= 0 && (reading.totalEnergyKWh ?: 0.0) >= 0 && (reading.homeConsumptionKW ?: 0.0) >= 0,
                "${case.name}: figures not negative",
            )
            assertEquals(1, http.requests.size, "${case.name}: one request per call")
            assertTrue(http.requests.all { it.method == "GET" && it.body == null }, "${case.name}: GET only")
            assertEquals(reading, reader.identifyLive().reading, "${case.name}: identifyLive reads the same")
        }
    }

    /** Errors: no connection and another device on the port are InverterUnreachable; cancellation passes through. */
    @Test
    fun httpReadersMapErrors() = runTest {
        val endpoint = ModbusEndpoint("x", 80)
        for (case in InverterContractCase.http) {
            assertFailsWith<SunWattError.InverterUnreachable>(case.name) {
                case.make(endpoint, StubHttpClient(Reply.Failure(IOException("connection refused")))).readLive()
            }
            // Another device answered on the port.
            assertFailsWith<SunWattError.InverterUnreachable>(case.name) {
                case.make(endpoint, StubHttpClient(Reply.Ok("<html>Router</html>", status = 404))).readLive()
            }
            assertFailsWith<CancellationException>(case.name) {
                case.make(endpoint, StubHttpClient(Reply.Failure(CancellationException("cancelled")))).readLive()
            }
        }
    }

    /** Cancelling while the device hangs: CancellationException. */
    @Test
    fun httpReadersPassCancellationThrough() = runTest {
        for (case in InverterContractCase.http) {
            val job = async { case.make(ModbusEndpoint("x", 80), StubHttpClient(Reply.Hang)).readLive() }
            testScheduler.advanceUntilIdle()
            job.cancel()
            assertFailsWith<CancellationException>(case.name) { job.await() }
        }
    }
}
