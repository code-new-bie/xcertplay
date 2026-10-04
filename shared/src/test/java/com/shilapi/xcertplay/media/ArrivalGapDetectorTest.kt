package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArrivalGapDetectorTest {
    @Test
    fun onlyGapsBetweenTheThresholdAndIdleCountAsStalls() {
        val detector = ArrivalGapDetector(thresholdMs = 300, idleMs = 3_000)
        assertNull(detector.onArrival(0))
        assertNull(detector.onArrival(17))
        assertEquals(400L, detector.onArrival(417))
        // A long pause is the sender going idle (static screen), not a stall.
        assertNull(detector.onArrival(5_000))
        detector.reset()
        assertNull(detector.onArrival(9_000))
    }

    @Test
    fun audioPausesWhileAStreamStartsAreIgnored() {
        val detector = ArrivalGapDetector(thresholdMs = 250, warmupMs = 3_000)
        assertNull(detector.onArrival(0))
        assertNull(detector.onArrival(400))
        assertNull(detector.onArrival(2_900))
        assertEquals(400L, detector.onArrival(3_300))
    }

    @Test
    fun onlyAudioAndVideoPausingTogetherIsALinkStall() {
        val correlator = LinkStallCorrelator(minOverlapMs = 200)
        // Video alone (static screen) is not a link stall.
        assertNull(correlator.onVideoGap(endMs = 1_000, gapMs = 600))
        // Audio paused 500..900 overlaps the video pause 400..1000 by 400 ms.
        assertEquals(400L, correlator.onAudioGap(endMs = 900, gapMs = 400))
        // Reported once.
        assertNull(correlator.onAudioGap(endMs = 950, gapMs = 300))
    }
}
