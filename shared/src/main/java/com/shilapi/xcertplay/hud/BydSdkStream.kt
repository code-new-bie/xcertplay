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
            while (running.get()) {
                var adb: LocalAdb? = null
                var retryMillis = 30_000L
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
                        retryMillis = 0L // normal bounded helper expiry: restart without a 30-second gap
                    } else if (running.get()) {
                        throw IllegalStateException("SDK helper ended early")
                    }
                } catch (error: Exception) {
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
        fun command(context: Context, mode: String, once: Boolean = false): String {
            require(mode == "speed" || mode == "battery")
            val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
            return "CLASSPATH='$apk' app_process /system/bin ${BydVehicleDataTool::class.java.name} $mode" +
                if (once) " once" else ""
        }
    }
}
