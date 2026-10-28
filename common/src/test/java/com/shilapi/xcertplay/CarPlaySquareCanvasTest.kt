package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayViewArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlaySquareCanvasTest {
    @Test
    fun aLandscapeScreenGetsASquareWithBothOrientations() {
        val square = requireNotNull(CarPlaySquareCanvas.plan(1920, 1080))
        assertEquals(1920, square.side)
        assertEquals(AirPlayViewArea(0, 0, 1920, 1080), square.areas[CarPlaySquareCanvas.LANDSCAPE])
        assertEquals(AirPlayViewArea(0, 0, 1080, 1920), square.areas[CarPlaySquareCanvas.PORTRAIT])
        assertEquals(CarPlaySquareCanvas.LANDSCAPE, square.initialArea)
    }

    @Test
    fun aSessionStartedInPortraitStartsInThePortraitArea() {
        val square = requireNotNull(CarPlaySquareCanvas.plan(1080, 1920))
        assertEquals(1920, square.side)
        assertEquals(CarPlaySquareCanvas.PORTRAIT, square.initialArea)
    }

    @Test
    fun theWindowsShapePicksTheArea() {
        val square = requireNotNull(CarPlaySquareCanvas.plan(1344, 756))
        assertEquals(CarPlaySquareCanvas.LANDSCAPE, square.areaFor(1920, 1080))
        assertEquals(CarPlaySquareCanvas.PORTRAIT, square.areaFor(1080, 1920))
        assertEquals("1344x756", square.describe(CarPlaySquareCanvas.LANDSCAPE))
        assertEquals("756x1344", square.describe(CarPlaySquareCanvas.PORTRAIT))
    }

    @Test
    fun squareAndOversizedCanvasesKeepThePlainCanvas() {
        assertNull(CarPlaySquareCanvas.plan(1080, 1080))
        assertNull("2560 is too heavy as a square", CarPlaySquareCanvas.plan(2560, 1440))
        assertNull(CarPlaySquareCanvas.plan(0, 1080))
    }

    @Test
    fun splitAreasAreAddedPerScreenOrientationAndKeptEven() {
        val square = requireNotNull(CarPlaySquareCanvas.plan(1920, 1080)).withSplitAreas(951 to 1001, 1080 to 899)
        assertEquals(4, square.areas.size)
        assertEquals(AirPlayViewArea(0, 0, 950, 1000), square.areas[2])
        assertEquals(AirPlayViewArea(0, 0, 1080, 898), square.areas[3])
        assertEquals(2, square.splitLandscape)
        assertEquals(3, square.splitPortrait)
        assertTrue(square.isSplit(2))
        assertTrue(square.isPortrait(3))
        assertFalse(square.isPortrait(2))
        assertEquals(CarPlaySquareCanvas.LANDSCAPE, square.initialArea)
    }

    @Test
    fun aSplitAreaIsNeverLargerThanItsScreensWholeArea() {
        val square = requireNotNull(CarPlaySquareCanvas.plan(1920, 1080)).withSplitAreas(2000 to 1200, null)
        assertEquals(AirPlayViewArea(0, 0, 1920, 1080), square.areas[2])
        assertNull(square.splitPortrait)
    }

    @Test
    fun inSplitScreenTheScreensOrientationPicksTheSplitArea() {
        val square = requireNotNull(CarPlaySquareCanvas.plan(1920, 1080)).withSplitAreas(950 to 1000, 1080 to 900)
        // A landscape screen's split window is taller than wide, yet stays a landscape screen's area.
        assertEquals(2, square.areaFor(950, 1000, splitScreen = true, screenPortrait = false))
        assertEquals(2, square.areaFor(1010, 930, splitScreen = true, screenPortrait = false))
        assertEquals(3, square.areaFor(1080, 900, splitScreen = true, screenPortrait = true))
        // A window far from the split area's shape keeps the whole area of the screen's orientation.
        assertEquals(CarPlaySquareCanvas.LANDSCAPE, square.areaFor(600, 1000, splitScreen = true, screenPortrait = false))
        // Leaving split screen goes back by the window's shape.
        assertEquals(CarPlaySquareCanvas.LANDSCAPE, square.areaFor(1920, 1080))
        assertEquals(CarPlaySquareCanvas.PORTRAIT, square.areaFor(1080, 1920))
    }

    @Test
    fun withoutSplitAreasSplitScreenKeepsTheOldChoice() {
        val square = requireNotNull(CarPlaySquareCanvas.plan(1920, 1080))
        assertEquals(CarPlaySquareCanvas.PORTRAIT, square.areaFor(950, 1000, splitScreen = true, screenPortrait = false))
    }
}
