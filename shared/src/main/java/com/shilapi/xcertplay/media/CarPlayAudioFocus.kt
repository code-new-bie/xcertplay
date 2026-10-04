package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Audio focus for CarPlay audio. BYD head units send steering-wheel media keys to the media
 * session of the media audio-focus owner, so while CarPlay plays music xcertplay holds long-term
 * media focus until the session ends; when another head-unit source takes it, the next CarPlay
 * music start takes it back. Calls hold transient call focus so the head unit does not play over
 * them. One instance serves the process.
 */
internal object CarPlayAudioFocus {
    private val handler = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    private var diagnostic: (String) -> Unit = {}
    private var mediaRequest: AudioFocusRequest? = null
    private var mediaHeld = false
    private var callRequest: AudioFocusRequest? = null

    fun onMediaStarted(context: Context, log: (String) -> Unit) = handler.post {
        bind(context, log)
        if (mediaHeld) return@post
        val request = mediaRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                // Only a permanent loss hands the steering-wheel keys to another source.
                if (change == AudioManager.AUDIOFOCUS_LOSS) mediaHeld = false
                report("Audio focus (media) changed: ${describe(change)}")
            }, handler)
            .build()
            .also { mediaRequest = it }
        mediaHeld = audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        report("Audio focus (media) requested for steering-wheel keys: granted=$mediaHeld")
    }

    fun onCallStarted(context: Context, log: (String) -> Unit) = handler.post {
        bind(context, log)
        if (callRequest != null) return@post
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                report("Audio focus (call) changed: ${describe(change)}")
            }, handler)
            .build()
        callRequest = request
        val granted = audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        report("Audio focus (call) requested: granted=$granted")
    }

    fun onCallStopped() = handler.post {
        val request = callRequest ?: return@post
        callRequest = null
        audio?.abandonAudioFocusRequest(request)
        report("Audio focus (call) released")
    }

    /** The CarPlay session ended: give the head unit its sources and keys back. */
    fun releaseAll() = handler.post {
        callRequest?.let { audio?.abandonAudioFocusRequest(it) }
        callRequest = null
        val request = mediaRequest ?: return@post
        if (mediaHeld) report("Audio focus (media) released")
        audio?.abandonAudioFocusRequest(request)
        mediaHeld = false
    }

    private fun bind(context: Context, log: (String) -> Unit) {
        diagnostic = log
        if (audio == null) audio = context.applicationContext.getSystemService(AudioManager::class.java)
    }

    private fun describe(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "gain"
        AudioManager.AUDIOFOCUS_LOSS -> "loss"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "transient loss"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "transient loss (duck)"
        else -> change.toString()
    }

    private fun report(message: String) {
        runCatching { diagnostic(message) }
    }
}
