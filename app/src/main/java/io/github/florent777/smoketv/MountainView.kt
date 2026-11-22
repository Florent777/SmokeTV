package io.github.florent777.smoketv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.random.Random

/**
 * Generated default wallpaper: layered mountain ridges under a night or dawn sky. The layers
 * pan sideways at different speeds (parallax) as focus moves along a row, see [parallaxTo].
 * Ridges come from fixed seeds, so the scene is the same on every start. Paths are built once
 * per size; a frame only translates them, which is cheap on the Fire TV GPU.
 */
class MountainView(ctx: Context) : View(ctx) {
    private var light = false
    private var progress = 0f
    private var anim: ValueAnimator? = null
    private val ridges = Array(DEPTH.size) { Path() }
    private val fills = Array(DEPTH.size) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val sky = Paint()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var stars = FloatArray(0)

    fun setLight(l: Boolean) {
        light = l
        if (width > 0) shade(width, height)
        invalidate()
    }

    /** 0 = focus at the start of the row, 1 = at its end. Eases there over ~0.9 s. */
    fun parallaxTo(target: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(progress, target.coerceIn(0f, 1f)).apply {
            duration = 900
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        build(w, h)
        shade(w, h)
    }

    private fun build(w: Int, h: Int) {
        for (i in DEPTH.indices) {
            val rnd = Random(SEED + i * 7919)
            val extra = TRAVEL * w * DEPTH[i] + 8f
            val left = -extra
            val right = w + extra
            // Midpoint displacement: rough for far ridges, gentler for near hills.
            val n = 128
            val ys = FloatArray(n + 1)
            ys[0] = rnd.nextFloat()
            ys[n] = rnd.nextFloat()
            var step = n
            var d = 1f
            while (step > 1) {
                val half = step / 2
                var x = half
                while (x < n) {
                    ys[x] = (ys[x - half] + ys[x + half]) / 2f + (rnd.nextFloat() * 2f - 1f) * d
                    x += step
                }
                d *= ROUGH[i]
                step = half
            }
            val lo = ys.min()
            val span = ys.max() - lo + 1e-6f
            val p = ridges[i]
            p.reset()
            p.moveTo(left, h.toFloat())
            for (k in 0..n) {
                p.lineTo(left + (right - left) * k / n, BASE[i] * h - AMP[i] * h * (ys[k] - lo) / span)
            }
            p.lineTo(right, h.toFloat())
            p.close()
        }
        val rnd = Random(SEED)
        val density = resources.displayMetrics.density
        stars = FloatArray(STARS * 3)
        for (s in 0 until STARS) {
            stars[s * 3] = (rnd.nextFloat() * 1.1f - 0.05f) * w
            stars[s * 3 + 1] = rnd.nextFloat() * rnd.nextFloat() * 0.55f * h // denser near the top
            stars[s * 3 + 2] = (0.5f + rnd.nextFloat() * 1.1f) * density
        }
    }

    private fun shade(w: Int, h: Int) {
        val (top, horizon) = if (light) 0xFF86AEDB.toInt() to 0xFFF6E7D6.toInt() else 0xFF04060D.toInt() to 0xFF1D2646.toInt()
        sky.shader = LinearGradient(0f, 0f, 0f, h * 0.72f, top, horizon, Shader.TileMode.CLAMP)
        val haze = if (light) 0xFFF2E9E0.toInt() else 0xFF2B3660.toInt()
        val colors = if (light) DAY else NIGHT
        for (i in DEPTH.indices) {
            val c = colors[i]
            val mist = (1f - DEPTH[i]) * 0.55f // far ridges fade into haze at their foot
            fills[i].shader = LinearGradient(
                0f, (BASE[i] - AMP[i]) * h, 0f, h.toFloat(),
                c, mix(c, haze, mist), Shader.TileMode.CLAMP,
            )
        }
        val cx = w * 0.6f
        val cy = h * if (light) 0.2f else 0.15f
        val r = h * if (light) 0.05f else 0.038f
        disc.color = if (light) 0xFFFFF5E3.toInt() else 0xFFE8ECF6.toInt()
        val glowColor = if (light) 0x99FFE6BF.toInt() else 0x55B9C6EE
        glow.shader = RadialGradient(cx, cy, r * 4.5f, glowColor, glowColor and 0x00FFFFFF, Shader.TileMode.CLAMP)
        starPaint.color = 0xFFDDE3F2.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val shift = -(progress - 0.5f) * 2f * TRAVEL * w
        canvas.drawRect(0f, 0f, w, h, sky)

        canvas.save()
        canvas.translate(shift * 0.05f, 0f)
        if (!light) {
            for (s in 0 until stars.size / 3) {
                starPaint.alpha = 90 + (s * 37) % 140
                canvas.drawCircle(stars[s * 3], stars[s * 3 + 1], stars[s * 3 + 2], starPaint)
            }
        }
        val cx = w * 0.6f
        val cy = h * if (light) 0.2f else 0.15f
        val r = h * if (light) 0.05f else 0.038f
        canvas.drawCircle(cx, cy, r * 4.5f, glow)
        canvas.drawCircle(cx, cy, r, disc)
        canvas.restore()

        for (i in DEPTH.indices) {
            canvas.save()
            canvas.translate(shift * DEPTH[i], 0f)
            canvas.drawPath(ridges[i], fills[i])
            canvas.restore()
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    private companion object {
        const val SEED = 20261122
        const val STARS = 90
        /** Sideways travel of the nearest layer, as a share of the screen width. */
        const val TRAVEL = 0.08f
        /** Parallax speed per layer, far to near. */
        val DEPTH = floatArrayOf(0.2f, 0.38f, 0.58f, 0.8f, 1f)
        /** Ridge foot (fraction of height) and height of each layer. */
        val BASE = floatArrayOf(0.56f, 0.64f, 0.73f, 0.84f, 0.97f)
        val AMP = floatArrayOf(0.26f, 0.22f, 0.18f, 0.15f, 0.13f)
        val ROUGH = floatArrayOf(0.62f, 0.58f, 0.54f, 0.5f, 0.46f)
        val NIGHT = intArrayOf(0xFF2A3456.toInt(), 0xFF1F2846.toInt(), 0xFF161D36.toInt(), 0xFF0E1328.toInt(), 0xFF070A16.toInt())
        val DAY = intArrayOf(0xFFD0D9E7.toInt(), 0xFFB6C4D9.toInt(), 0xFF9BADC9.toInt(), 0xFF8297B9.toInt(), 0xFF6C83A8.toInt())
    }
}
