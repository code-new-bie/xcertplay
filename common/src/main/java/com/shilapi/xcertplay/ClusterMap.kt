package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * CarPlay's instrument-cluster screen on the BYD cluster (optional, off by default). The iPhone
 * draws the map as a second screen stream; it is shown on the head unit's cluster projection
 * display, whose name starts with [ClusterVirtualDisplay.NAME_PREFIX] and whose content the cluster
 * shows by itself. After DiPlay's dashboard map (docs/BYD_NAVIGATION.md).
 */
internal object ClusterMapSettings {
    /** What the iPhone shows on the cluster; it lists the ones it offers in altScreenURLs. */
    enum class Content(val id: String, val url: String) {
        MAP("map", "maps:/car/instrumentcluster/map"),
        CARD("card", "maps:/car/instrumentcluster/instructioncard"),
        MAP_WITH_CARD("map_card", "maps:/car/instrumentcluster"),
    }

    /** The stream as a share of the display: a smaller stream, scaled up, shows the map larger. */
    val SCALES = listOf(100, 83, 67)
    private const val DEFAULT_SCALE = 83
    private const val PREFS = "xcertplay_cluster_map"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("enabled", enabled).apply()

    fun content(context: Context): Content =
        prefs(context).getString("content", null).let { id -> Content.values().firstOrNull { it.id == id } } ?: Content.MAP

    fun setContent(context: Context, content: Content) = prefs(context).edit().putString("content", content.id).apply()

    fun scalePercent(context: Context): Int =
        prefs(context).getInt("scale", DEFAULT_SCALE).takeIf { it in SCALES } ?: DEFAULT_SCALE

    fun setScalePercent(context: Context, percent: Int) {
        if (percent in SCALES) prefs(context).edit().putInt("scale", percent).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The head unit's cluster projection display, found by name; its size is read from the display. */
internal object ClusterVirtualDisplay {
    const val NAME_PREFIX = "fission_"
    /** A 12.3-inch cluster; Apple Maps ignores the physical size there, so an estimate will do. */
    private const val DIAGONAL_MM = 312.0

    data class Target(val id: Int, val name: String, val width: Int, val height: Int)

    fun find(context: Context): Target? = choose(candidates(context))

    /** Every display but the main one, for the log. */
    fun describe(context: Context): String =
        candidates(context).joinToString { "${it.id}:${it.name} ${it.width}x${it.height}" }.ifEmpty { "none" }

    /** The largest display named [NAME_PREFIX]…, if any. */
    internal fun choose(candidates: List<Target>): Target? = candidates
        .filter { it.name.startsWith(NAME_PREFIX, ignoreCase = true) && it.width > 0 && it.height > 0 }
        .maxByOrNull { it.width.toLong() * it.height }

    /** The stream size for [target] at [scalePercent] of it, even on both sides for the encoder. */
    fun streamSize(target: Target, scalePercent: Int): Pair<Int, Int> =
        (target.width * scalePercent / 100 and 1.inv()) to (target.height * scalePercent / 100 and 1.inv())

    fun physicalWidthMm(target: Target): Int =
        (DIAGONAL_MM * target.width / hypot(target.width.toDouble(), target.height.toDouble())).roundToInt()

    private fun candidates(context: Context): List<Target> {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return manager.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }.map { display ->
            val mode = display.mode
            Target(display.displayId, display.name.orEmpty(), mode.physicalWidth, mode.physicalHeight)
        }
    }
}

/**
 * The cluster stream's window on the projection display: black, so a stream that has not started
 * or has ended leaves no frozen map, and neither focusable nor touchable, so the CarPlay screen keeps
 * the keys and touches. [onSurface] gets its Surface, and null when it goes.
 */
internal class ClusterPresentation(
    context: Context,
    display: Display,
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        val video = SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
            })
        }
        setContentView(
            FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
    }
}
