package com.shilapi.xcertplay

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExporterTest {
    @Test
    fun exportedFilesShareOneTimestamp() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val (session, logcat) = LogExporter.fileNames(0L)
            assertEquals("xcertplay-19700101-000000.log", session)
            assertEquals("xcertplay-logcat-19700101-000000.txt", logcat)
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun namesAreSafeForFileManagers() {
        val (session, logcat) = LogExporter.fileNames(System.currentTimeMillis())
        for (name in listOf(session, logcat)) {
            assertTrue(name, name.matches(Regex("[A-Za-z0-9._-]+")))
        }
    }
}
