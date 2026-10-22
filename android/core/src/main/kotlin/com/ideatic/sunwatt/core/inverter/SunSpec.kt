package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.models.InverterIdentification
import com.ideatic.sunwatt.core.models.LiveInverterReading
import com.ideatic.sunwatt.core.models.LocalInverterReader
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import kotlin.math.pow

// SunSpec over Modbus TCP: the common register map of SolarEdge, SMA, Fronius, Kostal, ABB/FIMER and many others
// (SunSpec Alliance "Information Model Specification"). Read-only, like the Huawei client.
//
// Layout: the marker "SunS" (0x5375 0x6E53) at a base address (40000, else 0 or 50000), then a chain of models, each
// [ID, length, length registers of data], ending with ID 0xFFFF. SunWatt reads model 1 (common: manufacturer and model
// name) and the first inverter model: 101/102/103 (integers with scale factors) or 111/112/113 (floats).
// SunSpec has no "energy today" register, only a lifetime counter: today's energy comes from the cloud figure plus what
// the counter grew since (`LocalInverterSolarClient.dailyEnergy`).
//
// Usual settings: SolarEdge port 1502, unit 1 (turned on in SetApp or by the installer: "Modbus TCP"); SMA port 502,
// unit 126; Fronius port 502, unit 1 ("Modbus TCP" with "SunSpec Model Type: float" or "int+SF").

/** Reads a SunSpec inverter. Three to six requests per reading (marker, model headers, common and inverter blocks). */
class SunSpecClient(
    val endpoint: ModbusEndpoint,
    makeTransport: ModbusTransportFactory? = null,
) : LocalInverterReader {
    private val makeTransport: ModbusTransportFactory = makeTransport ?: defaultTransport

    override suspend fun readLive(): LiveInverterReading = identifyLive().reading

    override suspend fun identifyLive(): InverterIdentification =
        ModbusSession.run(endpoint, makeTransport) { read(it) }

    companion object {
        /** TCP with a short pause after connecting (some gateways drop a request sent at once). */
        val defaultTransport: ModbusTransportFactory = {
            TcpModbusTransport(it.host, it.port, settleDelay = TcpModbusTransport.sunSpecSettleDelay)
        }

        /** Ports to try when the user typed none: SolarEdge's 1502 and the standard 502. */
        val usualPorts: List<Int> = listOf(502, 1502)

        /** Unit IDs to try when the saved one does not answer: most inverters 1, SMA 126, some gateways 2. */
        val usualUnitIDs: List<Int> = listOf(1, 126, 2)

        internal val marker: List<Int> = listOf(0x5375, 0x6E53)
        internal val integerInverterModels: Set<Int> = setOf(101, 102, 103)
        internal val floatInverterModels: Set<Int> = setOf(111, 112, 113)

        internal suspend fun read(registers: ModbusSession.ReadRegisters): InverterIdentification {
            var start: Int? = null
            for (base in listOf(40000, 0, 50000)) {
                try {
                    if (registers(base, 2) == marker) {
                        start = base
                        break
                    }
                } catch (e: ModbusFrameFailure) {
                    continue
                }
            }
            if (start == null) throw ModbusFrameFailure.UnexpectedDevice
            var address = start + 2
            var model: String? = null
            // A real chain has a handful of models; the limit stops a device that never ends it.
            repeat(40) {
                val header = registers(address, 2)
                val id = header[0]
                val length = header[1]
                if (id == 0xFFFF || length <= 0 || address + 2 + length > 0xFFFF) throw ModbusFrameFailure.UnexpectedDevice
                if (id == 1 && length >= 32) {
                    model = commonModelName(registers(address + 2, 32))
                } else if (id in integerInverterModels || id in floatInverterModels) {
                    val data = registers(address + 2, minOf(length, 50))
                    val reading = inverterReading(id, data) ?: throw ModbusFrameFailure.UnexpectedDevice
                    return InverterIdentification(model, reading)
                }
                address += 2 + length
            }
            throw ModbusFrameFailure.UnexpectedDevice
        }

        /**
         * Manufacturer (registers 0-15) and model (16-31) of the common model, e.g. "SolarEdge SE5000H" or "Fronius
         * Symo 8.2-3-M". The model alone when it already starts with the manufacturer.
         */
        internal fun commonModelName(data: List<Int>): String? {
            if (data.size < 32) return null
            val manufacturer = ModbusFrame.text(data.subList(0, 16))
            val model = ModbusFrame.text(data.subList(16, 32))
            return if (manufacturer != null && model != null) {
                if (model.lowercase().startsWith(manufacturer.lowercase())) model else "$manufacturer $model"
            } else {
                model ?: manufacturer
            }
        }

        /**
         * The data of inverter model 101-103 or 111-113 (without the ID and length registers). Null when the power is
         * not implemented or the block is too short.
         */
        internal fun inverterReading(model: Int, data: List<Int>): LiveInverterReading? {
            if (model in integerInverterModels) {
                // W 12, W_SF 13, WH 22-23 (acc32, 0 = not implemented), WH_SF 24.
                if (data.size < 25 || data[12] == 0x8000) return null
                val watts = scaled(data[12].toShort().toDouble(), data[13]) ?: return null
                val wattHours = ModbusFrame.uint32(data, 22)
                val lifetime = if (wattHours == 0L) null else scaled(wattHours.toDouble(), data[24])?.let { it / 1000 }
                return LiveInverterReading(activePowerKW = watts / 1000, totalEnergyKWh = lifetime)
            }
            // Floats: W 20-21, WH 30-31; NaN = not implemented.
            if (data.size < 32) return null
            val watts = Float.fromBits(ModbusFrame.uint32(data, 20).toInt()).toDouble()
            val wattHours = Float.fromBits(ModbusFrame.uint32(data, 30).toInt()).toDouble()
            if (!watts.isFinite()) return null
            return LiveInverterReading(
                activePowerKW = watts / 1000,
                totalEnergyKWh = if (wattHours.isFinite() && wattHours > 0) wattHours / 1000 else null,
            )
        }

        /**
         * [value] x 10^[factor] (a signed scale factor register); null when the factor is not implemented (0x8000) or
         * out of range.
         */
        internal fun scaled(value: Double, factor: Int): Double? {
            val exponent = factor.toShort().toInt()
            if (factor == 0x8000 || exponent !in -10..10) return null
            return value * 10.0.pow(exponent)
        }
    }
}
