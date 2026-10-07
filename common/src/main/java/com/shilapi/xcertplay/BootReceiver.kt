package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.network.BluetoothHandoff
import com.shilapi.xcertplay.network.WifiScanPause
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Starts the CarPlay host after boot when the user has enabled the startup option, and repairs
 * what a killed run left behind.
 *
 * A BYD head unit's power-off is a "quick boot": it kills every third-party app, so the delayed
 * restore of the iPhone's Bluetooth links, the Wi-Fi search and the app scan permissions never
 * runs, and all of them survive into the next drive. Power-on resends BOOT_COMPLETED (with
 * from_quickboot). With auto-start the restrictions wait for CarPlay to connect; without it they
 * are restored now in the background. A CarPlay screen already running (opened before this
 * broadcast) manages them itself and is left alone.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val autoStart = AirPlayPersistence.loadAutoStartOnBoot(app)
        if (BluetoothHandoff.hasLeftover(app) || WifiScanPause.hasLeftover(app)) {
            val quickBoot = intent.getBooleanExtra(EXTRA_FROM_QUICKBOOT, false)
            if (CarPlayHostLaunch.hostRunning) {
                BootRestoreLog(app).append(
                    "${bootLabel(quickBoot)}: CarPlay restrictions left by the last run; CarPlay already " +
                        "running, which restores them unless CarPlay connects",
                )
            } else {
                handleLeftover(app, autoStart, quickBoot)
            }
        }
        if (!autoStart) return

        val launch = Intent(context, CarPlayHostActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(CarPlayHostLaunch.EXTRA_SOURCE, CarPlayHostLaunch.SOURCE_BOOT)
        }
        try {
            context.startActivity(launch)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Boot auto-start could not launch CarPlayHostActivity", error)
        }
    }

    private fun handleLeftover(app: Context, autoStart: Boolean, quickBoot: Boolean) {
        val log = BootRestoreLog(app)
        log.append(
            "${bootLabel(quickBoot)}: CarPlay restrictions left by the last run; " +
                if (autoStart) "auto-start on, waiting for CarPlay" else "auto-start off, restoring now",
        )
        // Creating them takes the leftover over: they restore it unless CarPlay connects in time.
        val bluetooth = BluetoothHandoff.shared(app).also { it.diagnostic = log::append }
        val wifiSearch = WifiScanPause.shared(app).also { it.diagnostic = log::append }
        if (autoStart) return

        val pending = goAsync()
        thread(name = "xcertplay-boot-restore") {
            try {
                val started = SystemClock.elapsedRealtime()
                val wifiRestored = AtomicBoolean(false)
                val wifiThread = thread(name = "xcertplay-boot-restore-wifi") {
                    wifiRestored.set(wifiSearch.resumeNowBlocking(RESTORE_TIMEOUT_MILLIS))
                }
                val bluetoothRestored = bluetooth.releaseNowBlocking(RESTORE_TIMEOUT_MILLIS, "power-on without auto-start")
                wifiThread.join((started + RESTORE_TIMEOUT_MILLIS - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                val done = bluetoothRestored && wifiRestored.get()
                log.append(
                    "Boot restore: Bluetooth restored=$bluetoothRestored, Wi-Fi search and app permissions " +
                        "restored=${wifiRestored.get()}, took ${SystemClock.elapsedRealtime() - started}ms" +
                        if (done) "" else "; the rest continues in the background or on the next app start",
                )
            } finally {
                pending.finish()
            }
        }
    }

    private fun bootLabel(quickBoot: Boolean) = if (quickBoot) "Head unit powered on" else "Device booted"

    private companion object {
        const val TAG = "xcertplay-boot"
        const val EXTRA_FROM_QUICKBOOT = "from_quickboot"
        /** Both run in parallel and stay inside a foreground broadcast's 10 s limit. */
        const val RESTORE_TIMEOUT_MILLIS = 8_000L
    }
}
