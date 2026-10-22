package com.ideatic.sunwatt.core.services

import com.ideatic.sunwatt.core.models.NORTHBOUND_DEFAULT_HOST
import java.security.MessageDigest

/**
 * User name + system code of a FusionSolar API account and the server it belongs to. The system code is a secret:
 * [toString] hides it. Build one with [create].
 */
class NorthboundCredentials private constructor(
    val host: String,
    val userName: String,
    val systemCode: String,
) {
    /** Base URL of every interface, with the trailing slash. */
    val baseUrl: String get() = "https://$host/thirdData/"

    /**
     * One-way key of the account (SHA-256 hex of host, user name and system code). Session stores key their entries
     * by it; a persistent store may prefix it (`northbound.<key>`, as the Apple keychain item). Never the code itself.
     */
    val accountKey: String by lazy {
        MessageDigest.getInstance("SHA-256").digest("$host\n$userName\n$systemCode".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    override fun toString(): String = "NorthboundCredentials(host=$host, userName=$userName, systemCode=***)"

    override fun equals(other: Any?): Boolean = other is NorthboundCredentials &&
        host == other.host && userName == other.userName && systemCode == other.systemCode

    override fun hashCode(): Int = listOf(host, userName, systemCode).hashCode()

    companion object {
        private val hostCharacters = Regex("[a-z0-9.-]*")

        /**
         * Null when the user name or the system code is empty, or the server is not a fusionsolar.huawei.com host: the
         * credentials are only ever sent to Huawei. An empty server means [NORTHBOUND_DEFAULT_HOST]; "https://" and a
         * path are dropped.
         */
        fun create(host: String, userName: String, systemCode: String): NorthboundCredentials? {
            var server = host.trim().lowercase(java.util.Locale.ROOT)
            for (prefix in listOf("https://", "http://")) {
                if (server.startsWith(prefix)) server = server.removePrefix(prefix)
            }
            server = server.substringBefore('/')
            if (server.isEmpty()) server = NORTHBOUND_DEFAULT_HOST
            val trusted = server == "fusionsolar.huawei.com" || server.endsWith(".fusionsolar.huawei.com")
            if (!trusted || !hostCharacters.matches(server) || server.contains("..")) return null
            val user = userName.trim()
            val code = systemCode.trim()
            if (user.length !in 1..128 || code.length !in 1..128) return null
            return NorthboundCredentials(server, user, code)
        }
    }
}
