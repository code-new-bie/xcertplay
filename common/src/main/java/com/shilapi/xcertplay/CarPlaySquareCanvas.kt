package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayViewArea

/**
 * A square CarPlay canvas that holds the screen's two orientations as view areas anchored at its
 * top-left corner: a landscape area across the top and a portrait area down the left. The stream's
 * size is fixed for a session, so a turning screen (BYD's rotating centre screen) otherwise means a
 * new connection; with the square it moves CarPlay to the other area with updateViewArea instead.
 * The square keeps the plain canvas's pixel density, so CarPlay's scale is the same both ways.
 */
internal data class CarPlaySquareCanvas(val side: Int, val areas: List<AirPlayViewArea>, val initialArea: Int) {
    /** The area for a window of [width]×[height]. */
    fun areaFor(width: Int, height: Int): Int = if (width >= height) LANDSCAPE else PORTRAIT

    fun describe(index: Int): String = areas[index].let { "${it.width}x${it.height}" }

    companion object {
        const val LANDSCAPE = 0
        const val PORTRAIT = 1
        /** Larger squares are too heavy to encode and decode at a steady frame rate. */
        const val MAX_SIDE = 1920

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
    }
}
