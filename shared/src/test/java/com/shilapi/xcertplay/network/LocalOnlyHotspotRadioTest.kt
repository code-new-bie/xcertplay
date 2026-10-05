package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class LocalOnlyHotspotRadioTest {
    @Test fun legacyBandsAreNotSoftApBitMasks() {
        assertEquals("2.4 GHz", LocalOnlyHotspotRadio.legacyBandLabel(0, 0))
        assertEquals("5 GHz", LocalOnlyHotspotRadio.legacyBandLabel(1, 0))
        assertEquals("Automatic band", LocalOnlyHotspotRadio.legacyBandLabel(-1, 0))
        assertEquals("Unknown band", LocalOnlyHotspotRadio.legacyBandLabel(null, 0))
    }

    @Test fun onlyLiveMatchingApOutputSuppliesFrequency() {
        val output = "Interface wlan1\n\tifindex 12\n\ttype AP\n\tchannel 149 (5745 MHz), width: 80 MHz"
        val channel = LocalOnlyHotspotRadio.parseIw(output, "wlan1")!!
        assertEquals(149, channel.number)
        assertEquals(5745, channel.frequencyMHz)
        assertEquals("5 GHz", channel.bandLabel)
        assertNull(LocalOnlyHotspotRadio.parseIw(output, "wlan0"))
        assertNull(LocalOnlyHotspotRadio.parseIw(output.replace("type AP", "type managed"), "wlan1"))
        assertNull(LocalOnlyHotspotRadio.parseIw("iw: not found", "wlan1"))
    }
}
