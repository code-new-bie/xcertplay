package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessHostAddressTest {
    private val lan = InetAddress.getByName("192.168.1.20")
    private val linkLocal = InetAddress.getByName("fe80::1234")

    @Test
    fun prefersTheRoutableIpv4Address() {
        val selected = wirelessHostAddress(listOf(linkLocal, lan), interfaceIndex = 7)
        assertEquals(lan, selected)
    }

    @Test
    fun fallsBackToTheLinkLocalAddressWithTheInterfaceScope() {
        val selected = wirelessHostAddress(listOf(linkLocal), interfaceIndex = 7) as Inet6Address
        assertTrue(selected.isLinkLocalAddress)
        assertEquals(7, selected.scopeId)
    }

    @Test
    fun leavesTheScopeOffWithoutAnInterfaceIndex() {
        val selected = wirelessHostAddress(listOf(linkLocal), interfaceIndex = 0) as Inet6Address
        assertEquals(0, selected.scopeId)
    }

    @Test
    fun ignoresLoopbackAutoconfiguredAndMulticastAddresses() {
        val unusable = listOf(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("169.254.10.2"),
            InetAddress.getByName("224.0.0.251"),
            InetAddress.getByName("2001:db8::1"),
        )
        assertNull(wirelessHostAddress(unusable, interfaceIndex = 7))
        assertEquals(emptyList<InetAddress>(), existingWifiHostAddresses(unusable, interfaceIndex = 7))
    }

    @Test
    fun existingWifiPublishesBothFamiliesIpv4First() {
        val hosts = existingWifiHostAddresses(listOf(linkLocal, lan), interfaceIndex = 7)
        assertEquals(2, hosts.size)
        assertEquals(lan, hosts[0])
        val secondary = hosts[1] as Inet6Address
        assertTrue(secondary.isLinkLocalAddress)
        assertEquals(7, secondary.scopeId)
    }

    @Test
    fun existingWifiKeepsASingleFamilyWhenTheOtherIsMissing() {
        assertEquals(listOf(lan), existingWifiHostAddresses(listOf(lan), interfaceIndex = 7))
        val onlyV6 = existingWifiHostAddresses(listOf(linkLocal), interfaceIndex = 7)
        assertEquals(1, onlyV6.size)
        assertTrue(onlyV6.single() is Inet6Address)
    }

    @Test
    fun existingWifiDropsTheIpv6AddressWhenItCannotBeScoped() {
        assertEquals(listOf(lan), existingWifiHostAddresses(listOf(linkLocal, lan), interfaceIndex = 0))
    }
}
