package com.ideatic.sunwatt.core.models

import java.net.URI

/** Links the widgets open the app with (`sunwatt://…`). */
enum class SunWattLink(val rawValue: String) {
    /** A widget tap: open the app and load fresh data. */
    REFRESH("refresh");

    companion object {
        const val SCHEME = "sunwatt"

        /** The link for [REFRESH]. */
        const val REFRESH_URL = "$SCHEME://refresh"

        /** Parses a link the app was opened with. Other schemes and unknown hosts are null. */
        fun parse(uri: String): SunWattLink? {
            val parsed = try {
                URI(uri)
            } catch (e: java.net.URISyntaxException) {
                return null
            }
            if (parsed.scheme?.lowercase() != SCHEME) return null
            val host = parsed.host?.lowercase() ?: return null
            return entries.firstOrNull { it.rawValue == host }
        }
    }
}
