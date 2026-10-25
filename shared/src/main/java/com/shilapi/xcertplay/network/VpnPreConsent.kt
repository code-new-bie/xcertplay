package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * Wired CarPlay needs Android's VPN consent, the "connection request" dialog, and the BYD head unit
 * asks again on every wired connection. Android counts an allowed ACTIVATE_VPN app op as that
 * consent, and the car's own ADB can set it, so with ADB approved the dialog does not appear. Only an
 * ADB key the driver already approved is used; this never asks for approval. After DiPlay 732e1918,
 * which does it only where the firmware has no consent dialog.
 */
object VpnPreConsent {
    private const val PREFS = "xcertplay_vpn_pre_consent"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Allows this app's VPN over approved ADB and says what ADB allowed. Call off the main thread. */
    fun grant(context: Context, adb: LocalAdb = LocalAdb(AdbKeys.load(context))): LocalAdb.Access = adb.use {
        val access = it.connect(mayAsk = false)
        if (access == LocalAdb.Access.READY) it.shell("cmd appops set ${context.packageName} ACTIVATE_VPN allow")
        access
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
