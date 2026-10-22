package com.ideatic.sunwatt.core.services

/**
 * Errors shown to the user. The app maps each case to a localized text; nothing here is localized. The message never
 * contains a secret (kiosk token, API key, system code).
 */
sealed class SunWattError(message: String) : Exception(message) {
    /**
     * Stable name of the case (the Swift case name), kept in stored data and used by the app to pick the localized text.
     * Cases with a payload ([Http], [BadResponse], [Network]) carry it in [detail].
     */
    open val code: String get() = message.orEmpty()

    /** The payload of [Http] (the status), [BadResponse] and [Network]; null for the other cases. */
    open val detail: String? get() = null

    data object InvalidKioskURL : SunWattError("invalidKioskURL")

    /**
     * FusionSolar answered `success: false` or 401/403/404 for the kiosk link: most likely the link expired (they last
     * one year) or the kiosk was turned off. The loader calls it expired once it lasts 30 minutes.
     */
    data object KioskLinkRejected : SunWattError("kioskLinkRejected")

    /** The inverter address is not a valid IP address or host name. */
    data object InvalidInverterAddress : SunWattError("invalidInverterAddress")

    /** No Modbus answer from the Huawei dongle (not at home, wrong address, or Modbus TCP off). */
    data object InverterUnreachable : SunWattError("inverterUnreachable")

    /** SolarEdge / PVOutput: the ID is not a number or the key has the wrong format. */
    data object InvalidSolarCredentials : SunWattError("invalidSolarCredentials")

    /** SolarEdge / PVOutput answered 401 or 403: wrong key or ID, or the API access is off. */
    data object SolarCredentialsRejected : SunWattError("solarCredentialsRejected")

    /** FusionSolar API account: the user name or system code is missing, or the server is not a FusionSolar one. */
    data object InvalidNorthboundAccount : SunWattError("invalidNorthboundAccount")

    /** FusionSolar API account: the login was refused (wrong user name or system code, or not an API account). */
    data object NorthboundAccountRejected : SunWattError("northboundAccountRejected")

    /**
     * The provider refused because SunWatt asked too often (FusionSolar API failCode 407). Kept for the provider's
     * fetch interval, then asked again.
     */
    data object RateLimited : SunWattError("rateLimited")

    data object NotConfigured : SunWattError("notConfigured")

    data class Http(val status: Int) : SunWattError("http $status") {
        override val code: String get() = "http"
        override val detail: String get() = status.toString()
    }

    data class BadResponse(override val detail: String) : SunWattError("badResponse: $detail") {
        override val code: String get() = "badResponse"
    }

    data object Unavailable : SunWattError("unavailable")

    data class Network(override val detail: String) : SunWattError("network: $detail") {
        override val code: String get() = "network"
    }

    companion object {
        /**
         * The case with this [code] (and [detail] for the cases that carry one), as stored by the loader. Null for an
         * unknown code (written by a newer version).
         */
        fun fromCode(code: String, detail: String? = null): SunWattError? = when (code) {
            "invalidKioskURL" -> InvalidKioskURL
            "kioskLinkRejected" -> KioskLinkRejected
            "invalidInverterAddress" -> InvalidInverterAddress
            "inverterUnreachable" -> InverterUnreachable
            "invalidSolarCredentials" -> InvalidSolarCredentials
            "solarCredentialsRejected" -> SolarCredentialsRejected
            "invalidNorthboundAccount" -> InvalidNorthboundAccount
            "northboundAccountRejected" -> NorthboundAccountRejected
            "rateLimited" -> RateLimited
            "notConfigured" -> NotConfigured
            "unavailable" -> Unavailable
            "http" -> detail?.toIntOrNull()?.let { Http(it) }
            "badResponse" -> BadResponse(detail.orEmpty())
            "network" -> Network(detail.orEmpty())
            else -> null
        }
    }
}
