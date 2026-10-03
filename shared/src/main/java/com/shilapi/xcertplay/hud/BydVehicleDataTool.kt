package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException

/** Read-only SDK bridge running as adb shell. Uses this firmware's SDK instead of another ROM's IDs. */
object BydVehicleDataTool {
    private class Device(private val name: String, context: Context) {
        private val type = Class.forName("android.hardware.bydauto.$name")
        private val instance = try {
            type.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (_: InvocationTargetException) {
            type.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        }
        fun read(method: String): Number = type.getMethod(method).invoke(instance) as Number
    }

    @JvmStatic
    @SuppressLint("PrivateApi")
    fun main(args: Array<String>) {
        try {
            runCatching { android.os.Looper.prepareMainLooper() }
            val thread = Class.forName("android.app.ActivityThread")
            val main = thread.getMethod("systemMain").invoke(null)
            val context = thread.getMethod("getSystemContext").invoke(main) as Context
            val mode = args.firstOrNull() ?: return
            val once = args.getOrNull(1) == "once"
            val speed = if (mode == "speed") Device("speed.BYDAutoSpeedDevice", context) else null
            val gearbox = if (speed != null) Device("gearbox.BYDAutoGearboxDevice", context) else null
            val statistic = if (mode == "battery") Device("statistic.BYDAutoStatisticDevice", context) else null
            val power = if (statistic != null) runCatching { Device("power.BYDAutoPowerDevice", context) }.getOrNull() else null
            val charging = if (statistic != null) runCatching { Device("charging.BYDAutoChargingDevice", context) }.getOrNull() else null
            check(speed != null || statistic != null)
            val deadline = SystemClock.elapsedRealtime() + 5 * 60_000L
            var lastBattery = -30_000L
            var lastGear = -1_000L
            var gear = 0
            do {
                val before = SystemClock.elapsedRealtime()
                if (speed != null && gearbox != null) {
                    if (before - lastGear >= 1_000L) {
                        gear = gearbox.read("getGearboxAutoModeType").toInt()
                        lastGear = before
                    }
                    val kmh = speed.read("getCurrentSpeed").toDouble()
                    println("XCERTPLAY speed ${(before + SystemClock.elapsedRealtime()) / 2} $kmh $gear")
                } else if (statistic != null && before - lastBattery >= 30_000L) {
                    val percent = statistic.read("getElecPercentageValue").toDouble()
                    val range = statistic.read("getElecDrivingRangeValue").toInt()
                    val remaining = runCatching { power?.read("getBatteryRemainPowerEV")?.toDouble() }.getOrNull()
                    val state = runCatching { charging?.read("getBatteryManagementDeviceState")?.toInt() }.getOrNull()
                    println("XCERTPLAY battery $percent $range ${remaining ?: "-"} ${state ?: "-"}")
                    lastBattery = before
                } else {
                    println("XCERTPLAY heartbeat") // keep adb's read deadline alive between battery samples
                }
                System.out.flush()
                if (once || System.out.checkError()) break
                Thread.sleep(if (speed != null) 250L else 1_000L)
            } while (SystemClock.elapsedRealtime() < deadline)
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            println("XCERTPLAY error ${cause.javaClass.simpleName} ${cause.message?.take(120).orEmpty()}")
        } finally {
            System.out.flush()
            System.exit(0)
        }
    }
}
