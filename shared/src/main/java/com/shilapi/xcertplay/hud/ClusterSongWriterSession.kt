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

/** Owns exactly one helper launch. Updates replace one slot, never queue shell commands. */
internal class ClusterSongWriterSession(
    private val context: Context,
    initial: ClusterCard?,
    private val diagnostic: (String) -> Unit,
    private val newShell: () -> ClusterWriterShell = { AdbClusterWriterShell(context) },
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : ClusterSongSessionHandle {
    val token: String = UUID.randomUUID().toString().replace("-", "")
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
    @Volatile private var normalExit = false
    @Volatile private var helperPid: Int? = null
    @Volatile private var helperLaunched = false

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
            synchronized(lock) { if (!stopping) publishLocked() }
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
        var shell: ClusterWriterShell? = null
        try {
            synchronized(lock) { if (stopping) return }
            shell = newShell().also { client = it }
            if (!shell.connect()) throw IOException("ADB shell unavailable (authorize ADB first)")
            val path = checkNotNull(mailbox).file.absolutePath
            val quotedPath = "'${path.replace("'", "'\\''")}'"
            if (shell.shell("if [ -r $quotedPath ]; then echo XCERTPLAY-readable; fi")?.trim() != "XCERTPLAY-readable") {
                throw IOException("ADB shell cannot read the app's cluster mailbox")
            }
            synchronized(lock) {
                if (stopping) return
                helperLaunched = true
            }
            val encodedPath = Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))
            val helper = BydSdkStream.helper(context, "clustersongwatch", token, encodedPath)
            // The shell reports the child PID before Java/SDK startup, so cancellation can target it immediately.
            val launch = "$helper & xcertplay_cluster_pid=\$!; " +
                "echo XCERTPLAY clusterwriter starting token=$token pid=\$xcertplay_cluster_pid; " +
                "wait \$xcertplay_cluster_pid"
            shell.stream(launch) { line ->
                if (line.startsWith("XCERTPLAY clusterwriter starting token=$token pid=")) {
                    helperPid = line.substringAfter("pid=").substringBefore(' ').toIntOrNull()?.takeIf { it > 0 }
                }
                if (!line.startsWith("XCERTPLAY clusterwriter heartbeat")) report(line.removePrefix("XCERTPLAY "))
            }
            normalExit = true
        } catch (error: Exception) {
            if (!synchronized(lock) { stopping }) report("helper unavailable: ${error.javaClass.simpleName} ${error.message}")
        } finally {
            shell?.close()
            client = null
            readerDone.countDown()
            stop(if (normalExit) "helper ended" else "ADB link ended")
        }
    }

    private fun finishStop() {
        var confirmed = false
        try {
            // Healthy helpers acknowledge STOP and close the ADB shell before this deadline.
            readerDone.await(1_500, TimeUnit.MILLISECONDS)
            if (normalExit || !helperLaunched) {
                client?.close()
                confirmed = readerDone.await(500, TimeUnit.MILLISECONDS)
            } else {
                client?.close()
                mailbox?.delete()
                // The helper's independent watchdog kills a stuck SDK call after its lease expires.
                val pid = helperPid
                if (pid != null) confirmed = confirmOrTerminate(pid)
                else report("stop awaiting watchdog: helper PID unavailable")
            }
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
}
