package com.shilapi.xcertplay.hud

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** A single latest-value slot, shared with the shell helper. Times use the same boot clock. */
internal data class ClusterWriterFrame(
    val token: String,
    val sequence: Long,
    val leaseMs: Long,
    val changedMs: Long,
    val state: Int,
    val stop: Boolean,
    val text: String,
) {
    fun encode(): String = listOf(
        "1", token, sequence, leaseMs, changedMs, state, if (stop) "stop" else "update",
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)),
    ).joinToString(" ") + "\n"

    fun stopReason(expectedToken: String, nowMs: Long): String? = when {
        token != expectedToken -> "session replaced"
        stop -> "stop requested"
        nowMs < leaseMs || nowMs - leaseMs > LEASE_TIMEOUT_MS -> "owner heartbeat expired"
        else -> null
    }

    companion object {
        const val POLL_MS = 100L
        const val HEARTBEAT_MS = 1_000L
        const val LEASE_TIMEOUT_MS = 3_000L
        const val FORCE_STOP_GRACE_MS = 1_000L
        const val MAX_FRAME_BYTES = 2_048L
        private val TOKEN = Regex("[a-f0-9]{32}")

        fun decode(value: String): ClusterWriterFrame? {
            val fields = value.trimEnd().split(' ')
            if (fields.size != 8 || fields[0] != "1" || !TOKEN.matches(fields[1])) return null
            val sequence = fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val lease = fields[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val changed = fields[4].toLongOrNull()?.takeIf { it in 0..lease } ?: return null
            val state = fields[5].toIntOrNull()?.takeIf { it in 1..3 } ?: return null
            if (fields[6] != "stop" && fields[6] != "update") return null
            val text = try {
                String(Base64.getDecoder().decode(fields[7]), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (text.toByteArray(Charsets.UTF_16LE).size > 255) return null
            return ClusterWriterFrame(fields[1], sequence, lease, changed, state, fields[6] == "stop", text)
        }
    }
}

internal class ClusterWriterMailbox(val file: File) {
    /** Rename within one directory: readers see the old complete frame or the new complete frame. */
    @Synchronized
    fun publish(frame: ClusterWriterFrame) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeText(frame.encode(), Charsets.UTF_8)
            replace(temporary)
        } finally {
            temporary.delete()
        }
    }

    private fun replace(temporary: File) {
        for (attempt in 0..4) {
            try {
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (error: AccessDeniedException) {
                // Windows readers can briefly prevent replacement; the Android path normally needs no retry.
                if (attempt == 4) throw error
                try { Thread.sleep(10L * (attempt + 1)) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while publishing cluster slot", interrupted)
                }
            }
        }
    }

    fun read(): ClusterWriterFrame? = try {
        if (!file.isFile || file.length() > ClusterWriterFrame.MAX_FRAME_BYTES) null
        else ClusterWriterFrame.decode(String(Files.readAllBytes(file.toPath()), Charsets.UTF_8))
    } catch (_: IOException) {
        null
    }

    @Synchronized
    fun delete() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }
}

/** The helper consumes only increasing sequences; lease refreshes never rewrite the card. */
internal class ClusterWriterLoop(
    private val token: String,
    private val read: () -> ClusterWriterFrame?,
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    private val apply: (ClusterWriterFrame) -> String,
    private val clear: () -> String,
    private val emit: (String) -> Unit,
) {
    fun run() {
        var sequence = -1L
        var lastHeartbeat = now()
        var reason = "helper failed"
        try {
            while (true) {
                val frame = read()
                val timestamp = now()
                val stop = frame?.stopReason(token, timestamp) ?: if (frame == null) "mailbox unavailable" else null
                if (stop != null) { reason = stop; break }
                checkNotNull(frame)
                if (frame.sequence > sequence) {
                    val result = apply(frame)
                    emit("XCERTPLAY clusterwriter applied token=$token seq=${frame.sequence} " +
                        "ageMs=${timestamp - frame.changedMs} skipped=${(frame.sequence - sequence - 1).coerceAtLeast(0)} $result")
                    sequence = frame.sequence
                }
                if (timestamp - lastHeartbeat >= ClusterWriterFrame.HEARTBEAT_MS) {
                    emit("XCERTPLAY clusterwriter heartbeat token=$token seq=$sequence")
                    lastHeartbeat = timestamp
                }
                sleep(ClusterWriterFrame.POLL_MS)
            }
        } finally {
            val result = clear()
            emit("XCERTPLAY clusterwriter stopped token=$token seq=$sequence reason=$reason $result")
        }
    }
}

/** Once stopping starts it is terminal, even if an obsolete publisher refreshes the old lease. */
internal class ClusterWriterWatchdog(private val token: String) {
    private var stopSince: Long? = null
    fun shouldTryClear(nowMs: Long): Boolean = stopSince?.let {
        nowMs - it >= ClusterWriterFrame.FORCE_STOP_GRACE_MS / 2
    } ?: false
    fun shouldTerminate(frame: ClusterWriterFrame?, nowMs: Long): Boolean {
        if (frame == null || frame.stopReason(token, nowMs) != null) {
            if (stopSince == null) stopSince = nowMs
        }
        return stopSince?.let { nowMs - it >= ClusterWriterFrame.FORCE_STOP_GRACE_MS } ?: false
    }
}
