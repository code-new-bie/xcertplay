package com.shilapi.xcertplay.transport

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockdownPairRejectionTest {
    @Test
    fun anIphoneThatForgotTheHostIsARejection() {
        // The message a user saw on the home screen, 2026-10-14.
        assertEquals("InvalidHostID", LockdownPairRejection.of(IOException("Lockdown StartSession failed: InvalidHostID")))
    }

    @Test
    fun anInvalidPairRecordStillIs() {
        assertEquals("InvalidPairRecord", LockdownPairRejection.of(IOException("Lockdown StartSession failed: InvalidPairRecord")))
    }

    @Test
    fun aWrappedRejectionIsFound() {
        val wrapped = RuntimeException("wired bring-up", IOException("Lockdown StartSession failed: InvalidHostID"))
        assertEquals("InvalidHostID", LockdownPairRejection.of(wrapped))
    }

    @Test
    fun otherLockdownFailuresAreNotRejections() {
        listOf(
            "Lockdown StartSession failed: PasswordProtected",
            "Lockdown StartService failed: InvalidService",
            "StartSession did not enable TLS",
        ).forEach { assertNull(it, LockdownPairRejection.of(IOException(it))) }
        assertNull(LockdownPairRejection.of(IOException()))
    }

    @Test
    fun aCauseCycleDoesNotHang() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)
        assertNull(LockdownPairRejection.of(first))
    }
}
