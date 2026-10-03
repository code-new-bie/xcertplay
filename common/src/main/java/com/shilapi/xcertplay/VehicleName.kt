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
    const val DEFAULT = "xcertplay"
    const val MAX_LENGTH = 64

    fun sanitize(value: String?): String? =
        value?.filterNot(Char::isISOControl)?.trim()?.take(MAX_LENGTH)?.trim()?.takeIf { it.isNotEmpty() }

    fun resolve(customName: String?, bluetoothName: String?): String =
        sanitize(customName) ?: sanitize(bluetoothName) ?: DEFAULT

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
