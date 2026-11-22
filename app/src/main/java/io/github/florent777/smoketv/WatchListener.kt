package io.github.florent777.smoketv

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** One "continue watching" entry, e.g. Netflix: "The Mentalist" / "Red John" with its 16:9 cover. */
class Episode(
    val pkg: String,
    val show: String,
    val episode: String?,
    val cover: Bitmap?,
    val intent: PendingIntent?,
    val key: String,
    val order: Long,
)

/** What streaming apps currently offer to continue, kept in memory while the listener is bound. */
object ContinueWatching {
    private val entries = LinkedHashMap<String, Episode>()
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread whenever the list changes. */
    var onChange: (() -> Unit)? = null

    @Volatile
    var connected = false
        internal set

    fun all(): List<Episode> = synchronized(entries) { entries.values.sortedBy { it.order } }

    fun forPackage(pkg: String) = all().filter { it.pkg == pkg }

    internal fun put(e: Episode) {
        synchronized(entries) { entries[e.key] = e }
        changed()
    }

    internal fun remove(key: String) {
        val gone = synchronized(entries) { entries.remove(key) != null }
        if (gone) changed()
    }

    internal fun clear() {
        synchronized(entries) { entries.clear() }
        changed()
    }

    private fun changed() = main.post { onChange?.invoke() }
}

/**
 * Reads the hidden "continue watching" cards that streaming apps post for the TV's home screen.
 * Netflix posts them as recommendation notifications in the group "continueWatching": text
 * "Show, Episode", a 640x360 cover as the large icon, and a content intent that opens that
 * episode. It refreshes them when you leave the app. (Prime Video posts none and reports no
 * title in its media session, checked on Fire OS 7.) Nothing is shown and nothing leaves the TV.
 *
 * Needs notification access, which Fire OS only grants over ADB:
 *   adb shell cmd notification allow_listener io.github.florent777.smoketv/io.github.florent777.smoketv.WatchListener
 */
class WatchListener : NotificationListenerService() {

    override fun onListenerConnected() {
        ContinueWatching.connected = true
        ContinueWatching.clear()
        try {
            activeNotifications?.forEach(::consider)
        } catch (_: SecurityException) {
        }
    }

    override fun onListenerDisconnected() {
        ContinueWatching.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = consider(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = ContinueWatching.remove(sbn.key)

    private fun consider(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (!isContinueWatching(n)) return
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        // Netflix: "Show, Episode title". A show without an episode stays whole.
        val cut = text.indexOf(", ")
        val show = if (cut > 0) text.substring(0, cut) else text
        val episode = if (cut > 0) text.substring(cut + 2) else null
        val cover = try {
            n.getLargeIcon()?.loadDrawable(this)?.let(::coverBitmap)
        } catch (_: RuntimeException) {
            null
        }
        ContinueWatching.put(Episode(sbn.packageName, show, episode, cover, n.contentIntent, sbn.key, sbn.postTime))
    }

    /** Scales the cover down to tile size (Netflix sends 640x360) to keep memory low. */
    private fun coverBitmap(d: Drawable): Bitmap? {
        val src = (d as? BitmapDrawable)?.bitmap
        val w = COVER_W
        val h = if (src != null && src.width > 0) w * src.height / src.width else w * 9 / 16
        if (src != null) return Bitmap.createScaledBitmap(src, w, h, true)
        if (d.intrinsicWidth <= 0) return null
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(it))
        }
    }

    private fun isContinueWatching(n: Notification): Boolean =
        n.category == Notification.CATEGORY_RECOMMENDATION && GROUPS.containsMatchIn(n.group.orEmpty())

    private companion object {
        val GROUPS = Regex("continue|resume|weiter", RegexOption.IGNORE_CASE)
        const val COVER_W = 400
    }
}
