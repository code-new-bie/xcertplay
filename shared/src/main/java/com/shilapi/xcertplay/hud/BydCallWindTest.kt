package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Coordinates the diagnostic probe and the opt-in CarPlay call session. */
object BydCallWindTest {
    data class Result(val requested: Boolean, val released: Boolean, val cancelled: Boolean, val details: String,
        val processGone: Boolean = true)
    private val lock = Any()
    private var manual: Run? = null
    private var automatic: Run? = null
    private var callOwner: Any? = null
    private var phoneCallActive = false
    private var generation = 0L
    private var cleanupUnconfirmed = false

    fun observeCall(owner: Any, active: Boolean) = synchronized(lock) {
        if (callOwner !== owner || phoneCallActive != active) generation++
        callOwner = owner
        phoneCallActive = active
        if (active) manual?.stop("CarPlay call started")
        else automatic?.stop("CarPlay call ended")
    }

    /** Updates call state and starts the opt-in full HiCar audio sequence off the callback thread. */
    fun observeCall(context: Context, owner: Any, active: Boolean, log: (String) -> Unit) {
        val expectedGeneration = synchronized(lock) {
            if (callOwner === owner && phoneCallActive == active) return
            generation++
            callOwner = owner
            phoneCallActive = active
            if (active) {
                manual?.stop("CarPlay call started")
            } else {
                automatic?.stop("CarPlay call ended")
            }
            generation
        }
        if (!active || !BydCallWindSettings.enabled(context)) return
        thread(name = "xcertplay-call-wind-auto-wait", isDaemon = true) {
            // Also wait for an earlier automatic call that is still releasing after a quick redial.
            repeat(80) {
                val waiting = synchronized(lock) {
                    if (generation != expectedGeneration || cleanupUnconfirmed) return@thread
                    manual != null || automatic != null
                }
                if (!waiting) {
                    startAutomatic(context, owner, expectedGeneration, log)
                    return@thread
                }
                try { Thread.sleep(100L) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }
            }
            runCatching { log("Call wind automatic: previous helper cleanup timed out; start skipped") }
        }
    }

    fun clearCall(owner: Any) = synchronized(lock) {
        if (callOwner === owner) {
            generation++
            callOwner = null
            phoneCallActive = false
            automatic?.stop("CarPlay call owner cleared")
        }
    }

    fun start(context: Context, log: (String) -> Unit, completed: (Result) -> Unit): Boolean = synchronized(lock) {
        if (manual != null || automatic != null || phoneCallActive || cleanupUnconfirmed) return@synchronized false
        val run = Run(context.applicationContext, log, automatic = false)
        manual = run
        thread(name = "xcertplay-call-wind-test", isDaemon = true) {
            val result = run.execute()
            synchronized(lock) {
                if (!result.processGone) cleanupUnconfirmed = true
                if (manual === run) manual = null
            }
            completed(result)
        }
        true
    }

    fun cancel(reason: String): CompletableFuture<Boolean> {
        val futures = synchronized(lock) {
            generation++ // Revoke queued starts before waiting for existing helpers.
            listOfNotNull(manual?.stop(reason), automatic?.stop(reason))
        }
        if (futures.isEmpty()) return CompletableFuture.completedFuture(synchronized(lock) { !cleanupUnconfirmed })
        val result = CompletableFuture<Boolean>()
        thread(name = "xcertplay-call-wind-cancel", isDaemon = true) {
            var confirmed = true
            futures.forEach { future ->
                confirmed = runCatching { future.get(6_000, TimeUnit.MILLISECONDS) && confirmed }
                    .getOrDefault(false)
            }
            result.complete(confirmed)
        }
        return result
    }

    fun cancelManual(reason: String): CompletableFuture<Boolean> = synchronized(lock) {
        manual?.stop(reason) ?: CompletableFuture.completedFuture(true)
    }

    private fun startAutomatic(context: Context, owner: Any, expectedGeneration: Long,
        log: (String) -> Unit) = synchronized(lock) {
        if (generation != expectedGeneration || cleanupUnconfirmed ||
            !phoneCallActive || callOwner !== owner || manual != null || automatic != null ||
            !BydCallWindSettings.enabled(context)
        ) return@synchronized
        val run = Run(context.applicationContext, log, automatic = true)
        automatic = run
        thread(name = "xcertplay-call-wind-auto", isDaemon = true) {
            val result = run.execute()
            synchronized(lock) {
                if (!result.processGone) cleanupUnconfirmed = true
                if (automatic === run) automatic = null
            }
            runCatching {
                log("Call wind automatic: requested=${result.requested} released=${result.released} " +
                    "cancelled=${result.cancelled} processGone=${result.processGone}")
            }
        }
    }

    private class Run(
        private val context: Context,
        private val log: (String) -> Unit,
        private val automatic: Boolean,
    ) {
        private val stateLock = Any()
        private val token = UUID.randomUUID().toString().replace("-", "")
        private val done = CompletableFuture<Boolean>()
        @Volatile private var stopping = false
        @Volatile private var client: LocalAdb? = null
        private var file: File? = null
        private var launched = false
        @Volatile private var heartbeat: Thread? = null

        fun stop(reason: String): CompletableFuture<Boolean> {
            synchronized(stateLock) {
                if (stopping || done.isDone) return done
                stopping = true
                file?.delete()
            }
            report("cancel reason=$reason")
            thread(name = "xcertplay-call-wind-stop", isDaemon = true) {
                // Leave the stream open briefly for the release acknowledgement.
                try {
                    done.get(1_500, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    client?.close()
                } catch (_: Exception) {
                    client?.close()
                }
            }
            return done
        }

        fun execute(): Result {
            val lines = mutableListOf<String>()
            var requested = false
            var released = false
            var processGone = true
            var pid: Int? = null
            var untouched = false
            try {
                val path = prepareFile() ?: return Result(false, false, true, "cancelled before startup")
                startAutomaticLease(path)
                val adb = LocalAdb(AdbKeys.load(context)).also { client = it }
                if (stopping) return Result(false, false, true, "cancelled before authentication")
                val access = adb.connect(mayAsk = !automatic)
                if (access != LocalAdb.Access.READY) throw IOException("ADB access=$access")
                if (stopping) return Result(false, false, true, "cancelled before launch")
                val quoted = "'${path.absolutePath.replace("'", "'\\''")}'"
                if (adb.shell("if [ -r $quoted ]; then echo readable; fi")?.trim() != "readable") {
                    throw IOException("ADB cannot read the test request")
                }
                synchronized(stateLock) {
                    if (stopping) return Result(false, false, true, "cancelled before launch")
                    launched = true
                    processGone = false
                }
                val encoded = Base64.getEncoder().encodeToString(path.absolutePath.toByteArray(Charsets.UTF_8))
                val mode = if (automatic) "auto" else "manual"
                val helper = BydSdkStream.helper(context, "callwindtest", token, encoded, mode)
                val launch = "$helper & xcertplay_wind_pid=\$!; " +
                    "echo XCERTPLAY callwind token=$token starting pid=\$xcertplay_wind_pid; " +
                    "wait \$xcertplay_wind_pid; " +
                    "echo XCERTPLAY callwind token=$token exited pid=\$xcertplay_wind_pid"
                adb.stream(launch) { line ->
                    if (line.startsWith("XCERTPLAY callwind token=$token ")) {
                        val message = line.substringAfter("token=$token ")
                        if (message.startsWith("requested ")) requested = true
                        if (message.startsWith("starting pid=")) {
                            message.substringAfter("pid=").substringBefore(' ').toIntOrNull()
                                ?.takeIf { it > 0 }?.let { pid = it }
                        }
                        if (message == "exited pid=$pid") processGone = true
                        if (message.startsWith("blocked:") || message == "cancelled before request") untouched = true
                        if (message == "released") released = true
                        if (!message.startsWith("heartbeat ")) {
                            if (lines.size == 32) lines.removeAt(0)
                            lines += message
                            report(message)
                        }
                    }
                }
            } catch (error: Exception) {
                val message = "failed: ${error.javaClass.simpleName} ${error.message.orEmpty().take(160)}"
                lines += message
                report(message)
            } finally {
                heartbeat?.interrupt()
                heartbeat = null
                synchronized(stateLock) { file?.delete() }
                client?.close()
                client = null
                if (!processGone) {
                    processGone = pid?.let { runCatching { confirmOrTerminate(it) }.getOrDefault(false) } == true
                }
                report("stop complete processGone=$processGone released=$released")
                done.complete(processGone && (released || untouched || !launched))
            }
            return Result(requested, released, stopping, lines.joinToString("\n"), processGone)
        }

        private fun confirmOrTerminate(pid: Int): Boolean = LocalAdb(AdbKeys.load(context)).use { adb ->
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return@use false
            val match = "*${BydVehicleDataTool::class.java.name}*callwindtest*$token*"
            val command = "case \"\$(tr '\\000' ' ' < /proc/$pid/cmdline 2>/dev/null)\" in " +
                "$match) kill -TERM $pid; sleep 1;; esac; " +
                "case \"\$(tr '\\000' ' ' < /proc/$pid/cmdline 2>/dev/null)\" in " +
                "$match) kill -KILL $pid; sleep 0.1;; esac; " +
                "if [ ! -d /proc/$pid ]; then echo XCERTPLAY-wind-gone; fi"
            adb.shell(command)?.lineSequence()?.any { it.trim() == "XCERTPLAY-wind-gone" } == true
        }

        private fun prepareFile(): File? = synchronized(stateLock) {
            if (stopping) return@synchronized null
            val directory = context.getExternalFilesDir("call-wind-test")
                ?: throw IOException("test directory unavailable")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create test directory")
            File(directory, "$token.request").also { file = it; it.writeText("$token\n") }
        }

        private fun startAutomaticLease(path: File) {
            if (!automatic) return
            heartbeat = thread(name = "xcertplay-call-wind-lease", isDaemon = true) {
                while (!stopping && path.isFile) {
                    path.setLastModified(System.currentTimeMillis())
                    try { Thread.sleep(CallWindProbe.POLL_MS * 10) } catch (_: InterruptedException) {
                        return@thread
                    }
                }
            }
        }

        private fun report(message: String) {
            runCatching {
                log("Call wind ${if (automatic) "automatic" else "test"}: token=$token $message")
            }
        }
    }
}
