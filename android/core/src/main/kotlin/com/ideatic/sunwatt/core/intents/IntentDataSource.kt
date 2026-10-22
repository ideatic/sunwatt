package com.ideatic.sunwatt.core.intents

import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.models.SolarAccount
import com.ideatic.sunwatt.core.models.currentHour
import com.ideatic.sunwatt.core.models.plants
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** Data for intents, tiles and shortcuts: the cache first, the network only when the cache cannot answer. */
class IntentDataSource(
    val loader: SunWattLoader,
    val deadline: kotlin.time.Duration = 8.seconds,
    /** Data stored less than this long ago is used without a request (see [SunWattLoader.load]). */
    val maxCacheAge: kotlin.time.Duration = SunWattLoader.SHARED_CACHE_WINDOW,
) {
    enum class Need { PRICE, SOLAR }

    suspend fun data(need: Need): SunWattData {
        val cached = loader.cached()
        val now = loader.now()
        val longestInterval = loader.store.loadSettings().plants
            .mapNotNull { SolarAccount.fromPlant(it)?.provider?.minimumFetchInterval }.maxOrNull() ?: kotlin.time.Duration.ZERO
        if (cacheAnswers(cached, need, now, maxOf(SOLAR_MAX_AGE, longestInterval))) return cached
        return loader.load(deadline, maxCacheAge)
    }

    companion object {
        /** Cached solar readings older than this are refreshed (an answer for "now" must be recent). */
        val SOLAR_MAX_AGE: kotlin.time.Duration = 10.minutes

        /** [solarMaxAge]: the provider's own fetch interval when longer (SolarEdge would not fetch again anyway). */
        fun cacheAnswers(
            data: SunWattData,
            need: Need,
            now: Instant,
            solarMaxAge: kotlin.time.Duration = SOLAR_MAX_AGE,
        ): Boolean = when (need) {
            Need.PRICE -> data.prices?.currentHour(now) != null
            // Every plant must be answerable (several plants: the answer gives each one).
            Need.SOLAR -> data.allPlants.all { plant ->
                if (plant.status is SourceStatus.NotConfigured) {
                    true
                } else {
                    val solar = plant.solar
                    solar != null && Duration.between(solar.fetchedAt, now).toKotlinDuration() <= solarMaxAge
                }
            }
        }
    }
}
