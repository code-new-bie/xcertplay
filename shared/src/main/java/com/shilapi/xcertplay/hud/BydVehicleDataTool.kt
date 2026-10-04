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

        /** Calls a setter; the result code, or the failure, is returned as text for the test log. */
        fun write(method: String, vararg args: Any): String = try {
            val types = args.map { if (it is ByteArray) ByteArray::class.java else Int::class.javaPrimitiveType!! }
            type.getMethod(method, *types.toTypedArray()).invoke(instance, *args).toString()
        } catch (error: InvocationTargetException) {
            val cause = error.targetException
            "failed ${cause.javaClass.simpleName} ${cause.message?.take(80).orEmpty()}"
        }
    }

    /**
     * Sends the instrument cluster what BYD's phone app sends during a Bluetooth call: the caller
     * name (UTF-16LE) and a running call time. Shows whether the shell may write these and whether
     * the car reacts (cluster call card, lowered fan).
     */
    private fun callInfoTest(context: Context) {
        val instrument = Device("instrument.BYDAutoInstrumentDevice", context)
        val name = "CarPlay test".toByteArray(Charsets.UTF_16LE)
        println("XCERTPLAY calltest sendCallInfo=${instrument.write("sendCallInfo", name)}")
        for (second in 0..CALL_TEST_SECONDS) {
            val result = instrument.write("sendCallTime", 0, 0, second)
            // One line a second also keeps adb's 5-second read deadline alive.
            println("XCERTPLAY calltest sendCallTime(0,0,$second)=$result")
            System.out.flush()
            Thread.sleep(1_000L)
        }
        println("XCERTPLAY calltest done")
    }

    private const val CALL_TEST_SECONDS = 10

    /** BYD's instrument device type; setMediaState/setMediaInfo take it explicitly. */
    private const val INSTRUMENT_DEVICE = 1007

    /**
     * Writes the dashboard music card: source, play state and text ("-" skips one), the way BYD's
     * media center does underneath its focus-owner check. The signal IDs differ between firmware
     * builds (Song PLUS 2021 and Tang 2024 use different values), so they are read from this
     * firmware's BYDAutoFeatureIds; when they cannot be read nothing is written.
     */
    private fun clusterSong(context: Context, args: List<String>) {
        val ids = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
        fun id(name: String): Int = ids.getField(name).getInt(null)
        val sourceId = id("INSTRUMENT_MUSIC_SOURCE_SET")
        val stateId = id("INSTRUMENT_MUSIC_STATE_SET")
        val infoId = id("INSTRUMENT_MUSIC_INFO_SET")
        val instrument = Device("instrument.BYDAutoInstrumentDevice", context)
        val results = mutableListOf<String>()
        args.getOrNull(0)?.takeIf { it != "-" }?.let {
            results += "source=${instrument.write("setMediaState", INSTRUMENT_DEVICE, sourceId, it.toInt())}"
        }
        args.getOrNull(1)?.takeIf { it != "-" }?.let {
            results += "state=${instrument.write("setMediaState", INSTRUMENT_DEVICE, stateId, it.toInt())}"
        }
        args.getOrNull(2)?.takeIf { it != "-" }?.let { encoded ->
            val text = String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8).toByteArray(Charsets.UTF_16LE)
            results += "text=${if (text.size > 255) "too long" else instrument.write("setMediaInfo", INSTRUMENT_DEVICE, infoId, text)}"
        }
        println("XCERTPLAY clustersong ${results.joinToString(" ")}")
    }

    /**
     * Turns the head unit's automatic Wi-Fi network search on or off. BYD scans every 10 s while its
     * Wi-Fi client is not joined, pulling the radio off the CarPlay channel; the shell holds
     * CONNECTIVITY_INTERNAL, which is all enableWifiConnectivityManager checks. Called by name, so
     * firmware builds with different binder transaction numbers need nothing special.
     */
    @SuppressLint("PrivateApi")
    private fun wifiScan(enabled: Boolean) {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "wifi") as android.os.IBinder
        val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            ?: error("no wifi service")
        wifi.javaClass.getMethod("enableWifiConnectivityManager", Boolean::class.javaPrimitiveType).invoke(wifi, enabled)
        println("XCERTPLAY wifiscan enabled=$enabled ok")
    }

    @JvmStatic
    @SuppressLint("PrivateApi")
    fun main(args: Array<String>) {
        try {
            if (args.firstOrNull() == "wifiscan") {
                wifiScan(args.getOrNull(1) == "on")
                return
            }
            runCatching { android.os.Looper.prepareMainLooper() }
            val thread = Class.forName("android.app.ActivityThread")
            val main = thread.getMethod("systemMain").invoke(null)
            val context = thread.getMethod("getSystemContext").invoke(main) as Context
            val mode = args.firstOrNull() ?: return
            if (mode == "calltest") {
                callInfoTest(context)
                return
            }
            if (mode == "clustersong") {
                clusterSong(context, args.drop(1))
                return
            }
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
