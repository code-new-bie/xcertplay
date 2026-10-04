package com.shilapi.xcertplay.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import java.io.Closeable

/**
 * Logs radio activity that takes the Wi-Fi radio off the CarPlay channel: finished Wi-Fi scans and
 * Wi-Fi P2P peer discovery started or stopped by any app. Matching these times against
 * "Media stall" lines shows whether stalls come from off-channel scanning.
 */
internal class WirelessActivityLog(
    context: Context,
    private val diagnostic: (String) -> Unit,
) : Closeable {
    private val app = context.applicationContext
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                    val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                    report("Radio activity: Wi-Fi scan finished updated=$updated")
                }
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val started = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, 0) ==
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    report("Radio activity: Wi-Fi P2P peer discovery ${if (started) "started" else "stopped"}")
                }
            }
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        registered = runCatching { app.registerReceiver(receiver, filter) }
            .onFailure { report("Radio activity log unavailable: ${it.javaClass.simpleName}") }
            .isSuccess
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        registered = false
        runCatching { app.unregisterReceiver(receiver) }
    }

    override fun close() = stop()

    private fun report(message: String) {
        runCatching { diagnostic(message) }
    }
}
