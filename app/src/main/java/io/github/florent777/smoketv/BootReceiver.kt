package io.github.florent777.smoketv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Opens SmokeTV after boot. Fire OS 7 (Android 9) allows this as is. Fire OS 8 (Android 11)
 * blocks activity starts from the background unless the app is exempt; an enabled
 * HomeRedirectService (a system-bound accessibility service) or the overlay app-op makes it so:
 *   adb shell appops set io.github.florent777.smoketv SYSTEM_ALERT_WINDOW allow
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (_: RuntimeException) {
            // Background start refused; the redirect service will take over on the first Home press.
        }
    }
}
