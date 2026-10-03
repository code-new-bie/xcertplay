package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessoryIdsTest {
    private val mac = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

    @Test
    fun eachHeadUnitGetsStableDistinctLocalUnicastIds() {
        val ids = AccessoryIds.forAndroidId("1234abcd5678ef90")
        assertEquals(ids, AccessoryIds.forAndroidId("1234abcd5678ef90"))
        assertNotEquals(ids, AccessoryIds.forAndroidId("0000000000000001"))
        assertNotEquals(ids.deviceId, ids.bluetoothId)
        for (id in listOf(ids.deviceId, ids.bluetoothId)) {
            assertTrue(id, id.matches(mac))
            val first = id.substring(0, 2).toInt(16)
            assertEquals("locally administered unicast: $id", 0x02, first and 0x03)
        }
    }

    @Test
    fun aMissingAndroidIdKeepsTheFixedIds() {
        assertEquals(AccessoryIds.FALLBACK, AccessoryIds.forAndroidId(null))
        assertEquals(AccessoryIds.FALLBACK, AccessoryIds.forAndroidId(""))
        assertEquals("02:00:00:00:00:02", AccessoryIds.FALLBACK.deviceId)
        assertEquals("02:00:00:00:00:01", AccessoryIds.FALLBACK.bluetoothId)
    }
}
