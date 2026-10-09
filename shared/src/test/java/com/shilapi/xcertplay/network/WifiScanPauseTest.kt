package com.shilapi.xcertplay.network

import android.os.Looper
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WifiScanPauseTest {
    private class Shell : WifiScanShell {
        val commands = CopyOnWriteArrayList<String>()
        val modes = mutableMapOf<String, String>()
        /** Gaode's mode; each package keeps its own AppOps entry. */
        val mode get() = modes[AppScanRestriction.GAODE] ?: "allow"
        var losePauseReply = false
        var access = LocalAdb.Access.READY
        override fun connect() = access
        override fun close() = Unit
        override fun shell(command: String): String? {
            commands += command
            return when {
                "wifiscan off" in command -> if (losePauseReply) null else "XCERTPLAY wifiscan enabled=false ok"
                "wifiscan on" in command -> "XCERTPLAY wifiscan enabled=true ok"
                command.startsWith("pm path") -> "package:/system/app/Gaode.apk"
                command.startsWith("cmd appops get") ->
                    "CHANGE_WIFI_STATE: ${modes[command.split(' ')[5]] ?: "allow"}\nXCERTPLAY-appops-read"
                command.startsWith("cmd appops set") -> {
                    modes[command.split(' ')[5]] = command.substringBefore(" &&").substringAfterLast(' ')
                    "XCERTPLAY-appops-set"
                }
                command.startsWith("am force-stop") -> "XCERTPLAY-app-stopped"
                else -> error(command)
            }
        }
    }

    private fun manager(shell: Shell, delay: Long = 100L, grace: Long = 60_000L): WifiScanPause {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.sourceDir = "/data/app/xcertplay/base.apk"
        return WifiScanPause(app, { shell }, delay, grace)
    }

    /** What a run killed by the head unit's power-off leaves: the search paused, Gaode restricted. */
    private fun leavePaused() {
        WifiScanPauseSettings.prefs(RuntimeEnvironment.getApplication()).edit()
            .putBoolean("paused", true).putBoolean("search_recovery_pending", true)
            .putString("gaode_original_change_wifi_state", "allow").commit()
    }

    /** Runs the startup check, which goes through the main looper to the worker. */
    private fun started(manager: WifiScanPause): WifiScanPause {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50)
        return manager
    }

    private fun awaitResume(shell: Shell): Boolean {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (shell.commands.any { "wifiscan on" in it }) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test fun searchLeftPausedByAKilledRunWaitsForCarPlayBeforeResuming() {
        val shell = Shell()
        leavePaused()
        val manager = started(manager(shell, grace = 400L))
        assertFalse("Still paused right after startup", shell.commands.any { "wifiscan on" in it })
        assertTrue(awaitResume(shell))
        Thread.sleep(100)
        assertFalse(manager.isPaused())
        assertTrue(shell.commands.any { it.startsWith("cmd appops set") && "${AppScanRestriction.GAODE} CHANGE_WIFI_STATE allow" in it })
    }

    @Test fun carPlayConnectingDuringTheStartupGraceKeepsTheSearchPaused() {
        val shell = Shell()
        leavePaused()
        val manager = started(manager(shell, grace = 300L))
        manager.pause()
        Thread.sleep(700)
        assertFalse(shell.commands.any { "wifiscan on" in it })
        assertTrue(manager.isPaused())
    }

    @Test fun resumeLaterDoesNotShortenTheStartupGrace() {
        val shell = Shell()
        leavePaused()
        val manager = started(manager(shell, delay = 100L, grace = 900L))
        manager.resumeLater()
        Thread.sleep(400)
        assertFalse(shell.commands.any { "wifiscan on" in it })
        assertTrue(awaitResume(shell))
    }

    @Test fun aLeftoverIsFoundFromTheSavedStateAlone() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(WifiScanPause.hasLeftover(app))
        WifiScanPauseSettings.prefs(app).edit().putString("baidu_location_original_change_wifi_state", "allow").commit()
        assertTrue(WifiScanPause.hasLeftover(app))
    }

    @Test fun lostPauseReplyStillRestoresSearchAndOriginalPermissionOnExit() {
        val shell = Shell().apply { losePauseReply = true }
        val manager = manager(shell)
        manager.pause()
        assertTrue(manager.resumeNowBlocking(2000))
        assertTrue(shell.commands.any { "wifiscan on" in it })
        assertEquals("allow", shell.mode)
        assertFalse(manager.isPaused())
    }

    @Test fun quickReconnectCancelsResumeAndStopsGaodeOncePerConnection() {
        val shell = Shell()
        val manager = manager(shell)
        val secondStop = CountDownLatch(2)
        manager.diagnostic = { if ("force-stop acknowledged" in it) secondStop.countDown() }
        manager.pause()
        manager.pause() // Duplicate notification in the same connection.
        manager.resumeLater()
        manager.pause() // New connection before the delay expires.
        assertTrue(secondStop.await(2, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertFalse(shell.commands.any { "wifiscan on" in it })
        assertEquals(2, shell.commands.count { it.startsWith("am force-stop") })
        assertTrue(manager.resumeNowBlocking(2000))
        assertEquals("allow", shell.mode)
    }

    @Test fun disablingRestoresImmediatelyAndEnablingDoesNotKillGaodeTwice() {
        val shell = Shell()
        val manager = manager(shell, 60_000)
        manager.pause()
        manager.settingChanged(false)
        manager.settingChanged(true)
        assertTrue(manager.resumeNowBlocking(2000))
        assertEquals(1, shell.commands.count { it.startsWith("am force-stop") })
        assertEquals(2, shell.commands.count { "wifiscan on" in it })
        assertEquals("allow", shell.mode)
    }

    @Test fun pauseRestrictsBaiduLocationScansWithoutStoppingIt() {
        val shell = Shell()
        val manager = manager(shell)
        manager.pause()
        assertTrue(manager.resumeNowBlocking(2000))
        val baidu = AppScanRestriction.BAIDU_LOCATION
        assertTrue(shell.commands.any { it.startsWith("cmd appops set") && "$baidu CHANGE_WIFI_STATE ignore" in it })
        assertTrue(shell.commands.any { it.startsWith("cmd appops set") && "$baidu CHANGE_WIFI_STATE allow" in it })
        assertFalse(shell.commands.any { it.startsWith("am force-stop") && baidu in it })
        assertEquals(1, shell.commands.count { it.startsWith("am force-stop") })
    }

    @Test fun startupRepairsPendingSearchEvenWithoutPauseAcknowledgement() {
        val shell = Shell()
        WifiScanPauseSettings.prefs(RuntimeEnvironment.getApplication()).edit()
            .putBoolean("search_recovery_pending", true).commit()
        val manager = manager(shell)
        assertTrue(manager.resumeNowBlocking(2000))
        assertTrue(shell.commands.any { "wifiscan on" in it })
    }

    @Test fun thePauseStateTellsWhyTheSearchKeepsRunning() {
        val shell = Shell()
        val manager = manager(shell)
        assertEquals(WifiScanPause.PauseState.IDLE, manager.state)
        manager.pause()
        assertTrue(manager.resumeNowBlocking(2000))
        assertEquals("Resumed after the session", WifiScanPause.PauseState.IDLE, manager.state)

        shell.access = LocalAdb.Access.NOT_APPROVED
        manager.pause()
        awaitState(manager, WifiScanPause.PauseState.ADB_NOT_APPROVED)
        assertTrue(manager.state.notInEffect)

        shell.access = LocalAdb.Access.UNREACHABLE
        manager.pause()
        awaitState(manager, WifiScanPause.PauseState.ADB_UNREACHABLE)

        shell.access = LocalAdb.Access.READY
        manager.pause()
        awaitState(manager, WifiScanPause.PauseState.PAUSED)
        assertFalse(manager.state.notInEffect)

        WifiScanPauseSettings.prefs(RuntimeEnvironment.getApplication()).edit().putBoolean("enabled", false).commit()
        manager.settingChanged(false)
        awaitState(manager, WifiScanPause.PauseState.SETTING_OFF)
        assertFalse(manager.state.notInEffect)
    }

    @Test fun aLostPauseReplyIsAFailureRatherThanAnAdbProblem() {
        val shell = Shell().apply { losePauseReply = true }
        val manager = manager(shell)
        manager.pause()
        awaitState(manager, WifiScanPause.PauseState.FAILED)
    }

    private fun awaitState(manager: WifiScanPause, expected: WifiScanPause.PauseState) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (manager.state != expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, manager.state)
    }
}
