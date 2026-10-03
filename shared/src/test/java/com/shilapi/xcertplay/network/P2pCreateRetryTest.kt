package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Test

class P2pCreateRetryTest {
    @Test
    fun busyRetriesTheSameFrequencyWithGrowingDelays() {
        for (retries in 0 until P2pCreateRetry.MAX_BUSY_RETRIES) {
            assertEquals(
                P2pCreateRetry.Next.RETRY_SAME,
                P2pCreateRetry.next(WifiP2pManager.BUSY, retries, hasNextFrequency = false),
            )
        }
        assertEquals(listOf(500L, 1000L, 1500L), (1..3).map(P2pCreateRetry::busyDelayMillis))
    }

    @Test
    fun exhaustedBusyFallsBackOrFails() {
        val max = P2pCreateRetry.MAX_BUSY_RETRIES
        assertEquals(P2pCreateRetry.Next.NEXT_FREQUENCY, P2pCreateRetry.next(WifiP2pManager.BUSY, max, true))
        assertEquals(P2pCreateRetry.Next.FAIL, P2pCreateRetry.next(WifiP2pManager.BUSY, max, false))
    }

    @Test
    fun otherRejectionsUseTheNextFrequencyOnly() {
        assertEquals(P2pCreateRetry.Next.NEXT_FREQUENCY, P2pCreateRetry.next(WifiP2pManager.ERROR, 0, true))
        assertEquals(P2pCreateRetry.Next.FAIL, P2pCreateRetry.next(WifiP2pManager.ERROR, 0, false))
        assertEquals(P2pCreateRetry.Next.FAIL, P2pCreateRetry.next(WifiP2pManager.P2P_UNSUPPORTED, 0, false))
    }
}
