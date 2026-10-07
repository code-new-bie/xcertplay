package com.shilapi.xcertplay

/**
 * The window CarPlay normally gets on a BYD head unit, recorded while no camera picture is shown.
 * A session that starts while the reversing or panorama picture shrinks the window is negotiated
 * for this window instead, so closing the picture grows the window back without a reconnect.
 */
internal data class NormalWindow(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
) {
    fun sameLayout(other: NormalWindow): Boolean =
        rotation == other.rotation && hideTopBar == other.hideTopBar && hideBottomBar == other.hideBottomBar

    /** Whether [window] is this window made smaller, with the same rotation and bars. */
    fun shrinksTo(window: NormalWindow): Boolean =
        sameLayout(window) && window.width <= width && window.height <= height &&
            (window.width < width || window.height < height)

    companion object {
        /**
         * What to store after seeing [candidate] with no camera picture shown, or null to keep
         * [current]. Within one layout the window only grows: a shrink seen just before the camera
         * state arrives must not replace the full window.
         */
        fun next(current: NormalWindow?, candidate: NormalWindow): NormalWindow? = when {
            candidate.width <= 0 || candidate.height <= 0 -> null
            current == null || !current.sameLayout(candidate) -> candidate
            candidate != current && candidate.width >= current.width && candidate.height >= current.height -> candidate
            else -> null
        }
    }
}
