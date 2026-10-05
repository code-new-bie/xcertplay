package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.hud.BydSdkStream
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.io.Closeable

internal interface WifiScanShell : Closeable {
    fun connect(): Boolean
    fun shell(command: String): String?
}

private class AdbWifiScanShell(context: Context) : WifiScanShell {
    private val adb = LocalAdb(AdbKeys.load(context))
    override fun connect() = adb.connect(mayAsk = false) == LocalAdb.Access.READY
    override fun shell(command: String) = adb.shell(command)
    override fun close() = adb.close()
}

/** Whether wireless CarPlay pauses the head unit's automatic Wi-Fi network search. */
object WifiScanPauseSettings {
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        WifiScanPause.shared(context).settingChanged(enabled)
    }

    internal fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("xcertplay_wifi_scan_pause", Context.MODE_PRIVATE)
}

/**
 * Pauses the head unit's automatic Wi-Fi network search while wireless CarPlay runs (after DiPlay
 * PR #225). BYD scans every 10 s whenever its Wi-Fi client is not joined, and each scan takes the
 * shared radio off the CarPlay channel, so audio and video stall together. Through the approved
 * local adb shell, IWifiManager.enableWifiConnectivityManager(false) stops those scans.
 *
 * One instance serves the process: a reconnect keeps the search paused (resuming would start a scan
 * at once), and [resumeLater] resumes it only if no session pauses it again within
 * [RESUME_DELAY_MS]. The paused state is saved, so a run killed while paused is repaired on the
 * next start. Without adb approval nothing happens.
 */
class WifiScanPause internal constructor(
    context: Context,
    private val newShell: () -> WifiScanShell = { AdbWifiScanShell(context.applicationContext) },
    private val resumeDelayMillis: Long = RESUME_DELAY_MS,
) {
    private val app = context.applicationContext
    private val prefs = WifiScanPauseSettings.prefs(app)
    private val worker = ScheduledThreadPoolExecutor(1) {
        Thread(it, "xcertplay-wifi-scan").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var pendingResume: ScheduledFuture<*>? = null // worker thread
    private var sessionRequested = false
    private var gaodeAttempted = false
    private var gaodeApplied = false
    private val gaode = GaodeScanRestriction(prefs, android.os.Process.myUid() / 100_000, ::report)

    /** Where progress is written; the controller of the current connection sets it. */
    @Volatile
    var diagnostic: (String) -> Unit = {}

    init {
        worker.execute {
            if (needsRestore()) {
                report("Wi-Fi network search was left paused by an earlier run; resuming")
                restore()
            }
        }
    }

    fun pause() {
        worker.execute {
            pendingResume?.cancel(false)
            pendingResume = null
            sessionRequested = true
            if (WifiScanPauseSettings.enabled(app)) restrict()
        }
    }

    /** Whether the head unit's Wi-Fi network search is currently paused by this app. */
    fun isPaused(): Boolean = prefs.getBoolean(KEY_PAUSED, false)

    fun resumeLater() {
        worker.execute {
            sessionRequested = false
            gaodeAttempted = false
            gaodeApplied = false
            if (!needsRestore()) return@execute
            pendingResume?.cancel(false)
            pendingResume = worker.schedule({
                pendingResume = null
                restore()
            }, resumeDelayMillis, TimeUnit.MILLISECONDS)
            report("Wi-Fi network search resumes in ${RESUME_DELAY_MS / 1000}s unless CarPlay reconnects")
        }
    }

    /**
     * The app is exiting: resume the search now instead of after [RESUME_DELAY_MS], waiting up to
     * [timeoutMillis]. Call from a background thread.
     */
    fun resumeNowBlocking(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            worker.submit<Boolean> {
                pendingResume?.cancel(false)
                pendingResume = null
                sessionRequested = false
                gaodeAttempted = false
                restore()
                !needsRestore()
            }.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
    }

    internal fun settingChanged(enabled: Boolean) {
        worker.execute {
            pendingResume?.cancel(false)
            pendingResume = null
            if (!enabled) restore() else if (sessionRequested) restrict()
        }
    }

    private fun restrict() {
        // Reassert after reconnect: a previous resume may have succeeded but lost its reply.
        setSearch(enabled = false)
        if (!gaodeApplied) {
            val forceStop = !gaodeAttempted
            gaodeAttempted = true
            gaodeApplied = true
            withShell { gaode.apply(it, forceStop) }
        }
    }

    private fun needsRestore() = isPaused() || prefs.getBoolean(KEY_RECOVERY, false) || gaode.pending

    private fun restore() {
        gaodeApplied = false
        if (isPaused() || prefs.getBoolean(KEY_RECOVERY, false)) setSearch(enabled = true)
        if (gaode.pending) withShell { gaode.restore(it) }
    }

    private fun withShell(action: ((String) -> String?) -> Unit) {
        runCatching {
            newShell().use { adb ->
                if (adb.connect()) action(adb::shell)
                else report("Gaode: ADB unavailable; pending recovery retained")
            }
        }.onFailure { report("Gaode: ADB failed ${it.javaClass.simpleName}; pending recovery retained") }
    }

    // Worker thread.
    private fun setSearch(enabled: Boolean) {
        // A lost acknowledgement must still be repaired on exit or next launch.
        if (!enabled && !prefs.edit().putBoolean(KEY_RECOVERY, true).commit()) {
            report("Wi-Fi network search unchanged: cannot persist recovery")
            return
        }
        val output = runCatching {
            newShell().use { adb ->
                if (!adb.connect()) null
                else adb.shell(BydSdkStream.helper(app, "wifiscan", if (enabled) "on" else "off"))
            }
        }.getOrNull()
        val ok = output?.contains("XCERTPLAY wifiscan enabled=$enabled ok") == true
        if (ok) prefs.edit().putBoolean(KEY_PAUSED, !enabled)
            .putBoolean(KEY_RECOVERY, !enabled).commit()
        val verb = if (enabled) "resumed" else "paused"
        report(
            when {
                ok -> "Wi-Fi network search $verb"
                output == null -> "Wi-Fi network search not $verb: adb shell unavailable (authorize ADB first)"
                else -> "Wi-Fi network search not $verb: ${output.trim().take(160)}"
            },
        )
    }

    private fun report(message: String) {
        runCatching { diagnostic(message) }
    }

    companion object {
        const val RESUME_DELAY_MS = 15_000L
        private const val KEY_PAUSED = "paused"
        private const val KEY_RECOVERY = "search_recovery_pending"

        @Volatile
        private var instance: WifiScanPause? = null

        fun shared(context: Context): WifiScanPause =
            instance ?: synchronized(this) { instance ?: WifiScanPause(context).also { instance = it } }
    }
}
