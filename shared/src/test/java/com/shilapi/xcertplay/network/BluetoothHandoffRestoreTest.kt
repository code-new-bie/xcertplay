package com.shilapi.xcertplay.network

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Looper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BluetoothHandoffRestoreTest {
    private lateinit var app: Application
    private lateinit var handoff: BluetoothHandoff
    private lateinit var calls: TestProfile

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        BluetoothHandoffSettings.prefs(app).edit().clear().putBoolean("stuck_off_repaired", true).commit()
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        calls = TestProfile()
        shadowOf(adapter).setProfileProxy(16, calls)
        shadowOf(adapter).setProfileProxy(11, TestProfile())
        handoff = ReflectionHelpers.callConstructor(
            BluetoothHandoff::class.java, ClassParameter.from(Context::class.java, app),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun queuedHoldIsReleasedBeforeExitReportsSuccess() {
        handoff.hold(ADDRESS, calls = true, audio = false)
        assertTrue(restore(checkWaitingForMain = true))
        assertEquals(100, calls.priority)
        assertFalse(prefs().contains("held_address"))
    }

    @Test
    fun rejectedPriorityRestoreRemainsPending() {
        hold()
        calls.rejectRestore = true
        assertFalse(restore())
        assertEquals(0, calls.priority)
        assertTrue(prefs().contains("saved_priority_calls"))
        assertEquals(ADDRESS, prefs().getString("held_address", null))
    }

    @Test
    fun acceptedSetterWithAnUnchangedPriorityRemainsPending() {
        hold()
        calls.ignoreRestore = true
        assertFalse(restore())
        assertTrue(prefs().contains("saved_priority_calls"))
        assertEquals(0, calls.connects)
    }

    @Test
    fun rejectedReconnectCanBeRetriedWithoutLosingTheOriginalPriority() {
        hold()
        calls.acceptConnect = false
        assertFalse(restore())
        assertEquals(100, calls.priority)
        assertTrue(prefs().contains("saved_priority_calls"))
        calls.acceptConnect = true
        assertTrue(restore())
        assertFalse(prefs().contains("saved_priority_calls"))
    }

    @Test
    fun alreadyConnectedPhoneDoesNotNeedAnotherConnectRequest() {
        hold()
        calls.state = BluetoothProfile.STATE_CONNECTED
        calls.acceptConnect = false
        assertTrue(restore())
        assertEquals(0, calls.connects)
    }

    private fun hold() {
        handoff.hold(ADDRESS, calls = true, audio = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, calls.priority)
    }

    private fun prefs() = BluetoothHandoffSettings.prefs(app)

    private fun restore(checkWaitingForMain: Boolean = false): Boolean {
        val result = AtomicReference<Boolean?>()
        val failure = AtomicReference<Throwable?>()
        val started = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            started.countDown()
            try {
                result.set(handoff.releaseNowBlocking(500))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        started.await()
        if (checkWaitingForMain) {
            worker.join(30)
            assertTrue("Exit must wait for its main-thread release request", worker.isAlive)
            assertNull(result.get())
        }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (worker.isAlive && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
        if (worker.isAlive) worker.interrupt()
        worker.join(500)
        failure.get()?.let { throw AssertionError(it) }
        return requireNotNull(result.get()) { "Bluetooth restore did not finish" }
    }

    /** Public methods stand in for BYD's hidden Bluetooth profile API. */
    class TestProfile : BluetoothProfile {
        var priority = 100
        var state = BluetoothProfile.STATE_DISCONNECTED
        var connects = 0
        var rejectRestore = false
        var ignoreRestore = false
        var acceptConnect = true

        fun getPriority(device: BluetoothDevice): Int = priority
        fun setPriority(device: BluetoothDevice, value: Int): Boolean {
            if (value > 0 && rejectRestore) return false
            if (value == 0 || !ignoreRestore) priority = value
            return true
        }
        fun connect(device: BluetoothDevice): Boolean { connects++; return acceptConnect }
        fun disconnect(device: BluetoothDevice): Boolean { state = BluetoothProfile.STATE_DISCONNECTED; return true }
        override fun getConnectionState(device: BluetoothDevice): Int = state
        override fun getConnectedDevices(): List<BluetoothDevice> = emptyList()
        override fun getDevicesMatchingConnectionStates(states: IntArray): List<BluetoothDevice> = emptyList()
    }

    private companion object {
        const val ADDRESS = "14:1A:97:73:B3:F6"
    }
}
