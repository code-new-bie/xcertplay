package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings

/**
 * The vehicle name shown in the iPhone's car list and on the CarPlay return-to-car icon.
 * A saved custom name wins; otherwise it follows the head unit's Bluetooth name, which is what
 * the driver already sees in the iPhone's Bluetooth list.
 */
internal object VehicleName {
    /**
     * Used when the Bluetooth name is unreadable, e.g. before Bluetooth permission is granted. The
     * iPhone keeps the name it first sees, so this must read well as a car name.
     */
    const val DEFAULT = "${AirPlayPersistence.DEFAULT_MANUFACTURER} ${AirPlayPersistence.DEFAULT_MODEL}"
    const val MAX_LENGTH = 64

    enum class Source(val label: String) { CUSTOM("custom"), BLUETOOTH("Bluetooth"), FALLBACK("fallback") }

    fun sanitize(value: String?): String? =
        value?.filterNot(Char::isISOControl)?.trim()?.take(MAX_LENGTH)?.trim()?.takeIf { it.isNotEmpty() }

    fun resolve(customName: String?, bluetoothName: String?): String =
        resolveWithSource(customName, bluetoothName).first

    fun resolveWithSource(customName: String?, bluetoothName: String?): Pair<String, Source> =
        sanitize(customName)?.let { it to Source.CUSTOM }
            ?: sanitize(bluetoothName)?.let { it to Source.BLUETOOTH }
            ?: (DEFAULT to Source.FALLBACK)

    fun bluetoothName(context: Context): String? {
        val adapter = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.name
        }.getOrNull()
        val setting = runCatching {
            Settings.Secure.getString(context.contentResolver, "device_name")
        }.getOrNull()
        return listOfNotNull(adapter, setting).firstNotNullOfOrNull(::sanitize)
    }
}
