package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioClockDriftTest {
    private val rate = 48_000
    private val packetFrames = 1_024
    private val packetNs = packetFrames * 1_000_000_000L / rate

    /** Feeds [seconds] of packets from [startNs]; returns the time after the last one. */
    private fun AudioClockDrift.send(fromRtp: Long, startNs: Long, seconds: Int): Long {
        var now = startNs
        val packets = seconds * rate / packetFrames
        for (index in 0 until packets) {
            onPacket((fromRtp + index * packetFrames.toLong()).toInt(), now)
            now += packetNs
        }
        return now
    }

    @Test
    fun aHeadUnitPlayingFastShowsAsAPositiveDifference() {
        val drift = AudioClockDrift(rate)
        // The Humax case: the iPhone sends 48 000 frames/s, the track plays 0.76 % faster.
        drift.send(0, 0, 11)
        assertNull(drift.describe(0, 0, 11_000_000_000L))
        val end = drift.send(11L * rate, 11_000_000_000L, 70)
        val played = (48_000 * 1.0076 * (end - 11_000_000_000L) / 1e9).toLong()
        val line = drift.describe(played, 0, end)!!
        assertTrue(line, line.startsWith("sendHz=48000.0 playHz=48364.8 clockDiffPct=+0.760"))
    }

    @Test
    fun nothingIsShownBeforeAMinuteOfSteadyPlay() {
        val drift = AudioClockDrift(rate)
        drift.send(0, 0, 40)
        assertNull(drift.describe(0, 0, 40_000_000_000L))
        assertNull(drift.describe(48_000L * 30, 0, 70_000_000_000L))
    }

    @Test
    fun anUnderrunStartsTheMeasurementAgain() {
        val drift = AudioClockDrift(rate)
        drift.describe(0, 0, 0)
        val end = drift.send(0, 0, 80)
        assertNull(drift.describe(48_000L * 80, 1, end))
        assertNull(drift.describe(48_000L * 81, 1, end + 1_000_000_000L))
    }

    @Test
    fun aPauseInThePacketsStartsTheMeasurementAgain() {
        val drift = AudioClockDrift(rate)
        drift.describe(0, 0, 0)
        val paused = drift.send(0, 0, 80)
        // Five seconds without packets, then the same music goes on.
        val resumed = paused + 5_000_000_000L
        val end = drift.send(80L * rate, resumed, 30)
        assertNull(drift.describe(48_000L * 110, 0, end))
    }

    @Test
    fun theRtpTimestampMayWrap() {
        val drift = AudioClockDrift(rate)
        drift.describe(0, 0, 0)
        val start = 0xffff_0000L
        val end = drift.send(start, 0, 80)
        val line = drift.describe((48_000L * end / 1_000_000_000L), 0, end)!!
        assertEquals("sendHz=48000.0", line.substringBefore(' '))
    }
}
