package com.optionslab.app.data

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The Signal tester's notification reader (15 Oct 2026; [SignalTesterHost]). SHADOW ONLY: it never trades.
 *
 * Off by default: the component is disabled in the manifest and enabled only while Boss's "Read Telegram notifications" switch
 * is on; even then Android delivers nothing until Boss grants IraAlgo notification access himself in the system screen.
 * What it reads: ONLY notifications posted by Telegram's apps ([SignalTesterHost.TELEGRAM]) and, of those, ONLY the chats whose
 * title is on Boss's allow-list. Every other notification - any other app, any other chat, a group summary - is dropped at
 * once, in memory, and never stored or logged. Each message keeps its own post time, to the second. The check is in memory
 * (no disk, no network), and the recording itself runs in the host's background job, so the main thread never waits.
 * It never replies, dismisses, opens or changes a notification.
 */
class TelegramCallListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName !in SignalTesterHost.TELEGRAM) return
        runCatching { read(s.packageName, s.notification, s.postTime) }
    }

    companion object {
        /**
         * A Telegram notification read: its chat's title and its messages (text, epoch ms), handed to the host - which drops it
         * unless the title is allow-listed. A group summary carries no message of its own and is skipped.
         */
        fun read(pkg: String, n: Notification?, postTime: Long) {
            if (pkg !in SignalTesterHost.TELEGRAM || n == null) return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val ex = n.extras ?: return
            val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim()
            if (SignalTesterHost.accepts(pkg, title) == false) return
            SignalTesterHost.fromNotification(pkg, title, messages(ex, postTime))
        }

        /** The messages in [ex]: MessagingStyle's lines with their own times, else the big or plain text at [postTime]. */
        @Suppress("DEPRECATION")
        internal fun messages(ex: Bundle, postTime: Long): List<Pair<String, Long>> {
            val arr: Array<Parcelable>? = runCatching { ex.getParcelableArray(Notification.EXTRA_MESSAGES) }.getOrNull()
            val lines = arr.orEmpty().mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                text to (b.getLong("time").takeIf { it > 0 } ?: postTime)
            }
            if (lines.isNotEmpty()) return lines
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            return if (text.isNullOrBlank()) emptyList() else listOf(text to postTime)
        }
    }
}
