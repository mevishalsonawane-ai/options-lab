package com.optionslab.app.ira

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.optionslab.app.R
import com.optionslab.app.work.Notifier

/**
 * Jarvis's pop-up: a short message that drops in at the top of the screen over any app, stays about 3 seconds and
 * hides by itself (the owner's wish, 2026-10-02). A heads-up notification - no "draw over other apps" permission;
 * private on the lock screen. JarvisAlgo only.
 */
object JarvisPopup {
    const val SHOW_MS = 3_500L
    private var next = 0

    fun show(context: Context, title: String, text: String, tab: String = "almanac") {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        val n = NotificationCompat.Builder(context, Notifier.POPUP)
            .setSmallIcon(R.drawable.ic_notification_art)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setTimeoutAfter(SHOW_MS)
            .setAutoCancel(true)
            .setContentIntent(Notifier.openApp(context, tab))
            .build()
        val id = 7400 + synchronized(this) { next = (next + 1) % 50; next }
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}
