package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydSdkDeviceTest {
    /** Stands in for android.hardware.IBYDAutoListener. */
    interface FakeListener {
        fun onDataChanged(event: Any?)
    }

    /** Stands in for a BYD SDK device: a getter and IBYDAutoListener registration. */
    class FakeDevice {
        var gear = 1
        val listeners = mutableListOf<FakeListener>()

        fun getGearboxAutoModeType(): Int = gear

        fun failing(): Int = throw IllegalStateException("not allowed")

        fun registerListener(listener: FakeListener) {
            listeners += listener
        }

        fun unregisterListener(listener: FakeListener) {
            listeners -= listener
        }
    }

    private val fake = FakeDevice()
    private val device = BydSdkDevice(FakeDevice::class.java, fake)

    @Test
    fun gettersAreReadAndFailuresAreNull() {
        assertEquals(1, device.readInt("getGearboxAutoModeType"))
        fake.gear = 4
        assertEquals(4, device.readInt("getGearboxAutoModeType"))
        assertNull(device.readInt("failing"))
        assertNull(device.readInt("missing"))
    }

    @Test
    fun aRegisteredListenerSignalsEveryEventAndUnregisters() {
        var events = 0
        val handle = device.listen(FakeListener::class.java.name) { events++ }
        assertNotNull(handle)
        val listener = fake.listeners.single()
        listener.onDataChanged(null)
        listener.onDataChanged("gear")
        assertEquals(2, events)
        assertTrue(listener == listener)
        handle!!.close()
        assertTrue(fake.listeners.isEmpty())
    }

    @Test
    fun aListenerThatCannotRegisterIsNull() {
        assertNull(device.listen("com.example.NoSuchListener") {})
    }

    @Test
    fun aFailingCallbackDoesNotReachTheSdk() {
        device.listen(FakeListener::class.java.name) { throw IllegalStateException("boom") }
        fake.listeners.single().onDataChanged(null)
    }
}
