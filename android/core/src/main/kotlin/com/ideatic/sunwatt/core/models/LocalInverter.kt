package com.ideatic.sunwatt.core.models

import com.ideatic.sunwatt.core.inverter.FroniusSolarAPIClient
import com.ideatic.sunwatt.core.inverter.HuaweiModbusClient
import com.ideatic.sunwatt.core.inverter.ModbusTransportFactory
import com.ideatic.sunwatt.core.inverter.SunSpecClient
import com.ideatic.sunwatt.core.services.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Which inverter protocol the selected provider reads on the home network. */
enum class LocalInverterKind(val rawValue: String) {
    /** FusionSolar: a Huawei inverter through its dongle (Huawei register map, port 502, unit 1 or 0). */
    HUAWEI("huawei"),

    /** SolarEdge and PVOutput: a SunSpec inverter (SolarEdge, SMA, Fronius, ...; port 502 or 1502). */
    SUN_SPEC("sunSpec"),

    /** Fronius: the inverter's Solar API over HTTP (port 80, no unit ID). */
    FRONIUS("fronius");

    /** Ports tried when the address names none. */
    val ports: List<Int>
        get() = when (this) {
            HUAWEI -> listOf(502)
            SUN_SPEC -> listOf(502, 1502) // SunSpecClient.usualPorts
            FRONIUS -> listOf(80) // FroniusSolarAPIClient.port
        }

    /** The port an address without one means; saved addresses leave it out. */
    val defaultPort: Int get() = ports[0]

    /** Unit IDs tried, in order (the Solar API has none: one pass). */
    val unitIDs: List<Int>
        get() = when (this) {
            HUAWEI -> listOf(1, 0)
            SUN_SPEC -> listOf(1, 126, 2) // SunSpecClient.usualUnitIDs
            FRONIUS -> listOf(1)
        }

    /**
     * How long one read may take. Huawei dongles pause 1 s after connecting; a SunSpec inverter asked with the wrong
     * unit ID may not answer at all, so that wait is kept short.
     */
    val readDeadline: Duration get() = if (this == HUAWEI) 6.seconds else 4.seconds

    /**
     * The reader for this kind. [makeTransport] null: the reader's own TCP transport (Huawei waits longer after
     * connecting). [http] (Fronius) null: the shared OkHttp client.
     */
    fun reader(endpoint: ModbusEndpoint, makeTransport: ModbusTransportFactory? = null, http: HttpClient? = null): LocalInverterReader =
        when (this) {
            HUAWEI -> HuaweiModbusClient(endpoint, makeTransport)
            SUN_SPEC -> SunSpecClient(endpoint, makeTransport)
            FRONIUS -> FroniusSolarAPIClient(endpoint, http)
        }

    companion object {
        fun fromRaw(raw: String): LocalInverterKind? = entries.firstOrNull { it.rawValue == raw }

        fun of(provider: SolarProvider): LocalInverterKind = provider.profile.localInverter
    }
}

/**
 * Address of an inverter (or its dongle) on the home network: an IPv4 address or a host name, optionally ":port"
 * (Modbus: default 502; the Fronius Solar API: 80, unit ID unused), and the Modbus unit ID (usually 1).
 */
data class ModbusEndpoint(val host: String, val port: Int = 502, val unitID: Int = 1) {
    init {
        require(port in 0..65535) { "port out of range" }
        require(unitID in 0..255) { "unit ID out of range" }
    }

    /** How the address is saved: "host", or "host:port" when the port is not 502. */
    val text: String get() = text(502)

    /** "host", or "host:port" when the port is not [defaultPort] (the kind's usual one). */
    fun text(defaultPort: Int): String = if (port == defaultPort) host else "$host:$port"

    companion object {
        /** Null for anything that is not a plain host: no scheme, no path, no spaces. */
        fun parse(text: String, unitID: Int = 1, defaultPort: Int = 502): ModbusEndpoint? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || unitID !in 0..247) return null
            var host = trimmed
            var port = defaultPort
            val colon = trimmed.lastIndexOf(':')
            if (colon >= 0) {
                val value = parsePort(trimmed.substring(colon + 1)) ?: return null
                if (value <= 0) return null
                port = value
                host = trimmed.substring(0, colon)
            }
            if (host.length !in 1..253 || !host.all { isAllowed(it) }) return null
            if (host.startsWith('.') || host.startsWith('-') || host.endsWith('.')) return null
            // Digits and dots only: must be a full IPv4 address.
            if (host.all { it in '0'..'9' || it == '.' }) {
                val parts = host.split('.')
                val valid = parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.toInt() <= 255 }
                if (!valid) return null
            }
            return ModbusEndpoint(host, port, unitID)
        }

        /** True when [text] names a port (`host:port`). */
        fun hasPort(text: String): Boolean = text.trim().contains(':')

        private fun isAllowed(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-'

        /** Like Swift's `UInt16(String)`: an optional "+", ASCII digits only, at most 65535. */
        private fun parsePort(text: String): Int? {
            val digits = text.removePrefix("+")
            if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
            val significant = digits.trimStart('0')
            if (significant.length > 5) return null
            val value = if (significant.isEmpty()) 0 else significant.toInt()
            return if (value <= 65535) value else null
        }
    }
}

/** One live reading, whatever the inverter brand. */
data class LiveInverterReading(
    /** AC power now (kW). Slightly negative at night on some inverters (standby consumption). */
    val activePowerKW: Double,
    /** Energy produced today (kWh). Null when the inverter does not count it (SunSpec: only a lifetime counter). */
    val dailyEnergyKWh: Double? = null,
    /** Energy produced since installation (kWh). */
    val totalEnergyKWh: Double? = null,
    /** Power at the grid connection from a meter the inverter reads (kW): positive when feeding the grid. */
    val meterActivePowerKW: Double? = null,
    /**
     * The home's consumption (kW) when the inverter measures it (Fronius: right also with a battery). Null: worked
     * out from production and the meter.
     */
    val homeConsumptionKW: Double? = null,
)

/** The model name (when the inverter gives it) and a reading, from one connection. */
data class InverterIdentification(val model: String?, val reading: LiveInverterReading)

/**
 * An inverter that can be read on the home network (Modbus TCP or HTTP, read-only). Contract, checked for every reader
 * by the provider contract tests:
 *
 * - **Reading.** `activePowerKW` is finite (may be slightly negative at night; callers clamp it). Energy figures are
 *   null when the inverter does not count them, never negative. [identifyLive] returns the same reading as [readLive]
 *   plus the model name when the inverter reports one.
 * - **Errors.** `CancellationException` (never wrapped); `SunWattError.InverterUnreachable` when there is no connection
 *   (away from home, port closed, Modbus TCP or the Solar API off), and for an HTTP reader also when another device
 *   answered; a Modbus failure type (Modbus readers, added with them) when a device answered but not as this kind of
 *   inverter (wrong unit ID, another device, Modbus exception): callers try the next unit ID only then.
 * - **Budget.** One connection (Modbus) or one GET (HTTP) per call, closed afterwards; callers put a deadline on it
 *   (5 s for live reads) and the reader stops when cancelled. Never writes a register, never sends anything but GET.
 */
interface LocalInverterReader {
    suspend fun readLive(): LiveInverterReading

    /** The model name (when the inverter gives it) and a reading, on one connection. Used by "Find inverter". */
    suspend fun identifyLive(): InverterIdentification
}
