package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Modbus TCP over a plain [Socket]. Huawei dongles drop requests sent right after connecting, so [open] waits
 * [settleDelay] once the connection is up. Blocking socket calls run on `Dispatchers.IO`; cancelling the caller closes
 * the socket, which ends the blocked call at once. Only function codes 3 and 4 are sent.
 */
class TcpModbusTransport(
    private val host: String,
    private val port: Int,
    private val settleDelay: Duration = 1.seconds,
    private val connectTimeout: Duration = 4.seconds,
    private val readTimeout: Duration = 4.seconds,
) : ModbusTransport {
    private val socket = Socket()
    private val closed = AtomicBoolean(false)

    override suspend fun open() {
        blocking {
            // No route or connection refused: not at home, or Modbus TCP off.
            socket.connect(InetSocketAddress(host, port), connectTimeout.inWholeMilliseconds.toInt())
            socket.soTimeout = readTimeout.inWholeMilliseconds.toInt()
            socket.tcpNoDelay = true
        }
        delay(settleDelay)
    }

    override suspend fun exchange(request: ByteArray): ByteArray {
        require(request.size > 7 && request[7].toInt() in intArrayOf(ModbusFrame.READ_HOLDING_REGISTERS, ModbusFrame.READ_INPUT_REGISTERS)) {
            "only read function codes are allowed"
        }
        return blocking {
            val output = socket.getOutputStream()
            output.write(request)
            output.flush()
            val input = socket.getInputStream()
            val header = readFully(input, 7)
            val length = (header[4].toInt() and 0xFF shl 8) or (header[5].toInt() and 0xFF)
            if (length !in 2..254) throw SunWattError.InverterUnreachable
            header + readFully(input, length - 1)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Already gone.
            }
        }
    }

    private fun readFully(input: java.io.InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw SunWattError.InverterUnreachable
            read += n
        }
        return buffer
    }

    /** Runs [block] on the IO pool; cancelling the caller closes the socket so the blocked call returns. */
    private suspend fun <T> blocking(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val job = ioScope.launch {
            val result = try {
                Result.success(block())
            } catch (e: SunWattError) {
                Result.failure(e)
            } catch (e: CancellationException) {
                Result.failure(e)
            } catch (e: Exception) {
                // IOException, timeout, bad address: no connection.
                Result.failure(SunWattError.InverterUnreachable)
            }
            // Ignored when the caller was cancelled meanwhile.
            continuation.resumeWith(result)
        }
        continuation.invokeOnCancellation {
            close()
            job.cancel()
        }
    }

    companion object {
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Default pause after connecting for SunSpec gateways (some drop a request sent at once). */
        val sunSpecSettleDelay: Duration = 200.milliseconds
    }
}
