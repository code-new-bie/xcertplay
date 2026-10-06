package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BydCallWindMcuTest {
    @Test
    fun deniedPermissionDoesNotWriteMcu() {
        val writes = mutableListOf<Int>()
        val logs = mutableListOf<String>()
        val mcu = BydCallWindMcu({ false }, { value -> writes += value; 0 }, logs::add, 2000)

        runCatching { mcu.activate() }.also { assertTrue(it.isFailure) }

        assertEquals(emptyList<Int>(), writes)
        assertTrue(logs.any { it.contains("permission=false") })
        assertEquals("not attempted", mcu.release())
    }

    @Test
    fun activeWriteFailureStillReleases() {
        val writes = mutableListOf<Int>()
        val logs = mutableListOf<String>()
        val mcu = BydCallWindMcu(
            { true },
            { value -> writes += value; if (value == 0) -1 else 0 },
            logs::add,
            2000,
        )

        runCatching { mcu.activate() }.also { assertTrue(it.isFailure) }

        assertEquals("released", mcu.release())
        assertEquals(listOf(0, 1), writes)
        assertTrue(logs.any { it.contains("active result=-1 success=false") })
        assertTrue(logs.any { it.contains("release result=0 success=true") })
    }
}
