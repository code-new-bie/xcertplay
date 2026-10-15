package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class UpdateCatalogTest {
    private val sha = "6d6df45949be99c8252d5a9c293c47efccd497c93d11c9f3405322c2e56be1f1"

    private fun asset(name: String, digest: String? = "sha256:$sha", size: Long = 38_119_913L, url: String? = null) =
        """{"name":"$name","size":$size,${digest?.let { "\"digest\":\"$it\"," } ?: ""}
           "browser_download_url":"${url ?: "https://github.com/code-new-bie/xcertplay/releases/download/1.4.0-byd.11/$name"}"}"""

    private fun release(
        tag: String = "1.4.0-byd.11",
        assets: String = listOf(
            asset("xcertplay-automotive-$tag.apk"),
            asset("xcertplay-mobile-$tag.apk"),
        ).joinToString(","),
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"published_at":"2026-10-14T01:35:00Z",
           "body":"## CarPlay\n\n### 本版新增（相比 1.4.0-byd.10）\n- **音乐**：修复\n- GPS\n\n### 已知问题\n- 无",
           "assets":[$assets]}"""

    @Test
    fun picksThisModulesApkWithItsDigest() {
        val mobile = UpdateCatalog.parse(release(), "xcertplay-mobile-")
        assertNotNull(mobile)
        mobile!!
        assertEquals(ReleaseVersion(1, 4, 0, 11), mobile.version)
        assertEquals("xcertplay-mobile-1.4.0-byd.11.apk", mobile.apkName)
        assertEquals(sha, mobile.sha256)
        assertEquals(38_119_913L, mobile.apkSize)
        assertEquals("2026-10-14T01:35:00Z", mobile.publishedAt)
        assertEquals("xcertplay-automotive-1.4.0-byd.11.apk", UpdateCatalog.parse(release(), "xcertplay-automotive-")!!.apkName)
    }

    @Test
    fun anApkThatCannotBeCheckedIsNeverOffered() {
        assertNull(UpdateCatalog.parse(release(assets = asset("xcertplay-mobile-1.4.0-byd.11.apk", digest = null)), "xcertplay-mobile-"))
        assertNull(UpdateCatalog.parse(release(assets = asset("xcertplay-mobile-1.4.0-byd.11.apk", digest = "md5:abc")), "xcertplay-mobile-"))
        assertNull(UpdateCatalog.parse(release(assets = asset("xcertplay-mobile-1.4.0-byd.11.apk", size = 0)), "xcertplay-mobile-"))
        assertNull(
            UpdateCatalog.parse(
                release(assets = asset("xcertplay-mobile-1.4.0-byd.11.apk", url = "http://example.com/x.apk")),
                "xcertplay-mobile-",
            ),
        )
    }

    @Test
    fun draftsPreReleasesOtherTagsAndMissingAssetsAreIgnored() {
        assertNull(UpdateCatalog.parse(release(draft = true), "xcertplay-mobile-"))
        assertNull(UpdateCatalog.parse(release(prerelease = true), "xcertplay-mobile-"))
        assertNull(UpdateCatalog.parse(release(tag = "1.4.0"), "xcertplay-mobile-"))
        assertNull(UpdateCatalog.parse(release(assets = asset("xcertplay-automotive-1.4.0-byd.11.apk")), "xcertplay-mobile-"))
        assertNull(UpdateCatalog.parse("not json", "xcertplay-mobile-"))
    }

    @Test
    fun theSummaryIsTheNewInThisReleaseSection() {
        val notes = UpdateCatalog.parse(release(), "xcertplay-mobile-")!!.notes
        assertEquals("音乐：修复\nGPS", UpdateCatalog.summary(notes))
        assertEquals("a\nb", UpdateCatalog.summary("- a\n- b\n- c", maxLines = 2))
        // Later notes call it "本版修复" or "本版改动（相比 …）".
        val later = "## 1.4.0-byd.16\n### 安装\n- 安装说明\n### 本版改动（相比 1.4.0-byd.15）\n- **有线**\n  - 改了读法\n" +
            "### 相比原版的改动\n- 别的"
        assertEquals("有线\n改了读法", UpdateCatalog.summary(later))
    }
}
