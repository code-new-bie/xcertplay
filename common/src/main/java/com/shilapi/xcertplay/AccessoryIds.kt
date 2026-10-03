package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.security.MessageDigest

/**
 * The AirPlay device ID and Bluetooth ID the iPhone uses to recognise this car.
 *
 * The device ID is derived from the saved AirPlay pairing public key, so it is stable for this
 * installation and differs between head units. The Bluetooth ID is the head unit's real Bluetooth
 * address when known, otherwise the device ID.
 */
internal data class AccessoryIds(
    val deviceId: String,
    val bluetoothId: String,
    val bluetoothSource: BluetoothSource,
) {
    enum class BluetoothSource(val label: String) {
        /** Read through ADB from the settings page and saved. */
        SAVED("saved"),
        /** Readable directly, which needs the LOCAL_MAC_ADDRESS permission. */
        SYSTEM("system"),
        DEVICE_ID("deviceID"),
    }

    companion object {
        private val MAC = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")

        /** adbd's shell holds LOCAL_MAC_ADDRESS, which an installed app cannot get. */
        const val ADB_READ_COMMAND = "settings get secure bluetooth_address"

        fun of(context: Context, identity: AirPlayIdentity): AccessoryIds = resolve(
            deviceId(identity.publicKey),
            AirPlayPersistence.loadHeadUnitBluetoothAddress(context),
            bluetoothAddressCandidates(context),
        )

        fun resolve(deviceId: String, saved: String?, system: List<String?>): AccessoryIds {
            realBluetoothAddress(listOf(saved))?.let { return AccessoryIds(deviceId, it, BluetoothSource.SAVED) }
            realBluetoothAddress(system)?.let { return AccessoryIds(deviceId, it, BluetoothSource.SYSTEM) }
            return AccessoryIds(deviceId, deviceId, BluetoothSource.DEVICE_ID)
        }

        /** A locally administered unicast MAC address from the first six bytes of SHA-256(key). */
        fun deviceId(publicKey: ByteArray): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(6)
            bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it) }
        }

        /** The first candidate that is a real address, not Android's 02:00:00:00:00:xx placeholder. */
        fun realBluetoothAddress(candidates: List<String?>): String? =
            candidates.filterNotNull().map(String::trim).firstOrNull {
                MAC.matches(it) && !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
            }?.uppercase()

        /** Reads the real address through the head unit's network ADB; may ask for ADB approval. */
        fun readThroughAdb(context: Context): Pair<LocalAdb.Access, String?> =
            LocalAdb(AdbKeys.load(context)).use { adb ->
                val access = adb.connect(mayAsk = true)
                if (access != LocalAdb.Access.READY) return@use access to null
                access to realBluetoothAddress(adb.shell(ADB_READ_COMMAND).orEmpty().lines())
            }

        @Suppress("DEPRECATION", "HardwareIds")
        private fun bluetoothAddressCandidates(context: Context): List<String?> = listOf(
            runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.address }.getOrNull(),
            runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull(),
        )
    }
}
