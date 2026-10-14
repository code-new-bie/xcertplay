package com.shilapi.xcertplay.media

/**
 * Measures the RTP clock of a received audio stream: the timestamp step between two consecutive
 * packets divided by the audio the earlier one carried. Opus is decoded at 48 kHz whatever format
 * the iPhone chose, and the playback position reported in /feedback counts at 48 kHz; DiPlay found
 * that the 16 and 24 kHz Opus formats count their timestamps at 16 and 24 kHz instead. This only
 * measures, so a session log can settle which clock the iPhone uses.
 */
internal class RtpClockProbe(private val samplesNeeded: Int = DEFAULT_SAMPLES) {
    private var lastSequence = -1
    private var lastTimestamp = 0L
    private var lastDurationUs = 0L
    private val rates = ArrayList<Long>(samplesNeeded)
    private var done = false

    /**
     * Notes one packet ([timestamp] as the unsigned 32-bit RTP value, [durationUs] of audio). Returns
     * the clock in Hz once, after [samplesNeeded] consecutive pairs; null before and after that.
     * Lost, reordered or empty packets are skipped rather than counted.
     */
    fun onPacket(sequence: Int, timestamp: Long, durationUs: Long): Long? {
        if (done) return null
        if (lastSequence >= 0 && ((sequence - lastSequence) and 0xffff) == 1 && lastDurationUs > 0) {
            val step = (timestamp - lastTimestamp) and 0xffff_ffffL
            if (step in 1 until HALF_RANGE) rates += step * 1_000_000L / lastDurationUs
        }
        lastSequence = sequence and 0xffff
        lastTimestamp = timestamp and 0xffff_ffffL
        lastDurationUs = durationUs
        if (rates.size < samplesNeeded) return null
        done = true
        return rates.sorted()[rates.size / 2]
    }

    private companion object {
        const val DEFAULT_SAMPLES = 20
        const val HALF_RANGE = 0x8000_0000L
    }
}
