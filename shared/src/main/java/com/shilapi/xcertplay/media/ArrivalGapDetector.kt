package com.shilapi.xcertplay.media

/**
 * Reports a pause between packet arrivals that looks like a radio stall rather than the sender going
 * idle: at least [thresholdMs], but shorter than [idleMs] (a static screen or paused audio). Pauses
 * within [warmupMs] of the first packet are ignored: a new stream arrives in bursts while it starts.
 */
internal class ArrivalGapDetector(
    private val thresholdMs: Long,
    private val idleMs: Long = 3_000L,
    private val warmupMs: Long = 0L,
) {
    private var firstMs = -1L
    private var lastMs = -1L

    /** Records an arrival at [nowMs]; returns the gap before it when it counts as a stall. */
    @Synchronized
    fun onArrival(nowMs: Long): Long? {
        val previous = lastMs
        lastMs = nowMs
        if (previous < 0) {
            firstMs = nowMs
            return null
        }
        if (nowMs - firstMs < warmupMs) return null
        val gap = nowMs - previous
        return gap.takeIf { it in thresholdMs until idleMs }
    }

    @Synchronized
    fun reset() {
        firstMs = -1L
        lastMs = -1L
    }

    companion object {
        /** Video arrives at up to 60 fps while the screen changes. */
        const val VIDEO_THRESHOLD_MS = 300L
        /** Media audio packets arrive every ~23 ms. */
        const val AUDIO_THRESHOLD_MS = 250L
        /** A new audio stream arrives in bursts for its first seconds. */
        const val AUDIO_WARMUP_MS = 3_000L
    }
}

/**
 * Video alone pauses whenever the screen is static, so a video pause only counts as a link stall
 * when audio paused over the same time; it is then reported once.
 */
internal class LinkStallCorrelator(private val minOverlapMs: Long = 200L) {
    private var videoGap: LongRange? = null
    private var audioGap: LongRange? = null

    /** Records a video pause ending at [endMs]; returns the overlap with an audio pause, if any. */
    @Synchronized
    fun onVideoGap(endMs: Long, gapMs: Long): Long? {
        val gap = (endMs - gapMs)..endMs
        return overlap(gap, audioGap)?.also { audioGap = null } ?: run { videoGap = gap; null }
    }

    /** Records an audio pause ending at [endMs]; returns the overlap with a video pause, if any. */
    @Synchronized
    fun onAudioGap(endMs: Long, gapMs: Long): Long? {
        val gap = (endMs - gapMs)..endMs
        return overlap(gap, videoGap)?.also { videoGap = null } ?: run { audioGap = gap; null }
    }

    private fun overlap(a: LongRange, b: LongRange?): Long? {
        if (b == null) return null
        val length = minOf(a.last, b.last) - maxOf(a.first, b.first)
        return length.takeIf { it >= minOverlapMs }
    }
}
