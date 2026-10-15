package com.shilapi.xcertplay.media

import java.util.Locale
import kotlin.math.abs

/**
 * How fast the head unit plays a stream against how fast the iPhone sends it, both timed on this
 * device's monotonic clock. A head unit whose audio clock runs fast drains the buffer by the
 * difference and pauses to refill it every minute or so: a Humax unit played 0.76 % fast (DiPlay
 * dfd7c3d6, #610). This only measures; the stats line shows it once both spans reach
 * [MIN_WINDOW_NANOS]. A gap or jump in the packets (pause, seek, new track) or an underrun starts
 * the measurement again, and the figures sharpen as a stretch of steady playback grows. The first
 * seconds after a start are left out, since the iPhone may send faster then to fill the buffer.
 */
internal class AudioClockDrift(private val sampleRate: Int) {
    private var lastRawRtp = -1L
    private var rtpWraps = 0L
    private var receiving = false
    private var receivingSinceNs = 0L
    private var lastNs = 0L
    private var lastRtp = 0L
    private var sending = false
    private var sendStartNs = 0L
    private var sendStartRtp = 0L
    private var playing = false
    private var playStartNs = 0L
    private var playStartFrames = 0L
    private var playStartUnderruns = 0

    /** A packet with RTP timestamp [rtpTimestamp] arrived from the iPhone at [nowNs]. */
    @Synchronized
    fun onPacket(rtpTimestamp: Int, nowNs: Long) {
        val raw = rtpTimestamp.toLong() and UINT32_MASK
        if (lastRawRtp >= 0 && raw < lastRawRtp && lastRawRtp - raw > UINT32_HALF) rtpWraps++
        lastRawRtp = raw
        val rtp = rtpWraps * UINT32_MODULUS + raw
        if (receiving) {
            // A late or repeated packet says nothing new about the iPhone's pace.
            if (rtp <= lastRtp) return
            val arrivedNs = nowNs - lastNs
            val mediaNs = (rtp - lastRtp) * NANOS_PER_SECOND / sampleRate
            if (arrivedNs > MAX_GAP_NANOS || abs(mediaNs - arrivedNs) > MAX_GAP_NANOS) restart()
        }
        if (!receiving) {
            receiving = true
            receivingSinceNs = nowNs
        }
        lastNs = nowNs
        lastRtp = rtp
        if (!sending && nowNs - receivingSinceNs >= SETTLE_NANOS) {
            sending = true
            sendStartNs = nowNs
            sendStartRtp = rtp
        }
    }

    /**
     * The track has played [playedFrames] by [nowNs] with [underruns] so far; returns the figures for
     * the stats line, or null while either span is still short.
     */
    @Synchronized
    fun describe(playedFrames: Long, underruns: Int, nowNs: Long): String? {
        if (!playing || underruns != playStartUnderruns || playedFrames < playStartFrames) {
            playing = true
            playStartNs = nowNs
            playStartFrames = playedFrames
            playStartUnderruns = underruns
            return null
        }
        val sendSpanNs = lastNs - sendStartNs
        val playSpanNs = nowNs - playStartNs
        if (!sending || sendSpanNs < MIN_WINDOW_NANOS || playSpanNs < MIN_WINDOW_NANOS) return null
        val sendHz = (lastRtp - sendStartRtp).toDouble() * NANOS_PER_SECOND / sendSpanNs
        val playHz = (playedFrames - playStartFrames).toDouble() * NANOS_PER_SECOND / playSpanNs
        return String.format(
            Locale.US,
            "sendHz=%.1f playHz=%.1f clockDiffPct=%+.3f clockWindowSec=%d",
            sendHz,
            playHz,
            (playHz / sendHz - 1.0) * 100.0,
            minOf(sendSpanNs, playSpanNs) / NANOS_PER_SECOND,
        )
    }

    private fun restart() {
        receiving = false
        sending = false
        playing = false
    }

    private companion object {
        const val UINT32_MASK = 0xffff_ffffL
        const val UINT32_MODULUS = 0x1_0000_0000L
        const val UINT32_HALF = 0x8000_0000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MAX_GAP_NANOS = 1_000_000_000L
        const val SETTLE_NANOS = 10_000_000_000L
        const val MIN_WINDOW_NANOS = 60_000_000_000L
    }
}
