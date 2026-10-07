package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class NavigationAudioBufferTest {
    @Test fun defaultKeepsTheIphoneRequestedEightyMilliseconds() {
        assertEquals(80, NavigationAudioBuffer.DEFAULT_DURATION_MS)
        assertEquals(80, NavigationAudioBuffer.sanitizeDurationMs(NavigationAudioBuffer.DEFAULT_DURATION_MS))
    }

    @Test fun valuesAreClampedAndSnappedToTwentyMillisecondSteps() {
        assertEquals(80, NavigationAudioBuffer.sanitizeDurationMs(0))
        assertEquals(300, NavigationAudioBuffer.sanitizeDurationMs(1_000))
        assertEquals(100, NavigationAudioBuffer.sanitizeDurationMs(95))
        assertEquals(200, NavigationAudioBuffer.sanitizeDurationMs(205))
        val slider = (NavigationAudioBuffer.MIN_DURATION_MS..NavigationAudioBuffer.MAX_DURATION_MS
            step NavigationAudioBuffer.STEP_DURATION_MS).toList()
        assertEquals(12, slider.size)
        slider.forEach { assertEquals(it, NavigationAudioBuffer.sanitizeDurationMs(it)) }
    }

    @Test fun onlyTheAlternateAudioStreamUsesTheSetting() {
        assertTrue(NavigationAudioBuffer.isPromptStream(101))
        assertFalse(NavigationAudioBuffer.isPromptStream(100))
        assertFalse(NavigationAudioBuffer.isPromptStream(102))
    }

    @Test fun startBytesAndDrainLimitFollowTheDuration() {
        // 48 kHz mono 16-bit: 96 bytes per millisecond.
        assertEquals(7_680, NavigationAudioBuffer.startBytes(80, 48_000, 1))
        assertEquals(28_800, NavigationAudioBuffer.startBytes(300, 48_000, 1))
        assertEquals(280L, NavigationAudioBuffer.drainLimitMs(80))
        assertEquals(500L, NavigationAudioBuffer.drainLimitMs(300))
    }
}
