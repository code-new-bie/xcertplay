package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayViewArea
import kotlin.math.abs
import kotlin.math.ln

/**
 * A square CarPlay canvas that holds the screen's two orientations as view areas anchored at its
 * top-left corner: a landscape area across the top and a portrait area down the left. The stream's
 * size is fixed for a session, so a turning screen (BYD's rotating centre screen) otherwise means a
 * new connection; with the square it moves CarPlay to the other area with updateViewArea instead.
 * The square keeps the plain canvas's pixel density, so CarPlay's scale is the same both ways.
 *
 * With split-screen areas ([withSplitAreas]) it also holds an area the size of the app's window in
 * the head unit's split screen, per screen orientation, so CarPlay fills that window too.
 */
internal data class CarPlaySquareCanvas(
    val side: Int,
    val areas: List<AirPlayViewArea>,
    val initialArea: Int,
    val splitLandscape: Int? = null,
    val splitPortrait: Int? = null,
) {
    /**
     * The area for a window of [width]×[height]: its shape picks the orientation. In split screen
     * with split areas declared, the screen's orientation picks it ([screenPortrait]; a split window
     * can have either shape): its split area when that has about the window's shape, else its whole
     * area.
     */
    fun areaFor(width: Int, height: Int, splitScreen: Boolean = false, screenPortrait: Boolean = height > width): Int {
        if (splitScreen && (splitLandscape != null || splitPortrait != null)) {
            val split = if (screenPortrait) splitPortrait else splitLandscape
            if (split != null && closeShape(areas[split], width, height)) return split
            return if (screenPortrait) PORTRAIT else LANDSCAPE
        }
        return if (width >= height) LANDSCAPE else PORTRAIT
    }

    fun isSplit(index: Int): Boolean = index == splitLandscape || index == splitPortrait

    /** Whether area [index] is for a portrait screen. */
    fun isPortrait(index: Int): Boolean = index == PORTRAIT || index == splitPortrait

    fun describe(index: Int): String = areas[index].let { "${it.width}x${it.height}" }

    /**
     * Adds a split-screen area of [landscape] and of [portrait] canvas pixels (either may be null),
     * each even and no larger than its screen's whole area.
     */
    fun withSplitAreas(landscape: Pair<Int, Int>?, portrait: Pair<Int, Int>?): CarPlaySquareCanvas {
        val next = areas.toMutableList()
        fun add(size: Pair<Int, Int>?, whole: AirPlayViewArea): Int? {
            size ?: return null
            val width = size.first.coerceIn(2, whole.width) and 1.inv()
            val height = size.second.coerceIn(2, whole.height) and 1.inv()
            next += AirPlayViewArea(0, 0, width, height)
            return next.lastIndex
        }
        val splitLandscape = add(landscape, areas[LANDSCAPE])
        val splitPortrait = add(portrait, areas[PORTRAIT])
        return copy(areas = next, splitLandscape = splitLandscape, splitPortrait = splitPortrait)
    }

    companion object {
        const val LANDSCAPE = 0
        const val PORTRAIT = 1
        /** Larger squares are too heavy to encode and decode at a steady frame rate. */
        const val MAX_SIDE = 1920
        /** A split area serves a window whose width-to-height ratio is within this factor of its own. */
        private const val MAX_ASPECT_MISMATCH = 1.25

        /** For a plain canvas of [width]×[height]; null when it is square already or the square would exceed [MAX_SIDE]. */
        fun plan(width: Int, height: Int): CarPlaySquareCanvas? {
            if (width <= 0 || height <= 0 || width == height) return null
            val long = maxOf(width, height)
            val short = minOf(width, height)
            if (long > MAX_SIDE) return null
            return CarPlaySquareCanvas(
                side = long,
                areas = listOf(AirPlayViewArea(0, 0, long, short), AirPlayViewArea(0, 0, short, long)),
                initialArea = if (width >= height) LANDSCAPE else PORTRAIT,
            )
        }

        private fun closeShape(area: AirPlayViewArea, width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) return false
            val ratio = area.width.toDouble() / area.height / (width.toDouble() / height)
            return abs(ln(ratio)) <= ln(MAX_ASPECT_MISMATCH)
        }
    }
}
