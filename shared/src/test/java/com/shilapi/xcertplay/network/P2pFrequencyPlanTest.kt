package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pFrequencyPlanTest {
    @Test
    fun aConnected5GhzStationIsSharedFirst() {
        assertEquals(listOf(5200, null, 5745, 5180), P2pFrequencyPlan.candidates(5200))
        assertEquals(listOf(5745, null, 5180), P2pFrequencyPlan.candidates(5745))
        assertNull(P2pFrequencyPlan.sharedRadioNote(5200))
    }

    @Test
    fun withoutAUsableStationChannelTheGroupStaysOn5Ghz() {
        val fiveGhzOnly = listOf(null, 5745, 5180)
        for (station in listOf(null, 2412, 2437, 2462, 5500, 5300)) {
            assertEquals(fiveGhzOnly, P2pFrequencyPlan.candidates(station))
        }
        assertNull(P2pFrequencyPlan.sharedRadioNote(null))
        assertTrue(P2pFrequencyPlan.sharedRadioNote(2437)!!.contains("2.4 GHz"))
        assertTrue(P2pFrequencyPlan.sharedRadioNote(5500)!!.contains("DFS"))
    }

    @Test
    fun everyExplicitCandidateIs5Ghz() {
        for (station in listOf(null, 2437, 5180, 5240, 5500, 5745, 5825)) {
            P2pFrequencyPlan.candidates(station).filterNotNull().forEach { frequency ->
                assertTrue("$frequency", frequency in 5150..5895)
            }
        }
    }
}
