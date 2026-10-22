package com.ideatic.sunwatt.core.services

import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * Host + `kk` token extracted from a FusionSolar kiosk URL.
 * Accepted inputs:
 * - Kiosk page URL: `https://<host>/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=XXXX` (kk is in the FRAGMENT)
 * - API URL: `https://<host>/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=XXXX` (kk is in the query)
 * Surrounding whitespace is ignored. The scheme must be https, with no explicit port and no user info.
 * The host is lowercased. When `kk` appears more than once, the first one wins (the query before the fragment).
 * The token is a secret: [toString] hides it.
 */
data class KioskEndpoint(val host: String, val kk: String) {
    /** SHA-256 (hex) of host and token. Identifies this link in caches without storing the token. */
    val cacheID: String
        get() = MessageDigest.getInstance("SHA-256").digest("$host\n$kk".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** `https://<host>/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=<kk>` */
    val apiURL: String
        get() = "https://$host/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=${percentEncode(kk)}"

    override fun toString(): String = "KioskEndpoint(host: $host, kk: ***)"

    companion object {
        /** Returns null when the string is not a valid kiosk URL. */
        fun parse(string: String): KioskEndpoint? {
            val uri = try {
                URI(string.trim())
            } catch (e: URISyntaxException) {
                return null
            }
            val host = uri.host
            if (uri.scheme?.lowercase() != "https" || uri.port != -1 || uri.userInfo != null || host.isNullOrEmpty()) return null

            // kk lives in the query (API URL) or after the "?" inside the fragment (kiosk page URL).
            val items = queryItems(uri.rawQuery) +
                queryItems(uri.rawFragment?.let { fragment -> fragment.indexOf('?').takeIf { it >= 0 }?.let { fragment.substring(it + 1) } })
            val kk = items.firstOrNull { it.first == "kk" }?.second
            if (kk.isNullOrEmpty()) return null
            return KioskEndpoint(host.lowercase(), kk)
        }

        /** `name=value` pairs of a raw query, percent-decoded ("+" stays a plus). */
        private fun queryItems(rawQuery: String?): List<Pair<String, String?>> {
            if (rawQuery.isNullOrEmpty()) return emptyList()
            return rawQuery.split('&').filter { it.isNotEmpty() }.map { item ->
                val eq = item.indexOf('=')
                if (eq < 0) percentDecode(item) to null else percentDecode(item.substring(0, eq)) to percentDecode(item.substring(eq + 1))
            }
        }

        private fun percentDecode(text: String): String {
            if (!text.contains('%')) return text
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val code = if (c == '%' && i + 2 < text.length) {
                    val hi = Character.digit(text[i + 1], 16)
                    val lo = Character.digit(text[i + 2], 16)
                    if (hi >= 0 && lo >= 0) hi * 16 + lo else null
                } else {
                    null
                }
                if (code != null) {
                    bytes.write(code)
                    i += 3
                } else {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return bytes.toString(Charsets.UTF_8)
        }

        /** Everything outside the unreserved set (letters, digits, `-._~`) is escaped, "+", "&" and "=" included. */
        private fun percentEncode(text: String): String {
            val out = StringBuilder()
            for (b in text.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") out.append(c) else out.append("%%%02X".format(b))
            }
            return out.toString()
        }
    }
}
