package com.shilapi.xcertplay.update

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Gets an update APK ready and installs it through the head unit's own ADB, which needs no
 * installer screen. The APK is downloaded and checked in this app's own cache: its SHA-256 must
 * match GitHub's, and it must be this app, at the release's version, signed like this app. Only
 * then is it copied to Download/xcertplay, where the ADB shell and a file manager can open it; this
 * app itself cannot open files there by path on Android 10.
 *
 * Without ADB the checked copy in the cache goes to the system installer, the way DiPlay installs
 * (`aa7cd204`), when Android lets this app open it. BYD's settings answer the "install unknown apps"
 * page with "not supported", so on a BYD head unit the driver usually installs it with BYD's file
 * manager, a system app that the installer does not question.
 */
internal object UpdateInstaller {
    /** The folder the log export uses too. */
    const val FOLDER = "xcertplay"
    const val LOG_PATH = "/data/local/tmp/xcertplay-update.log"
    /** How long the install waits for this app to finish exiting before it replaces it anyway. */
    const val EXIT_WAIT_SECONDS = 30
    private const val CACHE_FOLDER = "update"
    /** Matches `update_apk_paths.xml` and the provider in the manifest. */
    private const val PROVIDER_SUFFIX = ".update-apks"
    /** A cached APK this old is left over from an install that is long over. */
    private const val STALE_CACHE_MILLIS = 60 * 60 * 1000L
    const val BYD_FILE_MANAGER = "com.byd.filemanager"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val OLD_APKS = "xcertplay-%.apk"

    private val SAFE_PACKAGE = Regex("^[A-Za-z0-9_.]+$")
    private val SAFE_COMPONENT = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.]+$")
    private val SAFE_PATH = Regex("^/[A-Za-z0-9_./ ()+-]+$")

    /** Downloads [release] into this app's cache and checks its SHA-256; returns the file. */
    fun download(
        context: Context,
        client: UpdateClient,
        release: UpdateRelease,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean,
    ): File {
        val folder = File(context.cacheDir, CACHE_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not create $folder")
        folder.listFiles()?.forEach { it.delete() }
        val target = File(folder, release.apkName)
        try {
            val sha256 = target.outputStream().use { output -> client.download(release, output, onProgress, cancelled) }
            if (sha256 != release.sha256) throw IOException("The downloaded APK does not match its SHA-256")
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /** Refuses an APK that is not this app at [release]'s version, signed with this app's key. */
    fun verify(context: Context, apk: File, release: UpdateRelease) {
        val manager = context.packageManager
        // Both flags: some Android 9-10 builds fill only the older signatures for an archive.
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(apk.path, flags)
            ?: throw IOException("Android cannot read the downloaded APK")
        if (archive.packageName != context.packageName) {
            throw IOException("The APK is ${archive.packageName}, not ${context.packageName}")
        }
        if (archive.versionName != release.tag) {
            throw IOException("The APK is version ${archive.versionName}, not ${release.tag}")
        }
        val signers = signers(archive) ?: throw IOException("The APK's signature cannot be read")
        val installed = signers(manager.getPackageInfo(context.packageName, flags))
        if (signers != installed) throw IOException("The APK is not signed with this app's key")
    }

    /**
     * Copies the checked [apk] to Download/xcertplay (on Android 9, this app's own download folder)
     * and returns its path there. Earlier update APKs there are removed first. The cached [apk] stays
     * for the system installer.
     */
    fun publish(context: Context, apk: File, release: UpdateRelease): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(context, apk, release)
        } else {
            publishToAppFolder(context, apk, release)
        }

    /** Removes cached update APKs older than an hour; a fresh one may still be in an installer. */
    fun clearStaleCache(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        File(context.cacheDir, CACHE_FOLDER).listFiles()
            ?.filter { nowMillis - it.lastModified() > STALE_CACHE_MILLIS }
            ?.forEach { it.delete() }
    }

    /**
     * Whether Android lets this app open the installer ("install unknown apps" allowed for it). On the
     * BYD firmware checked, nothing in the settings can allow it, so this is false there unless the
     * head unit's ADB allowed it.
     */
    fun mayOpenInstaller(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** What opens the installer, and how many apps said they can install an APK. */
    class InstallerLaunch(val intent: Intent, val installers: Int)

    /**
     * The installer for the cached [apk]. With several apps that can install it (a file manager such
     * as MT Manager), Android asks which one, as in DiPlay.
     */
    fun installerLaunch(context: Context, apk: File): InstallerLaunch {
        val uri = FileProvider.getUriForFile(context, context.packageName + PROVIDER_SUFFIX, apk)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val installers = context.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).size
        val launch = if (installers > 1) Intent.createChooser(view, null) else view
        // A task of its own, so the installer stays in front when this app moves back and exits.
        return InstallerLaunch(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), installers)
    }

    /** BYD's file manager, or null on other head units. */
    fun fileManagerIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(BYD_FILE_MANAGER)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The shell command that installs [apkPath] and opens the app again. It runs detached, so it
     * survives the end of this app's ADB connection, and waits until this app has exited (Bluetooth,
     * Wi-Fi search and app permissions restored) before `pm install` replaces it.
     */
    fun installCommand(packageName: String, apkPath: String, launchComponent: String): String {
        require(SAFE_PACKAGE.matches(packageName)) { "Unexpected package name: $packageName" }
        require(SAFE_PATH.matches(apkPath)) { "Unexpected APK path: $apkPath" }
        require(SAFE_COMPONENT.matches(launchComponent)) { "Unexpected launch component: $launchComponent" }
        val script = "i=0; while [ \$i -lt $EXIT_WAIT_SECONDS ] && pidof $packageName >/dev/null; " +
            "do sleep 1; i=\$((i+1)); done; " +
            "pm install -r -t \"$apkPath\" >$LOG_PATH 2>&1; " +
            "am start -n $launchComponent >>$LOG_PATH 2>&1"
        return "nohup sh -c '$script' >/dev/null 2>&1 &"
    }

    /**
     * Starts the install over the head unit's ADB. Returns the ADB access state; anything but
     * READY means nothing was started. [beforeInstall] runs just before the command is sent.
     */
    fun installOverAdb(context: Context, apkPath: String, beforeInstall: () -> Unit): LocalAdb.Access {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.component?.flattenToShortString()
            ?: throw IOException("This app has no launcher activity to open after the update")
        val command = installCommand(context.packageName, apkPath, launch)
        LocalAdb(AdbKeys.load(context)).use { adb ->
            val access = adb.connect(mayAsk = false)
            if (access != LocalAdb.Access.READY) return access
            beforeInstall()
            adb.shell(command) ?: throw IOException("The head unit's ADB did not run the install command")
            return access
        }
    }

    /** What `pm install` printed for the last update, read over ADB; null when it cannot be read. */
    fun readInstallLog(context: Context): String? =
        LocalAdb(AdbKeys.load(context)).use { adb ->
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return null
            adb.shell("cat $LOG_PATH 2>/dev/null")
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToMediaStore(context: Context, apk: File, release: UpdateRelease): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"
        // Earlier update APKs this app saved: about 38 MB each, they would pile up otherwise.
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(relativePath, OLD_APKS),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null) }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, release.apkName)
            put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create the APK in Download/$FOLDER")
        try {
            resolver.openOutputStream(uri)?.use { output -> apk.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("Could not write the APK in Download/$FOLDER")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            // The shell that installs it needs a file path; MediaStore still reports it on Android 10.
            @Suppress("DEPRECATION")
            val path = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            return path ?: throw IOException("Android did not say where the APK was saved")
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun publishToAppFolder(context: Context, apk: File, release: UpdateRelease): String {
        val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not create $folder")
        folder.listFiles { file -> file.name.startsWith("xcertplay-") && file.name.endsWith(".apk") }
            ?.forEach { it.delete() }
        val target = File(folder, release.apkName)
        try {
            apk.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            return target.absolutePath
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String>? =
        (info.signingInfo?.apkContentsSigners ?: info.signatures)
            ?.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
}
