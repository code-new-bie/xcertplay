package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydCameraStateTest {
    @Test fun helperLinesParseIncludingUnreadableFields() {
        assertEquals(BydCameraReading(1, 5, 2), BydCameraReading.parse("XCERTPLAY camera 1 5 2"))
        assertEquals(BydCameraReading(null, null, 4), BydCameraReading.parse("XCERTPLAY camera - - 4"))
        assertNull(BydCameraReading.parse("XCERTPLAY speed 1 2 3"))
        assertNull(BydCameraReading.parse("XCERTPLAY camera 1 2"))
        assertEquals(BydCameraReading(0, 1, 1, 3), BydCameraReading.parse("XCERTPLAY camera 0 1 1 3"))
        assertEquals(BydCameraReading(0, 1, 1, null), BydCameraReading.parse("XCERTPLAY camera 0 1 1 -"))
        assertNull(BydCameraReading.parse("XCERTPLAY camera 0 1 1 3 9"))
    }

    @Test fun switchingTheCarOffIsConfirmedOnceAfterTheHold() {
        val detector = BydPowerOffDetector(confirmMs = 1_000)
        assertFalse(detector.update(power(OK, gear = 1), 0))
        assertFalse("Not yet held", detector.update(power(OFF, gear = 1), 100))
        assertFalse(detector.update(power(OFF, gear = 1), 900))
        assertTrue(detector.update(power(OFF, gear = 1), 1_100))
        assertFalse("Fires once", detector.update(power(OFF, gear = 1), 3_100))
    }

    @Test fun aShortDropToOffIsNotASwitchOff() {
        val detector = BydPowerOffDetector(confirmMs = 1_000)
        detector.update(power(OK, gear = 4), 0)
        assertFalse(detector.update(power(OFF, gear = 4), 100))
        assertFalse(detector.update(power(OK, gear = 4), 400))
        assertFalse("The hold starts again", detector.update(power(OFF, gear = 1), 1_200))
        assertTrue(detector.update(power(OFF, gear = 1), 2_300))
    }

    @Test fun offInADrivingGearOrWithoutAnEarlierOnLevelDoesNotFire() {
        val driving = BydPowerOffDetector(confirmMs = 0)
        driving.update(power(OK, gear = 4), 0)
        assertFalse(driving.update(power(OFF, gear = 4), 100))
        assertTrue("Unknown gear does not block", driving.update(power(OFF, gear = null), 200))

        val startedOff = BydPowerOffDetector(confirmMs = 0)
        assertFalse(startedOff.update(power(OFF, gear = 1), 0))
        assertFalse(startedOff.update(power(INVALID, gear = 1), 100))
        assertFalse(startedOff.update(power(null, gear = 1), 200))
        assertFalse(startedOff.update(power(OFF, gear = 1), 300))
    }

    @Test fun theCarCanBeSwitchedOffAgainAfterComingBackOn() {
        val detector = BydPowerOffDetector(confirmMs = 0)
        detector.update(power(ON, gear = 1), 0)
        assertTrue(detector.update(power(OFF, gear = 1), 100))
        detector.update(power(ON, gear = 1), 200)
        assertTrue(detector.update(power(OFF, gear = 1), 300))
    }

    private fun power(level: Int?, gear: Int?) = BydCameraReading(0, 1, gear, level)

    private companion object {
        const val OFF = 0
        const val ON = 2
        const val OK = 3
        const val INVALID = 255
    }

    @Test fun panoramaWorkingOrReverseGearMeansShown() {
        assertTrue(BydCameraReading(1, 0, 4).shown) // panorama on while in D
        assertTrue(BydCameraReading(null, null, 2).shown) // reverse, no panorama unit
        assertFalse(BydCameraReading(0, 1, 4).shown)
        assertFalse(BydCameraReading(null, null, null).shown)
    }

    @Test fun shownLastsForTheHoldAfterTheCameraCloses() {
        val state = BydCameraState(holdMs = 3_000)
        assertFalse(state.hasReading())
        assertFalse(state.shown(0))
        assertTrue(state.update(BydCameraReading(1, 5, 2), 1_000))
        assertTrue(state.shown(1_000))
        assertTrue(state.update(BydCameraReading(0, 1, 4), 2_000))
        assertTrue("The window grows back after the camera closes", state.shown(4_000))
        assertFalse(state.shown(4_001))
    }

    @Test fun repeatedReadingsAreNotChangesAndClearForgetsTheCamera() {
        val state = BydCameraState()
        assertTrue(state.update(BydCameraReading(1, 5, 2), 0))
        assertFalse(state.update(BydCameraReading(1, 5, 2), 2_000))
        state.clear()
        assertFalse(state.hasReading())
        assertFalse(state.shown(2_001))
    }
}
