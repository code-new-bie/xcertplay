package com.shilapi.xcertplay.network

import android.content.SharedPreferences

/**
 * Restricts one app's Wi-Fi scans through its package-level CHANGE_WIFI_STATE app op: the
 * firmware's WifiServiceImpl.startScan rejects a caller whose op is not allowed, unless it holds
 * NETWORK_SETTINGS (neither Gaode nor Baidu location does).
 *
 * Worker-thread only. Persist the package mode before changing it; never reset all AppOps.
 */
internal class AppScanRestriction(
    private val prefs: SharedPreferences,
    private val userId: Int,
    private val packageName: String,
    private val label: String,
    private val originalKey: String,
    /** Also force-stop the app once per connection (Gaode: this ends stock navigation). */
    private val stopsApp: Boolean,
    private val report: (String) -> Unit,
) {
    val pending: Boolean get() = prefs.contains(originalKey)

    fun apply(shell: (String) -> String?, forceStop: Boolean = true) {
        val installed = shell("pm path --user $userId $packageName")
        if (installed == null) { report("$label: package lookup failed"); return }
        if (!installed.lineSequence().any { it.startsWith("package:") }) {
            report("$label: package absent or unavailable; skipped")
            return
        }
        report("$label: installed user=$userId")
        val original = if (pending) prefs.getString(originalKey, null) else readMode(shell)
        if (original == null) {
            report("$label: original CHANGE_WIFI_STATE unknown; restriction skipped")
        } else if (!pending && !prefs.edit().putString(originalKey, original).commit()) {
            report("$label: cannot persist original mode; restriction skipped")
        } else {
            report("$label: saved original CHANGE_WIFI_STATE=$original")
            val restricted = setMode(shell, "ignore")
            report("$label: CHANGE_WIFI_STATE ignore confirmed=$restricted (scan suppression requires vehicle verification)")
        }
        if (!forceStop || !stopsApp) return
        val stopped = shell("am force-stop --user $userId $packageName && echo XCERTPLAY-app-stopped")
            ?.lineSequence()?.any { it.trim() == "XCERTPLAY-app-stopped" } == true
        report("$label: force-stop acknowledged=$stopped; navigation will not be relaunched")
    }

    fun restore(shell: (String) -> String?): Boolean {
        val original = prefs.getString(originalKey, null) ?: return true
        val restored = original in MODES && setMode(shell, original)
        val saved = restored && prefs.edit().remove(originalKey).commit()
        report("$label: restore CHANGE_WIFI_STATE=$original confirmed=$saved")
        return saved
    }

    private fun readMode(shell: (String) -> String?): String? =
        parseMode(shell("cmd appops get --user $userId $packageName CHANGE_WIFI_STATE && echo XCERTPLAY-appops-read"))

    private fun setMode(shell: (String) -> String?, mode: String): Boolean {
        val output = shell("cmd appops set --user $userId $packageName CHANGE_WIFI_STATE $mode && echo XCERTPLAY-appops-set")
        return output?.lineSequence()?.any { it.trim() == "XCERTPLAY-appops-set" } == true && readMode(shell) == mode
    }

    companion object {
        const val GAODE = "com.autonavi.amapauto"
        /** BYD's system network-location provider (/system/priv-app/BaiduNetworklocation). */
        const val BAIDU_LOCATION = "com.baidu.map.location"
        private val MODES = setOf("allow", "ignore", "deny", "default", "foreground")

        fun gaode(prefs: SharedPreferences, userId: Int, report: (String) -> Unit) = AppScanRestriction(
            prefs, userId, GAODE, "Gaode", "gaode_original_change_wifi_state", stopsApp = true, report,
        )

        /** Only its scans are restricted; the provider keeps running, so network location may be coarser. */
        fun baiduLocation(prefs: SharedPreferences, userId: Int, report: (String) -> Unit) = AppScanRestriction(
            prefs, userId, BAIDU_LOCATION, "Baidu location", "baidu_location_original_change_wifi_state",
            stopsApp = false, report,
        )

        internal fun parseMode(output: String?): String? {
            if (output == null || output.lineSequence().none { it.trim() == "XCERTPLAY-appops-read" }) return null
            val lines = output.lineSequence().map(String::trim).toList()
            val entry = lines.firstOrNull { it.startsWith("CHANGE_WIFI_STATE:") }
            if (entry != null) return entry.substringAfter(':').trim().substringBefore(';')
                .substringBefore(' ').takeIf { it in MODES }
            // UID-level entries are not package entries and must not be overwritten.
            // "No operations" is not MODE_DEFAULT: the firmware reports the effective default
            // separately (CHANGE_WIFI_STATE normally defaults to allow). Preserve that value.
            return if ("No operations." in lines) lines.firstOrNull { it.startsWith("Default mode: ") }
                ?.substringAfter("Default mode: ")?.takeIf { it in MODES } else null
        }
    }
}
