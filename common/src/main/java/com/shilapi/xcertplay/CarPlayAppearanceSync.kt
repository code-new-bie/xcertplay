package com.shilapi.xcertplay

import android.os.Handler

/** Main-thread startup resends, each using the latest appearance at the time of sending. */
internal class CarPlayAppearanceSync(
    private val handler: Handler,
    private val synchronize: () -> Unit,
) {
    private var activeOwner: Any? = null
    private val repeat = Runnable { if (activeOwner != null) synchronize() }

    fun start(owner: Any) {
        stop()
        activeOwner = owner
        synchronize()
        REPEAT_DELAYS_MS.forEach { handler.postDelayed(repeat, it) }
    }

    fun end(owner: Any) {
        if (activeOwner === owner) stop()
    }

    fun stop() {
        activeOwner = null
        handler.removeCallbacks(repeat)
    }

    private companion object {
        val REPEAT_DELAYS_MS = longArrayOf(2_000L, 5_000L)
    }
}
