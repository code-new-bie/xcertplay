package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal interface ClusterWriterShell : Closeable {
    fun connect(): Boolean
    fun shell(command: String): String?
    fun stream(command: String, onLine: (String) -> Unit)
}

internal interface ClusterSongSessionHandle {
    fun start()
    fun update(card: ClusterCard?)
    fun stop(reason: String): CompletableFuture<Boolean>
}

private class AdbClusterWriterShell(context: Context) : ClusterWriterShell {
    private val adb = LocalAdb(AdbKeys.load(context))
    override fun connect() = adb.connect(mayAsk = false) == LocalAdb.Access.READY
    override fun shell(command: String) = adb.shell(command)
    override fun stream(command: String, onLine: (String) -> Unit) = adb.stream(command, onLine)
    override fun close() = adb.close()
}

/** Owns one latest-value slot and a bounded helper-restart budget. */
internal class ClusterSongWriterSession(
    private val context: Context,
    initial: ClusterCard?,
    private val diagnostic: (String) -> Unit,
    private val newShell: () -> ClusterWriterShell = { AdbClusterWriterShell(context) },
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : ClusterSongSessionHandle {
    @Volatile var token: String = UUID.randomUUID().toString().replace("-", "")
        private set
    private val lock = Any()
    private val publisher = ScheduledThreadPoolExecutor(1) {
        Thread(it, "xcertplay-cluster-slot").apply { isDaemon = true }
    }
    private val readerDone = CountDownLatch(1)
    private val stopped = CompletableFuture<Boolean>()
    private var started = false
    private var stopping = false
    private var latest = initial ?: ClusterCard.EMPTY
    private var sequence = 0L
    private var changedMs = now()
    private var publishedSequence = -1L
    private var updateScheduled = false
    private var lastPublishedMs = -ClusterWriterFrame.HEARTBEAT_MS
    private var mailbox: ClusterWriterMailbox? = null
    @Volatile private var client: ClusterWriterShell? = null
    @Volatile private var helperPid: Int? = null
    @Volatile private var helperGone = true
    private var recovering = false
    private var helperRestarts = 0

    override fun start() {
        synchronized(lock) {
            if (started || stopping) return
            started = true
            try {
                val directory = context.getExternalFilesDir("cluster") ?: throw IOException("external app directory unavailable")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cluster directory unavailable")
                mailbox = ClusterWriterMailbox(File(directory, "$token.state"))
                publishLocked()
            } catch (error: Exception) {
                report("start unavailable: ${error.javaClass.simpleName} ${error.message}")
                stop("mailbox creation failed")
                readerDone.countDown()
                return
            }
            publisher.scheduleWithFixedDelay(::publish, ClusterWriterFrame.POLL_MS,
                ClusterWriterFrame.POLL_MS, TimeUnit.MILLISECONDS)
        }
        thread(name = "xcertplay-cluster-reader", isDaemon = true) { readHelper() }
    }

    override fun update(card: ClusterCard?) = synchronized(lock) {
        if (stopping) return@synchronized
        val next = card ?: ClusterCard.EMPTY
        if (next == latest) return@synchronized
        latest = next
        sequence++
        changedMs = now()
        if (started && !updateScheduled) {
            updateScheduled = true
            publisher.execute {
                synchronized(lock) { updateScheduled = false }
                publish()
            }
        }
    }

    /** Stop is terminal, including when it races authentication or startup. */
    override fun stop(reason: String): CompletableFuture<Boolean> {
        synchronized(lock) {
            if (stopping) return stopped
            stopping = true
            publisher.shutdownNow()
            sequence++
            if (!started) {
                stopped.complete(true)
                return stopped
            }
            try { publishLocked() } catch (error: Exception) {
                report("stop frame failed: ${error.javaClass.simpleName} ${error.message}")
                mailbox?.delete() // A missing slot also expires the helper.
            }
        }
        report("stop requested reason=$reason pid=${helperPid ?: "pending"}")
        thread(name = "xcertplay-cluster-stop", isDaemon = true) { finishStop() }
        return stopped
    }

    private fun publish() {
        try {
            synchronized(lock) { if (!stopping && !recovering) publishLocked() }
        } catch (error: Exception) {
            report("mailbox update failed: ${error.javaClass.simpleName} ${error.message}")
            stop("mailbox update failed")
        }
    }

    private fun publishLocked() {
        val timestamp = now()
        if (!stopping && publishedSequence == sequence && timestamp - lastPublishedMs < ClusterWriterFrame.HEARTBEAT_MS) return
        val card = if (stopping) ClusterCard.EMPTY else latest
        val state = if (card == ClusterCard.EMPTY) 3 else if (card.playing) 1 else 2
        mailbox?.publish(ClusterWriterFrame(token, sequence, timestamp, changedMs, state, stopping, card.text))
        publishedSequence = sequence
        lastPublishedMs = timestamp
    }

    private fun readHelper() {
        var stopAfterReader = false
        try {
            while (true) {
                if (synchronized(lock) { stopping }) break
                runHelperAttempt()
                synchronized(lock) { recovering = true }
                if (!helperGone) {
                    val pid = helperPid
                    helperGone = pid != null && runCatching { confirmOrTerminate(pid) }.getOrDefault(false)
                }
                if (!helperGone) {
                    report("helper exit unconfirmed; restart blocked pid=${helperPid ?: "unknown"}")
                    stopAfterReader = true
                    break
                }
                val restart = synchronized(lock) {
                    if (stopping || helperRestarts >= MAX_HELPER_RESTARTS) false
                    else {
                        helperRestarts++
                        true
                    }
                }
                if (!restart) {
                    stopAfterReader = true
                    report("helper restart budget exhausted restarts=$helperRestarts")
                    break
                }
                report("helper ended unexpectedly; restarting attempt=$helperRestarts/$MAX_HELPER_RESTARTS")
                try {
                    Thread.sleep(HELPER_RESTART_BACKOFF_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    stopAfterReader = true
                    break
                }
                synchronized(lock) {
                    if (!stopping) {
                        val directory = checkNotNull(mailbox).file.parentFile
                        mailbox?.delete()
                        token = UUID.randomUUID().toString().replace("-", "")
                        mailbox = ClusterWriterMailbox(File(directory, "$token.state"))
                        helperPid = null
                        publishedSequence = -1
                        recovering = false
                        publishLocked()
                    }
                }
            }
        } catch (error: Exception) {
            report("helper recovery failed: ${error.javaClass.simpleName} ${error.message}")
            stopAfterReader = true
        } finally {
            readerDone.countDown()
            if (stopAfterReader) stop("helper ended after restart budget")
        }
    }

    /** Runs one shell/helper attempt. Returns true when the session is already stopping. */
    private fun runHelperAttempt(): Boolean {
        var shell: ClusterWriterShell? = null
        try {
            synchronized(lock) { if (stopping) return true }
            shell = newShell().also { client = it }
            if (!shell.connect()) throw IOException("ADB shell unavailable (authorize ADB first)")
            val path = checkNotNull(mailbox).file.absolutePath
            val quotedPath = "'${path.replace("'", "'\\''")}'"
            if (shell.shell("if [ -r $quotedPath ]; then echo XCERTPLAY-readable; fi")?.trim() != "XCERTPLAY-readable") {
                throw IOException("ADB shell cannot read the app's cluster mailbox")
            }
            synchronized(lock) {
                if (stopping) return true
                helperGone = false
            }
            val encodedPath = Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))
            val helper = BydSdkStream.helper(context, "clustersongwatch", token, encodedPath)
            // The shell reports the child PID before Java/SDK startup, so cancellation can target it immediately.
            val launch = "$helper & xcertplay_cluster_pid=\$!; " +
                "echo XCERTPLAY clusterwriter starting token=$token pid=\$xcertplay_cluster_pid; " +
                "wait \$xcertplay_cluster_pid; " +
                "echo XCERTPLAY clusterwriter exited token=$token pid=\$xcertplay_cluster_pid"
            shell.stream(launch) { line ->
                if (line.startsWith("XCERTPLAY clusterwriter starting token=$token pid=")) {
                    line.substringAfter("pid=").substringBefore(' ').toIntOrNull()?.takeIf { it > 0 }?.let {
                        helperPid = it
                    }
                }
                if (line == "XCERTPLAY clusterwriter exited token=$token pid=$helperPid") helperGone = true
                if (!line.startsWith("XCERTPLAY clusterwriter heartbeat")) report(line.removePrefix("XCERTPLAY "))
            }
            if (synchronized(lock) { stopping }) {
                return true
            }
            report("helper stream ended unexpectedly pid=${helperPid ?: "none"} processGone=$helperGone")
            return false
        } catch (error: Exception) {
            val stoppingNow = synchronized(lock) { stopping }
            if (!stoppingNow) {
                report("helper unavailable: ${error.javaClass.simpleName} ${error.message}")
            }
            return stoppingNow
        } finally {
            shell?.close()
            if (client === shell) client = null
        }
    }

    private fun finishStop() {
        var confirmed = false
        try {
            // Healthy helpers acknowledge STOP and close the ADB shell before this deadline.
            if (!readerDone.await(1_500, TimeUnit.MILLISECONDS)) {
                client?.close()
            }
            // Only the reader owns PID cleanup. Closing an ADB stream is not process-exit evidence.
            confirmed = readerDone.await(4_000, TimeUnit.MILLISECONDS) && helperGone
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            report("stop confirmation failed: ${error.javaClass.simpleName} ${error.message}")
        } finally {
            mailbox?.delete()
            report("stop complete pid=${helperPid ?: "none"} processGone=$confirmed")
            stopped.complete(confirmed)
        }
    }

    private fun confirmOrTerminate(pid: Int): Boolean = newShell().use { shell ->
        if (!shell.connect()) return@use false
        // Check both class/mode and the unique token before signalling a PID; never kill other helpers.
        val className = BydVehicleDataTool::class.java.name
        val command = "case \"\$(tr '\\000' ' ' < /proc/$pid/cmdline 2>/dev/null)\" in " +
            "*$className*clustersongwatch*$token*) kill -TERM $pid; sleep 1;; esac; " +
            "case \"\$(tr '\\000' ' ' < /proc/$pid/cmdline 2>/dev/null)\" in " +
            "*$className*clustersongwatch*$token*) kill -KILL $pid; sleep 0.1;; esac; " +
            "xcertplay_stop_checks=0; while [ -d /proc/$pid ] && [ \$xcertplay_stop_checks -lt 20 ]; do " +
            "sleep 0.1; xcertplay_stop_checks=\$((xcertplay_stop_checks + 1)); done; " +
            "if [ ! -d /proc/$pid ]; then echo XCERTPLAY-process-gone; fi"
        shell.shell(command)?.contains("XCERTPLAY-process-gone") == true
    }

    private fun report(message: String) {
        runCatching { diagnostic("Cluster song: token=$token $message") }
    }

    private companion object {
        const val MAX_HELPER_RESTARTS = 2
        const val HELPER_RESTART_BACKOFF_MS = 250L
    }
}
