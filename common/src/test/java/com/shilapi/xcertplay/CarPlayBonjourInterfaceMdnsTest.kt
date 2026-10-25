package com.shilapi.xcertplay

import android.content.Context
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.network.CarPlayBonjour
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** The JmDNS path of [CarPlayBonjour], used for the existing Wi-Fi / same-LAN mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayBonjourInterfaceMdnsTest {
    private val context = mock(Context::class.java)
    private val wifi = mock(WifiManager::class.java)
    private val lock = mock(WifiManager.MulticastLock::class.java)
    private val ipv4 = InetAddress.getByName("192.0.2.10")
    private val ipv6 = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 7)
    private val config = AirPlayConfig(
        deviceName = "xcertplay",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
    )
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test-pairing")

    private fun setup() {
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(WifiManager::class.java)).thenReturn(wifi)
        `when`(wifi.createMulticastLock("carplay-bonjour")).thenReturn(lock)
        `when`(lock.isHeld).thenReturn(true)
    }

    /** DiPlay PR 972: an unnamed address made JmDNS ask DNS for its name, 20 s on a BYD Android 10 unit. */
    @Test
    fun addressesAreNamedBeforeJmDnsStartsForBothFamilies() {
        setup()
        val hostName = "carplay-020000000002"
        val received = mutableListOf<InetAddress>()
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(any(InetAddress::class.java), eq(hostName)) }
                .thenAnswer { invocation ->
                    val address = invocation.getArgument<InetAddress>(0)
                    // toString does not query DNS; it shows whether a name is already set.
                    assertEquals("$hostName/${address.hostAddress}", address.toString())
                    received.add(address)
                    mock(JmDNS::class.java)
                }
            CarPlayBonjour(context, config, identity, ipv4.hostAddress, true, additionalAddresses = listOf(ipv6))
                .use { it.start() }
        }
        assertEquals(listOf(ipv4, ipv6), received)
        assertEquals(ipv6.scopeId, (received[1] as Inet6Address).scopeId)
    }

    @Test
    fun publishesAndBrowsesBothFamiliesAndClosesBothRegistries() {
        setup()
        val v4 = mock(JmDNS::class.java)
        val v6 = mock(JmDNS::class.java)
        `when`(v4.inetAddress).thenReturn(ipv4)
        `when`(v6.inetAddress).thenReturn(ipv6)
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv6, "carplay-020000000002") }.thenReturn(v6)
            factory.`when`<JmDNS> { JmDNS.create(ipv4, "carplay-020000000002") }.thenReturn(v4)
            val bonjour = CarPlayBonjour(
                context, config, identity, ipv6.hostAddress, true,
                additionalAddresses = listOf(ipv4),
            )
            bonjour.start()
            assertEquals("IPv6,IPv4", bonjour.publishedFamilies)
            listOf(v6, v4).forEach { dns ->
                verify(dns).registerService(any(ServiceInfo::class.java))
                verify(dns).addServiceListener(eq("_carplay-ctrl._tcp.local."), any(ServiceListener::class.java))
            }
            bonjour.close()
            bonjour.close()
            verify(v6).close()
            verify(v4).close()
            verify(lock).release()
            assertEquals("none", bonjour.publishedFamilies)
        }
    }

    @Test
    fun theRegistryAddressControlsTheFamilyEvenWhenTheDeprecatedInterfaceReturnsTheOtherOne() {
        setup()
        val dns = mock(JmDNS::class.java)
        `when`(dns.inetAddress).thenReturn(ipv4)
        // Android can return an arbitrary address of the interface here.
        `when`(dns.getInterface()).thenReturn(ipv6)
        val info = mock(ServiceInfo::class.java)
        `when`(info.inetAddresses).thenReturn(arrayOf(InetAddress.getByName("192.0.2.20")))
        `when`(info.port).thenReturn(7000)
        val event = mock(ServiceEvent::class.java)
        `when`(event.dns).thenReturn(dns)
        `when`(event.info).thenReturn(info)
        `when`(event.name).thenReturn("test-phone")
        CarPlayBonjour(
            context, config, identity, ipv6.hostAddress, true,
            additionalAddresses = listOf(ipv4),
        ).use { bonjour ->
            val listener = ReflectionHelpers.getField<ServiceListener>(bonjour, "interfaceListener")
            listener.serviceResolved(event)
            val queue = ReflectionHelpers.getField<LinkedBlockingQueue<*>>(bonjour, "interfaceServices")
            assertEquals(1, queue.size)
            verify(dns, never()).getInterface()
        }
    }

    @Test
    fun probesUseTheLocalAddressOfTheTargetFamilyAndTheInterfaceScope() {
        setup()
        val bonjour = CarPlayBonjour(
            context, config, identity, ipv6.hostAddress, true,
            additionalAddresses = listOf(ipv4),
        )
        fun invoke(name: String, target: InetAddress): InetAddress = ReflectionHelpers.callInstanceMethod(
            bonjour, name, ClassParameter.from(InetAddress::class.java, target),
        )
        assertEquals(ipv4, invoke("sourceAddressFor", InetAddress.getByName("192.0.2.20")))
        assertEquals(ipv6, invoke("sourceAddressFor", InetAddress.getByName("fe80::20")))
        assertEquals(7, (invoke("applyLocalScope", InetAddress.getByName("fe80::20")) as Inet6Address).scopeId)
        bonjour.close()
    }

    @Test
    fun aSingleAddressPublishesOneRegistryAndNeverTouchesNsd() {
        setup()
        val dns = mock(JmDNS::class.java)
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv6, "carplay-020000000002") }.thenReturn(dns)
            CarPlayBonjour(context, config, identity, ipv6.hostAddress, true).use {
                it.start()
                assertEquals("IPv6", it.publishedFamilies)
                verify(dns).registerService(any(ServiceInfo::class.java))
            }
            factory.verify({ JmDNS.create(ipv6, "carplay-020000000002") }, times(1))
            verify(dns).close()
        }
        verify(context, never()).getSystemService(Context.NSD_SERVICE)
    }

    @Test
    fun aFailingSecondRegistryCleansUpTheFirstAndTheMulticastLock() {
        setup()
        val first = mock(JmDNS::class.java)
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv6, "carplay-020000000002") }.thenReturn(first)
            factory.`when`<JmDNS> { JmDNS.create(ipv4, "carplay-020000000002") }.thenThrow(IOException("bind failed"))
            val bonjour = CarPlayBonjour(
                context, config, identity, ipv6.hostAddress, true,
                additionalAddresses = listOf(ipv4),
            )
            assertThrows(IOException::class.java) { bonjour.start() }
            verify(first).close()
            verify(lock).release()
            assertEquals("none", bonjour.publishedFamilies)
        }
    }

    @Test
    fun theSystemNsdModeDoesNotTakeTheMulticastLockOrPublishThroughJmDns() {
        setup()
        mockStatic(JmDNS::class.java).use { factory ->
            // Only construction is checked: start() would need a real NsdManager.
            val bonjour = CarPlayBonjour(context, config, identity, ipv4.hostAddress)
            assertEquals("none", bonjour.publishedFamilies)
            bonjour.close()
            factory.verifyNoInteractions()
            verify(wifi, never()).createMulticastLock(any(String::class.java))
        }
    }
}
