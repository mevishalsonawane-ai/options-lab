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
 * line for a break the arm skipped for want of room. The words are [LiquidityNotice]'s; this only posts them. All are
 * silent (Boss, 07 Oct 2026): in the shade, with no sound, no buzz and no pop-up.
 *
 * Each event is told once ([once]: by the position and the event, kept across restarts for the day). With "hide figures
 * on the lock screen" on, the text carries no option price and no rupee amount, and the notification is private (the
 * lock screen shows "Unlock to read"). While the phone saves battery the chart is left out and skipped breaks are not told.
 * Information only: nothing here places, changes or times an order.
 */
object LiquidityNotices {
    private const val PREFS = "liquidity.notices"
    private const val KEY = "told"
    private const val IDS_KEY = "ids"
    /** Entries' and exits' ids: 40,000-47,999 (a slot each position, two ids a slot); skipped breaks' 48,000-48,099. */
    private const val ENTRY_IDS = 40_000
    private const val SLOTS = 4_000
    private const val SKIP_IDS = 48_000
    /** Skipped breaks: at most one a book in this many minutes. */
    private const val SKIP_GAP_MIN = 10L
    /** Notification slots are kept this many days back ([entryId]). */
    private const val KEEP_DAYS = 2L

    private val lastSkip = HashMap<String, LocalDateTime>()

    /**
     * The notification ids of a position's entry and exit (the exit's is the entry's + 1): a slot of its own for the day,
     * kept across a restart ([IDS_KEY]: "day|digest|slot", the position as a digest only), so two positions of a day never
     * share one. A new position's slot is the first free one from its hash.
     */
    @Synchronized internal fun entryId(context: Context, day: LocalDate, position: String): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val digest = digest(position)
        // Every day's slots are kept but those older than [KEEP_DAYS] before [day]: a call for another day (the exit of
        // yesterday's position, told after a restart) never drops today's. A slot taken on any kept day is not given again.
        val oldest = day.minusDays(KEEP_DAYS)
        val kept = prefs.getStringSet(IDS_KEY, emptySet()).orEmpty().filterTo(HashSet()) {
            runCatching { !LocalDate.parse(it.substringBefore('|')).isBefore(oldest) }.getOrDefault(false)
        }
        val taken = HashSet<Int>()
        for (k in kept) {
            val parts = k.split('|')
            val slot = parts.getOrNull(2)?.toIntOrNull() ?: continue
            if (parts[0] == day.toString() && parts.getOrNull(1) == digest) return ENTRY_IDS + slot * 2
            taken += slot
        }
        val start = (position.hashCode() and Int.MAX_VALUE) % SLOTS
        val slot = (0 until SLOTS).map { (start + it) % SLOTS }.firstOrNull { it !in taken } ?: start
        kept += "$day|$digest|$slot"
        prefs.edit().putStringSet(IDS_KEY, kept).apply()
        return ENTRY_IDS + slot * 2
    }

    private fun digest(s: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }

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
        // No permission: nothing is marked told (it is told once it can be).
        if (!Notifier.canPost(context)) return
        val day = e.time.toLocalDate()
        if (!once(context, day, "$position|entry")) return
        val h = hide()
        // Liquidity 15+5's paper trades are told silently (Boss, 07 Oct 2026): shown in the shade, no sound, buzz or pop-up.
        // Its fill card is silent too ([Notifier.quietFill]).
        post(context, entryId(context, day, position), Notifier.BUY, "BUY", LiquidityNotice.entry(e, h), chart, silent = true)
    }

    /** An exit: told once for [position] and its exit time (a part sold earlier is its own exit). */
    fun exit(context: Context, position: String, x: LiquidityNotice.Exit, chart: (Int, Int) -> LiquidityNotice.Chart?) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        if (!Notifier.canPost(context)) return
        if (!once(context, x.exitTime.toLocalDate(), "$position|exit|${x.exitTime}")) return
        val h = hide()
        // Silent like the entry (Boss, 07 Oct 2026).
        post(context, entryId(context, x.entryTime.toLocalDate(), position) + 1, Notifier.SELL, "SELL", LiquidityNotice.exit(x, h), chart, silent = true)
    }

    /**
     * A break skipped for want of room ([com.optionslab.engine.orb.LiquidityRules.hasRoom]): only with its switch on, not
     * while saving battery, once per break and at most once a book in [SKIP_GAP_MIN] minutes. Quiet (no sound, low priority).
     * Not a buy or sell: like every other notice, only with "other alerts" on ([AppSettings.otherAlerts], as [Notifier.post]).
     */
    fun skip(context: Context, book: String, bar: LocalDateTime, s: LiquidityNotice.Skip, now: LocalDateTime) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        if (!com.optionslab.app.ira.Automations.on(com.optionslab.app.ira.Automations.Auto.LIQSKIP)) return
        if (saving(context)) return
        if (!Notifier.canPost(context)) return
        if (!runCatching { AppSettings.load().otherAlerts }.getOrDefault(false)) return
        synchronized(this) { lastSkip[book]?.let { if (now.isBefore(it.plusMinutes(SKIP_GAP_MIN))) return } }
        if (!once(context, bar.toLocalDate(), "$book|skip|$bar")) return
        synchronized(this) { lastSkip[book] = now }
        val said = LiquidityNotice.skip(s)
        runCatching { com.optionslab.app.ira.Automations.acted(com.optionslab.app.ira.Automations.Auto.LIQSKIP, said.line) }
        val id = SKIP_IDS + ((book.hashCode() and Int.MAX_VALUE) % 100)
        val b = Notifier.builder(context, Notifier.HEALTH, said.title, said.line, "almanac",
            card = NoticeCard(id, Notifier.HEALTH, said.title, said.body, System.currentTimeMillis(), tab = "almanac"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(said.body))
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        try { NotificationManagerCompat.from(context).notify(id, b.build()) } catch (_: SecurityException) {}
    }

    private fun post(context: Context, id: Int, channel: String, side: String, said: LiquidityNotice.Said,
                     chart: (Int, Int) -> LiquidityNotice.Chart?, silent: Boolean = false) {
        if (!Notifier.canPost(context)) return
        // The plain trade notification (title, the first line collapsed, the whole text expanded), as every fill's.
        val b = Notifier.builder(context, channel, said.title, said.line, "almanac",
            card = NoticeCard(id, channel, said.title, said.body, System.currentTimeMillis(), tab = "almanac"))
            .setContentText(said.line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(said.body))
            // The whole text stays in the extras even with the chart's custom view (accessibility, a watch).
            .addExtras(android.os.Bundle().apply { putCharSequence(NotificationCompat.EXTRA_BIG_TEXT, said.body) })
        // Silent: no sound or buzz, and low priority so it does not pop up over the screen (it still shows in the shade).
        if (silent) b.setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
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
