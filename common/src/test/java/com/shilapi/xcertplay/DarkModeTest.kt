package com.shilapi.xcertplay

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkModeTest {
    @Test
    fun darkModeIsDetectedWithUnrelatedConfigurationBits() {
        assertTrue(isDarkMode(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_CAR))
    }

    @Test
    fun lightAndUndefinedModesAreNotDark() {
        assertFalse(isDarkMode(Configuration.UI_MODE_NIGHT_NO))
        assertFalse(isDarkMode(Configuration.UI_MODE_NIGHT_UNDEFINED))
    }

    @Test
    fun fixedVehicleModeOverridesAndroidAndDayNightSignal() {
        assertFalse(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_YES, "1", "0"))
        assertTrue(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_NO, "2", "1"))
    }

    @Test
    fun automaticVehicleModeFollowsActualDayNightSignal() {
        assertTrue(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_NO, "0", "0"))
        assertFalse(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_YES, "0", "1"))
    }

    @Test
    fun missingVehicleStateFallsBackToAndroid() {
        assertTrue(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_YES, null, "1"))
        assertFalse(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_NO, "0", null))
        assertTrue(resolveCarPlayDarkMode(Configuration.UI_MODE_NIGHT_YES, "unexpected", "1"))
    }
}
