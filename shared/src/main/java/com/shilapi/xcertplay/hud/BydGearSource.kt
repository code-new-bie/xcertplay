package com.shilapi.xcertplay.hud

import android.content.Context
import java.io.Closeable

/**
 * Whether the car is in P, for video in car. The gear is read through BYD's SDK in this process
 * (BYDAUTO_GEARBOX_COMMON, a normal permission), with a change listener, so it needs no ADB and has
 * no gap while the ADB helper restarts. When the SDK does not answer, the ADB camera monitor's
 * reading is used. Which source answers is logged when it changes.
 */
object BydGearSource {
    private const val GEARBOX = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
    private const val READ_GEAR = "getGearboxAutoModeType"

    /** Where the source is logged; the current host screen sets it. */
    @Volatile
    var log: (String) -> Unit = {}

    @Volatile private var onChange: () -> Unit = {}
    private var started = false
    @Volatile private var device: BydSdkDevice? = null
    private var events: Closeable? = null
    private var source: String? = null

    /** Opens the SDK's gearbox device once; later calls do nothing. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        BydSdkDevice.open(context, GEARBOX)
            .onSuccess { opened ->
                device = opened
                events = opened.listen { onChange() }
                report(
                    "Gear: BYD SDK opened in the app, gear=${opened.readInt(READ_GEAR) ?: "unreadable"}, " +
                        "change events ${if (events != null) "registered" else "unavailable"}",
                )
            }
            .onFailure { report("Gear: BYD SDK unavailable in the app (${it.javaClass.simpleName}: ${it.message}); using ADB") }
    }

    /** Called on any gearbox event (any thread); null stops. */
    fun watch(listener: (() -> Unit)?) {
        onChange = listener ?: {}
    }

    /** True in P, false in another gear, null when neither the SDK nor ADB gives a gear. */
    fun parked(): Boolean? {
        val direct = device?.readInt(READ_GEAR)?.let(BydGear::parked)
        val parked = direct ?: BydCameraMonitor.parked()
        noteSource(
            when {
                direct != null -> "BYD SDK"
                parked != null -> "ADB"
                else -> "none"
            },
        )
        return parked
    }

    @Synchronized
    private fun noteSource(next: String) {
        if (next == source) return
        source = next
        report("Gear source: $next")
    }

    private fun report(message: String) {
        runCatching { log(message) }
    }
}
