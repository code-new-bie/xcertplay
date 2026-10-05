package com.shilapi.xcertplay.network

import android.content.SharedPreferences

/** Worker-thread only. Persist the package mode before changing it; never reset all AppOps. */
internal class GaodeScanRestriction(
    private val prefs: SharedPreferences,
    private val userId: Int,
    private val report: (String) -> Unit,
) {
    val pending: Boolean get() = prefs.contains(KEY_ORIGINAL)

    fun apply(shell: (String) -> String?, forceStop: Boolean = true) {
        val installed = shell("pm path --user $userId $PACKAGE")
        if (installed == null) { report("Gaode: package lookup failed"); return }
        if (!installed.lineSequence().any { it.startsWith("package:") }) {
            report("Gaode: package absent or unavailable; skipped")
            return
        }
        report("Gaode: installed user=$userId")
        val original = if (pending) prefs.getString(KEY_ORIGINAL, null) else readMode(shell)
        if (original == null) {
            report("Gaode: original CHANGE_WIFI_STATE unknown; restriction skipped")
        } else if (!pending && !prefs.edit().putString(KEY_ORIGINAL, original).commit()) {
            report("Gaode: cannot persist original mode; restriction skipped")
        } else {
            report("Gaode: saved original CHANGE_WIFI_STATE=$original")
            val restricted = setMode(shell, "ignore")
            report("Gaode: CHANGE_WIFI_STATE ignore confirmed=$restricted (scan suppression requires vehicle verification)")
        }
        if (!forceStop) return
        val stopped = shell("am force-stop --user $userId $PACKAGE && echo XCERTPLAY-gaode-stopped")
            ?.lineSequence()?.any { it.trim() == "XCERTPLAY-gaode-stopped" } == true
        report("Gaode: force-stop acknowledged=$stopped; navigation will not be relaunched")
    }

    fun restore(shell: (String) -> String?): Boolean {
        val original = prefs.getString(KEY_ORIGINAL, null) ?: return true
        val restored = original in MODES && setMode(shell, original)
        val saved = restored && prefs.edit().remove(KEY_ORIGINAL).commit()
        report("Gaode: restore CHANGE_WIFI_STATE=$original confirmed=$saved")
        return saved
    }

    private fun readMode(shell: (String) -> String?): String? =
        parseMode(shell("cmd appops get --user $userId $PACKAGE CHANGE_WIFI_STATE && echo XCERTPLAY-appops-read"))

    private fun setMode(shell: (String) -> String?, mode: String): Boolean {
        val output = shell("cmd appops set --user $userId $PACKAGE CHANGE_WIFI_STATE $mode && echo XCERTPLAY-appops-set")
        return output?.lineSequence()?.any { it.trim() == "XCERTPLAY-appops-set" } == true && readMode(shell) == mode
    }

    companion object {
        const val PACKAGE = "com.autonavi.amapauto"
        private const val KEY_ORIGINAL = "gaode_original_change_wifi_state"
        private val MODES = setOf("allow", "ignore", "deny", "default", "foreground")

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
