package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtpClockProbeTest {
    private val frameUs = 20_000L

    /** Feeds [count] consecutive 20 ms packets whose timestamps step by [step]; the last result. */
    private fun RtpClockProbe.feed(
        count: Int,
        step: Long,
        firstSequence: Int = 100,
        firstTimestamp: Long = 1_000L,
    ): Long? {
        var result: Long? = null
        for (i in 0 until count) {
            val measured = onPacket(
                sequence = (firstSequence + i) and 0xffff,
                timestamp = (firstTimestamp + i * step) and 0xffff_ffffL,
                durationUs = frameUs,
            )
            if (measured != null) result = measured
        }
        return result
    }

    @Test
    fun a16KhzOpusClockIsMeasured() {
        assertEquals(16_000L, RtpClockProbe().feed(count = 21, step = 320))
    }

    @Test
    fun a48KhzOpusClockIsMeasured() {
        assertEquals(48_000L, RtpClockProbe().feed(count = 21, step = 960))
    }

    @Test
    fun theClockIsReportedOnlyOnceAndNotBeforeEnoughPairs() {
        val probe = RtpClockProbe()
        assertNull(probe.feed(count = 20, step = 320))
        assertEquals(16_000L, probe.feed(count = 1, step = 320, firstSequence = 120, firstTimestamp = 1_000L + 20 * 320))
        assertNull(probe.feed(count = 30, step = 320, firstSequence = 121, firstTimestamp = 1_000L + 21 * 320))
    }

    @Test
    fun sequenceAndTimestampWrapAreHandled() {
        assertEquals(
            24_000L,
            RtpClockProbe().feed(count = 21, step = 480, firstSequence = 65_530, firstTimestamp = 0xffff_ff00L),
        )
    }

    @Test
    fun aGapInTheSequenceIsNotCounted() {
        val probe = RtpClockProbe(samplesNeeded = 3)
        assertNull(probe.onPacket(1, 0, frameUs))
        assertNull(probe.onPacket(2, 320, frameUs))
        // Packet 3 was lost: 4 follows 2 with a two-packet step, which must not count.
        assertNull(probe.onPacket(4, 960, frameUs))
        assertNull(probe.onPacket(5, 1_280, frameUs))
        assertEquals(16_000L, probe.onPacket(6, 1_600, frameUs))
    }

    @Test
    fun emptyOrReorderedPacketsAreSkipped() {
        val probe = RtpClockProbe(samplesNeeded = 2)
        assertNull(probe.onPacket(1, 0, 0))
        // No duration on the previous packet: nothing to divide by.
        assertNull(probe.onPacket(2, 320, frameUs))
        // A late packet with an older timestamp gives no usable step.
        assertNull(probe.onPacket(3, 0, frameUs))
        assertNull(probe.onPacket(4, 320, frameUs))
        assertEquals(16_000L, probe.onPacket(5, 640, frameUs))
    }
}
