package io.github.florent777.smoketv

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * OPTIONAL Home-button redirect. Fire OS ignores third-party HOME apps: the Home button always
 * opens Amazon's launcher. This service waits for Amazon's home screen to appear and brings
 * SmokeTV back in front of it.
 *
 * It is OFF by default (Android never enables an accessibility service by itself, and Fire OS
 * has no UI for sideloaded ones). Enable it from a PC:
 *
 *   adb shell settings put secure enabled_accessibility_services io.github.florent777.smoketv/io.github.florent777.smoketv.HomeRedirectService
 *   adb shell settings put secure accessibility_enabled 1
 *
 * Recommended (instant instead of ~5 s): Android delays app activity starts for 5 s after a Home
 * press, and alarms by min_futurity. The redirect launches through an exact alarm (sent by
 * system_server, so the app-switch delay does not apply); this makes that alarm fire at once:
 *
 *   adb shell settings put global alarm_manager_constants min_futurity=0
 *
 * Disable again:
 *   adb shell settings put secure enabled_accessibility_services '""'
 *
 * Only Amazon's HOME activity is redirected: Amazon's settings screens live in the same
 * package (com.amazon.tv.launcher.ui.MainSettingsActivity) and must stay reachable.
 */
class HomeRedirectService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var cover: View? = null
    private val uncover = Runnable { cover?.visibility = View.GONE }
    private val launchFallback = Runnable { launch() }
    /** The last window that came to the front was Amazon's home screen. */
    private var onAmazonHome = false
    // Waking from standby resumes whatever was on screen without a new window event.
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (onAmazonHome) redirect()
        }
    }

    override fun onServiceConnected() {
        instance = this
        // An opaque accessibility overlay hides Amazon's home until ours has drawn. On Android 11
        // it is also the visible window that lets the background activity start through.
        val v = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply { windowAnimations = 0 }
        cover = try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp); v
        } catch (_: RuntimeException) {
            null
        }
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        // System UI and dialogs don't change what is underneath.
        if (pkg == "com.android.systemui" || pkg == "android") return
        onAmazonHome = pkg == AMAZON_PKG && e.className?.toString() == AMAZON_HOME
        if (onAmazonHome) redirect()
    }

    private fun redirect() {
        val v = cover
        if (v == null || v.visibility == View.VISIBLE) {
            launch()
            return
        }
        v.visibility = View.VISIBLE
        v.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                v.viewTreeObserver.removeOnPreDrawListener(this)
                handler.removeCallbacks(launchFallback)
                launch()
                return true
            }
        })
        handler.postDelayed(launchFallback, 150)
        handler.removeCallbacks(uncover)
        handler.postDelayed(uncover, COVER_MAX_MS)
    }

    private fun launch() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
        )
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), pi)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenOn) } catch (_: IllegalArgumentException) { }
        cover?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: RuntimeException) { } }
        cover = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val AMAZON_PKG = "com.amazon.tv.launcher"
        private const val AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
        private const val COVER_MAX_MS = 1500L

        @Volatile
        private var instance: HomeRedirectService? = null

        val isRunning: Boolean get() = instance != null

        /** SmokeTV's window has focus: drop the cover. */
        fun onHomeShown() {
            instance?.let { it.handler.post(it.uncover) }
        }
    }
}
