package com.shilapi.xcertplay.hud

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Shell-side call-state bridge. Its Binder token lives until this process exits. */
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
                    if (now - stopSince >= 1_000) {
                        // A stuck Binder cannot keep the client token alive indefinitely.
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
                try { Thread.sleep(CallWindProbe.POLL_MS) } catch (_: InterruptedException) { return@thread }
            }
        }
        val hook = Thread({ runCatching { probe.get()?.releaseOnce() } }, "xcertplay-call-wind-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            emit("starting pid=${android.os.Process.myPid()} mode=${if (automatic) "automatic" else "manual"}")
            RandomAccessFile(File(file.parentFile, "test.lock"), "rw").channel.use { channel ->
                val ownership = channel.tryLock()
                if (ownership == null) { emit("blocked: another probe is running"); return }
                ownership.use {
                    if (!file.isFile) { emit("cancelled before request"); return }
                    val current = CallWindProbe(NativeBridge(context()),
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

    private class NativeBridge(context: Context) : CallWindBridge {
        private val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "audio") as IBinder
        private val audio = Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        private val audioType = Class.forName("android.media.IAudioService")
        // The firmware's AudioManager wrapper swallows RemoteException. Call the same service
        // directly so a failed request/release is reported instead of looking successful.
        private val setter = audioType.getMethod("setCallState", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, IBinder::class.java)
        private val mode = audioType.getMethod("getMode")
        private val callback = Binder()
        private val muteSetter = audioType.getMethod("setMuteState",
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        private val modeSetter = audioType.getMethod("setMode", Int::class.javaPrimitiveType,
            IBinder::class.java, String::class.java)
        private val packageName = context.opPackageName
        private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        private val property = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
        private var callAttempted = false
        private var muteAttempted = false
        private var modeAttempted = false

        override fun unavailableReason(): String? {
            if (property.invoke(null, "sys.isInEcall", "0") != "0") return "emergency call is active"
            if (mode.invoke(audio) != AudioManager.MODE_NORMAL) return "system call/audio mode is busy"
            if (property.invoke(null, "bluetooth.call", "false") != "false") return "Bluetooth call is active"
            if (property.invoke(null, "sys.hicar.callstate", "0") != "0") return "HiCar call is active"
            val adapter = adapter ?: return "HFP status unavailable"
            if (adapter.getProfileConnectionState(HEADSET_CLIENT) != BluetoothProfile.STATE_DISCONNECTED) {
                return "disconnect the stock Bluetooth phone connection before starting"
            }
            return null
        }

        /** Same order as the stock HiCar service: call state, mute, then audio mode. */
        override fun request() {
            check(unavailableReason() == null) { "native call became active before entry" }
            callAttempted = true
            setter.invoke(audio, CALL_CLIENT_BT, AudioManager.MODE_IN_CALL, callback)
            muteAttempted = true
            muteSetter.invoke(audio, CALL_CLIENT_BT, true)
            modeAttempted = true
            modeSetter.invoke(audio, AudioManager.MODE_IN_CALL, callback, packageName)
        }

        /** Undo attempted operations, including ambiguous Binder failures, but never untouched state. */
        override fun release() {
            var failure: Exception? = null
            fun attempt(action: () -> Unit) {
                try { action() } catch (error: Exception) { if (failure == null) failure = error }
            }
            if (muteAttempted) attempt { muteSetter.invoke(audio, CALL_CLIENT_BT, false) }
            if (callAttempted) attempt { setter.invoke(audio, CALL_CLIENT_BT, AudioManager.MODE_NORMAL, callback) }
            if (modeAttempted) attempt { modeSetter.invoke(audio, AudioManager.MODE_NORMAL, callback, packageName) }
            failure?.let { throw it }
        }
    }

    private const val AUTO_MAX_MS = 30 * 60_000L
    private const val HEADSET_CLIENT = 16
    private const val CALL_CLIENT_BT = 1
}
