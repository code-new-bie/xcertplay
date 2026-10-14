package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.pm.PackageManager
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
    private const val PERMISSION = "android.permission.BYDAUTO_GEARBOX_COMMON"

    /** Where the source is logged; the current host screen sets it. */
    @Volatile
    var log: (String) -> Unit = {}

    @Volatile private var onChange: () -> Unit = {}
    private var started = false
    @Volatile private var device: BydSdkDevice? = null
    private var events: Closeable? = null
    private var source: String? = null
    private var readFailure: String? = null

    /** Opens the SDK's gearbox device once; later calls do nothing. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        BydSdkDevice.open(context, GEARBOX)
            .onSuccess { opened ->
                device = opened
                val listening = opened.tryListen { onChange() }
                events = listening.getOrNull()
                // Why a read or a listener fails is the point of this line: the refusal of the car's
                // service, a missing method and a permission that was not granted look alike otherwise.
                val gear = opened.tryReadInt(READ_GEAR)
                    .fold({ it.toString() }, { "unreadable (${BydSdkDevice.describe(it)})" })
                val changes = listening.fold({ "registered" }, { "unavailable (${BydSdkDevice.describe(it)})" })
                val granted = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
                report(
                    "Gear: BYD SDK opened in the app, gear=$gear, change events $changes, " +
                        "permission ${if (granted) "granted" else "not granted"}",
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
        val direct = device?.let(::readParked)
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

    /** The SDK's reading as parked or not; null, and why in the log, when it gives no gear. */
    private fun readParked(device: BydSdkDevice): Boolean? {
        val read = device.tryReadInt(READ_GEAR)
        val value = read.getOrNull()
        val parked = value?.let(BydGear::parked)
        noteReadFailure(
            when {
                parked != null -> null
                value != null -> "returned $value, which is not a gear"
                else -> BydSdkDevice.describe(checkNotNull(read.exceptionOrNull()))
            },
        )
        return parked
    }

    /** Logs a new reason the SDK gives no gear, once; a reading that works clears it. */
    @Synchronized
    private fun noteReadFailure(reason: String?) {
        if (reason == readFailure) return
        readFailure = reason
        if (reason != null) report("Gear: BYD SDK gives no gear ($reason)")
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
