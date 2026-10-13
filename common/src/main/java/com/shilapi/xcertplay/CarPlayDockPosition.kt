package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist

/**
 * Where CarPlay puts its dock, declared as each view area's viewAreaStatusBarEdge. Automatic keeps
 * what a single view area always got: the iPhone decides, which puts the dock on the driver's side
 * of a landscape screen and at the bottom of a portrait one. A turning screen's square canvas has
 * two areas, and with more than one area the iPhone puts the dock at the bottom whatever their
 * shape, so Automatic names those two edges itself there.
 */
internal enum class CarPlayDockPosition(val id: String) {
    AUTOMATIC("automatic"),
    DRIVER_SIDE("driver_side"),
    BOTTOM("bottom");

    /** The edge of a plain canvas's single view area; null lets the iPhone decide, as before. */
    val singleAreaEdge: Int?
        get() = when (this) {
            AUTOMATIC -> null
            DRIVER_SIDE -> AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE
            BOTTOM -> AirPlayInfoPlist.DOCK_EDGE_BOTTOM
        }

    /** The edge of a turning screen's landscape or [portrait] view area. */
    fun squareAreaEdge(portrait: Boolean): Int = when (this) {
        AUTOMATIC -> if (portrait) AirPlayInfoPlist.DOCK_EDGE_BOTTOM else AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE
        DRIVER_SIDE -> AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE
        BOTTOM -> AirPlayInfoPlist.DOCK_EDGE_BOTTOM
    }

    companion object {
        fun of(id: String?): CarPlayDockPosition = values().firstOrNull { it.id == id } ?: AUTOMATIC
    }
}
