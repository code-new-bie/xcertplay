package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Shell-side fan reduction. Runs as adb shell because the head unit's autoservice accepts AC
 * writes from UIDs below 10000 only; app UIDs would need a BYDAUTO_AC_SET no app can hold.
 */
internal object BydCallWindTestTool {
    fun run(args: List<String>, context: () -> Context) {
        val token = args.getOrNull(0) ?: error("missing call wind token")
        require(token.matches(Regex("[a-f0-9]{32}")))
        val file = File(String(Base64.getDecoder().decode(args.getOrNull(1)), Charsets.UTF_8))
        require(file.isAbsolute && file.name == "$token.request")
        val automatic = when (args.getOrNull(2) ?: "manual") {
            "manual" -> false
            "auto" -> true
            else -> error("unknown call wind mode")
        }
        val target = args.getOrNull(3)?.toIntOrNull() ?: error("missing call wind target level")
        require(target in BydCallWindSettings.MIN_LEVEL..BydCallWindSettings.MAX_LEVEL)
        fun emit(message: String) {
            println("XCERTPLAY callwind token=$token $message")
            System.out.flush()
        }
        val finished = AtomicBoolean(false)
        val probe = AtomicReference<CallWindProbe?>()
        val lease = CallWindLease(SystemClock::elapsedRealtime)
        fun leaseAlive() = if (automatic) lease.alive(file.isFile, file.lastModified()) else file.isFile
        val expires = SystemClock.elapsedRealtime() +
            if (automatic) AUTO_MAX_MS else CallWindProbe.DURATION_MS + 5_000L
        val watchdog = thread(name = "xcertplay-call-wind-watchdog", isDaemon = true) {
            var stopSince: Long? = null
            while (!finished.get()) {
                val now = SystemClock.elapsedRealtime()
                if (!leaseAlive() || now >= expires) {
                    if (stopSince == null) {
                        stopSince = now
                        thread(name = "xcertplay-call-wind-release", isDaemon = true) {
                            emit(probe.get()?.releaseOnce() ?: "not requested")
                        }
                    }
                    if (now - stopSince >= RELEASE_GRACE_MS) {
                        // A stuck Binder cannot keep the helper alive indefinitely.
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
                try { Thread.sleep(CallWindProbe.POLL_MS) } catch (_: InterruptedException) { return@thread }
            }
        }
        val hook = Thread({ runCatching { probe.get()?.releaseOnce() } }, "xcertplay-call-wind-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            emit("starting pid=${android.os.Process.myPid()} mode=${if (automatic) "automatic" else "manual"} " +
                "target=$target")
            RandomAccessFile(File(file.parentFile, "test.lock"), "rw").channel.use { channel ->
                val ownership = channel.tryLock()
                if (ownership == null) { emit("blocked: another probe is running"); return }
                ownership.use {
                    if (!file.isFile) { emit("cancelled before request"); return }
                    val bridge = AcWindReduction(VehicleAcFan(context()), target, ::nativeCall,
                        SystemClock::elapsedRealtime, Thread::sleep, ::emit)
                    val current = CallWindProbe(bridge,
                        {
                            leaseAlive() && SystemClock.elapsedRealtime() < expires
                        },
                        SystemClock::elapsedRealtime, Thread::sleep, ::emit)
                    probe.set(current)
                    emit(current.run(if (automatic) 0L else CallWindProbe.DURATION_MS))
                }
            }
        } catch (error: Exception) {
            val cause = error.cause ?: error
            emit("unavailable: ${cause.javaClass.simpleName} ${cause.message.orEmpty().take(160)}")
        } finally {
            // Keep the watchdog armed while releasing, including on SDK/Binder failures.
            try { probe.get()?.releaseOnce() } finally {
                finished.set(true)
                watchdog.interrupt()
                file.delete()
                Runtime.getRuntime().removeShutdownHook(hook)
            }
        }
    }

    /** A stock Bluetooth, HiCar or emergency call already lowers the fan through the car itself. */
    private fun nativeCall(): String? {
        val property = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
        if (property.invoke(null, "sys.isInEcall", "0") != "0") return "emergency call is active"
        if (property.invoke(null, "bluetooth.call", "false") != "false") return "Bluetooth call is active"
        if (property.invoke(null, "sys.hicar.callstate", "0") != "0") return "HiCar call is active"
        return null
    }

    private class VehicleAcFan(context: Context) : AcFan {
        private val acType = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice")
        private val ac = BydVehicleDataTool.sdkDevice(acType, context)
        private val settingType = runCatching {
            Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice")
        }.getOrNull()
        private val setting = settingType?.let { runCatching { BydVehicleDataTool.sdkDevice(it, context) }.getOrNull() }
        private val int = Int::class.javaPrimitiveType!!

        override fun powerState() = read("getAcStartState")
        override fun controlMode() = read("getAcControlMode")
        override fun windLevel() = read("getAcWindLevel")
        override fun stockCallWind(): Int? = runCatching {
            (settingType!!.getMethod("getACBTWind").invoke(setting!!) as Number).toInt()
        }.getOrNull()

        override fun setWindLevel(level: Int) = (acType.getMethod("setAcWindLevel", int, int)
            .invoke(ac, AcWindReduction.AC_CTRL_SOURCE_UI_KEY, level) as Number).toInt()

        override fun setAutoMode() = (acType.getMethod("setAcControlMode", int, int)
            .invoke(ac, AcWindReduction.AC_CTRL_SOURCE_UI_KEY, AcWindReduction.AC_CTRLMODE_AUTO) as Number).toInt()

        private fun read(method: String) = (acType.getMethod(method).invoke(ac) as Number).toInt()
    }

    private const val AUTO_MAX_MS = 30 * 60_000L
    /** BYD AC commands wait for the vehicle's acknowledgement, so release gets several seconds. */
    const val RELEASE_GRACE_MS = 5_000L
}
