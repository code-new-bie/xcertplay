package com.shilapi.xcertplay.orchestration

/** Checks the router name and password typed for [WirelessHotspotMode.EXISTING_WIFI]. */
object ExistingWifiValidation {
    enum class Error {
        EMPTY_NAME,
        LONG_NAME,
        INVALID_CHARACTER,
        PASSWORD_LENGTH,
    }

    /** An empty password means an open network; otherwise WPA2 needs 8 to 63 characters. */
    fun error(ssid: String, passphrase: String): Error? = when {
        ssid.isBlank() -> Error.EMPTY_NAME
        ssid.encodeToByteArray().size > 32 -> Error.LONG_NAME
        '\u0000' in ssid || '\u0000' in passphrase -> Error.INVALID_CHARACTER
        passphrase.isNotEmpty() && passphrase.length !in 8..63 -> Error.PASSWORD_LENGTH
        else -> null
    }
}
