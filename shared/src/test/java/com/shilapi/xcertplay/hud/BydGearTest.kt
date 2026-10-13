package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BydGearTest {
    @Test
    fun onlyPIsParked() {
        assertEquals(true, BydGear.parked(1))
        (2..6).forEach { assertEquals("gear $it", false, BydGear.parked(it)) }
    }

    @Test
    fun anUnknownGearIsNeitherParkedNorDriving() {
        assertNull(BydGear.parked(null))
        assertNull(BydGear.parked(0))
        assertNull(BydGear.parked(255))
    }

    @Test
    fun theCameraStateKeepsTheLastGearUntilCleared() {
        val state = BydCameraState()
        assertNull(state.gear())
        state.update(BydCameraReading(0, 1, 1, 2), 0)
        assertEquals(1, state.gear())
        state.update(BydCameraReading(0, 1, 4, 2), 300)
        assertEquals(4, state.gear())
        state.clear()
        assertNull(state.gear())
    }
}
