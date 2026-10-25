package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one read-only helper and adb connection; no approval dialog is requested in background use. */
internal class BydSdkStream(
    private val context: Context,
    private val mode: String,
    private val onLine: (String) -> Unit,
    private val onUnavailable: () -> Unit = {},
    private val onProgress: (String) -> Unit = {},
    private val newClient: () -> LocalAdb = { LocalAdb(AdbKeys.load(context)) },
) : Closeable {
    private val running = AtomicBoolean()
    @Volatile private var client: LocalAdb? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "xcertplay-byd-$mode").apply { isDaemon = true } }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker.execute {
            var unavailableLogged = false
            var failures = 0
            while (running.get()) {
                var adb: LocalAdb? = null
                var retryMillis = 0L
                try {
                    if (!running.get()) break
                    val connection = newClient()
                    adb = connection
                    client = connection
                    if (!running.get()) break
                    val access = connection.connect(mayAsk = false)
                    if (access != LocalAdb.Access.READY) {
                        throw IllegalStateException("ADB access=$access")
                    }
                    if (!running.get()) break
                    onProgress("BYD $mode SDK reader connected")
                    unavailableLogged = false
                    val helperStartedAt = SystemClock.elapsedRealtime()
                    connection.stream(command(context, mode)) { line ->
                        if (running.get()) {
                            if (line.startsWith("XCERTPLAY error")) throw IllegalStateException(line)
                            onLine(line)
                        }
                    }
                    if (SystemClock.elapsedRealtime() - helperStartedAt >= 4 * 60_000L) {
                        failures = 0 // normal bounded helper expiry: restart at once
                    } else if (running.get()) {
                        throw IllegalStateException("SDK helper ended early")
                    }
                } catch (error: Exception) {
                    retryMillis = retryDelayMillis(++failures)
                    if (running.get() && !unavailableLogged) {
                        Log.w("xcertplay-BYD-Data", "$mode unavailable", error)
                        onProgress("BYD $mode unavailable: ${error.message?.take(160)}")
                        unavailableLogged = true
                    }
                } finally {
                    adb?.close()
                    client = null
                    onUnavailable()
                }
                if (running.get()) try { Thread.sleep(retryMillis) } catch (_: InterruptedException) { break }
            }
        }
    }

    override fun close() {
        running.set(false)
        val connection = client
        worker.shutdownNow()
        // STOP_LOCATION_INFORMATION can run on iAP2. A socket still authenticating holds connect's
        // lock, so let a short-lived teardown thread wait for it instead of blocking the phone loop.
        if (connection != null) kotlin.concurrent.thread(name = "xcertplay-byd-close", isDaemon = true) { connection.close() }
        onUnavailable()
    }

    companion object {
        private const val EARLY_RETRIES = 2
        private const val EARLY_RETRY_MS = 5_000L
        private const val RETRY_MS = 30_000L

        /**
         * The wait after the [failures]th failure in a row. The first ones come sooner: the helpers
         * all start with the session, and on 2026-10-23 the panorama one timed out twice and then
         * waited 30 s, leaving video in car 73 s without a gear.
         */
        internal fun retryDelayMillis(failures: Int): Long = if (failures <= EARLY_RETRIES) EARLY_RETRY_MS else RETRY_MS

        fun command(context: Context, mode: String, once: Boolean = false): String {
            require(mode == "speed" || mode == "battery" || mode == "panorama")
            return helper(context, mode) + if (once) " once" else ""
        }

        /** A one-off helper run: [args] follow the mode, each a single shell-safe word. */
        fun helper(context: Context, vararg args: String): String {
            require(args.isNotEmpty() && args.all { arg -> arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "+/=-_" } })
            val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
            return "CLASSPATH='$apk' app_process /system/bin ${BydVehicleDataTool::class.java.name} ${args.joinToString(" ")}"
        }
    }
}
