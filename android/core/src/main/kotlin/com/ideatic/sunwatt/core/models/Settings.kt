package com.ideatic.sunwatt.core.models

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What a widget shows (widget configuration). */
@Serializable(with = WidgetContentSerializer::class)
enum class WidgetContent(val rawValue: String) {
    BOTH("both"),
    SOLAR_ONLY("solarOnly"),
    PRICE_ONLY("priceOnly");

    companion object {
        fun fromRaw(raw: String): WidgetContent? = entries.firstOrNull { it.rawValue == raw }
    }
}

object WidgetContentSerializer :
    RawValueEnumSerializer<WidgetContent>("WidgetContent", WidgetContent.entries, { it.rawValue })

/** Which price points a widget chart shows. */
@Serializable(with = PriceWindowSerializer::class)
enum class PriceWindow(val rawValue: String) {
    /** Whole current day (market time zone of the price region). */
    WHOLE_DAY("wholeDay"),

    /** Previous 6 hours + next 12 hours. */
    AROUND("around");

    companion object {
        fun fromRaw(raw: String): PriceWindow? = entries.firstOrNull { it.rawValue == raw }
    }
}

object PriceWindowSerializer : RawValueEnumSerializer<PriceWindow>("PriceWindow", PriceWindow.entries, { it.rawValue })

/**
 * User settings shared between app and widgets. Immutable: change a copy with `copy(...)`. Property names are the
 * stored JSON keys, the same as in the Apple app. The kiosk link and the API key are secrets: [toString] hides them.
 */
@Serializable
data class SunWattSettings(
    val kioskURL: String = "",
    val showTomorrowPrices: Boolean = true,
    /** An unknown region (settings from a newer app version) reads as Spain instead of losing every setting. */
    @Serializable(with = LenientPriceRegionSerializer::class) val priceRegion: PriceRegion = PriceRegion.SPAIN,
    /** True once the user finished (or skipped) the first-launch onboarding in the app. */
    val onboardingCompleted: Boolean = false,
    /**
     * Where the solar data comes from. FusionSolar reads [kioskURL]; SolarEdge and PVOutput read the ID and key.
     * An unknown provider (settings from a newer app version) reads as FusionSolar.
     */
    @Serializable(with = LenientSolarProviderSerializer::class) val solarProvider: SolarProvider = SolarProvider.FUSION_SOLAR,
    /** SolarEdge site ID or PVOutput system ID. */
    val solarSiteID: String = "",
    /** SolarEdge or PVOutput API key. A secret, like the kiosk link: never log it. */
    val solarAPIKey: String = "",
    /**
     * When the current kiosk link was saved in SunWatt. Kiosk links expire one year after Huawei creates them, so this
     * gives a latest-possible expiry (the link may be older). Null without a link.
     */
    @Serializable(with = InstantIso8601Serializer::class) val kioskLinkSavedAt: Instant? = null,
    /** The user closed the "link expires soon" notice for the current link. */
    val kioskExpiryNoticeDismissed: Boolean = false,
    /**
     * FusionSolar only, optional: IP address or host name of the Huawei dongle on the home network (Modbus TCP). When it
     * answers, SunWatt shows live data from the inverter; elsewhere it uses the kiosk link.
     */
    val huaweiLocalHost: String = "",
    /** Modbus unit ID of the inverter behind the dongle (found by "Test inverter"; usually 1). */
    val huaweiLocalUnitID: Int = 1,
    /**
     * SolarEdge and PVOutput only, optional: address of a SunSpec inverter on the home network ("host" or "host:port",
     * e.g. SolarEdge "192.168.1.40:1502"). When it answers, SunWatt shows its live power.
     */
    val sunSpecHost: String = "",
    /** Modbus unit ID of the SunSpec inverter (found by "Test inverter"; 1, SMA 126). */
    val sunSpecUnitID: Int = 1,
    /** Fronius only: address of the inverter on the home network ("host" or "host:port"; the Solar API's port is 80). */
    val froniusHost: String = "",
    /**
     * FusionSolar API account only: the FusionSolar server (e.g. "eu5.fusionsolar.huawei.com"); empty: the default.
     * The user name is kept in [solarSiteID] and the system code (a secret) in [solarAPIKey].
     */
    val northboundHost: String = "",
    /** Name of the first plant (the fields above), shown when there are several. Empty: the provider's station name. */
    val primaryPlantName: String = "",
    /**
     * Further plants ([SolarPlant]). Empty for a single plant: then nothing in the UI changes. A list that cannot be
     * read is dropped as a whole, not an error.
     */
    @Serializable(with = LenientPlantListSerializer::class) val additionalPlants: List<SolarPlant> = emptyList(),
) {
    /** Never prints the kiosk link or the API key. */
    override fun toString(): String =
        "SunWattSettings(kioskURL=${if (kioskURL.isEmpty()) "" else "***"}, priceRegion=$priceRegion, " +
            "solarProvider=$solarProvider, solarSiteID=$solarSiteID, " +
            "solarAPIKey=${if (solarAPIKey.isEmpty()) "" else "***"}, huaweiLocalHost=$huaweiLocalHost, " +
            "sunSpecHost=$sunSpecHost, froniusHost=$froniusHost, northboundHost=$northboundHost, " +
            "primaryPlantName=$primaryPlantName, additionalPlants=$additionalPlants)"
}

object Constants {
    val madrid: ZoneId = ZoneId.of("Europe/Madrid")

    /** Data older than this is shown as stale. */
    val staleAfter: Duration = 45.minutes

    /** Widget reload request interval. */
    val refreshInterval: Duration = 15.minutes
}
