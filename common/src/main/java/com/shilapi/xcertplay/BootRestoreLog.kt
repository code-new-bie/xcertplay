package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened to CarPlay's leftover restrictions at boot. Kept apart from the session log, which
 * every host start overwrites, and exported with it.
 */
internal class BootRestoreLog(context: Context) {
    private val file = file(context)

    fun append(message: String) {
        Log.i(TAG, message)
        synchronized(LOCK) {
            runCatching {
                file.parentFile?.mkdirs()
                if (file.length() > MAX_BYTES) keepNewestHalf()
                file.appendText("${FORMAT.format(Date())}  $message\n")
            }
        }
    }

    private fun keepNewestHalf() {
        val bytes = file.readBytes()
        var start = bytes.size - MAX_BYTES / 2
        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
        file.writeBytes(bytes.copyOfRange(minOf(start + 1, bytes.size), bytes.size))
    }

    companion object {
        private const val TAG = "xcertplay-boot"
        private const val MAX_BYTES = 128 * 1024
        private val LOCK = Any()
        private val FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        fun file(context: Context): File =
            File(File(context.getExternalFilesDir(null) ?: context.filesDir, "logs"), "boot-restore.log")
    }
}
