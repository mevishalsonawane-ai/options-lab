package com.optionslab.app.ira

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.optionslab.app.R
import com.optionslab.app.work.Notifier

/**
 * Jarvis's pop-up: a short message that drops in at the top of the screen over any app, stays about 3 seconds and
 * hides by itself (the owner's wish, 2026-10-02). A heads-up notification - no "draw over other apps" permission;
 * private on the lock screen, and while the phone is locked a line holding Boss's account ([com.optionslab.ira.Overheard])
 * only says that it is in the chat. Jarvis only.
 */
object JarvisPopup {
    const val SHOW_MS = 3_500L
    private var next = 0

    /**
     * [action]: a request of Jarvis's waiting for Boss's Confirm ([IraHub] pending id) that this pop-up asks about;
     * [proposal]: a strategy waiting for Approve; [setting]: a Settings row it is about. Tapped, the app opens with the
     * pop-up's whole text over it and - while still waiting - the chat's own buttons for it
     * ([com.optionslab.app.work.NoticeCard]).
     */
    fun show(context: Context, title: String, text: String, tab: String = "almanac",
             action: Long? = null, proposal: Long? = null, setting: String? = null) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        // A locked phone's pop-up may be seen by anyone near it: no amount, P&L or symbol - only that it is in the chat.
        val locked = runCatching { IraHub.locked() }.getOrDefault(true)
        val shownTitle = com.optionslab.ira.Overheard.title(title, locked)
        val shown = com.optionslab.ira.Overheard.said(text, locked)
        val id = 7400 + synchronized(this) { next = (next + 1) % 50; next }
        // The whole card is kept in memory for the unlocked app; the intent carries only what the pop-up shows.
        val card = com.optionslab.app.work.NoticeCard(id, "jarvis", title, text, System.currentTimeMillis(), tab = tab,
            setting = setting, action = action, proposal = proposal)
        val n = NotificationCompat.Builder(context, Notifier.POPUP)
            .setSmallIcon(R.drawable.ic_notification_art)
            .setContentTitle(shownTitle)
            .setContentText(shown)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shown))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply { if (action != null) setPublicVersion(JarvisApproval.lockedVersion(context)) }
            .setTimeoutAfter(SHOW_MS)
            .setAutoCancel(true)
            .setContentIntent(Notifier.openCard(context, card, card.copy(title = shownTitle, text = shown)))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}
