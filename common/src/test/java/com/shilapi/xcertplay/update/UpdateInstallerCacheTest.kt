package com.shilapi.xcertplay.update

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class UpdateInstallerCacheTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun onlyAnApkLeftFromAnInstallLongOverIsRemoved() {
        val folder = File(app.cacheDir, "update").apply { mkdirs() }
        val now = 10_000_000_000L
        val old = File(folder, "xcertplay-mobile-1.4.0-byd.13.apk").apply { writeText("old"); setLastModified(now - 2 * 3_600_000L) }
        val fresh = File(folder, "xcertplay-mobile-1.4.0-byd.14.apk").apply { writeText("new"); setLastModified(now - 60_000L) }

        UpdateInstaller.clearStaleCache(app, now)

        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun withoutTheInstallPermissionTheInstallerIsNotOpenedByItself() {
        // Robolectric, like the BYD firmware, has not allowed "install unknown apps" for this app.
        assertFalse(UpdateInstaller.mayOpenInstaller(app))
    }

    @Test
    fun bydsFileManagerIsOfferedOnlyWhereItIsInstalled() {
        assertNull(UpdateInstaller.fileManagerIntent(app))

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(UpdateInstaller.BYD_FILE_MANAGER)
        val activity = ActivityInfo().apply {
            packageName = UpdateInstaller.BYD_FILE_MANAGER
            name = "com.byd.filemanager.view.MainActivity"
            applicationInfo = ApplicationInfo().apply { packageName = UpdateInstaller.BYD_FILE_MANAGER }
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = UpdateInstaller.BYD_FILE_MANAGER })
        shadowOf(app.packageManager).addResolveInfoForIntent(launcher, ResolveInfo().apply { activityInfo = activity })

        val intent = UpdateInstaller.fileManagerIntent(app)
        assertNotNull(intent)
        assertEquals(UpdateInstaller.BYD_FILE_MANAGER, intent!!.component?.packageName)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
