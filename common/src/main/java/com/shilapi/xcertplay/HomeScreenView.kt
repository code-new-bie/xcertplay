package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** What the home screen shows while CarPlay video is not on screen. */
internal data class HomeModel(
    val title: String,
    val hint: String,
    val modeLabel: String,
    val steps: List<HomeStep>,
    val failure: HomeFailure?,
    val showUseLocalHotspot: Boolean,
)

internal data class HomeStep(val title: String, val detail: String?, val state: StepState)

internal data class HomeFailure(val advice: String, val reason: String)

/**
 * Home screen shown before CarPlay video arrives: status and connection mode on the left, the five
 * connection steps on the right, failure advice and actions below the steps.
 */
internal class HomeScreenView(
    context: Context,
    onSettings: () -> Unit,
    onBluetoothSettings: () -> Unit,
    onReconnect: () -> Unit,
    onUseLocalHotspot: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(ACCENT)
    }
    private val titleView = text(30f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
    private val hintView = text(16f, SECONDARY).apply { gravity = Gravity.CENTER }
    private val modeView = text(15f, ACCENT).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = rounded(Color.argb(40, 127, 205, 154), 16)
    }
    private val stepsView = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val failureCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = rounded(Color.argb(60, 232, 96, 96), 14)
    }
    private val adviceView = text(17f, Color.WHITE, bold = true)
    private val reasonView = text(13f, SECONDARY)
    private val useLocalHotspotButton = button(context.getString(R.string.home_use_local_hotspot), primary = false)

    init {
        setBackgroundColor(BACKGROUND)
        isClickable = true

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                ImageView(context).apply {
                    setImageDrawable(context.packageManager.getApplicationIcon(context.applicationInfo))
                },
                LinearLayout.LayoutParams(dp(112), dp(112)),
            )
            addView(spinner, LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = dp(20) })
            addView(titleView, wrap().apply { topMargin = dp(18) })
            addView(hintView, wrap().apply { topMargin = dp(10) })
            addView(modeView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(18) })
        }

        failureCard.addView(adviceView, wrap())
        failureCard.addView(reasonView, wrap().apply { topMargin = dp(6) })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(context.getString(R.string.home_bt_settings), primary = false).apply {
                setOnClickListener { onBluetoothSettings() }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(useLocalHotspotButton.apply { setOnClickListener { onUseLocalHotspot() } },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
            addView(button(context.getString(R.string.home_reconnect), primary = true).apply {
                setOnClickListener { onReconnect() }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
        }
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(stepsView, wrap())
            addView(failureCard, wrap().apply { topMargin = dp(18) })
            addView(actions, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(24) })
        }

        val columns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(72), dp(48), dp(48))
            addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(right, LinearLayout.LayoutParams(0, WRAP, 1.15f).apply { marginStart = dp(48) })
        }
        addView(columns, LayoutParams(MATCH, WRAP, Gravity.CENTER))

        val settings = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(20), dp(10))
            background = rounded(Color.argb(40, 255, 255, 255), 24)
            isClickable = true
            contentDescription = context.getString(R.string.open_settings)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_settings)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(text(17f, Color.WHITE).apply { text = context.getString(R.string.home_settings) },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            setOnClickListener { onSettings() }
        }
        addView(settings, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(20), dp(24), 0)
        })
    }

    fun render(model: HomeModel) {
        titleView.text = model.title
        hintView.text = model.hint
        modeView.text = model.modeLabel
        spinner.visibility = if (model.failure == null) View.VISIBLE else View.INVISIBLE
        stepsView.removeAllViews()
        model.steps.forEachIndexed { index, step ->
            stepsView.addView(stepRow(step), wrap().apply { if (index > 0) topMargin = dp(14) })
        }
        failureCard.visibility = if (model.failure != null) View.VISIBLE else View.GONE
        adviceView.text = model.failure?.advice
        reasonView.text = model.failure?.reason
        useLocalHotspotButton.visibility = if (model.showUseLocalHotspot) View.VISIBLE else View.GONE
    }

    private fun stepRow(step: HomeStep): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val (glyph, color) = when (step.state) {
            StepState.DONE -> "✓" to ACCENT
            StepState.ACTIVE -> "●" to ACCENT
            StepState.FAILED -> "✕" to DANGER
            StepState.PENDING -> "" to MUTED
        }
        addView(text(18f, color, bold = true).apply {
            text = glyph
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), color)
            }
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(20f, if (step.state == StepState.PENDING) MUTED else Color.WHITE,
                bold = step.state == StepState.ACTIVE || step.state == StepState.FAILED).apply {
                text = step.title
            }, wrap())
            if (!step.detail.isNullOrBlank()) {
                addView(text(14f, SECONDARY).apply { text = step.detail }, wrap().apply { topMargin = dp(3) })
            }
        }
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) })
    }

    private fun button(label: String, primary: Boolean): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        minHeight = dp(52)
        setPadding(dp(22), 0, dp(22), 0)
        setTextColor(if (primary) BUTTON_TEXT else Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(if (primary) ACCENT else Color.rgb(58, 62, 68))
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false): TextView = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun wrap() = LinearLayout.LayoutParams(MATCH, WRAP)
    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
        val ACCENT = Color.rgb(127, 205, 154)
        val SECONDARY = Color.rgb(170, 180, 190)
        val MUTED = Color.rgb(98, 106, 114)
        val DANGER = Color.rgb(232, 96, 96)
        val BUTTON_TEXT = Color.rgb(8, 17, 11)
    }
}
