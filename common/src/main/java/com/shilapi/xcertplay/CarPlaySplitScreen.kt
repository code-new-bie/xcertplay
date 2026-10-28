package com.shilapi.xcertplay

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Optional: in the head unit's split screen CarPlay fills the app's window without reconnecting
 * (after DiPlay be57cfeb, ab070ce2 and f8b8f53a). The square canvas then holds one more view area per
 * screen orientation, the size of the split window ([CarPlaySquareCanvas.withSplitAreas]); entering
 * split screen moves CarPlay there with updateViewArea and leaving it moves CarPlay back.
 *
 * The window is remembered as fractions of the whole screen, which showing or hiding the system bars
 * does not change, so the next connection declares exactly its shape. Before one has been seen it is
 * expected from the screen, its bars and Android's divider.
 */
internal object CarPlaySplitScreen {
    private const val PREFS = "xcertplay_split_screen"

    /** The split window last seen on a [portrait] or landscape screen, as fractions of the whole screen. */
    fun window(context: Context, portrait: Boolean): Pair<Float, Float>? {
        val prefs = prefs(context)
        val width = prefs.getFloat(key(portrait, "width"), 0f)
        val height = prefs.getFloat(key(portrait, "height"), 0f)
        return (width to height).takeIf { valid(it) }
    }

    /** Remembers a split window of [width]×[height] on a [screenWidth]×[screenHeight] screen; true when it changed. */
    fun remember(context: Context, width: Int, height: Int, screenWidth: Int, screenHeight: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val fraction = width.toFloat() / screenWidth to height.toFloat() / screenHeight
        if (!valid(fraction)) return false
        val portrait = screenHeight > screenWidth
        val previous = window(context, portrait)
        if (previous != null && abs(previous.first - fraction.first) < SAME && abs(previous.second - fraction.second) < SAME) {
            return false
        }
        prefs(context).edit()
            .putFloat(key(portrait, "width"), fraction.first)
            .putFloat(key(portrait, "height"), fraction.second)
            .apply()
        return true
    }

    /**
     * The split window to expect before one has been seen, as fractions of the whole screen: the
     * screen halved across its long side less the [divider], with both system bars ([bars], their
     * heights added) shown, as BYD shows them in split screen. On DiPlay's Tang (2560x1440, bars of
     * 112 and 120 px, a 20 px divider) that gave the 1270x1208 and 1440x1154 windows the head unit
     * used. Null when the screen size is unknown.
     */
    fun expectedWindow(portrait: Boolean, screenLong: Int, screenShort: Int, bars: Int, divider: Int): Pair<Float, Float>? {
        if (screenLong <= 0 || screenShort <= 0) return null
        val shown = bars.coerceAtLeast(0)
        val gap = divider.coerceAtLeast(0)
        val window = if (portrait) {
            1f to ((screenLong - shown - gap) / 2).toFloat() / screenLong
        } else {
            ((screenLong - gap) / 2).toFloat() / screenLong to (screenShort - shown).toFloat() / screenShort
        }
        return window.takeIf { valid(it) }
    }

    /**
     * The canvas size of a split window that takes [fraction] of a [screenWidth]×[screenHeight]
     * screen, at [canvasPerWindowPixel] canvas pixels per window pixel.
     */
    fun canvasSize(fraction: Pair<Float, Float>, screenWidth: Int, screenHeight: Int, canvasPerWindowPixel: Double): Pair<Int, Int> =
        (fraction.first * screenWidth * canvasPerWindowPixel).roundToInt() to
            (fraction.second * screenHeight * canvasPerWindowPixel).roundToInt()

    private fun valid(fraction: Pair<Float, Float>) = fraction.first in 0.1f..1f && fraction.second in 0.1f..1f

    private fun key(portrait: Boolean, name: String) = "${if (portrait) "portrait" else "landscape"}_$name"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A window this close (a fraction of the screen) to the remembered one is the same window. */
    private const val SAME = 0.005f
}
