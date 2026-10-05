package com.shilapi.xcertplay

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayShutdownTest {
    @Test
    fun connectionIsDisconnectedBeforeMediaAndBluetoothAreRestored() {
        val steps = mutableListOf<String>()
        val result = runCarPlayShutdown(
            disconnect = { steps += "disconnect"; true },
            closeMedia = { steps += "media" },
            restoreBluetooth = { steps += "Bluetooth"; true },
            resumeWifiSearch = { steps += "Wi-Fi"; true },
            onFailure = { _, error -> throw AssertionError(error) },
        )
        assertEquals(listOf("disconnect", "media", "Bluetooth", "Wi-Fi"), steps)
        assertEquals(CarPlayShutdownResult(true, true, true), result)
    }

    @Test
    fun connectionFailureDoesNotSkipEitherRestore() {
        val failures = mutableListOf<String>()
        var mediaClosed = false
        var bluetoothAttempted = false
        var wifiAttempted = false
        val result = runCarPlayShutdown(
            disconnect = { throw IOException("disconnect failed") },
            closeMedia = { mediaClosed = true; throw IOException("media failed") },
            restoreBluetooth = { bluetoothAttempted = true; throw IOException("Bluetooth failed") },
            resumeWifiSearch = { wifiAttempted = true; true },
            onFailure = { step, _ -> failures += step },
        )
        assertEquals(listOf("CarPlay connection", "media", "Bluetooth"), failures)
        assertTrue(mediaClosed)
        assertTrue(bluetoothAttempted)
        assertTrue(wifiAttempted)
        assertEquals(CarPlayShutdownResult(false, false, true), result)
    }

    @Test
    fun timeOutAndRejectedRestoresAreNotReportedAsSuccess() {
        val result = runCarPlayShutdown({ false }, {}, { false }, { false }, { _, _ -> })
        assertFalse(result.connectionClosed)
        assertFalse(result.bluetoothRestored)
        assertFalse(result.wifiSearchResumed)
    }
}
