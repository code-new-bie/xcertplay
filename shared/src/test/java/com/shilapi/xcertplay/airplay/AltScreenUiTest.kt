package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AltScreenUiTest {
    private val map = "maps:/car/instrumentcluster/map"
    private val card = "maps:/car/instrumentcluster/instructioncard"
    private val both = "maps:/car/instrumentcluster"

    @Test
    fun theChosenContentIsUsedWhenOfferedElseAnotherClusterView() {
        assertEquals(card, AltScreenUi.choose(card, listOf(map, card)))
        assertEquals(map, AltScreenUi.choose(card, listOf("music:/car/nowplaying", map)))
        assertEquals("music:/car/nowplaying", AltScreenUi.choose(card, listOf("music:/car/nowplaying")))
        assertEquals("Nothing offered keeps the choice", map, AltScreenUi.choose(map, emptyList()))
        assertEquals(listOf(map, both), AltScreenUi.offered(mapOf("altScreenURLs" to listOf(map, "", both, map, 7))))
        assertEquals(emptyList<String>(), AltScreenUi.offered(null))
    }

    @Test
    fun showUiGoesOutOnceEverythingIsReadyAndAgainAfterANewStream() {
        val sent = mutableListOf<Map<String, Any?>>()
        val ui = AltScreenUi { sent += it; true }
        ui.select(map)
        assertFalse(ui.setEventReady(true))
        assertFalse(ui.setEnabled(true))
        assertTrue(ui.setStreamReady(true))
        assertFalse("Only once per stream", ui.setEventReady(true))
        ui.setStreamReady(false)
        assertTrue(ui.setStreamReady(true))
        assertEquals(2, sent.size)
        assertEquals("showUI", sent[0]["type"])
        assertEquals(mapOf("uuid" to AirPlayInfoPlist.ALT_UUID, "url" to map), sent[0]["params"])
    }

    @Test
    fun withoutTheFeaturesOrContentNothingIsSent() {
        val sent = mutableListOf<Map<String, Any?>>()
        val ui = AltScreenUi { sent += it; true }
        ui.setEventReady(true)
        ui.setStreamReady(true)
        assertFalse("Not enabled by SETUP", ui.setEnabled(false))
        assertFalse("No content chosen", ui.setEnabled(true))
        assertEquals(0, sent.size)
    }

    @Test
    fun setupEnablesTheClusterOnlyWithBothFeaturesProposed() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 1080),
        )
        val cluster = base.copy(cluster = AirPlayDisplayConfig(1600, 600, primaryInputDevice = 0, initialUrl = map))
        val proposed = listOf("altScreen", "uiContext", "viewAreas")
        assertTrue(setupEnabledFeatures(cluster, proposed).containsAll(listOf("altScreen", "uiContext")))
        assertFalse("altScreen" in setupEnabledFeatures(cluster, listOf("altScreen")))
        assertFalse("altScreen" in setupEnabledFeatures(base, proposed))
    }

    @Test
    fun theClusterIsDeclaredWithItsContentAndNoInput() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 1080),
                cluster = AirPlayDisplayConfig(1600, 600, primaryInputDevice = 0, initialUrl = map),
            ),
        )
        val cluster = (info["displays"] as List<*>).map { it as Map<*, *> }.single { it["type"] == 111 }
        assertEquals(AirPlayInfoPlist.ALT_UUID, cluster["uuid"])
        assertEquals(1600, cluster["widthPixels"])
        assertEquals(0, cluster["primaryInputDevice"])
        assertEquals(map, cluster["initialURL"])
        assertEquals(listOf(map), info["uiContextNowOnDisplayURLs"])
        assertEquals(emptyList<String>(), info["uiContextLastOnDisplayURLs"])
    }

    @Test
    fun theClusterKeyFrameNamesItsDisplay() {
        assertEquals(mapOf("type" to "forceKeyFrame", "params" to mapOf("uuid" to AirPlayInfoPlist.ALT_UUID)),
            screenKeyFrameCommand(AirPlayInfoPlist.ALT_UUID))
        assertEquals(emptyMap<String, Any?>(), mainScreenKeyFrameCommand()["params"])
    }
}
