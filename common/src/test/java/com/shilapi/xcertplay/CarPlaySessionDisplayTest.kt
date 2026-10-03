package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlaySessionDisplayTest {
    // 80% canvas inside a 1920x1080 startup window.
    private val display = CarPlaySessionDisplay(1536, 864, 0, true, true, 1920, 1080)

    @Test fun shrinkingAndRestoringCameraWindowKeepsScaledSession() {
        for ((width, height) in listOf(960 to 1080, 720 to 480, 1920 to 1080)) {
            assertTrue(display.canKeepSession(width, height, 0, true, true))
        }
    }

    @Test fun actualRotationAndSystemBarChangesRequireNegotiation() {
        assertFalse(display.canKeepSession(1080, 1920, 1, true, true))
        assertFalse(display.canKeepSession(1920, 1080, 0, false, true))
        assertFalse(display.canKeepSession(1920, 1080, 0, true, false))
    }

    @Test fun startupInCameraWindowMustRecoverFullCanvasWhenItExpands() {
        val narrow = CarPlaySessionDisplay(720, 480, 0, true, true, 900, 600)
        assertFalse(narrow.canKeepSession(1920, 1080, 0, true, true))
        assertFalse(display.canKeepSession(0, 1080, 0, true, true))
    }
}
