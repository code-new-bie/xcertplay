package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.util.Log

/**
 * A head-unit-defined audio channel number. Some head units route audio by legacy stream number
 * through their own audio policy rather than by usage, so a non-zero number is handed to the
 * platform as a legacy stream type. 0 keeps the usage-based routing.
 */
object VehicleAudioChannel {
    const val AUTOMATIC = 0
    const val MAX = 40

    fun sanitize(channel: Int): Int = channel.coerceIn(AUTOMATIC, MAX)

    /** Attributes for [channel], or null to keep usage routing (automatic or rejected numbers). */
    fun attributes(channel: Int): AudioAttributes? {
        if (channel !in 1..MAX) return null
        return try {
            AudioAttributes.Builder().setLegacyStreamType(channel).build()
        } catch (error: Exception) {
            Log.w(TAG, "audio channel $channel rejected; keeping usage routing", error)
            null
        }
    }

    private const val TAG = "VehicleAudioChannel"
}
