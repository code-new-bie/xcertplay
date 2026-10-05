package com.shilapi.xcertplay.hud

import android.content.Context

/** Saved opt-in for the automatic CarPlay call audio sequence. */
object BydCallWindSettings {
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) BydCallWindTest.cancel("setting disabled")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("xcertplay_byd_call_wind", Context.MODE_PRIVATE)
}
