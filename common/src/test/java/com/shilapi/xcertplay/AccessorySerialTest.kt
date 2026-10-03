package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessorySerialTest {
    @Test
    fun eachHeadUnitGetsAStableSerial() {
        val serial = AccessorySerial.forAndroidId("1234abcd5678ef90")
        assertTrue(serial.matches(Regex("BYD-SongPLUS-[0-9A-F]{8}")))
        assertEquals(serial, AccessorySerial.forAndroidId("1234abcd5678ef90"))
        assertNotEquals(serial, AccessorySerial.forAndroidId("0000000000000001"))
    }

    @Test
    fun aMissingAndroidIdStillGivesAValidSerial() {
        assertEquals("BYD-SongPLUS-00000000", AccessorySerial.forAndroidId(null))
        assertEquals("BYD-SongPLUS-00000000", AccessorySerial.forAndroidId(" "))
    }
}
