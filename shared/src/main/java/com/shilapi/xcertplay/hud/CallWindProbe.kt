package com.shilapi.xcertplay.hud

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface CallWindBridge {
    fun unavailableReason(): String?
    fun request()
    fun release()
}

/** File timestamps are only change counters; lease expiry uses a monotonic clock. */
internal class CallWindLease(private val now: () -> Long) {
    private var stamp: Long? = null
    private var changedAt = now()

    @Synchronized fun alive(exists: Boolean, modified: Long): Boolean {
        if (!exists) return false
        val current = now()
        if (stamp != modified) {
            stamp = modified
            changedAt = current
        }
        return current - changedAt <= CallWindProbe.AUTO_LEASE_MS
    }
}

/** Serializes entry and release; cancellation is visible before waiting for a Binder call. */
internal class CallWindProbe(
    private val bridge: CallWindBridge,
    private val keepRunning: () -> Boolean,
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    private val emit: (String) -> Unit,
) {
    private val requested = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val operationLock = Any()
    private val releasing = AtomicBoolean(false)
    private val released = CountDownLatch(1)
    private val releaseResult = AtomicReference("release pending")

    fun run(durationMs: Long = DURATION_MS): String {
        bridge.unavailableReason()?.let { return "blocked: $it" }
        if (!keepRunning()) return "cancelled before request"
        val deadline = if (durationMs > 0) now() + durationMs else Long.MAX_VALUE
        try {
            synchronized(operationLock) {
                if (cancelled.get() || !keepRunning()) return "cancelled before request"
                requested.set(true) // A failed call may have reached the service; still release it.
                bridge.request()
            }
            emit("requested durationMs=${if (durationMs > 0) durationMs else "until-call-end"}")
            var nextHeartbeat = now()
            while (!cancelled.get() && keepRunning() && now() < deadline) {
                if (now() >= nextHeartbeat) {
                    emit("heartbeat remainingMs=${(deadline - now()).coerceAtLeast(0)}")
                    nextHeartbeat = now() + 1_000
                }
                sleep(POLL_MS)
            }
            return if (!cancelled.get() && keepRunning()) "completed" else "cancelled"
        } finally {
            emit(releaseOnce())
        }
    }

    fun releaseOnce(): String {
        cancelled.set(true)
        return synchronized(operationLock) { releaseLocked() }
    }

    private fun releaseLocked(): String {
        if (!requested.get()) return "not requested"
        if (releasing.compareAndSet(false, true)) {
            try {
                bridge.release()
                releaseResult.set("released")
            } catch (error: Exception) {
                releaseResult.set("release failed: ${error.javaClass.simpleName}")
            } finally {
                released.countDown()
            }
        } else {
            released.await(1_000, TimeUnit.MILLISECONDS)
        }
        return releaseResult.get()
    }

    companion object {
        const val DURATION_MS = 10_000L
        const val POLL_MS = 100L
        const val AUTO_LEASE_MS = 5_000L
    }
}
