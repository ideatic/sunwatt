package com.ideatic.sunwatt.core.models

import com.ideatic.sunwatt.core.services.EnergyChartsClient
import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.OkHttpSunWattClient
import com.ideatic.sunwatt.core.services.PriceDataSource
import com.ideatic.sunwatt.core.services.PvpcClient
import kotlinx.serialization.Serializable
import java.time.ZoneId

/**
 * Country / bidding zone whose electricity price is shown.
 * Spain uses the regulated retail PVPC price from Red Eléctrica; the others use day-ahead
 * wholesale prices from Energy-Charts (Fraunhofer ISE, CC BY 4.0).
 */
@Serializable(with = PriceRegionSerializer::class)
enum class PriceRegion(val rawValue: String) {
    SPAIN("spain"),
    PORTUGAL("portugal"),
    FRANCE("france"),
    GERMANY("germany"),
    AUSTRIA("austria"),
    BELGIUM("belgium"),
    NETHERLANDS("netherlands"),
    ITALY_NORTH("italyNorth");

    /**
     * Everything SunWatt knows about the region. The one place to describe a new one (with its name in the app's
     * strings and a contract case); the properties below only read it.
     */
    val profile: PriceRegionProfile
        get() = when (this) {
            SPAIN -> PriceRegionProfile(Constants.madrid, "🇪🇸", PriceSource.RedElectrica)
            PORTUGAL -> PriceRegionProfile(ZoneId.of("Europe/Lisbon"), "🇵🇹", PriceSource.EnergyCharts("PT"))
            // CET/CEST: the same rules as Europe/Madrid, so the Madrid zone serves them.
            FRANCE -> PriceRegionProfile(Constants.madrid, "🇫🇷", PriceSource.EnergyCharts("FR"))
            GERMANY -> PriceRegionProfile(Constants.madrid, "🇩🇪", PriceSource.EnergyCharts("DE-LU"))
            AUSTRIA -> PriceRegionProfile(Constants.madrid, "🇦🇹", PriceSource.EnergyCharts("AT"))
            BELGIUM -> PriceRegionProfile(Constants.madrid, "🇧🇪", PriceSource.EnergyCharts("BE"))
            NETHERLANDS -> PriceRegionProfile(Constants.madrid, "🇳🇱", PriceSource.EnergyCharts("NL"))
            ITALY_NORTH -> PriceRegionProfile(Constants.madrid, "🇮🇹", PriceSource.EnergyCharts("IT-North"))
        }

    /** Local time zone of the market day. Use for all price day/hour math. */
    val timeZone: ZoneId get() = profile.timeZone

    /** True for a retail tariff (PVPC); false for wholesale day-ahead prices. */
    val isRetailTariff: Boolean get() = profile.source.isRetailTariff

    /** Flag emoji for pickers. */
    val flag: String get() = profile.flag

    /**
     * Non-localized name of the price shown: "PVPC" in Spain. Null for the day-ahead wholesale price, whose name
     * ("Day-ahead price") is a localized string owned by the app.
     */
    val tariffName: String?
        get() = when (profile.source) {
            PriceSource.RedElectrica -> "PVPC"
            is PriceSource.EnergyCharts -> null
        }

    /** Data provider name (a proper noun, not translated). */
    val sourceName: String get() = profile.source.name

    /** Energy-Charts bidding zone code; null for a region served by another source. */
    val energyChartsZone: String?
        get() = (profile.source as? PriceSource.EnergyCharts)?.zone

    // The localized `displayName` (country names) is an app string, not core.
    /** The client for this region's prices. */
    fun client(http: HttpClient = OkHttpSunWattClient()): PriceDataSource = profile.source.client(this, http)

    companion object {
        fun fromRaw(raw: String): PriceRegion? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Stored as the raw string; an unknown value fails. */
object PriceRegionSerializer : RawValueEnumSerializer<PriceRegion>("PriceRegion", PriceRegion.entries, { it.rawValue })

/** For settings: an unknown region (from a newer app version) reads as Spain instead of losing every setting. */
object LenientPriceRegionSerializer :
    RawValueEnumSerializer<PriceRegion>("PriceRegion", PriceRegion.entries, { it.rawValue }, PriceRegion.SPAIN)

/** What distinguishes one price region from another ([PriceRegion.profile]). */
data class PriceRegionProfile(
    /** Time zone of the market day: day and hour math, labels. */
    val timeZone: ZoneId,
    /** Flag emoji for pickers. */
    val flag: String,
    /** Who publishes the prices, and what kind of price they are. */
    val source: PriceSource,
)

/** Who publishes a region's prices. Each source has a client that keeps the [PriceDataSource] contract. */
sealed interface PriceSource {
    /** Red Eléctrica's regulated retail tariff (PVPC, Spain): one request per market day. */
    data object RedElectrica : PriceSource

    /**
     * Energy-Charts (Fraunhofer ISE) day-ahead wholesale prices of a bidding zone. CC BY 4.0: the attribution must
     * stay visible. Answers HTTP 429 to parallel requests: today and tomorrow in one request, and the loader's shared
     * cache window keeps widgets from asking at once.
     */
    data class EnergyCharts(val zone: String) : PriceSource

    /** Client for [region]'s prices from this source. */
    fun client(region: PriceRegion, http: HttpClient = OkHttpSunWattClient()): PriceDataSource = when (this) {
        RedElectrica -> PvpcClient(http)
        is EnergyCharts -> EnergyChartsClient(region, http)
    }

    /** True for a retail tariff; false for wholesale day-ahead prices (never presented as what the user pays). */
    val isRetailTariff: Boolean
        get() = when (this) {
            RedElectrica -> true
            is EnergyCharts -> false
        }

    /** Publisher name (a proper noun, not translated). */
    val name: String
        get() = when (this) {
            RedElectrica -> "Red Eléctrica"
            is EnergyCharts -> "Energy-Charts (Fraunhofer ISE)"
        }

    /** Licence whose attribution must stay visible next to the prices; null when none is required. */
    val license: String?
        get() = when (this) {
            RedElectrica -> null
            is EnergyCharts -> "CC BY 4.0"
        }
}
