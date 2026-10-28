package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlaySplitScreenTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun theExpectedWindowMatchesTheWindowsDiPlaySawOnATang() {
        // 2560x1440, bars of 112 and 120 px, a 20 px divider: 1270x1208 side by side, 1440x1154 stacked.
        val landscape = requireNotNull(CarPlaySplitScreen.expectedWindow(false, 2560, 1440, 232, 20))
        assertEquals(1270 to 1208, CarPlaySplitScreen.canvasSize(landscape, 2560, 1440, 1.0))
        val portrait = requireNotNull(CarPlaySplitScreen.expectedWindow(true, 2560, 1440, 232, 20))
        assertEquals(1440 to 1154, CarPlaySplitScreen.canvasSize(portrait, 1440, 2560, 1.0))
        assertNull(CarPlaySplitScreen.expectedWindow(false, 0, 1080, 0, 0))
    }

    @Test
    fun aSeenWindowIsRememberedPerScreenOrientation() {
        assertNull(CarPlaySplitScreen.window(app, portrait = false))
        assertTrue(CarPlaySplitScreen.remember(app, 950, 1000, 1920, 1080))
        assertFalse("The same window again", CarPlaySplitScreen.remember(app, 950, 1000, 1920, 1080))
        val seen = requireNotNull(CarPlaySplitScreen.window(app, portrait = false))
        assertEquals(950 to 1000, CarPlaySplitScreen.canvasSize(seen, 1920, 1080, 1.0))
        assertNull(CarPlaySplitScreen.window(app, portrait = true))
        // At a lower resolution the area keeps the window's shape.
        assertEquals(475 to 500, CarPlaySplitScreen.canvasSize(seen, 1920, 1080, 0.5))
    }

    @Test
    fun anImplausibleWindowIsNotRemembered() {
        assertFalse(CarPlaySplitScreen.remember(app, 100, 1000, 1920, 1080))
        assertFalse(CarPlaySplitScreen.remember(app, 950, 1000, 0, 0))
        assertNull(CarPlaySplitScreen.window(app, portrait = false))
    }
}
