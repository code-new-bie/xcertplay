package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.util.Log

/**
 * A head-unit-defined audio channel number. Some head units route audio by legacy stream number
 * through their own audio policy rather than by usage, so a number 0..[MAX] is handed to the
 * platform as a legacy stream type (BYD: 0 call, 6 Bluetooth call, 14 navigation). -1 keeps the
 * usage-based routing.
 */
object VehicleAudioChannel {
    const val AUTOMATIC = -1
    const val MAX = 40

    fun sanitize(channel: Int): Int = channel.coerceIn(AUTOMATIC, MAX)

    /** Attributes for [channel], or null to keep usage routing (automatic or rejected numbers). */
    fun attributes(channel: Int): AudioAttributes? {
        if (channel !in 0..MAX) return null
        return try {
            AudioAttributes.Builder().setLegacyStreamType(channel).build()
        } catch (error: Exception) {
            Log.w(TAG, "audio channel $channel rejected; keeping usage routing", error)
            null
        }
    }

    private const val TAG = "VehicleAudioChannel"
}
