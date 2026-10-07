package com.shilapi.xcertplay

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Copies the session log, the boot-restore log and this process's logcat to a folder a file
 * manager can reach, so logs
 * can be taken off a head unit without adb. Android 10+ writes to the public Download folder
 * through MediaStore (no permission); Android 9 falls back to the app's own download directory.
 */
internal object LogExporter {
    const val FOLDER = "xcertplay"

    data class Result(val location: String, val fileCount: Int)

    data class FileNames(val session: String, val logcat: String, val boot: String)

    fun fileNames(timestampMillis: Long): FileNames {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestampMillis))
        return FileNames("xcertplay-$stamp.log", "xcertplay-logcat-$stamp.txt", "xcertplay-boot-$stamp.txt")
    }

    fun export(context: Context, sessionLog: File?): Result {
        val names = fileNames(System.currentTimeMillis())
        val files = buildList {
            sessionLog?.takeIf { it.isFile }?.let { add(names.session to it.readBytes()) }
            BootRestoreLog.file(context).takeIf { it.isFile }?.let { add(names.boot to it.readBytes()) }
            add(names.logcat to readOwnLogcat())
        }
        val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            files.forEach { (name, bytes) -> writeToDownloads(context, name, bytes) }
            "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"
        } else {
            val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, FOLDER)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
            files.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes) }
            directory.absolutePath
        }
        return Result(location, files.size)
    }

    /** Apps can read their own process's log without READ_LOGS. */
    private fun readOwnLogcat(): ByteArray {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        val output = ByteArrayOutputStream()
        process.inputStream.use { it.copyTo(output) }
        if (!process.waitFor(LOGCAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroy()
        return output.toByteArray()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloads(context: Context, name: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create $name in Downloads")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Cannot open $name")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private const val LOGCAT_TIMEOUT_SECONDS = 10L
}
