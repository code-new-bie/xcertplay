package com.shilapi.xcertplay.media

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/** Whether the decoder CarPlay video will use can take a frame size. */
object VideoDecoderSupport {
    data class Result(val decoder: String?, val supported: Boolean)

    /**
     * Checks the decoder [AndroidMediaSink] would create: the software HEVC decoder when it prefers
     * one, otherwise the first decoder for [mime], as MediaCodec.createDecoderByType picks it.
     */
    fun check(mime: String, width: Int, height: Int, preferSoftwareHevc: Boolean): Result = runCatching {
        val decoder = decoderFor(mime, preferSoftwareHevc) ?: return Result(null, false)
        val video = decoder.getCapabilitiesForType(mime).videoCapabilities
        Result(decoder.name, video?.isSizeSupported(width, height) == true)
    }.getOrDefault(Result(null, false))

    /**
     * [check], remembered for this system build: listing the codecs takes about 0.2 s before every
     * connection, and the answer only changes with the firmware. A lookup that found no decoder is
     * not remembered.
     */
    fun checkRemembered(context: Context, mime: String, width: Int, height: Int, preferSoftwareHevc: Boolean): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(FINGERPRINT_KEY, null) != Build.FINGERPRINT) {
            prefs.edit().clear().putString(FINGERPRINT_KEY, Build.FINGERPRINT).apply()
        }
        val key = cacheKey(mime, width, height, preferSoftwareHevc)
        decode(prefs.getString(key, null))?.let { return it }
        return check(mime, width, height, preferSoftwareHevc).also { result ->
            encode(result)?.let { prefs.edit().putString(key, it).apply() }
        }
    }

    internal fun cacheKey(mime: String, width: Int, height: Int, preferSoftwareHevc: Boolean): String =
        "$mime ${width}x$height software=$preferSoftwareHevc"

    internal fun encode(result: Result): String? =
        result.decoder?.let { (if (result.supported) "1|" else "0|") + it }

    internal fun decode(value: String?): Result? {
        if (value == null || value.length < 3 || value[1] != '|') return null
        return when (value[0]) {
            '1' -> Result(value.substring(2), true)
            '0' -> Result(value.substring(2), false)
            else -> null
        }
    }

    private const val PREFS = "xcertplay_decoder_support"
    private const val FINGERPRINT_KEY = "fingerprint"

    private fun decoderFor(mime: String, preferSoftwareHevc: Boolean): MediaCodecInfo? {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        if (preferSoftwareHevc && mime == MediaFormat.MIMETYPE_VIDEO_HEVC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            decoders.firstOrNull { it.isSoftwareOnly }?.let { return it }
        }
        return decoders.firstOrNull()
    }
}
