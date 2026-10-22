package com.ideatic.sunwatt.core.models

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * One solar plant: its provider, how SunWatt reaches it, and optionally its inverter on the home network.
 *
 * The first plant ([SunWattSettings.primaryPlant], id [PRIMARY_ID]) lives in the settings fields that existed before
 * several plants were possible, so settings, secrets and caches of a single-plant install stay where they were.
 * Further plants are [SunWattSettings.additionalPlants]. The kiosk link and the API key are secrets: [toString] hides
 * them, and the store keeps them in the keystore.
 */
@Serializable
data class SolarPlant(
    /** [PRIMARY_ID] for the first plant, a UUID string for the others. Never shown. */
    val id: String = UUID.randomUUID().toString(),
    /** Name chosen by the user; empty: the station name the provider reports, else the provider's name. */
    val name: String = "",
    /** An unknown provider (from a newer version) reads as FusionSolar. */
    @Serializable(with = LenientSolarProviderSerializer::class) val provider: SolarProvider = SolarProvider.FUSION_SOLAR,
    val kioskURL: String = "",
    /** SolarEdge site ID or PVOutput system ID. */
    val siteID: String = "",
    val apiKey: String = "",
    @Serializable(with = InstantIso8601Serializer::class) val kioskLinkSavedAt: Instant? = null,
    val kioskExpiryNoticeDismissed: Boolean = false,
    val huaweiLocalHost: String = "",
    val huaweiLocalUnitID: Int = 1,
    val sunSpecHost: String = "",
    val sunSpecUnitID: Int = 1,
    /** FusionSolar API account: the server; empty: [NORTHBOUND_DEFAULT_HOST]. */
    val northboundHost: String = "",
    /** Fronius: the inverter's address on the home network. */
    val froniusHost: String = "",
) {
    val isPrimary: Boolean get() = id == PRIMARY_ID

    /** The name to show: the user's, else [stationName] (what the provider calls the plant), else the provider. */
    fun displayName(stationName: String?): String {
        val own = name.trim()
        if (own.isNotEmpty()) return own
        if (!stationName.isNullOrEmpty()) return stationName
        return provider.displayName
    }

    override fun toString(): String =
        "SolarPlant(id: $id, name: $name, provider: ${provider.rawValue}, " +
            "kioskURL: ${if (kioskURL.isEmpty()) "" else "***"}, apiKey: ${if (apiKey.isEmpty()) "" else "***"})"

    companion object {
        const val PRIMARY_ID = "primary"
    }
}

/** The first plant, read from the original settings fields. Set it with [withPrimaryPlant]. */
val SunWattSettings.primaryPlant: SolarPlant
    get() = SolarPlant(
        id = SolarPlant.PRIMARY_ID, name = primaryPlantName, provider = solarProvider, kioskURL = kioskURL,
        siteID = solarSiteID, apiKey = solarAPIKey, kioskLinkSavedAt = kioskLinkSavedAt,
        kioskExpiryNoticeDismissed = kioskExpiryNoticeDismissed, huaweiLocalHost = huaweiLocalHost,
        huaweiLocalUnitID = huaweiLocalUnitID, sunSpecHost = sunSpecHost, sunSpecUnitID = sunSpecUnitID,
        northboundHost = northboundHost, froniusHost = froniusHost,
    )

/** These settings with [plant] written into the original fields of the first plant (its id is ignored). */
fun SunWattSettings.withPrimaryPlant(plant: SolarPlant): SunWattSettings = copy(
    primaryPlantName = plant.name,
    solarProvider = plant.provider,
    kioskURL = plant.kioskURL,
    solarSiteID = plant.siteID,
    solarAPIKey = plant.apiKey,
    kioskLinkSavedAt = plant.kioskLinkSavedAt,
    kioskExpiryNoticeDismissed = plant.kioskExpiryNoticeDismissed,
    huaweiLocalHost = plant.huaweiLocalHost,
    huaweiLocalUnitID = plant.huaweiLocalUnitID,
    sunSpecHost = plant.sunSpecHost,
    sunSpecUnitID = plant.sunSpecUnitID,
    northboundHost = plant.northboundHost,
    froniusHost = plant.froniusHost,
)

/** Every plant, the first one first. Always at least one (the first, possibly not set up). */
val SunWattSettings.plants: List<SolarPlant> get() = listOf(primaryPlant) + additionalPlants

/** True with more than one plant: widgets rotate, the dashboard pages, Siri gives a total. */
val SunWattSettings.hasSeveralPlants: Boolean get() = additionalPlants.isNotEmpty()

/** The plant with this id, if any. */
fun SunWattSettings.plant(id: String): SolarPlant? = plants.firstOrNull { it.id == id }

/** Replaces the plant with the same id (the first one or an additional one). Unknown id: unchanged. */
fun SunWattSettings.updatingPlant(plant: SolarPlant): SunWattSettings {
    if (plant.isPrimary) return withPrimaryPlant(plant)
    val index = additionalPlants.indexOfFirst { it.id == plant.id }
    if (index < 0) return this
    return copy(additionalPlants = additionalPlants.toMutableList().also { it[index] = plant })
}

/**
 * These settings with [plant] in the place of the first one: everything written for "the" solar plant (accounts,
 * loader, editors) works on any plant this way.
 */
fun SunWattSettings.focused(plant: SolarPlant): SunWattSettings = withPrimaryPlant(plant)
