package com.shilapi.xcertplay.hud

import android.content.Context

/** Saved opt-in for lowering the AC fan during CarPlay calls, and the level it is lowered to. */
object BydCallWindSettings {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 3
    const val DEFAULT_LEVEL = 2

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) BydCallWindTest.cancel("setting disabled")
    }

    /** Fan level used during calls; takes effect from the next call. */
    fun targetLevel(context: Context): Int =
        prefs(context).getInt("target_level", DEFAULT_LEVEL).coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun setTargetLevel(context: Context, level: Int) {
        prefs(context).edit().putInt("target_level", level.coerceIn(MIN_LEVEL, MAX_LEVEL)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("xcertplay_byd_call_wind", Context.MODE_PRIVATE)
}
