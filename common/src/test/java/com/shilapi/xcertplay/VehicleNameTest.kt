package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleNameTest {
    @Test
    fun customNameWinsOverBluetoothName() {
        assertEquals("My Song", VehicleName.resolve(" My Song ", "BYD Auto"))
    }

    @Test
    fun blankCustomNameFollowsBluetoothName() {
        assertEquals("BYD Auto", VehicleName.resolve("   ", "BYD Auto"))
        assertEquals("BYD Auto", VehicleName.resolve(null, "BYD Auto"))
    }

    @Test
    fun fallbackReadsAsTheCarAndEachNameReportsItsSource() {
        assertEquals("BYD Song PLUS", VehicleName.DEFAULT)
        assertEquals("My Song" to VehicleName.Source.CUSTOM, VehicleName.resolveWithSource("My Song", "BYD Auto"))
        assertEquals("BYD Auto" to VehicleName.Source.BLUETOOTH, VehicleName.resolveWithSource(null, "BYD Auto"))
        assertEquals(VehicleName.DEFAULT to VehicleName.Source.FALLBACK, VehicleName.resolveWithSource(null, null))
    }

    @Test
    fun missingNamesFallBackToDefault() {
        assertEquals(VehicleName.DEFAULT, VehicleName.resolve(null, null))
        assertEquals(VehicleName.DEFAULT, VehicleName.resolve("", "\u0000 "))
    }

    @Test
    fun sanitizeDropsControlCharactersAndLimitsLength() {
        assertEquals("AB", VehicleName.sanitize("A\nB\u0000"))
        assertEquals(VehicleName.MAX_LENGTH, VehicleName.sanitize("x".repeat(100))!!.length)
        assertNull(VehicleName.sanitize("\t\n"))
    }
}
