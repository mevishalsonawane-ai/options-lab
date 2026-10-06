package com.optionslab.app.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.optionslab.app.R
import com.optionslab.app.data.AppSettings
import com.optionslab.ira.LiquidityNotice
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Liquidity 15+5's own trade notifications (Boss, 06 Oct 2026): the entry and the exit in words with a small chart of the
 * book's last bars, and - only with its switch on (Market alerts → "Skipped Liquidity breaks", off by default) - a quiet
 * line for a break the arm skipped for want of room. The words are [LiquidityNotice]'s; this only posts them.
 *
 * Each event is told once ([once]: by the position and the event, kept across restarts for the day). With "hide figures
 * on the lock screen" on, the text carries no option price and no rupee amount, and the notification is private (the
 * lock screen shows "Unlock to read"). While the phone saves battery the chart is left out and skipped breaks are not told.
 * Information only: nothing here places, changes or times an order.
 */
object LiquidityNotices {
    private const val PREFS = "liquidity.notices"
    private const val KEY = "told"
    private const val ENTRY_IDS = 40_000
    private const val SKIP_IDS = 48_000
    /** Skipped breaks: at most one a book in this many minutes. */
    private const val SKIP_GAP_MIN = 10L

    private val lastSkip = HashMap<String, LocalDateTime>()

    /** The notification ids of a position's entry and exit (the exit's is the entry's + 1). */
    fun entryId(position: String): Int = ENTRY_IDS + ((position.hashCode() and Int.MAX_VALUE) % 3_000) * 2

    /**
     * True the first time [event] is seen on [day] (then remembered, also across a restart): the dedupe of every notice.
     * Kept as hashes only, so the file holds no symbol or time.
     */
    @Synchronized internal fun once(context: Context, day: LocalDate, event: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Only today's are kept: yesterday's drop out on the first notice of a new day.
        val told = prefs.getStringSet(KEY, emptySet()).orEmpty().filterTo(HashSet()) { it.startsWith("$day|") }
        if (!told.add("$day|${event.hashCode()}")) return false
        prefs.edit().putStringSet(KEY, told).apply()
        return true
    }

    @Synchronized internal fun resetForTest() { lastSkip.clear() }

    private fun hide(): Boolean = runCatching { AppSettings.load().hideAmountsOnLockScreen }.getOrDefault(true)

    /** Battery saver on (Android's, or Jarvis's low-battery saver): no chart, no skipped-break notices. */
    private fun saving(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false) ||
            runCatching { Battery.saving(context) }.getOrDefault(false)

    /** An entry: told once for [position] (the arm, the contract and the entry time). [chart] is drawn when given. */
    fun entry(context: Context, position: String, e: LiquidityNotice.Entry, chart: (Int, Int) -> LiquidityNotice.Chart?) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        if (!once(context, e.time.toLocalDate(), "$position|entry")) return
        val h = hide()
        post(context, entryId(position), Notifier.BUY, "BUY", LiquidityNotice.entry(e, h), chart)
    }

    /** An exit: told once for [position] and its exit time (a part sold earlier is its own exit). */
    fun exit(context: Context, position: String, x: LiquidityNotice.Exit, chart: (Int, Int) -> LiquidityNotice.Chart?) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        if (!once(context, x.exitTime.toLocalDate(), "$position|exit|${x.exitTime}")) return
        val h = hide()
        post(context, entryId(position) + 1, Notifier.SELL, "SELL", LiquidityNotice.exit(x, h), chart)
    }

    /**
     * A break skipped for want of room ([com.optionslab.engine.orb.LiquidityRules.hasRoom]): only with its switch on, not
     * while saving battery, once per break and at most once a book in [SKIP_GAP_MIN] minutes. Quiet (no sound, low priority).
     */
    fun skip(context: Context, book: String, bar: LocalDateTime, s: LiquidityNotice.Skip, now: LocalDateTime) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        if (!com.optionslab.app.ira.Automations.on(com.optionslab.app.ira.Automations.Auto.LIQSKIP)) return
        if (saving(context)) return
        synchronized(this) { lastSkip[book]?.let { if (now.isBefore(it.plusMinutes(SKIP_GAP_MIN))) return } }
        if (!once(context, bar.toLocalDate(), "$book|skip|$bar")) return
        synchronized(this) { lastSkip[book] = now }
        val said = LiquidityNotice.skip(s)
        runCatching { com.optionslab.app.ira.Automations.acted(com.optionslab.app.ira.Automations.Auto.LIQSKIP, said.line) }
        if (!Notifier.canPost(context)) return
        val id = SKIP_IDS + ((book.hashCode() and Int.MAX_VALUE) % 100)
        val b = Notifier.builder(context, Notifier.HEALTH, said.title, said.line, "strategy",
            card = NoticeCard(id, Notifier.HEALTH, said.title, said.body, System.currentTimeMillis(), tab = "strategy"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(said.body))
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        try { NotificationManagerCompat.from(context).notify(id, b.build()) } catch (_: SecurityException) {}
    }

    private fun post(context: Context, id: Int, channel: String, side: String, said: LiquidityNotice.Said,
                     chart: (Int, Int) -> LiquidityNotice.Chart?) {
        if (!Notifier.canPost(context)) return
        // The plain trade notification (title, the first line collapsed, the whole text expanded), as every fill's.
        val b = Notifier.builder(context, channel, said.title, said.line, "strategy",
            card = NoticeCard(id, channel, said.title, said.body, System.currentTimeMillis(), tab = "strategy"))
            .setContentText(said.line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(said.body))
        // Expanded: the words over the mini-chart (left out while saving battery, or when there are no bars to draw).
        if (!saving(context)) runCatching {
            val d = context.resources.displayMetrics.density.coerceIn(1f, 2f)
            val geo = chart((CHART_W_DP * d).toInt(), (CHART_H_DP * d).toInt()) ?: return@runCatching
            val bmp = draw(geo, d)
            val big = RemoteViews(context.packageName, R.layout.notif_liquidity_big).apply {
                setTextViewText(R.id.title, said.title)
                setTextViewText(R.id.line, said.body)
                setImageViewBitmap(R.id.chart, bmp)
                setContentDescription(R.id.chart, "$side chart: the book's last bars, the broken level and the target")
            }
            b.setStyle(NotificationCompat.DecoratedCustomViewStyle()).setCustomBigContentView(big)
        }
        try { NotificationManagerCompat.from(context).notify(id, b.build()) } catch (_: SecurityException) {}
    }

    const val CHART_W_DP = 300
    const val CHART_H_DP = 96

    // Theme-independent: a dark panel with its own inks, readable on a light or a dark notification shade alike.
    private const val PANEL = 0xFF1C1F24.toInt()
    private const val UP = 0xFF00A86B.toInt()
    private const val DOWN = 0xFFE0322B.toInt()
    private const val LEVEL = 0xFFE8B04A.toInt()
    private const val TARGET = 0xFF5AA9E6.toInt()
    private const val ENTRY = 0xFFFFFFFF.toInt()
    private const val EXIT = 0xFFFF8A65.toInt()

    /** Paints [c] (the geometry is [LiquidityNotice.chart]'s): candles, the level (solid), the target (dashed), the markers. */
    internal fun draw(c: LiquidityNotice.Chart, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        fill.color = PANEL
        cv.drawRoundRect(RectF(0f, 0f, c.width.toFloat(), c.height.toFloat()), 8 * density, 8 * density, fill)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density.coerceAtLeast(1f) }
        for (k in c.candles) {
            val col = if (k.up) UP else DOWN
            line.color = col
            cv.drawLine(k.x, k.high, k.x, k.low, line)
            fill.color = col
            val half = c.candleWidth / 2
            cv.drawRect(k.x - half, k.top, k.x + half, maxOf(k.bottom, k.top + 1f), fill)
        }
        c.level?.let { y -> line.color = LEVEL; line.pathEffect = null; cv.drawLine(0f, y, c.width.toFloat(), y, line) }
        c.target?.let { y ->
            line.color = TARGET; line.pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
            cv.drawLine(0f, y, c.width.toFloat(), y, line); line.pathEffect = null
        }
        val r = 3.5f * density
        c.entry?.let { m -> fill.color = ENTRY; cv.drawCircle(m.x, m.y, r, fill) }
        c.exit?.let { m -> fill.color = EXIT; cv.drawCircle(m.x, m.y, r, fill) }
        return bmp
    }
}
