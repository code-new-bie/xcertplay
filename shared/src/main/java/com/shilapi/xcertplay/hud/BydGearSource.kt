package com.shilapi.xcertplay.hud

import android.os.SystemClock

/**
 * Whether the car is in P, for video in car, from the ADB helpers: the camera monitor's gear, or the
 * wheel-speed helper's while the camera one has none (it can time out at session start, and each
 * helper restarts every five minutes). The app cannot read the gear itself: BYD's service refuses
 * the gearbox to other apps ("[getInt] permission deny!" in the 2026-10-23 log; its COMMON
 * permissions cover only AC, bodywork, door lock, instrument, panorama and settings). Which source
 * answers is logged when it changes.
 */
object BydGearSource {
    /** Where the source is logged; the current host screen sets it. */
    @Volatile
    var log: (String) -> Unit = {}

    private var source: String? = null

    /** True in P, false in another gear, null when neither helper gives a gear. */
    fun parked(): Boolean? {
        val camera = BydCameraMonitor.parked()
        val parked = camera ?: BydSpeedGear.parked(SystemClock.elapsedRealtime())
        noteSource(
            when {
                camera != null -> "ADB camera helper"
                parked != null -> "ADB wheel-speed helper"
                else -> "none"
            },
        )
        return parked
    }

    @Synchronized
    private fun noteSource(next: String) {
        if (next == source) return
        source = next
        runCatching { log("Gear source: $next") }
    }
}
