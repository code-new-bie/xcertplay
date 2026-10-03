package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.adb.AdbPacket
import com.shilapi.xcertplay.adb.LocalAdb
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BydSdkStreamTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test fun stopDoesNotWaitForAnAdbConnectionStillAuthenticating() {
        ServerSocket(0).use { server ->
            val connected = CountDownLatch(1)
            val finishHandshake = CountDownLatch(1)
            val done = CountDownLatch(1)
            val peerSocket = AtomicReference<Socket?>()
            val peer = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        peerSocket.set(socket)
                        AdbPacket.read(socket.getInputStream())
                        connected.countDown()
                        finishHandshake.await(2, TimeUnit.SECONDS)
                        runCatching {
                            socket.getOutputStream().write(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION,
                                AdbPacket.MAX_PAYLOAD, "device::\u0000".toByteArray()).encode())
                            AdbPacket.read(socket.getInputStream())
                        }
                    }
                } finally { done.countDown() }
            }
            val source = BydSdkStream(RuntimeEnvironment.getApplication(), "speed", {},
                newClient = { LocalAdb(key, port = server.localPort) })
            try {
                source.start()
                assertTrue(connected.await(2, TimeUnit.SECONDS))
                val started = System.nanoTime()
                source.close()
                assertTrue("Stopping a provider must not wait on the iAP2 thread", System.nanoTime() - started < 500_000_000L)
                finishHandshake.countDown()
                assertTrue(done.await(2, TimeUnit.SECONDS))
            } finally {
                source.close()
                finishHandshake.countDown()
                peerSocket.get()?.close()
                peer.join(2000)
            }
        }
    }

    @Test fun anEarlyHelperExitDoesNotStartARapidProcessRestartLoop() {
        ServerSocket(0).use { server ->
            val backedOff = CountDownLatch(1)
            val attempts = AtomicInteger()
            val progress = CopyOnWriteArrayList<String>()
            val app = RuntimeEnvironment.getApplication()
            app.applicationInfo.sourceDir = "/data/app/xcertplay/base.apk"
            val peer = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    AdbPacket.read(input)
                    output.write(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION,
                        AdbPacket.MAX_PAYLOAD, "device::\u0000".toByteArray()).encode())
                    val open = AdbPacket.read(input)
                    output.write(AdbPacket(AdbPacket.OKAY, 77, open.arg0, ByteArray(0)).encode())
                    output.write(AdbPacket(AdbPacket.CLSE, 77, open.arg0, ByteArray(0)).encode())
                    AdbPacket.read(input)
                }
            }
            val source = BydSdkStream(app, "battery", {},
                onProgress = { progress += it; if (it.contains("SDK helper ended early")) backedOff.countDown() },
                newClient = { attempts.incrementAndGet(); LocalAdb(key, port = server.localPort) })
            try {
                source.start()
                assertTrue(progress.joinToString(), backedOff.await(2, TimeUnit.SECONDS))
                assertEquals(1, attempts.get())
            } finally { source.close(); peer.join(2000) }
        }
    }
}
