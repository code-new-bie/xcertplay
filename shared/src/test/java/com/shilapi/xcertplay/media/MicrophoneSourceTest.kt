package com.shilapi.xcertplay.media

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneSourceTest {
    @Test
    fun callsUseTheEchoCancellingSourceAndSiriTheRecognitionSource() {
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MicrophoneSource.forAudioType("telephony"))
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, MicrophoneSource.forAudioType("speechRecognition"))
        assertEquals(MediaRecorder.AudioSource.MIC, MicrophoneSource.forAudioType("default"))
        assertEquals("VOICE_COMMUNICATION", MicrophoneSource.label(MediaRecorder.AudioSource.VOICE_COMMUNICATION))
    }
}
