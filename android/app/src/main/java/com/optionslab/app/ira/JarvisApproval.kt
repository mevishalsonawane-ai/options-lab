package com.optionslab.app.ira

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.optionslab.app.R
import com.optionslab.app.work.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A news trade to approve (Jarvis): a heads-up pop-up with the news, its impact and the order, and Approve /
 * Reject buttons. It stays until the owner answers - by a button, by saying yes or no to Jarvis, or on the Ira
 * screen - and then hides itself; unanswered, it lapses with the trade after 10 minutes. The buttons need the phone
 * unlocked, and they only reach the app's own receiver (not exported).
 */
object JarvisApproval {
    const val ACTION_APPROVE = "com.optionslab.app.ira.APPROVE"
    const val ACTION_REJECT = "com.optionslab.app.ira.REJECT"
    const val EXTRA_ID = "id"

    private fun notificationId(id: Long) = 7500 + Math.floorMod(id, 90L).toInt()

    fun show(context: Context, id: Long, title: String, text: String) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        fun button(action: String, label: String) = NotificationCompat.Action.Builder(0, label,
            PendingIntent.getBroadcast(context, (id xor action.hashCode().toLong()).toInt(),
                Intent(context, JarvisActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAuthenticationRequired(true)
            .build()
        val n = NotificationCompat.Builder(context, Notifier.POPUP)
            .setSmallIcon(R.drawable.ic_notification_art)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // On the lock screen only how many requests wait - never the trade, the symbol or an amount.
            .setPublicVersion(lockedVersion(context))
            .setTimeoutAfter(IraHub.NEWS_ANSWER_MS)
            .setOngoing(true)
            // Tapped (not a button): the app opens with the trade's whole text over it and the chat's own Approve / Reject
            // (the fingerprint for a live one), or - once answered or lapsed - only that it is no longer waiting.
            .setContentIntent(Notifier.openCard(context, com.optionslab.app.work.NoticeCard(notificationId(id), "approval", title, text,
                System.currentTimeMillis(), tab = "almanac", action = id)))
            .addAction(button(ACTION_APPROVE, "Approve"))
            .addAction(button(ACTION_REJECT, "Reject"))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(id), n) }
    }

    /** What a locked phone shows of a request's notification: the count of requests waiting, nothing else. */
    internal fun lockedVersion(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, Notifier.POPUP)
            .setSmallIcon(R.drawable.ic_notification_art)
            .setContentTitle(com.optionslab.ira.Requests.lockedLine(runCatching { IraHub.state.value.pending.size }.getOrDefault(1).coerceAtLeast(1)))
            .build()

    fun hide(context: Context, id: Long) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId(id)) }
    }
}

/** Approve / Reject on [JarvisApproval]'s pop-up. Not exported: only the app's own buttons reach it. */
class JarvisActionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        val id = intent.getLongExtra(JarvisApproval.EXTRA_ID, 0L)
        JarvisApproval.hide(context, id)
        val done = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    JarvisApproval.ACTION_APPROVE -> {
                        val r = IraHub.confirm(id) ?: "That news trade had already lapsed; nothing was placed."
                        JarvisPopup.show(context, "News trade", r)
                    }
                    JarvisApproval.ACTION_REJECT -> IraHub.cancelAction(id)
                }
            } finally { done.finish() }
        }
    }
}
