package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayViewArea
import org.junit.Assert.assertEquals
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
}
