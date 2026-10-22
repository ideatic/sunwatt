package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.timeline.SunWattLoader
import com.ideatic.sunwatt.core.models.LiveInverterReading
import com.ideatic.sunwatt.core.models.LocalInverterKind
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** An IPv4 network of the device: its address and subnet mask as 32-bit values (0...0xFFFFFFFF). */
data class Ipv4Network(val address: Long, val mask: Long)

/**
 * The device's own Wi-Fi / Ethernet networks. The app implements it (on Android with `ConnectivityManager`: Wi-Fi and
 * Ethernet only, not cellular, VPN or loopback). Core has no Android types.
 */
fun interface LocalNetworks {
    fun ipv4Networks(): List<Ipv4Network>
}

/**
 * "Find inverter": looks for inverters on the home network. Neither Huawei dongles, SunSpec inverters nor the Fronius
 * Solar API announce themselves, so every address of the device's Wi-Fi/Ethernet subnet (at most the 254 around it) is
 * tried on the kind's ports with a quick TCP connect; the few that accept are read like an inverter (each unit ID in
 * turn). Read-only, like the live data. Takes a few seconds.
 */
class InverterFinder(
    val kind: LocalInverterKind,
    /** Addresses to try. */
    private val candidates: () -> List<String>,
    /** True when `host` accepts a TCP connection on `port` (within [PROBE_TIMEOUT]). */
    private val probe: suspend (String, Int) -> Boolean,
    /** Transport for the full read of a host that accepted; null: the reader's own. */
    private val makeTransport: ModbusTransportFactory? = null,
    /** HTTP for the Solar API read (Fronius); null: the shared client. */
    private val http: HttpClient? = null,
    /** Connections tried at the same time. */
    private val concurrency: Int = 64,
) {
    /** The scan of the device's networks, with a real TCP probe. */
    constructor(kind: LocalInverterKind, networks: LocalNetworks, makeTransport: ModbusTransportFactory? = null, http: HttpClient? = null) :
        this(kind, { candidatesFor(networks.ipv4Networks()) }, ::tcpProbe, makeTransport, http)

    data class Match(
        /** "host", or "host:port" when the port is not the kind's usual one: the value to save. */
        val address: String,
        val unitID: Int,
        /** e.g. "SUN2000-15KTL-M5" or "SolarEdge SE5000H"; null when the inverter does not report it. */
        val model: String?,
        val reading: LiveInverterReading,
    )

    /** Inverters that answered, in address order. Empty when none did (or no local network). */
    suspend fun find(): List<Match> = coroutineScope {
        val targets = candidates().flatMap { host -> kind.ports.map { host to it } }
        val permits = Semaphore(concurrency)
        val open = targets.map { (host, port) ->
            async { permits.withPermit { if (probe(host, port)) host to port else null } }
        }.awaitAll().filterNotNull()
        val matches = open.map { (host, port) ->
            async { identify(kind, host, listOf(port), kind.unitIDs, makeTransport, http).getOrNull() }
        }.awaitAll().filterNotNull()
        // One entry per address (an inverter answering on both ports appears once).
        val seen = HashSet<String>()
        matches
            .sortedWith(compareBy<Match>({ ipv4(hostOf(it.address)) ?: 0L }, { it.address }))
            .filter { seen.add(hostOf(it.address)) }
    }

    companion object {
        val PROBE_TIMEOUT: Duration = 1200.milliseconds

        /**
         * Reads [host] on each port and unit ID in turn and returns the first that answers like an inverter. Used by the
         * scan and by "Test inverter". A Modbus answer that is not an inverter (wrong unit ID, "gateway target failed")
         * or, for SunSpec, no answer in time tries the next unit ID; a refused connection the next port. Cancellation is
         * thrown, never returned.
         */
        suspend fun identify(
            kind: LocalInverterKind,
            host: String,
            ports: List<Int>,
            unitIDs: List<Int>,
            makeTransport: ModbusTransportFactory?,
            http: HttpClient? = null,
        ): Result<Match> {
            var lastError: Throwable = SunWattError.InverterUnreachable
            for (port in ports) {
                for (unit in unitIDs) {
                    if (unit !in 0..247) continue
                    val endpoint = ModbusEndpoint(host, port, unit)
                    val reader = kind.reader(endpoint, makeTransport, http)
                    val result = SunWattLoader.race(kind.readDeadline) { reader.identifyLive() }
                    val identification = result.getOrNull()
                    if (identification != null) {
                        return Result.success(
                            Match(endpoint.text(kind.defaultPort), unit, identification.model, identification.reading),
                        )
                    }
                    val error = result.exceptionOrNull() ?: continue
                    lastError = error
                    val timedOut = error is SunWattError.Network
                    if (error is ModbusFrameFailure || (kind == LocalInverterKind.SUN_SPEC && timedOut)) continue
                    break
                }
            }
            // An answer that never was an inverter reads as "no inverter there".
            return Result.failure(if (lastError is SunWattError) lastError else SunWattError.InverterUnreachable)
        }

        internal fun hostOf(address: String): String = ModbusEndpoint.parse(address)?.host ?: address

        // Addresses

        /**
         * The other addresses of [address]'s subnet. Subnets larger than /24 are narrowed to the /24 around the device
         * (home routers almost always use /24; a /16 would be 65,000 addresses). Nothing for /31 and /32.
         */
        fun neighbours(address: Long, mask: Long): List<String> {
            val narrowed = if (mask < 0xFFFF_FF00L) 0xFFFF_FF00L else mask
            val network = address and narrowed
            val broadcast = network or (narrowed.inv() and 0xFFFF_FFFFL)
            if (broadcast <= network + 1) return emptyList()
            return (network + 1 until broadcast).filter { it != address }.map(::dotted)
        }

        fun dotted(value: Long): String =
            "${value shr 24 and 0xFF}.${value shr 16 and 0xFF}.${value shr 8 and 0xFF}.${value and 0xFF}"

        fun ipv4(text: String): Long? {
            val parts = text.split('.').map { it.toLongOrNull() ?: return null }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return null
            return parts.fold(0L) { acc, part -> acc shl 8 or part }
        }

        /** Neighbours on all the given networks, without repeats. */
        fun candidatesFor(networks: List<Ipv4Network>): List<String> =
            networks.flatMap { neighbours(it.address, it.mask) }.distinct()

        /** TCP connect without the dongle pause; closed at once. */
        suspend fun tcpProbe(host: String, port: Int): Boolean {
            val transport = TcpModbusTransport(host, port, settleDelay = Duration.ZERO, connectTimeout = PROBE_TIMEOUT)
            try {
                return SunWattLoader.race(PROBE_TIMEOUT) { transport.open() }.isSuccess
            } finally {
                transport.close()
            }
        }
    }
}
