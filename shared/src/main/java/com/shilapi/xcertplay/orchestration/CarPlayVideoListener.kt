package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.iap2.message.Iap2MediaRemoteCommand
import java.io.Closeable

/** iOS 27 video in car, played by the host in the car's own player (see [VideoInCar]). */
interface CarPlayVideoListener {
    /** Whether the car is in P, or null when it cannot tell. Read every [VideoInCarGate.POLL_MILLIS] while CarPlay runs. */
    fun readParked(): Boolean?

    /** Calls [onChange] (any thread) when the gear may have changed, to check it at once; null stops. */
    fun watchGear(onChange: (() -> Unit)?) {}

    /** Video became allowed or not; when not, the player must close. Any thread. */
    fun onVideoAllowedChanged(allowed: Boolean)

    /** A playback message from the iPhone, e.g. insertPlayQueueItem, setRate, seek, stop. Any thread. */
    fun onVideoMessage(streamId: Long, message: Map<String, Any?>)

    /** The iPhone asks the car to show its video player (requestUI "videoplayback:"). Any thread. */
    fun onVideoUiRequested()

    /** The CarPlay session ended; the player must close. Any thread. */
    fun onVideoSessionEnded()

    /**
     * A steering-wheel media key: true when the car's video player is open and took it. The iPhone
     * must not get it then, as a CarPlay play/pause makes it end the video session. Any thread.
     */
    fun onMediaKey(command: Iap2MediaRemoteCommand): Boolean = false
}

/**
 * Keeps [VideoInCar.allowed] in step with the car: allowed only while the gear reads P. Another gear
 * ends video at once. An unknown gear (no reading at all) keeps video off, but one that goes unknown
 * for under [UNKNOWN_GRACE_MILLIS] keeps the last decision: the ADB helper restarts every five
 * minutes and has no reading for about two seconds. Changes go to [onChanged].
 */
internal class VideoInCarGate(
    private val readParked: () -> Boolean?,
    private val onChanged: (Boolean) -> Unit,
    private val onObserved: (Boolean?) -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : Closeable {
    @Volatile private var closed = false
    private var observed = false
    private var lastParked: Boolean? = null
    private var unknownSinceMillis: Long? = null

    fun start() {
        Thread({
            while (!closed) {
                update(runCatching(readParked).getOrNull())
                try {
                    Thread.sleep(POLL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "xcertplay-video-gate").apply { isDaemon = true }.start()
    }

    /** Reads the gear now, for a change signalled between polls. Any thread. */
    fun refresh() {
        if (!closed) update(runCatching(readParked).getOrNull())
    }

    @Synchronized
    internal fun update(parked: Boolean?) {
        if (!observed || parked != lastParked) {
            observed = true
            lastParked = parked
            onObserved(parked)
        }
        val allowed = when (parked) {
            true, false -> {
                unknownSinceMillis = null
                parked
            }
            null -> {
                val now = nowMillis()
                val since = unknownSinceMillis ?: now.also { unknownSinceMillis = it }
                VideoInCar.allowed && now - since < UNKNOWN_GRACE_MILLIS
            }
        }
        if (allowed == VideoInCar.allowed || closed) return
        VideoInCar.allowed = allowed
        onChanged(allowed)
    }

    override fun close() {
        closed = true
        VideoInCar.allowed = false
    }

    companion object {
        /** Gear changes are also signalled at once (watchGear); the poll covers a source without events. */
        const val POLL_MILLIS = 500L
        /** Longer than the ADB helper's restart, short enough to stop video soon after losing the gear. */
        const val UNKNOWN_GRACE_MILLIS = 10_000L
    }
}
