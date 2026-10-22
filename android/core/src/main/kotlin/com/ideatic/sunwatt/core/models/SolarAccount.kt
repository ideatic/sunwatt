package com.ideatic.sunwatt.core.models

import com.ideatic.sunwatt.core.inverter.FroniusSolarAPIClient
import com.ideatic.sunwatt.core.inverter.LocalInverterSolarClient
import com.ideatic.sunwatt.core.inverter.ModbusTransportFactory
import com.ideatic.sunwatt.core.services.FusionSolarKioskClient
import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.InMemoryNorthboundSessionStore
import com.ideatic.sunwatt.core.services.KioskEndpoint
import com.ideatic.sunwatt.core.services.NorthboundClient
import com.ideatic.sunwatt.core.services.NorthboundCredentials
import com.ideatic.sunwatt.core.services.NorthboundSessionStore
import com.ideatic.sunwatt.core.services.OkHttpSunWattClient
import com.ideatic.sunwatt.core.services.PvOutputClient
import com.ideatic.sunwatt.core.services.SolarDataSource
import com.ideatic.sunwatt.core.services.SolarEdgeClient
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Default FusionSolar server of the API account (Northbound). The Northbound piece will own this constant; it lives
 * here only because the provider's portal URL is built from it.
 */
const val NORTHBOUND_DEFAULT_HOST = "eu5.fusionsolar.huawei.com"

/** Monitoring service the solar data comes from. */
@Serializable(with = SolarProviderSerializer::class)
enum class SolarProvider(val rawValue: String) {
    /** Huawei FusionSolar public kiosk link ([SunWattSettings.kioskURL]). */
    FUSION_SOLAR("fusionSolar"),

    /** SolarEdge Monitoring API: site ID + API key. */
    SOLAR_EDGE("solarEdge"),

    /** pvoutput.org: system ID + API key. Many inverter brands upload there. */
    PV_OUTPUT("pvOutput"),

    /**
     * Huawei FusionSolar Northbound API ("API account"): user name + system code of an account the installer creates.
     * Shown as the second way to connect FusionSolar, not as its own button.
     */
    FUSION_SOLAR_API("fusionSolarAPI"),

    /**
     * A Fronius inverter read through its Solar API on the home network ([SunWattSettings.froniusHost]). No cloud:
     * Fronius's Solar.web API is paid. Away from home the last data stays on screen.
     */
    FRONIUS("fronius");

    /**
     * Everything SunWatt knows about the provider. The one place to describe a new one (with its client and its
     * account case); the properties below only read it.
     */
    val profile: SolarProviderProfile
        get() = when (this) {
            FUSION_SOLAR -> SolarProviderProfile(
                displayName = "Huawei FusionSolar", shortName = "FusionSolar",
                setup = SolarProviderProfile.Setup.KIOSK_LINK, portalUrl = null,
                minimumFetchInterval = Duration.ZERO, localInverter = LocalInverterKind.HUAWEI, keepsCloudHome = false,
            )
            // 300 requests a day per site, 2 per fetch: one fetch every 20 minutes is at most 144 a day per device, so
            // a phone and a watch together stay under the limit. Its curve has 15-minute points, so nothing is lost.
            SOLAR_EDGE -> SolarProviderProfile(
                displayName = "SolarEdge", shortName = "SolarEdge",
                setup = SolarProviderProfile.Setup.API_KEY, portalUrl = "https://monitoring.solaredge.com",
                minimumFetchInterval = 20.minutes, localInverter = LocalInverterKind.SUN_SPEC, keepsCloudHome = true,
            )
            // 60 requests an hour: no limit needed at SunWatt's refresh rate.
            PV_OUTPUT -> SolarProviderProfile(
                displayName = "PVOutput", shortName = "PVOutput",
                setup = SolarProviderProfile.Setup.API_KEY, portalUrl = "https://pvoutput.org/account.jsp",
                minimumFetchInterval = Duration.ZERO, localInverter = LocalInverterKind.SUN_SPEC, keepsCloudHome = true,
            )
            // Huawei limits how often each interface may be called (HTTP 200 with failCode 407) and allows one session
            // per account; a fetch is 3 to 5 calls. Every 10 minutes keeps a phone and a watch inside the limits.
            FUSION_SOLAR_API -> SolarProviderProfile(
                displayName = "Huawei FusionSolar API", shortName = "FusionSolar",
                setup = SolarProviderProfile.Setup.NORTHBOUND_ACCOUNT, portalUrl = "https://$NORTHBOUND_DEFAULT_HOST",
                minimumFetchInterval = 10.minutes, localInverter = LocalInverterKind.HUAWEI, keepsCloudHome = false,
                pickerGroup = FUSION_SOLAR,
            )
            // The inverter on the home network only: no request limit, no cloud consumption to keep.
            FRONIUS -> SolarProviderProfile(
                displayName = "Fronius", shortName = "Fronius",
                setup = SolarProviderProfile.Setup.HOME_NETWORK, portalUrl = null,
                minimumFetchInterval = Duration.ZERO, localInverter = LocalInverterKind.FRONIUS, keepsCloudHome = false,
            )
        }

    /** The picker button that shows as selected for this provider. */
    val pickerGroup: SolarProvider get() = profile.pickerGroup ?: this

    /** Brand name, the same in every language. */
    val displayName: String get() = profile.displayName

    /** Short name for the provider picker (four fit side by side on a phone). */
    val shortName: String get() = profile.shortName

    /** Where the user finds the ID and API key. */
    val portalUrl: String? get() = profile.portalUrl

    /** True when the provider is set up with an ID + API key (not a link). */
    val usesAPIKey: Boolean get() = profile.setup == SolarProviderProfile.Setup.API_KEY

    /**
     * True when the provider's credentials are kept in `solarSiteID` and `solarAPIKey` (ID + key, or user name +
     * system code): switching between such providers clears them.
     */
    val usesCredentialFields: Boolean
        get() = when (profile.setup) {
            SolarProviderProfile.Setup.API_KEY, SolarProviderProfile.Setup.NORTHBOUND_ACCOUNT -> true
            SolarProviderProfile.Setup.KIOSK_LINK, SolarProviderProfile.Setup.HOME_NETWORK -> false
        }

    /** Solar data younger than this is reused instead of fetched, even on an explicit refresh. */
    val minimumFetchInterval: Duration get() = profile.minimumFetchInterval

    companion object {
        fun fromRaw(raw: String): SolarProvider? = entries.firstOrNull { it.rawValue == raw }

        /** The providers with their own button in the picker (the others are a second way to connect one of them). */
        val pickerCases: List<SolarProvider> get() = entries.filter { it.pickerGroup == it }
    }
}

/** Stored as the raw string; an unknown value fails. */
object SolarProviderSerializer :
    RawValueEnumSerializer<SolarProvider>("SolarProvider", SolarProvider.entries, { it.rawValue })

/** For settings and plants: an unknown provider (from a newer app version) reads as FusionSolar. */
object LenientSolarProviderSerializer : RawValueEnumSerializer<SolarProvider>(
    "SolarProvider", SolarProvider.entries, { it.rawValue }, SolarProvider.FUSION_SOLAR,
)

/** What distinguishes one solar provider from another ([SolarProvider.profile]). */
data class SolarProviderProfile(
    /** Brand name, the same in every language. */
    val displayName: String,
    /** Short name for the provider picker. */
    val shortName: String,
    val setup: Setup,
    /** Where the user finds the ID and API key; null when the app explains it itself. */
    val portalUrl: String?,
    /**
     * The provider's request limit as SunWatt applies it: solar data (or a failure) younger than this is reused
     * instead of fetched, even on an explicit refresh. Zero: no limit. The inverter on the home network is still read.
     */
    val minimumFetchInterval: Duration,
    /** Which inverter protocol "Live data at home" speaks for this provider. */
    val localInverter: LocalInverterKind,
    /**
     * Keep the cloud's home consumption next to live inverter data that has no meter reading (the provider's own meter
     * measures it). False: the inverter's meter is the only source (Huawei).
     */
    val keepsCloudHome: Boolean,
    /** The picker button this provider belongs to when it is another way to connect that provider; null: its own. */
    val pickerGroup: SolarProvider? = null,
) {
    /** How the user connects the account. */
    enum class Setup {
        /** A public read-only link (FusionSolar kiosk). Links expire; the app warns before and after. */
        KIOSK_LINK,

        /** An ID + API key ([SolarAPICredentials]), kept in the keystore. */
        API_KEY,

        /** A user name + system code on a FusionSolar server; the code in the keystore. */
        NORTHBOUND_ACCOUNT,

        /** Only the inverter's address on the home network (Fronius); no account, no secret. */
        HOME_NETWORK,
    }
}

/**
 * Site/system ID + API key of SolarEdge or PVOutput. Both values are trimmed; the ID must be digits only and the key
 * letters and digits only (8 to 128). The key is a secret: [toString] hides it. Build one with [parse].
 */
class SolarAPICredentials private constructor(val siteID: String, val apiKey: String) {
    override fun equals(other: Any?): Boolean =
        other is SolarAPICredentials && other.siteID == siteID && other.apiKey == apiKey

    override fun hashCode(): Int = 31 * siteID.hashCode() + apiKey.hashCode()

    override fun toString(): String = "SolarAPICredentials(siteID: $siteID, apiKey: ***)"

    companion object {
        /** Returns null when either value is not valid. */
        fun parse(siteID: String, apiKey: String): SolarAPICredentials? {
            val id = siteID.trim()
            val key = apiKey.trim()
            if (id.length !in 1..12 || !id.all { it in '0'..'9' }) return null
            if (key.length !in 8..128 || !key.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }) return null
            return SolarAPICredentials(id, key)
        }
    }
}

/**
 * The solar source configured in settings, validated. [fromSettings] and [fromPlant] give null when the plant is not set
 * up or its values are not valid. No case prints a secret: the kiosk link, the API key and the system code are hidden
 * by their own types.
 */
sealed class SolarAccount {
    /**
     * FusionSolar kiosk link; [local]: the Huawei dongle on the home network (optional). At least one of the two is set
     * ([fromPlant] makes sure): the dongle alone is enough at home.
     */
    data class FusionSolar(val kiosk: KioskEndpoint?, override val local: ModbusEndpoint? = null) : SolarAccount()

    /** [local]: a SunSpec inverter on the home network (optional), read for live data while it answers. */
    data class SolarEdge(val credentials: SolarAPICredentials, override val local: ModbusEndpoint? = null) : SolarAccount()

    data class PvOutput(val credentials: SolarAPICredentials, override val local: ModbusEndpoint? = null) : SolarAccount()

    /** FusionSolar Northbound API account; [local]: the Huawei dongle on the home network (optional). */
    data class FusionSolarAPI(val credentials: NorthboundCredentials, override val local: ModbusEndpoint? = null) : SolarAccount()

    /** A Fronius inverter's Solar API on the home network (port 80 unless the address names another). */
    data class Fronius(override val local: ModbusEndpoint) : SolarAccount()

    /** The inverter read on the home network, if set up: the Huawei dongle, a SunSpec inverter or a Fronius inverter. */
    abstract val local: ModbusEndpoint?

    /** The inverter read on the home network, if set up. Same as [local], named as in the Swift `localInverter`. */
    val localInverter: ModbusEndpoint? get() = local

    /** The kiosk link of a FusionSolar account, if any. */
    val kioskEndpoint: KioskEndpoint?
        get() = (this as? FusionSolar)?.kiosk

    val provider: SolarProvider
        get() = when (this) {
            is FusionSolar -> SolarProvider.FUSION_SOLAR
            is SolarEdge -> SolarProvider.SOLAR_EDGE
            is PvOutput -> SolarProvider.PV_OUTPUT
            is FusionSolarAPI -> SolarProvider.FUSION_SOLAR_API
            is Fronius -> SolarProvider.FRONIUS
        }

    /**
     * Reader for the inverter on the home network, if set up. [makeTransport] null: the reader's own TCP transport.
     * [http] (Fronius) null: the shared OkHttp client.
     */
    fun localReader(makeTransport: ModbusTransportFactory? = null, http: HttpClient? = null): LocalInverterReader? =
        local?.let { provider.profile.localInverter.reader(it, makeTransport, http) }

    /**
     * One-way identifier of the account for caches (never the secret itself). FusionSolar keeps
     * [KioskEndpoint.cacheID], so caches written before other providers existed stay valid. The inverter address is
     * not part of it (except for an account that is only an inverter): adding one keeps the cloud cache.
     */
    val cacheID: String
        get() = when (this) {
            // Same plant as the kiosk link: keep its cache when the inverter address is added.
            is FusionSolar -> kiosk?.cacheID ?: sha256Hex("huaweiLocal\n${local?.host ?: ""}")
            is SolarEdge -> sha256Hex("${provider.rawValue}\n${credentials.siteID}\n${credentials.apiKey}")
            is PvOutput -> sha256Hex("${provider.rawValue}\n${credentials.siteID}\n${credentials.apiKey}")
            is FusionSolarAPI ->
                sha256Hex("${provider.rawValue}\n${credentials.host}\n${credentials.userName}\n${credentials.systemCode}")
            is Fronius -> sha256Hex("${provider.rawValue}\n${local.host}\n${local.port}")
        }

    /**
     * Client for this account. [northboundSessions] is where a FusionSolar API account keeps its session (one store per
     * process, DECISIONS D36); the default keeps it in this process's memory only.
     */
    fun client(
        http: HttpClient = OkHttpSunWattClient(),
        northboundSessions: NorthboundSessionStore = defaultNorthboundSessions,
        now: () -> Instant = Instant::now,
    ): SolarDataSource = when (this) {
        is FusionSolar -> withLocal(kiosk?.let { FusionSolarKioskClient(it, http, now) }, http, now)
        is SolarEdge -> withLocal(SolarEdgeClient(credentials, http, now), http, now)
        is PvOutput -> withLocal(PvOutputClient(credentials, http, now), http, now)
        is FusionSolarAPI -> withLocal(NorthboundClient(credentials, http, now, northboundSessions), http, now)
        is Fronius -> withLocal(null, http, now)
    }

    /**
     * The inverter alone, without the cloud source: used while the cloud may not be asked again yet
     * ([SolarProvider.minimumFetchInterval]). Null without an inverter on the home network.
     */
    fun liveOnlyClient(http: HttpClient? = null, now: () -> Instant = Instant::now): SolarDataSource? =
        localReader(http = http)?.let {
            LocalInverterSolarClient(it, cloud = null, keepsCloudHome = provider.profile.keepsCloudHome, now = now)
        }

    /**
     * The cloud source, with the inverter on the home network in front of it when one is set up. [cloud] is null for a
     * FusionSolar plant set up with the dongle alone and for Fronius.
     */
    private fun withLocal(cloud: SolarDataSource?, http: HttpClient, now: () -> Instant): SolarDataSource {
        val reader = localReader(http = http)
        if (reader != null) {
            return LocalInverterSolarClient(reader, cloud, keepsCloudHome = provider.profile.keepsCloudHome, now = now)
        }
        return cloud ?: NotConfiguredSolarSource
    }

    companion object {
        /** Used when the caller passes no session store (tests, a single process). The app passes its own. */
        private val defaultNorthboundSessions: NorthboundSessionStore by lazy { InMemoryNorthboundSessionStore() }

        /** The first plant's account. */
        fun fromSettings(settings: SunWattSettings): SolarAccount? = fromPlant(settings.primaryPlant)

        fun fromPlant(plant: SolarPlant): SolarAccount? = when (plant.provider) {
            SolarProvider.FUSION_SOLAR -> {
                val kiosk = KioskEndpoint.parse(plant.kioskURL)
                val local = ModbusEndpoint.parse(plant.huaweiLocalHost, plant.huaweiLocalUnitID)
                if (kiosk == null && local == null) null else FusionSolar(kiosk, local)
            }
            SolarProvider.SOLAR_EDGE, SolarProvider.PV_OUTPUT -> {
                val credentials = SolarAPICredentials.parse(plant.siteID, plant.apiKey)
                if (credentials == null) {
                    null
                } else {
                    // An address that does not parse is ignored, like the Huawei one: the cloud keeps working.
                    api(plant.provider, credentials, ModbusEndpoint.parse(plant.sunSpecHost, plant.sunSpecUnitID))
                }
            }
            SolarProvider.FUSION_SOLAR_API ->
                NorthboundCredentials.create(plant.northboundHost, plant.siteID, plant.apiKey)?.let {
                    FusionSolarAPI(it, ModbusEndpoint.parse(plant.huaweiLocalHost, plant.huaweiLocalUnitID))
                }
            SolarProvider.FRONIUS ->
                ModbusEndpoint.parse(plant.froniusHost, defaultPort = FroniusSolarAPIClient.PORT)?.let { Fronius(it) }
        }

        /** The account of an ID + API key provider; null for a provider set up otherwise. */
        fun api(provider: SolarProvider, credentials: SolarAPICredentials, local: ModbusEndpoint? = null): SolarAccount? =
            when (provider) {
                SolarProvider.FUSION_SOLAR, SolarProvider.FUSION_SOLAR_API, SolarProvider.FRONIUS -> null
                SolarProvider.SOLAR_EDGE -> SolarEdge(credentials, local)
                SolarProvider.PV_OUTPUT -> PvOutput(credentials, local)
            }

        private fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Stands in for an account with neither a cloud source nor an inverter, which [SolarAccount.fromPlant] never builds; the
 * loader shows "not configured".
 */
object NotConfiguredSolarSource : SolarDataSource {
    override suspend fun fetch(): SolarSnapshot = throw SunWattError.NotConfigured

    override fun toString(): String = "NotConfiguredSolarSource"
}

/** True when the user typed anything for the selected provider (valid or not). */
val SunWattSettings.hasSolarInput: Boolean
    get() = when (solarProvider.profile.setup) {
        SolarProviderProfile.Setup.KIOSK_LINK -> kioskURL.isNotBlank() || huaweiLocalHost.isNotBlank()
        SolarProviderProfile.Setup.API_KEY, SolarProviderProfile.Setup.NORTHBOUND_ACCOUNT ->
            solarSiteID.isNotBlank() || solarAPIKey.isNotBlank()
        SolarProviderProfile.Setup.HOME_NETWORK -> froniusHost.isNotBlank()
    }

/** The error to show when there is input for the selected provider but it is not valid. */
val SunWattSettings.invalidSolarInputError: SunWattError
    get() = when (solarProvider.profile.setup) {
        SolarProviderProfile.Setup.API_KEY -> SunWattError.InvalidSolarCredentials
        SolarProviderProfile.Setup.NORTHBOUND_ACCOUNT -> SunWattError.InvalidNorthboundAccount
        SolarProviderProfile.Setup.HOME_NETWORK -> SunWattError.InvalidInverterAddress
        SolarProviderProfile.Setup.KIOSK_LINK ->
            if (huaweiLocalHost.isNotBlank() && ModbusEndpoint.parse(huaweiLocalHost, huaweiLocalUnitID) == null) {
                SunWattError.InvalidInverterAddress
            } else {
                SunWattError.InvalidKioskURL
            }
    }
