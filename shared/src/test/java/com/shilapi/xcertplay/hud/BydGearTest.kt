package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.transport.VehicleGear
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

    @Test
    fun theWheelSpeedGearCountsOnlyWhileFresh() {
        BydSpeedGear.clear()
        assertNull(BydSpeedGear.parked(1_000))
        BydSpeedGear.note(VehicleGear.PARK, 1_000)
        assertEquals(true, BydSpeedGear.parked(1_000 + BydSpeedGear.FRESH_MILLIS))
        assertNull(BydSpeedGear.parked(1_001 + BydSpeedGear.FRESH_MILLIS))
        BydSpeedGear.note(VehicleGear.DRIVE, 5_000)
        assertEquals(false, BydSpeedGear.parked(5_500))
        BydSpeedGear.clear()
        assertNull(BydSpeedGear.parked(5_500))
    }

    @Test
    fun earlyHelperFailuresRetrySooner() {
        assertEquals(5_000L, BydSdkStream.retryDelayMillis(1))
        assertEquals(5_000L, BydSdkStream.retryDelayMillis(2))
        assertEquals(30_000L, BydSdkStream.retryDelayMillis(3))
        assertEquals(30_000L, BydSdkStream.retryDelayMillis(10))
    }
}
