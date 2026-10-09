package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress

/** An iPhone Personal Hotspot hands out 172.20.10.2-14 and is itself 172.20.10.1: a /28. */
internal object IphoneHotspotAddress {
    fun matches(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        return (bytes[0].toInt() and 0xff) == 172 && (bytes[1].toInt() and 0xff) == 20 &&
            (bytes[2].toInt() and 0xff) == 10 && (bytes[3].toInt() and 0xff) < 16
    }

    fun matches(properties: LinkProperties): Boolean = properties.linkAddresses.any { matches(it.address) }
}

/**
 * Watches whether the head unit's own Wi-Fi is joined to an iPhone's Personal Hotspot. The iPhone
 * then serves its hotspot and the CarPlay link at once, and the head unit's radio may alternate
 * channels, so wireless CarPlay stutters. The hotspot's address range identifies it without
 * location permission; the network name is added when Android reveals it.
 *
 * [onChange] runs on a ConnectivityManager thread with the hotspot's name ("" when unknown), or
 * null once the head unit has left it.
 */
class IphoneHotspotMonitor(context: Context, private val onChange: (String?) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val onHotspot = mutableSetOf<Network>()
    private var reported: String? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            update(network, IphoneHotspotAddress.matches(linkProperties))
        }

        override fun onLost(network: Network) {
            update(network, false)
        }
    }

    init {
        registered = runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity?.registerNetworkCallback(request, callback) ?: error("no ConnectivityManager")
        }.isSuccess
    }

    @Synchronized
    private fun update(network: Network, matches: Boolean) {
        val changed = if (matches) onHotspot.add(network) else onHotspot.remove(network)
        if (!changed) return
        val now = if (onHotspot.isEmpty()) null else ssid()
        if (now == reported) return
        reported = now
        runCatching { onChange(now) }
    }

    private fun ssid(): String = runCatching {
        @Suppress("DEPRECATION")
        app.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
    }.getOrNull()?.removeSurrounding("\"")?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID } ?: ""

    override fun close() {
        if (!registered) return
        registered = false
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }
}
