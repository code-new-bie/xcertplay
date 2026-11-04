package com.shilapi.xcertplay.media

import android.content.Context
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoDecoderSupportTest {
    private val hevc = "video/hevc"

    @Test
    fun encodedResultsComeBack() {
        val supported = VideoDecoderSupport.Result("OMX.qcom.video.decoder.hevc", true)
        val unsupported = VideoDecoderSupport.Result("c2.android.hevc.decoder", false)
        assertEquals(supported, VideoDecoderSupport.decode(VideoDecoderSupport.encode(supported)))
        assertEquals(unsupported, VideoDecoderSupport.decode(VideoDecoderSupport.encode(unsupported)))
        assertNull(VideoDecoderSupport.encode(VideoDecoderSupport.Result(null, false)))
        for (bad in listOf(null, "", "1|", "1", "x|name", "1-name")) assertNull(VideoDecoderSupport.decode(bad))
    }

    @Test
    fun aRememberedAnswerSkipsTheCodecList() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("xcertplay_decoder_support", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("fingerprint", Build.FINGERPRINT)
            .putString(VideoDecoderSupport.cacheKey(hevc, 1920, 1920, false), "1|OMX.qcom.video.decoder.hevc")
            .commit()

        assertEquals(
            VideoDecoderSupport.Result("OMX.qcom.video.decoder.hevc", true),
            VideoDecoderSupport.checkRemembered(context, hevc, 1920, 1920, preferSoftwareHevc = false),
        )
    }

    @Test
    fun anotherFirmwareForgetsTheAnswers() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("xcertplay_decoder_support", Context.MODE_PRIVATE)
        val key = VideoDecoderSupport.cacheKey(hevc, 1920, 1920, false)
        prefs.edit().putString("fingerprint", "old/build").putString(key, "1|OMX.old.decoder").commit()

        // The test runtime lists no decoders, and a lookup that found none is not remembered.
        assertEquals(
            VideoDecoderSupport.Result(null, false),
            VideoDecoderSupport.checkRemembered(context, hevc, 1920, 1920, preferSoftwareHevc = false),
        )
        assertNull(prefs.getString(key, null))
        assertEquals(Build.FINGERPRINT, prefs.getString("fingerprint", null))
    }
}
