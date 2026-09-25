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
    /** The three notifications the owner gets by default: a buy filled, a sell filled, an order waiting for approval. */
    const val BUY = "orders.buy"
    const val SELL = "orders.sell"
    const val APPROVAL = "orders.approval"
    private val ALWAYS = setOf(BUY, SELL, APPROVAL)

    const val ID_LIVE = 1001
    const val ID_HARVEST = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(BUY, "Buy orders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A buy order was filled (paper or Zerodha), and by which strategy or by hand"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
            NotificationChannel(SELL, "Sell orders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A sell order was filled (paper or Zerodha), and by which strategy or by hand"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
            NotificationChannel(APPROVAL, "Order approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An order or a strategy start waiting for you to approve it"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                enableVibration(true)
            },
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
            .setPriority(if (channel == RISK || channel in ALWAYS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (channel == RISK) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
    }

    /**
     * A buy or sell filled: "BUY filled · Paper · ORB" / "SELL filled · Live · Manual". It lands on the
     * position's own card (PositionCards), which the market watch then keeps live with its P&L and a Close button.
     */
    fun orderFilled(context: Context, action: String, qty: Int, symbol: String, price: Double, venue: String, source: String?) {
        val buy = action.equals("BUY", ignoreCase = true)
        val headline = "${if (buy) "BUY" else "SELL"} filled · $venue · ${source ?: "Manual"}"
        val line = "$qty $symbol @ ${String.format(java.util.Locale.ENGLISH, "%.2f", price)}"
        Alerts.post(line, Alerts.Kind.SUCCESS, headline)
        if (!canPost(context)) return
        PositionCards.card(context, if (venue == "Paper") "Paper" else "Live", symbol, if (buy) qty else -qty, price, price, 0.0,
            alert = true, headline = headline)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(context: Context, id: Int, channel: String, title: String, text: String, tab: String? = null) {
        // Everything notified also drops in at the top of the app when it is open (green / red).
        Alerts.post(text, when (channel) {
            BUY, SELL -> Alerts.Kind.SUCCESS
            RISK -> Alerts.Kind.ERROR
            APPROVAL -> if (Alerts.classify("$title $text") == Alerts.Kind.ERROR) Alerts.Kind.ERROR else Alerts.Kind.INFO
            else -> Alerts.classify("$title $text")
        }, title, throttle = true)
        if (!canPost(context)) return
        // Only buy / sell / approval notifications unless the owner turned the others on (More → Schedules).
        if (channel !in ALWAYS && !runCatching { AppSettings.load().otherAlerts }.getOrDefault(false)) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder(context, channel, title, text, tab).build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; nothing to do.
        }
    }
}
