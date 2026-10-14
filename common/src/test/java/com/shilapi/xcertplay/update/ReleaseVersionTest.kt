package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun readsThisForksTags() {
        assertEquals(ReleaseVersion(1, 4, 0, 11), ReleaseVersion.parse("1.4.0-byd.11"))
        assertEquals(ReleaseVersion(1, 4, 0, 11), ReleaseVersion.parse("v1.4.0-byd.11"))
        assertEquals("1.4.0-byd.11", ReleaseVersion.parse(" 1.4.0-byd.11 ").toString())
    }

    @Test
    fun rejectsOtherNames() {
        listOf(null, "", "1.4.0", "1.4.0-byd", "1.4.0-byd.x", "1.4-byd.2", "1.4.0-byd.2-test", "upstream-1.4.0")
            .forEach { assertNull(it, ReleaseVersion.parse(it)) }
    }

    @Test
    fun ordersByUpstreamVersionThenReleaseNumber() {
        val ordered = listOf("1.3.3-byd.9", "1.4.0-byd.1", "1.4.0-byd.10", "1.4.0-byd.11", "1.4.1-byd.12", "2.0.0-byd.1")
            .map { checkNotNull(ReleaseVersion.parse(it)) }
        assertEquals(ordered, ordered.shuffled().sorted())
        // Numbers, not text: byd.10 is newer than byd.9.
        assertTrue(ReleaseVersion.parse("1.4.0-byd.10")!! > ReleaseVersion.parse("1.4.0-byd.9")!!)
    }
}
