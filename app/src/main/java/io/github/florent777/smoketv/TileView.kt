package io.github.florent777.smoketv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Artwork for a tile: [fill] = TV banner shown edge to edge, [silhouette] = icon has transparency. */
class TileImage(val bitmap: Bitmap, val fill: Boolean, val silhouette: Boolean)

/**
 * Glass app tile: translucent rounded card with a 1dp border. Focus scales it to 1.08,
 * brightens the border and fades the label in underneath.
 */
@SuppressLint("ViewConstructor")
class TileView(
    ctx: Context,
    val app: AppEntry?,
    val cardW: Int,
    val cardH: Int,
    private var labelAlways: Boolean,
) : LinearLayout(ctx) {
    /** Set for a "continue watching" tile. */
    var episode: Episode? = null
        private set
    private val card = FrameLayout(ctx)
    private val image = ImageView(ctx)
    private val label = TextView(ctx)
    private val sub = TextView(ctx)
    private var palette = Palette.LIGHT_TEXT
    private var img: TileImage? = null
    private var mono = false
    private var placeholder = false

    var dimmed = false
        set(v) { field = v; refresh() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        isLongClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        id = generateViewId()
        contentDescription = app?.label

        card.clipToOutline = true
        card.addView(image, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams(cardW, cardH))

        label.text = app?.label
        label.textSize = 13f
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        label.alpha = if (labelAlways) 1f else 0f
        addView(label, LayoutParams(cardW, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        sub.textSize = 12f
        sub.maxLines = 1
        sub.ellipsize = TextUtils.TruncateAt.END
        sub.gravity = Gravity.CENTER
        sub.visibility = GONE
        addView(sub, LayoutParams(cardW, LayoutParams.WRAP_CONTENT))
        refresh()
    }

    /** Shows the show and episode under the card at all times. */
    fun setEpisode(e: Episode) {
        episode = e
        labelAlways = true
        label.text = e.show
        label.alpha = 1f
        sub.text = e.episode.orEmpty()
        sub.visibility = if (e.episode.isNullOrEmpty()) GONE else VISIBLE
        contentDescription = listOfNotNull(app?.label, e.show, e.episode).joinToString(", ")
        e.cover?.let {
            img = TileImage(it, fill = true, silhouette = false)
            mono = false
            image.setImageBitmap(it)
        }
        refresh()
    }

    fun setPlaceholder(res: Int, text: String) {
        placeholder = true
        image.setImageResource(res)
        label.text = text
        contentDescription = text
        refresh()
    }

    fun setImage(i: TileImage?, monochrome: Boolean) {
        if (episode?.cover != null) return // the cover (with Netflix's own branding) stays
        img = i
        mono = monochrome
        image.setImageBitmap(i?.bitmap)
        refresh()
    }

    fun applyPalette(p: Palette) {
        palette = p
        refresh()
    }

    private fun refresh() {
        val f = isFocused
        card.background = glass(
            dp(14).toFloat(),
            if (f) palette.fillFocus else palette.fill,
            dp(if (f) 2 else 1),
            if (f) palette.strokeFocus else palette.stroke,
        )
        val i = img
        // Full-bleed artwork (covers, banners) would hide the border: draw it on top instead.
        card.foreground = if (i != null && i.fill) {
            glass(dp(14).toFloat(), 0, dp(if (f) 2 else 1), if (f) palette.strokeFocus else palette.stroke)
        } else {
            null
        }
        if (i != null && i.fill) {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setPadding(0, 0, 0, 0)
        } else {
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            // Logo box: 60% x 46% of the card for logos, a bit larger for full-colour icons.
            val (ph, pv) = when {
                placeholder -> (cardH * 0.32f) to (cardH * 0.32f)
                mono && i?.silhouette == true -> (cardW * 0.2f) to (cardH * 0.27f)
                else -> (cardH * 0.18f) to (cardH * 0.18f)
            }
            image.setPadding(ph.toInt(), pv.toInt(), ph.toInt(), pv.toInt())
        }
        image.colorFilter = when {
            placeholder -> silhouette(palette.fg)
            i == null || !mono -> null
            i.silhouette -> silhouette(palette.fg)
            else -> GRAYSCALE
        }
        label.setTextColor(palette.fg)
        sub.setTextColor(palette.fgDim)
        card.alpha = if (dimmed) 0.4f else 1f
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gain, direction, previous)
        refresh()
        val s = if (gain) FOCUS_SCALE else 1f
        animate().scaleX(s).scaleY(s).translationZ(if (gain) 8f else 0f)
            .setDuration(160).setInterpolator(DECEL).start()
        if (!labelAlways) label.animate().alpha(if (gain) 1f else 0f).setDuration(160).start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = cardH / 2f
    }

    companion object {
        const val FOCUS_SCALE = 1.08f
    }
}
