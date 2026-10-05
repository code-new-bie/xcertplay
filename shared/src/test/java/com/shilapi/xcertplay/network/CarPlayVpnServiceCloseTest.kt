package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayVpnServiceCloseTest {
    @Test
    fun listenerCloseFailureStillDisconnectsEveryAcceptedSession() {
        val service = CarPlayVpnService()
        val server = object : ServerSocket(0, 2, InetAddress.getLoopbackAddress()) {
            override fun close() {
                super.close()
                throw IOException("listener close failed")
            }
        }
        ReflectionHelpers.setField(service, "serverSocket", server)
        val sessions = ReflectionHelpers.getField<MutableSet<AirPlaySession>>(service, "sessions")
        Socket(server.inetAddress, server.localPort).use { firstPeer ->
            server.accept().use { first ->
                Socket(server.inetAddress, server.localPort).use { secondPeer ->
                    server.accept().use { second ->
                        firstPeer.soTimeout = 2_000
                        secondPeer.soTimeout = 2_000
                        sessions += session(first)
                        sessions += session(second)
                        assertThrows(IOException::class.java) { service.detach() }
                        assertTrue(server.isClosed)
                        assertEquals(-1, firstPeer.getInputStream().read())
                        assertEquals(-1, secondPeer.getInputStream().read())
                        assertTrue(sessions.isEmpty())
                        service.detach() // A repeated shutdown is safe.
                    }
                }
            }
        }
    }

    private fun session(socket: Socket) = AirPlaySession(
        socket,
        AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        AirPlayIdentity.generate(), PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
    )
}
