package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.Configuration
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayAppearanceMonitorTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://carsettings/global")
    private lateinit var provider: AppearanceProvider
    private var monitor: CarPlayAppearanceMonitor? = null
    private val changes = mutableListOf<Boolean>()
    private val diagnostics = mutableListOf<String>()

    /** Units that never define an Android night mode fall back to the vehicle keys. */
    private var liveUiMode = Configuration.UI_MODE_NIGHT_UNDEFINED

    @Before
    fun setUp() {
        provider = AppearanceProvider().apply {
            attachInfo(app, ProviderInfo().apply { authority = "carsettings" })
        }
        ShadowContentResolver.registerProviderInternal("carsettings", provider)
    }

    @After
    fun tearDown() { monitor?.stop(); idle() }

    private fun start(uiMode: Int = liveUiMode): CarPlayAppearanceMonitor =
        CarPlayAppearanceMonitor(app, Handler(Looper.getMainLooper()), uiMode,
            diagnostic = diagnostics::add, suppliedWorker = Handler(Looper.getMainLooper()),
            uiModeReader = { liveUiMode },
            onDarkModeChanged = changes::add).also { monitor = it; it.start(); idle() }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun notifyChange() { app.contentResolver.notifyChange(uri, null); idle() }

    @Test
    fun fixedModeChangesWithoutAndroidConfigurationChange() {
        provider.values["sys_screen_mode"] = "1"
        start()
        provider.values["sys_screen_mode"] = "2"
        notifyChange()
        provider.values["sys_screen_mode"] = "1"
        notifyChange()
        assertEquals(listOf(false, true, false), changes)
        assertTrue(diagnostics.any { "source=vehicle" in it && "resolved=dark" in it })
    }

    @Test
    fun automaticModeFollowsVehicleSignalWhenAndroidDoesNotDefineOne() {
        provider.values.putAll(mapOf("sys_screen_mode" to "0", "sys_day_or_night" to "1"))
        start()
        provider.values["sys_day_or_night"] = "0"
        notifyChange()
        provider.values["sys_day_or_night"] = "1"
        notifyChange()
        assertEquals(listOf(false, true, false), changes)
    }

    @Test
    fun vehicleKeysWinEvenWhenAndroidDefinesAMode() {
        // The keys flip with the car while uiMode trails by about a second: the keys must decide,
        // otherwise the head unit reports the previous appearance first.
        provider.values["sys_screen_mode"] = "1"
        liveUiMode = Configuration.UI_MODE_NIGHT_YES
        start(Configuration.UI_MODE_NIGHT_YES)
        idle(2_000)
        assertEquals(listOf(false), changes)
        assertTrue(diagnostics.last().startsWith("CarPlay appearance source=vehicle"))
        assertTrue(diagnostics.last().contains("vehicle=light resolved=light"))
    }

    @Test
    fun pollingCoversProvidersThatDoNotNotifyChanges() {
        provider.values["sys_screen_mode"] = "1"
        start()
        provider.values["sys_screen_mode"] = "2"
        idle(1000)
        assertEquals(listOf(false, true), changes)
    }

    @Test
    fun liveAndroidModeChangesWithoutAConfigurationCallback() {
        liveUiMode = Configuration.UI_MODE_NIGHT_NO
        start()
        liveUiMode = Configuration.UI_MODE_NIGHT_YES
        idle(5_000)
        assertEquals(listOf(false, true), changes)
        assertTrue(diagnostics.last().contains("source=uiMode"))
    }

    @Test
    fun initialReadUsesCurrentModeInsteadOfTheInitialSnapshot() {
        liveUiMode = Configuration.UI_MODE_NIGHT_YES
        start(Configuration.UI_MODE_NIGHT_NO)
        assertEquals(listOf(true), changes)
    }

    @Suppress("DEPRECATION")
    @Test
    fun applicationModeRemainsLiveWhenTheActivityConfigurationIsOverridden() {
        val initial = Configuration(app.resources.configuration).apply {
            uiMode = Configuration.UI_MODE_NIGHT_NO
        }
        app.resources.updateConfiguration(initial, app.resources.displayMetrics)
        val wrappedContext = app.createConfigurationContext(Configuration(initial))
        monitor = CarPlayAppearanceMonitor(
            wrappedContext, Handler(Looper.getMainLooper()), initial.uiMode,
            suppliedWorker = Handler(Looper.getMainLooper()), onDarkModeChanged = changes::add,
        ).also { it.start() }
        idle()
        val next = Configuration(initial).apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
        app.resources.updateConfiguration(next, app.resources.displayMetrics)
        idle(5_000)
        assertEquals(
            Configuration.UI_MODE_NIGHT_NO,
            wrappedContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
        )
        assertEquals(listOf(false, true), changes)
    }

    @Test
    fun providerFailureFallsBackAndRecoversWithBoundedDiagnostics() {
        provider.fail = true
        val monitor = start()
        liveUiMode = Configuration.UI_MODE_NIGHT_YES
        monitor.updateUiMode(Configuration.UI_MODE_NIGHT_YES)
        idle(15000)
        assertEquals(listOf(false, true), changes)
        assertEquals(1, diagnostics.count { "query failed" in it })
        liveUiMode = Configuration.UI_MODE_NIGHT_UNDEFINED
        provider.fail = false
        provider.values["sys_screen_mode"] = "1"
        notifyChange()
        assertEquals(listOf(false, true, false), changes)
        assertTrue(diagnostics.last().contains("source=vehicle"))
    }

    @Test
    fun stableStateProducesNoRepeatedCallbacksOrLogLines() {
        provider.values["sys_screen_mode"] = "2"
        start()
        idle(10000)
        assertEquals(listOf(true), changes)
        assertEquals(1, diagnostics.size)
    }

    @Test
    fun stopCancelsPollingAndPendingMainThreadCallbacks() {
        provider.values["sys_screen_mode"] = "1"
        val monitor = start()
        val queries = provider.queries
        monitor.stop()
        provider.values["sys_screen_mode"] = "2"
        notifyChange()
        idle(10000)
        assertEquals(queries, provider.queries)
        assertEquals(listOf(false), changes)
    }

    class AppearanceProvider : ContentProvider() {
        val values = mutableMapOf<String, String>()
        var fail = false
        var queries = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            if (fail) throw SecurityException("provider unavailable")
            return MatrixCursor(arrayOf("value")).apply {
                values[selectionArgs?.singleOrNull()]?.let { addRow(arrayOf(it)) }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
