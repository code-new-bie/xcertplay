package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CarPlayVideoLayoutTest {
    @Test fun sameAspectRatioFillsTheWindow() {
        assertEquals(CarPlayVideoLayout(0f, 0f, 960f, 495f), CarPlayVideoLayout.fit(1920, 990, 960, 495))
    }

    @Test fun reducedHeightAddsSideBarsWithoutDistortion() {
        val content = CarPlayVideoLayout.fit(1920, 990, 1920, 942)
        assertEquals(942f, content.height, 0.001f)
        assertEquals(0f, content.top, 0.001f)
        assertEquals((1920f - content.width) / 2, content.left, 0.001f)
        assertEquals(1920f / 990, content.width / content.height, 0.0001f)
        assertFalse(content.contains(1f, 471f))
        assertTrue(content.contains(960f, 471f))
    }

    @Test fun narrowSurroundViewWindowAddsTopAndBottomBars() {
        val content = CarPlayVideoLayout.fit(1920, 990, 700, 990)
        assertEquals(700f, content.width, 0.001f)
        assertEquals(0f, content.left, 0.001f)
        assertEquals((990f - content.height) / 2, content.top, 0.001f)
        assertEquals(1920f / 990, content.width / content.height, 0.0001f)
        assertFalse(content.contains(350f, 1f))
    }

    @Test fun returningToTheOriginalWindowRemovesTheBars() {
        CarPlayVideoLayout.fit(1920, 990, 700, 990)
        assertEquals(CarPlayVideoLayout(0f, 0f, 1920f, 990f), CarPlayVideoLayout.fit(1920, 990, 1920, 990))
    }

    @Test fun theLandscapeAreaOfASquareFillsALandscapeWindow() {
        val content = CarPlayVideoLayout.fitArea(1920, 1920, 0, 0, 1920, 1080, 1920, 1080)
        assertEquals(CarPlayVideoLayout(0f, 0f, 1920f, 1920f), content)
        assertTrue("The window's corners map into the landscape area", content.contains(1919f, 1079f))
    }

    @Test fun thePortraitAreaOfASquareFillsAPortraitWindow() {
        assertEquals(CarPlayVideoLayout(0f, 0f, 1920f, 1920f), CarPlayVideoLayout.fitArea(1920, 1920, 0, 0, 1080, 1920, 1080, 1920))
    }

    @Test fun anAreaScalesToALargerWindowAndStaysCentred() {
        val content = CarPlayVideoLayout.fitArea(1920, 1920, 0, 0, 1920, 1080, 2560, 1440)
        assertEquals(0f, content.left, 0.001f)
        assertEquals(0f, content.top, 0.001f)
        assertEquals(2560f, content.width, 0.001f)
        // A slightly shorter window keeps the area's proportions with bars at the sides.
        val shorter = CarPlayVideoLayout.fitArea(1920, 1920, 0, 0, 1080, 1920, 1080, 1800)
        assertEquals(1800f / 1920, shorter.width / 1920f, 0.0001f)
        assertEquals((1080f - 1080f * 1800f / 1920f) / 2f, shorter.left, 0.001f)
    }

    @Test fun anAreaAwayFromTheOriginShiftsTheCanvasOutOfTheWindow() {
        val content = CarPlayVideoLayout.fitArea(1920, 1920, 840, 0, 1080, 1920, 1080, 1920)
        assertEquals(-840f, content.left, 0.001f)
        assertEquals(1920f, content.width, 0.001f)
    }
}
