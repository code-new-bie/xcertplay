package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessoryIdsTest {
    @Test
    fun deviceIdIsAStableLocalUnicastAddressPerPairingKey() {
        val key = ByteArray(32) { it.toByte() }
        val id = AccessoryIds.deviceId(key)
        assertEquals(id, AccessoryIds.deviceId(key.copyOf()))
        assertNotEquals(id, AccessoryIds.deviceId(ByteArray(32)))
        assertTrue(id, id.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")))
        assertEquals(0x02, id.substring(0, 2).toInt(16) and 0x03)
    }

    @Test
    fun bluetoothIdSkipsAndroidPlaceholders() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            AccessoryIds.realBluetoothAddress(listOf("02:00:00:00:00:00", null, "aa:bb:cc:dd:ee:ff")),
        )
        assertNull(AccessoryIds.realBluetoothAddress(listOf("02:00:00:00:00:00", "00:00:00:00:00:00", "bad", null)))
    }
}
