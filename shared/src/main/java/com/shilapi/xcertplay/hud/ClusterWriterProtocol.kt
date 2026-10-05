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
        const val MAILBOX_FAILURE_GRACE_MS = 1_000L
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
        var mailboxUnavailableSince: Long? = null
        var mailboxUnavailableReported = false
        try {
            while (true) {
                val frame = read()
                val timestamp = now()
                if (frame == null) {
                    if (mailboxUnavailableSince == null) {
                        mailboxUnavailableSince = timestamp
                        if (!mailboxUnavailableReported) {
                            emit("XCERTPLAY clusterwriter mailbox unavailable token=$token " +
                                "graceMs=${ClusterWriterFrame.MAILBOX_FAILURE_GRACE_MS}")
                            mailboxUnavailableReported = true
                        }
                    }
                    if (timestamp - checkNotNull(mailboxUnavailableSince) >=
                        ClusterWriterFrame.MAILBOX_FAILURE_GRACE_MS
                    ) {
                        reason = "mailbox unavailable"
                        break
                    }
                    sleep(ClusterWriterFrame.POLL_MS)
                    continue
                }
                if (mailboxUnavailableSince != null) {
                    emit("XCERTPLAY clusterwriter mailbox recovered token=$token " +
                        "downtimeMs=${timestamp - checkNotNull(mailboxUnavailableSince)}")
                    mailboxUnavailableSince = null
                    mailboxUnavailableReported = false
                }
                val stop = frame.stopReason(token, timestamp)
                if (stop != null) { reason = stop; break }
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

/**
 * Watches the mailbox independently of SDK calls. A short read gap is recoverable, while an
 * explicit stop or replaced session remains terminal even if an obsolete publisher refreshes the
 * old lease.
 */
internal class ClusterWriterWatchdog(private val token: String) {
    private var stopSince: Long? = null
    private var reason: String? = null
    private var terminal = false

    fun shouldTryClear(nowMs: Long): Boolean = stopSince?.let {
        // Missing/partially replaced files are allowed to recover before the card is cleared.
        // Lease expiry and explicit stop are already sustained conditions by this point.
        reason != "mailbox unavailable" &&
            nowMs - it >= ClusterWriterFrame.FORCE_STOP_GRACE_MS / 2
    } ?: false

    fun stopReason(): String? = reason

    fun shouldTerminate(frame: ClusterWriterFrame?, nowMs: Long): Boolean {
        val currentReason = if (frame == null) "mailbox unavailable" else frame.stopReason(token, nowMs)
        if (frame?.stop == true || currentReason == "session replaced") {
            terminal = true
        }
        if (terminal) {
            if (stopSince == null) stopSince = nowMs
            reason = currentReason
        } else if (currentReason == "mailbox unavailable" || currentReason == "owner heartbeat expired") {
            if (stopSince == null) stopSince = nowMs
            reason = currentReason
        } else {
            // A transient read gap or lease observation recovered before the grace period.
            stopSince = null
            reason = null
        }
        return stopSince?.let { nowMs - it >= ClusterWriterFrame.FORCE_STOP_GRACE_MS } ?: false
    }
}
