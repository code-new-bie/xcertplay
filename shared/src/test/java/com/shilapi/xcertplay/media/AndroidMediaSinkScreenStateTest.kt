package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AndroidMediaSinkScreenStateTest {
    @Test
    fun aListenerAttachedLaterLearnsTheActiveScreens() {
        val sink = AndroidMediaSink(RuntimeEnvironment.getApplication())
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        sink.onScreenStreamActive(111, false)

        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events += type to active }

        assertEquals(listOf(110 to true), events)
        sink.close()
    }

    @Test
    fun laterChangesStillReachTheListener() {
        val sink = AndroidMediaSink(RuntimeEnvironment.getApplication())
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events += type to active }

        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(110, false)

        assertEquals(listOf(110 to true, 110 to false), events)
        sink.close()
    }
}
