package com.optionslab.app.work

import kotlinx.coroutines.launch
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.RemoteViews
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
 * With "hide amounts on lock screen" on (the default), every notification is PRIVATE: on a
 * locked screen only a neutral line shows, never a strike, a credit or a rupee figure. With it
 * off they are PUBLIC and show in full on the lock screen.
 */
object Notifier {
    /** The ongoing watch (a foreground service must show one): minimum importance, so it stays collapsed with no status-bar icon. */
    const val LIVE = "watch.quiet"
    /**
     * IraGoldAlgo's always-on service: Android requires a notification for it, so it goes to a channel created switched
     * OFF - nothing is shown (the owner wants buy / sell notifications only); the app then appears only under the
     * system's "Active apps".
     */
    const val GOLD_BG = "gold.background"
    const val RISK = "risk"
    const val SCHEDULE = "schedule"
    const val HEALTH = "health"
    /** The three notifications the owner gets by default: a buy filled, a sell filled, an order waiting for approval. */
    const val BUY = "orders.buy"
    const val SELL = "orders.sell"
    const val APPROVAL = "orders.approval"
    /** A strategy Jarvis found and backtested, waiting for the owner's approval. */
    const val IRA = "ira.strategies"
    /** Jarvis only: the line shown while Jarvis listens for its name. */
    const val VOICE = "ira.voice"
    /** Jarvis only: Jarvis's short pop-ups (heads-up, gone after a few seconds). */
    const val POPUP = "ira.popup"
    private val ALWAYS = setOf(BUY, SELL, APPROVAL, IRA)

    const val ID_LIVE = 1001
    const val ID_HARVEST = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        // The old "live" channel showed index levels at low importance; its successor is LIVE ("watch").
        // Deleting a channel a running foreground service still uses throws (SecurityException): never let that stop the app.
        runCatching { nm.deleteNotificationChannel("live") }
        // The watch's visible channel ("watch", minimum importance) is replaced by one created switched off: the owner
        // wants buy / sell / approval notifications, not the ongoing line (2026-10-02). The watch itself is unchanged.
        runCatching { nm.deleteNotificationChannel("watch") }
        if (com.optionslab.app.BuildConfig.GOLD) {
            // IraGoldAlgo: buys and sells, and the silent line Android requires for the always-on service - nothing else.
            listOf(APPROVAL, RISK, SCHEDULE, HEALTH, LIVE).forEach { runCatching { nm.deleteNotificationChannel(it) } }
            nm.createNotificationChannels(listOf(
                NotificationChannel(BUY, "Buy", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A paper buy by a gold arm"; lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(SELL, "Sell", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A paper sell by a gold arm"; lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(GOLD_BG, "Running in the background", NotificationManager.IMPORTANCE_NONE).apply {
                    description = "Off: the background checks run without showing anything. Only buys and sells are notified."
                    lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                    setShowBadge(false); setSound(null, null); enableVibration(false)
                },
            ))
            return
        }
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
            NotificationChannel(LIVE, "Order watch", NotificationManager.IMPORTANCE_NONE).apply {
                description = "Off: the background watch of your orders, positions and strategies runs during market hours without showing anything"
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
            NotificationChannel(IRA, "Jarvis strategies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A strategy Jarvis found in a pattern and backtested, with its results, waiting for your approval"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        ))
        if (com.optionslab.app.BuildConfig.JARVIS) nm.createNotificationChannel(
            NotificationChannel(VOICE, "Jarvis in the background", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Jarvis listens for its name or downloads its model, with a Stop or Cancel button. What it hears stays on the phone."
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                setShowBadge(false); setSound(null, null); enableVibration(false)
            })
        if (com.optionslab.app.BuildConfig.JARVIS) nm.createNotificationChannel(
            NotificationChannel(POPUP, "Jarvis pop-ups", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Jarvis's short messages at the top of the screen: alerts, results, the morning check. They hide after a few seconds."
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                setShowBadge(false); setSound(null, null); enableVibration(false)
            })
    }

    fun openApp(context: Context, tab: String? = null): PendingIntent = PendingIntent.getActivity(
        context, tab?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { tab?.let { putExtra(MainActivity.EXTRA_TAB, it) }; putExtra(MainActivity.EXTRA_NONCE, MainActivity.nonce()) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun publicVersion(context: Context, channel: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_art)
            .setContentTitle("IraAlgo")
            .setContentText("Unlock to read")
            .build()

    /**
     * [side] ("BUY" / "SELL" / "LONG" / "SHORT") makes it a trade notification: a green or red tile with the word, drawn
     * as an image (the same size on every phone, whatever its font setting), 40% of the width, the details beside it.
     */
    fun builder(context: Context, channel: String, title: String, text: String, tab: String? = null, side: String? = null): NotificationCompat.Builder {
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
            .apply { if (side != null) runCatching { tradeViews(context, side, title, text) } }
    }

    private fun NotificationCompat.Builder.tradeViews(context: Context, side: String, title: String, text: String) {
        fun views(layout: Int, hDp: Int, firstLine: Boolean) = RemoteViews(context.packageName, layout).apply {
            setImageViewBitmap(R.id.side, tile(context, side, 144, hDp))
            setContentDescription(R.id.side, side)
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.line, if (firstLine) text.substringBefore('\n') else text)
        }
        setStyle(NotificationCompat.DecoratedCustomViewStyle())
        setCustomContentView(views(R.layout.notif_trade, 44, true))
        setCustomBigContentView(views(R.layout.notif_trade_big, 104, false))
    }

    /** Green for a buy / long, red for a sell / short. */
    internal fun sideColor(side: String): Int =
        if (side.uppercase() in setOf("SELL", "SHORT")) 0xFFE0322B.toInt() else 0xFF00A86B.toInt()

    /**
     * The [side] tile, [wDp] x [hDp]: a rounded block of its colour with the word in white, as large as fits. Drawn at
     * no more than 2 px a dp, so the notification stays small (it is scaled to the screen).
     */
    internal fun tile(context: Context, side: String, wDp: Int, hDp: Int): Bitmap {
        val d = context.resources.displayMetrics.density.coerceIn(1f, 2f)
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sideColor(side) }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 10 * d, 10 * d, bg)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; textSize = h * 0.62f
        }
        val word = side.uppercase()
        val fit = w * 0.84f / ink.measureText(word).coerceAtLeast(1f)
        if (fit < 1f) ink.textSize *= fit
        val m = ink.fontMetrics
        c.drawText(word, w / 2f, h / 2f - (m.ascent + m.descent) / 2, ink)
        return bmp
    }

    /**
     * A buy or sell filled: "BUY filled · Paper · Strategy: ORB" / "SELL filled · Live · Manual · Chart". [source] is
     * the order's label ([com.optionslab.app.data.Origins]; null for a hand order from an unnamed screen). It lands on the
     * position's own card (PositionCards), which the market watch then keeps live with its P&L and a Close button.
     */
    fun orderFilled(context: Context, action: String, qty: Int, symbol: String, price: Double, venue: String, source: String?, orderId: String? = null) {
        val buy = action.equals("BUY", ignoreCase = true)
        val headline = "${if (buy) "BUY" else "SELL"} filled · $venue · ${com.optionslab.app.data.Origins.display(source ?: com.optionslab.app.data.Origins.MANUAL).first}"
        // The order's id, so the fill can be found in the order book ("order #a1b2c3d4").
        val line = "$qty $symbol @ ${String.format(java.util.Locale.ENGLISH, "%.2f", price)}" +
            (com.optionslab.app.data.Origins.shortId(orderId)?.let { " · order $it" } ?: "")
        Alerts.post(line, Alerts.Kind.SUCCESS, headline)
        if (!canPost(context)) return
        val card = if (venue == "Paper") "Paper" else "Live"
        // A fill that squared the position off (an exit, a stop, a square-off) takes the card down instead.
        // (Only when the paper book holds that contract and it is now flat; no row at all is not a close.)
        if (venue == "Paper" && runCatching { com.optionslab.app.data.Paper.state.positions.filter { it.symbol == symbol }
                .takeIf { it.isNotEmpty() }?.sumOf { it.quantity } }.getOrNull() == 0) {
            PositionCards.dismiss(context, card, symbol); return
        }
        PositionCards.card(context, card, symbol, if (buy) qty else -qty, price, price, 0.0, alert = true, headline = headline)
        // Zerodha: read the book again shortly, so a fill that closed the position takes its card down.
        if (venue != "Paper") kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(2_000); runCatching { PositionCards.refresh(context) }
        }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(context: Context, id: Int, channel: String, title: String, text: String, tab: String? = null) {
        // IraGoldAlgo notifies buys and sells only (the owner's choice, 2026-10-02); anything else is never posted there.
        if (com.optionslab.app.BuildConfig.GOLD && channel != BUY && channel != SELL) return
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
            NotificationManagerCompat.from(context).notify(id, builder(context, channel, title, text, tab,
                side = when (channel) { BUY -> "BUY"; SELL -> "SELL"; else -> null }).build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; nothing to do.
        }
    }
}
