package com.shilapi.xcertplay.adb

import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdbCloseTest {
    @Test fun closeInterruptsAnAuthenticationReadWithoutWaitingForItsTimeout() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val peerDone = CountDownLatch(1)
            val peer = thread(isDaemon = true) {
                server.accept().use { socket ->
                    AdbPacket.read(socket.getInputStream())
                    accepted.countDown()
                    assertTrue(socket.getInputStream().read() < 0)
                }
                peerDone.countDown()
            }
            val adb = LocalAdb(key, port = server.localPort)
            val connecting = thread(isDaemon = true) { adb.connect(mayAsk = false) }
            assertTrue(accepted.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            adb.close()
            assertTrue(System.nanoTime() - started < 500_000_000L)
            assertTrue(peerDone.await(2, TimeUnit.SECONDS))
            connecting.join(2000)
            peer.join(2000)
        }
    }
}
