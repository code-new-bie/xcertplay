package com.shilapi.xcertplay.network

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneHotspotAddressTest {
    @Test
    fun onlyTheIphoneHotspotRangeMatches() {
        for (address in listOf("172.20.10.1", "172.20.10.2", "172.20.10.14")) {
            assertTrue(address, IphoneHotspotAddress.matches(InetAddress.getByName(address)))
        }
        for (address in listOf("172.20.10.16", "172.20.11.2", "192.168.43.2", "10.0.0.2", "fe80::1")) {
            assertFalse(address, IphoneHotspotAddress.matches(InetAddress.getByName(address)))
        }
    }
}
