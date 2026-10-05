package com.shilapi.xcertplay

internal data class CarPlayShutdownResult(
    val connectionClosed: Boolean,
    val bluetoothRestored: Boolean,
    val wifiSearchResumed: Boolean,
)

/** Disconnect first, and still attempt every restore if an earlier cleanup step fails. */
internal fun runCarPlayShutdown(
    disconnect: () -> Boolean,
    closeMedia: () -> Unit,
    restoreBluetooth: () -> Boolean,
    resumeWifiSearch: () -> Boolean,
    onFailure: (String, Exception) -> Unit,
): CarPlayShutdownResult {
    fun attempt(name: String, action: () -> Boolean): Boolean = try {
        action()
    } catch (error: Exception) {
        onFailure(name, error)
        false
    }
    val connectionClosed = attempt("CarPlay connection", disconnect)
    val mediaClosed = attempt("media") { closeMedia(); true }
    val bluetoothRestored = attempt("Bluetooth", restoreBluetooth)
    val wifiSearchResumed = attempt("Wi-Fi search", resumeWifiSearch)
    return CarPlayShutdownResult(connectionClosed && mediaClosed, bluetoothRestored, wifiSearchResumed)
}
