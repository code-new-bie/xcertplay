package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecodeFlowTest {
    @Test
    fun closingQueueDisposesFramesItStillOwns() {
        val discarded = mutableListOf<String>()
        val queue = VideoWorkQueue<String>(1, discarded::add)
        assertTrue(queue.frame("frame"))

        queue.close()

        assertEquals(listOf("frame"), discarded)
        assertFalse(queue.frame("late"))
    }

    @Test
    fun pumpReleasesBufferOnlyAfterCodecAcceptsIt() {
        var attempts = 0
        var releases = 0
        val pump = VideoDecodePump(object : VideoDecodePump.Port {
            override fun drain() = Unit
            override fun queue(bytes: ByteArray, size: Int, presentationTimeUs: Long): Boolean {
                attempts++
                assertEquals(3, size)
                return attempts == 2
            }
        })
        pump.submit(ByteArray(16), 3, 42) { releases++ }

        pump.tick()
        assertEquals(0, releases)
        pump.tick()
        assertEquals(1, releases)
    }

    @Test
    fun clearingPumpReleasesPendingBuffer() {
        var releases = 0
        val pump = VideoDecodePump(object : VideoDecodePump.Port {
            override fun drain() = Unit
            override fun queue(bytes: ByteArray, size: Int, presentationTimeUs: Long) = false
        })
        pump.submit(ByteArray(1), 1, 0) { releases++ }

        pump.clear()

        assertEquals(1, releases)
    }
}
