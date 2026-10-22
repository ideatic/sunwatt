package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.services.SolarDataSource
import com.ideatic.sunwatt.core.services.SunWattError
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/** Test helpers for the inverter readers, modelled on the Swift test helpers. */

/** Europe/Madrid wall-clock time as an instant. */
fun madrid(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Instant =
    LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of("Europe/Madrid")).toInstant()

/** The cloud snapshot of the Swift `storeSampleSolar` (station "Casa"). */
fun sampleSolar(fetchedAt: Instant = madrid(2026, 6, 1, 12, 0)): SolarSnapshot = SolarSnapshot(
    stationName = "Casa",
    currentPowerKW = 2.5,
    dailyEnergyKWh = 10.2,
    curve = listOf(CurvePoint(madrid(2026, 6, 1, 11, 55), 2.4), CurvePoint(madrid(2026, 6, 1, 12, 0), null)),
    fetchedAt = fetchedAt,
    dataAsOf = madrid(2026, 6, 1, 11, 55),
)

/** A cloud source that answers with a fixed result. */
class FakeSolar(private val result: Result<SolarSnapshot>) : SolarDataSource {
    override suspend fun fetch(): SolarSnapshot = result.getOrThrow()
}

/** Holding registers of a fake SUN2000 behind a dongle: 2,912 kW, 5,12 kWh today, 44.150,55 kWh in total, on-grid. */
fun fakeInverterRegisters(powerW: Int = 2912, meterW: Int? = null): Map<Int, Int> {
    val registers = HashMap<Int, Int>()
    for (address in 32080..32115) registers[address] = 0
    registers[32080] = powerW ushr 16 and 0xFFFF
    registers[32081] = powerW and 0xFFFF
    registers[32089] = 0x0200
    registers[32106] = 4_415_055 ushr 16
    registers[32107] = 4_415_055 and 0xFFFF
    registers[32115] = 512
    if (meterW != null) {
        for (address in 37100..37114) registers[address] = 0
        registers[37100] = 1
        registers[37113] = meterW ushr 16 and 0xFFFF
        registers[37114] = meterW and 0xFFFF
    }
    return registers
}

/** Answer to a "read holding registers" request: the registers, or exception 2 (illegal address) when one is missing. */
fun modbusResponse(request: ByteArray, registers: Map<Int, Int>, unit: Int = 1): ByteArray {
    val b = IntArray(request.size) { request[it].toInt() and 0xFF }
    val address = b[8] shl 8 or b[9]
    val count = b[10] shl 8 or b[11]
    val values = (0 until count).map { registers[(address + it) and 0xFFFF] }
    if (b[6] != unit) return byteArrayOf(request[0], request[1], 0, 0, 0, 3, request[6], 0x83.toByte(), 0x0B)
    if (values.any { it == null }) return byteArrayOf(request[0], request[1], 0, 0, 0, 3, request[6], 0x83.toByte(), 0x02)
    val body = ArrayList<Byte>()
    body += request[6]
    body += 0x03
    body += (count * 2).toByte()
    for (value in values) {
        body += (value!! shr 8).toByte()
        body += value.toByte()
    }
    return byteArrayOf(request[0], request[1], 0, 0, (body.size shr 8).toByte(), body.size.toByte()) + body.toByteArray()
}

/** A fake dongle or inverter: answers read requests from a register map; records every request. */
class FakeModbusTransport(
    private val registers: Map<Int, Int>,
    private val unit: Int = 1,
    private val reachable: Boolean = true,
) : ModbusTransport {
    private val recorded = CopyOnWriteArrayList<ByteArray>()

    @Volatile
    var closed = false
        private set

    val requests: List<ByteArray> get() = recorded.toList()

    override suspend fun open() {
        if (!reachable) throw SunWattError.InverterUnreachable
    }

    override suspend fun exchange(request: ByteArray): ByteArray {
        recorded.add(request)
        return modbusResponse(request, registers, unit)
    }

    override fun close() {
        closed = true
    }
}

/** A device that never answers (for deadline and cancellation tests). */
class HangingModbusTransport : ModbusTransport {
    @Volatile
    var closed = false
        private set

    override suspend fun open() = kotlinx.coroutines.awaitCancellation()

    override suspend fun exchange(request: ByteArray): ByteArray = kotlinx.coroutines.awaitCancellation()

    override fun close() {
        closed = true
    }
}

/** Registers of [text] as SunSpec string registers (two ASCII characters each, zero padded to [count]). */
fun sunSpecText(text: String, count: Int): List<Int> {
    val bytes = text.toByteArray().map { it.toInt() and 0xFF } + List(maxOf(0, count * 2 - text.length)) { 0 }
    return List(count) { bytes[2 * it] shl 8 or bytes[2 * it + 1] }
}

/**
 * A SunSpec map at [base]: common model (manufacturer, model) and an inverter model, 2.912 kW and 18,420 kWh in total.
 * [float]: model 113 (Fronius style) instead of 103 with scale factors (SolarEdge style).
 */
fun fakeSunSpecRegisters(
    base: Int = 40000,
    float: Boolean = false,
    powerW: Double = 2912.0,
    lifetimeWh: Double = 18_420_000.0,
): Map<Int, Int> {
    val values = ArrayList<Int>()
    values += listOf(0x5375, 0x6E53)
    val common = sunSpecText(if (float) "Fronius" else "SolarEdge", 16) +
        sunSpecText(if (float) "Fronius Symo 8.2-3-M" else "SE5000H-RW000BNN4", 16) + List(33) { 0 }
    values += listOf(1, common.size) + common
    val inverter = MutableList(if (float) 60 else 50) { 0 }
    if (float) {
        val w = powerW.toFloat().toRawBits()
        val wh = lifetimeWh.toFloat().toRawBits()
        inverter[20] = w ushr 16 and 0xFFFF
        inverter[21] = w and 0xFFFF
        inverter[30] = wh ushr 16 and 0xFFFF
        inverter[31] = wh and 0xFFFF
    } else {
        // W with scale factor -1 (29120 x 0.1), WH with scale factor 0.
        inverter[12] = (powerW * 10).toInt() and 0xFFFF
        inverter[13] = 0xFFFF
        val wh = lifetimeWh.toLong()
        inverter[22] = (wh ushr 16 and 0xFFFF).toInt()
        inverter[23] = (wh and 0xFFFF).toInt()
        inverter[24] = 0
    }
    values += listOf(if (float) 113 else 103, inverter.size) + inverter
    values += listOf(0xFFFF, 0)
    return values.withIndex().associate { (base + it.index) to it.value }
}
