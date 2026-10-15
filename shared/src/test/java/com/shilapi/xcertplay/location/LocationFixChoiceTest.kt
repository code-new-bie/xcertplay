package com.shilapi.xcertplay.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationFixChoiceTest {
    private data class Fix(val provider: String, val ageMillis: Long)

    private fun choose(vararg fixes: Fix) = LocationFixChoice.choose(
        fixes = fixes.toList(),
        provider = { it.provider },
        ageMillis = { it.ageMillis },
        maxAgeMillis = 30_000L,
    )

    @Test
    fun aFreshGpsFixWinsOverANewerNetworkFix() {
        assertEquals(Fix("gps", 900), choose(Fix("gps", 900), Fix("network", 100)))
    }

    @Test
    fun aNetworkFixStandsInOnlyWhileGpsIsStale() {
        assertEquals(Fix("network", 100), choose(Fix("gps", 5_000), Fix("network", 100)))
        assertEquals(Fix("gps", 5_000), choose(Fix("gps", 5_000), Fix("network", 8_000)))
    }

    @Test
    fun fixesOlderThanTheLimitOrFromTheFutureAreNotUsed() {
        assertNull(choose(Fix("gps", 31_000), Fix("network", 40_000)))
        assertEquals(Fix("network", 1_000), choose(Fix("gps", -500), Fix("network", 1_000)))
    }
}
