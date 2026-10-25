package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.VideoInCar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoInCarGateTest {
    @After
    fun reset() {
        VideoInCar.allowed = false
    }

    @Test
    fun videoIsAllowedOnlyWhileTheGearReadsPark() {
        val changes = mutableListOf<Boolean>()
        val gate = VideoInCarGate({ null }, onChanged = { changes += it })

        gate.update(null) // no ADB: stays off
        gate.update(false)
        gate.update(true)
        gate.update(true)
        gate.update(null) // unreadable while parked: off again
        gate.update(false)

        assertEquals(listOf(true, false), changes)
        assertFalse(VideoInCar.allowed)
    }

    @Test
    fun closingTheGateTurnsVideoOff() {
        val gate = VideoInCarGate({ true }, onChanged = {})
        gate.update(true)
        gate.close()
        gate.update(true)

        assertFalse(VideoInCar.allowed)
    }

    @Test
    fun observationsDistinguishUnknownParkAndNotPark() {
        val observations = mutableListOf<Boolean?>()
        val gate = VideoInCarGate({ null }, {}, observations::add)

        gate.update(null)
        gate.update(null)
        gate.update(false)
        gate.update(true)

        assertEquals(listOf(null, false, true), observations)
    }

    @Test
    fun aShortGapInTheGearKeepsVideoAndALongOneEndsIt() {
        var now = 0L
        val changes = mutableListOf<Boolean>()
        val gate = VideoInCarGate({ null }, onChanged = { changes += it }, nowMillis = { now })

        gate.update(true)
        now = 1_000
        gate.update(null) // the ADB helper restarting
        now = 3_000
        gate.update(true)
        now = 5_000
        gate.update(null)
        now = 14_999
        gate.update(null)
        assertTrue(VideoInCar.allowed)
        now = 15_000
        gate.update(null)

        assertEquals(listOf(true, false), changes)
        assertFalse(VideoInCar.allowed)
    }

    @Test
    fun anotherGearEndsVideoAtOnceEvenDuringAGap() {
        val changes = mutableListOf<Boolean>()
        val gate = VideoInCarGate({ null }, onChanged = { changes += it }, nowMillis = { 0L })

        gate.update(true)
        gate.update(null)
        gate.update(false)

        assertEquals(listOf(true, false), changes)
    }
}
