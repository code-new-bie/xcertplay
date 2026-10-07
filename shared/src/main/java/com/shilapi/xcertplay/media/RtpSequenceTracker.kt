package com.shilapi.xcertplay.media

/**
 * Counts audio packets by RTP sequence number, for diagnostics only: playback still takes packets
 * in arrival order. A packet older than the newest one seen is counted as late (reordered or
 * resent), so a late packet is also counted once in [lost] when its gap was first seen.
 */
internal class RtpSequenceTracker {
    var received = 0L
        private set
    var lost = 0L
        private set
    var late = 0L
        private set
    var duplicate = 0L
        private set
    private var newest = -1

    fun onPacket(rtp: ByteArray) {
        if (rtp.size < RTP_HEADER_BYTES) return
        val sequence = ((rtp[2].toInt() and 0xff) shl 8) or (rtp[3].toInt() and 0xff)
        received++
        if (newest < 0) {
            newest = sequence
            return
        }
        val delta = (sequence - newest) and 0xffff
        when {
            delta == 0 -> duplicate++
            delta < HALF_RANGE -> {
                lost += delta - 1
                newest = sequence
            }
            else -> late++
        }
    }

    fun describe(): String = "received=$received lost=$lost late=$late duplicate=$duplicate"

    private companion object {
        const val RTP_HEADER_BYTES = 12
        const val HALF_RANGE = 0x8000
    }
}
