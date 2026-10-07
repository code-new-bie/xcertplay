package com.shilapi.xcertplay.media

/**
 * Start-up buffer for CarPlay's alternate audio stream (type 101), which carries navigation
 * prompts. The iPhone asks for 80 ms; a longer buffer rides out short Wi-Fi gaps at the cost of
 * starting each prompt slightly later. The default keeps the iPhone's value.
 */
object NavigationAudioBuffer {
    const val MIN_DURATION_MS = 80
    const val MAX_DURATION_MS = 300
    const val STEP_DURATION_MS = 20
    const val DEFAULT_DURATION_MS = MIN_DURATION_MS

    /** Time allowed beyond the buffer to play out a prompt after the iPhone tears it down. */
    const val DRAIN_MARGIN_MS = 200L

    private const val STREAM_TYPE_ALT_AUDIO = 101
    private const val BYTES_PER_PCM_16_SAMPLE = 2L
    private const val MILLIS_PER_SECOND = 1_000L

    /** Clamps [durationMs] into range and snaps it to the nearest [STEP_DURATION_MS] multiple. */
    fun sanitizeDurationMs(durationMs: Int): Int {
        val clamped = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        val steps = (clamped - MIN_DURATION_MS + STEP_DURATION_MS / 2) / STEP_DURATION_MS
        return MIN_DURATION_MS + steps * STEP_DURATION_MS
    }

    /** True for the stream whose start-up buffer this setting controls. */
    fun isPromptStream(payloadType: Int): Boolean = payloadType == STREAM_TYPE_ALT_AUDIO

    /** Bytes of 16-bit PCM buffered before a prompt starts playing. */
    fun startBytes(durationMs: Int, sampleRate: Int, channelCount: Int): Int {
        require(sampleRate > 0)
        require(channelCount > 0)
        return (sampleRate.toLong() * channelCount * BYTES_PER_PCM_16_SAMPLE *
            sanitizeDurationMs(durationMs) / MILLIS_PER_SECOND).toInt()
    }

    /** Longest wait for a torn-down prompt to finish playing before it is released. */
    fun drainLimitMs(durationMs: Int): Long = sanitizeDurationMs(durationMs) + DRAIN_MARGIN_MS
}
