package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/** Follows the head unit's actual appearance, including switches that do not update Android uiMode. */
internal class CarPlayAppearanceMonitor(
    context: Context,
    private val mainHandler: Handler,
    initialUiMode: Int,
    private val diagnostic: (String) -> Unit = {},
    private val suppliedWorker: Handler? = null,
    /** Live system UI mode. Defaults to the process-wide configuration, never the Activity's. */
    private val uiModeReader: () -> Int = {
        context.applicationContext.resources.configuration.uiMode
    },
    private val onDarkModeChanged: (Boolean) -> Unit,
) {
    // Read the live application configuration even if an Activity uses a configuration override.
    private val resolver = context.applicationContext.contentResolver
    private val workerThread = HandlerThread("carplay-appearance")
    private val uri = Uri.parse("content://carsettings/global")
    @Volatile private var fallbackUiMode = initialUiMode
    @Volatile private var running = false
    private lateinit var worker: Handler
    private var observer: ContentObserver? = null
    private var lastMode: Boolean? = null
    private var providerFailureLogged = false
    private var lastDiagnostic: String? = null
    private val check = object : Runnable {
        override fun run() {
            if (!running) return
            val uiMode = currentUiMode()
            var screenMode: String? = null
            var dayOrNight: String? = null
            val mode = try {
                screenMode = readValue("sys_screen_mode")
                dayOrNight = if (screenMode == "0") readValue("sys_day_or_night") else null
                providerFailureLogged = false
                // A unit that publishes no mode key at all is treated as unavailable, which keeps
                // the Android uiMode fallback and the slower retry.
                if (screenMode == null) null else resolveCarPlayDarkMode(uiMode, screenMode, dayOrNight)
            } catch (error: RuntimeException) {
                if (!providerFailureLogged) {
                    Log.w(TAG, "Vehicle appearance unavailable; using Android uiMode", error)
                    report("Vehicle appearance query failed: ${error.javaClass.simpleName}")
                    providerFailureLogged = true
                }
                null
            }
            val resolved = mode ?: isDarkMode(uiMode)
            val vehicleMode = vehicleDarkMode(screenMode, dayOrNight)
            val definedUiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
                Configuration.UI_MODE_NIGHT_UNDEFINED
            val source = when {
                vehicleMode != null -> "vehicle"
                definedUiMode -> "uiMode"
                else -> "undefined"
            }
            val status = "CarPlay appearance source=$source " +
                "uiNight=${uiMode and Configuration.UI_MODE_NIGHT_MASK} " +
                "screenMode=${screenMode?.take(16) ?: "missing"} " +
                "dayOrNight=${dayOrNight?.take(16) ?: "missing"} " +
                "vehicle=${vehicleMode?.let { if (it) "dark" else "light" } ?: "unknown"} " +
                "resolved=${if (resolved) "dark" else "light"}"
            if (status != lastDiagnostic) {
                lastDiagnostic = status
                report(status)
            }
            if (resolved != lastMode) {
                lastMode = resolved
                mainHandler.post { if (running) onDarkModeChanged(resolved) }
            }
            worker.postDelayed(this, if (mode == null) RETRY_INTERVAL_MS else CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        worker = suppliedWorker ?: run {
            workerThread.start()
            Handler(workerThread.looper)
        }
        observer = object : ContentObserver(worker) {
            override fun onChange(selfChange: Boolean) = refresh()

            override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
        }
        try {
            resolver.registerContentObserver(uri, true, observer!!)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Vehicle appearance observer unavailable; polling remains active", error)
            report("Vehicle appearance observer failed: ${error.javaClass.simpleName}; polling active")
            observer = null
        }
        refresh()
    }

    /** Keeps [refresh] working when the live reader is unavailable; the reader stays authoritative. */
    fun updateUiMode(nextUiMode: Int) {
        fallbackUiMode = nextUiMode
        refresh()
    }

    fun stop() {
        if (!running) return
        running = false
        observer?.let { runCatching { resolver.unregisterContentObserver(it) } }
        observer = null
        worker.removeCallbacks(check)
        if (suppliedWorker == null) workerThread.quitSafely()
    }

    private fun currentUiMode(): Int =
        runCatching { uiModeReader() }.getOrDefault(fallbackUiMode)

    private fun refresh() {
        if (!running) return
        worker.removeCallbacks(check)
        worker.post(check)
    }

    private fun readValue(key: String): String? = resolver.query(
        uri,
        arrayOf("value"),
        "key=?",
        arrayOf(key),
        null,
    )?.use { cursor ->
        if (cursor.count == 1 && cursor.moveToFirst()) cursor.getString(0)?.trim() else null
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        mainHandler.post { if (running) diagnostic(message) }
    }

    private companion object {
        const val TAG = "CarPlayAppearance"
        const val CHECK_INTERVAL_MS = 1_000L
        const val RETRY_INTERVAL_MS = 5_000L

        /** The vehicle's own day/night answer, or null when the keys say nothing usable. */
        fun vehicleDarkMode(screenMode: String?, dayOrNight: String?): Boolean? = when (screenMode) {
            "1" -> false
            "2" -> true
            "0" -> when (dayOrNight) {
                "0" -> true
                "1" -> false
                else -> null
            }
            else -> null
        }
    }
}
