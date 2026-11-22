package io.github.florent777.smoketv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

val DECEL = DecelerateInterpolator()

/** All theme colours. [dark] = dark text/icons, i.e. light mode. */
class Palette(val dark: Boolean) {
    val fg = if (dark) 0xFF111111.toInt() else Color.WHITE
    val fgDim = if (dark) 0xB3111111.toInt() else 0xB3FFFFFF.toInt()
    val fill = if (dark) 0x47FFFFFF else 0x1FFFFFFF
    val fillFocus = if (dark) 0x8CFFFFFF.toInt() else 0x3DFFFFFF
    val stroke = if (dark) 0x26000000 else 0x33FFFFFF
    val strokeFocus = if (dark) 0xD9111111.toInt() else 0xF2FFFFFF.toInt()
    /** The scrim always pushes the wallpaper away from the text colour. */
    val scrimRgb = if (dark) 0xFFFFFF else 0x000000

    // Panels: menus, settings, drawer, upload.
    val panel = if (dark) 0xFFF5F6F8.toInt() else 0xFF1A1A1A.toInt()
    val panelStroke = if (dark) 0x1F000000 else 0x26FFFFFF
    val panelText = if (dark) 0xFF111111.toInt() else Color.WHITE
    val panelDim = if (dark) 0x99111111.toInt() else 0xB3FFFFFF.toInt()
    val rowFocus = if (dark) 0x1F000000 else 0x2EFFFFFF
    val menuShade = if (dark) 0x4D000000 else 0x99000000.toInt()
    val drawerBg = if (dark) 0xF0EEF1F5.toInt() else 0xE6000000.toInt()

    companion object {
        val LIGHT_TEXT = Palette(false)
    }
}

fun glass(radius: Float, fill: Int, strokePx: Int, stroke: Int) = GradientDrawable().apply {
    cornerRadius = radius
    setColor(fill)
    if (strokePx > 0) setStroke(strokePx, stroke)
}

/** Paints every pixel in [color], keeping its alpha: crisp one-colour icons. */
fun silhouette(color: Int) = ColorMatrixColorFilter(
    floatArrayOf(
        0f, 0f, 0f, 0f, Color.red(color).toFloat(),
        0f, 0f, 0f, 0f, Color.green(color).toFloat(),
        0f, 0f, 0f, 0f, Color.blue(color).toFloat(),
        0f, 0f, 0f, 1f, 0f,
    )
)

/** High-contrast grayscale, for icons with no transparency (a silhouette would be a blank square). */
val GRAYSCALE: ColorMatrixColorFilter = run {
    val k = 1.25f
    val o = -24f
    val r = 0.299f * k
    val g = 0.587f * k
    val b = 0.114f * k
    ColorMatrixColorFilter(floatArrayOf(r, g, b, 0f, o, r, g, b, 0f, o, r, g, b, 0f, o, 0f, 0f, 0f, 1f, 0f))
}

/** Subtle round icon button for the top bar. */
@SuppressLint("ViewConstructor")
class RoundButton(ctx: Context, icon: Int, desc: String) : ImageView(ctx) {
    var palette = Palette.LIGHT_TEXT
        set(v) { field = v; refresh() }

    init {
        setImageResource(icon)
        contentDescription = desc
        isFocusable = true
        isClickable = true
        scaleType = ScaleType.FIT_CENTER
        val p = dp(10)
        setPadding(p, p, p, p)
        id = generateViewId()
        refresh()
    }

    private fun refresh() {
        val f = isFocused
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (f) palette.fillFocus else 0)
            if (f) setStroke(dp(1), palette.strokeFocus)
        }
        setColorFilter(if (f) palette.fg else palette.fgDim)
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gain, direction, previous)
        refresh()
        val s = if (gain) 1.12f else 1f
        animate().scaleX(s).scaleY(s).setDuration(150).setInterpolator(DECEL).start()
    }
}

/** A focusable line in menus, settings and the drawer header. */
@SuppressLint("ViewConstructor")
class OptionRow(ctx: Context, label: String, value: String?, private val palette: Palette) : LinearLayout(ctx) {
    private val valueView = TextView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        id = generateViewId()
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(TextView(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(palette.panelText)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        valueView.textSize = 15f
        valueView.setTextColor(palette.panelDim)
        addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(16) })
        setValue(value)
        refresh(false)
    }

    fun setValue(v: String?) {
        valueView.text = v ?: ""
        valueView.visibility = if (v.isNullOrEmpty()) GONE else VISIBLE
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gain, direction, previous)
        refresh(gain)
    }

    private fun refresh(focused: Boolean) {
        background = if (focused) glass(dp(10).toFloat(), palette.rowFocus, 0, 0) else null
    }
}
