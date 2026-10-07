package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class RtpSequenceTrackerTest {
    private fun packet(sequence: Int) = ByteArray(16).also {
        it[2] = (sequence shr 8).toByte()
        it[3] = sequence.toByte()
    }

    @Test fun consecutivePacketsCountNoLoss() {
        val tracker = RtpSequenceTracker()
        (100..110).forEach { tracker.onPacket(packet(it)) }
        assertEquals("received=11 lost=0 late=0 duplicate=0", tracker.describe())
    }

    @Test fun gapsLatePacketsAndDuplicatesAreCountedSeparately() {
        val tracker = RtpSequenceTracker()
        listOf(1, 2, 5, 3, 5, 6).forEach { tracker.onPacket(packet(it)) }
        assertEquals(6, tracker.received)
        assertEquals(2, tracker.lost) // 3 and 4 were missing when 5 arrived.
        assertEquals(1, tracker.late) // 3 arrived after 5.
        assertEquals(1, tracker.duplicate)
    }

    @Test fun sequenceWrapAroundIsNotLoss() {
        val tracker = RtpSequenceTracker()
        listOf(65_534, 65_535, 0, 1).forEach { tracker.onPacket(packet(it)) }
        assertEquals(0, tracker.lost)
        assertEquals(0, tracker.late)
    }

    @Test fun packetsShorterThanAnRtpHeaderAreIgnored() {
        val tracker = RtpSequenceTracker()
        tracker.onPacket(ByteArray(4))
        assertEquals(0, tracker.received)
    }
}
