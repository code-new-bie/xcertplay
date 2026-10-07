package com.shilapi.xcertplay

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BootLaunchTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        BootRestoreLog.file(app).delete()
    }

    @After
    fun tearDown() {
        repeat(3) { CarPlayHostLaunch.hostDestroyed() }
    }

    @Test
    fun usbAdapterDoesNotStartCarPlayWithAutoStartOff() {
        val activity = Robolectric.buildActivity(UsbAttachActivity::class.java, usbAttached()).create().get()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(bootLog().contains("auto-start off, CarPlay not started"))
    }

    @Test
    fun usbAdapterStartsCarPlayWithAutoStartOn() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        val activity = Robolectric.buildActivity(UsbAttachActivity::class.java, usbAttached()).create().get()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, started.component?.className)
        assertEquals(CarPlayHostLaunch.SOURCE_USB, CarPlayHostLaunch.source(started))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun usbAdapterLeavesARunningCarPlayScreenAlone() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        CarPlayHostLaunch.hostCreated()
        val activity = Robolectric.buildActivity(UsbAttachActivity::class.java, usbAttached()).create().get()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(bootLog().contains("CarPlay already running"))
    }

    @Test
    fun powerOnLeavesTheLeftoverToARunningCarPlayScreen() {
        leaveCallsOff()
        CarPlayHostLaunch.hostCreated()
        BootReceiver().onReceive(app, powerOn())
        assertTrue("Not restored behind the running session", handoffPrefs().contains("saved_priority_calls"))
        assertTrue(bootLog().contains("CarPlay already running"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun powerOnWithoutLeftoverOrAutoStartDoesNothing() {
        BootReceiver().onReceive(app, powerOn())
        assertFalse(BootRestoreLog.file(app).exists())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun powerOnWithAutoStartOpensCarPlayAndSaysWhy() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        BootReceiver().onReceive(app, powerOn())
        val started = shadowOf(app).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, started.component?.className)
        assertEquals(CarPlayHostLaunch.SOURCE_BOOT, CarPlayHostLaunch.source(started))
    }

    @Test
    fun launchSourceNamesTheAppIconAndOtherActions() {
        val icon = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        assertEquals("app icon", CarPlayHostLaunch.source(icon))
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, CarPlayHostLaunch.source(usbAttached()))
        assertEquals("unknown", CarPlayHostLaunch.source(null))
    }

    private fun usbAttached() = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)

    private fun powerOn() = Intent(Intent.ACTION_BOOT_COMPLETED).putExtra("from_quickboot", true)

    /** What a run killed by the head unit's power-off leaves for the iPhone's calls link. */
    private fun leaveCallsOff() {
        handoffPrefs().edit().putString("held_address", "14:1A:97:73:B3:F6").putInt("saved_priority_calls", 100).commit()
    }

    private fun handoffPrefs() = app.getSharedPreferences("xcertplay_bluetooth_handoff", Context.MODE_PRIVATE)

    private fun bootLog(): String = BootRestoreLog.file(app).takeIf { it.isFile }?.readText().orEmpty()
}
