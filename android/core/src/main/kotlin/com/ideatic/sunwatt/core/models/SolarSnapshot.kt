package com.ideatic.sunwatt.core.models

import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/** One point of today's production curve. `kW == null` means "no data" (the kiosk sends "-" / "--" / ""). */
@Serializable
data class CurvePoint(
    @Serializable(with = InstantIso8601Serializer::class) val time: Instant,
    val kW: Double? = null,
)

/**
 * What the house uses and trades with the grid, from a meter at the connection point. Only plants with such a meter
 * report it (SolarEdge with a meter, PVOutput when the uploader sends consumption). Export-only plants and the
 * FusionSolar kiosk have none: [SolarSnapshot.home] is null and the views show nothing about it.
 */
@Serializable
data class HomeEnergy(
    /** What the house uses now (kW). */
    val consumptionKW: Double? = null,
    /** Power from the grid now (kW): positive when buying, negative when selling. */
    val gridKW: Double? = null,
    /** Energy the house used today (kWh). */
    val dailyConsumptionKWh: Double? = null,
    /** Energy bought from the grid today (kWh). */
    val dailyImportKWh: Double? = null,
    /** Energy sold to the grid today (kWh). */
    val dailyExportKWh: Double? = null,
    /** Today's consumption curve, same time base as the production curve. */
    val curve: List<CurvePoint> = emptyList(),
) {
    /** Share of today's consumption covered by the panels (0...1): 1 - import / consumption. Null when unknown. */
    val selfSufficiency: Double?
        get() {
            val used = dailyConsumptionKWh ?: return null
            val bought = dailyImportKWh ?: return null
            if (used <= 0.05) return null
            return min(max(1 - bought / used, 0.0), 1.0)
        }
}

/** Solar data of one plant, whatever the provider. Contract: see [SolarDataSource]. */
@Serializable
data class SolarSnapshot(
    val stationName: String? = null,
    /** Current power in kW. */
    val currentPowerKW: Double? = null,
    val dailyEnergyKWh: Double? = null,
    val monthEnergyKWh: Double? = null,
    val yearEnergyKWh: Double? = null,
    val cumulativeEnergyKWh: Double? = null,
    /** Today's curve, sorted by time. */
    val curve: List<CurvePoint> = emptyList(),
    /** When this app fetched the data (device clock). */
    @Serializable(with = InstantIso8601Serializer::class) val fetchedAt: Instant,
    /**
     * Time of the last non-null curve point, if any. Huawei caches up to ~30 min, so this is the "data as of" time.
     */
    @Serializable(with = InstantIso8601Serializer::class) val dataAsOf: Instant? = null,
    /**
     * One-way identifier of the kiosk link (host + token) this snapshot came from, set by the loader. Null in caches
     * written before this field existed: those never match a link.
     */
    val endpointID: String? = null,
    /**
     * Home consumption and grid exchange. Null when the plant has no meter (export only, or the source has no such
     * data): then nothing about consumption is shown.
     */
    val home: HomeEnergy? = null,
    /** True when the figures were read from the inverter on the home network just now (not through the cloud). */
    val isLive: Boolean? = null,
    /**
     * Live snapshots only: when the cloud part (kiosk, SolarEdge, PVOutput) was last fetched. Rate limits count from
     * this, not from [fetchedAt], which every inverter read moves. Null when the snapshot is the cloud data itself.
     */
    @Serializable(with = InstantIso8601Serializer::class) val cloudFetchedAt: Instant? = null,
) {
    /**
     * The provider contract: production is never negative. Inverters report a small standby draw at night as negative
     * power; it reads as 0.
     */
    fun clampingProduction(): SolarSnapshot = copy(
        currentPowerKW = currentPowerKW?.let { max(it, 0.0) },
        curve = curve.map { CurvePoint(it.time, it.kW?.let { kW -> max(kW, 0.0) }) },
        dailyEnergyKWh = dailyEnergyKWh?.let { max(it, 0.0) },
    )

    /** True when the plant is not producing (night or no data). */
    val isIdle: Boolean get() = (currentPowerKW ?: 0.0) <= 0.01

    /** Latest point with a value. */
    val latestPoint: CurvePoint? get() = curve.lastOrNull { it.kW != null }

    /** Peak power of the curve. */
    val peakKW: Double? get() = curve.mapNotNull { it.kW }.maxOrNull()
}
