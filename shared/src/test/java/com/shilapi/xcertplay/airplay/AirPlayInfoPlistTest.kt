package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayInfoPlistTest {
    @Test
    fun defaultDisplayIncludesFullViewAndSafeAreas() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        assertEquals(0, display["initialViewArea"])
        assertEquals(1280, view["widthPixels"])
        assertEquals(720, view["heightPixels"])
        assertEquals(0, view["originXPixels"])
        assertEquals(0, view["originYPixels"])
        assertEquals(1, display["primaryInputDevice"])
        assertNotNull(safe)
        assertEquals(1280, safe["widthPixels"])
        assertEquals(720, safe["heightPixels"])
        assertEquals(true, safe["drawUIOutsideSafeArea"])
    }

    @Test
    fun hevcCapabilityIsAdvertisedOnlyWhenEnabled() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )

        assertFalse(AirPlayInfoPlist.build(base).containsKey("hevcInfo"))
        assertTrue(AirPlayInfoPlist.build(base.copy(hevc = true)).containsKey("hevcInfo"))
    }

    @Test
    fun drivingSideAndDisplayValuesAreAdvertised() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 1280,
                    heightPixels = 720,
                    fps = 37,
                    widthPhysicalMm = 125,
                ),
                rightHandDrive = true,
                manufacturer = "Example",
                model = "HeadUnit",
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        assertEquals(true, info["rightHandDrive"])
        assertEquals("Example", info["manufacturer"])
        assertEquals("HeadUnit", info["model"])
        assertEquals(35, display["maxFPS"])
        assertEquals(125, display["widthPhysical"])
        assertEquals(70, display["heightPhysical"])
    }

    @Test
    fun squareOemIconIsAdvertisedWithItsOriginalBytes() {
        val iconBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                icons = listOf(AirPlayIcon(1, 1, iconBytes)),
                oemLabel = "xcertplay",
            ),
        )

        val icon = (info["oemIcons"] as List<*>).single() as Map<*, *>
        assertEquals(true, info["oemIconVisible"])
        assertEquals("xcertplay", info["oemIconLabel"])
        assertEquals(1, icon["widthPixels"])
        assertEquals(1, icon["heightPixels"])
        assertTrue((icon["imageData"] as ByteArray).contentEquals(iconBytes))
    }

    @Test
    fun safeAreaInsetsReachTheAirPlayViewArea() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 960,
                    heightPixels = 540,
                    safeArea = AirPlayInsets(top = 10, bottom = 15, left = 20, right = 25),
                    safeAreaDrawOutside = false,
                ),
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        assertEquals(915, safe["widthPixels"])
        assertEquals(515, safe["heightPixels"])
        assertEquals(20, safe["originXPixels"])
        assertEquals(10, safe["originYPixels"])
        assertEquals(false, safe["drawUIOutsideSafeArea"])
    }

    @Test
    fun microphoneInputsAreAdvertisedOnlyWhenEnabled() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )

        fun telephony(info: Map<String, Any?>): Map<*, *> =
            (info["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["audioType"] == "telephony" }

        fun defaultAudio(info: Map<String, Any?>): Map<*, *> =
            (info["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["type"] == 100 && it["audioType"] == "default" }

        assertFalse(telephony(AirPlayInfoPlist.build(base)).containsKey("audioInputFormats"))
        assertTrue(
            telephony(AirPlayInfoPlist.build(base.copy(microphone = true)))
                .containsKey("audioInputFormats"),
        )
        assertEquals(
            0x4154,
            defaultAudio(AirPlayInfoPlist.build(base.copy(microphone = true)))["audioInputFormats"],
        )
        assertEquals(
            0x4154,
            telephony(AirPlayInfoPlist.build(base.copy(microphone = true)))["audioInputFormats"],
        )
        assertEquals(
            0x70004154,
            defaultAudio(AirPlayInfoPlist.build(base.copy(microphone = true, wirelessAudio = true)))["audioInputFormats"],
        )
    }

    @Test
    fun wiredMainAudioAdvertisesOnlyPcmAndWirelessKeepsOpus() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )
        fun defaultOutput(config: AirPlayConfig): Int =
            (AirPlayInfoPlist.build(config)["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["type"] == 100 && it["audioType"] == "default" }["audioOutputFormats"] as Int

        assertEquals(0xc3fc, defaultOutput(base))
        assertEquals(0x7000c3fc, defaultOutput(base.copy(wirelessAudio = true)))
    }

    @Test
    fun mainAltAndHighAudioStreamsAreDeclared() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            ),
        )

        val types = (info["audioFormats"] as List<*>)
            .map { (it as Map<*, *>)["type"] }
            .toSet()
        assertEquals(setOf(100, 101, 102), types)
    }

    @Test
    fun aSquareCanvasDeclaresBothOrientationsAsSwitchableViewAreas() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 1920,
                    heightPixels = 1920,
                    viewAreas = listOf(AirPlayViewArea(0, 0, 1920, 1080), AirPlayViewArea(0, 0, 1080, 1920)),
                    initialViewArea = 1,
                ),
            ),
        )
        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val areas = display["viewAreas"] as List<*>
        assertEquals(2, areas.size)
        assertEquals(1, display["initialViewArea"])
        assertEquals(true, display["viewAreaTransitionControl"])
        val portrait = areas[1] as Map<*, *>
        assertEquals(1080, portrait["widthPixels"])
        assertEquals(1920, portrait["heightPixels"])
        assertEquals(0, portrait["originXPixels"])
        val safe = portrait["safeArea"] as Map<*, *>
        assertEquals(1080, safe["widthPixels"])
        assertEquals(1920, safe["heightPixels"])
        assertEquals(1920, display["widthPixels"])
        assertEquals(1920, display["heightPixels"])
    }

    @Test
    fun aSingleAreaDisplayDoesNotOfferViewAreaTransitions() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 1080),
            ),
        )
        val display = (info["displays"] as List<*>).single() as Map<*, *>
        assertFalse(display.containsKey("viewAreaTransitionControl"))
    }

    @Test
    fun updateViewAreaNamesTheAreaItsAnimationAndTheOtherAreas() {
        val command = AirPlayInfoPlist.viewAreaCommand(index = 1, areaCount = 2)
        assertEquals("updateViewArea", command["type"])
        val params = command["params"] as Map<*, *>
        assertEquals(AirPlayInfoPlist.MAIN_UUID, params["uuid"])
        assertEquals(1, params["viewAreaIndex"])
        assertEquals(300, params["animationDurationMillis"])
        assertEquals(listOf(0), params["adjacentViewAreas"])
    }

    @Test
    fun theDockEdgeIsDeclaredOnlyWhenChosen() {
        fun area(display: AirPlayDisplayConfig): Map<*, *> {
            val info = AirPlayInfoPlist.build(
                AirPlayConfig(
                    deviceName = "test",
                    deviceId = "02:00:00:00:00:02",
                    btMac = "02:00:00:00:00:02",
                    sourceVersion = "366.0",
                    main = display,
                ),
            )
            val entry = (info["displays"] as List<*>).single() as Map<*, *>
            return (entry["viewAreas"] as List<*>).first() as Map<*, *>
        }
        val plain = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 1080)
        assertFalse(area(plain).containsKey("viewAreaStatusBarEdge"))
        assertEquals(
            AirPlayInfoPlist.DOCK_EDGE_BOTTOM,
            area(plain.copy(statusBarEdge = AirPlayInfoPlist.DOCK_EDGE_BOTTOM))["viewAreaStatusBarEdge"],
        )
    }

    @Test
    fun eachSwitchableAreaCarriesItsOwnDockEdge() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 1920,
                    heightPixels = 1920,
                    viewAreas = listOf(
                        AirPlayViewArea(0, 0, 1920, 1080, AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE),
                        AirPlayViewArea(0, 0, 1080, 1920, AirPlayInfoPlist.DOCK_EDGE_BOTTOM),
                    ),
                ),
            ),
        )
        val areas = ((info["displays"] as List<*>).single() as Map<*, *>)["viewAreas"] as List<*>
        assertEquals(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, (areas[0] as Map<*, *>)["viewAreaStatusBarEdge"])
        assertEquals(AirPlayInfoPlist.DOCK_EDGE_BOTTOM, (areas[1] as Map<*, *>)["viewAreaStatusBarEdge"])
    }
}
