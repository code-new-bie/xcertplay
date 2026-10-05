package com.shilapi.xcertplay.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.SupplicantState
import android.net.wifi.p2p.WifiP2pManager
import android.os.SystemClock
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
    private var startedMs = 0L
    private var lastScanMs: Long? = null
    private var scanCount = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                    val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                    val now = SystemClock.elapsedRealtime()
                    val interval = lastScanMs?.let { now - it }
                    lastScanMs = now
                    scanCount++
                    @Suppress("DEPRECATION")
                    val frequency = runCatching {
                        app.getSystemService(WifiManager::class.java)?.connectionInfo?.takeIf {
                            it.supplicantState == SupplicantState.COMPLETED
                        }?.frequency
                    }.getOrNull()
                    report("Radio activity: Wi-Fi scan finished updated=$updated elapsedMs=$now " +
                        "sinceSessionMs=${now - startedMs} count=$scanCount intervalMs=${interval ?: "first"} " +
                        "searchPaused=${WifiScanPause.shared(app).isPaused()} " +
                        "stationFrequencyMHz=${frequency ?: "unknown"}")
                }
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val started = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, 0) ==
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    report("Radio activity: Wi-Fi P2P peer discovery ${if (started) "started" else "stopped"} " +
                        "elapsedMs=${SystemClock.elapsedRealtime()}")
                }
            }
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        startedMs = SystemClock.elapsedRealtime()
        lastScanMs = null
        scanCount = 0
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
