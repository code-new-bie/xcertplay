package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayControllerCloseTest {
    private val airPlay = AirPlayConfig(
        deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
        sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
    )

    @Test
    fun mediaCleanupFailureDoesNotSkipHotspotRemoval() = withConnection(
        media = object : AirPlayMediaHandler {
            override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
                throw IOException("media cleanup failed")
            }
        },
    ) { controller, socket, peer ->
        val removed = CountDownLatch(1)
        ReflectionHelpers.setField(controller, "hotspot", hotspot { removed.countDown() })
        controller.close()
        assertFalse(controller.awaitClosed(2_000))
        assertEquals(0L, removed.count)
        assertTrue(socket.isClosed)
        assertEquals(-1, peer.getInputStream().read())
    }

    @Test
    fun closeDisconnectsThePeerBeforeWaitingForHotspotRemoval() = withConnection { controller, socket, peer ->
        val removing = CountDownLatch(1)
        val allowRemoval = CountDownLatch(1)
        ReflectionHelpers.setField(controller, "hotspot", hotspot {
            removing.countDown()
            check(allowRemoval.await(2, TimeUnit.SECONDS))
        })
        try {
            controller.close()
            assertTrue(removing.await(2, TimeUnit.SECONDS))
            assertTrue(socket.isClosed)
            assertEquals(-1, peer.getInputStream().read())
            assertFalse(controller.awaitClosed(0))
        } finally {
            allowRemoval.countDown()
        }
        assertTrue(controller.awaitClosed(2_000))
    }

    @Test
    fun failedHotspotRemovalStillDisconnectsAndIsReportedAsFailure() = withConnection { controller, socket, peer ->
        ReflectionHelpers.setField(controller, "hotspot", hotspot { throw IOException("radio rejected removal") })
        controller.close()
        assertFalse(controller.awaitClosed(2_000))
        assertTrue(socket.isClosed)
        assertEquals(-1, peer.getInputStream().read())
    }

    @Test
    fun aSessionArrivingAfterCloseIsImmediatelyDisconnected() = withConnection { controller, socket, peer ->
        ReflectionHelpers.setField(controller, "activeSession", null)
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        val listener = ReflectionHelpers.getField<AirPlaySessionListener>(controller, "sessionListener")
        listener.onSessionActive(session(socket))
        assertTrue(socket.isClosed)
        assertEquals(-1, peer.getInputStream().read())
    }

    private fun withConnection(
        media: AirPlayMediaHandler = object : AirPlayMediaHandler {},
        test: (CarPlayController, Socket, Socket) -> Unit,
    ) {
        val controller = CarPlayController(
            context = RuntimeEnvironment.getApplication(),
            config = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.REMOTE, remoteMfiServer = "https://mfi.example.test",
                transport = CarPlayTransport.WIRELESS,
                identification = Iap2IdentificationConfig(
                    name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                    firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
                ),
            ),
            airPlayConfig = airPlay, identity = AirPlayIdentity.generate(), pairings = PairingStore(),
            listener = object : AirPlaySessionListener {}, media = media,
            reportStatus = {},
        )
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                Socket(server.inetAddress, server.localPort).use { peer ->
                    peer.soTimeout = 2_000
                    server.accept().use { socket ->
                        ReflectionHelpers.setField(controller, "activeSession", session(socket))
                        test(controller, socket, peer)
                    }
                }
            }
        } finally {
            controller.close()
            controller.awaitClosed(2_000)
        }
    }

    private fun session(socket: Socket) = AirPlaySession(
        socket, airPlay, AirPlayIdentity.generate(), PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
    )

    private fun hotspot(close: () -> Unit) = object : WirelessHotspotManager {
        override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("Not used by close tests")
        override fun close() = close.invoke()
    }
}
