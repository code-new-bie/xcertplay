package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives the MFi coprocessor's USB adapter (CH341) when it attaches. Attaching grants the app
 * the device permission. A BYD head unit attaches it at every power-on, so CarPlay opens from here
 * only when the user enabled auto-start; a CarPlay screen that already exists is left as it is.
 * No window: it finishes in onCreate, in a task of its own so the CarPlay task is not raised.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoStart = AirPlayPersistence.loadAutoStartOnBoot(this)
        when {
            CarPlayHostLaunch.hostRunning ->
                BootRestoreLog(this).append("MFi USB adapter attached: CarPlay already running; left as is")
            autoStart -> startActivity(
                Intent(this, CarPlayHostActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    .putExtra(CarPlayHostLaunch.EXTRA_SOURCE, CarPlayHostLaunch.SOURCE_USB),
            )
            else -> BootRestoreLog(this).append("MFi USB adapter attached: auto-start off, CarPlay not started")
        }
        finish()
    }
}
