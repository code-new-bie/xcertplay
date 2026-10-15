package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallerTest {
    private val pkg = "com.shilapi.xcertplay.byd"
    private val launch = "com.shilapi.xcertplay.byd/com.shilapi.xcertplay.MainActivity"
    private val apk = "/storage/emulated/0/Download/xcertplay/xcertplay-mobile-1.4.0-byd.12.apk"

    @Test
    fun theInstallIgnoresTheHangUpCopiesTheApkAndRecordsEachStep() {
        val command = UpdateInstaller.installCommand(pkg, apk, launch, "1791900000123")
        val log = "/data/local/tmp/xcertplay-update.log"
        val staged = "/data/local/tmp/xcertplay-update.apk"
        assertEquals(
            "trap '' HUP; sh -c '" +
                "echo \"xcertplay-update started 1791900000123 \$(date +%H:%M:%S)\" >$log; " +
                "i=0; while [ \$i -lt 30 ] && pidof $pkg >/dev/null; do sleep 1; i=\$((i+1)); done; " +
                "echo \"xcertplay-update app exited after \${i}s\" >>$log; " +
                "if cp \"$apk\" $staged; then echo \"xcertplay-update installing\" >>$log; " +
                "pm install -r -t $staged >>$log 2>&1; echo \"xcertplay-update pm exit=\$?\" >>$log; " +
                "else echo \"xcertplay-update copy failed\" >>$log; fi; rm -f $staged; " +
                "am start -n $launch >>$log 2>&1; " +
                "echo \"xcertplay-update done \$(date +%H:%M:%S)\" >>$log" +
                "' </dev/null >/dev/null 2>&1 &",
            command,
        )
        // nohup set its own SIGHUP handling too late: the job was gone before it ran.
        assertFalse(command.contains("nohup"))
    }

    @Test
    fun aPathMediaStoreRenamedStillWorks() {
        val renamed = "/storage/emulated/0/Download/xcertplay/xcertplay-mobile-1.4.0-byd.12 (1).apk"
        assertTrue(UpdateInstaller.installCommand(pkg, renamed, launch, "1").contains("\"$renamed\""))
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
            assertThrows(path, IllegalArgumentException::class.java) { UpdateInstaller.installCommand(pkg, path, launch, "1") }
        }
        assertThrows(IllegalArgumentException::class.java) { UpdateInstaller.installCommand("a;b", apk, launch, "1") }
        assertThrows(IllegalArgumentException::class.java) { UpdateInstaller.installCommand(pkg, apk, "a/b c", "1") }
        assertThrows(IllegalArgumentException::class.java) { UpdateInstaller.installCommand(pkg, apk, launch, "1; reboot") }
    }
}
