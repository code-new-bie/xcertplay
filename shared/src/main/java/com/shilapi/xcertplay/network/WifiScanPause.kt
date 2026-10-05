package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.hud.BydSdkStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Whether wireless CarPlay pauses the head unit's automatic Wi-Fi network search. */
object WifiScanPauseSettings {
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

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
class WifiScanPause private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = WifiScanPauseSettings.prefs(app)
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "xcertplay-wifi-scan").apply { isDaemon = true } }
    private var pendingResume: ScheduledFuture<*>? = null // worker thread

    /** Where progress is written; the controller of the current connection sets it. */
    @Volatile
    var diagnostic: (String) -> Unit = {}

    init {
        worker.execute {
            if (prefs.getBoolean(KEY_PAUSED, false)) {
                report("Wi-Fi network search was left paused by an earlier run; resuming")
                setSearch(enabled = true)
            }
        }
    }

    fun pause() {
        worker.execute {
            pendingResume?.cancel(false)
            pendingResume = null
            if (prefs.getBoolean(KEY_PAUSED, false)) return@execute
            setSearch(enabled = false)
        }
    }

    /** Whether the head unit's Wi-Fi network search is currently paused by this app. */
    fun isPaused(): Boolean = prefs.getBoolean(KEY_PAUSED, false)

    fun resumeLater() {
        worker.execute {
            if (!prefs.getBoolean(KEY_PAUSED, false)) return@execute
            pendingResume?.cancel(false)
            pendingResume = worker.schedule({
                pendingResume = null
                setSearch(enabled = true)
            }, RESUME_DELAY_MS, TimeUnit.MILLISECONDS)
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
                if (prefs.getBoolean(KEY_PAUSED, false)) setSearch(enabled = true)
                !prefs.getBoolean(KEY_PAUSED, false)
            }.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
    }

    // Worker thread.
    private fun setSearch(enabled: Boolean) {
        val output = runCatching {
            LocalAdb(AdbKeys.load(app)).use { adb ->
                if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) null
                else adb.shell(BydSdkStream.helper(app, "wifiscan", if (enabled) "on" else "off"))
            }
        }.getOrNull()
        val ok = output?.contains("XCERTPLAY wifiscan enabled=$enabled ok") == true
        if (ok) prefs.edit().putBoolean(KEY_PAUSED, !enabled).commit()
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

        @Volatile
        private var instance: WifiScanPause? = null

        fun shared(context: Context): WifiScanPause =
            instance ?: synchronized(this) { instance ?: WifiScanPause(context).also { instance = it } }
    }
}
