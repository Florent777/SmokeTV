package io.github.florent777.smoketv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private enum class Place { PINNED, RECENT, DRAWER }

    private class Overlay(val view: View, val restore: View?, val isMenu: Boolean, val onClose: (() -> Unit)?)

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var mountains: MountainView
    private lateinit var wallpaperView: ImageView
    private lateinit var themeButton: RoundButton
    private lateinit var scrimView: View
    private lateinit var content: LinearLayout
    private lateinit var clock: TextClock
    private lateinit var date: TextClock
    private lateinit var weatherView: TextView
    private lateinit var buttons: List<RoundButton>
    private lateinit var pinnedScroll: HorizontalScrollView
    private lateinit var pinnedRow: LinearLayout
    private lateinit var recentsSection: LinearLayout
    private lateinit var recentsTitle: TextView
    private lateinit var recentScroll: HorizontalScrollView
    private lateinit var recentRow: LinearLayout
    private lateinit var recentsHint: TextView
    private lateinit var bottomSpacer: View

    private var palette = Palette.LIGHT_TEXT
    private val overlays = ArrayList<Overlay>()
    private var drawerGrid: GridLayout? = null
    private var drawerChip: OptionRow? = null
    private var showHiddenInDrawer = false
    private var server: WallpaperServer? = null
    private var appsLoaded = false
    private var rowsDirty = false

    private val wallpaperFile by lazy { File(filesDir, "wallpaper") }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            i.data?.schemeSpecificPart?.let(repo::evict)
            reloadApps()
        }
    }

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            main.postDelayed(this, WEATHER_MS)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepository(this)
        palette = Palette(dark = prefs.lightMode)
        buildUi()
        setContentView(root)
        applyPalette()
        loadWallpaper()
        ContinueWatching.onChange = { onWatchingChanged() }
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        reloadApps()
    }

    override fun onResume() {
        super.onResume()
        if (rowsDirty && appsLoaded) rebuildRows()
        if (overlays.isEmpty()) focusFirst()
        refreshWeather()
        main.removeCallbacks(weatherTick)
        main.postDelayed(weatherTick, WEATHER_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) HomeRedirectService.onHomeShown()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(weatherTick)
    }

    override fun onStop() {
        super.onStop()
        closeAllOverlays() // also shuts the upload server down
    }

    /** HOME while SmokeTV is already in front: back to the start of the row. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeAllOverlays()
        pinnedScroll.smoothScrollTo(0, 0)
        focusFirst()
    }

    override fun onDestroy() {
        ContinueWatching.onChange = null
        unregisterReceiver(packageReceiver)
        stopServer()
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Fine on targetSdk 30; no AndroidX back dispatcher in this app.")
    override fun onBackPressed() {
        // Never call super: this is the home screen, finishing would reveal Amazon's launcher.
        if (overlays.isNotEmpty()) popOverlay() else focusFirst()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (overlays.lastOrNull()?.isMenu == true) {
                popOverlay()
                return true
            }
            val t = currentFocus as? TileView
            if (t?.app != null) showTileMenu(t) else if (overlays.isEmpty()) openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        mountains = MountainView(this).apply { setLight(prefs.lightMode) }
        wallpaperView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Slightly enlarged so a photo can pan with the parallax without showing its edges.
            scaleX = 1f + 2.2f * PHOTO_TRAVEL
            scaleY = 1f + 2.2f * PHOTO_TRAVEL
            visibility = View.GONE
        }
        scrimView = View(this)
        root.addView(mountains, MATCH_PARENT, MATCH_PARENT)
        root.addView(wallpaperView, MATCH_PARENT, MATCH_PARENT)
        root.addView(scrimView, MATCH_PARENT, MATCH_PARENT)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(28), 0, dp(16))
        }
        root.addView(content, MATCH_PARENT, MATCH_PARENT)

        // Top bar: clock/date/weather left, quick actions right.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false // focused buttons scale past their bounds
            clipToPadding = false
            setPadding(dp(SIDE), 0, dp(SIDE), 0)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        themeButton = RoundButton(this, if (prefs.lightMode) R.drawable.ic_sun else R.drawable.ic_moon, "Dark or light mode")
            .apply { setOnClickListener { toggleTheme() } }
        clock = TextClock(this).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            textSize = 44f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
        }
        val status = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val datePattern = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEdMMMM")
        date = TextClock(this).apply {
            format12Hour = datePattern
            format24Hour = datePattern
            textSize = 15f
        }
        weatherView = TextView(this).apply { textSize = 15f; visibility = View.GONE }
        status.addView(date)
        status.addView(weatherView)
        left.addView(clock)
        left.addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        top.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons = listOf(
            themeButton,
            RoundButton(this, R.drawable.ic_apps, "All apps").apply { setOnClickListener { openDrawer() } },
            RoundButton(this, R.drawable.ic_settings, "Fire TV settings").apply { setOnClickListener { openSystemSettings() } },
            RoundButton(this, R.drawable.ic_tune, "Launcher settings").apply { setOnClickListener { openSettings() } },
        )
        buttons.forEach { top.addView(it, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(12) }) }
        content.addView(top, MATCH_PARENT, WRAP_CONTENT)

        content.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Middle: pinned apps.
        pinnedRow = tileRow()
        pinnedScroll = hScroll(pinnedRow)
        content.addView(pinnedScroll, MATCH_PARENT, WRAP_CONTENT)

        // Bottom: recently launched apps.
        recentsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        recentsTitle = TextView(this).apply {
            text = "RECENT"
            textSize = 12f
            letterSpacing = 0.15f
            setPadding(dp(SIDE), 0, dp(SIDE), 0)
        }
        recentRow = tileRow()
        recentScroll = hScroll(recentRow)
        recentsHint = TextView(this).apply {
            text = "Episodes you watch on Netflix show up here"
            textSize = 14f
            setPadding(dp(SIDE), dp(14), dp(SIDE), dp(14))
        }
        recentsSection.addView(recentsTitle)
        recentsSection.addView(recentScroll, MATCH_PARENT, WRAP_CONTENT)
        recentsSection.addView(recentsHint)
        content.addView(recentsSection, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })

        bottomSpacer = View(this)
        content.addView(bottomSpacer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.3f))
    }

    private fun tileRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(SIDE), dp(14), dp(SIDE), dp(8))
    }

    private fun hScroll(child: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = false
        clipToPadding = false
        addView(child)
    }

    private fun ViewGroup.tiles(): List<TileView> = (0 until childCount).mapNotNull { getChildAt(it) as? TileView }

    // ---------------------------------------------------------------- apps & rows

    private fun reloadApps() {
        io.execute {
            repo.reload()
            if (!prefs.hasPinned) prefs.pinned = repo.defaultPins(prefs.hidden)
            main.post {
                appsLoaded = true
                rebuildRows()
                if (overlays.isEmpty()) focusFirst()
                if (drawerGrid != null) fillDrawer(null, 0)
            }
        }
    }

    private fun pinnedApps() = prefs.pinned.mapNotNull(repo::find)

    private fun rebuildRows() {
        rowsDirty = false
        pinnedRow.removeAllViews()
        for (app in pinnedApps()) pinnedRow.addView(tile(app, Place.PINNED, PIN_W, PIN_H, false), tileLp(PIN_GAP))
        pinnedRow.addView(TileView(this, null, dp(PIN_H), dp(PIN_H), false).apply {
            setPlaceholder(R.drawable.ic_add, "Add apps")
            setOnClickListener { openDrawer() }
        }, tileLp(0))

        recentRow.removeAllViews()
        val recents = if (prefs.showRecents) recentItems() else emptyList()
        for ((app, ep) in recents) {
            val t = tile(app, Place.RECENT, REC_W, REC_H, false)
            if (ep != null) {
                t.setEpisode(ep)
                t.setOnClickListener { openEpisode(app, ep) }
            }
            recentRow.addView(t, tileLp(REC_GAP))
        }
        recentsSection.visibility = if (prefs.showRecents) View.VISIBLE else View.GONE
        recentScroll.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        recentsHint.visibility = if (recents.isEmpty()) View.VISIBLE else View.GONE
        (bottomSpacer.layoutParams as LinearLayout.LayoutParams).weight = if (prefs.showRecents) 0.3f else 1f
        bottomSpacer.requestLayout()

        (pinnedRow.tiles() + recentRow.tiles()).forEach { t ->
            t.onFocusChangeListener = View.OnFocusChangeListener { v, has -> if (has) parallaxFor(v as TileView) }
        }
        wireFocus()
        applyPalette()
    }

    /** Pans the wallpaper with the focus: start of a row = left, end = right. */
    private fun parallaxFor(t: TileView) {
        val row = t.parent as? ViewGroup ?: return
        keepMargin(t)
        val n = row.childCount
        val p = if (n <= 1) 0f else row.indexOfChild(t).toFloat() / (n - 1)
        mountains.parallaxTo(p)
        if (wallpaperView.visibility == View.VISIBLE) {
            wallpaperView.animate().translationX(-(p - 0.5f) * 2f * PHOTO_TRAVEL * root.width)
                .setDuration(900).setInterpolator(DECEL).start()
        }
    }

    /** HorizontalScrollView only scrolls a tile to the screen edge; keep the side margin visible. */
    private fun keepMargin(t: TileView) {
        val scroll = t.parent?.parent as? HorizontalScrollView ?: return
        scroll.post {
            val m = dp(SIDE)
            val left = t.left - scroll.scrollX
            val right = t.right - scroll.scrollX
            when {
                left < m -> scroll.smoothScrollTo(t.left - m, 0)
                right > scroll.width - m -> scroll.smoothScrollTo(t.right + m - scroll.width, 0)
            }
        }
    }

    private fun toggleTheme() {
        prefs.lightMode = !prefs.lightMode
        palette = Palette(dark = prefs.lightMode)
        themeButton.setImageResource(if (prefs.lightMode) R.drawable.ic_sun else R.drawable.ic_moon)
        mountains.setLight(prefs.lightMode)
        applyPalette()
    }

    private fun dialogTheme() =
        if (prefs.lightMode) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert

    /** The Recent row shows only episodes to continue (Netflix), never plain apps. */
    private fun recentItems(): List<Pair<AppEntry, Episode?>> =
        ContinueWatching.all().mapNotNull { ep -> repo.find(ep.pkg)?.let { it to ep } }.take(MAX_RECENT_SHOWN)

    private fun openEpisode(app: AppEntry, ep: Episode) {
        try {
            val pi = ep.intent ?: throw android.app.PendingIntent.CanceledException()
            pi.send()
            prefs.recordLaunch(app.pkg)
            rowsDirty = true
        } catch (_: android.app.PendingIntent.CanceledException) {
            launch(app) // card expired: open the app instead
        }
    }

    /** New continue-watching data: rebuild now unless the user is in the middle of the row. */
    private fun onWatchingChanged() {
        if (!appsLoaded) return
        val f = currentFocus as? TileView
        if (overlays.isNotEmpty() || (f != null && f.parent === recentRow)) {
            rowsDirty = true
            return
        }
        val pinnedIndex = f?.let { pinnedRow.indexOfChild(it) } ?: -1
        rebuildRows()
        if (pinnedIndex >= 0) focusTile(Place.PINNED, null, pinnedIndex)
    }

    private fun tileLp(gapDp: Int) = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(gapDp) }

    private fun tile(app: AppEntry, place: Place, wDp: Int, hDp: Int, labelAlways: Boolean): TileView {
        val t = TileView(this, app, dp(wDp), dp(hDp), labelAlways)
        t.tag = place
        t.setOnClickListener { launch(app) }
        t.setOnLongClickListener { showTileMenu(t); true } // long-press DPAD_CENTER
        t.applyPalette(palette)
        bindIcon(t)
        return t
    }

    private fun bindIcon(t: TileView) {
        val app = t.app ?: return
        val mono = prefs.monochrome
        repo.cached(app, mono, t.cardW, t.cardH)?.let { t.setImage(it, mono); return }
        io.execute {
            val img = repo.icon(app, mono, t.cardW, t.cardH)
            main.post { t.setImage(img, mono) }
        }
    }

    /** Explicit up/down links between top bar, pinned row and recents row; edges hold focus. */
    private fun wireFocus() {
        val pinned = pinnedRow.tiles()
        val recent = if (recentsSection.visibility == View.VISIBLE) recentRow.tiles() else emptyList()
        val topTarget = buttons[1].id // All apps
        pinned.forEachIndexed { i, t ->
            t.nextFocusUpId = topTarget
            t.nextFocusDownId = recent.getOrNull(minOf(i, recent.size - 1))?.id ?: t.id
            t.nextFocusLeftId = if (i == 0) t.id else View.NO_ID
            t.nextFocusRightId = if (i == pinned.lastIndex) t.id else View.NO_ID
        }
        recent.forEachIndexed { i, t ->
            t.nextFocusUpId = pinned.getOrNull(minOf(i, pinned.size - 1))?.id ?: topTarget
            t.nextFocusDownId = t.id
            t.nextFocusLeftId = if (i == 0) t.id else View.NO_ID
            t.nextFocusRightId = if (i == recent.lastIndex) t.id else View.NO_ID
        }
        buttons.forEachIndexed { i, b ->
            b.nextFocusUpId = b.id
            b.nextFocusDownId = pinned.firstOrNull()?.id ?: b.id
            b.nextFocusLeftId = if (i == 0) b.id else View.NO_ID
            b.nextFocusRightId = if (i == buttons.lastIndex) b.id else View.NO_ID
        }
    }

    private fun focusFirst() {
        (pinnedRow.getChildAt(0) ?: buttons[1]).requestFocus()
    }

    private fun focusTile(place: Place, pkg: String?, index: Int) {
        val tiles = (if (place == Place.RECENT) recentRow else pinnedRow).tiles()
        val t = tiles.firstOrNull { pkg != null && it.app?.pkg == pkg }
            ?: tiles.getOrNull(minOf(index, tiles.size - 1))
        (t ?: pinnedRow.getChildAt(0))?.requestFocus()
    }

    private fun launch(app: AppEntry) {
        if (!repo.launch(app)) {
            toast("Can't open ${app.label}")
            return
        }
        prefs.recordLaunch(app.pkg)
        rowsDirty = true
    }

    // ---------------------------------------------------------------- theme & wallpaper

    private fun applyPalette() {
        val p = palette
        clock.setTextColor(p.fg)
        date.setTextColor(p.fgDim)
        weatherView.setTextColor(p.fgDim)
        recentsTitle.setTextColor(p.fgDim)
        recentsHint.setTextColor(p.fgDim)
        val shadow = if (p.dark) 0 else 0x66000000
        listOf(clock, date, weatherView).forEach { it.setShadowLayer(if (p.dark) 0f else 6f, 0f, 1f, shadow) }
        buttons.forEach { it.palette = p }
        pinnedRow.tiles().forEach { it.applyPalette(p) }
        recentRow.tiles().forEach { it.applyPalette(p) }
        applyScrim()
    }

    /** Vertical scrim, strongest at the top and bottom edges. */
    private fun applyScrim() {
        val rgb = palette.scrimRgb
        val solid = (0xFF shl 24) or rgb
        val soft = (0x59 shl 24) or rgb
        scrimView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(solid, soft, rgb, rgb, soft, solid),
        )
        scrimView.alpha = prefs.scrim / 100f
    }

    private fun loadWallpaper() {
        val (w, h) = resources.displayMetrics.run { widthPixels to heightPixels }
        io.execute {
            val bmp = if (wallpaperFile.exists()) decodeSampled(wallpaperFile, w, h) else null
            main.post {
                wallpaperView.setImageBitmap(bmp)
                wallpaperView.visibility = if (bmp != null) View.VISIBLE else View.GONE
                mountains.visibility = if (bmp != null) View.GONE else View.VISIBLE
            }
        }
    }

    private fun decodeSampled(f: File, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------------------------------------------------------------- weather

    private fun refreshWeather(force: Boolean = false) {
        if (!prefs.weather || prefs.place.isEmpty()) {
            weatherView.visibility = View.GONE
            return
        }
        weatherView.visibility = View.VISIBLE
        weatherView.text = prefs.weatherText?.let { "  ·  $it" } ?: ""
        if (!force && System.currentTimeMillis() - prefs.weatherAt < WEATHER_MS) return
        val lat = prefs.lat
        val lon = prefs.lon
        thread(isDaemon = true) {
            val text = Weather.current(lat, lon) ?: return@thread
            main.post {
                prefs.weatherText = text
                prefs.weatherAt = System.currentTimeMillis()
                if (prefs.weather) weatherView.text = "  ·  $text"
            }
        }
    }

    private fun askLocation(onDone: () -> Unit) {
        val input = EditText(this).apply {
            hint = "City, or lat, lon"
            setText(prefs.place)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Weather location")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                thread(isDaemon = true) {
                    val place = Weather.resolve(q)
                    main.post {
                        if (place == null) {
                            toast("Location not found")
                        } else {
                            prefs.place = place.name
                            prefs.lat = place.lat
                            prefs.lon = place.lon
                            prefs.weatherText = null
                            refreshWeather(force = true)
                        }
                        onDone()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- overlays

    private fun pushOverlay(v: View, isMenu: Boolean = false, onClose: (() -> Unit)? = null) {
        val prev = currentFocus
        // Block focus underneath so the D-pad can't wander behind the overlay.
        ((overlays.lastOrNull()?.view as? ViewGroup) ?: content).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlays += Overlay(v, prev, isMenu, onClose)
        root.addView(v, MATCH_PARENT, MATCH_PARENT)
    }

    private fun popOverlay(restoreFocus: Boolean = true) {
        val o = overlays.removeLastOrNull() ?: return
        root.removeView(o.view)
        o.onClose?.invoke()
        ((overlays.lastOrNull()?.view as? ViewGroup) ?: content).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (!restoreFocus) return
        val r = o.restore
        if (r != null && r.isAttachedToWindow && r.requestFocus()) return
        if (overlays.isEmpty()) focusFirst()
    }

    private fun closeAllOverlays() {
        while (overlays.isNotEmpty()) popOverlay(restoreFocus = false)
    }

    private fun panelBg() = glass(dp(18).toFloat(), palette.panel, dp(1), palette.panelStroke)

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(palette.panelText)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(16), 0, dp(16), dp(12))
    }

    private fun trap(rows: List<View>) {
        rows.forEach { it.nextFocusLeftId = it.id; it.nextFocusRightId = it.id }
        rows.firstOrNull()?.let { it.nextFocusUpId = it.id }
        rows.lastOrNull()?.let { it.nextFocusDownId = it.id }
    }

    private fun showMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(dp(12), dp(18), dp(12), dp(12))
        }
        panel.addView(heading(title, 17f))
        val rows = items.map { (label, action) ->
            OptionRow(this, label, null, palette).also { row ->
                row.setOnClickListener { popOverlay(); action() }
                panel.addView(row, MATCH_PARENT, WRAP_CONTENT)
            }
        }
        val shade = FrameLayout(this).apply {
            setBackgroundColor(palette.menuShade)
            addView(panel, FrameLayout.LayoutParams(dp(340), WRAP_CONTENT, Gravity.CENTER))
        }
        pushOverlay(shade, isMenu = true)
        trap(rows)
        rows.firstOrNull()?.requestFocus()
    }

    private fun showTileMenu(t: TileView) {
        val app = t.app ?: return
        val place = t.tag as? Place ?: return
        val pkg = app.pkg
        val pinned = pinnedApps().map { it.pkg }
        val pi = pinned.indexOf(pkg)
        val index = (t.parent as? ViewGroup)?.indexOfChild(t) ?: 0
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (place == Place.PINNED) {
            if (pi > 0) items += "Move left" to { movePinned(pkg, -1) }
            if (pi in 0 until pinned.lastIndex) items += "Move right" to { movePinned(pkg, 1) }
        }
        if (pi >= 0) {
            items += "Unpin from row" to { prefs.pinned = pinned - pkg; afterChange(place, pkg, index) }
        } else {
            items += "Pin to row" to { prefs.pinned = pinned + pkg; afterChange(place, pkg, index) }
        }
        if (place == Place.RECENT && t.episode != null) {
            items += "Open ${app.label} start screen" to { launch(app) }
        }
        val hidden = pkg in prefs.hidden
        items += (if (hidden) "Show in drawer" else "Hide from drawer") to {
            prefs.hidden = if (hidden) prefs.hidden - pkg else prefs.hidden + pkg
            afterChange(place, pkg, index)
        }
        items += "App info" to { openAppInfo(pkg) }
        showMenu(app.label, items)
    }

    private fun movePinned(pkg: String, delta: Int) {
        val list = pinnedApps().map { it.pkg }.toMutableList()
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list[i] = list[j]
        list[j] = pkg
        prefs.pinned = list
        rebuildRows()
        focusTile(Place.PINNED, pkg, j)
    }

    private fun afterChange(place: Place, pkg: String?, index: Int) {
        rebuildRows()
        if (drawerGrid != null) fillDrawer(pkg, index) else focusTile(place, pkg, index)
    }

    // ---------------------------------------------------------------- all apps drawer

    private fun openDrawer() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SIDE), 0, dp(SIDE), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "All apps"
            textSize = 26f
            setTextColor(palette.panelText)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val chip = OptionRow(this, "Show hidden apps", onOff(showHiddenInDrawer), palette)
        chip.setOnClickListener {
            showHiddenInDrawer = !showHiddenInDrawer
            chip.setValue(onOff(showHiddenInDrawer))
            fillDrawer(null, 0)
            chip.requestFocus()
        }
        header.addView(chip, LinearLayout.LayoutParams(dp(280), WRAP_CONTENT))
        col.addView(header, MATCH_PARENT, WRAP_CONTENT)

        val grid = GridLayout(this).apply {
            columnCount = DRAWER_COLS
            clipChildren = false
            clipToPadding = false
            setPadding(dp(SIDE - 10), dp(10), dp(SIDE - 10), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            addView(grid)
        }
        col.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val shade = FrameLayout(this).apply {
            setBackgroundColor(palette.drawerBg)
            addView(col, MATCH_PARENT, MATCH_PARENT)
        }
        drawerGrid = grid
        drawerChip = chip
        pushOverlay(shade) {
            drawerGrid = null
            drawerChip = null
            content.visibility = View.VISIBLE
        }
        // Hide the home screen only after pushOverlay noted the focused view to return to.
        content.visibility = View.INVISIBLE
        fillDrawer(null, 0)
        (grid.getChildAt(0) ?: chip).requestFocus()
    }

    /** Tile width (dp) that makes DRAWER_COLS columns span the screen between the side margins. */
    private fun drawerTileW(): Int {
        val screenDp = resources.displayMetrics.run { widthPixels / density }.toInt()
        return (screenDp - 2 * (SIDE - 10)) / DRAWER_COLS - 20 // grid padding SIDE-10, tile margins 10+10
    }

    private fun fillDrawer(focusPkg: String?, fallbackIndex: Int) {
        val grid = drawerGrid ?: return
        val chip = drawerChip ?: return
        grid.removeAllViews()
        val hidden = prefs.hidden
        val tiles = repo.apps.filter { showHiddenInDrawer || it.pkg !in hidden }.map { app ->
            tile(app, Place.DRAWER, drawerTileW(), drawerTileW() * 9 / 16, labelAlways = true).also {
                it.dimmed = app.pkg in hidden
                grid.addView(it, GridLayout.LayoutParams().apply { setMargins(dp(10), dp(8), dp(10), dp(12)) })
            }
        }
        tiles.take(DRAWER_COLS).forEach { it.nextFocusUpId = chip.id }
        chip.nextFocusUpId = chip.id
        chip.nextFocusLeftId = chip.id
        chip.nextFocusRightId = chip.id
        chip.nextFocusDownId = tiles.firstOrNull()?.id ?: chip.id
        val target = tiles.firstOrNull { focusPkg != null && it.app?.pkg == focusPkg }
            ?: tiles.getOrNull(minOf(fallbackIndex, tiles.size - 1))
        if (focusPkg != null || fallbackIndex > 0 || !chip.isFocused) (target ?: chip).requestFocus()
    }

    // ---------------------------------------------------------------- settings

    private fun openSettings() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = ArrayList<OptionRow>()
        fun add(label: String, value: () -> String?, click: (OptionRow) -> Unit): OptionRow {
            val r = OptionRow(this, label, value(), palette)
            r.setOnClickListener { click(r); r.setValue(value()) }
            list.addView(r, MATCH_PARENT, WRAP_CONTENT)
            rows += r
            return r
        }
        var locationRow: OptionRow? = null
        val placeValue = { prefs.place.ifEmpty { "Not set" } }

        add("Icon style", { if (prefs.monochrome) "Monochrome" else "Original" }) {
            prefs.monochrome = !prefs.monochrome
            rebuildRows()
        }
        val scrimRow = add("Edge dimming", { "${prefs.scrim}%" }) {
            prefs.scrim = if (prefs.scrim >= 100) 0 else prefs.scrim + 10
            applyScrim()
        }
        scrimRow.setOnKeyListener { _, code, ev ->
            val d = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -10
                KeyEvent.KEYCODE_DPAD_RIGHT -> 10
                else -> return@setOnKeyListener false
            }
            if (ev.action == KeyEvent.ACTION_DOWN) {
                prefs.scrim += d
                applyScrim()
                scrimRow.setValue("${prefs.scrim}%")
            }
            true
        }
        add("Recent episodes row", { if (prefs.showRecents) "Shown" else "Hidden" }) {
            prefs.showRecents = !prefs.showRecents
            rebuildRows()
        }
        add("Weather", { onOff(prefs.weather) }) {
            prefs.weather = !prefs.weather
            if (prefs.weather && prefs.place.isEmpty()) askLocation { locationRow?.setValue(placeValue()) }
            refreshWeather(force = true)
        }
        locationRow = add("Weather location", placeValue) { row -> askLocation { row.setValue(placeValue()) } }
        add("Upload wallpaper from phone", { null }) { openUploadPanel() }
        add("Wallpaper", { if (wallpaperFile.exists()) "Your photo" else "Mountains" }) {
            // Back to the generated mountains (a photo comes from "Upload wallpaper from phone").
            if (wallpaperFile.delete()) loadWallpaper() else openUploadPanel()
        }
        add("Continue watching", { if (ContinueWatching.connected) "On" else "Off" }) { showWatchingHelp() }
        add("Home button redirect", { if (HomeRedirectService.isRunning) "Active" else "Off" }) { showRedirectHelp() }

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.panel)
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        panel.addView(heading("Launcher settings", 22f))
        panel.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        panel.addView(TextView(this).apply {
            text = "SmokeTV $version  ·  Menu button or long-press OK on a tile for options"
            textSize = 12f
            setTextColor(palette.panelDim)
            setPadding(dp(16), dp(8), dp(16), 0)
        })
        val shade = FrameLayout(this).apply {
            setBackgroundColor(palette.menuShade)
            addView(panel, FrameLayout.LayoutParams(dp(420), MATCH_PARENT, Gravity.END))
        }
        pushOverlay(shade) { stopServer() }
        trap(rows)
        rows.first().requestFocus()
    }

    private fun showRedirectHelp() {
        val c = "$packageName/$packageName.HomeRedirectService"
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Home button redirect")
            .setMessage(
                "Fire OS always sends the Home button to Amazon's launcher. The optional redirect " +
                    "service brings SmokeTV back in front of it.\n\nEnable it from a PC:\n\n" +
                    "adb shell settings put secure enabled_accessibility_services $c\n" +
                    "adb shell settings put secure accessibility_enabled 1\n\n" +
                    "For an instant switch also run:\n" +
                    "adb shell settings put global alarm_manager_constants min_futurity=0"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showWatchingHelp() {
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Continue watching")
            .setMessage(
                "Shows the episodes Netflix offers to continue in the Recent row; selecting one " +
                    "starts that episode. Netflix updates the list when you leave the app. " +
                    "The titles stay on this TV.\n\nSmokeTV needs notification access for this. " +
                    "Fire OS only grants it from a PC:\n\n" +
                    "adb shell cmd notification allow_listener $packageName/$packageName.WatchListener"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openUploadPanel() {
        val ip = localIp()
        if (ip == null) {
            toast("Connect the Fire TV to Wi-Fi first")
            return
        }
        stopServer()
        val status = TextView(this).apply {
            text = "Waiting for an image…"
            textSize = 15f
            setTextColor(palette.panelDim)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        val srv = WallpaperServer(
            wallpaperFile,
            onStatus = { msg -> main.post { status.text = msg } },
            onWallpaper = {
                main.post {
                    status.text = "Wallpaper updated. Send another, or press Back."
                    loadWallpaper()
                }
            },
        )
        val port = try { srv.start() } catch (_: IOException) {
            toast("Could not start the upload server")
            return
        }
        server = srv
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        card.addView(heading("Upload a wallpaper from your phone", 20f))
        card.addView(TextView(this).apply {
            text = "On a phone in the same Wi-Fi, open:"
            textSize = 15f
            setTextColor(palette.panelDim)
            setPadding(dp(16), 0, dp(16), dp(6))
        })
        card.addView(TextView(this).apply {
            text = if (port == 80) "http://$ip" else "http://$ip:$port"
            textSize = 30f
            setTextColor(palette.panelText)
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), 0, dp(16), 0)
        })
        card.addView(status)
        val done = OptionRow(this, "Done", null, palette).apply { setOnClickListener { popOverlay() } }
        card.addView(done, MATCH_PARENT, WRAP_CONTENT)
        val shade = FrameLayout(this).apply {
            setBackgroundColor(palette.menuShade)
            addView(card, FrameLayout.LayoutParams(dp(560), WRAP_CONTENT, Gravity.CENTER))
        }
        pushOverlay(shade) { stopServer() }
        trap(listOf(done))
        done.requestFocus()
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- system intents

    /**
     * Fire TV settings. On Fire OS, android.settings.SETTINGS goes to a stub that opens Amazon's
     * home screen at its settings row, which the Home redirect then sends straight back here.
     * Amazon's settings page is opened directly instead; the stub is only the last resort.
     */
    private fun openSystemSettings() {
        val direct = listOf(
            Intent(Intent.ACTION_MAIN).setClassName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"),
            Intent("amazon.intent.action.MAINSETTINGS").addCategory("android.intent.category.MAINSETTINGS"),
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS"),
        )
        for (i in direct) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        start(Intent(Settings.ACTION_SETTINGS))
    }

    private fun openAppInfo(pkg: String) =
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))

    private fun start(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast("Not available on this device")
        } catch (_: SecurityException) {
            toast("Not available on this device")
        }
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val SIDE = 48 // overscan-safe horizontal margin (dp)
        const val PIN_W = 200
        const val PIN_H = 112
        const val PIN_GAP = 20
        const val REC_W = 136
        const val REC_H = 76
        const val REC_GAP = 16
        const val DRAWER_COLS = 5
        const val MAX_RECENT_SHOWN = 10
        const val PHOTO_TRAVEL = 0.03f // photo wallpaper pan, share of screen width
        const val WEATHER_MS = 30 * 60 * 1000L
    }
}
