package com.shilapi.xcertplay.media

/**
 * Reports a pause between packet arrivals that looks like a radio stall rather than the sender going
 * idle: at least [thresholdMs] but shorter than [idleMs], after the sender was active, with at least
 * [minActiveArrivals] arrivals in the [activeWindowMs] before the pause. A static screen sends a
 * frame or two a second, so its pauses are not stalls; a stall cuts off a moving picture.
 */
internal class ArrivalGapDetector(
    private val thresholdMs: Long,
    private val idleMs: Long = 3_000L,
    private val activeWindowMs: Long = 1_000L,
    private val minActiveArrivals: Int = 0,
) {
    private val recent = ArrayDeque<Long>()

    /** Records an arrival at [nowMs]; returns the gap before it when it counts as a stall. */
    @Synchronized
    fun onArrival(nowMs: Long): Long? {
        val previous = recent.lastOrNull()
        if (previous != null) {
            while (recent.first() < previous - activeWindowMs) recent.removeFirst()
        }
        val arrivalsBeforePause = recent.size
        recent.addLast(nowMs)
        if (previous == null) return null
        val gap = nowMs - previous
        if (gap !in thresholdMs until idleMs) return null
        return gap.takeIf { arrivalsBeforePause >= minActiveArrivals }
    }

    @Synchronized
    fun reset() {
        recent.clear()
    }

    companion object {
        /** A video pause this long is a visible freeze. */
        const val VIDEO_THRESHOLD_MS = 700L
        /** A moving picture: about 10 fps or more in the second before the pause. */
        const val VIDEO_ACTIVE_WINDOW_MS = 1_000L
        const val VIDEO_MIN_ACTIVE_FRAMES = 10

        fun video() = ArrivalGapDetector(
            VIDEO_THRESHOLD_MS,
            activeWindowMs = VIDEO_ACTIVE_WINDOW_MS,
            minActiveArrivals = VIDEO_MIN_ACTIVE_FRAMES,
        )
    }
}
