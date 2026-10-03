package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChannelMappingTest {
    @Test
    fun mutingLocalMediaKeepsGuidanceCallsAndSiri() {
        val automotive = AudioChannelMappingMode.AUTOMOTIVE_BUS
        val mobile = AudioChannelMappingMode.MOBILE_COMPATIBLE
        assertFalse(AudioChannelMapper.shouldPlayLocally("media", 100, automotive, true))
        assertFalse(AudioChannelMapper.shouldPlayLocally("media", 100, mobile, true))
        assertFalse(AudioChannelMapper.shouldPlayLocally("compatibility", 100, automotive, true))
        for (audioType in listOf("alert", "telephony", "speechRecognition")) {
            assertTrue(AudioChannelMapper.shouldPlayLocally(audioType, 100, automotive, true))
            assertTrue(AudioChannelMapper.shouldPlayLocally(audioType, 100, mobile, true))
        }
        // Mobile mode keeps xcertplay's guidance routing for default/compatibility streams.
        assertTrue(AudioChannelMapper.shouldPlayLocally("default", 100, mobile, true))
        assertTrue(AudioChannelMapper.shouldPlayLocally("compatibility", 100, mobile, true))
        assertTrue(AudioChannelMapper.shouldPlayLocally("media", 100, automotive, false))
    }

    @Test
    fun vehicleAudioChannelIsLimitedToHeadUnitRange() {
        assertEquals(0, VehicleAudioChannel.sanitize(-3))
        assertEquals(7, VehicleAudioChannel.sanitize(7))
        assertEquals(VehicleAudioChannel.MAX, VehicleAudioChannel.sanitize(99))
    }

    @Test
    fun mobileCompatibleMappingMatchesTheOriginalRouting() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "media",
            payloadType = 100,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
        listOf("default", "alert", "compatibility").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.NAVIGATION,
                contentType = AudioContentType.SPEECH,
            )
        }
    }

    @Test
    fun automotiveMappingUsesTheBusSpecificCarPlayTypes() {
        listOf("media", "default", "compatibility").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.MEDIA,
                contentType = AudioContentType.MUSIC,
            )
        }
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "alert",
            payloadType = 100,
            channel = AudioChannel.NAVIGATION,
            contentType = AudioContentType.SPEECH,
        )
    }

    @Test
    fun unknownTypesKeepTheMainHighAudioFallback() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "unknown",
            payloadType = AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "unknown",
            payloadType = 100,
            channel = AudioChannel.NAVIGATION,
            contentType = AudioContentType.SPEECH,
        )
    }

    private fun assertMapped(
        mode: AudioChannelMappingMode,
        audioType: String,
        payloadType: Int,
        channel: AudioChannel,
        contentType: AudioContentType,
    ) {
        assertEquals(
            AudioChannelSelection(channel, contentType),
            AudioChannelMapper.map(audioType, payloadType, mode),
        )
    }
}
