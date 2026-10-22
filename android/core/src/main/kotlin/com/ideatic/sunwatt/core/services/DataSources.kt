package com.ideatic.sunwatt.core.services

import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarSnapshot
import java.time.Instant

/**
 * A solar provider (cloud service, inverter on the home network, or both). Contract, checked for every provider by the
 * provider contract tests:
 *
 * - **Snapshot.** `fetchedAt` is the device clock at the fetch (the client's `now`). `curve` holds today's points only
 *   (one local day, at most 25 hours apart, DST included), sorted by time, no two at the same time; a point's `kW` is
 *   null for "no data", never negative. `currentPowerKW` and the energy figures are null when unknown, never negative.
 *   `dataAsOf` is the time of the newest figure the provider reports. `endpointID` is left null (the loader sets it).
 *   `isLive` is true only for figures read from the inverter just now; `cloudFetchedAt` is set only on such live
 *   snapshots.
 * - **Errors.** Only `CancellationException` (the task was cancelled; never wrapped) or [SunWattError]: `Network` (no
 *   connection, timeout), `Http` (any other non-2xx), `BadResponse` (the body cannot be read), `Unavailable` (no data
 *   for today yet), `RateLimited` (asked too often), and the account errors `KioskLinkRejected`,
 *   `SolarCredentialsRejected`, `NorthboundAccountRejected`, `InverterUnreachable`. The loader shows them to the user,
 *   so their text must never contain the kiosk token or an API key (`HttpFetch.perform(redacting)`).
 * - **Budget.** One fetch must finish well within the loader's deadline (15 s) and stop when cancelled. Rate limits are
 *   the provider's `minimumFetchInterval`, applied by the loader, not here.
 * - **No side effects.** No storage, no logging of secrets; the loader stores the result.
 */
interface SolarDataSource {
    suspend fun fetch(): SolarSnapshot
}

/**
 * An electricity price source. Which one serves a region is in `PriceRegion.profile` (`PriceSource`). Contract, checked
 * for every source by the provider contract tests:
 *
 * - **Series.** `region` is the region asked for; points sorted by start, not overlapping, each with a positive
 *   duration (15 or 60 minutes, `granularity`); prices in EUR/MWh (negative allowed). `fetchedAt` is the device clock.
 *   A market day may have 23 or 25 hours (DST).
 * - **Errors.** `CancellationException` or [SunWattError] (`Network`, `Http`, `BadResponse`, `Unavailable` when the day
 *   is not published), as for [SolarDataSource].
 * - **Budget.** Within the loader deadline, cancellable. Energy-Charts answers HTTP 429 to parallel requests: one
 *   request per call where the API allows it.
 */
interface PriceDataSource {
    /** Fetches prices for the market day (in the source's time zone) that contains [day]. */
    suspend fun fetch(day: Instant): PriceSeries

    /**
     * Today, plus tomorrow when [includeTomorrow] and the source has published it. A failure for tomorrow must NOT
     * fail the whole call. "Today" comes from the source's own injected clock (no default implementation: it would
     * have to read the system clock).
     */
    suspend fun fetchTodayAndTomorrow(includeTomorrow: Boolean): PriceSeries
}
