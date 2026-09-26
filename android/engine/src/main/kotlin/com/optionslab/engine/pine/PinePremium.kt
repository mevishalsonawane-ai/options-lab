package com.optionslab.engine.pine

import com.optionslab.engine.Right
import com.optionslab.engine.Session
import com.optionslab.engine.Series
import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * Re-prices a backtest's trades as the auto-trader would place them: a long buys the ATM
 * CALL, a short buys the ATM PUT (or is skipped when shorts only exit), of the nearest
 * expiry after the entry day, for [lots] lots, at that option's own 1-minute prices, with
 * Zerodha's charges and [slippage] rupees per unit against each fill.
 *
 * Only days with option data on the phone can be priced (bundled and harvested sessions);
 * the rest are counted as skipped, with the reason.
 */
object PinePremium {
    data class Result(val report: Pine.Report?, val priced: Int, val skipped: Int, val reasons: Map<String, Int>)

    fun run(
        base: List<Pine.Trade>, bars: List<Pine.Bar>, sessionFor: (LocalDate) -> Session?,
        strikeStep: Int, lots: Int, capital: Double, shortsBuyPuts: Boolean = true, slippage: Double = 0.0,
    ): Result {
        val reasons = LinkedHashMap<String, Int>()
        fun skip(why: String) { reasons[why] = (reasons[why] ?: 0) + 1 }
        val cache = HashMap<LocalDate, Session?>()
        fun session(d: LocalDate) = cache.getOrPut(d) { runCatching { sessionFor(d) }.getOrNull() }
        val out = ArrayList<Pine.Trade>()
        var commission = 0.0
        for (t in base.filter { !it.open }) {
            if (!t.long && !shortsBuyPuts) { skip("shorts only exit"); continue }
            val inAt = Instant.ofEpochSecond(t.entryTime).atZone(Pine.IST)
            val outAt = Instant.ofEpochSecond(t.exitTime).atZone(Pine.IST)
            val s1 = session(inAt.toLocalDate()) ?: run { skip("no option data for the entry day"); null } ?: continue
            val right = if (t.long) Right.CE else Right.PE
            val strike = round(t.entryPrice / strikeStep) * strikeStep
            val expiries = s1.options.mapNotNull { it.expiry }.distinct().sorted()
            val expiry = expiries.firstOrNull { it.isAfter(inAt.toLocalDate()) } ?: expiries.firstOrNull { !it.isBefore(inAt.toLocalDate()) }
                ?: run { skip("no listed expiry"); null } ?: continue
            if (outAt.toLocalDate().isAfter(expiry)) { skip("held past the option's expiry"); continue }
            fun pick(s: Session): Series? = s.options.filter { it.expiry == expiry && it.right == right }
                .minByOrNull { abs(it.strike - strike) }?.takeIf { abs(it.strike - strike) <= strikeStep }
            val o1 = pick(s1) ?: run { skip("the ATM strike is not in the data"); null } ?: continue
            val s2 = if (outAt.toLocalDate() == inAt.toLocalDate()) s1 else session(outAt.toLocalDate())
            val o2 = s2?.options?.firstOrNull { it.expiry == expiry && it.right == right && it.strike == o1.strike }
                ?: run { skip("no option data for the exit day"); null } ?: continue
            val pIn = priceAt(o1, inAt.hour * 60 + inAt.minute) ?: run { skip("no option price at entry"); null } ?: continue
            val pOut = priceAt(o2, outAt.hour * 60 + outAt.minute) ?: run { skip("no option price at exit"); null } ?: continue
            val lot = if (o1.lot > 0) o1.lot else s1.lotHint ?: 1
            val qty = lot * lots.coerceIn(1, 100)
            val buy = pIn + slippage; val sell = max(0.05, pOut - slippage)
            val c = SandboxCosts.charge("BUY", BigDecimal(buy), qty).toDouble() + SandboxCosts.charge("SELL", BigDecimal(sell), qty).toDouble()
            commission += c
            val pnl = (sell - buy) * qty - c
            out += Pine.Trade("${o1.strike.toInt()} ${right.name}", t.exitId, true, qty.toDouble(), t.entryBar, t.entryTime, buy,
                t.exitBar, t.exitTime, sell, pnl, pnl / (buy * qty) * 100, c, false)
        }
        if (out.isEmpty()) return Result(null, 0, base.count { !it.open }, reasons)
        return Result(reportOf(out, bars, capital, commission), out.size, reasons.values.sum(), reasons)
    }

    /** The option's price at a minute: that minute's open, else the last close before it. */
    private fun priceAt(s: Series, minute: Int): Double? {
        val exact = s.indexOf(minute)
        if (exact >= 0) s.open?.get(exact)?.takeIf { it > 0 }?.let { return it }
        val i = s.lastAtOrBefore(minute)
        return if (i >= 0) s.close[i].takeIf { it > 0 } else null
    }

    /** A report from closed trades: the equity steps at each exit. */
    fun reportOf(trades: List<Pine.Trade>, bars: List<Pine.Bar>, capital: Double, commission: Double): Pine.Report {
        val equity = DoubleArray(bars.size) { capital }
        val byExit = trades.groupBy { it.exitBar }
        var run = capital
        for (i in bars.indices) { byExit[i]?.let { l -> run += l.sumOf { it.pnl } }; equity[i] = run }
        var peak = capital; var dd = 0.0; var ddPct = 0.0
        for (e in equity) { peak = max(peak, e); if (peak - e > dd) { dd = peak - e; ddPct = if (peak > 0) dd / peak * 100 else 0.0 } }
        val wins = trades.filter { it.pnl > 0 }; val losses = trades.filter { it.pnl < 0 }
        val gp = wins.sumOf { it.pnl }; val gl = -losses.sumOf { it.pnl }
        val net = trades.sumOf { it.pnl }
        val first = bars.firstOrNull()?.close ?: 0.0; val last = bars.lastOrNull()?.close ?: 0.0
        val r = Pine.Report(capital, net, net / capital * 100, gp, gl, if (gl > 0) gp / gl else null, trades.size, wins.size, losses.size,
            wins.size * 100.0 / trades.size, net / trades.size, if (wins.isEmpty()) 0.0 else gp / wins.size, if (losses.isEmpty()) 0.0 else -gl / losses.size,
            wins.maxOfOrNull { it.pnl } ?: 0.0, losses.minOfOrNull { it.pnl } ?: 0.0, dd, ddPct,
            if (first > 0) (last - first) / first * 100 else 0.0, commission, 0.0,
            trades.map { (it.exitBar - it.entryBar).toDouble() }.average(), trades.sortedBy { it.entryTime }, equity)
        return r.copy(extra = runCatching { Pine.extraOf(r, bars) }.getOrNull())
    }
}
