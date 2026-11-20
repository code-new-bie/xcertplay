package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayDisplaySettingsTest {
    @Test
    fun windowNoLargerThanTheDisplayIsPlausible() {
        assertTrue(AirPlayDisplaySettings.isPlausibleWindowSize(1920, 1080, 1920))
        assertTrue(AirPlayDisplaySettings.isPlausibleWindowSize(1080, 1920, 1920))
        assertTrue(AirPlayDisplaySettings.isPlausibleWindowSize(960, 1000, 1920))
    }

    @Test
    fun windowWiderOrTallerThanTheDisplayIsNotPlausible() {
        assertFalse(AirPlayDisplaySettings.isPlausibleWindowSize(3392, 1920, 1920))
        assertFalse(AirPlayDisplaySettings.isPlausibleWindowSize(1920, 2160, 1920))
        assertFalse(AirPlayDisplaySettings.isPlausibleWindowSize(0, 1080, 1920))
    }

    @Test
    fun unknownDisplaySizeAcceptsAnyPositiveWindow() {
        assertTrue(AirPlayDisplaySettings.isPlausibleWindowSize(3392, 1920, 0))
        assertFalse(AirPlayDisplaySettings.isPlausibleWindowSize(0, 1920, 0))
    }

    @Test
    fun maximumObservedWidthScalesPhysicalSizeWithCurrentActivityWidth() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 768,
            currentHeightPixels = 800,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
        )

        assertEquals(80, size.widthMm)
        assertEquals(83, size.heightMm)
    }

    @Test
    fun maximumObservedHeightScalesPhysicalSizeWithCurrentActivityHeight() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 978,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.HEIGHT,
        )

        assertEquals(356, size.widthMm)
        assertEquals(181, size.heightMm)
    }

    @Test
    fun maximumResolutionKeepsConfiguredReferenceLength() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 1080,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 250,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
        )

        assertEquals(250, size.widthMm)
        assertEquals(141, size.heightMm)
    }
}
