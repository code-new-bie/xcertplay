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

    @Test
    fun aSavedAddressWinsThenTheSystemThenTheDeviceId() {
        val device = "9E:00:00:00:00:01"
        assertEquals(
            AccessoryIds(device, "11:22:33:44:55:66", AccessoryIds.BluetoothSource.SAVED),
            AccessoryIds.resolve(device, "11:22:33:44:55:66", listOf("AA:BB:CC:DD:EE:FF")),
        )
        assertEquals(
            AccessoryIds(device, "AA:BB:CC:DD:EE:FF", AccessoryIds.BluetoothSource.SYSTEM),
            AccessoryIds.resolve(device, "02:00:00:00:00:00", listOf(null, "aa:bb:cc:dd:ee:ff")),
        )
        assertEquals(
            AccessoryIds(device, device, AccessoryIds.BluetoothSource.DEVICE_ID),
            AccessoryIds.resolve(device, null, listOf("02:00:00:00:00:00")),
        )
    }

    @Test
    fun adbOutputIsParsedLineByLine() {
        assertEquals("AA:BB:CC:DD:EE:FF", AccessoryIds.realBluetoothAddress("aa:bb:cc:dd:ee:ff\r\n".lines()))
        assertNull(AccessoryIds.realBluetoothAddress("null\n".lines()))
    }
}
