package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExistingWifiConfigTest {
    @Test
    fun acceptsAnOpenOrWpa2Network() {
        assertNull(ExistingWifiValidation.error("Pocket Wi-Fi", ""))
        assertNull(ExistingWifiValidation.error("Pocket Wi-Fi", "12345678"))
        assertNull(ExistingWifiValidation.error("Pocket Wi-Fi", "x".repeat(63)))
    }

    @Test
    fun rejectsABlankOrTooLongName() {
        assertEquals(ExistingWifiValidation.Error.EMPTY_NAME, ExistingWifiValidation.error("  ", ""))
        assertNull(ExistingWifiValidation.error("a".repeat(32), ""))
        assertEquals(ExistingWifiValidation.Error.LONG_NAME, ExistingWifiValidation.error("a".repeat(33), ""))
    }

    @Test
    fun theNameLimitCountsUtf8BytesNotCharacters() {
        // Six Chinese characters are 18 bytes; eleven are 33.
        assertNull(ExistingWifiValidation.error("路由器的名字", ""))
        assertEquals(ExistingWifiValidation.Error.LONG_NAME, ExistingWifiValidation.error("路".repeat(11), ""))
    }

    @Test
    fun rejectsAShortOrLongPasswordButNotAnEmptyOne() {
        assertEquals(ExistingWifiValidation.Error.PASSWORD_LENGTH, ExistingWifiValidation.error("net", "1234567"))
        assertEquals(ExistingWifiValidation.Error.PASSWORD_LENGTH, ExistingWifiValidation.error("net", "x".repeat(64)))
    }

    @Test
    fun rejectsNulCharacters() {
        assertEquals(ExistingWifiValidation.Error.INVALID_CHARACTER, ExistingWifiValidation.error("a\u0000b", ""))
        assertEquals(ExistingWifiValidation.Error.INVALID_CHARACTER, ExistingWifiValidation.error("net", "1234\u00005678"))
    }

    @Test
    fun theRuntimeConfigRequiresValidDetailsOnlyInThisMode() {
        // A hotspot or Wi-Fi Direct session must not fail on a half-filled same-LAN form.
        config(WirelessHotspotMode.WIFI_P2P, ssid = "", passphrase = "short")
        config(WirelessHotspotMode.EXISTING_WIFI, ssid = "Pocket Wi-Fi", passphrase = "")
        assertThrows(IllegalArgumentException::class.java) {
            config(WirelessHotspotMode.EXISTING_WIFI, ssid = "", passphrase = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(WirelessHotspotMode.EXISTING_WIFI, ssid = "Pocket Wi-Fi", passphrase = "short")
        }
    }

    private fun config(
        mode: WirelessHotspotMode,
        ssid: String,
        passphrase: String,
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.REMOTE,
        remoteMfiServer = "https://mfi.example.test",
        wirelessHotspotMode = mode,
        existingWifiSsid = ssid,
        existingWifiPassphrase = passphrase,
        identification = Iap2IdentificationConfig(
            name = "test",
            modelIdentifier = "test",
            manufacturer = "test",
            serialNumber = "test",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
    )
}
