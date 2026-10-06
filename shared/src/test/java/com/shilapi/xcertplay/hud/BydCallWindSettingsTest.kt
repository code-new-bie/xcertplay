package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.airplay.CarPlayAppStates
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydCallWindSettingsTest {
    @Test fun defaultsOffAndStoresTheOptInSeparatelyFromLyrics() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(BydCallWindSettings.enabled(context))
        BydCallWindSettings.setEnabled(context, true)
        assertTrue(BydCallWindSettings.enabled(context))
        assertFalse(BydClusterSongSettings.enabled(context))
        BydCallWindSettings.setEnabled(context, false)
        assertFalse(BydCallWindSettings.enabled(context))
    }

    @Test fun targetLevelDefaultsToTwoAndStaysWithinOneToThree() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(2, BydCallWindSettings.targetLevel(context))
        BydCallWindSettings.setTargetLevel(context, 1)
        assertEquals(1, BydCallWindSettings.targetLevel(context))
        BydCallWindSettings.setTargetLevel(context, 7)
        assertEquals(3, BydCallWindSettings.targetLevel(context))
        BydCallWindSettings.setTargetLevel(context, 0)
        assertEquals(1, BydCallWindSettings.targetLevel(context))
    }

    @Test fun activeCarPlayCallBlocksManualTesting() {
        val owner = Any()
        BydCallWindTest.observeCall(owner, true)
        try {
            assertFalse(BydCallWindTest.start(RuntimeEnvironment.getApplication(), {}, {
                fail("A test must not start during a CarPlay call")
            }))
            BydCallWindTest.clearCall(Any()) // An old controller must not clear this call.
            assertFalse(BydCallWindTest.start(RuntimeEnvironment.getApplication(), {}, {}))
        } finally {
            BydCallWindTest.clearCall(owner)
        }
    }

    @Test fun phoneCallStateIncludesWechatButDoesNotTreatSiriAsACall() {
        assertTrue(CarPlayAppStates.phoneCallActive(listOf(mapOf("appStateID" to 2, "entity" to 1)))!!)
        assertFalse(CarPlayAppStates.phoneCallActive(listOf(mapOf("appStateID" to 2, "entity" to 0)))!!)
        assertNull(CarPlayAppStates.phoneCallActive(listOf(mapOf("appStateID" to 1, "entity" to 1))))
        assertNull(CarPlayAppStates.phoneCallActive(null))
    }
}
