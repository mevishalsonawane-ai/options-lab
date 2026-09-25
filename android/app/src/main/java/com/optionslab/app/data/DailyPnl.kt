package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.sign

/**
 * One P&L figure per trading day, per account, for the P&L calendar.
 *
 *  - Today is recorded as it goes (the market watch and the open app), and the
 *    last reading of the day stays: realised + open P&L after charges.
 *  - The paper account's earlier days are also rebuilt from its own trade
 *    book (average-cost, charges included) for days that were never recorded.
 *  - Zerodha does not give past days' P&L through its API, so the Zerodha
 *    calendar fills from the day IraAlgo first recorded it.
 *
 * Private like everything about the account: kept in the encrypted vault.
 */
object DailyPnl {
    data class Day(val date: LocalDate, val pnl: Double, val trades: Int)

    private fun key(live: Boolean) = if (live) "pnl.days.live" else "pnl.days.paper"

    @Synchronized
    private fun read(live: Boolean): JSONObject = runCatching { JSONObject(SecurePrefs.getString(key(live)) ?: "{}") }.getOrDefault(JSONObject())

    /** Today's figure for the account; [trades] < 0 keeps the count already stored. */
    @Synchronized
    fun record(live: Boolean, pnl: Double, trades: Int) {
        if (!pnl.isFinite()) return
        val o = read(live)
        val today = Market.today().toString()
        val keepTrades = o.optJSONArray(today)?.optInt(1, 0) ?: 0
        o.put(today, JSONArray().put(Math.round(pnl * 100) / 100.0).put(if (trades >= 0) trades else keepTrades))
        // About three years of days is plenty; the oldest go first.
        val keys = o.keys().asSequence().toList().sorted()
        keys.dropLast(1100).forEach { o.remove(it) }
        SecurePrefs.put(key(live), o.toString())
    }

    /** Every day of [month] that has a figure. */
    fun month(live: Boolean, month: YearMonth): Map<LocalDate, Day> {
        val out = HashMap<LocalDate, Day>()
        if (!live) runCatching { rebuildPaper() }.getOrNull()?.forEach { (d, v) -> if (YearMonth.from(d) == month) out[d] = v }
        val o = read(live)
        o.keys().forEach { k ->
            val d = runCatching { LocalDate.parse(k) }.getOrNull() ?: return@forEach
            if (YearMonth.from(d) != month) return@forEach
            val a = o.getJSONArray(k)
            // A recorded day wins: it includes expiry settlements and open positions the trade book cannot see.
            out[d] = Day(d, a.getDouble(0), maxOf(a.optInt(1, 0), out[d]?.trades ?: 0))
        }
        return out
    }

    /** Earliest month with any figure, so the calendar knows where history starts. */
    fun firstMonth(live: Boolean): YearMonth? {
        val recorded = read(live).keys().asSequence().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.minOrNull()
        val traded = if (live) null else Paper.state.trades.minOfOrNull { it.timestamp.toLocalDate() }
        return listOfNotNull(recorded, traded).minOrNull()?.let { YearMonth.from(it) }
    }

    /** Realised P&L per day from the paper trade book: average cost per contract, less each day's charges. */
    private fun rebuildPaper(): Map<LocalDate, Day> {
        data class Pos(var qty: Int = 0, var avg: Double = 0.0)
        val pos = HashMap<String, Pos>()
        val pnl = HashMap<LocalDate, Double>()
        val count = HashMap<LocalDate, Int>()
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
            pnl[d] = (pnl[d] ?: 0.0) - t.charges.toDouble()
            count[d] = (count[d] ?: 0) + 1
        }
        return pnl.mapValues { (d, v) -> Day(d, Math.round(v * 100) / 100.0, count[d] ?: 0) }
    }
}
