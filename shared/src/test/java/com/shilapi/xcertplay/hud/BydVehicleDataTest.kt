package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.*
import org.junit.Test

class BydVehicleDataTest {
    @Test fun parsesSdkPercentageElectricRangeAndEnergy() {
        val data = BydVehicleData.battery("XCERTPLAY battery 80.5 88 14.49 1", 0.0)!!
        assertEquals(80.5, data.percent, 0.0)
        assertEquals(88, data.rangeKm)
        assertEquals(14.49, data.remainingKwh, 0.0)
        assertEquals(true, data.charging)
    }

    @Test fun manualCapacitySupportsDmiWithoutTheEvEnergyGetter() {
        val data = BydVehicleData.battery("XCERTPLAY battery 50 55 - -", 18.3)!!
        assertEquals(9.15, data.remainingKwh, 0.00001)
        assertNull(data.charging)
        assertNull(BydVehicleData.battery("XCERTPLAY battery 50 55 - -", 0.0))
        assertNull(BydVehicleData.battery("XCERTPLAY battery 50 55 0 -", 0.0))
        assertEquals(0.0, BydVehicleData.battery("XCERTPLAY battery 0 0 0 -", 0.0)!!.remainingKwh, 0.0)
    }

    @Test fun rejectsSdkSentinelsAndNonFiniteBatteryValues() {
        for (line in listOf("XCERTPLAY heartbeat", "XCERTPLAY battery 101 80 12 1", "XCERTPLAY battery -1 80 12 1",
            "XCERTPLAY battery NaN 80 12 1", "XCERTPLAY battery 80 1000 12 1", "XCERTPLAY battery 80 1023 12 1",
            "XCERTPLAY battery 80 -1 12 1", "XCERTPLAY battery 80 80 Infinity 1", "XCERTPLAY battery 80 80 -1 1")) {
            assertNull(line, BydVehicleData.battery(line, 0.0))
        }
    }

    @Test fun doesNotInventUnavailableChargingState() {
        assertNull(BydVehicleData.battery("XCERTPLAY battery 80 80 12 -", 0.0)!!.charging)
        assertNull(BydVehicleData.battery("XCERTPLAY battery 80 80 12 255", 0.0)!!.charging)
        assertEquals(false, BydVehicleData.battery("XCERTPLAY battery 80 80 12 2", 0.0)!!.charging)
    }

    @Test fun convertsKmhAndMapsAndroid10Gears() {
        val expected = listOf(VehicleGear.PARK, VehicleGear.REVERSE, VehicleGear.NEUTRAL,
            VehicleGear.DRIVE, VehicleGear.DRIVE, VehicleGear.DRIVE)
        for ((index, gear) in expected.withIndex()) {
            val (speed, parsedGear) = BydVehicleData.speed("XCERTPLAY speed 1000 36 ${index + 1}", 1500)!!
            assertEquals(10.0, speed.metersPerSecond, 0.0)
            assertEquals(1000L, speed.elapsedMillis)
            assertEquals(gear, parsedGear)
        }
    }

    @Test fun rejectsStaleFutureInvalidSpeedAndGearSamples() {
        for (line in listOf("XCERTPLAY speed 1000 36 0", "XCERTPLAY speed 1000 36 7", "XCERTPLAY speed 1000 NaN 4",
            "XCERTPLAY speed 1000 301 4", "XCERTPLAY speed 1000 -1 4", "XCERTPLAY speed 2001 36 4",
            "XCERTPLAY speed -1 36 4")) assertNull(line, BydVehicleData.speed(line, 2000))
        assertNull(BydVehicleData.speed("XCERTPLAY speed 1000 36 4", 3001))
        assertNotNull(BydVehicleData.speed("XCERTPLAY speed 1000 36 4", 3000))
    }
}
