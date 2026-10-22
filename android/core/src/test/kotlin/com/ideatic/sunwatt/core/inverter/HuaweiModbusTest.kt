package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.models.LocalInverterKind
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HuaweiModbusTest {
    private fun client(transport: ModbusTransport?) =
        HuaweiModbusClient(ModbusEndpoint("x")) { transport }

    @Test
    fun requestFrame() {
        val frame = ModbusFrame.readHoldingRegisters(transaction = 0x0102, unit = 1, address = 32080, count = 10)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0, 0, 0, 6, 1, 0x03, 0x7D, 0x50, 0, 10), frame)
    }

    @Test
    fun responseFrames() {
        val request = ModbusFrame.readHoldingRegisters(transaction = 7, unit = 1, address = 32080, count = 2)
        val ok = modbusResponse(request, mapOf(32080 to 0xFFFF, 32081 to 0xFFFB))
        val values = ModbusFrame.registers(ok, transaction = 7, count = 2)
        assertEquals(listOf(0xFFFF, 0xFFFB), values)
        assertEquals(-5, ModbusFrame.int32(values, 0))
        assertFailsWith<ModbusFrameFailure.BadFrame> { ModbusFrame.registers(ok, transaction = 8, count = 2) }
        assertFailsWith<ModbusFrameFailure.BadFrame> { ModbusFrame.registers(ok, transaction = 7, count = 3) }
        assertFailsWith<ModbusFrameFailure.BadFrame> { ModbusFrame.registers(byteArrayOf(0, 7, 0), transaction = 7, count = 2) }
        val missing = modbusResponse(request, emptyMap())
        val failure = assertFailsWith<ModbusFrameFailure.ModbusException> { ModbusFrame.registers(missing, transaction = 7, count = 2) }
        assertEquals(2, failure.code)
    }

    @Test
    fun modelNameFromRegisters() {
        assertEquals("SUN2000-15KTL-M5", ModbusFrame.text(sunSpecText("SUN2000-15KTL-M5", 15)))
        assertNull(ModbusFrame.text(List(15) { 0 }))
        assertNull(ModbusFrame.text(listOf(0x01FF)))
    }

    @Test
    fun readsInverterWithoutMeter() = runTest {
        val transport = FakeModbusTransport(fakeInverterRegisters())
        val reading = client(transport).read()
        assertEquals(2.912, reading.activePowerKW)
        assertEquals(5.12, reading.dailyEnergyKWh)
        assertTrue(abs(reading.totalEnergyKWh - 44150.55) < 1e-9)
        assertEquals(0x0200, reading.deviceStatus)
        assertNull(reading.meterActivePowerKW)
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests.all { it[7].toInt() == 0x03 })
        assertTrue(transport.closed)
    }

    @Test
    fun readsTheMeterWhenThereIsOne() = runTest {
        val transport = FakeModbusTransport(fakeInverterRegisters(meterW = -500))
        assertEquals(-0.5, client(transport).read().meterActivePowerKW)
    }

    @Test
    fun errorsAreMapped() = runTest {
        assertFailsWith<SunWattError.InverterUnreachable> { client(FakeModbusTransport(emptyMap(), reachable = false)).read() }
        assertFailsWith<SunWattError.InverterUnreachable> { client(null).read() }
        val wrongUnit = FakeModbusTransport(fakeInverterRegisters(), unit = 0)
        val failure = assertFailsWith<ModbusFrameFailure.ModbusException> { client(wrongUnit).read() }
        assertEquals(0x0B, failure.code)
    }

    @Test
    fun cancellationPassesThroughAndClosesTheTransport() = runTest {
        val transport = HangingModbusTransport()
        assertFailsWith<CancellationException> {
            withTimeout(1000) { client(transport).read() }
        }
        assertTrue(transport.closed)
    }

    @Test
    fun identifyReadsTheModelName() = runTest {
        val registers = fakeInverterRegisters().toMutableMap()
        sunSpecText("SUN2000-15KTL-M5", 15).forEachIndexed { i, value -> registers[30000 + i] = value }
        val identification = client(FakeModbusTransport(registers)).identify()
        assertEquals("SUN2000-15KTL-M5", identification.model)
        assertEquals(2.912, identification.reading.activePowerKW)
        // No model registers: still an inverter, model unknown.
        assertNull(client(FakeModbusTransport(fakeInverterRegisters())).identify().model)
    }

    // The merge with a cloud snapshot

    @Test
    fun mergeIntoKioskSnapshot() {
        val now = madrid(2026, 6, 1, 12, 33)
        val base = sampleSolar(madrid(2026, 6, 1, 12, 0)).copy(
            curve = listOf(
                CurvePoint(madrid(2026, 5, 31, 23, 55), 0.0),
                CurvePoint(madrid(2026, 6, 1, 12, 0), 2.4),
                CurvePoint(madrid(2026, 6, 1, 12, 32).plusSeconds(30), 2.5),
                CurvePoint(madrid(2026, 6, 1, 23, 55), null),
            ),
        )
        val reading = HuaweiInverterReading(activePowerKW = 2.9, dailyEnergyKWh = 5.1, totalEnergyKWh = 44150.0)
        val s = LocalInverterSolarClient.merge(reading, base, now)
        assertTrue(s.currentPowerKW == 2.9 && s.dailyEnergyKWh == 5.1 && s.cumulativeEnergyKWh == 44150.0)
        assertTrue(s.isLive == true && s.fetchedAt == now && s.dataAsOf == now)
        assertEquals("Casa", s.stationName)
        // Yesterday, the last minute and the future go; the reading is the last point.
        assertEquals(listOf(madrid(2026, 6, 1, 12, 0), now), s.curve.map { it.time })
        assertNull(s.home)
        // Standby at night: slightly negative power reads as zero.
        val night = LocalInverterSolarClient.merge(HuaweiInverterReading(-0.005, 0.0, 1.0), null, now)
        assertEquals(0.0, night.currentPowerKW)
    }

    @Test
    fun mergeWithMeterGivesHome() {
        val reading = HuaweiInverterReading(activePowerKW = 3.0, dailyEnergyKWh = 5.0, totalEnergyKWh = 1.0, meterActivePowerKW = 2.2)
        val home = LocalInverterSolarClient.merge(reading, null, madrid(2026, 6, 1, 12, 0)).home
        assertEquals(-2.2, home?.gridKW)
        assertTrue(abs((home?.consumptionKW ?: 0.0) - 0.8) < 1e-9)
    }

    @Test
    fun localFirstThenKiosk() = runTest {
        val now = madrid(2026, 6, 1, 12, 33)
        val kioskSnapshot = sampleSolar(madrid(2026, 6, 1, 12, 30))
        val up = HuaweiModbusClient(ModbusEndpoint("x")) { FakeModbusTransport(fakeInverterRegisters()) }
        val down = HuaweiModbusClient(ModbusEndpoint("x")) { FakeModbusTransport(emptyMap(), reachable = false) }
        val kiosk = FakeSolar(Result.success(kioskSnapshot))

        val atHome = LocalInverterSolarClient(up, kiosk, now = { now }).fetch()
        assertTrue(atHome.isLive == true && atHome.currentPowerKW == 2.912 && atHome.stationName == "Casa")

        val away = LocalInverterSolarClient(down, kiosk, now = { now }).fetch()
        assertEquals(kioskSnapshot, away)

        val inverterOnly = LocalInverterSolarClient(up, null, now = { now }).fetch()
        assertTrue(inverterOnly.curve.size == 1 && inverterOnly.isLive == true)

        assertFailsWith<SunWattError.InverterUnreachable> { LocalInverterSolarClient(down, null, now = { now }).fetch() }

        // At home with the kiosk failing: the inverter alone.
        val kioskDown = LocalInverterSolarClient(up, FakeSolar(Result.failure(SunWattError.Unavailable)), now = { now }).fetch()
        assertTrue(kioskDown.isLive == true)
    }

    @Test
    fun awayWithAFailingCloudReportsTheCloudError() = runTest {
        val down = HuaweiModbusClient(ModbusEndpoint("x")) { FakeModbusTransport(emptyMap(), reachable = false) }
        assertFailsWith<SunWattError.Unavailable> {
            LocalInverterSolarClient(down, FakeSolar(Result.failure(SunWattError.Unavailable))).fetch()
        }
    }

    @Test
    fun aHangingInverterFallsBackToTheCloudAfterTheDeadline() = runTest {
        val hang = HuaweiModbusClient(ModbusEndpoint("x")) { HangingModbusTransport() }
        val kiosk = sampleSolar()
        assertEquals(kiosk, LocalInverterSolarClient(hang, FakeSolar(Result.success(kiosk))).fetch())
        assertFailsWith<SunWattError.InverterUnreachable> { LocalInverterSolarClient(hang, null).fetch() }
    }

    @Test
    fun readDeadlinesAndUnitIDsOfTheKinds() {
        assertEquals(listOf(1, 0), LocalInverterKind.HUAWEI.unitIDs)
        assertEquals(6, LocalInverterKind.HUAWEI.readDeadline.inWholeSeconds)
    }

    // Real TCP: TcpModbusTransport against a tiny Modbus server on 127.0.0.1

    private class Server(private val registers: Map<Int, Int>) : AutoCloseable {
        val socket = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(client) }
            }
        }

        private fun serve(client: Socket) {
            client.use {
                val input = it.getInputStream()
                val output = it.getOutputStream()
                val request = ByteArray(12)
                while (true) {
                    var read = 0
                    while (read < 12) {
                        val n = input.read(request, read, 12 - read)
                        if (n < 0) return
                        read += n
                    }
                    output.write(modbusResponse(request, registers))
                    output.flush()
                }
            }
        }

        override fun close() {
            socket.close()
        }
    }

    @Test
    fun readsOverRealTcp() = runBlocking {
        Server(fakeInverterRegisters(meterW = 1500)).use { server ->
            val client = HuaweiModbusClient(ModbusEndpoint("127.0.0.1", server.port)) {
                TcpModbusTransport(it.host, it.port, settleDelay = 20.milliseconds)
            }
            val reading = client.read()
            assertEquals(2.912, reading.activePowerKW)
            assertEquals(5.12, reading.dailyEnergyKWh)
            assertEquals(1.5, reading.meterActivePowerKW)
        }
    }

    @Test
    fun closedPortIsUnreachable() = runBlocking {
        // Grab a free port, then close it again.
        val port = ServerSocket(0).use { it.localPort }
        val client = HuaweiModbusClient(ModbusEndpoint("127.0.0.1", port)) {
            TcpModbusTransport(it.host, it.port, settleDelay = 20.milliseconds)
        }
        assertFailsWith<SunWattError.InverterUnreachable> { client.read() }
    }

    @Test
    fun theTransportRefusesWriteFunctionCodes() = runBlocking {
        val transport = TcpModbusTransport("127.0.0.1", 1)
        val write = byteArrayOf(0, 1, 0, 0, 0, 6, 1, 0x06, 0, 1, 0, 1)
        assertFailsWith<IllegalArgumentException> { transport.exchange(write) }
        transport.close()
    }

    @Test
    fun cancellingClosesARealConnection() = runBlocking {
        // A server that accepts and never answers.
        ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1")).use { silent ->
            val accepted = thread(isDaemon = true) { try { silent.accept().use { Thread.sleep(5000) } } catch (_: Exception) {} }
            val client = HuaweiModbusClient(ModbusEndpoint("127.0.0.1", silent.localPort)) {
                TcpModbusTransport(it.host, it.port, settleDelay = 10.milliseconds, readTimeout = 30.seconds)
            }
            val started = System.nanoTime()
            val job = async { client.read() }
            delay(300)
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3000, "cancel must not wait for the read timeout")
            accepted.interrupt()
        }
    }
}
