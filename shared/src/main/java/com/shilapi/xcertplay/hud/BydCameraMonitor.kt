package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock

/** One sample of the reversing/panorama camera, as the shell helper prints it. */
internal data class BydCameraReading(val workState: Int?, val displayMode: Int?, val gear: Int?) {
    /** BYD shows a camera picture: the panorama unit is working, or the car is in reverse. */
    val shown: Boolean get() = workState == PANORAMA_WORK_ON || gear == GEAR_REVERSE

    fun describe(): String = "panorama=${workState ?: "-"} displayMode=${displayMode ?: "-"} gear=${gear ?: "-"}"

    companion object {
        const val LINE_PREFIX = "XCERTPLAY camera"
        // BYDAutoPanoramaDevice.PANORAMA_WORK_ON and BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_R.
        private const val PANORAMA_WORK_ON = 1
        private const val GEAR_REVERSE = 2

        fun parse(line: String): BydCameraReading? {
            if (!line.startsWith("$LINE_PREFIX ")) return null
            val fields = line.removePrefix("$LINE_PREFIX ").trim().split(' ')
            if (fields.size != 3) return null
            return BydCameraReading(fields[0].toIntOrNull(), fields[1].toIntOrNull(), fields[2].toIntOrNull())
        }
    }
}

/**
 * Whether a BYD camera picture is up. It still counts as shown for [holdMs] after the camera goes
 * away, because the window that shrank for it grows back a moment later.
 */
internal class BydCameraState(private val holdMs: Long = HOLD_MS) {
    private var reading: BydCameraReading? = null
    private var lastShownMs: Long? = null

    /** Records [reading]; returns whether it differs from the previous one. */
    @Synchronized
    fun update(reading: BydCameraReading, nowMs: Long): Boolean {
        if (reading.shown) lastShownMs = nowMs
        val changed = reading != this.reading
        this.reading = reading
        return changed
    }

    /** The helper stopped: nothing is known until it reports again. */
    @Synchronized
    fun clear() {
        reading = null
        lastShownMs = null
    }

    @Synchronized
    fun hasReading(): Boolean = reading != null

    @Synchronized
    fun shown(nowMs: Long): Boolean {
        if (reading?.shown == true) return true
        val last = lastShownMs ?: return false
        return nowMs - last <= holdMs
    }

    companion object {
        const val HOLD_MS = 3_000L
    }
}

/**
 * Reads the camera state through the authorized adb shell for as long as the app runs on a BYD
 * head unit, so it is known before CarPlay connects and a window that shrinks for the reversing or
 * panorama picture keeps the session.
 */
object BydCameraMonitor {
    private val state = BydCameraState()
    private var stream: BydSdkStream? = null

    /** Where readings are logged; the current host screen sets it, so a recreated screen takes over. */
    @Volatile
    var log: (String) -> Unit = {}

    /** Called on the reader thread with `shown` whenever the reading changes, the first included. */
    @Volatile
    var onChanged: (Boolean) -> Unit = {}

    @Synchronized
    fun start(context: Context) {
        if (stream != null) return
        state.clear()
        stream = BydSdkStream(context.applicationContext, "panorama", { line ->
            val reading = BydCameraReading.parse(line) ?: return@BydSdkStream
            if (state.update(reading, SystemClock.elapsedRealtime())) {
                runCatching { log("BYD camera: shown=${reading.shown} ${reading.describe()}") }
                runCatching { onChanged(reading.shown) }
            }
        }, { state.clear() }, { message -> runCatching { log(message) } }).also { it.start() }
    }

    fun stop() {
        val previous = synchronized(this) { stream.also { stream = null } }
        previous?.close()
        state.clear()
    }

    /** Whether the camera state is currently known (adb authorized and the helper reporting). */
    fun signalAvailable(): Boolean = state.hasReading()

    fun cameraShown(): Boolean = state.shown(SystemClock.elapsedRealtime())
}
