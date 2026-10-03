package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiChannelPreferenceTest {
    @Test fun frequenciesMatchChannelNumbers() {
        assertEquals(2412, WifiChannelPreference.frequency(1))
        assertEquals(2484, WifiChannelPreference.frequency(14))
        assertEquals(5180, WifiChannelPreference.frequency(36))
        assertEquals(5500, WifiChannelPreference.frequency(100))
        assertEquals(5885, WifiChannelPreference.frequency(177))
    }

    @Test fun automaticAndInvalidChannelsHaveNoFrequency() {
        for (channel in listOf(0, -1, 15, 35, 37, 200)) {
            assertNull(WifiChannelPreference.frequency(channel))
            assertEquals(WifiChannelPreference.AUTOMATIC, WifiChannelPreference.sanitize(channel))
        }
    }

    @Test fun requestedChannelFallsBackToAutomaticBand() {
        assertEquals(listOf(5500, null), WifiChannelPreference.creationFrequencies(100))
        assertEquals(listOf<Int?>(null), WifiChannelPreference.creationFrequencies(0))
        assertEquals(listOf<Int?>(null), WifiChannelPreference.creationFrequencies(35))
    }
}
