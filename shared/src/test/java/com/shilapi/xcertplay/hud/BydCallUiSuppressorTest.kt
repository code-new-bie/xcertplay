package com.shilapi.xcertplay.hud

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydCallUiSuppressorTest {
    private lateinit var app: Application
    private var suppressor: BydCallUiSuppressor? = null

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        BydCallUiSettings.prefs(app).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH)
    }

    @After
    fun tearDown() {
        suppressor?.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun install(packageName: String) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
        })
    }

    private fun start(): BydCallUiSuppressor {
        install("com.byd.bydlogtool")
        BydCallUiSettings.setEnabled(app, true)
        return BydCallUiSuppressor(app).also {
            suppressor = it
            it.updateUsage(visible = true, connected = true)
        }
    }

    private fun broadcasts(): List<Intent> = shadowOf(app).broadcastIntents.filter {
        it.action == "com.hicar.action.CALL_STATE"
    }

    private fun states(): List<String?> = broadcasts().map { it.getStringExtra("sys.hicar.callstate") }

    @Test
    fun defaultOffAndPhonePackageAloneDoNotEnableFeature() {
        install(BydCallUiSettings.PHONE_PACKAGE)
        assertFalse(BydCallUiSettings.enabled(app))
        assertFalse(BydSettingsAvailability.available(app))
        BydCallUiSettings.setEnabled(app, true)
        suppressor = BydCallUiSuppressor(app)
        suppressor!!.updateUsage(visible = true, connected = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(broadcasts().isEmpty())
    }

    @Test
    fun logToolEnablesCallSetting() {
        install("com.byd.bydlogtool")
        assertTrue(BydSettingsAvailability.available(app))
        BydCallUiSettings.setEnabled(app, true)
        suppressor = BydCallUiSuppressor(app)
        suppressor!!.updateUsage(visible = true, connected = true)
        assertEquals(listOf("1"), states())
    }

    @Test
    fun otherBydPackagesAloneDoNotEnableCallSetting() {
        install("com.byd.amapservice")
        install("com.ts.car.someip.service")
        install("com.byd.clusterdebug")
        assertFalse(BydSettingsAvailability.available(app))
        BydCallUiSettings.setEnabled(app, true)
        suppressor = BydCallUiSuppressor(app)
        suppressor!!.updateUsage(visible = true, connected = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(broadcasts().isEmpty())
    }

    @Test
    fun requiresVisibleConnectedCarPlayAndSendsOnlyToStockPhone() {
        install("com.byd.bydlogtool")
        BydCallUiSettings.setEnabled(app, true)
        suppressor = BydCallUiSuppressor(app)
        suppressor!!.updateUsage(visible = false, connected = true)
        suppressor!!.updateUsage(visible = true, connected = false)
        assertTrue(broadcasts().isEmpty())
        suppressor!!.updateUsage(visible = true, connected = true)
        assertEquals(listOf("1"), states())
        assertEquals(BydCallUiSettings.PHONE_PACKAGE, broadcasts().single().`package`)
    }

    @Test
    fun callEventsHaveBoundedRetriesAndNoRepeatingTimer() {
        start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf("1", "1", "1", "1"), states())
        app.sendBroadcast(Intent(BydCallUiSuppressor.CALL_CHANGED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(8, broadcasts().size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertEquals(8, broadcasts().size)
    }

    @Test
    fun leavingPageCancelsRetriesRestoresOnceAndUnregistersCallObserver() {
        val bridge = start()
        bridge.updateUsage(visible = false, connected = true)
        app.sendBroadcast(Intent(BydCallUiSuppressor.CALL_CHANGED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf("1", "0"), states())
        bridge.close()
        assertEquals(listOf("1", "0"), states())
    }

    @Test
    fun disablingWhileActiveRestoresAndCancelsPendingHides() {
        start()
        BydCallUiSettings.setEnabled(app, false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf("1", "0"), states())
    }

    @Test
    fun disconnectRestoresAndReturningToCarPlayStartsAgain() {
        val bridge = start()
        bridge.updateUsage(visible = true, connected = false)
        assertEquals(listOf("1", "0"), states())
        bridge.updateUsage(visible = true, connected = true)
        assertEquals(listOf("1", "0", "1"), states())
        bridge.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf("1", "0", "1", "0"), states())
    }
}
