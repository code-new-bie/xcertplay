package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** SDK bridge running as adb shell. Uses this firmware's SDK instead of another ROM's IDs. */
object BydVehicleDataTool {
    /**
     * An SDK device instance. getInstance() checks the device's BYDAUTO_*_COMMON permission on the
     * Java side, which the shell lacks; the constructor skips it and autoservice accepts shell UIDs.
     */
    internal fun sdkDevice(type: Class<*>, context: Context): Any = try {
        type.getMethod("getInstance", Context::class.java).invoke(null, context)
    } catch (_: InvocationTargetException) {
        type.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
    }

    private class Device(private val name: String, context: Context) {
        private val type = Class.forName("android.hardware.bydauto.$name")
        private val instance = sdkDevice(type, context)
        fun read(method: String): Number = type.getMethod(method).invoke(instance) as Number

        /** Calls a setter; the result code, or the failure, is returned as text for the test log. */
        fun write(method: String, vararg args: Any): String = try {
            val types = args.map { if (it is ByteArray) ByteArray::class.java else Int::class.javaPrimitiveType!! }
            checkNotNull(type).getMethod(method, *types.toTypedArray()).invoke(instance, *args).toString()
        } catch (error: InvocationTargetException) {
            val cause = error.targetException
            "failed ${cause.javaClass.simpleName} ${cause.message?.take(80).orEmpty()}"
        }
    }

    /**
     * Prints the camera state ([BydCameraReading.LINE_PREFIX]: panorama work state, display mode,
     * gear, vehicle power level) when it changes, and every [CAMERA_REPEAT_MS] to keep adb's read
     * deadline alive. A car without a panorama unit still reports the gear, which brings up the
     * rear camera. The power level tells the app the car was switched off before the head unit
     * sleeps.
     */
    private fun cameraWatch(context: Context, once: Boolean) {
        val panorama = runCatching { Device("panorama.BYDAutoPanoramaDevice", context) }.getOrNull()
        val gearbox = runCatching { Device("gearbox.BYDAutoGearboxDevice", context) }.getOrNull()
        val bodywork = runCatching { Device("bodywork.BYDAutoBodyworkDevice", context) }.getOrNull()
        check(panorama != null || gearbox != null)
        fun read(device: Device?, method: String): String =
            device?.let { runCatching { it.read(method).toInt().toString() }.getOrNull() } ?: "-"
        val deadline = SystemClock.elapsedRealtime() + 5 * 60_000L
        var last: String? = null
        var lastPrinted = 0L
        do {
            val line = "${BydCameraReading.LINE_PREFIX} ${read(panorama, "getPanoWorkState")} " +
                "${read(panorama, "getDisplayMode")} ${read(gearbox, "getGearboxAutoModeType")} " +
                read(bodywork, "getPowerLevel")
            val now = SystemClock.elapsedRealtime()
            if (line != last || now - lastPrinted >= CAMERA_REPEAT_MS) {
                println(line)
                System.out.flush()
                last = line
                lastPrinted = now
            }
            if (once || System.out.checkError()) break
            Thread.sleep(CAMERA_POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
    }

    private const val CAMERA_POLL_MS = 300L
    private const val CAMERA_REPEAT_MS = 2_000L

    /** BYD's instrument device type; setMediaState/setMediaInfo take it explicitly. */
    private const val INSTRUMENT_DEVICE = 1007

    /**
     * Writes the dashboard music card the way BYD's media center does underneath its focus-owner
     * check, reusing the SDK instance and feature IDs for every update in this shell process. The
     * signal IDs differ between firmware builds (Song PLUS 2021 and Tang 2024 use different
     * values), so they are read from this firmware's BYDAutoFeatureIds.
     */
    private class ClusterInstrument(context: Context) {
        private val ids = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
        private val sourceId = ids.getField("INSTRUMENT_MUSIC_SOURCE_SET").getInt(null)
        private val stateId = ids.getField("INSTRUMENT_MUSIC_STATE_SET").getInt(null)
        private val infoId = ids.getField("INSTRUMENT_MUSIC_INFO_SET").getInt(null)
        private val device = Device("instrument.BYDAutoInstrumentDevice", context)

        fun apply(frame: ClusterWriterFrame): String {
            val source = if (frame.state != 3) {
                device.write("setMediaState", INSTRUMENT_DEVICE, sourceId, 11)
            } else "0"
            val state = device.write("setMediaState", INSTRUMENT_DEVICE, stateId, frame.state)
            val text = device.write("setMediaInfo", INSTRUMENT_DEVICE, infoId, frame.text.toByteArray(Charsets.UTF_16LE))
            return "source=$source state=$state text=$text"
        }

        fun clear(): String = apply(ClusterWriterFrame("", 0, 0, 0, 3, true, " "))
    }

    private fun systemContext(): Context {
        runCatching { android.os.Looper.prepareMainLooper() }
        val type = Class.forName("android.app.ActivityThread")
        val main = type.getMethod("systemMain").invoke(null)
        return type.getMethod("getSystemContext").invoke(main) as Context
    }

    private fun clusterSongWatch(args: List<String>) {
        val token = args.getOrNull(0) ?: error("missing cluster session token")
        require(token.matches(Regex("[a-f0-9]{32}")))
        val path = String(java.util.Base64.getDecoder().decode(args.getOrNull(1)), Charsets.UTF_8)
        val file = File(path)
        require(file.isAbsolute && file.name == "$token.state")
        val mailbox = ClusterWriterMailbox(file)
        val finished = AtomicBoolean(false)
        val cleared = AtomicBoolean(false)
        val clearDone = CountDownLatch(1)
        val clearResult = AtomicReference("state=pending text=pending")
        val instrument = AtomicReference<ClusterInstrument?>()
        fun emit(line: String) { println(line); System.out.flush() }
        fun clearOnce(): String {
            if (cleared.compareAndSet(false, true)) {
                try {
                    clearResult.set(runCatching { instrument.get()?.clear() ?: "state=unavailable text=unavailable" }
                        .getOrElse { "state=failed text=failed reason=${it.javaClass.simpleName}" })
                } finally { clearDone.countDown() }
            } else {
                clearDone.await(ClusterWriterFrame.FORCE_STOP_GRACE_MS, TimeUnit.MILLISECONDS)
            }
            return clearResult.get()
        }
        emit("XCERTPLAY clusterwriter starting token=$token pid=${android.os.Process.myPid()}")
        // Independent of SDK calls: even a hung binder cannot leave an orphan shell process.
        thread(name = "xcertplay-cluster-watchdog", isDaemon = true) {
            val watchdog = ClusterWriterWatchdog(token)
            val fallbackClearStarted = AtomicBoolean(false)
            while (!finished.get()) {
                val now = SystemClock.elapsedRealtime()
                if (watchdog.shouldTerminate(mailbox.read(), now)) {
                    emit("XCERTPLAY clusterwriter watchdog stopping token=$token " +
                        "reason=${watchdog.stopReason() ?: "unknown"}")
                    mailbox.delete()
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@thread
                }
                if (watchdog.shouldTryClear(now) && !cleared.get() && fallbackClearStarted.compareAndSet(false, true)) {
                    thread(name = "xcertplay-cluster-forced-clear", isDaemon = true) {
                        emit("XCERTPLAY clusterwriter forced-clear token=$token ${clearOnce()}")
                    }
                }
                Thread.sleep(ClusterWriterFrame.POLL_MS)
            }
        }
        val shutdownHook = Thread({ runCatching { clearOnce() } }, "xcertplay-cluster-clear")
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            RandomAccessFile(File(file.parentFile, "writer.lock"), "rw").channel.use { channel ->
                val deadline = SystemClock.elapsedRealtime() + 4_500L
                var ownership = channel.tryLock()
                while (ownership == null && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(ClusterWriterFrame.POLL_MS)
                    ownership = channel.tryLock()
                }
                checkNotNull(ownership) { "another cluster writer is still active" }.use {
                    val frame = mailbox.read()
                    if (frame == null || frame.stopReason(token, SystemClock.elapsedRealtime()) != null) return@use
                    val device = ClusterInstrument(systemContext())
                    instrument.set(device)
                    emit("XCERTPLAY clusterwriter ready token=$token pollMs=${ClusterWriterFrame.POLL_MS}")
                    ClusterWriterLoop(token, mailbox::read, SystemClock::elapsedRealtime, Thread::sleep,
                        device::apply, ::clearOnce, ::emit).run()
                }
            }
        } finally {
            try { clearOnce() } finally {
                finished.set(true)
                mailbox.delete()
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            }
        }
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
            if (args.firstOrNull() == "callwindtest") {
                BydCallWindTestTool.run(args.drop(1), ::systemContext)
                return
            }
            if (args.firstOrNull() == "clustersongwatch") {
                clusterSongWatch(args.drop(1))
                return
            }
            if (args.firstOrNull() == "wifiscan") {
                wifiScan(args.getOrNull(1) == "on")
                return
            }
            val context = systemContext()
            val mode = args.firstOrNull() ?: return
            val once = args.getOrNull(1) == "once"
            if (mode == "panorama") {
                cameraWatch(context, once)
                return
            }
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
