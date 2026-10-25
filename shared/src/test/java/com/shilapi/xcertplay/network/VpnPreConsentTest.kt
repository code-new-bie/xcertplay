package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.adb.AdbPacket
import com.shilapi.xcertplay.adb.LocalAdb
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VpnPreConsentTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun approvedAdbAllowsThisAppsVpn() {
        val commands = CopyOnWriteArrayList<String>()
        ServerSocket(0).use { server ->
            val peer = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    AdbPacket.read(input)
                    output.write(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_PAYLOAD,
                        "device::\u0000".toByteArray()).encode())
                    val open = AdbPacket.read(input)
                    commands += String(open.payload).trimEnd('\u0000')
                    output.write(AdbPacket(AdbPacket.OKAY, 9, open.arg0, ByteArray(0)).encode())
                    output.write(AdbPacket(AdbPacket.CLSE, 9, open.arg0, ByteArray(0)).encode())
                    runCatching { AdbPacket.read(input) }
                }
            }
            val access = VpnPreConsent.grant(app, LocalAdb(key, port = server.localPort))
            peer.join(2000)
            assertEquals(LocalAdb.Access.READY, access)
        }
        assertEquals(listOf("shell:cmd appops set ${app.packageName} ACTIVATE_VPN allow"), commands)
    }

    @Test fun withoutApprovalNothingRunsAndNothingAsks() {
        ServerSocket(0).use { server ->
            val opened = CopyOnWriteArrayList<Int>()
            val peer = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    AdbPacket.read(input)
                    output.write(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_TOKEN, 0, ByteArray(20)).encode())
                    AdbPacket.read(input) // The signature, which this adbd does not know.
                    output.write(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_TOKEN, 0, ByteArray(20)).encode())
                    // A public key offered here would open the approval dialog in the car.
                    runCatching { opened += AdbPacket.read(input).arg0 }
                }
            }
            val access = VpnPreConsent.grant(app, LocalAdb(key, port = server.localPort))
            peer.join(2000)
            assertEquals(LocalAdb.Access.NOT_APPROVED, access)
            assertEquals(emptyList<Int>(), opened)
        }
    }

    @Test fun theSettingIsOnByDefault() {
        assertEquals(true, VpnPreConsent.enabled(app))
        VpnPreConsent.setEnabled(app, false)
        assertEquals(false, VpnPreConsent.enabled(app))
    }
}
