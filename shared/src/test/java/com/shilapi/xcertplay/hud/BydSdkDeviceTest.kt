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

    @Test
    fun aFailedReadSaysWhatTheSdkThrewAndWhere() {
        val reason = BydSdkDevice.describe(device.tryReadInt("failing").exceptionOrNull()!!)
        assertTrue(reason, reason.startsWith("java.lang.IllegalStateException: not allowed at "))
        assertTrue(reason, "FakeDevice.failing" in reason)
    }

    @Test
    fun aMissingMethodIsNamedAsSuch() {
        val reason = BydSdkDevice.describe(device.tryReadInt("missing").exceptionOrNull()!!)
        assertTrue(reason, reason.startsWith("java.lang.NoSuchMethodException"))
    }

    @Test
    fun aListenerThatCannotRegisterSaysWhy() {
        val reason = BydSdkDevice.describe(device.tryListen("com.example.NoSuchListener") {}.exceptionOrNull()!!)
        assertTrue(reason, reason.startsWith("java.lang.ClassNotFoundException"))
    }

    @Test
    fun aSuccessfulReadAndListenGiveResults() {
        fake.gear = 3
        assertEquals(3, device.tryReadInt("getGearboxAutoModeType").getOrNull())
        assertNotNull(device.tryListen(FakeListener::class.java.name) {}.getOrNull())
    }

    @Test
    fun describeUnwrapsReflectionAndCutsALongMessage() {
        val inner = SecurityException("x".repeat(500))
        val wrapped = java.lang.reflect.InvocationTargetException(inner)
        val reason = BydSdkDevice.describe(RuntimeException(wrapped).cause!!)
        assertTrue(reason, reason.startsWith("java.lang.SecurityException: " + "x".repeat(160) + " at "))
        assertTrue(reason, "x".repeat(161) !in reason)
    }

    @Test
    fun describeHandlesAnErrorWithoutAMessageOrFrames() {
        val bare = object : RuntimeException() {
            override fun fillInStackTrace(): Throwable = this
        }
        assertEquals(bare.javaClass.name, BydSdkDevice.describe(bare))
    }
}
