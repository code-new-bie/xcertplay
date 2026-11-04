package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class TraceHexTest {
    @Test
    fun matchesTheFormattedHex() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }, bytes.toTraceHex())
        assertEquals("", ByteArray(0).toTraceHex())
    }

    @Test
    fun largeBodiesAreCut() {
        val bytes = ByteArray(1500) { 0xab.toByte() }
        assertEquals("ab".repeat(1024) + "...(+476 bytes)", bytes.toTraceHex())
        assertEquals("abab...(+1498 bytes)", bytes.toTraceHex(limit = 2))
    }
}
