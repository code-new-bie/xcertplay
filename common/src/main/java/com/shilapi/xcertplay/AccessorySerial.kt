package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * The iAP2 accessory serial number: a fixed BYD prefix plus a code derived from this head unit's
 * Android ID, so it is stable across launches and differs between head units.
 */
internal object AccessorySerial {
    const val PREFIX = "BYD-SongPLUS-"
    private const val CODE_LENGTH = 8

    fun of(context: Context): String = forAndroidId(
        runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull(),
    )

    fun forAndroidId(androidId: String?): String {
        val seed = androidId?.trim()?.takeIf { it.isNotEmpty() } ?: return PREFIX + "0".repeat(CODE_LENGTH)
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return PREFIX + digest.joinToString("") { "%02X".format(it) }.take(CODE_LENGTH)
    }
}
