package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.models.LiveInverterReading
import com.ideatic.sunwatt.core.models.LocalInverterKind
import com.ideatic.sunwatt.core.models.HomeEnergy
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SunSpecTest {
    private fun client(registers: Map<Int, Int>, unit: Int = 1) =
        SunSpecClient(ModbusEndpoint("x", 1502)) { FakeModbusTransport(registers, unit) }

    @Test
    fun readsTheIntegerModel() = runTest {
        val (model, reading) = client(fakeSunSpecRegisters()).identifyLive()
        assertEquals("SolarEdge SE5000H-RW000BNN4", model)
        assertTrue(abs(reading.activePowerKW - 2.912) < 1e-9)
        assertEquals(18420.0, reading.totalEnergyKWh)
        assertTrue(reading.dailyEnergyKWh == null && reading.meterActivePowerKW == null)
    }

    @Test
    fun readsTheFloatModel() = runTest {
        val (model, reading) = client(fakeSunSpecRegisters(float = true)).identifyLive()
        // The model name already starts with the manufacturer: not repeated.
        assertEquals("Fronius Symo 8.2-3-M", model)
        assertTrue(abs(reading.activePowerKW - 2.912) < 1e-6)
        assertEquals(18420.0, reading.totalEnergyKWh)
    }

    @Test
    fun findsTheMarkerAtOtherBases() = runTest {
        val reading = client(fakeSunSpecRegisters(base = 0)).readLive()
        assertTrue(abs(reading.activePowerKW - 2.912) < 1e-9)
        val atFiftyThousand = client(fakeSunSpecRegisters(base = 50000)).readLive()
        assertTrue(abs(atFiftyThousand.activePowerKW - 2.912) < 1e-9)
    }

    @Test
    fun otherDevicesAreNotSunSpec() = runTest {
        // A Huawei dongle answers Modbus but has no SunSpec marker.
        assertFailsWith<ModbusFrameFailure.UnexpectedDevice> { client(fakeInverterRegisters()).readLive() }
        // Wrong unit ID: Modbus exceptions only.
        assertFailsWith<ModbusFrameFailure.UnexpectedDevice> { client(fakeSunSpecRegisters(), unit = 126).readLive() }
        val down = SunSpecClient(ModbusEndpoint("x")) { FakeModbusTransport(emptyMap(), reachable = false) }
        assertFailsWith<SunWattError.InverterUnreachable> { down.readLive() }
    }

    @Test
    fun notImplementedValues() {
        val data = MutableList(50) { 0 }
        data[12] = 0x8000
        assertNull(SunSpecClient.inverterReading(103, data))
        data[12] = 500
        data[13] = 0x8000
        assertNull(SunSpecClient.inverterReading(101, data))
        data[13] = 1
        // Lifetime counter 0: not implemented.
        assertEquals(LiveInverterReading(activePowerKW = 5.0), SunSpecClient.inverterReading(101, data))
        assertNull(SunSpecClient.inverterReading(103, data.take(10)))
        val floats = MutableList(60) { 0 }
        floats[20] = 0x7FC0
        assertNull(SunSpecClient.inverterReading(113, floats))
        assertTrue(abs((SunSpecClient.scaled(12.0, 0xFFFE) ?: 0.0) - 0.12) < 1e-12)
        assertNull(SunSpecClient.scaled(12.0, 40))
    }

    @Test
    fun aDeviceThatNeverEndsTheChainIsNotSunSpec() = runTest {
        // Marker, then models of ID 5 that repeat forever (every address answers the same header).
        val registers = HashMap<Int, Int>()
        registers[40000] = 0x5375
        registers[40001] = 0x6E53
        var address = 40002
        repeat(60) {
            registers[address] = 5
            registers[address + 1] = 2
            registers[address + 2] = 0
            registers[address + 3] = 0
            address += 4
        }
        val transport = FakeModbusTransport(registers)
        assertFailsWith<ModbusFrameFailure.UnexpectedDevice> { SunSpecClient(ModbusEndpoint("x")) { transport }.readLive() }
        // 1 marker read + at most 40 headers.
        assertTrue(transport.requests.size <= 41)
    }

    // Today's energy from the lifetime counter

    @Test
    fun todayFromTheLifetimeCounter() {
        val cloud = sampleSolar(madrid(2026, 6, 1, 12, 0)).copy(dailyEnergyKWh = 4.0)
        val first = LocalInverterSolarClient.merge(
            LiveInverterReading(activePowerKW = 3.0, totalEnergyKWh = 18420.0), cloud, madrid(2026, 6, 1, 12, 5), keepsBaseHome = true,
        )
        assertTrue(first.dailyEnergyKWh == 4.0 && first.cumulativeEnergyKWh == 18420.0)
        assertEquals(madrid(2026, 6, 1, 12, 0), first.cloudFetchedAt)
        assertEquals(cloud.home, first.home)
        val second = LocalInverterSolarClient.merge(
            LiveInverterReading(activePowerKW = 3.0, totalEnergyKWh = 18420.5), first, madrid(2026, 6, 1, 12, 15),
        )
        assertEquals(4.5, second.dailyEnergyKWh)
        assertEquals(madrid(2026, 6, 1, 12, 0), second.cloudFetchedAt)
        // A new day: no figure until the cloud gives one.
        val nextDay = LocalInverterSolarClient.merge(
            LiveInverterReading(activePowerKW = 0.0, totalEnergyKWh = 18421.0), second, madrid(2026, 6, 2, 0, 5),
        )
        assertNull(nextDay.dailyEnergyKWh)
        // A counter that went back adds nothing.
        assertEquals(4.5, LocalInverterSolarClient.dailyEnergy(100.0, madrid(2026, 6, 1, 12, 20), second))
        // A counter that jumped (another inverter) adds nothing either.
        assertEquals(4.5, LocalInverterSolarClient.dailyEnergy(19000.0, madrid(2026, 6, 1, 12, 20), second))
    }

    @Test
    fun keepsTheCloudHomeWhenAskedAndWithoutMeter() {
        val cloud = sampleSolar().copy(home = HomeEnergy(consumptionKW = 0.8, gridKW = -2.0, dailyConsumptionKWh = 6.0))
        val kept = LocalInverterSolarClient.merge(LiveInverterReading(activePowerKW = 3.0), cloud, madrid(2026, 6, 1, 12, 5), keepsBaseHome = true)
        assertEquals(cloud.home, kept.home)
        val dropped = LocalInverterSolarClient.merge(LiveInverterReading(activePowerKW = 3.0), cloud, madrid(2026, 6, 1, 12, 5))
        assertNull(dropped.home)
    }

    @Test
    fun endpointText() {
        assertEquals("192.168.1.40:1502", ModbusEndpoint("192.168.1.40", 1502).text)
        assertEquals("192.168.1.40", ModbusEndpoint("192.168.1.40").text)
        assertTrue(ModbusEndpoint.hasPort("192.168.1.40:502") && !ModbusEndpoint.hasPort(" 192.168.1.40 "))
    }

    // Test and find (the AppModel part of the Swift tests, on InverterFinder.identify)

    @Test
    fun testFindsThePortAndUnitID() = runTest {
        val kind = LocalInverterKind.SUN_SPEC
        // SMA style: unit 126, on 1502 here.
        val transports: ModbusTransportFactory = { endpoint ->
            if (endpoint.port == 1502) FakeModbusTransport(fakeSunSpecRegisters(), unit = 126) else FakeModbusTransport(emptyMap(), reachable = false)
        }
        val match = InverterFinder.identify(kind, "192.168.1.40", kind.ports, kind.unitIDs, transports).getOrThrow()
        assertEquals("192.168.1.40:1502", match.address)
        assertEquals(126, match.unitID)
        assertEquals("SolarEdge SE5000H-RW000BNN4", match.model)
        // A port typed by the user is the only one tried.
        val failure = InverterFinder.identify(kind, "192.168.1.40", listOf(502), kind.unitIDs, transports).exceptionOrNull()
        assertEquals(SunWattError.InverterUnreachable, failure)
    }

    @Test
    fun findLooksOnBothPorts() = runTest {
        val finder = InverterFinder(
            kind = LocalInverterKind.SUN_SPEC,
            candidates = { listOf("192.168.1.30", "192.168.1.40") },
            probe = { host, port -> host == "192.168.1.40" && port == 1502 || host == "192.168.1.30" && port == 502 },
            makeTransport = { endpoint ->
                // .30: a Huawei dongle, not SunSpec.
                if (endpoint.host == "192.168.1.40") FakeModbusTransport(fakeSunSpecRegisters()) else FakeModbusTransport(fakeInverterRegisters())
            },
        )
        val matches = finder.find()
        assertEquals(listOf("192.168.1.40:1502"), matches.map { it.address })
        assertEquals(1, matches.first().unitID)
    }
}
