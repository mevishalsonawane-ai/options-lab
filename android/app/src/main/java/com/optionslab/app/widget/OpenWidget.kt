package com.optionslab.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.optionslab.app.BuildConfig
import com.optionslab.app.MainActivity
import com.optionslab.app.R
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Market
import com.optionslab.app.data.Paper
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.OpenBook
import java.util.Locale

/**
 * The "Open" home-screen widget (the owner's wish, 2026-10-05): today's P&L on top, then only what is open - positions,
 * then orders still pending at Zerodha or in paper. It only draws: no button on it places, changes, exits or cancels
 * anything; a tap opens the app on its positions, which still asks for the PIN or fingerprint.
 *
 * Account figures appear only when the owner turned on "Show my P&L on the widget" (More → Security), the same switch
 * as [IraWidget]'s; when it is off the widget says so and holds nothing (what it kept is dropped from the vault).
 * Home screen only (never the lock screen), and not in the gold build (its receiver is disabled there).
 *
 * Battery: no timer of its own. It is redrawn only when the live watch or the app already has the books in hand
 * ([fromWatch], [fromZerodha], [fromPaper], [fromOrders]); nothing here reads the network. With no widget placed, or the
 * switch off, nothing is kept or drawn. Its figures reach the vault at most once a minute (as [IraWidget]'s do).
 */
class OpenWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = draw(context, manager, ids, true)

    /** The last one was removed from the home screen: what it kept leaves the vault. */
    override fun onDisabled(context: Context) { runCatching { forget() } }

    companion object {
        private const val K_Z = "ow.zerodha"
        private const val K_ZO = "ow.zorders"
        private const val K_P = "ow.paper"
        private const val K_AT = "ow.at"
        private val KEYS = listOf(K_Z, K_ZO, K_P, K_AT)
        private const val MAX_ROWS = 8
        const val OFF = "Turn on 'Show my P&L on the widget' in More → Security"

        private val ROW = intArrayOf(R.id.ow_row0, R.id.ow_row1, R.id.ow_row2, R.id.ow_row3, R.id.ow_row4, R.id.ow_row5, R.id.ow_row6, R.id.ow_row7)
        private val TITLE = intArrayOf(R.id.ow_t0, R.id.ow_t1, R.id.ow_t2, R.id.ow_t3, R.id.ow_t4, R.id.ow_t5, R.id.ow_t6, R.id.ow_t7)
        private val DETAIL = intArrayOf(R.id.ow_d0, R.id.ow_d1, R.id.ow_d2, R.id.ow_d3, R.id.ow_d4, R.id.ow_d5, R.id.ow_d6, R.id.ow_d7)
        private val FIGURE = intArrayOf(R.id.ow_f0, R.id.ow_f1, R.id.ow_f2, R.id.ow_f3, R.id.ow_f4, R.id.ow_f5, R.id.ow_f6, R.id.ow_f7)

        /** Figures newer than the vault copy (as [IraWidget]'s): written at most once a minute, and only when they differ. */
        private val unsaved = HashMap<String, String?>()
        private var savedAt: String? = null
        private var unsavedGen = Int.MIN_VALUE
        /** What the placed widgets show now: the same content is not sent to the launcher again. */
        @Volatile private var drawn: String? = null
        /** The live stream's last Zerodha update (at most every 5 s). */
        @Volatile private var streamAt = 0L

        /** Tests share this process: back to a fresh start. */
        internal fun resetForTest() {
            synchronized(unsaved) { unsaved.clear(); savedAt = null; unsavedGen = Int.MIN_VALUE }
            drawn = null; streamAt = 0L
        }

        private fun fresh() {
            val g = SecurePrefs.generationNow()
            if (g != unsavedGen) { unsaved.clear(); savedAt = null; unsavedGen = g }
        }

        private fun textNow(k: String): String? = synchronized(unsaved) {
            fresh()
            if (unsaved.containsKey(k)) unsaved[k] else SecurePrefs.getString(k)
        }

        private fun ids(context: Context): IntArray =
            runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, OpenWidget::class.java)) }.getOrNull() ?: IntArray(0)

        /** Is at least one on a home screen? (Unknown counts as no: nothing could be drawn anyway.) */
        fun placed(context: Context): Boolean = !BuildConfig.GOLD && ids(context).isNotEmpty()

        private fun allowed(): Boolean = runCatching { AppSettings.load().widgetPnl }.getOrDefault(false)

        /** Drop what it kept (the switch turned off, or the last widget removed). */
        private fun forget() {
            synchronized(unsaved) { fresh(); unsaved.clear(); savedAt = null }
            if (KEYS.any { SecurePrefs.getString(it) != null }) SecurePrefs.putAll(KEYS.associateWith { null })
            drawn = null
        }

        /** The switch in More → Security changed to [on]: redraw now (and when off, keep nothing). */
        fun refresh(context: Context, on: Boolean) {
            if (!placed(context)) { if (!on) runCatching { forget() }; return }
            if (!on) forget()
            draw(context, AppWidgetManager.getInstance(context), ids(context), true, on)
        }

        private fun store(context: Context, values: Map<String, String?>) {
            val placedIds = if (BuildConfig.GOLD) IntArray(0) else ids(context)
            if (placedIds.isEmpty()) return
            if (!allowed()) { if (drawn != "off") draw(context, AppWidgetManager.getInstance(context), placedIds, false, false); return }
            val now = Market.now()
            val at = String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d", now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)
            val m = HashMap<String, String?>(values)
            m[K_AT] = at
            val toVault: Map<String, String?>? = synchronized(unsaved) {
                fresh()
                val all = HashMap<String, String?>(unsaved)
                all.putAll(m)
                if (at != savedAt && all.any { (k, v) -> k != K_AT && v != SecurePrefs.getString(k) }) {
                    savedAt = at
                    unsaved.clear()
                    all
                } else { unsaved.putAll(m); null }
            }
            if (toVault != null) SecurePrefs.putAll(toVault)
            draw(context, AppWidgetManager.getInstance(context), placedIds, false, true)
        }

        fun zerodhaVenue(book: Broker.Positions): OpenBook.Venue = OpenBook.Venue(OpenBook.ZERODHA, book.m2m,
            book.net.filter { it.qty != 0 }.map { OpenBook.Pos(it.symbol, it.qty, it.last.takeIf { l -> l > 0 }, it.pnl) }, primary = true)

        fun paperVenue(snap: Paper.Snapshot, primary: Boolean): OpenBook.Venue = OpenBook.Venue(OpenBook.PAPER, snap.dayPnl,
            snap.positions.positions.filter { it.quantity != 0 }.map { OpenBook.Pos(it.symbol, it.quantity, it.ltp.takeIf { l -> l > 0 }, it.pnl) },
            snap.orders.orders.filter { it.pendingQuantity > 0 }.map { OpenBook.Ord(it.symbol, it.action, it.pendingQuantity, it.priceType, it.price, it.triggerPrice, it.status) },
            primary = primary)

        /**
         * The live watch's pass ([com.optionslab.app.work.PositionCards.refresh]): [live] the app is in Zerodha mode,
         * [loggedIn] with a session today, [book] its positions as read (null = the read failed), [paper] the paper books
         * (null = not read; kept as they were).
         */
        fun fromWatch(context: Context, live: Boolean, loggedIn: Boolean, book: Broker.Positions?, paper: Paper.Snapshot?) {
            if (BuildConfig.GOLD) return
            val z: OpenBook.Venue? = when {
                !live -> null
                !loggedIn -> OpenBook.Venue(OpenBook.ZERODHA, null, problem = "not logged in", primary = true)
                book == null -> OpenBook.Venue(OpenBook.ZERODHA, null, problem = "could not read", primary = true)
                else -> zerodhaVenue(book)
            }
            val m = HashMap<String, String?>()
            m[K_Z] = z?.let { OpenBook.encode(it) }
            if (paper != null) m[K_P] = OpenBook.encode(paperVenue(paper, !live))
            store(context, m)
        }

        /** Zerodha's positions read by the app ([com.optionslab.app.ui.AppModel.loadAccount]). */
        fun fromZerodha(context: Context, book: Broker.Positions) {
            if (BuildConfig.GOLD) return
            store(context, mapOf(K_Z to OpenBook.encode(zerodhaVenue(book))))
        }

        /** Zerodha's positions moved by the live price stream: at most every 5 s, and only in Zerodha mode. */
        fun fromStream(context: Context, book: Broker.Positions) {
            if (BuildConfig.GOLD) return
            val now = System.currentTimeMillis()
            if (now - streamAt < 5_000) return
            streamAt = now
            if (!runCatching { AppSettings.load().live }.getOrDefault(false)) return
            store(context, mapOf(K_Z to OpenBook.encode(zerodhaVenue(book))))
        }

        /** The paper books read by the app ([com.optionslab.app.ui.AppModel.loadPaper]). */
        fun fromPaper(context: Context, snap: Paper.Snapshot) {
            if (BuildConfig.GOLD) return
            val live = runCatching { AppSettings.load().live }.getOrDefault(false)
            store(context, mapOf(K_P to OpenBook.encode(paperVenue(snap, !live))))
        }

        /** Zerodha's order book, whenever something already read it ([Broker.orders]): the ones still working. */
        fun fromOrders(context: Context, rows: List<Broker.OrderRow>) {
            if (BuildConfig.GOLD) return
            val working = rows.filter { it.working }.map { o ->
                OpenBook.Ord(o.symbol, o.side, if (o.pending > 0) o.pending else o.qty, o.type, o.price, o.trigger, o.status, o.variety)
            }
            store(context, mapOf(K_ZO to OpenBook.encodeOrders(Market.today().toString(), working)))
        }

        /** What the widget shows now, from what it kept. */
        internal fun screen(): Pair<OpenBook.Screen, String?> {
            val today = Market.today().toString()
            val z = OpenBook.decode(textNow(K_Z))
            val zOrders = OpenBook.decodeOrders(textNow(K_ZO), today)
            val p = OpenBook.decode(textNow(K_P))
            // Zerodha's orders only beside a read of its positions: an unreadable account shows no stale orders.
            val venues = listOfNotNull(z?.let { if (it.problem == null) it.copy(orders = zOrders) else it }, p)
            val at = textNow(K_AT)?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
            return OpenBook.screen(venues, MAX_ROWS) to at?.let { OpenBook.asOf(it, Market.now().toLocalDateTime()) }
        }

        private fun colour(context: Context, t: OpenBook.Tone): Int = context.getColor(when (t) {
            OpenBook.Tone.GAIN -> R.color.widget_gain
            OpenBook.Tone.LOSS -> R.color.widget_loss
            OpenBook.Tone.PLAIN -> R.color.widget_ink
        })

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray, force: Boolean, show: Boolean = allowed()) {
            if (ids.isEmpty()) return
            val v = RemoteViews(context.packageName, R.layout.widget_open)
            val sig: String
            val figures = listOf(R.id.ow_caption, R.id.ow_pnl, R.id.ow_split, R.id.ow_stamp, R.id.ow_rule)
            if (!show) {
                figures.forEach { v.setViewVisibility(it, View.GONE) }
                ROW.forEach { v.setViewVisibility(it, View.GONE) }
                v.setViewVisibility(R.id.ow_more, View.GONE)
                v.setViewVisibility(R.id.ow_note, View.VISIBLE)
                v.setTextViewText(R.id.ow_note, OFF)
                sig = "off"
            } else {
                val (s, stamp) = screen()
                figures.forEach { v.setViewVisibility(it, View.VISIBLE) }
                v.setTextViewText(R.id.ow_caption, s.caption)
                v.setTextViewText(R.id.ow_pnl, s.headline)
                v.setTextColor(R.id.ow_pnl, colour(context, s.tone))
                if (s.split.isEmpty()) v.setViewVisibility(R.id.ow_split, View.GONE)
                else v.setTextViewText(R.id.ow_split, s.split.joinToString("\n"))
                if (stamp == null) v.setViewVisibility(R.id.ow_stamp, View.GONE) else v.setTextViewText(R.id.ow_stamp, stamp)
                for (i in ROW.indices) {
                    val r = s.rows.getOrNull(i)
                    if (r == null) { v.setViewVisibility(ROW[i], View.GONE); continue }
                    v.setViewVisibility(ROW[i], View.VISIBLE)
                    v.setTextViewText(TITLE[i], r.title)
                    v.setTextViewText(DETAIL[i], r.detail)
                    v.setTextViewText(FIGURE[i], r.figure)
                    v.setTextColor(FIGURE[i], colour(context, r.tone))
                }
                val more = s.more
                if (more == null) v.setViewVisibility(R.id.ow_more, View.GONE)
                else { v.setViewVisibility(R.id.ow_more, View.VISIBLE); v.setTextViewText(R.id.ow_more, "$more · tap to see all") }
                val note = s.note
                if (note == null) v.setViewVisibility(R.id.ow_note, View.GONE)
                else { v.setViewVisibility(R.id.ow_note, View.VISIBLE); v.setTextViewText(R.id.ow_note, note) }
                sig = s.toString() + "|" + stamp
            }
            if (!force && sig == drawn) return
            val open = PendingIntent.getActivity(context, 29, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "trade")
                .putExtra(MainActivity.EXTRA_NONCE, MainActivity.nonce()), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.ow_root, open)
            if (runCatching { manager.updateAppWidget(ids, v) }.isSuccess) drawn = sig
        }
    }
}
