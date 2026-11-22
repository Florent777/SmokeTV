package io.github.florent777.smoketv

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

class AppEntry(val pkg: String, val label: String, val component: ComponentName, val tv: Boolean)

/** App discovery, launching and the icon cache. Safe to call from a background thread. */
class AppRepository(private val ctx: Context) {
    private val pm: PackageManager = ctx.packageManager

    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    private val cache = object : LruCache<String, TileImage>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: TileImage) = value.bitmap.allocationByteCount / 1024
    }

    /** Leanback (TV) entries first, then phone launcher entries; one entry per package. */
    fun reload(): List<AppEntry> {
        val byPkg = LinkedHashMap<String, AppEntry>()
        fun collect(category: String, tv: Boolean) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == ctx.packageName || pkg in byPkg || pkg in NEVER_SHOW) continue
                if (isPlaceholder(ai.applicationInfo)) continue
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                byPkg[pkg] = AppEntry(pkg, label, ComponentName(pkg, ai.name), tv)
            }
        }
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)
        apps = byPkg.values.sortedBy { it.label.lowercase() }
        return apps
    }

    fun find(pkg: String): AppEntry? = apps.firstOrNull { it.pkg == pkg }

    /**
     * Fire OS ships some apps only as tiny stubs (e.g. Silk, miniTV: /system/priv-app/<pkg>-stub,
     * 30-70 KB) that download the real app when opened. They are not installed, so they are hidden;
     * once the real app is installed it replaces the stub and shows up.
     */
    private fun isPlaceholder(ai: android.content.pm.ApplicationInfo?): Boolean {
        val dir = ai?.sourceDir ?: return false
        if (dir.contains("-stub")) return true
        val system = ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        val updated = ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return system && !updated && java.io.File(dir).length() in 1 until 256 * 1024
    }

    fun defaultPins(hidden: Set<String>): List<String> {
        val present = apps.mapTo(HashSet()) { it.pkg }
        val picks = PREFERRED.filter { it in present }.toMutableList()
        // Fill up with the user's own apps only, never Amazon's extras.
        for (a in apps) {
            if (picks.size >= 6) break
            if (a.pkg !in picks && a.pkg !in hidden && !a.pkg.startsWith("com.amazon.")) picks += a.pkg
        }
        return picks
    }

    fun launch(app: AppEntry): Boolean {
        val primary = Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.tv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
        val candidates = listOfNotNull(primary, pm.getLeanbackLaunchIntentForPackage(app.pkg), pm.getLaunchIntentForPackage(app.pkg))
        for (i in candidates) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    fun evict(pkg: String) {
        cache.snapshot().keys.filter { it.startsWith("$pkg/") }.forEach { cache.remove(it) }
    }

    fun cached(app: AppEntry, mono: Boolean, w: Int, h: Int): TileImage? = cache.get(key(app, mono, w, h))

    /** Renders (or returns cached) artwork for a w x h px card. */
    fun icon(app: AppEntry, mono: Boolean, w: Int, h: Int): TileImage? {
        val k = key(app, mono, w, h)
        cache.get(k)?.let { return it }
        val img = try { render(app, mono, w, h) } catch (_: Exception) { null } ?: return null
        cache.put(k, img)
        return img
    }

    private fun key(app: AppEntry, mono: Boolean, w: Int, h: Int) = "${app.pkg}/$mono/${w}x$h"

    private fun render(app: AppEntry, mono: Boolean, w: Int, h: Int): TileImage? {
        CUSTOM_ART[app.pkg]?.let { return customArt(it, mono, w, h) }
        val banner = banner(app)
        if (banner != null) {
            val b = draw(banner, w, h)
            if (!mono) return TileImage(b, fill = true, silhouette = false)
            // A TV banner carries the whole wordmark: use it when the logo can be lifted off its plate.
            logo(b)?.let { return it }
        }
        val icon = hiResIcon(app)
        // Some Fire TV apps (Amazon Kids, Amazon Photos) ship a 16:9 TV picture as their icon:
        // that is a banner already, so it is used as one instead of being squeezed into a square.
        if (icon.intrinsicHeight > 0 && icon.intrinsicWidth.toFloat() / icon.intrinsicHeight > 1.3f) {
            val b = draw(icon, w, h)
            if (!mono) return TileImage(b, fill = true, silhouette = false)
            logo(b)?.let { return it }
        }
        // Rendered large: the logo inside an icon is small, and it gets lifted out and enlarged.
        val s = maxOf((h * 0.8f).toInt(), 384)
        if (mono && icon is AdaptiveIconDrawable) {
            // The foreground layer alone is often the logo on transparency.
            icon.foreground?.let { fg ->
                val f = draw(fg, s, s)
                if (opaqueShare(f) in 0.01f..0.45f) return TileImage(trim(f), fill = false, silhouette = true)
            }
        }
        val whole = drawWhole(icon, s)
        if (!mono) return TileImage(bannerFrom(app.label, whole, w, h), fill = true, silhouette = false)
        return logo(whole) ?: TileImage(whole, fill = false, silhouette = false)
    }

    /**
     * Draws an icon uncropped. Android shows only the middle 72 of an adaptive icon's 108 units;
     * many Fire TV wordmarks run past that (Fire OS shows banners, not icons), so the layers are
     * drawn over the full square instead.
     */
    private fun drawWhole(d: Drawable, s: Int): Bitmap {
        if (d !is AdaptiveIconDrawable) return draw(d, s, s)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = s * 0.18f
        c.clipPath(Path().apply { addRoundRect(0f, 0f, s.toFloat(), s.toFloat(), r, r, Path.Direction.CW) })
        d.background?.let { it.setBounds(0, 0, s, s); it.draw(c) }
        d.foreground?.let { it.setBounds(0, 0, s, s); it.draw(c) }
        return b
    }

    private fun opaqueShare(b: Bitmap): Float {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { (it ushr 24) > 200 }.toFloat() / px.size
    }

    /**
     * One-colour logo mask from artwork, or null. A logo on transparency is used as is; a logo on
     * a plain plate (solid or gently graded) has the plate colour, measured along its edge, keyed out.
     */
    private fun logo(src: Bitmap): TileImage? {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val opaque = px.count { (it ushr 24) > 200 }
        if (opaque < w * h * 0.45) return if (opaque > w * h * 0.01) TileImage(trim(src, px), fill = false, silhouette = true) else null
        val keyed = keyOut(px, w, h) ?: return null
        val out = Bitmap.createBitmap(keyed, w, h, Bitmap.Config.ARGB_8888)
        return TileImage(trim(out, keyed), fill = false, silhouette = true)
    }

    /** Colour of the plate behind a logo, as four corner colours (a solid plate has four equal ones). */
    private class Plate(val tl: Int, val tr: Int, val bl: Int, val br: Int) {
        fun at(fx: Float, fy: Float): Int {
            fun ch(shift: Int): Float {
                val top = ((tl shr shift) and 0xFF) * (1 - fx) + ((tr shr shift) and 0xFF) * fx
                val bottom = ((bl shr shift) and 0xFF) * (1 - fx) + ((br shr shift) and 0xFF) * fx
                return top * (1 - fy) + bottom * fy
            }
            return (0xFF shl 24) or (ch(16).toInt() shl 16) or (ch(8).toInt() shl 8) or ch(0).toInt()
        }
    }

    private fun dist(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat()) / 441.7f
    }

    private fun lumOf(c: Int) = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f

    /**
     * Finds a plain or smoothly graded plate: corner colours, checked against samples taken all
     * along the plate's edge. Null for busy artwork (photos, patterns).
     */
    private fun plate(px: IntArray, w: Int, h: Int): Plate? {
        fun opaqueAt(x: Int, y: Int) = (px[y * w + x] ushr 24) > 200
        fun patch(cx: Int, cy: Int): Int? {
            val r = maxOf(1, minOf(w, h) / 40)
            var n = 0; var sr = 0; var sg = 0; var sb = 0
            for (y in (cy - r)..(cy + r)) for (x in (cx - r)..(cx + r)) {
                if (x !in 0 until w || y !in 0 until h || !opaqueAt(x, y)) continue
                val c = px[y * w + x]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
            }
            return if (n == 0) null else (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
        }
        // Corners sit a little inside, clear of rounded corners.
        val ix = (w * 0.1f).toInt()
        val iy = (h * 0.1f).toInt()
        val p = Plate(
            patch(ix, iy) ?: return null, patch(w - 1 - ix, iy) ?: return null,
            patch(ix, h - 1 - iy) ?: return null, patch(w - 1 - ix, h - 1 - iy) ?: return null,
        )
        val inset = maxOf(2, w / 40)
        var n = 0
        var err = 0f
        for (y in 0 until h step 2) {
            val l = (0 until w).firstOrNull { opaqueAt(it, y) } ?: continue
            val r = (w - 1 downTo 0).first { opaqueAt(it, y) }
            for (x in intArrayOf(minOf(l + inset, w - 1), maxOf(r - inset, 0))) {
                err += dist(px[y * w + x], p.at(x / (w - 1f), y / (h - 1f))); n++
            }
        }
        for (x in 0 until w step 2) {
            val t = (0 until h).firstOrNull { opaqueAt(x, it) } ?: continue
            val b = (h - 1 downTo 0).first { opaqueAt(x, it) }
            for (y in intArrayOf(minOf(t + inset, h - 1), maxOf(b - inset, 0))) {
                err += dist(px[y * w + x], p.at(x / (w - 1f), y / (h - 1f))); n++
            }
        }
        return if (n >= 16 && err / n < 0.07f) p else null
    }

    /**
     * The logo on a plate: everything that differs from the plate colour at its spot. White
     * alpha mask, or the logo's own colours with [keepColour]. Null if there is no plain plate.
     */
    private fun keyOut(px: IntArray, w: Int, h: Int, keepColour: Boolean = false): IntArray? {
        val p = plate(px, w, h) ?: return null
        // A logo is lighter or darker than its plate. Outlines and shadows on the other side
        // (Twitch's dark outline around white letters) would merge the letters into a blob.
        var lighter = 0
        var darker = 0
        for (i in px.indices) {
            val c = px[i]
            if ((c ushr 24) < 128) continue
            val bg = p.at((i % w) / (w - 1f), (i / w) / (h - 1f))
            if (dist(c, bg) < 0.3f) continue
            val d = lumOf(c) - lumOf(bg)
            if (d > 0.05f) lighter++ else if (d < -0.05f) darker++
        }
        val keepLighter = lighter >= darker
        val out = IntArray(w * h)
        var kept = 0
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a < 8) continue
            val bg = p.at((i % w) / (w - 1f), (i / w) / (h - 1f))
            val d = lumOf(c) - lumOf(bg)
            if ((keepLighter && d < -0.05f) || (!keepLighter && d > 0.05f)) continue
            val k = ((dist(c, bg) - 0.14f) / 0.16f).coerceIn(0f, 1f)
            val alpha = (k * a).toInt()
            if (alpha > 128) kept++
            out[i] = (alpha shl 24) or (if (keepColour) c and 0xFFFFFF else 0xFFFFFF)
        }
        return if (kept < w * h * 0.015 || kept > w * h * 0.75) null else out
    }

    /**
     * A TV-banner-style tile for an app that has only a square icon: the icon's plate colours
     * fill the whole tile and its logo sits large in the middle, in its own colours. A
     * free-standing mark goes on a deep tint of its own colour; busy artwork gets the app name.
     */
    private fun bannerFrom(label: String, icon: Bitmap, w: Int, h: Int): Bitmap {
        val s = icon.width
        val px = IntArray(s * icon.height)
        icon.getPixels(px, 0, s, 0, 0, s, icon.height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val opaque = px.count { (it ushr 24) > 200 }
        if (opaque < px.size * 0.45) {
            val avg = average(px)
            c.drawPaint(Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), shade(avg, 0.5f), shade(avg, 0.72f), Shader.TileMode.CLAMP)
            })
            drawCentered(c, trim(icon, px), w, h)
            return out
        }
        val p = plate(px, s, icon.height)
        val logo = if (p != null) keyOut(px, s, icon.height, keepColour = true) else null
        if (p != null && logo != null) {
            // Four corner colours stretched over the tile = the icon's gradient, banner-sized.
            val corners = Bitmap.createBitmap(intArrayOf(p.tl, p.tr, p.bl, p.br), 2, 2, Bitmap.Config.ARGB_8888)
            c.drawBitmap(corners, null, RectF(-w * 0.5f, -h * 0.5f, w * 1.5f, h * 1.5f), Paint(Paint.FILTER_BITMAP_FLAG))
            val mark = Bitmap.createBitmap(logo, s, icon.height, Bitmap.Config.ARGB_8888)
            drawCentered(c, trim(mark, logo), w, h)
            return out
        }
        // Busy artwork: the app's name in a matching colour on its average colour.
        val avg = average(px)
        c.drawColor(avg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (lumOf(avg) > 0.6f) 0xFF111111.toInt() else Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            textSize = h * 0.2f
        }
        while (text.measureText(label) > w * 0.84f && text.textSize > h * 0.09f) text.textSize *= 0.92f
        val fm = text.fontMetrics
        c.drawText(label, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, text)
        return out
    }

    /** Fits a logo into the middle 64% x 50% of the tile, keeping its shape. */
    private fun drawCentered(c: Canvas, b: Bitmap, w: Int, h: Int) {
        val scale = minOf(w * 0.64f / b.width, h * 0.5f / b.height)
        val dw = b.width * scale
        val dh = b.height * scale
        c.drawBitmap(b, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun average(px: IntArray): Int {
        var n = 0L; var r = 0L; var g = 0L; var b = 0L
        for (c in px) if ((c ushr 24) > 128) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++ }
        if (n == 0L) return 0xFF333333.toInt()
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Darkens a colour by [t] (0..1). */
    private fun shade(c: Int, t: Float): Int = Color.rgb(
        (Color.red(c) * (1 - t)).toInt(), (Color.green(c) * (1 - t)).toInt(), (Color.blue(c) * (1 - t)).toInt(),
    )

    private fun trim(b: Bitmap): Bitmap {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return trim(b, px)
    }

    /** Crops to the visible pixels (plus a hair of margin), so logos fill their tile evenly. */
    private fun trim(b: Bitmap, px: IntArray): Bitmap {
        val w = b.width
        val h = b.height
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 24) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < x0 || y1 < y0) return b
        x0 = maxOf(0, x0 - 2); y0 = maxOf(0, y0 - 2); x1 = minOf(w - 1, x1 + 2); y1 = minOf(h - 1, y1 + 2)
        return Bitmap.createBitmap(b, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /** SmokeTV's own artwork for apps whose icon has no usable logo. */
    private class Art(val res: Int, val from: Int, val to: Int)

    /** Custom art: a white mark on a diagonal gradient, or just the mark in monochrome mode. */
    private fun customArt(art: Art, mono: Boolean, w: Int, h: Int): TileImage? {
        val mark = ctx.getDrawable(art.res) ?: return null
        val s = (h * 0.62f).toInt()
        if (mono) return TileImage(trim(draw(mark, s, s)), fill = false, silhouette = true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), art.from, art.to, Shader.TileMode.CLAMP)
        })
        mark.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2)
        mark.draw(c)
        return TileImage(out, fill = true, silhouette = false)
    }

    /**
     * The icon at the highest density the app ships (xxxhdpi where present). The default
     * lookup gives the TV's own density, about 96 px here, too small to lift a logo out of.
     */
    private fun hiResIcon(app: AppEntry): Drawable {
        try {
            val ai = pm.getActivityInfo(app.component, 0)
            val id = ai.iconResource
            if (id != 0) {
                pm.getResourcesForApplication(ai.applicationInfo)
                    .getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)?.let { return it }
            }
        } catch (_: Exception) {
        }
        return try { pm.getActivityIcon(app.component) } catch (_: PackageManager.NameNotFoundException) { pm.defaultActivityIcon }
    }

    private fun banner(app: AppEntry): Drawable? = try {
        pm.getActivityBanner(app.component) ?: pm.getApplicationBanner(app.pkg)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun draw(d: Drawable, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(b))
        return b
    }

    companion object {
        /** Never launchable in a useful way. */
        private val NEVER_SHOW = setOf("com.amazon.tv.launcher.ui", "com.android.tv.settings.overlay")

        /** Fire OS system entries hidden from the drawer by default (unhide via "Show hidden apps"). */
        val DEFAULT_HIDDEN = setOf(
            "com.amazon.tv.launcher", "com.amazon.tv.settings.v2", "com.android.settings",
            "com.amazon.tv.oobe", "com.amazon.tv.legal.notices", "com.amazon.tv.csapp",
            "com.amazon.tv.notificationcenter", "com.amazon.tv.parentalcontrols",
            "com.amazon.device.software.ota", "com.amazon.tv.forcedotaupdater.v2",
            "com.amazon.ftv.screensaver", "com.amazon.tv.devicecontrolsettings",
            "com.amazon.tmm.tutorial", "com.amazon.tv.ime", "com.amazon.vizzini",
            "com.amazon.cardinal", "com.amazon.tv.alexanotifications", "com.amazon.device.sync",
            "com.amazon.tv.livetv", "com.amazon.ssm", "com.amazon.tv.fw.metrics",
            "com.android.documentsui", "com.amazon.tv.intentsupport", "com.amazon.dp.logger",
            "com.amazon.aca", "com.amazon.hedwig", "com.amazon.alexa.externalmediaplayer.fireos",
            "com.amazon.tv.smarthomevideo", "com.amazon.tv.support", "com.amazon.tv.easyskill",
            "com.amazon.device.messaging", "com.amazon.tv.ottssocompanionapp",
            "com.amazon.firetv.troubleshooting", "com.amazon.ftv.profilepicker", "com.amazon.vizzini.ftvcds",
            "com.android.providers.downloads.ui",
        )

        /** Apps drawn with SmokeTV's own art. Amazon Game Center ships only Android's default robot icon. */
        private val CUSTOM_ART = mapOf(
            "com.amazon.gamehub" to Art(R.drawable.art_controller, 0xFF2FA593.toInt(), 0xFF1B6F62.toInt()),
        )

        /** Pinned on first run when installed. Prime Video is com.amazon.firebat on Fire TV sticks. */
        private val PREFERRED = listOf(
            "com.amazon.firetv.youtube", "com.google.android.youtube.tv", "tv.twitch.android.viewer",
            "com.netflix.ninja", "com.amazon.firebat", "com.amazon.avod", "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android", "com.disney.disneyplus", "com.amazon.cloud9",
        )
    }
}
