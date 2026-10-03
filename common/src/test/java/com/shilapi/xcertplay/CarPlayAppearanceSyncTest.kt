package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayAppearanceSyncTest {
    private val sentModes = mutableListOf<Boolean>()
    private var darkMode = true
    private lateinit var sync: CarPlayAppearanceSync

    @Before
    fun setUp() {
        sync = CarPlayAppearanceSync(Handler(Looper.getMainLooper())) { sentModes += darkMode }
    }

    @After
    fun tearDown() {
        sync.stop()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun idle(milliseconds: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))

    @Test
    fun startupSendsImmediatelyAndOnlyAtTwoAndFiveSeconds() {
        sync.start(Any())
        assertEquals(listOf(true), sentModes)
        idle(1_999)
        assertEquals(1, sentModes.size)
        idle(1)
        assertEquals(2, sentModes.size)
        idle(3_000)
        assertEquals(listOf(true, true, true), sentModes)
        idle(30_000)
        assertEquals(3, sentModes.size)
    }

    @Test
    fun resendsReadTheLatestModeInsteadOfTheStartupMode() {
        sync.start(Any())
        darkMode = false
        idle(2_000)
        darkMode = true
        idle(3_000)
        assertEquals(listOf(true, false, true), sentModes)
    }

    @Test
    fun reconnectCancelsOldDeadlinesAndIgnoresTheOldSessionEnd() {
        val oldSession = Any()
        val nextSession = Any()
        sync.start(oldSession)
        idle(1_000)
        darkMode = false
        sync.start(nextSession)
        sync.end(oldSession)
        idle(1_000)
        assertEquals(listOf(true, false), sentModes)
        idle(1_000)
        assertEquals(listOf(true, false, false), sentModes)
        idle(3_000)
        assertEquals(listOf(true, false, false, false), sentModes)
    }

    @Test
    fun disconnectCancelsPendingResends() {
        val session = Any()
        sync.start(session)
        sync.end(session)
        idle(10_000)
        assertEquals(listOf(true), sentModes)
    }

    @Test
    fun stoppingTheActivityCancelsPendingResends() {
        sync.start(Any())
        sync.stop()
        idle(10_000)
        assertEquals(listOf(true), sentModes)
    }
}
