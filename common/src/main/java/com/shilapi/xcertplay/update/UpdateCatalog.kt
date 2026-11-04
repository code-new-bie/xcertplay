package com.shilapi.xcertplay.update

import java.net.URI
import org.json.JSONObject

/** The newest published release and the APK this build would install from it. */
internal data class UpdateRelease(
    val version: ReleaseVersion,
    val tag: String,
    val notes: String,
    val publishedAt: String?,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 of the APK, as GitHub reports it for the asset. */
    val sha256: String,
)

/** Reads GitHub's answer for the latest release of this fork. */
internal object UpdateCatalog {
    const val REPOSITORY = "code-new-bie/xcertplay"
    const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases"

    private val SHA256 = Regex("^sha256:([0-9a-fA-F]{64})$")

    /** Far above this fork's APKs (about 38 MB); the copy stops at the size the release states. */
    const val MAXIMUM_APK_BYTES = 128L * 1024 * 1024

    /**
     * Release assets are served from github.com over https. The APK is installed over the head
     * unit's ADB, so an answer pointing elsewhere is not followed. After DiPlay 65ab1f36.
     */
    private fun isDownloadUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true) &&
            uri.rawUserInfo == null && uri.port == -1
    }

    /**
     * The release, or null when it is a draft or pre-release, its tag is not a version of this fork,
     * or it has no `[assetPrefix][tag].apk` on github.com with a SHA-256 digest and a plausible size:
     * an APK that cannot be checked is never offered.
     */
    fun parse(json: String, assetPrefix: String): UpdateRelease? {
        val release = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name")
        val version = ReleaseVersion.parse(tag) ?: return null
        val apkName = "$assetPrefix$tag.apk"
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") != apkName) continue
            val url = asset.optString("browser_download_url")
            if (!isDownloadUrl(url)) return null
            val sha256 = SHA256.matchEntire(asset.optString("digest"))?.groupValues?.get(1)?.lowercase()
                ?: return null
            val size = asset.optLong("size", -1L)
            if (size <= 0L || size > MAXIMUM_APK_BYTES) return null
            return UpdateRelease(
                version = version,
                tag = tag,
                notes = release.optString("body"),
                publishedAt = release.optString("published_at").takeIf { it.isNotBlank() },
                apkName = apkName,
                apkUrl = url,
                apkSize = size,
                sha256 = sha256,
            )
        }
        return null
    }

    /** The "new in this release" part of the notes, short enough for a settings page. */
    fun summary(notes: String, maxLines: Int = 8): String {
        val lines = notes.replace("\r\n", "\n").lines()
        // Notes have used "本版新增", "本版修复" and "本版改动（相比 …）" for this section.
        val start = lines.indexOfFirst { it.startsWith("### 本版") || it.startsWith("### New in") }
        val section = if (start < 0) lines else lines.drop(start + 1).takeWhile { !it.startsWith("### ") }
        return section.map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.removePrefix("- ").replace("**", "") }
            .take(maxLines)
            .joinToString("\n")
    }
}
