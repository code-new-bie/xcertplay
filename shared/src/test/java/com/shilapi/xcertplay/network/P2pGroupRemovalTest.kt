package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class P2pGroupRemovalTest {
    @Test
    fun successfulCallbackIsFollowedByCheckingThatTheGroupIsGone() {
        var queries = 0
        awaitP2pGroupRemoval(500, { it.onSuccess() }, { it.onGroupInfoAvailable(null); queries++ })
        assertEquals(1, queries)
    }

    @Test
    fun groupMayDisappearShortlyAfterTheSuccessfulCallback() {
        var queries = 0
        val group = WifiP2pGroup()
        awaitP2pGroupRemoval(500, { it.onSuccess() }, {
            it.onGroupInfoAvailable(if (++queries == 1) group else null)
        })
        assertEquals(2, queries)
    }

    @Test
    fun rejectionWithAnExistingGroupFailsCleanup() {
        fails("group still present") {
            awaitP2pGroupRemoval(500, { it.onFailure(WifiP2pManager.BUSY) }, {
                it.onGroupInfoAvailable(WifiP2pGroup())
            })
        }
    }

    @Test
    fun removingAnAlreadyAbsentGroupIsSafe() {
        awaitP2pGroupRemoval(500, { it.onFailure(WifiP2pManager.ERROR) }, { it.onGroupInfoAvailable(null) })
    }

    @Test
    fun missingRemovalCallbackDoesNotReportSuccess() {
        fails("removeGroup timed out") {
            awaitP2pGroupRemoval(10, {}, { throw AssertionError("No query before removal completes") })
        }
    }

    @Test
    fun missingInspectionCallbackDoesNotReportSuccess() {
        fails("disappearance was not confirmed") { awaitP2pGroupRemoval(10, { it.onSuccess() }, {}) }
    }

    @Test
    fun successfulCallbackWithAGroupThatRemainsDoesNotReportSuccess() {
        fails("still present after removal") {
            awaitP2pGroupRemoval(10, { it.onSuccess() }, { it.onGroupInfoAvailable(WifiP2pGroup()) })
        }
    }

    @Test
    fun platformExceptionIsReportedAsACleanupFailure() {
        fails("could not be completed") {
            awaitP2pGroupRemoval(500, { throw SecurityException("denied") }, {})
        }
    }

    private fun fails(message: String, action: () -> Unit) {
        try {
            action()
            fail("Expected cleanup to fail: $message")
        } catch (error: IOException) {
            assertTrue(error.message, error.message.orEmpty().contains(message))
        }
    }
}
