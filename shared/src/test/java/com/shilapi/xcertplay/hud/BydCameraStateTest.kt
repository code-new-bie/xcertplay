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
