package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.WifiScanPauseSettings

/**
 * Reads who scans Wi-Fi, through the head unit's network ADB: dumpsys wifi records the apps that
 * requested scans (WifiScanRequestProxy) and the framework's own periodic scans. Frequent scans
 * take the radio off the CarPlay channel.
 */
internal object WifiScanDiagnostics {
    // History sections list the oldest records first, so keep the tail: the most recent scans and
    // who asked for them (WorkSource), plus whether the 10-second connectivity scan is paused.
    private val COMMANDS = listOf(
        "date '+%Y-%m-%d %H:%M:%S %z'",
        "cat /proc/uptime",
        "dumpsys activity activities | grep 'mResumedActivity' | tail -n 3",
        "settings get global wifi_scan_always_enabled",
        "settings get secure location_mode",
        "dumpsys wifi | grep -iE 'WifiConnectivityManager|periodicScan|enableWifiConnectivityManager' | tail -n 30",
        "dumpsys wifiscanner | grep -E 'addSingleScanRequest|addBackgroundScanRequest|startScan' | tail -n 60",
        "dumpsys wifi | grep -iE 'scan request from|Foreground scan app|Background scan app' | tail -n 30",
    )

    fun collect(context: Context): Pair<LocalAdb.Access, List<String>> =
        LocalAdb(AdbKeys.load(context)).use { adb ->
            val access = adb.connect(mayAsk = true)
            if (access != LocalAdb.Access.READY) return@use access to emptyList()
            val started = SystemClock.elapsedRealtime()
            val header = "Snapshot start wallMs=${System.currentTimeMillis()} elapsedMs=$started " +
                "pauseSetting=${WifiScanPauseSettings.enabled(context)}"
            val lines = COMMANDS.flatMap { command ->
                listOf("$ $command") + adb.shell(command).orEmpty().lines().map(String::trimEnd).filter(String::isNotEmpty)
            }
            access to (listOf(header) + lines + ("Snapshot end elapsedMs=${SystemClock.elapsedRealtime()} " +
                "durationMs=${SystemClock.elapsedRealtime() - started}"))
        }
}
