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
import com.optionslab.app.data.Market
import com.optionslab.app.data.OrbArms
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.LiquidityMap
import com.optionslab.ira.WidgetLiquidity
import java.util.Locale

/**
 * The home-screen widget: NIFTY and BANKNIFTY and whether the market is open.
 * The account P&L appears only if the owner turned it on (More → Security),
 * because a home screen is seen by anyone holding the unlocked phone. Tapping
 * it opens the app, which still asks for the PIN or fingerprint.
 *
 * Liquidity 15+5 (not in IraGoldAlgo) has a row of its own ([WidgetLiquidity]): its state and lots, its open position or
 * today's paper result, and - where the widget is tall enough - the contract's premium or the next trigger. Its rupee
 * figures follow the same switch as the account P&L. Read from the arm's in-memory book with short waits on its lock
 * ([refreshLiquidity]) on the widget's own updates (no timer, no network); tapping the row opens the Strategies card.
 * Nothing on it arms, places or closes anything.
 */
class IraWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids, force = true)
        val pending = runCatching { goAsync() }.getOrNull()
        refreshLiquidity(context, ids) { runCatching { pending?.finish() } }
    }

    /** Resized: the Liquidity row's second line shows only where it fits. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        runCatching {
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, IraWidget::class.java))
            if (ids.isNotEmpty()) render(context, appWidgetManager, ids, force = true)
        }
    }

    companion object {
        private const val K_NIFTY = "w.nifty"
        private const val K_BANK = "w.bank"
        private const val K_PNL = "w.pnl"
        private const val K_AT = "w.at"
        /**
         * Today's Zerodha charges, "yyyy-MM-dd|rupees|x" (x: Zerodha's exact figure, e: the estimate; an older build's two
         * fields were an estimate) - another day's is never shown ([com.optionslab.ira.ExactCharges.decodeDay]).
         */
        private const val K_CHG = "w.chg"

        /** Liquidity 15+5 as last read for the row ([refreshLiquidity]), or null: not read yet (or unreadable) - no row. */
        @Volatile private var liquidity: WidgetLiquidity.Facts? = null
        /** When the arm's book was last read for the row (ms): at most every [LIQ_EVERY_MS], however often the widget redraws. */
        @Volatile private var liquidityAt = 0L
        private const val LIQ_EVERY_MS = 30_000L
        private val liquidityBusy = java.util.concurrent.atomic.AtomicBoolean(false)
        /** The Liquidity read, off the caller's thread (it waits on the arms' lock), one at a time. */
        private val liquidityWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ira-widget").apply { isDaemon = true } }
        /** Tests: the read on the caller's thread, every time ([liquidityFake], when set, in place of the arm's book). */
        @Volatile internal var inlineForTest = false
        @Volatile internal var liquidityFake: WidgetLiquidity.Facts? = null

        /**
         * What the widget last showed (its words, colours, rows and the widgets drawn), so a redraw that would show the same
         * is not sent (ANR fix, 9 Oct): the live stream redraws every 5 s, the watch and the open Home page too - each an
         * update sent through Android to the home screen, mostly the same figures between two prints.
         */
        @Volatile private var drawnSig: String? = null

        /** Tests: each test's home screen starts empty, so nothing counts as drawn already. */
        internal fun forgetDrawnForTest() { drawnSig = null }

        /** Tests share this process: back to a fresh start. */
        internal fun resetForTest() {
            drawnSig = null
            runCatching { liquidityWorker.submit(Runnable {}).get(5, java.util.concurrent.TimeUnit.SECONDS) }
            liquidity = null; liquidityAt = 0L; liquidityBusy.set(false); inlineForTest = false; liquidityFake = null
        }

        /** The account P&L last published (the live watch refreshes it every minute), or null. */
        fun lastPnl(): Double? = pnlNow()

        /**
         * Battery, round 3: figures newer than the vault copy (key -> value, a null P&L meaning none). The live stream
         * publishes every 5 s and each vault write is a Keystore encryption and two disk syncs, so the figures reach the
         * vault at most once a minute and only when they differ from the vault's (a stamp that moved alone stays here);
         * in between they live here and every widget redraw reads them. A process death loses at most that minute; the widget then shows the last saved
         * figures with their own stamp.
         */
        private val unsaved = HashMap<String, Any?>()
        /** The stamp (HH:mm) of the last vault write, or null before the first one in this process. */
        private var savedAt: String? = null

        /** The vault's generation [unsaved] belongs to: a re-init or wipe of the vault drops what was held. */
        private var unsavedGen = Int.MIN_VALUE

        private fun fresh() {
            val g = SecurePrefs.generationNow()
            if (g != unsavedGen) { unsaved.clear(); savedAt = null; unsavedGen = g }
        }

        /** What the vault holds for [k]: the P&L as a Double, the rest as text, null when absent. */
        private fun vaultValue(k: String): Any? = when {
            SecurePrefs.getString(k) == null -> null
            k == K_PNL -> SecurePrefs.getDouble(K_PNL, 0.0)
            else -> SecurePrefs.getString(k)
        }

        private fun textNow(k: String): String? = synchronized(unsaved) {
            fresh()
            if (unsaved.containsKey(k)) unsaved[k] as String? else SecurePrefs.getString(k)
        }

        private fun pnlNow(): Double? = synchronized(unsaved) {
            fresh()
            if (unsaved.containsKey(K_PNL)) unsaved[K_PNL] as Double?
            else if (SecurePrefs.getString(K_PNL) != null) SecurePrefs.getDouble(K_PNL, 0.0) else null
        }

        /** Is at least one widget on a home screen? (Unknown counts as yes: the watch then reads its prices as before.) */
        fun placed(context: Context): Boolean = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, IraWidget::class.java)).isNotEmpty()
        }.getOrDefault(true)

        /** Record the latest figures and redraw every placed widget. */
        fun publish(context: Context, nifty: Pair<Double, Double>?, bank: Pair<Double, Double>?, pnl: Double?) {
            val m = HashMap<String, Any?>()
            nifty?.let { m[K_NIFTY] = "%.2f|%.4f".format(Locale.ROOT, it.first, it.second) }
            bank?.let { m[K_BANK] = "%.2f|%.4f".format(Locale.ROOT, it.first, it.second) }
            m[K_PNL] = pnl
            val at = Market.now().let { "%02d:%02d".format(it.hour, it.minute) }
            m[K_AT] = at
            val toVault: Map<String, Any?>? = synchronized(unsaved) {
                fresh()
                val all = HashMap<String, Any?>(unsaved).apply { putAll(m) }
                if (at != savedAt && all.any { (k, v) -> k != K_AT && v != vaultValue(k) }) {
                    // A new minute with figures the vault does not have: everything held so far goes in one write.
                    savedAt = at
                    unsaved.clear()
                    all
                } else { unsaved.putAll(m); null }   // held in memory (the same minute, or only the stamp moved); the redraw shows it
            }
            // ANR fix (9 Oct): written with the next minute's lazy save ([SecurePrefs.putAllLazy]), not one Keystore encryption here.
            if (toVault != null) SecurePrefs.putAllLazy(toVault)
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, IraWidget::class.java))
            if (ids.isNotEmpty()) { render(context, mgr, ids); refreshLiquidity(context, ids) }
        }

        /**
         * Liquidity 15+5's row, read again on the widget's own update path (a publish, the launcher's update): at most every
         * [LIQ_EVERY_MS], off the caller's thread, one read at a time, and redrawn only when the row changed. Never in
         * IraGoldAlgo; [done] always runs (a receiver's goAsync finish).
         */
        private fun refreshLiquidity(context: Context, ids: IntArray, done: (() -> Unit)? = null) {
            if (BuildConfig.GOLD || ids.isEmpty()) { done?.invoke(); return }
            val app = context.applicationContext ?: context
            val work = {
                val was = liquidity
                val read = liquidityFake ?: runCatching { kotlinx.coroutines.runBlocking { readLiquidity() } }.getOrNull()
                // A busy lock or a failed read keeps what was shown (nothing shown before: no row).
                if (read != null) {
                    liquidity = read
                    // (A stale read before - its row hidden - is redrawn with the fresh one even when the words match.)
                    if (was == null || WidgetLiquidity.row(was, true) != WidgetLiquidity.row(read, true) ||
                        !runCatching { WidgetLiquidity.fresh(was, read.now, Market.isIndexOpen()) }.getOrDefault(false)) {
                        val mgr = AppWidgetManager.getInstance(app)
                        val placed = mgr.getAppWidgetIds(ComponentName(app, IraWidget::class.java))
                        if (placed.isNotEmpty()) render(app, mgr, placed)
                    }
                }
            }
            if (inlineForTest) { runCatching { work() }; done?.invoke(); return }
            val t = System.currentTimeMillis()
            if (t - liquidityAt < LIQ_EVERY_MS || !liquidityBusy.compareAndSet(false, true)) { done?.invoke(); return }
            liquidityAt = t
            val queued = runCatching {
                liquidityWorker.execute { try { runCatching { work() } } finally { liquidityBusy.set(false); done?.invoke() } }
            }.isSuccess
            if (!queued) { liquidityBusy.set(false); done?.invoke() }
        }

        /**
         * Liquidity 15+5 now, from the arm's in-memory book only - each read a short wait on its lock, nothing fetched: its
         * books' switches and today's positions ([OrbArms.liquidityDay]), its lots, the day's stop, the open position's mark
         * and index ([OrbArms.liquidityOpenNow]), and, flat and armed, each armed book's levels from the minutes the arm
         * itself read this minute ([OrbArms.liquidityMinutesKept]; none kept: no next trigger). Null when its book could
         * not be read in time.
         */
        private suspend fun readLiquidity(): WidgetLiquidity.Facts? {
            val now = Market.now().toLocalDateTime()
            val day = now.toLocalDate()
            val held = kotlinx.coroutines.withTimeoutOrNull(3_000) { OrbArms.liquidityDay(day) } ?: return null
            val books = held.first
            val positions = held.second
            val lots = runCatching { kotlinx.coroutines.withTimeoutOrNull(2_000) { OrbArms.liquidityLots() } }.getOrNull()
            val dayStop = runCatching { kotlinx.coroutines.withTimeoutOrNull(2_000) { com.optionslab.app.data.Strategies.stoppedWhy() } }.getOrNull()
            val stopped = dayStop != null ||
                books.any { com.optionslab.ira.LiquidityWhyNot.kind(it.status) == com.optionslab.ira.LiquidityWhyNot.Kind.STOPPED }
            val state = when {
                books.none { it.armed } -> WidgetLiquidity.State.OFF
                stopped -> WidgetLiquidity.State.STOPPED
                else -> WidgetLiquidity.State.ARMED
            }
            val paper = positions.filter { !it.live }
            val closed = paper.filter { !it.open }
            val net: Double? = if (closed.isEmpty()) null else closed.sumOf { (it.grossPnl ?: 0.0) - it.charges }
            val pos = positions.lastOrNull { it.open }
            var open: WidgetLiquidity.Open? = null
            if (pos != null) {
                val src = LiquidityRules.RENAMED[pos.arm] ?: pos.arm
                val und = LiquidityRules.BOOKS.firstOrNull { it.source == src }?.let { LiquidityRules.underlyingOf(it) }
                if (und != null) {
                    val read = runCatching { kotlinx.coroutines.withTimeoutOrNull(2_000) { OrbArms.liquidityOpenNow(pos.arm, pos.symbol) } }.getOrNull()
                    open = WidgetLiquidity.Open(und, pos.right, pos.symbol, pos.live, pos.qty, pos.entry, read?.mark, pos.level, pos.target,
                        read?.index, read?.indexAt)
                }
            }
            val reads: List<LiquidityMap.Read> = if (open != null || state != WidgetLiquidity.State.ARMED) emptyList() else runCatching {
                val on = books.filter { it.armed }.map { LiquidityRules.RENAMED[it.book] ?: it.book }.toSet()
                LiquidityRules.BOOKS.filter { it.source in on }.mapNotNull { arm ->
                    val u = LiquidityRules.underlyingOf(arm)
                    OrbArms.liquidityMinutesKept(u, now)?.let { LiquidityMap.read(it, u, LiquidityRules.minutesOf(arm), now) }
                }
            }.getOrDefault(emptyList())
            return WidgetLiquidity.Facts(now, state, lots, paper.size, net, open, reads)
        }

        /** The smallest placed widget's height in dp (0: the launcher did not say). */
        private fun heightDp(manager: AppWidgetManager, ids: IntArray): Int = ids.minOfOrNull { id ->
            runCatching { manager.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0 }.getOrDefault(0)
        } ?: 0

        /**
         * Today's Zerodha charges: estimated from today's trades (the app's account read has them), or - [exact] - Zerodha's
         * own contract-note figure the app already kept for every order of the day (usefulness, round 35: "Charges ₹X"
         * instead of the estimate; the widget never asks Zerodha). The widget's P&L is Zerodha's own, before charges, and
         * these go on a small line under it. Held with the other figures (the vault gets it with the next minute's write)
         * and redrawn.
         */
        fun charges(context: Context, value: Double?, exact: Boolean = false) {
            val v = value?.takeIf { it.isFinite() }?.let {
                com.optionslab.ira.ExactCharges.encodeDay(Market.today().toString(), com.optionslab.ira.ExactCharges.Shown(it, estimate = !exact))
            }
            // Battery, round 14: the order watch hands the same figure every pass (once a minute) and redraws the widget
            // itself moments later ([publish]); redrawn here only when the figure changed (a new fill, a new day).
            val changed = synchronized(unsaved) {
                fresh()
                val was = if (unsaved.containsKey(K_CHG)) unsaved[K_CHG] as String? else SecurePrefs.getString(K_CHG)
                unsaved[K_CHG] = v
                was != v
            }
            if (!changed) return
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, IraWidget::class.java))
            if (ids.isNotEmpty()) render(context, mgr, ids)
        }

        /** Today's charges kept by [charges] (exact or an estimate), or null (none, or another day's). */
        private fun chargesToday(): com.optionslab.ira.ExactCharges.Shown? =
            com.optionslab.ira.ExactCharges.decodeDay(textNow(K_CHG), Market.today().toString())

        private fun line(name: String, raw: String?): String {
            val (last, chg) = raw?.split("|")?.let { it[0].toDouble() to it[1].toDouble() } ?: return "$name  —"
            return String.format(Locale.ENGLISH, "%-9s %,10.2f  %+.2f%%", name, last, 100 * chg)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, force: Boolean = false) {
            val v = RemoteViews(context.packageName, R.layout.widget_iraalgo)
            val niftyLine = line("NIFTY", textNow(K_NIFTY))
            val bankLine = line("BANKNIFTY", textNow(K_BANK))
            v.setTextViewText(R.id.w_nifty, niftyLine)
            v.setTextViewText(R.id.w_bank, bankLine)
            val showPnl = AppSettings.load().widgetPnl
            val pnl = pnlNow()
            var pnlShown: String? = null
            if (showPnl && pnl != null) {
                v.setViewVisibility(R.id.w_pnl, View.VISIBLE)
                pnlShown = String.format(Locale.ENGLISH, "P&L  Rs %+,.0f", pnl) + (if (pnl >= 0) "+" else "-")
                v.setTextViewText(R.id.w_pnl, String.format(Locale.ENGLISH, "P&L  Rs %+,.0f", pnl))
                v.setTextColor(R.id.w_pnl, context.getColor(if (pnl >= 0) R.color.widget_gain else R.color.widget_loss))
            } else v.setViewVisibility(R.id.w_pnl, View.GONE)
            // Zerodha's P&L is before charges: today's charges in small type under it - Zerodha's exact figure when the app
            // kept it for every order of the day, else the estimate (none known: no line).
            val chargesLine = if (showPnl && pnl != null) chargesToday()?.let { com.optionslab.ira.PnlCharges.line(it.value, it.estimate) } else null
            if (chargesLine == null) v.setViewVisibility(R.id.w_charges, View.GONE)
            else { v.setViewVisibility(R.id.w_charges, View.VISIBLE); v.setTextViewText(R.id.w_charges, chargesLine) }
            val liqLine = liquidityRow(context, manager, ids, v, showPnl)
            val at = textNow(K_AT)
            val status = (if (Market.isOpen()) "Market open" else "Market shut") + (at?.let { " · $it IST" } ?: "")
            v.setTextViewText(R.id.w_status, status)
            val sig = listOf(ids.joinToString(","), niftyLine, bankLine, pnlShown ?: "-", chargesLine ?: "-", liqLine ?: "-", status)
                .joinToString("\u0001")
            if (!force && sig == drawnSig) return
            val open = PendingIntent.getActivity(context, 9, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "almanac").putExtra(MainActivity.EXTRA_NONCE, MainActivity.nonce()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_root, open)
            manager.updateAppWidget(ids, v)
            drawnSig = sig
        }

        /**
         * Liquidity 15+5's row: none in IraGoldAlgo or before its book was read; its rupee figures only with [figures]. The
         * row's words, or null when no row is shown.
         */
        private fun liquidityRow(context: Context, manager: AppWidgetManager, ids: IntArray, v: RemoteViews, figures: Boolean): String? {
            // A cached read from another day, or over 10 minutes old while the market is open, is not shown as current.
            val f = liquidity?.takeIf { runCatching { WidgetLiquidity.fresh(it, Market.now().toLocalDateTime(), Market.isIndexOpen()) }.getOrDefault(false) }
            val row = if (BuildConfig.GOLD || f == null) null else runCatching { WidgetLiquidity.row(f, figures) }.getOrNull()
            if (row == null) { v.setViewVisibility(R.id.w_liq_box, View.GONE); return null }
            v.setViewVisibility(R.id.w_liq_box, View.VISIBLE)
            v.setTextViewText(R.id.w_liq, row.line)
            v.setTextColor(R.id.w_liq, context.getColor(when (row.tone) {
                WidgetLiquidity.Tone.GAIN -> R.color.widget_gain
                WidgetLiquidity.Tone.LOSS -> R.color.widget_loss
                WidgetLiquidity.Tone.PLAIN -> R.color.widget_ink
            }))
            val detail = row.detail?.takeIf { WidgetLiquidity.detailFits(heightDp(manager, ids)) }
            if (detail == null) v.setViewVisibility(R.id.w_liq2, View.GONE)
            else { v.setViewVisibility(R.id.w_liq2, View.VISIBLE); v.setTextViewText(R.id.w_liq2, detail) }
            // Its own tap: the Strategies card (the deep link Liquidity's notices use), never an action.
            val strategies = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "strategy")
                .putExtra(MainActivity.EXTRA_NONCE, MainActivity.nonce()), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_liq_box, strategies)
            return listOf(row.line, row.tone.name, detail ?: "-").joinToString("|")
        }
    }
}
