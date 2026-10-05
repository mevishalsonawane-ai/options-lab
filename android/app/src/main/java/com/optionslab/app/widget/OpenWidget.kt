package com.optionslab.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.optionslab.app.BuildConfig
import com.optionslab.app.MainActivity
import com.optionslab.app.R
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.DailyPnl
import com.optionslab.app.data.Market
import com.optionslab.app.data.Paper
import com.optionslab.app.data.PnlTracker
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.OpenBook
import java.time.LocalDateTime
import java.util.Locale

/**
 * The "Open" home-screen widget (the owner's wish, 2026-10-05): today's P&L on top, then only what is open - positions,
 * then orders still pending at Zerodha or in paper. It only draws: no button on it places, changes, exits or cancels
 * anything; a tap opens the app on its positions, which still asks for the PIN or fingerprint.
 *
 * Account figures appear only when the owner turned on "Show my P&L on the widget" (More → Security), the same switch
 * as [IraWidget]'s; when it is off the widget says so and holds nothing (what it kept is dropped from the vault). The
 * switch is held in memory ([enabled]) the moment it moves, before the settings file is saved, and every vault write is
 * checked against it under the same lock as [forget]: once it is off, no figure can land in the vault or be drawn.
 * Home screen only (never the lock screen), and not in the gold build (its receiver is disabled there).
 *
 * Battery: no timer of its own. It is redrawn only when the live watch or the app already has the books in hand
 * ([fromWatch], [fromZerodha], [fromPaper], [fromOrders]), and filled once from what the phone already holds - no
 * network - when it is placed or updated, the switch is turned on, the app starts or the phone restarts ([fillLocal]:
 * the paper books as last saved, Zerodha's P&L as the app recorded it today); the only read made for it is the live watch's one order-book
 * read a pass while it shows Zerodha orders still working ([wantsOrders]; with the screen off, about every 5 minutes).
 * With no widget placed, or the switch off, nothing is kept, read or drawn. Its figures reach the vault at most once a minute (as [IraWidget]'s do), and the order
 * path never waits on it ([fromOrdersSoon]).
 */
class OpenWidget : AppWidgetProvider() {
    /**
     * Placed, or the launcher asks again: drawn at once from what it kept, then filled from what the phone already holds
     * ([fillLocal], off the main thread; no network), so a widget placed in the evening shows the day's P&L straight away
     * instead of waiting for the app or a market-hours watch pass.
     */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runCatching { draw(context, manager, ids, true) }
        val pending = runCatching { goAsync() }.getOrNull()
        fillSoon(context) { runCatching { pending?.finish() } }
    }

    /** The first one was placed: filled from the phone at once (as [onUpdate]). */
    override fun onEnabled(context: Context) {
        val pending = runCatching { goAsync() }.getOrNull()
        fillSoon(context) { runCatching { pending?.finish() } }
    }

    /** Resized: as many rows as fit, so the "+N more" line is never cut off. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        runCatching { draw(context, appWidgetManager, ids(context), true) }
    }

    /** The last one was removed from the home screen: what it kept leaves the vault (off the main thread). */
    override fun onDisabled(context: Context) { later { forget() } }

    companion object {
        private const val K_Z = "ow.zerodha"
        private const val K_ZO = "ow.zorders"
        private const val K_P = "ow.paper"
        /** The shared stamp of earlier versions: no longer written (each account carries its own), still cleared. */
        private const val K_AT = "ow.at"
        private val KEYS = listOf(K_Z, K_ZO, K_P, K_AT)
        private const val MAX_ROWS = 8
        /** Conservative heights (dp) for fitting rows: everything above and below the rows, and one row. */
        private const val HEAD_DP = 160
        private const val ROW_DP = 36
        const val OFF = "Turn on 'Show my P&L on the widget' in More → Security"

        private val ROW = intArrayOf(R.id.ow_row0, R.id.ow_row1, R.id.ow_row2, R.id.ow_row3, R.id.ow_row4, R.id.ow_row5, R.id.ow_row6, R.id.ow_row7)
        private val TITLE = intArrayOf(R.id.ow_t0, R.id.ow_t1, R.id.ow_t2, R.id.ow_t3, R.id.ow_t4, R.id.ow_t5, R.id.ow_t6, R.id.ow_t7)
        private val DETAIL = intArrayOf(R.id.ow_d0, R.id.ow_d1, R.id.ow_d2, R.id.ow_d3, R.id.ow_d4, R.id.ow_d5, R.id.ow_d6, R.id.ow_d7)
        private val FIGURE = intArrayOf(R.id.ow_f0, R.id.ow_f1, R.id.ow_f2, R.id.ow_f3, R.id.ow_f4, R.id.ow_f5, R.id.ow_f6, R.id.ow_f7)

        /** Figures newer than the vault copy (as [IraWidget]'s): written at most once a minute, and only when they differ. Also the lock every vault write and [forget] run under. */
        private val unsaved = HashMap<String, String?>()
        private var savedAt: String? = null
        private var unsavedGen = Int.MIN_VALUE
        /** What the placed widgets show now: the same content is not sent to the launcher again. */
        @Volatile private var drawn: String? = null
        /** The live stream's last Zerodha update (at most every 5 s). */
        @Volatile private var streamAt = 0L
        /** When Zerodha's order book was last read (by anyone, [fromOrders]); 0 = not yet. Paces [wantsOrders] with the screen off. */
        @Volatile private var ordersAt = 0L
        /** The screen's state on [wantsOrders]' last look (null = not looked yet): screen-on after off reads the book. */
        @Volatile private var screenWasOn: Boolean? = null

        /**
         * "Show my P&L on the widget" as last set ([refresh] sets it at once; the settings file is saved later), or null
         * until first read from the settings. Changed only under [flagLock], which is never held across any I/O.
         */
        @Volatile private var enabled: Boolean? = null
        private val flagLock = Any()
        /** One redraw at a time, so a redraw with figures can never land after the switch's redraw without them. */
        private val drawLock = Any()

        /** The switch's vault work and redraw, and Zerodha's order book, off the caller's thread, one at a time and in order. */
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "open-widget").apply { isDaemon = true } }
        /** Tests: run that work on the caller's thread instead. */
        @Volatile internal var inlineForTest = false

        /** [work] on the widget's thread; [done] always runs after it (or at once when it could not be handed over). */
        private fun later(done: (() -> Unit)? = null, work: () -> Unit) {
            if (inlineForTest) { runCatching { work() }; done?.invoke(); return }
            val queued = runCatching { worker.execute { try { runCatching { work() } } finally { done?.invoke() } } }.isSuccess
            if (!queued) done?.invoke()
        }

        /** Tests share this process: back to a fresh start. */
        internal fun resetForTest() {
            runCatching { worker.submit(Runnable {}).get(5, java.util.concurrent.TimeUnit.SECONDS) }
            synchronized(unsaved) { unsaved.clear(); savedAt = null; unsavedGen = Int.MIN_VALUE }
            synchronized(flagLock) { enabled = null }
            drawn = null; streamAt = 0L; ordersAt = 0L; screenWasOn = null; inlineForTest = false
        }

        /** Under [unsaved]'s lock: a wiped (or re-opened) vault drops what was held, the switch included (read again). */
        private fun fresh() {
            val g = SecurePrefs.generationNow()
            if (g != unsavedGen) {
                unsaved.clear(); savedAt = null
                if (unsavedGen != Int.MIN_VALUE) synchronized(flagLock) { enabled = null }
                unsavedGen = g
            }
        }

        private fun textNow(k: String): String? = synchronized(unsaved) {
            fresh()
            if (unsaved.containsKey(k)) unsaved[k] else SecurePrefs.getString(k)
        }

        private fun ids(context: Context): IntArray =
            runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, OpenWidget::class.java)) }.getOrNull() ?: IntArray(0)

        /** Is at least one on a home screen? (Unknown counts as no: nothing could be drawn anyway.) */
        fun placed(context: Context): Boolean = !BuildConfig.GOLD && ids(context).isNotEmpty()

        /** The switch: as last set, else as saved (read once; a switch moved meanwhile wins). */
        private fun allowed(): Boolean {
            enabled?.let { return it }
            val saved = runCatching { AppSettings.load().widgetPnl }.getOrDefault(false)
            synchronized(flagLock) {
                if (enabled == null) enabled = saved
                return enabled == true
            }
        }

        /** Drop what it kept (the switch turned off, or the last widget removed); under the lock every vault write takes. */
        private fun forget() {
            val held = synchronized(unsaved) {
                fresh()
                val inMemory = unsaved.isNotEmpty()
                unsaved.clear(); savedAt = null
                val inVault = KEYS.any { SecurePrefs.getString(it) != null }
                if (inVault) SecurePrefs.putAll(KEYS.associateWith { null })
                inMemory || inVault
            }
            // Something was dropped: the next redraw is sent even if it looks the same.
            if (held) drawn = null
        }

        /**
         * The switch in More → Security changed to [on]: held at once, then (off the main thread, in order) what was kept
         * is dropped when off, and the widget redrawn.
         */
        fun refresh(context: Context, on: Boolean) {
            synchronized(flagLock) { enabled = on }
            val app = context.applicationContext ?: context
            later {
                if (!on) forget()
                // On: filled at once from what the phone holds (no waiting for the next pass); off: the note alone.
                if (placed(app)) { if (on) fillLocal(app) else draw(app, AppWidgetManager.getInstance(app), ids(app), true, false) }
            }
        }

        /**
         * [fillLocal] off the caller's thread (the widget placed or updated, the app started, the phone restarted); [done]
         * runs when it is over (a receiver's goAsync finish).
         */
        fun fillSoon(context: Context, done: (() -> Unit)? = null) {
            if (BuildConfig.GOLD) { done?.invoke(); return }
            val app = context.applicationContext ?: context
            later(done) { fillLocal(app) }
        }

        /**
         * Fill the widget from what this phone already holds, with no network read: the paper books as last saved
         * ([Paper.localSnapshot], as of when they were last marked while anything is open), and Zerodha's P&L as the app
         * recorded it today ([DailyPnl], timed by [PnlTracker]'s last sample; after the close the day's final figure).
         * A reading kept already wins when it is newer ([OpenBook.takeLocal]). Nothing is read, kept or drawn with figures
         * when no widget is placed or the switch is off; a fresh install with nothing recorded says to open the app once.
         */
        internal fun fillLocal(context: Context) {
            if (BuildConfig.GOLD) return
            val placedIds = ids(context)
            if (placedIds.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            if (!allowed()) { off(context, manager, placedIds); return }
            val now = nowMinute()
            val live = runCatching { AppSettings.load().live }.getOrDefault(false)
            val fill = HashMap<String, String?>()
            runCatching { Paper.localSnapshot() }.getOrNull()?.let { (snap, savedMs) ->
                val open = snap.positions.positions.any { it.quantity != 0 }
                val saved = java.time.Instant.ofEpochMilli(savedMs).atZone(Market.now().zone).toLocalDateTime()
                val local = paperVenue(snap, !live, OpenBook.localAt(open, saved, now))
                if (OpenBook.takeLocal(OpenBook.decode(textNow(K_P)), local)) fill[K_P] = OpenBook.encode(local)
            }
            if (runCatching { Broker.linked }.getOrDefault(false)) runCatching { recordedZerodha(now, live) }.getOrNull()?.let { local ->
                if (OpenBook.takeLocal(OpenBook.decode(textNow(K_Z)), local)) fill[K_Z] = OpenBook.encode(local)
            }
            if (fill.isEmpty()) draw(context, manager, placedIds, true) else store(context, fill)
        }

        /** Zerodha's P&L as the app recorded it today (no read of its books, so no positions), or null for none. */
        private fun recordedZerodha(now: LocalDateTime, live: Boolean): OpenBook.Venue? {
            val day = DailyPnl.sessionDay(true)
            val kept = day?.let { d -> DailyPnl.all(true)[d] }
            val figure = kept?.pnl
            val sample = PnlTracker.today().lastOrNull()?.let { it.minute to it.pnl }
            // Zerodha's P&L is before charges; the day's charges go on the small line - Zerodha's exact figure when the
            // calendar kept it (usefulness, round 35), else the estimate the app made from the day's trades.
            return OpenBook.recorded(OpenBook.ZERODHA, figure, sample, now, !Market.isOpen(), live,
                charges = kept?.charges?.takeIf { it > 0 }, estimate = kept?.exact != true)
        }

        private fun off(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (drawn != "off") draw(context, manager, ids, false, false)
        }

        private fun store(context: Context, values: Map<String, String?>) {
            val placedIds = if (BuildConfig.GOLD) IntArray(0) else ids(context)
            if (placedIds.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            if (!allowed()) { forget(); off(context, manager, placedIds); return }
            val now = Market.now()
            val at = String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d", now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)
            val kept = synchronized(unsaved) {
                fresh()
                // Checked here, right before the write and under forget()'s lock: once the switch is off nothing lands.
                if (enabled != true) return@synchronized false
                val all = HashMap<String, String?>(unsaved)
                all.putAll(values)
                if (at != savedAt && all.any { (k, v) -> !OpenBook.sameFigures(v, SecurePrefs.getString(k)) }) {
                    savedAt = at
                    unsaved.clear()
                    SecurePrefs.putAllSoon(all)
                } else unsaved.putAll(values)
                true
            }
            if (!kept) { forget(); off(context, manager, placedIds); return }
            draw(context, manager, placedIds, false)
        }

        private fun nowMinute(): LocalDateTime = Market.now().toLocalDateTime().withSecond(0).withNano(0)

        /**
         * Zerodha's books as the widget keeps them: its P&L (before charges, as Zerodha shows it) and the day's charges on
         * the small line ([com.optionslab.ira.ExactCharges.widgetNext]): [exact] Zerodha's contract-note figure the app
         * already kept for every order of the day (usefulness, round 35; never asked from here), else [charges] estimated
         * from today's trades, else what was kept for today ([keptZerodhaCharges]) - a kept exact figure said as an
         * estimate once a reading that [checked] for it no longer finds it covering the day.
         */
        fun zerodhaVenue(book: Broker.Positions, at: LocalDateTime?, charges: Double? = null, exact: Double? = null,
                         checked: Boolean = false): OpenBook.Venue {
            val shown = com.optionslab.ira.ExactCharges.widgetNext(keptZerodhaCharges(at), exact, charges, checked)
            return OpenBook.Venue(OpenBook.ZERODHA, book.m2m,
                book.net.filter { it.qty != 0 }.map { OpenBook.Pos(it.symbol, it.qty, it.last.takeIf { l -> l > 0 }, it.pnl) }, primary = true, at = at,
                charges = shown?.value, estimate = shown?.estimate ?: true)
        }

        /** The Zerodha charges the widget kept from earlier today (a read of its trades, or Zerodha's exact figure), or null. */
        private fun keptZerodhaCharges(at: LocalDateTime?): com.optionslab.ira.ExactCharges.Shown? = runCatching {
            OpenBook.decode(textNow(K_Z))?.takeIf { k -> at != null && k.at?.toLocalDate() == at.toLocalDate() }
                ?.let { k -> k.charges?.let { com.optionslab.ira.ExactCharges.Shown(it, k.estimate) } }
        }.getOrNull()

        /** The paper books: the day's P&L before charges (display only), the day's charges paid on the small line. */
        fun paperVenue(snap: Paper.Snapshot, primary: Boolean, at: LocalDateTime?): OpenBook.Venue = OpenBook.Venue(OpenBook.PAPER, snap.dayGross,
            snap.positions.positions.filter { it.quantity != 0 }.map { OpenBook.Pos(it.symbol, it.quantity, it.ltp.takeIf { l -> l > 0 }, it.pnl) },
            snap.orders.orders.filter { it.pendingQuantity > 0 }.map { OpenBook.Ord(it.symbol, it.action, it.pendingQuantity, it.priceType, it.price, it.triggerPrice, it.status) },
            primary = primary, at = at, charges = snap.dayCharges, estimate = false)

        /**
         * The live watch's pass ([com.optionslab.app.work.PositionCards.refresh]): [live] the app is in Zerodha mode,
         * [loggedIn] with a session today, [book] its positions as read (null = the read failed), [paper] the paper books
         * (null = not read; kept as they were), [orders] Zerodha's order book as this pass read it (null = not read).
         */
        fun fromWatch(context: Context, live: Boolean, loggedIn: Boolean, book: Broker.Positions?, paper: Paper.Snapshot?,
                      orders: List<Broker.OrderRow>? = null) {
            if (BuildConfig.GOLD) return
            val at = nowMinute()
            val z: OpenBook.Venue? = when {
                !live -> null
                !loggedIn -> OpenBook.Venue(OpenBook.ZERODHA, null, problem = "not logged in", primary = true, at = at)
                book == null -> OpenBook.Venue(OpenBook.ZERODHA, null, problem = "could not read", primary = true, at = at)
                // Zerodha's exact charges only when this pass read the order book and the figure kept is for exactly those
                // orders (an order placed outside the app - Kite web - since the ask is seen only there); else the
                // estimate from the day's kept trades (the watch's worker thread: these read the vault; never ask Zerodha).
                else -> zerodhaVenue(book, at, runCatching { com.optionslab.app.data.TradeBook.liveChargesOn(Market.today()) }.getOrNull(),
                    orders?.let { o -> runCatching { com.optionslab.app.data.ZerodhaCharges.kept(o, Market.today()) }.getOrNull() }, checked = true)
            }
            val m = HashMap<String, String?>()
            m[K_Z] = z?.let { OpenBook.encode(it) }
            if (paper != null) m[K_P] = OpenBook.encode(paperVenue(paper, !live, at))
            store(context, m)
        }

        /**
         * Should the live watch read Zerodha's order book on this pass? Only while a placed widget, with the switch on and
         * in Zerodha mode, shows orders still working there (one read a pass, so a filled or cancelled one goes).
         * Battery (round 12): with the screen off nobody sees it, so then only when the book is about 5 minutes old
         * ([com.optionslab.ira.WidgetOrdersPace]); the screen's state unknown counts as on.
         */
        fun wantsOrders(context: Context, live: Boolean): Boolean {
            if (BuildConfig.GOLD || !live || !placed(context) || !allowed()) return false
            val screenOn = runCatching { context.getSystemService(android.os.PowerManager::class.java)?.isInteractive }.getOrNull() ?: true
            // Review (battery 12): the first pass after the screen comes on reads the book, whatever the widget shows.
            val wasOn = screenWasOn
            screenWasOn = screenOn
            if (com.optionslab.ira.WidgetOrdersPace.woke(wasOn, screenOn)) return true
            val last = ordersAt
            val since = if (last > 0L) System.currentTimeMillis() - last else null
            if (!com.optionslab.ira.WidgetOrdersPace.due(since, screenOn)) return false
            return OpenBook.decodeOrders(textNow(K_ZO), Market.today().toString()).any { OpenBook.pendingLabel(it.status, it.variety) != null }
        }

        /**
         * Zerodha's positions read by the app ([com.optionslab.app.ui.AppModel.loadAccount]), with [charges] estimated from
         * today's trades when they were read too (null: the figure kept from earlier today stays), or [exact] - Zerodha's
         * own figure already kept for exactly the day's orders - in its place.
         */
        fun fromZerodha(context: Context, book: Broker.Positions, charges: Double? = null, exact: Double? = null) {
            if (BuildConfig.GOLD) return
            store(context, mapOf(K_Z to OpenBook.encode(zerodhaVenue(book, nowMinute(), charges, exact, checked = charges != null || exact != null))))
        }

        /**
         * Zerodha's exact charges for the day just came (the account page asked its contract note for every order of the
         * day): the small line under the kept Zerodha figure says them in place of the estimate. Only today's kept
         * reading changes; nothing kept, no line. Never asks Zerodha.
         */
        fun exactCharges(context: Context, value: Double) {
            if (BuildConfig.GOLD || !value.isFinite()) return
            val kept = OpenBook.decode(textNow(K_Z))?.takeIf { it.pnl != null && it.at?.toLocalDate() == Market.today() } ?: return
            store(context, mapOf(K_Z to OpenBook.encode(kept.copy(charges = value, estimate = false))))
        }

        /** Zerodha's positions moved by the live price stream: at most every 5 s, and only in Zerodha mode. */
        fun fromStream(context: Context, book: Broker.Positions) {
            if (BuildConfig.GOLD) return
            val now = System.currentTimeMillis()
            if (now - streamAt < 5_000) return
            streamAt = now
            if (!runCatching { AppSettings.load().live }.getOrDefault(false)) return
            store(context, mapOf(K_Z to OpenBook.encode(zerodhaVenue(book, nowMinute()))))
        }

        /** The paper books read by the app ([com.optionslab.app.ui.AppModel.loadPaper]). */
        fun fromPaper(context: Context, snap: Paper.Snapshot) {
            if (BuildConfig.GOLD) return
            val live = runCatching { AppSettings.load().live }.getOrDefault(false)
            store(context, mapOf(K_P to OpenBook.encode(paperVenue(snap, !live, nowMinute()))))
        }

        /** Zerodha's order book, whenever something already read it: the ones still working. */
        fun fromOrders(context: Context, rows: List<Broker.OrderRow>) {
            if (BuildConfig.GOLD) return
            ordersAt = System.currentTimeMillis()
            val working = rows.filter { it.working }.map { o ->
                OpenBook.Ord(o.symbol, o.side, if (o.pending > 0) o.pending else o.qty, o.type, o.price, o.trigger, o.status, o.variety)
            }
            store(context, mapOf(K_ZO to OpenBook.encodeOrders(Market.today().toString(), working)))
        }

        /** [fromOrders] from [Broker.orders]: handed to the widget's own thread, so the order path never waits on it. */
        fun fromOrdersSoon(context: Context, rows: List<Broker.OrderRow>) {
            if (BuildConfig.GOLD) return
            val app = context.applicationContext ?: context
            later { fromOrders(app, rows) }
        }

        /** What the widget shows now, from what it kept, in at most [maxRows] rows. */
        internal fun screen(maxRows: Int = MAX_ROWS): Pair<OpenBook.Screen, String?> {
            val now = Market.now().toLocalDateTime()
            // The headline is the account the app is in: Zerodha in live mode, Paper in paper mode.
            val live = runCatching { AppSettings.load().live }.getOrDefault(false)
            val zOrders = OpenBook.decodeOrders(textNow(K_ZO), now.toLocalDate().toString())
            // Zerodha's orders only beside a read of its positions: an unreadable account shows no stale orders.
            val z = OpenBook.decode(textNow(K_Z))?.let { if (it.problem == null) it.copy(orders = zOrders) else it }?.copy(primary = live)
            val p = OpenBook.decode(textNow(K_P))?.copy(primary = !live)
            return OpenBook.view(listOfNotNull(z, p), now, maxRows)
        }

        /** How many rows fit the smallest placed widget's height (all of them when the launcher does not say). */
        private fun visibleRows(manager: AppWidgetManager, ids: IntArray): Int {
            var rows = MAX_ROWS
            for (id in ids) {
                val h = runCatching { manager.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0 }.getOrDefault(0)
                if (h > 0) rows = minOf(rows, ((h - HEAD_DP) / ROW_DP).coerceAtLeast(1))
            }
            return rows
        }

        private fun colour(context: Context, t: OpenBook.Tone): Int = context.getColor(when (t) {
            OpenBook.Tone.GAIN -> R.color.widget_gain
            OpenBook.Tone.LOSS -> R.color.widget_loss
            OpenBook.Tone.PLAIN -> R.color.widget_ink
        })

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray, force: Boolean, show: Boolean = allowed()) {
            if (ids.isEmpty()) return
            synchronized(drawLock) {
                // The switch as it is now, not as it was when this redraw was asked for.
                val showNow = show && enabled == true
                val v = RemoteViews(context.packageName, R.layout.widget_open)
                val sig: String
                val figures = listOf(R.id.ow_caption, R.id.ow_pnl, R.id.ow_split, R.id.ow_stamp, R.id.ow_rule)
                if (!showNow) {
                    figures.forEach { v.setViewVisibility(it, View.GONE) }
                    v.setViewVisibility(R.id.ow_charges, View.GONE)
                    ROW.forEach { v.setViewVisibility(it, View.GONE) }
                    v.setViewVisibility(R.id.ow_more, View.GONE)
                    v.setViewVisibility(R.id.ow_note, View.VISIBLE)
                    v.setTextViewText(R.id.ow_note, OFF)
                    sig = "off"
                } else {
                    val (s, stamp) = screen(visibleRows(manager, ids))
                    figures.forEach { v.setViewVisibility(it, View.VISIBLE) }
                    v.setTextViewText(R.id.ow_caption, s.caption)
                    v.setTextViewText(R.id.ow_pnl, s.headline)
                    v.setTextColor(R.id.ow_pnl, colour(context, s.tone))
                    // The headline is before charges; the day's charges in small type under it (none: no line).
                    val chargesLine = s.charges
                    if (chargesLine == null) v.setViewVisibility(R.id.ow_charges, View.GONE)
                    else { v.setViewVisibility(R.id.ow_charges, View.VISIBLE); v.setTextViewText(R.id.ow_charges, chargesLine) }
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
}
