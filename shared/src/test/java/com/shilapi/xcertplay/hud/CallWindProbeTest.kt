package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class CallWindProbeTest {
    @Test fun futureFileTimestampCannotKeepAnAbandonedLeaseAlive() {
        var time = 0L
        val lease = CallWindLease { time }
        assertTrue(lease.alive(true, Long.MAX_VALUE))
        time = 5_001L
        assertFalse(lease.alive(true, Long.MAX_VALUE))
    }

    @Test fun changedFileTimestampRefreshesLeaseEvenWhenWallClockMovesBackwards() {
        var time = 0L
        val lease = CallWindLease { time }
        assertTrue(lease.alive(true, 10_000))
        time = 4_000
        assertTrue(lease.alive(true, 9_000))
        time = 8_000
        assertTrue(lease.alive(true, 9_000))
        assertFalse(lease.alive(false, 9_000))
    }
    @Test fun cancellationDuringRequestWaitsUntilAllEntryOperationsFinish() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val cancelling = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val bridge = object : CallWindBridge {
            override fun unavailableReason(): String? = null
            override fun request() {
                events += "call"
                entered.countDown()
                check(proceed.await(2, TimeUnit.SECONDS))
                events += "mute"
                events += "mode"
            }
            override fun release() { events += "release" }
        }
        val probe = CallWindProbe(bridge, { true }, System::currentTimeMillis, Thread::sleep, {})
        val runner = thread { probe.run() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val canceller = thread { cancelling.countDown(); probe.releaseOnce() }
        assertTrue(cancelling.await(2, TimeUnit.SECONDS))
        proceed.countDown()
        runner.join(2000)
        canceller.join(2000)
        assertFalse(runner.isAlive)
        assertFalse(canceller.isAlive)
        assertEquals(listOf("call", "mute", "mode", "release"), events.toList())
    }

    @Test fun watchdogBeforeRequestMakesCancellationTerminal() {
        val bridge = Bridge()
        val probe = CallWindProbe(bridge, { true }, { 0L }, {}, {})
        assertEquals("not requested", probe.releaseOnce())
        assertEquals("cancelled before request", probe.run())
        assertEquals(0, bridge.requests)
        assertEquals(0, bridge.releases)
    }
    private class Bridge : CallWindBridge {
        var blocked: String? = null
        var requests = 0
        var releases = 0
        var failRequest = false
        var failRelease = false
        override fun unavailableReason() = blocked
        override fun request() { requests++; if (failRequest) error("request failed after dispatch") }
        override fun release() { releases++; if (failRelease) error("release failed") }
    }

    @Test fun completesAfterTenSecondsAndReleasesExactlyOnce() {
        val bridge = Bridge()
        var time = 0L
        val output = mutableListOf<String>()
        val probe = CallWindProbe(bridge, { true }, { time }, { time += it }, output::add)
        assertEquals("completed", probe.run())
        assertEquals(10_000L, time)
        assertEquals(1, bridge.requests)
        assertEquals(1, bridge.releases)
        assertEquals("released", probe.releaseOnce())
        assertEquals(1, bridge.releases)
        assertTrue(output.contains("released"))
    }

    @Test fun nativeCallGuardRejectsWithoutChangingVehicleState() {
        val bridge = Bridge().apply { blocked = "emergency call is active" }
        val probe = CallWindProbe(bridge, { true }, { 0L }, {}, {})
        assertEquals("blocked: emergency call is active", probe.run())
        assertEquals(0, bridge.requests)
        assertEquals(0, bridge.releases)
    }

    @Test fun cancellationBeforeStartupDoesNotRequestOrReleaseAnotherCall() {
        val bridge = Bridge()
        val probe = CallWindProbe(bridge, { false }, { 0L }, {}, {})
        assertEquals("cancelled before request", probe.run())
        assertEquals("not requested", probe.releaseOnce())
        assertEquals(0, bridge.requests)
        assertEquals(0, bridge.releases)
    }

    @Test fun stopFileDisappearanceReleasesBeforeTheDeadline() {
        val bridge = Bridge()
        var time = 0L
        val probe = CallWindProbe(bridge, { time < 300 }, { time }, { time += it }, {})
        assertEquals("cancelled", probe.run())
        assertEquals(300L, time)
        assertEquals(1, bridge.releases)
    }

    @Test fun failedRequestStillAttemptsReleaseBecauseDispatchMayHaveSucceeded() {
        val bridge = Bridge().apply { failRequest = true }
        val probe = CallWindProbe(bridge, { true }, { 0L }, {}, {})
        assertThrows(IllegalStateException::class.java) { probe.run() }
        assertEquals(1, bridge.requests)
        assertEquals(1, bridge.releases)
    }

    @Test fun failedReleaseIsReportedAsFailureInsteadOfSuccessfulRecovery() {
        val bridge = Bridge().apply { failRelease = true }
        var time = 0L
        val output = mutableListOf<String>()
        val probe = CallWindProbe(bridge, { true }, { time }, { time += it }, output::add)
        probe.run()
        assertFalse(output.contains("released"))
        assertTrue(output.any { it.startsWith("release failed:") })
    }

    @Test fun watchdogReleaseAndFinallyDoNotReleaseTwice() {
        val bridge = Bridge()
        var running = true
        lateinit var probe: CallWindProbe
        probe = CallWindProbe(bridge, { running }, { 0L }, {
            running = false
            assertEquals("released", probe.releaseOnce())
        }, {})
        assertEquals("cancelled", probe.run())
        assertEquals(1, bridge.releases)
    }
}
