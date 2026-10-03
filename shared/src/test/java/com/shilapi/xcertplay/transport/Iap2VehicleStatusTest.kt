package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2VehicleStatusTest {
    @Test fun sendsWattHoursAndPercentageWithCorrectUnitsAndNoUnknownChargingState() {
        val status = VehicleStatusSnapshot(55, false, 50.0, 9150, 18300, 110)
        val update = Iap2VehicleStatus.update(status)
        assertEquals(0xA101, update.messageId)
        val params = Iap2ParameterList.parse(update.payload)
        assertEquals(55L, unsigned(params.first(11)!!.payload))
        assertEquals(9150L, unsigned(params.first(22)!!.payload))
        assertEquals(18300L, unsigned(params.first(23)!!.payload))
        assertEquals(50000L, unsigned(params.first(24)!!.payload))
        assertNull(params.first(25))
        val charging = Iap2ParameterList.parse(Iap2VehicleStatus.update(status.copy(charging = true)).payload)
        assertEquals(1L, unsigned(charging.first(25)!!.payload))
    }

    @Test fun reportsOnlyDuringPhoneSubscriptionAndSkipsUnavailableReadings() {
        var clock = 0L
        var current: VehicleStatusSnapshot? = reading
        val reporter = Iap2VehicleStatusReporter({ current }, {}, { clock })
        val sent = mutableListOf<Iap2Frame>()
        reporter.tick { sent += it }
        assertTrue(sent.isEmpty())
        reporter.handle(Iap2Messages.buildRaw(0xA100) {}) { sent += it }
        assertEquals(1, sent.size)
        clock = 29_000_000_000L
        reporter.tick { sent += it }
        assertEquals(1, sent.size)
        clock = 30_000_000_000L
        reporter.tick { sent += it }
        assertEquals(2, sent.size)
        current = null
        clock = 60_000_000_000L
        reporter.tick { sent += it }
        assertEquals(2, sent.size)
        reporter.handle(Iap2Messages.buildRaw(0xA102) {}) { sent += it }
        current = reading
        clock = 90_000_000_000L
        reporter.tick { sent += it }
        assertEquals(2, sent.size)
    }

    private fun unsigned(bytes: ByteArray): Long = bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
    private val reading = VehicleStatusSnapshot(
        rangeKm = 150,
        rangeWarning = false,
        batteryPercent = 25.0,
        currentChargeWh = 25_100,
        maxChargeWh = 100_400,
        maxRangeKm = 600,
    )

    @Test
    fun anElectricVehicleIsDeclaredOnlyWithABatteryReading() {
        val enabled = identification(vehicleStatusEnabled = true)

        // Switch on, but no provider at all.
        assertFalse(enabled.withVehicleStatusFrom(null).vehicleStatusEnabled)
        // ADB off, not approved, or a car without these properties: the provider has no reading.
        assertFalse(enabled.withVehicleStatusFrom { null }.vehicleStatusEnabled)
        assertTrue(enabled.withVehicleStatusFrom { reading }.vehicleStatusEnabled)
        // Switch off: never declared, whatever the provider has.
        assertFalse(identification(vehicleStatusEnabled = false).withVehicleStatusFrom { reading }.vehicleStatusEnabled)
    }

    @Test
    fun withoutAReadingNoVehicleComponentsOrMessagesAreSent() {
        val parameters = parameters(identification(vehicleStatusEnabled = true).withVehicleStatusFrom { null })

        assertNull(parameters.first(20))
        assertNull(parameters.first(21))
        assertFalse(Iap2VehicleStatus.VEHICLE_STATUS_UPDATE in u16Values(parameters.first(6)!!.payload))
        assertFalse(Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES in u16Values(parameters.first(7)!!.payload))
    }

    @Test
    fun declaresTheChosenChargingConnectors() {
        assertEquals(listOf(1, 4), connectors(EvChargingConnectors.CCS2_TYPE2))
        assertEquals(listOf(5, 6), connectors(EvChargingConnectors.GB_T))
        assertEquals(listOf(6), connectors(EvChargingConnectors.GB_T_AC_ONLY))
        assertEquals(listOf(0, 2), connectors(EvChargingConnectors.CCS1_J1772))
    }

    private fun connectors(choice: EvChargingConnectors): List<Int> {
        val config = identification(vehicleStatusEnabled = true).copy(chargingConnectors = choice)
        val vehicle = Iap2ParameterList.parse(parameters(config).first(20)!!.payload)
        return vehicle.all(11).map { it.payload.single().toInt() }
    }

    private fun identification(vehicleStatusEnabled: Boolean) = Iap2IdentificationConfig(
        name = "xcertplay",
        modelIdentifier = "xcertplay",
        manufacturer = "xcertplay",
        serialNumber = "xcertplay",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 3,
        vehicleStatusEnabled = vehicleStatusEnabled,
    )

    private fun parameters(config: Iap2IdentificationConfig) =
        Iap2ParameterList.parse(Iap2IdentificationClient.identificationInformation(config).payload)

    private fun u16Values(bytes: ByteArray): List<Int> =
        List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or (bytes[index * 2 + 1].toInt() and 0xff)
        }
}
