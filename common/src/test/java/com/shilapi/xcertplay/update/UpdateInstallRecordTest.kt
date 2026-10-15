package com.shilapi.xcertplay.update

import com.shilapi.xcertplay.update.UpdateInstallRecord.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateInstallRecordTest {
    @Test
    fun aRecordThatWasNeverWrittenMeansTheScriptDidNotRun() {
        // What 1.4.0-byd.12 to 16 left behind: nothing at all.
        assertEquals(State.NOT_STARTED, UpdateInstallRecord.parse(null).state)
        assertEquals(State.NOT_STARTED, UpdateInstallRecord.parse("").state)
        assertNull(UpdateInstallRecord.parse("").token)
    }

    @Test
    fun aRecordWithoutItsLastLineIsStillInstalling() {
        val record = UpdateInstallRecord.parse(
            "xcertplay-update started 1791900000123 21:59:07\n" +
                "xcertplay-update app exited after 2s\n" +
                "xcertplay-update installing\n",
        )
        assertEquals("1791900000123", record.token)
        assertEquals(State.RUNNING, record.state)
    }

    @Test
    fun pmsOwnWordsSayWhyItFailed() {
        val record = UpdateInstallRecord.parse(
            "xcertplay-update started 7 21:59:07\n" +
                "xcertplay-update app exited after 2s\n" +
                "xcertplay-update installing\n" +
                "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.shilapi.xcertplay.byd signatures do not match]\n" +
                "xcertplay-update pm exit=1\n" +
                "Starting: Intent { cmp=com.shilapi.xcertplay.byd/com.shilapi.xcertplay.CarPlayHostActivity }\n" +
                "xcertplay-update done 21:59:20\n",
        )
        assertEquals(State.FAILED, record.state)
        assertEquals(
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.shilapi.xcertplay.byd signatures do not match]",
            record.failure(),
        )
    }

    @Test
    fun aCopyThatFailedIsSaidSo() {
        val record = UpdateInstallRecord.parse(
            "xcertplay-update started 7 21:59:07\nxcertplay-update app exited after 0s\n" +
                "xcertplay-update copy failed\nxcertplay-update done 21:59:08\n",
        )
        assertEquals(State.FAILED, record.state)
        assertEquals("the APK could not be copied to /data/local/tmp", record.failure())
    }

    @Test
    fun aSuccessfulInstallIsInstalled() {
        val record = UpdateInstallRecord.parse(
            "xcertplay-update started 7 21:59:07\nxcertplay-update app exited after 3s\nxcertplay-update installing\n" +
                "Success\nxcertplay-update pm exit=0\nStarting: Intent { cmp=a/b }\nxcertplay-update done 21:59:31\n",
        )
        assertEquals(State.INSTALLED, record.state)
        assertEquals(listOf("Success"), record.pmOutput)
    }
}
