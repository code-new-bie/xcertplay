package com.shilapi.xcertplay.hud

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClusterWriterProtocolTest {
    @get:Rule val temporary = TemporaryFolder()
    private val token = "a".repeat(32)
    private fun frame(sequence: Long = 0, now: Long = 1000) =
        ClusterWriterFrame(token, sequence, now, now, 1, false, "歌词 🎵\n下一行")

    @Test fun unicodeAndNewlinesRoundTripAndTruncatedFramesAreRejected() {
        val value = frame()
        assertEquals(value, ClusterWriterFrame.decode(value.encode()))
        assertNull(ClusterWriterFrame.decode(value.encode().substringBeforeLast(' ')))
        assertNull(ClusterWriterFrame.decode(value.copy(text = "字".repeat(128)).encode()))
        assertNull(ClusterWriterFrame.decode(value.copy(token = "../x").encode()))
    }

    @Test fun aSlotOverwritesTheOldFrameAndStopsWithoutLeavingTemporaryFiles() {
        val mailbox = ClusterWriterMailbox(File(temporary.root, "$token.state"))
        mailbox.publish(frame(0))
        mailbox.publish(frame(50))
        assertEquals(50L, mailbox.read()?.sequence)
        mailbox.publish(frame(51).copy(stop = true, state = 3))
        assertTrue(mailbox.read()!!.stop)
        assertEquals(1, temporary.root.listFiles()!!.size)
        mailbox.delete()
        assertEquals(0, temporary.root.listFiles()!!.size)
    }

    @Test fun theHelperSkipsOldUpdatesAndHeartbeatRefreshesDoNotRewriteTheCard() {
        var time = 1000L
        var value = frame(10)
        val applied = mutableListOf<Long>()
        var clears = 0
        var polls = 0
        val lines = mutableListOf<String>()
        ClusterWriterLoop(token, { value }, { time }, {
            time += it
            value = when (++polls) {
                1 -> value.copy(leaseMs = time)
                2 -> frame(30, time)
                else -> value.copy(stop = true, state = 3, leaseMs = time)
            }
        }, { applied += it.sequence; "state=0 text=0" }, { clears++; "state=0 text=0" }, lines::add).run()
        assertEquals(listOf(10L, 30L), applied)
        assertEquals(1, clears)
        assertTrue(lines.any { it.contains("seq=30") && it.contains("skipped=19") })
        assertTrue(lines.last().contains("reason=stop requested"))
    }

    @Test fun anOwnerCrashExpiresTheLeaseAndStillClearsTheCard() {
        var time = 1000L
        var clears = 0
        val lines = mutableListOf<String>()
        ClusterWriterLoop(token, { frame() }, { time }, { time += it }, { "state=0" },
            { clears++; "state=0" }, lines::add).run()
        assertTrue(time <= 4200)
        assertEquals(1, clears)
        assertTrue(lines.last().contains("owner heartbeat expired"))
    }

    @Test fun aShortMailboxReadGapRecoversWithoutClearingTheCard() {
        var time = 1000L
        var reads = 0
        var clears = 0
        val applied = mutableListOf<Long>()
        val lines = mutableListOf<String>()
        ClusterWriterLoop(token, {
            when (reads++) {
                0 -> null
                1 -> frame(sequence = 2, now = 1100)
                else -> frame(sequence = 2, now = time).copy(stop = true, state = 3)
            }
        }, { time }, { time += it }, { applied += it.sequence; "state=0" },
            { clears++; "state=0" }, lines::add).run()
        assertEquals(listOf(2L), applied)
        assertEquals(1, clears)
        assertTrue(lines.any { it.contains("mailbox unavailable") })
        assertTrue(lines.any { it.contains("mailbox recovered") })
        assertFalse(lines.last().contains("mailbox unavailable"))
    }

    @Test fun aFailedSdkWriteStillAttemptsCardCleanup() {
        var clears = 0
        assertThrows(IOException::class.java) {
            ClusterWriterLoop(token, { frame() }, { 1000 }, {}, { throw IOException("SDK failure") },
                { clears++; "state=0" }, {}).run()
        }
        assertEquals(1, clears)
    }

    @Test fun theWatchdogStopsAHungSdkAndAnOldPublisherCannotCancelStop() {
        val watchdog = ClusterWriterWatchdog(token)
        assertFalse(watchdog.shouldTerminate(frame(), 1000))
        assertFalse(watchdog.shouldTerminate(frame().copy(stop = true), 1100))
        assertFalse(watchdog.shouldTryClear(1500))
        assertTrue(watchdog.shouldTryClear(1600))
        assertFalse(watchdog.shouldTerminate(frame(now = 1500), 1500))
        assertTrue(watchdog.shouldTerminate(frame(now = 2100), 2100))
    }

    @Test fun theWatchdogKillsAnOrphanAfterLeaseExpiryOrMissingMailbox() {
        val expired = ClusterWriterWatchdog(token)
        assertFalse(expired.shouldTerminate(frame(), 4100))
        assertTrue(expired.shouldTerminate(frame(), 5100))
        val missing = ClusterWriterWatchdog(token)
        assertFalse(missing.shouldTerminate(null, 1000))
        assertTrue(missing.shouldTerminate(null, 2000))
    }

    @Test fun theWatchdogAllowsAMissingMailboxToRecoverBeforeKillingTheHelper() {
        val watchdog = ClusterWriterWatchdog(token)
        assertFalse(watchdog.shouldTerminate(null, 1000))
        assertFalse(watchdog.shouldTryClear(1400))
        assertFalse(watchdog.shouldTerminate(frame(now = 1100), 1100))
        assertFalse(watchdog.shouldTryClear(1600))
    }
}
