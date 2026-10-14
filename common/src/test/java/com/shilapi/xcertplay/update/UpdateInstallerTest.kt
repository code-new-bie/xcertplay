package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallerTest {
    private val pkg = "com.shilapi.xcertplay.byd"
    private val launch = "com.shilapi.xcertplay.byd/com.shilapi.xcertplay.MainActivity"
    private val apk = "/storage/emulated/0/Download/xcertplay/xcertplay-mobile-1.4.0-byd.12.apk"

    @Test
    fun theInstallRunsDetachedWaitsForTheAppToExitAndOpensItAgain() {
        val command = UpdateInstaller.installCommand(pkg, apk, launch)
        assertEquals(
            "nohup sh -c 'i=0; while [ \$i -lt 30 ] && pidof $pkg >/dev/null; do sleep 1; i=\$((i+1)); done; " +
                "pm install -r -t \"$apk\" >/data/local/tmp/xcertplay-update.log 2>&1; " +
                "am start -n $launch >>/data/local/tmp/xcertplay-update.log 2>&1' >/dev/null 2>&1 &",
            command,
        )
    }

    @Test
    fun aPathMediaStoreRenamedStillWorks() {
        val renamed = "/storage/emulated/0/Download/xcertplay/xcertplay-mobile-1.4.0-byd.12 (1).apk"
        assertTrue(UpdateInstaller.installCommand(pkg, renamed, launch).contains("\"$renamed\""))
    }

    @Test
    fun anythingTheShellWouldInterpretIsRefused() {
        listOf(
            "/sdcard/Download/a'b.apk",
            "/sdcard/Download/a\"b.apk",
            "/sdcard/Download/\$(reboot).apk",
            "/sdcard/Download/a`id`.apk",
            "/sdcard/Download/a;b.apk",
            "relative/path.apk",
        ).forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) { UpdateInstaller.installCommand(pkg, path, launch) }
        }
        assertThrows(IllegalArgumentException::class.java) { UpdateInstaller.installCommand("a;b", apk, launch) }
        assertThrows(IllegalArgumentException::class.java) { UpdateInstaller.installCommand(pkg, apk, "a/b c") }
    }
}
