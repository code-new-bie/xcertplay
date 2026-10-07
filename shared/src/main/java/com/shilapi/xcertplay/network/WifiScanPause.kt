package com.shilapi.xcertplay.network

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
 * [RESUME_DELAY_MS]. The paused state is saved: BYD's power-off kills the app before that resume,
 * so the next start resumes the search unless CarPlay connects within [STARTUP_GRACE_MS]. Without
 * adb approval nothing happens.
 */
class WifiScanPause internal constructor(
    context: Context,
    private val newShell: () -> WifiScanShell = { AdbWifiScanShell(context.applicationContext) },
    private val resumeDelayMillis: Long = RESUME_DELAY_MS,
    private val startupGraceMillis: Long = STARTUP_GRACE_MS,
) {
    private val app = context.applicationContext
    private val prefs = WifiScanPauseSettings.prefs(app)
    private val worker = ScheduledThreadPoolExecutor(1) {
        Thread(it, "xcertplay-wifi-scan").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var pendingResume: ScheduledFuture<*>? = null // worker thread
    private var sessionRequested = false
    private var appsAttempted = false
    private var appsApplied = false
    private val restrictions = scanRestrictions(prefs, ::report)

    /** Where progress is written; the controller of the current connection sets it. */
    @Volatile
    var diagnostic: (String) -> Unit = {}

    init {
        // Through the main looper: callers set [diagnostic] right after shared() on the main thread.
        Handler(Looper.getMainLooper()).post { worker.execute(::takeOverLeftover) }
    }

    /**
     * A search a killed process left paused stays paused a while longer: resuming at once would
     * start a scan just as the CarPlay session about to connect needs the radio.
     */
    private fun takeOverLeftover() {
        if (sessionRequested || pendingResume != null || !needsRestore()) return
        pendingResume = worker.schedule({
            pendingResume = null
            restore()
        }, startupGraceMillis, TimeUnit.MILLISECONDS)
        report("Wi-Fi network search was left paused by an earlier run; resuming in " +
            "${startupGraceMillis / 1000}s unless CarPlay connects")
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
            appsAttempted = false
            appsApplied = false
            if (!needsRestore()) return@execute
            // A longer wait already pending (after startup) is kept.
            if ((pendingResume?.getDelay(TimeUnit.MILLISECONDS) ?: 0L) >= resumeDelayMillis) return@execute
            pendingResume?.cancel(false)
            pendingResume = worker.schedule({
                pendingResume = null
                restore()
            }, resumeDelayMillis, TimeUnit.MILLISECONDS)
            report("Wi-Fi network search resumes in ${RESUME_DELAY_MS / 1000}s unless CarPlay reconnects")
        }
    }

    /**
     * The app is exiting, or the head unit powered on without CarPlay: resume the search now
     * instead of after the delay, waiting up to [timeoutMillis]. Call from a background thread.
     */
    fun resumeNowBlocking(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            worker.submit<Boolean> {
                pendingResume?.cancel(false)
                pendingResume = null
                sessionRequested = false
                appsAttempted = false
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
        if (!appsApplied) {
            // Gaode is force-stopped at most once per connection; Baidu location is never stopped.
            val forceStop = !appsAttempted
            appsAttempted = true
            appsApplied = true
            withShell { shell -> restrictions.forEach { it.apply(shell, forceStop) } }
        }
    }

    private fun needsRestore() =
        isPaused() || prefs.getBoolean(KEY_RECOVERY, false) || restrictions.any { it.pending }

    private fun restore() {
        appsApplied = false
        if (isPaused() || prefs.getBoolean(KEY_RECOVERY, false)) setSearch(enabled = true)
        val pending = restrictions.filter { it.pending }
        if (pending.isNotEmpty()) withShell { shell -> pending.forEach { it.restore(shell) } }
    }

    private fun withShell(action: ((String) -> String?) -> Unit) {
        runCatching {
            newShell().use { adb ->
                if (adb.connect()) action(adb::shell)
                else report("App scan restriction: ADB unavailable; pending recovery retained")
            }
        }.onFailure {
            report("App scan restriction: ADB failed ${it.javaClass.simpleName}; pending recovery retained")
        }
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
        /** Long enough for wireless CarPlay to connect after the head unit powers on. */
        const val STARTUP_GRACE_MS = 60_000L
        private const val KEY_PAUSED = "paused"
        private const val KEY_RECOVERY = "search_recovery_pending"

        @Volatile
        private var instance: WifiScanPause? = null

        fun shared(context: Context): WifiScanPause =
            instance ?: synchronized(this) { instance ?: WifiScanPause(context).also { instance = it } }

        /** Whether an earlier run left the search paused or an app restricted; reads only the saved state. */
        fun hasLeftover(context: Context): Boolean {
            val prefs = WifiScanPauseSettings.prefs(context)
            return prefs.getBoolean(KEY_PAUSED, false) || prefs.getBoolean(KEY_RECOVERY, false) ||
                scanRestrictions(prefs) {}.any { it.pending }
        }

        private fun scanRestrictions(prefs: SharedPreferences, report: (String) -> Unit) =
            (android.os.Process.myUid() / 100_000).let { userId ->
                listOf(
                    AppScanRestriction.gaode(prefs, userId, report),
                    AppScanRestriction.baiduLocation(prefs, userId, report),
                )
            }
    }
}
