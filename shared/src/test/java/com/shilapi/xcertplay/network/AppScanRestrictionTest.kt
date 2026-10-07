package com.shilapi.xcertplay.network

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AppScanRestrictionTest {
    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("gaode-test", Context.MODE_PRIVATE)

    private inner class Shell(var mode: String = "allow") {
        var installed = true
        var readable = true
        var failRestore = false
        var stops = 0
        val modes = mutableListOf<String>()
        lateinit var restriction: AppScanRestriction
        fun execute(command: String): String? = when {
            command.startsWith("pm path") -> if (installed) "package:/system/app/Gaode.apk" else ""
            command.startsWith("cmd appops get") -> if (!readable) null else
                "CHANGE_WIFI_STATE: $mode; time=+1s\nXCERTPLAY-appops-read"
            command.startsWith("cmd appops set") -> {
                assertTrue("Recovery must be durable before mutation", restriction.pending)
                val requested = command.substringBefore(" &&").substringAfterLast(' ')
                modes += requested
                if (failRestore && requested != "ignore") null else {
                    mode = requested
                    "XCERTPLAY-appops-set"
                }
            }
            command.startsWith("am force-stop") -> { stops++; "XCERTPLAY-app-stopped" }
            else -> error(command)
        }
    }

    @Test fun savesOriginalBeforeMutationAndRecoversAcrossNewInstance() {
        val shell = Shell("foreground")
        val first = AppScanRestriction.gaode(prefs, 0, {}).also { shell.restriction = it }
        first.apply(shell::execute)
        assertEquals("ignore", shell.mode)
        assertEquals(1, shell.stops)
        val next = AppScanRestriction.gaode(prefs, 0, {}).also { shell.restriction = it }
        next.apply(shell::execute, forceStop = false)
        assertEquals(1, shell.stops)
        assertTrue(next.restore(shell::execute))
        assertEquals("foreground", shell.mode)
        assertFalse(next.pending)
    }

    @Test fun failedRestoreRetainsOriginalForNextAttempt() {
        val shell = Shell("deny")
        val restriction = AppScanRestriction.gaode(prefs, 0, {}).also { shell.restriction = it }
        restriction.apply(shell::execute)
        shell.failRestore = true
        assertFalse(restriction.restore(shell::execute))
        assertTrue(restriction.pending)
        shell.failRestore = false
        assertTrue(restriction.restore(shell::execute))
        assertEquals("deny", shell.mode)
    }

    @Test fun absentPackageIsSkippedAndUnknownModeIsNotOverwritten() {
        val shell = Shell().apply { installed = false }
        val restriction = AppScanRestriction.gaode(prefs, 0, {}).also { shell.restriction = it }
        restriction.apply(shell::execute)
        assertEquals(0, shell.stops)
        assertFalse(restriction.pending)
        shell.installed = true
        shell.readable = false
        restriction.apply(shell::execute)
        assertEquals(1, shell.stops)
        assertTrue(shell.modes.isEmpty())
        assertFalse(restriction.pending)
    }

    @Test fun baiduLocationIsRestrictedAndRestoredButNeverStopped() {
        val shell = Shell("allow")
        val baidu = AppScanRestriction.baiduLocation(prefs, 0, {}).also { shell.restriction = it }
        baidu.apply(shell::execute, forceStop = true)
        assertEquals("ignore", shell.mode)
        assertEquals(0, shell.stops)
        assertTrue(baidu.pending)
        assertFalse("Each app keeps its own recovery record", AppScanRestriction.gaode(prefs, 0, {}).pending)
        assertTrue(baidu.restore(shell::execute))
        assertEquals("allow", shell.mode)
        assertFalse(baidu.pending)
    }

    @Test fun parserNeverConfusesUidOverridesOrFailedReadsWithPackageMode() {
        assertEquals("allow", AppScanRestriction.parseMode("No operations.\nDefault mode: allow\nXCERTPLAY-appops-read"))
        assertNull(AppScanRestriction.parseMode("No operations.\nXCERTPLAY-appops-read"))
        assertNull(AppScanRestriction.parseMode("CHANGE_WIFI_STATE: allow"))
        assertNull(AppScanRestriction.parseMode("Uid mode: CHANGE_WIFI_STATE: ignore\nXCERTPLAY-appops-read"))
        assertEquals("allow", AppScanRestriction.parseMode(
            "Uid mode: CHANGE_WIFI_STATE: ignore\nCHANGE_WIFI_STATE: allow; time=+1s\nXCERTPLAY-appops-read"))
    }
}
