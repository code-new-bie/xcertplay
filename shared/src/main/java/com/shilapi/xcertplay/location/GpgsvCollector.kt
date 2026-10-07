package com.shilapi.xcertplay.location

/**
 * Keeps the newest complete `$GPGSV` group (GPS satellites in view) from the GNSS chip's own NMEA
 * output, so it can be sent next to the encoded GGA/RMC. A group is sentences 1..N of N in order;
 * an incomplete or out-of-order group is dropped, and a group older than [maxAgeMs] is not used.
 */
internal class GpgsvCollector(private val maxAgeMs: Long = MAX_AGE_MS) {
    private val pending = mutableListOf<String>()
    private var latest: String? = null
    private var latestAtMs = 0L

    @Synchronized
    fun onSentence(sentence: String, nowMs: Long) {
        val line = sentence.trim()
        if (!line.startsWith(PREFIX) || '*' !in line) return
        val fields = line.substringBefore('*').split(',')
        val total = fields.getOrNull(1)?.toIntOrNull() ?: return
        val number = fields.getOrNull(2)?.toIntOrNull() ?: return
        if (total !in 1..MAX_SENTENCES || number !in 1..total) return
        if (number == 1) pending.clear()
        if (pending.size != number - 1) {
            pending.clear()
            return
        }
        pending += line
        if (number == total) {
            latest = pending.joinToString("") { "$it\r\n" }
            latestAtMs = nowMs
            pending.clear()
        }
    }

    @Synchronized
    fun latest(nowMs: Long): String? = latest?.takeIf { nowMs - latestAtMs in 0..maxAgeMs }

    @Synchronized
    fun clear() {
        pending.clear()
        latest = null
    }

    private companion object {
        const val PREFIX = "\$GPGSV,"
        const val MAX_SENTENCES = 9
        const val MAX_AGE_MS = 5_000L
    }
}
