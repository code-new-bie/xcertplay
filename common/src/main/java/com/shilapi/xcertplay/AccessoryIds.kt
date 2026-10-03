package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * The AirPlay device ID and fallback Bluetooth ID the iPhone uses to recognise this car. Both are
 * derived from the head unit's Android ID, so they are stable across launches and differ between
 * head units; they are locally administered unicast MAC addresses, never a vendor-assigned one.
 */
internal data class AccessoryIds(val deviceId: String, val bluetoothId: String) {
    companion object {
        val FALLBACK = AccessoryIds(deviceId = "02:00:00:00:00:02", bluetoothId = "02:00:00:00:00:01")

        fun of(context: Context): AccessoryIds = forAndroidId(
            runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull(),
        )

        fun forAndroidId(androidId: String?): AccessoryIds {
            val seed = androidId?.trim()?.takeIf { it.isNotEmpty() } ?: return FALLBACK
            return AccessoryIds(
                deviceId = localMac("airplay-device:$seed"),
                bluetoothId = localMac("bluetooth:$seed"),
            )
        }

        private fun localMac(seed: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).copyOf(6)
            // Set the locally administered bit and clear the multicast bit.
            bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it) }
        }
    }
}
