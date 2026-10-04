package com.shilapi.xcertplay.media

/**
 * Reports a pause between packet arrivals that looks like a radio stall rather than the sender going
 * idle: at least [thresholdMs], but shorter than [idleMs] (a static screen sends nothing).
 */
internal class ArrivalGapDetector(
    private val thresholdMs: Long,
    private val idleMs: Long = 3_000L,
) {
    private var lastMs = -1L

    /** Records an arrival at [nowMs]; returns the gap before it when it counts as a stall. */
    @Synchronized
    fun onArrival(nowMs: Long): Long? {
        val previous = lastMs
        lastMs = nowMs
        if (previous < 0) return null
        val gap = nowMs - previous
        return gap.takeIf { it in thresholdMs until idleMs }
    }

    @Synchronized
    fun reset() {
        lastMs = -1L
    }

    companion object {
        /** A video pause this long is a visible freeze. */
        const val VIDEO_THRESHOLD_MS = 700L
    }
}
