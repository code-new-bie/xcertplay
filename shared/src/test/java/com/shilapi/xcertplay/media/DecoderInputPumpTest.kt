package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DecoderInputPumpTest {
    /** A clock that moves only when the fake decoder says time passed. */
    private class Clock {
        var now = 0L
        fun advance(ns: Long) {
            now += ns
        }
    }

    private val ms = 1_000_000L

    @Test
    fun aFreeBufferIsTakenAfterOneDrainWithoutAStall() {
        val calls = mutableListOf<String>()
        val result = DecoderInputPump.acquire(
            running = { true },
            drain = { calls += "drain" },
            dequeue = { calls += "dequeue"; 3 },
            timeoutNs = 100 * ms,
        )
        assertEquals(3, result.index)
        assertEquals(0L, result.stalledNs)
        assertEquals(listOf("drain", "dequeue"), calls)
    }

    @Test
    fun drainingBeforeEachAttemptFreesTheInputAndTheStallIsTimed() {
        val clock = Clock()
        var drains = 0
        var attempts = 0
        val result = DecoderInputPump.acquire(
            running = { true },
            drain = { drains++ },
            // Each failed attempt waits 10 ms, as dequeueInputBuffer does; the third finds a buffer.
            dequeue = {
                attempts++
                clock.advance(10 * ms)
                if (attempts < 3) -1 else 5
            },
            timeoutNs = 100 * ms,
            nanoTime = { clock.now },
        )
        assertEquals(5, result.index)
        assertEquals(3, drains)
        // From the start of the first failed attempt to the end of the successful one.
        assertEquals(30 * ms, result.stalledNs)
    }

    @Test
    fun itGivesUpAfterTheLimit() {
        val clock = Clock()
        var attempts = 0
        val result = DecoderInputPump.acquire(
            running = { true },
            drain = {},
            dequeue = {
                attempts++
                clock.advance(10 * ms)
                -1
            },
            timeoutNs = 100 * ms,
            nanoTime = { clock.now },
        )
        assertEquals(-1, result.index)
        assertEquals(10, attempts)
        assertEquals(100 * ms, result.stalledNs)
    }

    @Test
    fun aSlowFirstDrainStillGetsOneAttempt() {
        val clock = Clock()
        var attempts = 0
        val result = DecoderInputPump.acquire(
            running = { true },
            // A blocking track write can hold the drain longer than the whole limit.
            drain = { clock.advance(150 * ms) },
            dequeue = {
                attempts++
                -1
            },
            timeoutNs = 100 * ms,
            nanoTime = { clock.now },
        )
        assertEquals(-1, result.index)
        assertEquals(1, attempts)
    }

    @Test
    fun aStoppedRendererNeitherDrainsNorAsks() {
        val result = DecoderInputPump.acquire(
            running = { false },
            drain = { fail("drained") },
            dequeue = { fail("asked"); 0 },
            timeoutNs = 100 * ms,
        )
        assertEquals(-1, result.index)
        assertEquals(0L, result.stalledNs)
    }

    @Test
    fun stoppingDuringTheWaitEndsIt() {
        val clock = Clock()
        var attempts = 0
        val result = DecoderInputPump.acquire(
            running = { attempts < 2 },
            drain = {},
            dequeue = {
                attempts++
                clock.advance(10 * ms)
                -1
            },
            timeoutNs = 100 * ms,
            nanoTime = { clock.now },
        )
        assertEquals(-1, result.index)
        assertEquals(2, attempts)
        assertEquals(20 * ms, result.stalledNs)
    }
}
