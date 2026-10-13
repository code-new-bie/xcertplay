package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_BOTTOM
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayDockPositionTest {
    @Test
    fun automaticLeavesASingleAreaToTheIphone() {
        assertNull(CarPlayDockPosition.AUTOMATIC.singleAreaEdge)
        assertEquals(DOCK_EDGE_DRIVER_SIDE, CarPlayDockPosition.DRIVER_SIDE.singleAreaEdge)
        assertEquals(DOCK_EDGE_BOTTOM, CarPlayDockPosition.BOTTOM.singleAreaEdge)
    }

    @Test
    fun automaticOnATurningScreenKeepsTheSingleAreaLook() {
        assertEquals(DOCK_EDGE_DRIVER_SIDE, CarPlayDockPosition.AUTOMATIC.squareAreaEdge(portrait = false))
        assertEquals(DOCK_EDGE_BOTTOM, CarPlayDockPosition.AUTOMATIC.squareAreaEdge(portrait = true))
    }

    @Test
    fun aChosenEdgeAppliesToBothOrientations() {
        listOf(false, true).forEach { portrait ->
            assertEquals(DOCK_EDGE_DRIVER_SIDE, CarPlayDockPosition.DRIVER_SIDE.squareAreaEdge(portrait))
            assertEquals(DOCK_EDGE_BOTTOM, CarPlayDockPosition.BOTTOM.squareAreaEdge(portrait))
        }
    }

    @Test
    fun unknownOrMissingSavedValuesAreAutomatic() {
        assertEquals(CarPlayDockPosition.AUTOMATIC, CarPlayDockPosition.of(null))
        assertEquals(CarPlayDockPosition.AUTOMATIC, CarPlayDockPosition.of("left"))
        CarPlayDockPosition.values().forEach { assertEquals(it, CarPlayDockPosition.of(it.id)) }
    }
}
