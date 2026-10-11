package com.shilapi.xcertplay.media

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
