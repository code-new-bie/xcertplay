package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus

/** The five steps the home screen shows while CarPlay connects. */
internal enum class ConnectionStep { AUTHENTICATION, LINK, PHONE, HANDSHAKE, CARPLAY }

internal enum class StepState { PENDING, ACTIVE, DONE, FAILED }

/** What a failure most likely needs from the driver. */
internal enum class FailureKind {
    AUTHENTICATION,
    BLUETOOTH_OFF,
    NO_PAIRED_IPHONE,
    WIFI_P2P,
    HOTSPOT,
    USB,
    TIMEOUT,
    OTHER,
}

/** Maps the controller's detailed stages onto the home screen's steps. */
internal object ConnectionProgress {
    fun stepOf(status: CarPlayStatus): ConnectionStep? = when (status) {
        CarPlayStatus.DiscoveringMfi,
        CarPlayStatus.WaitingForMfi,
        CarPlayStatus.RequestingMfiPermission -> ConnectionStep.AUTHENTICATION
        CarPlayStatus.MfiReady,
        CarPlayStatus.StartingHotspot,
        CarPlayStatus.DiscoveringIphone,
        CarPlayStatus.WaitingForIphone,
        CarPlayStatus.RequestingIphonePermission,
        CarPlayStatus.WaitingForReenumeration,
        CarPlayStatus.SelectingConfiguration,
        CarPlayStatus.OpeningDataPaths -> ConnectionStep.LINK
        is CarPlayStatus.HotspotReady,
        CarPlayStatus.WaitingForPairedIphone,
        CarPlayStatus.ConnectingBluetooth,
        CarPlayStatus.Pairing -> ConnectionStep.PHONE
        CarPlayStatus.RunningWireless,
        CarPlayStatus.ConnectingControl,
        CarPlayStatus.AttachingNetwork -> ConnectionStep.HANDSHAKE
        CarPlayStatus.WirelessActive,
        CarPlayStatus.RunningControl -> ConnectionStep.CARPLAY
        CarPlayStatus.ControlEnded,
        is CarPlayStatus.Failed -> null
    }

    fun stateOf(step: ConnectionStep, current: ConnectionStep, failed: Boolean): StepState = when {
        step.ordinal < current.ordinal -> StepState.DONE
        step.ordinal > current.ordinal -> StepState.PENDING
        failed -> StepState.FAILED
        else -> StepState.ACTIVE
    }

    /** Ordered so the most specific cause wins: a P2P hotspot failure also mentions "hotspot". */
    fun failureKind(message: String): FailureKind {
        val text = message.lowercase()
        return when {
            "wifi_p2p" in text || "wi-fi p2p" in text || "5 ghz group" in text -> FailureKind.WIFI_P2P
            "hotspot" in text -> FailureKind.HOTSPOT
            "bluetooth is not enabled" in text || "bluetooth adapter is unavailable" in text ->
                FailureKind.BLUETOOTH_OFF
            "no bonded" in text || "pair an iphone" in text || "rfcomm" in text -> FailureKind.NO_PAIRED_IPHONE
            "mfi" in text || "coprocessor" in text || "ch341" in text || "i2c" in text ||
                "baa" in text || "authenticationfailed" in text || "certificate" in text -> FailureKind.AUTHENTICATION
            "usbmux" in text || "iphone usb" in text || "ncm" in text -> FailureKind.USB
            "timed out" in text || "timeout" in text -> FailureKind.TIMEOUT
            else -> FailureKind.OTHER
        }
    }
}
