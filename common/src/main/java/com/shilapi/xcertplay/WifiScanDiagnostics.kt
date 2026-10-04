package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * Reads who scans Wi-Fi, through the head unit's network ADB: dumpsys wifi records the apps that
 * requested scans (WifiScanRequestProxy) and the framework's own periodic scans. Frequent scans
 * take the radio off the CarPlay channel.
 */
internal object WifiScanDiagnostics {
    private val COMMANDS = listOf(
        "settings get global wifi_scan_always_enabled",
        "dumpsys wifi | grep -iE 'scan|pno|periodic|request' | head -n 200",
        "dumpsys wifiscanner | head -n 120",
    )

    fun collect(context: Context): Pair<LocalAdb.Access, List<String>> =
        LocalAdb(AdbKeys.load(context)).use { adb ->
            val access = adb.connect(mayAsk = true)
            if (access != LocalAdb.Access.READY) return@use access to emptyList()
            access to COMMANDS.flatMap { command ->
                listOf("$ $command") + adb.shell(command).orEmpty().lines().map(String::trimEnd).filter(String::isNotEmpty)
            }
        }
}
