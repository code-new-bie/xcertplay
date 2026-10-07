package com.shilapi.xcertplay

import android.content.Intent
import java.util.concurrent.atomic.AtomicInteger

/** Why the CarPlay screen was opened, and whether one exists in this process. */
internal object CarPlayHostLaunch {
    const val EXTRA_SOURCE = "com.shilapi.xcertplay.extra.LAUNCH_SOURCE"
    const val SOURCE_BOOT = "boot auto-start"
    const val SOURCE_USB = "MFi USB adapter attached"

    private val hosts = AtomicInteger()

    /** A CarPlay screen exists, possibly in the background; it manages the CarPlay restrictions. */
    val hostRunning: Boolean get() = hosts.get() > 0

    fun hostCreated() {
        hosts.incrementAndGet()
    }

    fun hostDestroyed() {
        hosts.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun source(intent: Intent?): String =
        intent?.getStringExtra(EXTRA_SOURCE) ?: when {
            intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) -> "app icon"
            else -> intent?.action ?: "unknown"
        }
}
