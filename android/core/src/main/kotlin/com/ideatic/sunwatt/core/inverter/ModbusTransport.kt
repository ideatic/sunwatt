package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.models.ModbusEndpoint
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException

/**
 * A Modbus TCP connection: open, exchange whole frames, close. [open] and [exchange] throw
 * [SunWattError.InverterUnreachable] (or `CancellationException`) when the connection fails; [close] may be called more
 * than once. Only the read function codes 3 and 4 may be sent: SunWatt never writes a register.
 */
interface ModbusTransport {
    suspend fun open()

    /** Sends a request frame and returns the complete response frame. */
    suspend fun exchange(request: ByteArray): ByteArray

    fun close()
}

/** Makes the transport for an endpoint; null when the platform cannot open such connections. */
typealias ModbusTransportFactory = (ModbusEndpoint) -> ModbusTransport?

/** Why a device that answered Modbus is not the inverter we expected. */
sealed class ModbusFrameFailure(message: String) : Exception(message) {
    /** Malformed or unexpected frame. */
    data object BadFrame : ModbusFrameFailure("badFrame")

    /** The device answered with a Modbus exception (e.g. 2: illegal address, 11: gateway target failed). */
    data class ModbusException(val code: Int) : ModbusFrameFailure("exception $code")

    /** The device answers Modbus but is not the expected kind (e.g. no SunSpec marker, no inverter model). */
    data object UnexpectedDevice : ModbusFrameFailure("unexpectedDevice")
}

/** Modbus TCP framing for "read holding registers". Registers are 16-bit values held in an [Int] (0...65535). */
object ModbusFrame {
    const val READ_HOLDING_REGISTERS = 0x03
    const val READ_INPUT_REGISTERS = 0x04

    /** MBAP header (transaction, protocol 0, length 6, unit) + function 0x03, start address, register count. */
    fun readHoldingRegisters(transaction: Int, unit: Int, address: Int, count: Int): ByteArray = byteArrayOf(
        (transaction shr 8).toByte(), transaction.toByte(), 0, 0, 0, 6, unit.toByte(),
        READ_HOLDING_REGISTERS.toByte(), (address shr 8).toByte(), address.toByte(), (count shr 8).toByte(), count.toByte(),
    )

    /** The register values of a response to [readHoldingRegisters] with the same [transaction] and [count]. */
    fun registers(frame: ByteArray, transaction: Int, count: Int): List<Int> {
        val b = IntArray(frame.size) { frame[it].toInt() and 0xFF }
        if (b.size < 9 || (b[0] shl 8 or b[1]) != (transaction and 0xFFFF) || b[2] != 0 || b[3] != 0 ||
            (b[4] shl 8 or b[5]) != b.size - 6
        ) {
            throw ModbusFrameFailure.BadFrame
        }
        if (b[7] == 0x83) throw ModbusFrameFailure.ModbusException(b[8])
        val byteCount = b[8]
        if (b[7] != READ_HOLDING_REGISTERS || byteCount != count * 2 || b.size != 9 + byteCount) throw ModbusFrameFailure.BadFrame
        return (9 until 9 + byteCount step 2).map { b[it] shl 8 or b[it + 1] }
    }

    fun uint32(registers: List<Int>, index: Int): Long =
        (registers[index].toLong() shl 16) or registers[index + 1].toLong()

    fun int32(registers: List<Int>, index: Int): Int = uint32(registers, index).toInt()

    /** Two ASCII characters per register, padded with zeros. Null when empty or not printable. */
    fun text(registers: List<Int>): String? {
        val bytes = registers.flatMap { listOf(it shr 8 and 0xFF, it and 0xFF) }.takeWhile { it != 0 }
        if (!bytes.all { it in 0x20..0x7E }) return null
        val value = bytes.map { it.toChar() }.joinToString("").trim(' ', '\t')
        return value.ifEmpty { null }
    }
}

/** One Modbus TCP connection used for several "read holding registers" requests. */
internal object ModbusSession {
    /** Reads `count` registers from `address`. */
    fun interface ReadRegisters {
        suspend operator fun invoke(address: Int, count: Int): List<Int>
    }

    /**
     * Opens the connection, runs [body] with a register reader and closes it. Errors: `CancellationException`,
     * [SunWattError] as thrown, [ModbusFrameFailure] when the device answered unexpectedly (e.g. wrong unit ID),
     * anything else [SunWattError.InverterUnreachable].
     */
    suspend fun <T> run(endpoint: ModbusEndpoint, makeTransport: ModbusTransportFactory, body: suspend (ReadRegisters) -> T): T {
        val transport = makeTransport(endpoint) ?: throw SunWattError.InverterUnreachable
        try {
            transport.open()
            var transaction = 0
            val registers = ReadRegisters { address, count ->
                transaction = (transaction + 1) and 0xFFFF
                val request = ModbusFrame.readHoldingRegisters(transaction, endpoint.unitID, address, count)
                ModbusFrame.registers(transport.exchange(request), transaction, count)
            }
            return body(registers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SunWattError) {
            throw e
        } catch (e: ModbusFrameFailure) {
            // The device answered, but not as expected (e.g. wrong unit ID): kept apart from "no connection".
            throw e
        } catch (e: Exception) {
            throw SunWattError.InverterUnreachable
        } finally {
            transport.close()
        }
    }
}
