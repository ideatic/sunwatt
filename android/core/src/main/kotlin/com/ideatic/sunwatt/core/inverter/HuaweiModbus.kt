package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.models.InverterIdentification
import com.ideatic.sunwatt.core.models.LiveInverterReading
import com.ideatic.sunwatt.core.models.LocalInverterReader
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import kotlinx.coroutines.CancellationException

// Huawei SUN2000 inverters read directly on the home network, through the Smart Dongle (SDongleA-05 and similar),
// with Modbus TCP. The dongle must have Modbus TCP set to "Enable (unrestricted)" (an installer setting).
// SunWatt only reads holding registers (function 0x03); it never writes.
// Register map: Huawei "SUN2000 MODBUS Interface Definitions".

/** One reading of the inverter (and of the Huawei power meter, when there is one). */
data class HuaweiInverterReading(
    /** Inverter active power now (kW). Slightly negative at night (standby consumption). */
    val activePowerKW: Double,
    /** Energy produced today (kWh). */
    val dailyEnergyKWh: Double,
    /** Energy produced since installation (kWh). */
    val totalEnergyKWh: Double,
    /** Huawei device status code (e.g. 0x0200 on-grid, 0xA000 standby: no irradiation). */
    val deviceStatus: Int? = null,
    /**
     * Power at the grid connection from a Huawei meter (kW): positive when feeding the grid, negative when buying.
     * Null without a meter (export-only plants).
     */
    val meterActivePowerKW: Double? = null,
) {
    val live: LiveInverterReading
        get() = LiveInverterReading(
            activePowerKW = activePowerKW,
            dailyEnergyKWh = dailyEnergyKWh,
            totalEnergyKWh = totalEnergyKWh,
            meterActivePowerKW = meterActivePowerKW,
        )
}

/** The model name (nil when the dongle does not give it) and a Huawei reading, from one connection. */
data class HuaweiIdentification(val model: String?, val reading: HuaweiInverterReading)

/**
 * Reads a Huawei inverter through its dongle. Three requests per reading: inverter power and status (32080...32089),
 * energy (32106...32115) and the meter (37100...37114, skipped without a meter).
 */
class HuaweiModbusClient(
    val endpoint: ModbusEndpoint,
    makeTransport: ModbusTransportFactory? = null,
) : LocalInverterReader {
    private val makeTransport: ModbusTransportFactory = makeTransport ?: defaultTransport

    suspend fun read(): HuaweiInverterReading = ModbusSession.run(endpoint, makeTransport) { reading(it) }

    /**
     * The model name (registers 30000...30014, ASCII, e.g. "SUN2000-15KTL-M5"; null if the dongle does not give it)
     * and a reading, on one connection. Used by "Find inverter".
     */
    suspend fun identify(): HuaweiIdentification = ModbusSession.run(endpoint, makeTransport) { registers ->
        val model = try {
            ModbusFrame.text(registers(30000, 15))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        HuaweiIdentification(model, reading(registers))
    }

    override suspend fun readLive(): LiveInverterReading = read().live

    override suspend fun identifyLive(): InverterIdentification {
        val identification = identify()
        return InverterIdentification(identification.model, identification.reading.live)
    }

    private suspend fun reading(registers: ModbusSession.ReadRegisters): HuaweiInverterReading {
        val inverter = registers(32080, 10)
        val energy = registers(32106, 10)
        // Meter: status 37100 (1 = normal), active power 37113 (I32, W, > 0 feeding the grid).
        val meter = try {
            registers(37100, 15)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val meterKW = if (meter != null && meter[0] == 1) ModbusFrame.int32(meter, 13) / 1000.0 else null
        return HuaweiInverterReading(
            activePowerKW = ModbusFrame.int32(inverter, 0) / 1000.0,
            dailyEnergyKWh = ModbusFrame.uint32(energy, 8) / 100.0,
            totalEnergyKWh = ModbusFrame.uint32(energy, 0) / 100.0,
            deviceStatus = inverter[9],
            meterActivePowerKW = meterKW,
        )
    }

    companion object {
        /** TCP; Huawei dongles drop requests sent right after connecting, so the transport waits 1 s once connected. */
        val defaultTransport: ModbusTransportFactory = { TcpModbusTransport(it.host, it.port) }
    }
}
