package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionProgressTest {
    @Test
    fun wirelessStagesAdvanceThroughTheSteps() {
        val stages = listOf(
            CarPlayStatus.DiscoveringMfi to ConnectionStep.AUTHENTICATION,
            CarPlayStatus.MfiReady to ConnectionStep.LINK,
            CarPlayStatus.StartingHotspot to ConnectionStep.LINK,
            CarPlayStatus.HotspotReady("DIRECT-x", "5 GHz", 149, "aa", "fe80::1", "Wi-Fi P2P") to ConnectionStep.PHONE,
            CarPlayStatus.ConnectingBluetooth to ConnectionStep.PHONE,
            CarPlayStatus.RunningWireless to ConnectionStep.HANDSHAKE,
            CarPlayStatus.WirelessActive to ConnectionStep.CARPLAY,
        )
        for ((status, step) in stages) assertEquals(status.toString(), step, ConnectionProgress.stepOf(status))
    }

    @Test
    fun wiredStagesAdvanceThroughTheSteps() {
        assertEquals(ConnectionStep.LINK, ConnectionProgress.stepOf(CarPlayStatus.WaitingForReenumeration))
        assertEquals(ConnectionStep.PHONE, ConnectionProgress.stepOf(CarPlayStatus.Pairing))
        assertEquals(ConnectionStep.HANDSHAKE, ConnectionProgress.stepOf(CarPlayStatus.AttachingNetwork))
        assertEquals(ConnectionStep.CARPLAY, ConnectionProgress.stepOf(CarPlayStatus.RunningControl))
        assertNull(ConnectionProgress.stepOf(CarPlayStatus.Failed("x")))
    }

    @Test
    fun stepStatesFollowTheCurrentStep() {
        val current = ConnectionStep.PHONE
        assertEquals(StepState.DONE, ConnectionProgress.stateOf(ConnectionStep.LINK, current, failed = true))
        assertEquals(StepState.FAILED, ConnectionProgress.stateOf(ConnectionStep.PHONE, current, failed = true))
        assertEquals(StepState.ACTIVE, ConnectionProgress.stateOf(ConnectionStep.PHONE, current, failed = false))
        assertEquals(StepState.PENDING, ConnectionProgress.stateOf(ConnectionStep.CARPLAY, current, failed = true))
    }

    @Test
    fun failuresAreClassifiedFromControllerMessages() {
        val cases = mapOf(
            "Could not establish WIFI_P2P hotspot: Wi-Fi P2P createGroup failed: Wi-Fi P2P is busy" to FailureKind.WIFI_P2P,
            "Wi-Fi P2P could not form a 5 GHz group; try LocalOnlyHotspot" to FailureKind.WIFI_P2P,
            "Could not establish LOCAL_ONLY_HOTSPOT hotspot: LocalOnlyHotspot stopped while starting" to FailureKind.HOTSPOT,
            "Bluetooth is not enabled" to FailureKind.BLUETOOTH_OFF,
            "No bonded PHONE_SMART devices found; pair an iPhone and retry" to FailureKind.NO_PAIRED_IPHONE,
            "Could not connect RFCOMM to 14:1A:97:73:B3:F6" to FailureKind.NO_PAIRED_IPHONE,
            "MFi coprocessor client is unavailable" to FailureKind.AUTHENTICATION,
            "Could not open the selected local MFi certificate" to FailureKind.AUTHENTICATION,
            "iPhone USBMUX operation failed" to FailureKind.USB,
            "iAP2 identification timed out" to FailureKind.TIMEOUT,
            "Something unexpected" to FailureKind.OTHER,
        )
        for ((message, kind) in cases) assertEquals(message, kind, ConnectionProgress.failureKind(message))
    }
}
