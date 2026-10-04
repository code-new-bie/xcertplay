package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingState
import com.shilapi.xcertplay.iap2.message.Iap2PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterCardTest {
    @Test
    fun cardReadsTitleAndArtistWithPlayState() {
        val playing = ClusterCard.of(Iap2NowPlayingState(title = " 晴天 ", artist = "周杰伦", status = Iap2PlaybackStatus.PLAYING))
        assertEquals(ClusterCard("晴天 — 周杰伦", true), playing)
        val paused = ClusterCard.of(Iap2NowPlayingState(title = "晴天", status = Iap2PlaybackStatus.PAUSED))
        assertEquals(ClusterCard("晴天", false), paused)
        assertNull(ClusterCard.of(Iap2NowPlayingState(title = "  ")))
    }

    @Test
    fun longTextIsCutToTheDashboardLimitWithoutSplittingCharacters() {
        val card = ClusterCard.of(Iap2NowPlayingState(title = "🎵".repeat(100), status = Iap2PlaybackStatus.PLAYING))!!
        assertTrue(card.text.toByteArray(Charsets.UTF_16LE).size <= 255)
        assertFalse(Character.isHighSurrogate(card.text.last()))
    }
}
