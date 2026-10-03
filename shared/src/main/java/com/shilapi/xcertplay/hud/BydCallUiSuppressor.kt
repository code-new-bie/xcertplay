package com.shilapi.xcertplay.hud

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable

/**
 * Event-driven use of the stock phone's HiCar UI teardown. The broadcast does not set HiCar
 * properties or prevent subsequent window creation, so brief popup flashes remain possible.
 * All state and callbacks are confined to the main thread; no polling or background worker.
 */
class BydCallUiSuppressor(context: Context) : Closeable {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = BydCallUiSettings.prefs(app)
    private val supported = BydSettingsAvailability.available(app)
    private var visible = false
    private var connected = false
    private var active = false
    private var closed = false
    private val hide = Runnable { if (active && !closed) dispatch("1") }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == CALL_CHANGED && active) requestHide()
        }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BydCallUiSettings.KEY_ENABLED) handler.post { if (!closed) reconcile() }
    }

    init {
        if (supported) prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun updateUsage(visible: Boolean, connected: Boolean) {
        if (closed) return
        this.visible = visible
        this.connected = connected
        reconcile()
    }

    private fun reconcile() {
        val shouldHide = supported && visible && connected && BydCallUiSettings.enabled(app)
        if (shouldHide == active) return
        if (shouldHide) {
            try {
                val filter = IntentFilter(CALL_CHANGED)
                // The HFP broadcast originates from the Bluetooth app, not from xcertplay.
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(receiver, filter, Manifest.permission.BLUETOOTH, handler,
                        Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    app.registerReceiver(receiver, filter, Manifest.permission.BLUETOOTH, handler)
                }
                active = true
                requestHide()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Cannot observe stock Bluetooth call UI", error)
            }
        } else {
            stop()
        }
    }

    private fun requestHide() {
        handler.removeCallbacks(hide)
        dispatch("1")
        // Receiver ordering is unspecified: the stock phone may create its window after us.
        // Three bounded retries also cover the stock restore callback delayed by 2 seconds.
        handler.postDelayed(hide, 250)
        handler.postDelayed(hide, 750)
        handler.postDelayed(hide, 2250)
    }

    private fun stop() {
        handler.removeCallbacks(hide)
        if (!active) return
        active = false
        runCatching { app.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "Cannot unregister stock call observer", it) }
        dispatch("0")
    }

    private fun dispatch(state: String) {
        try {
            app.sendBroadcast(Intent("com.hicar.action.CALL_STATE")
                .setPackage(BydCallUiSettings.PHONE_PACKAGE)
                .putExtra("sys.hicar.callstate", state))
            // Dispatch is not an acknowledgement from the stock phone.
            Log.d(TAG, "Stock call UI broadcast dispatched state=$state")
        } catch (error: RuntimeException) {
            Log.w(TAG, "Cannot dispatch stock call UI broadcast", error)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    companion object {
        private const val TAG = "xcertplay-BYD-CallUI"
        internal const val CALL_CHANGED = "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"
    }
}
