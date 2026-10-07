package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock

/** One sample of the reversing/panorama camera and the vehicle power level, as the shell helper prints it. */
internal data class BydCameraReading(
    val workState: Int?,
    val displayMode: Int?,
    val gear: Int?,
    val powerLevel: Int? = null,
) {
    /** BYD shows a camera picture: the panorama unit is working, or the car is in reverse. */
    val shown: Boolean get() = workState == PANORAMA_WORK_ON || gear == GEAR_REVERSE

    fun describe(): String = "panorama=${workState ?: "-"} displayMode=${displayMode ?: "-"} gear=${gear ?: "-"} " +
        "power=${powerLevel ?: "-"}"

    companion object {
        const val LINE_PREFIX = "XCERTPLAY camera"
        // BYDAutoPanoramaDevice.PANORAMA_WORK_ON and BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_R.
        private const val PANORAMA_WORK_ON = 1
        private const val GEAR_REVERSE = 2

        fun parse(line: String): BydCameraReading? {
            if (!line.startsWith("$LINE_PREFIX ")) return null
            val fields = line.removePrefix("$LINE_PREFIX ").trim().split(' ')
            if (fields.size !in 3..4) return null
            return BydCameraReading(
                fields[0].toIntOrNull(), fields[1].toIntOrNull(), fields[2].toIntOrNull(), fields.getOrNull(3)?.toIntOrNull(),
            )
        }
    }
}

/**
 * Tells when the car was switched off: the power level drops to OFF from a level the car was on
 * at, and is still OFF [confirmMs] later, outside a driving gear. A head unit started while the car
 * is already off never fires, and it fires once per switch-off.
 */
internal class BydPowerOffDetector(private val confirmMs: Long = CONFIRM_MS) {
    private var wasOn = false
    private var offSinceMs: Long? = null
    private var fired = false

    /** Feeds one reading; returns true once the switch-off is confirmed. */
    @Synchronized
    fun update(reading: BydCameraReading, nowMs: Long): Boolean {
        val level = reading.powerLevel?.takeIf { it in POWER_LEVEL_OFF..POWER_LEVEL_FAKE_OK } ?: return false
        if (level != POWER_LEVEL_OFF) {
            wasOn = true
            offSinceMs = null
            fired = false
            return false
        }
        if (!wasOn || fired) return false
        val since = offSinceMs ?: nowMs.also { offSinceMs = it }
        if (nowMs - since < confirmMs || reading.gear in DRIVING_GEARS) return false
        fired = true
        return true
    }

    companion object {
        const val CONFIRM_MS = 1_000L
        // BYDAutoBodyworkDevice.BODYWORK_POWER_LEVEL_OFF .. BODYWORK_POWER_LEVEL_FAKE_OK (255 is invalid).
        private const val POWER_LEVEL_OFF = 0
        private const val POWER_LEVEL_FAKE_OK = 4
        // BYDAutoGearboxDevice.GEARBOX_AUTO_MODE_R, N, D, M, S; P is 1.
        private val DRIVING_GEARS = 2..6
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
    private val powerOff = BydPowerOffDetector()
    private var stream: BydSdkStream? = null

    /** Where readings are logged; the current host screen sets it, so a recreated screen takes over. */
    @Volatile
    var log: (String) -> Unit = {}

    /** Called on the reader thread with `shown` whenever the reading changes, the first included. */
    @Volatile
    var onChanged: (Boolean) -> Unit = {}

    /**
     * Called once on the reader thread when the car is switched off; the head unit sleeps about ten
     * seconds later and kills the app.
     */
    @Volatile
    var onPowerOff: () -> Unit = {}

    @Synchronized
    fun start(context: Context) {
        if (stream != null) return
        state.clear()
        stream = BydSdkStream(context.applicationContext, "panorama", { line ->
            val reading = BydCameraReading.parse(line) ?: return@BydSdkStream
            val now = SystemClock.elapsedRealtime()
            if (state.update(reading, now)) {
                runCatching { log("BYD camera: shown=${reading.shown} ${reading.describe()}") }
                runCatching { onChanged(reading.shown) }
            }
            if (powerOff.update(reading, now)) {
                runCatching { log("BYD vehicle switched off: ${reading.describe()}") }
                runCatching { onPowerOff() }
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
