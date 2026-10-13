package com.shilapi.xcertplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.network.ExistingWifiManager
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ExistingWifiManagerTest {
    private val context = mock(Context::class.java)
    private val connectivity = mock(ConnectivityManager::class.java)
    private val wifi = mock(WifiManager::class.java)
    private val info = mock(WifiInfo::class.java)
    private val network = mock(Network::class.java)
    private val iface = NetworkInterface.getNetworkInterfaces().toList().first { !it.isLoopback }
    private lateinit var properties: LinkProperties
    private lateinit var callback: ConnectivityManager.NetworkCallback
    private var changes = 0
    private val diagnostics = mutableListOf<String>()

    @Before
    fun setup() {
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(connectivity)
        `when`(context.getSystemService(WifiManager::class.java)).thenReturn(wifi)
        `when`(connectivity.allNetworks).thenReturn(arrayOf(network))
        // No INTERNET or VALIDATED capability: neither is a requirement for local CarPlay.
        doReturn(capabilities(NetworkCapabilities.TRANSPORT_WIFI)).`when`(connectivity).getNetworkCapabilities(network)
        properties = link("192.0.2.10/24")
        `when`(connectivity.getLinkProperties(network)).thenAnswer { properties }
        `when`(wifi.connectionInfo).thenReturn(info)
        `when`(info.ssid).thenReturn("\"Pocket Wi-Fi\"")
        `when`(info.frequency).thenReturn(5180)
        `when`(info.bssid).thenReturn("aa:bb:cc:dd:ee:ff")
        if (Build.VERSION.SDK_INT >= 31) `when`(info.currentSecurityType).thenReturn(WifiInfo.SECURITY_TYPE_PSK)
        doAnswer { callback = it.getArgument(1); null }.`when`(connectivity)
            .registerNetworkCallback(any(NetworkRequest::class.java), any(ConnectivityManager.NetworkCallback::class.java))
    }

    @Test
    fun usesTheStationAddressAndTheTypedCredentialsWithoutChangingWifiOrTheDefaultRoute() {
        manager().use { manager ->
            val result = start(manager)
            assertEquals(WirelessHotspotBackend.EXISTING_WIFI, result.backend)
            assertEquals("192.0.2.10", result.hostAddress!!.hostAddress)
            assertEquals(iface.name, result.interfaceName)
            assertEquals(36, result.channel)
            assertEquals(Iap2WirelessSecurity.WPA_WPA2, result.security)
            assertEquals("pocket-password", result.passphrase)
            // The router's BSSID must never replace the receiver's own AirPlay identity.
            assertNull(result.bssid)
            assertArrayEquals(
                byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte()),
                result.accessPointBssid,
            )
            assertTrue(diagnostics.none { it.contains("pocket-password") })
            assertTrue(diagnostics.any { it.contains("Existing Wi-Fi attached") })
            verify(connectivity, never()).bindProcessToNetwork(any())
            verify(connectivity, never()).requestNetwork(any(NetworkRequest::class.java), any(ConnectivityManager.NetworkCallback::class.java))
        }
        verify(connectivity).unregisterNetworkCallback(callback)
    }

    @Test
    fun ignoresCellularAndVpnNetworks() {
        val cellular = mock(Network::class.java)
        val vpn = mock(Network::class.java)
        `when`(connectivity.allNetworks).thenReturn(arrayOf(cellular, vpn, network))
        doReturn(capabilities(NetworkCapabilities.TRANSPORT_CELLULAR)).`when`(connectivity).getNetworkCapabilities(cellular)
        doReturn(capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN))
            .`when`(connectivity).getNetworkCapabilities(vpn)
        manager().use { assertEquals("192.0.2.10", start(it).hostAddress!!.hostAddress) }
    }

    @Test
    fun publishesIpv4AndKeepsTheScopedLinkLocalAsSecondary() {
        properties.setLinkAddresses(properties.linkAddresses + linkAddress("fe80::1234/64"))
        manager().use {
            val result = start(it)
            assertEquals("192.0.2.10", result.hostAddress!!.hostAddress)
            assertEquals(
                listOf("192.0.2.10", "fe80:0:0:0:0:0:0:1234"),
                result.hostAddresses.map { host -> host.hostAddress!!.substringBefore('%') },
            )
            val secondary = result.hostAddresses.last() as Inet6Address
            assertTrue(secondary.isLinkLocalAddress)
            assertEquals(iface.index, secondary.scopeId)
        }
    }

    @Test
    fun lossOfTheIpv4AddressRestartsEvenWhenTheLinkLocalOneRemains() {
        properties.setLinkAddresses(properties.linkAddresses + linkAddress("fe80::1234/64"))
        manager().use {
            start(it)
            properties.setLinkAddresses(listOf(linkAddress("fe80::1234/64")))
            callback.onLinkPropertiesChanged(network, properties)
            assertEquals(1, changes)
        }
    }

    @Test
    fun anUnreadableAccessPointAddressIsOmitted() {
        `when`(info.ssid).thenReturn(WifiManager.UNKNOWN_SSID)
        `when`(info.bssid).thenReturn("02:00:00:00:00:00")
        manager().use {
            assertNull(start(it).accessPointBssid)
            assertTrue(diagnostics.single { line -> line.contains("Existing Wi-Fi attached") }.contains("networkNameReadable=false"))
        }
    }

    @Test
    fun invalidZeroOrMulticastAccessPointAddressesAreNeverSent() {
        for (address in listOf("invalid", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:fb")) {
            `when`(info.bssid).thenReturn(address)
            manager().use { assertNull(start(it).accessPointBssid) }
        }
    }

    @Test
    @Config(sdk = [33])
    fun redactedCapabilitiesMustNotHideAReadableStationIdentity() {
        val redacted = mock(WifiInfo::class.java)
        `when`(redacted.ssid).thenReturn(WifiManager.UNKNOWN_SSID)
        val caps = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        `when`(caps.transportInfo).thenReturn(redacted)
        doReturn(caps).`when`(connectivity).getNetworkCapabilities(network)
        manager().use {
            val result = start(it)
            assertNotNull(result.accessPointBssid)
            assertTrue(diagnostics.single { line -> line.contains("Existing Wi-Fi attached") }.contains("networkNameReadable=true"))
        }
        `when`(info.ssid).thenReturn("\"Different network\"")
        manager().use { assertThrows(IOException::class.java) { start(it) } }
    }

    @Test
    @Config(sdk = [33])
    fun rejectsAnObservedOpenSecuredOrEnterpriseMismatch() {
        `when`(info.currentSecurityType).thenReturn(WifiInfo.SECURITY_TYPE_OPEN)
        manager().use { assertThrows(IOException::class.java) { start(it) } }
        manager("").use { assertEquals(Iap2WirelessSecurity.NONE, start(it).security) }
        `when`(info.currentSecurityType).thenReturn(WifiInfo.SECURITY_TYPE_PSK)
        manager("").use { assertThrows(IOException::class.java) { start(it) } }
        `when`(info.currentSecurityType).thenReturn(WifiInfo.SECURITY_TYPE_EAP)
        manager().use { assertThrows(IOException::class.java) { start(it) } }
    }

    @Test
    fun aRedactedNameAllowsTheTypedConfigurationAndAnUnknownChannelStaysZero() {
        `when`(info.ssid).thenReturn(WifiManager.UNKNOWN_SSID)
        `when`(info.frequency).thenReturn(-1)
        manager("").use {
            val result = start(it)
            assertEquals("Pocket Wi-Fi", result.ssid)
            assertEquals(0, result.channel)
            assertEquals(Iap2WirelessSecurity.NONE, result.security)
        }
    }

    @Test
    fun rejectsAWrongNetworkAndAMissingConnection() {
        `when`(info.ssid).thenReturn("\"Different network\"")
        manager().use {
            assertTrue(assertThrows(IOException::class.java) { start(it) }.message!!.contains("does not match"))
        }
        `when`(connectivity.allNetworks).thenReturn(emptyArray())
        manager().use {
            assertTrue(assertThrows(IOException::class.java) { start(it, 1) }.message!!.contains("not connected"))
        }
    }

    @Test
    fun restrictedWifiInfoStillUsesTheTypedNetwork() {
        `when`(wifi.connectionInfo).thenThrow(SecurityException("Wi-Fi information restricted"))
        manager().use {
            val result = start(it)
            assertEquals("Pocket Wi-Fi", result.ssid)
            assertEquals(0, result.channel)
            assertEquals("192.0.2.10", result.hostAddress!!.hostAddress)
        }
    }

    @Test
    fun reactsOnceToLossOrAnAddressChangeButIgnoresADnsChangeAndClose() {
        manager().use {
            start(it)
            properties.setDnsServers(listOf(InetAddress.getByName("192.0.2.1")))
            callback.onLinkPropertiesChanged(network, properties)
            assertEquals(0, changes)
            callback.onLinkPropertiesChanged(network, link("192.0.2.11/24"))
            callback.onLost(network)
            assertEquals(1, changes)
        }
        callback.onLost(network)
        assertEquals(1, changes)
        assertTrue(diagnostics.any { it.contains("address changed") })
    }

    @Test
    fun refusesAmbiguousWifiNetworksAndInvalidCredentials() {
        val other = mock(Network::class.java)
        `when`(connectivity.allNetworks).thenReturn(arrayOf(network, other))
        doReturn(capabilities(NetworkCapabilities.TRANSPORT_WIFI)).`when`(connectivity).getNetworkCapabilities(other)
        manager().use {
            assertTrue(assertThrows(IOException::class.java) { start(it) }.message!!.contains("Multiple"))
        }
        assertThrows(IllegalArgumentException::class.java) { manager("short") }
    }

    private fun manager(password: String = "pocket-password") =
        ExistingWifiManager(context, "Pocket Wi-Fi", password, diagnostics::add, { changes++ })

    private fun link(address: String) = LinkProperties().apply {
        interfaceName = iface.name
        setLinkAddresses(listOf(linkAddress(address)))
    }

    private fun linkAddress(address: String): LinkAddress = mock(LinkAddress::class.java).also {
        `when`(it.address).thenReturn(InetAddress.getByName(address.substringBefore('/')))
    }

    private fun capabilities(vararg transports: Int): NetworkCapabilities = mock(NetworkCapabilities::class.java).also {
        `when`(it.hasTransport(anyInt())).thenAnswer { call -> call.getArgument<Int>(0) in transports }
    }

    // start() refuses the main thread, which is where Robolectric runs the test.
    private fun start(manager: ExistingWifiManager, timeout: Long = 1000): WirelessHotspotInfo {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return executor.submit<WirelessHotspotInfo> { manager.start(timeout) }.get(3, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw error.cause!!
        } finally {
            executor.shutdownNow()
        }
    }
}
