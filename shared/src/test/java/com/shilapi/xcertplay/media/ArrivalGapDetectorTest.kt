package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArrivalGapDetectorTest {
    @Test
    fun onlyGapsBetweenTheThresholdAndIdleCountAsStalls() {
        val detector = ArrivalGapDetector(thresholdMs = 700, idleMs = 3_000)
        assertNull(detector.onArrival(0))
        assertNull(detector.onArrival(400))
        assertEquals(800L, detector.onArrival(1_200))
        // A long pause is the sender going idle (static screen), not a stall.
        assertNull(detector.onArrival(5_000))
        detector.reset()
        assertNull(detector.onArrival(9_000))
    }

    @Test
    fun aStaticScreenRefreshingAboutOnceASecondIsNotAStall() {
        val detector = ArrivalGapDetector.video()
        // The pattern of the wired Qin PLUS log: one or two frames, then about 0.9 s of nothing.
        var now = 0L
        repeat(20) {
            assertNull(detector.onArrival(now))
            assertNull(detector.onArrival(now + 40))
            now += 950
        }
    }

    @Test
    fun aPauseInAMovingPictureIsAStall() {
        val detector = ArrivalGapDetector.video()
        var now = 0L
        repeat(30) {
            assertNull(detector.onArrival(now))
            now += 33
        }
        val lastFrame = now - 33
        assertEquals(900L, detector.onArrival(lastFrame + 900))
    }

    @Test
    fun onlyTheSecondBeforeThePauseDecidesWhetherThePictureWasMoving() {
        val detector = ArrivalGapDetector.video()
        // Motion that ended long before the pause does not count.
        var now = 0L
        repeat(30) {
            detector.onArrival(now)
            now += 33
        }
        detector.onArrival(2_000)
        assertNull(detector.onArrival(2_900))
        // Motion starting again makes the next pause a stall.
        now = 3_000
        repeat(12) {
            detector.onArrival(now)
            now += 50
        }
        assertEquals(800L, detector.onArrival(now - 50 + 800))
    }
}
