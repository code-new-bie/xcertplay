package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.Iap2CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayAppStatesTest {
    @Test
    fun appStatesReadAsWhoOwnsCallSiriAndNavigation() {
        val states = listOf(
            mapOf("appStateID" to 2L, "entity" to 1L),
            mapOf("appStateID" to 1L, "entity" to 1L, "speechMode" to 1L),
            mapOf("appStateID" to 3L, "entity" to 0L),
        )
        assertEquals(
            "CarPlay app state: phoneCall=iPhone speech=iPhone(speaking) navigation=off",
            CarPlayAppStates.describe(states),
        )
        assertNull(CarPlayAppStates.describe(null))
    }

    @Test
    fun callNumbersKeepOnlyTheLastFourDigits() {
        assertEquals("*9876", Iap2CallState.mask("19876"))
        assertEquals("*0010", Iap2CallState.mask("10010"))
        assertEquals("****5678", Iap2CallState.mask("12345678"))
        assertEquals("none", Iap2CallState.mask(null))
    }
}
