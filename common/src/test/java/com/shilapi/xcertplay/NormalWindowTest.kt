package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalWindowTest {
    private val full = NormalWindow(1920, 1080, 0, hideTopBar = false, hideBottomBar = false)

    @Test fun cameraWindowWithTheSameLayoutIsAShrink() {
        assertTrue(full.shrinksTo(full.copy(width = 1280)))
        assertTrue(full.shrinksTo(full.copy(height = 720)))
        assertFalse("Same size is not a shrink", full.shrinksTo(full))
        assertFalse("Bigger is not a shrink", full.shrinksTo(full.copy(width = 2000)))
        assertFalse("Other bars", full.shrinksTo(full.copy(width = 1280, hideTopBar = true)))
        assertFalse("Other rotation", full.shrinksTo(full.copy(width = 1280, rotation = 1)))
    }

    @Test fun firstWindowIsRecordedAndOnlyGrowsWithinOneLayout() {
        assertEquals(full, NormalWindow.next(null, full))
        assertNull("A shrink seen before the camera state must not replace it", NormalWindow.next(full, full.copy(width = 1280)))
        assertNull(NormalWindow.next(full, full))
        val bigger = full.copy(height = 1100)
        assertEquals(bigger, NormalWindow.next(full, bigger))
    }

    @Test fun aNewLayoutReplacesTheWindowAndEmptySizesAreIgnored() {
        val barsHidden = full.copy(height = 1000, hideBottomBar = true)
        assertEquals(barsHidden, NormalWindow.next(full, barsHidden))
        assertNull(NormalWindow.next(full, full.copy(width = 0)))
    }
}
