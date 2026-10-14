package com.shilapi.xcertplay.update

import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Talks to GitHub: the latest release, then its APK. Blocking; call it off the main thread. */
internal class UpdateClient(private val assetPrefix: String, private val userAgent: String) {
    /** The latest release this build can install, or null when there is none. Throws on network failure. */
    fun fetchLatest(): UpdateRelease? {
        val connection = open(UpdateCatalog.LATEST_URL)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered HTTP $code")
            val body = connection.inputStream.use { input ->
                val bytes = input.readNBytesCompat(MAX_RELEASE_JSON_BYTES + 1)
                if (bytes.size > MAX_RELEASE_JSON_BYTES) throw IOException("GitHub's release answer is too large")
                String(bytes, Charsets.UTF_8)
            }
            return UpdateCatalog.parse(body, assetPrefix)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Copies the release's APK to [output] and returns its SHA-256 in lower-case hex. The size must
     * match the release; [onProgress] gets the bytes so far, and [cancelled] stops the copy.
     */
    fun download(
        release: UpdateRelease,
        output: OutputStream,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean,
    ): String {
        val connection = open(release.apkUrl)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("The download answered HTTP $code")
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            connection.inputStream.use { input ->
                while (true) {
                    if (cancelled()) throw InterruptedIOException("Download cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > release.apkSize) throw IOException("The download is larger than the release says")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    onProgress(total)
                }
            }
            if (total != release.apkSize) {
                throw IOException("The download stopped at $total of ${release.apkSize} bytes")
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // GitHub sends the APK from another https host; HttpURLConnection follows that itself.
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_RELEASE_JSON_BYTES = 1024 * 1024
    }
}
