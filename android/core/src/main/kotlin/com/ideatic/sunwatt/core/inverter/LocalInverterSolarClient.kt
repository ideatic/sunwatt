package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.timeline.SunWattLoader
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.HomeEnergy
import com.ideatic.sunwatt.core.models.LiveInverterReading
import com.ideatic.sunwatt.core.models.LocalInverterReader
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.services.SolarDataSource
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The cloud source of the provider (kiosk link, SolarEdge, PVOutput, FusionSolar API) and the inverter on the home
 * network, read together. When the inverter answers (at home), its live figures replace the cloud's and its reading is
 * added to the cloud curve; otherwise (away, or the inverter is busy) the cloud snapshot is used as it is. Without a
 * cloud source ([cloud] null), only the inverter.
 */
class LocalInverterSolarClient(
    val local: LocalInverterReader,
    val cloud: SolarDataSource?,
    /**
     * Keep the cloud's home consumption when the inverter reads no meter (SolarEdge's comes from its own meter).
     * Huawei: false, without a Huawei meter the plant has no consumption figures.
     */
    val keepsCloudHome: Boolean = false,
    /** Away from home the connection can hang until this deadline; keep it short. */
    val localDeadline: Duration = 5.seconds,
    val now: () -> Instant = Instant::now,
) : SolarDataSource {
    override suspend fun fetch(): SolarSnapshot = coroutineScope {
        val fromCloud = async { fetchCloud(cloud) }
        val fromInverter = SunWattLoader.race(localDeadline) { local.readLive() }
        val cloudResult = fromCloud.await()
        val reading = fromInverter.getOrNull()
        if (reading != null) {
            return@coroutineScope merge(reading, cloudResult?.getOrNull(), now(), keepsCloudHome)
        }
        // Without a cloud source every failure (timeout, refused, odd answer) reads as "the inverter did not answer".
        if (cloudResult == null) throw SunWattError.InverterUnreachable
        cloudResult.getOrThrow()
    }

    private suspend fun fetchCloud(source: SolarDataSource?): Result<SolarSnapshot>? {
        if (source == null) return null
        return try {
            Result.success(source.fetch())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        /**
         * [base] (the cloud snapshot, or the last one shown) with the live reading: power, today's and total energy, a
         * curve point at [date], and home consumption when the inverter reads a meter.
         */
        fun merge(reading: LiveInverterReading, base: SolarSnapshot?, date: Instant, keepsBaseHome: Boolean = false): SolarSnapshot {
            var snapshot = base ?: SolarSnapshot(fetchedAt = date)
            val power = max(reading.activePowerKW, 0.0)
            snapshot = snapshot.copy(
                currentPowerKW = power,
                dailyEnergyKWh = reading.dailyEnergyKWh ?: dailyEnergy(reading.totalEnergyKWh, date, base),
                cumulativeEnergyKWh = reading.totalEnergyKWh ?: snapshot.cumulativeEnergyKWh,
                fetchedAt = date,
                dataAsOf = date,
                isLive = true,
            )
            if (base != null) {
                snapshot = snapshot.copy(
                    cloudFetchedAt = if (base.isLive == true) base.cloudFetchedAt else (base.cloudFetchedAt ?: base.fetchedAt),
                )
            }
            // Today's curve only (a base from yesterday contributes nothing), plus this reading. Points of the last
            // minute are replaced, so live reads every few seconds add at most one point a minute.
            val limit = date.minusSeconds(55)
            snapshot = snapshot.copy(
                curve = snapshot.curve.filter { isSameMadridDay(it.time, date) && it.time < limit } + CurvePoint(date, power),
            )
            val meter = reading.meterActivePowerKW
            if (meter != null) {
                // Meter: > 0 feeding the grid. HomeEnergy.gridKW: > 0 buying.
                val grid = -meter
                val consumption = reading.homeConsumptionKW ?: max(power + grid, 0.0)
                snapshot = snapshot.copy(home = HomeEnergy(consumptionKW = consumption, gridKW = grid, dailyConsumptionKWh = null))
            } else if (!keepsBaseHome) {
                snapshot = snapshot.copy(home = null)
            }
            return snapshot
        }

        fun merge(reading: HuaweiInverterReading, base: SolarSnapshot?, date: Instant): SolarSnapshot =
            merge(reading.live, base, date)

        /**
         * Today's energy for an inverter that only has a lifetime counter (SunSpec): [previous] (a live snapshot of the
         * same Madrid day) plus what the counter grew since. When [previous] is not live (its lifetime may come from the
         * cloud and not match the inverter's), its own figure. Null without a snapshot of the same day.
         */
        internal fun dailyEnergy(lifetimeKWh: Double?, date: Instant, previous: SolarSnapshot?): Double? {
            if (previous == null || !isSameMadridDay(previous.fetchedAt, date)) return null
            val daily = previous.dailyEnergyKWh ?: return null
            // A counter that went back (replaced inverter) or jumped (another inverter) adds nothing.
            val before = previous.cumulativeEnergyKWh
            if (previous.isLive == true && lifetimeKWh != null && before != null &&
                lifetimeKWh >= before && lifetimeKWh - before < 500
            ) {
                return daily + (lifetimeKWh - before)
            }
            return daily
        }

        private fun isSameMadridDay(a: Instant, b: Instant): Boolean =
            a.atZone(Constants.madrid).toLocalDate() == b.atZone(Constants.madrid).toLocalDate()
    }
}
