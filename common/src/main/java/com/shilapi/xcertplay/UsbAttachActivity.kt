package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle

/**
 * Receives the MFi coprocessor's USB adapter (CH341) and the iPhone when they attach. Attaching
 * grants the app the device permission. A BYD head unit attaches the adapter at every power-on, so
 * CarPlay opens from here only when the user enabled auto-start; a CarPlay screen that already exists
 * is left as it is, which keeps a wired session whose iPhone re-enumerates. No window: it finishes in
 * onCreate, in a task of its own so the CarPlay task is not raised.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val iphone = isIphone(attachedDevice(intent)?.vendorId)
        val what = if (iphone) "iPhone" else "MFi USB adapter"
        val autoStart = AirPlayPersistence.loadAutoStartOnBoot(this)
        when {
            CarPlayHostLaunch.hostRunning ->
                BootRestoreLog(this).append("$what attached: CarPlay already running; left as is")
            autoStart -> startActivity(
                Intent(this, CarPlayHostActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    .putExtra(
                        CarPlayHostLaunch.EXTRA_SOURCE,
                        if (iphone) CarPlayHostLaunch.SOURCE_IPHONE else CarPlayHostLaunch.SOURCE_USB,
                    ),
            )
            else -> BootRestoreLog(this).append("$what attached: auto-start off, CarPlay not started")
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun attachedDevice(intent: Intent?): UsbDevice? = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    internal companion object {
        private const val APPLE_VENDOR_ID = 0x05ac

        fun isIphone(vendorId: Int?): Boolean = vendorId == APPLE_VENDOR_ID
    }
}
