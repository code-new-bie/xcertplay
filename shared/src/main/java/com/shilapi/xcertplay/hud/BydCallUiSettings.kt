package com.shilapi.xcertplay.hud

import android.content.Context

/** Opt-in setting shown under the existing BYD device detection. */
object BydCallUiSettings {
    internal const val PHONE_PACKAGE = "com.byd.bluetoothcall"
    internal const val PREFS = "xcertplay_byd_call_ui"
    internal const val KEY_ENABLED = "hide_stock_call_ui"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    internal fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
