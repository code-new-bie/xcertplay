package com.shilapi.xcertplay.network

/** Keep legacy WifiConfiguration enums separate from SoftApConfiguration's bit masks. */
internal object LocalOnlyHotspotRadio {
    data class Channel(val number: Int, val frequencyMHz: Int) {
        val bandLabel: String get() = when (frequencyMHz) {
            in 2400..2500 -> "2.4 GHz"
            in 4900..5900 -> "5 GHz"
            in 5925..7125 -> "6 GHz"
            else -> "Unknown band"
        }
    }

    fun legacyBandLabel(band: Int?, channel: Int): String = when (band) {
        0 -> "2.4 GHz"
        1 -> "5 GHz"
        -1 -> "Automatic band"
        else -> when (channel) {
            in 1..14 -> "2.4 GHz"
            in 32..177 -> "5 GHz"
            else -> "Unknown band"
        }
    }

    /** Accept only the requested live AP interface, never station or historical scan output. */
    fun parseIw(output: String?, interfaceName: String): Channel? {
        if (output == null) return null
        val lines = output.lineSequence().map(String::trim).toList()
        if (lines.firstOrNull() != "Interface $interfaceName" || "type AP" !in lines) return null
        val channel = lines.firstNotNullOfOrNull {
            Regex("^channel (\\d+) \\((\\d+) MHz\\).*$").matchEntire(it)
        } ?: return null
        val number = channel.groupValues[1].toIntOrNull() ?: return null
        val frequency = channel.groupValues[2].toIntOrNull() ?: return null
        if (number !in 1..233 || frequency !in 2400..7125) return null
        return Channel(number, frequency)
    }
}
