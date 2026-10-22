package com.ideatic.sunwatt.ui.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ideatic.sunwatt.core.inverter.Ipv4Network
import com.ideatic.sunwatt.core.inverter.LocalNetworks
import java.net.Inet4Address

/** The device's Wi-Fi and Ethernet IPv4 networks (not cellular, VPN or loopback), for "Find inverter". */
class AndroidLocalNetworks(context: Context) : LocalNetworks {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun ipv4Networks(): List<Ipv4Network> {
        val manager = connectivity ?: return emptyList()
        return try {
            manager.allNetworks.flatMap { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@flatMap emptyList()
                val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!local || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@flatMap emptyList()
                manager.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
                    val address = link.address as? Inet4Address ?: return@mapNotNull null
                    if (address.isLoopbackAddress) return@mapNotNull null
                    val bits = link.prefixLength.coerceIn(0, 32)
                    val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
                    val value = address.address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
                    Ipv4Network(value, mask)
                }
            }.distinct()
        } catch (e: SecurityException) {
            // No ACCESS_NETWORK_STATE: nothing to scan.
            emptyList()
        }
    }
}
