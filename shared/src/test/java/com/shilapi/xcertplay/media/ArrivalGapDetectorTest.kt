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
}
