package com.optionslab.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.optionslab.app.MainActivity
import com.optionslab.app.R
import com.optionslab.app.data.AppSettings

/**
 * Four channels, so each kind can be silenced on its own:
 *   live      the ongoing market-hours watch (quiet, updated in place)
 *   risk      breakeven proximity and price alarms (loud)
 *   schedule  entry reminders, tickets, settlements, harvests
 *   health    the kill-condition verdict changing
 *
 * Every notification is PRIVATE: on a locked screen only a neutral line shows,
 * never a strike, a credit or a rupee figure.
 */
object Notifier {
    const val LIVE = "live"
    const val RISK = "risk"
    const val SCHEDULE = "schedule"
    const val HEALTH = "health"

    const val ID_LIVE = 1001
    const val ID_HARVEST = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(LIVE, "Market watch", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Index levels and your open paper ticket during market hours"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
            },
            NotificationChannel(RISK, "Risk alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The index nearing your strike or breakeven, and your price alarms"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                enableVibration(true)
            },
            NotificationChannel(SCHEDULE, "Schedule", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Entry reminders, paper tickets, settlements and the nightly harvest"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
            NotificationChannel(HEALTH, "Strategy health", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a kill condition changes state"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        ))
    }

    fun openApp(context: Context, tab: String? = null): PendingIntent = PendingIntent.getActivity(
        context, tab?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { tab?.let { putExtra(MainActivity.EXTRA_TAB, it) } },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun publicVersion(context: Context, channel: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_art)
            .setContentTitle("IraAlgo")
            .setContentText("Unlock to read")
            .build()

    fun builder(context: Context, channel: String, title: String, text: String, tab: String? = null): NotificationCompat.Builder {
        val hide = AppSettings.load().hideAmountsOnLockScreen
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_art)
            .setColor(0xFFB08D57.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, tab))
            .setAutoCancel(true)
            .setVisibility(if (hide) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion(context, channel))
            .setPriority(if (channel == RISK) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (channel == RISK) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(context: Context, id: Int, channel: String, title: String, text: String, tab: String? = null) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder(context, channel, title, text, tab).build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; nothing to do.
        }
    }
}
