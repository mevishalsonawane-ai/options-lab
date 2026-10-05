package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import com.optionslab.engine.sandbox.SandboxRules
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.sign

/**
 * One P&L figure per trading day, per account, for the P&L calendar.
 *
 *  - Today is recorded as it goes (the market watch and the open app), and the
 *    last reading of the day stays: realised + open P&L, with the day's charges
 *    beside it (paper: what it paid; Zerodha: an estimate from its trades).
 *  - Shown before charges (Boss, 5 Oct: as Zerodha shows its P&L), the charges on
 *    a small line: [Day.pnl] is the figure before charges, [Day.charges] the
 *    charges. What is stored is unchanged - paper keeps its figure after charges,
 *    Zerodha its own m2m (before them) - with the charges as a third element; an
 *    entry kept before that (no charges) shows exactly as it always did.
 *  - The paper account's earlier days are also rebuilt from its own trade
 *    book (average-cost, charges included) for days that were never recorded.
 *  - Zerodha does not give past days' P&L through its API, so the Zerodha
 *    calendar fills from the day IraAlgo first recorded it.
 *
 * Private like everything about the account: kept in the encrypted vault.
 */
object DailyPnl {
    /**
     * One day: [pnl] the P&L before charges (as shown; the calendar's tile), [trades] the count, [charges] that day's
     * charges (0 when not kept - an older entry, whose [pnl] is then the figure as it was recorded), [exact] when they are
     * Zerodha's own contract-note figure for every order of the day rather than the estimate from its trades.
     */
    data class Day(val date: LocalDate, val pnl: Double, val trades: Int, val charges: Double = 0.0, val exact: Boolean = false) {
        /** After charges. */
        val net: Double get() = pnl - charges
    }

    private fun key(live: Boolean) = if (live) "pnl.days.live" else "pnl.days.paper"

    /**
     * Bumped whenever [record] or [resetPaper] changes a stored figure, whoever called it (the open app, the market
     * watch, the evening report), so an open P&L calendar redraws (review, speed 5: a figure the watch recorded no longer
     * goes missing). Only ever counts up; a StateFlow, so it is read and bumped safely from any thread.
     */
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    val changes: kotlinx.coroutines.flow.StateFlow<Int> get() = _changes

    private fun changed() { _changes.update { it + 1 } }

    @Synchronized
    private fun read(live: Boolean): JSONObject = runCatching { JSONObject(SecurePrefs.getString(key(live)) ?: "{}") }.getOrDefault(JSONObject())

    /**
     * The trading day a reading at [now] belongs to, or null when it belongs to none. The paper account's day
     * runs to its 03:00 reset, so a reading after midnight is still the day before (its trades and "today"
     * P&L are that day's). Zerodha still shows yesterday's positions in the early morning, so nothing before
     * 09:00 is recorded for it.
     */
    fun sessionDay(live: Boolean, now: ZonedDateTime = Market.now()): LocalDate? {
        val t = now.withZoneSameInstant(SandboxRules.IST).toLocalDateTime()
        return if (live) t.toLocalDate().takeIf { !t.toLocalTime().isBefore(LocalTime.of(9, 0)) }
        else SandboxRules.lastSessionExpiry("03:00", t).toLocalDate()
    }

    /**
     * The current trading day's figure for the account; [trades] < 0 keeps the count already stored. [pnl] as each
     * account always kept it: paper after charges (Paper.Snapshot.dayPnl), Zerodha its own m2m (before charges);
     * [charges] the day's charges (paper: paid; Zerodha: estimated, or - [exact] - Zerodha's own figure for every order of
     * the day, usefulness round 35), < 0 keeps the charges already stored. True when the kept figure changed (the
     * calendar then reads again).
     *
     * Speed, round 5 ([com.optionslab.ira.DayFigure]): a reading equal to the kept one writes nothing, and a new one is
     * readable at once and written to the vault in the background ([SecurePrefs.putAllSoon]): the paper and Zerodha
     * refreshes that record it no longer wait on a Keystore encryption and two disk syncs before showing the new book.
     */
    @Synchronized
    fun record(live: Boolean, pnl: Double, trades: Int, charges: Double = -1.0, exact: Boolean = false): Boolean {
        if (!pnl.isFinite()) return false
        val today = (sessionDay(live) ?: return false).toString()
        val o = read(live)
        val stored = kept(o, today)
        val entry = com.optionslab.ira.DayFigure.next(stored, pnl, trades, charges, exact) ?: return false
        // An exact figure is marked by a fourth element (1); every other entry is written as it always was.
        o.put(today, JSONArray().put(entry.pnl).put(entry.trades).put(entry.charges).also { if (entry.exact) it.put(1) })
        // About three years of days is plenty; the oldest go first.
        val keys = o.keys().asSequence().toList().sorted()
        keys.dropLast(1100).forEach { o.remove(it) }
        SecurePrefs.putAllSoon(mapOf(key(live) to o.toString()))
        changed()
        return true
    }

    /** The entry kept for [day] in [o], or null (none, or damaged). */
    private fun kept(o: JSONObject, day: String): com.optionslab.ira.DayFigure.Kept? = o.optJSONArray(day)?.let { a ->
        runCatching { com.optionslab.ira.DayFigure.Kept(a.getDouble(0), a.optInt(1, 0), a.optDouble(2, 0.0), a.optInt(3, 0) == 1) }.getOrNull()
    }

    /**
     * Usefulness, round 35: the current trading day's [charges] alone ([exact]: Zerodha's own figure for every order of
     * the day), its P&L and trade count kept as recorded; [onlyOverExact]: only in place of a kept exact figure (one that
     * no longer covers the day's orders gives way to the estimate). Nothing when no figure is kept for the day yet. True
     * when the kept entry changed.
     */
    @Synchronized
    fun recordCharges(live: Boolean, charges: Double, exact: Boolean, onlyOverExact: Boolean = false): Boolean {
        val today = (sessionDay(live) ?: return false).toString()
        val stored = kept(read(live), today) ?: return false
        if (onlyOverExact && !stored.exact) return false
        return record(live, stored.pnl, -1, charges, exact)
    }

    /** The paper calendar starts empty again (Reset paper); the Zerodha days are kept. */
    @Synchronized
    fun resetPaper() { SecurePrefs.put(key(false), "{}"); changed() }

    /** Every day of [month] that has a figure. */
    fun month(live: Boolean, month: YearMonth): Map<LocalDate, Day> = all(live).filterKeys { YearMonth.from(it) == month }

    /** Every day with a figure, for the account. */
    fun all(live: Boolean): Map<LocalDate, Day> {
        val out = HashMap<LocalDate, Day>()
        if (!live) runCatching { rebuildPaper() }.getOrNull()?.let { out.putAll(it) }
        val rebuilt = out.keys.toSet()
        val o = read(live)
        o.keys().forEach { k ->
            val d = runCatching { LocalDate.parse(k) }.getOrNull() ?: return@forEach
            val a = o.getJSONArray(k)
            // Older builds recorded after midnight under the new date: a copy of the day before, with no trade of its own.
            val prev = o.optJSONArray(d.minusDays(1).toString())
            if (d !in rebuilt && prev != null && a.optInt(1, 0) > 0 && prev.optInt(1, 0) == a.optInt(1, 0) &&
                prev.getDouble(0) == a.getDouble(0)) return@forEach
            // A recorded day wins: it includes expiry settlements and open positions the trade book cannot see.
            // Shown before charges: paper kept its figure after them (added back), Zerodha its m2m (before them already).
            // An entry with no charges kept (an older build's) shows as it always did.
            val charges = a.optDouble(2, 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0
            val shown = if (live) a.getDouble(0) else com.optionslab.ira.DayFigure.paise(com.optionslab.ira.PnlCharges.gross(a.getDouble(0), charges))
            out[d] = Day(d, shown, maxOf(a.optInt(1, 0), out[d]?.trades ?: 0), charges, exact = charges > 0 && a.optInt(3, 0) == 1)
        }
        return out
    }

    /** Earliest month with any figure, so the calendar knows where history starts. */
    fun firstMonth(live: Boolean): YearMonth? {
        val recorded = read(live).keys().asSequence().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.minOrNull()
        val traded = if (live) null else Paper.state.trades.minOfOrNull { it.timestamp.toLocalDate() }
        return listOfNotNull(recorded, traded).minOrNull()?.let { YearMonth.from(it) }
    }

    /** Realised P&L per day from the paper trade book: average cost per contract, before charges, with each day's charges. */
    private fun rebuildPaper(): Map<LocalDate, Day> {
        data class Pos(var qty: Int = 0, var avg: Double = 0.0)
        val pos = HashMap<String, Pos>()
        val pnl = HashMap<LocalDate, Double>()
        val count = HashMap<LocalDate, Int>()
        val paid = HashMap<LocalDate, Double>()
        for (t in Paper.state.trades.sortedBy { it.timestamp }) {
            val d = t.timestamp.toLocalDate()
            val p = pos.getOrPut("${t.symbol}|${t.product}") { Pos() }
            val px = t.price.toDouble()
            val signed = if (t.action == "BUY") t.quantity else -t.quantity
            if (p.qty == 0 || sign(p.qty.toDouble()) == sign(signed.toDouble())) {
                p.avg = (p.avg * abs(p.qty) + px * abs(signed)) / (abs(p.qty) + abs(signed))
                p.qty += signed
            } else {
                val closing = minOf(abs(signed), abs(p.qty))
                pnl[d] = (pnl[d] ?: 0.0) + (px - p.avg) * closing * sign(p.qty.toDouble())
                val left = p.qty + signed
                if (left != 0 && sign(left.toDouble()) != sign(p.qty.toDouble())) p.avg = px   // reversed through zero
                p.qty = left
                if (p.qty == 0) p.avg = 0.0
            }
            if (d !in pnl) pnl[d] = 0.0
            paid[d] = (paid[d] ?: 0.0) + t.charges.toDouble()
            count[d] = (count[d] ?: 0) + 1
        }
        return pnl.mapValues { (d, v) -> Day(d, Math.round(v * 100) / 100.0, count[d] ?: 0, Math.round((paid[d] ?: 0.0) * 100) / 100.0) }
    }
}
