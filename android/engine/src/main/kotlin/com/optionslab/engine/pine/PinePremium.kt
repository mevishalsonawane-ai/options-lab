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
 * Zerodha's charges and [slippage] rupees per unit against each fill. Each fill is priced at
 * its own moment (a bar's open, its close, or inside it), and the pieces of an entry closed by
 * partial exits share its [lots].
 *
 * Only days with option data on the phone can be priced (bundled and harvested sessions);
 * the rest are counted as skipped, with the reason.
 *
 * With [Protect] (the script's own auto-trade exits: Boss's 06 Oct rule makes the stop-loss, the target and the profit
 * lock compulsory on every Pine strategy) each option is also sold, as the auto-trader would, at the first minute after
 * the buy whose range reaches the premium stop (entry - stop), the target (entry + target) or the profit lock's level
 * (com.optionslab.engine.orb.ProfitLock: the ladder and the trail, from the best price before that minute), whichever
 * comes first - the stop first within a minute (the cautious reading).
 */
object PinePremium {
    data class Result(val report: Pine.Report?, val priced: Int, val skipped: Int, val reasons: Map<String, Int>)

    /** The auto-trader's own exits on the option, in premium points (0: none), and the profit lock with its [trail]. */
    data class Protect(val stopPts: Double, val targetPts: Double, val lock: Boolean = true,
                       val trail: com.optionslab.engine.orb.ProfitLock.Trail? = com.optionslab.engine.orb.ProfitLock.Trail())

    /** Where [Protect] sold: the exit's name, price and moment (epoch seconds). */
    internal data class Out(val why: String, val price: Double, val at: Long)

    fun run(
        base: List<Pine.Trade>, bars: List<Pine.Bar>, sessionFor: (LocalDate) -> Session?,
        strikeStep: Int, lots: Int, capital: Double, shortsBuyPuts: Boolean = true, slippage: Double = 0.0,
        protect: Protect? = null,
    ): Result {
        val reasons = LinkedHashMap<String, Int>()
        fun skip(why: String) { reasons[why] = (reasons[why] ?: 0) + 1 }
        val cache = HashMap<LocalDate, Session?>()
        fun session(d: LocalDate) = cache.getOrPut(d) { runCatching { sessionFor(d) }.getOrNull() }
        val out = ArrayList<Pine.Trade>()
        var commission = 0.0
        val lotsFor = lotShares(base, lots.coerceIn(1, 100))
        for ((n, t) in base.withIndex()) {
            if (t.open) continue
            if (!t.long && !shortsBuyPuts) { skip("shorts only exit"); continue }
            val pieceLots = lotsFor[n]
            if (pieceLots <= 0) { skip("partial exit under one lot"); continue }
            // The fills' own moments, not the bars' starts: a fill at a bar's close or inside it
            // must not be priced at the bar's open (that would look ahead up to a bar).
            val inAtSec0 = t.entryFillTime ?: t.entryTime
            val outAtSec0 = t.exitFillTime ?: t.exitTime
            val inDay = Instant.ofEpochSecond(inAtSec0).atZone(Pine.IST).toLocalDate()
            val s1 = session(inDay) ?: run { skip("no option data for the entry day"); null } ?: continue
            val inAtSec = if (t.entryIntrabar) firstTouch(s1.index, t.entryTime, inAtSec0, t.entryPrice) ?: inAtSec0 else inAtSec0
            val outDay = Instant.ofEpochSecond(outAtSec0).atZone(Pine.IST).toLocalDate()
            val outAtSec = if (t.exitIntrabar) firstTouch(session(outDay)?.index, t.exitTime, outAtSec0, t.exitPrice) ?: outAtSec0 else outAtSec0
            val inAt = Instant.ofEpochSecond(inAtSec).atZone(Pine.IST)
            val outAt = Instant.ofEpochSecond(outAtSec).atZone(Pine.IST)
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
            val pIn = priceAt(o1, inAtSec) ?: run { skip("no option price at entry"); null } ?: continue
            val lot = if (o1.lot > 0) o1.lot else s1.lotHint ?: 1
            val qty = lot * pieceLots
            val sold = protect?.let { protectedExit(o1, pIn, qty, inAtSec, outAtSec, it) }
            val pOut = sold?.price ?: priceAt(o2, outAtSec) ?: run { skip("no option price at exit"); null } ?: continue
            val exitSec = sold?.at ?: outAtSec
            val exitBar = if (sold == null) t.exitBar else max(t.entryBar, bars.indexOfLast { it.time <= exitSec })
            val buy = pIn + slippage; val sell = max(0.05, pOut - slippage)
            val c = SandboxCosts.charge("BUY", BigDecimal(buy), qty).toDouble() + SandboxCosts.charge("SELL", BigDecimal(sell), qty).toDouble()
            commission += c
            val pnl = (sell - buy) * qty - c
            out += Pine.Trade("${o1.strike.toInt()} ${right.name}", sold?.why ?: t.exitId, true, qty.toDouble(), t.entryBar, t.entryTime, buy,
                exitBar, if (sold == null) t.exitTime else bars.getOrNull(exitBar)?.time ?: exitSec, sell, pnl, pnl / (buy * qty) * 100, c, false,
                entryFillTime = inAtSec, exitFillTime = exitSec)
        }
        if (out.isEmpty()) return Result(null, 0, base.count { !it.open }, reasons)
        return Result(reportOf(out, bars, capital, commission), out.size, reasons.values.sum(), reasons)
    }

    /**
     * The first minute of [o] after the buy at [entry] (at [inAt]) and up to the script's own exit ([outAt]; on a later
     * day: the entry day's last minute) whose range reaches the stop, the target or the profit lock's level, and the price
     * it sold at (the level, or the minute's open when it opened past it). Null when none was reached.
     */
    internal fun protectedExit(o: Series, entry: Double, qty: Int, inAt: Long, outAt: Long, p: Protect): Out? {
        val zIn = Instant.ofEpochSecond(inAt).atZone(Pine.IST); val zOut = Instant.ofEpochSecond(outAt).atZone(Pine.IST)
        val midnight = zIn.toLocalDate().atStartOfDay(Pine.IST).toEpochSecond()
        val first = zIn.hour * 60 + zIn.minute + if (zIn.second == 0) 0 else 1
        val sameDay = zOut.toLocalDate() == zIn.toLocalDate()
        val last = if (sameDay) zOut.hour * 60 + zOut.minute - if (zOut.second == 0) 1 else 0 else Int.MAX_VALUE
        val stop = if (p.stopPts > 0) entry - p.stopPts else null
        val target = if (p.targetPts > 0) entry + p.targetPts else null
        val ref = if (p.lock) com.optionslab.engine.orb.ProfitLock.pineReference(p.targetPts, p.stopPts) else null
        val trail = if (p.lock) p.trail else null
        val cost = com.optionslab.engine.orb.ProfitLock.roundTripPerUnit(entry, qty)
        var peak = entry
        for (j in 0 until o.size) {
            val m = o.minutes[j]
            if (m < first) continue
            if (m > last) break
            val c = o.close[j]
            val op = o.open?.get(j)?.takeIf { it > 0 } ?: c
            val hi = o.high?.get(j)?.takeIf { it > 0 } ?: max(op, c)
            val lo = o.low?.get(j)?.takeIf { it > 0 } ?: minOf(op, c)
            val at = midnight + m * 60L + 59
            val locked = if (p.lock) com.optionslab.engine.orb.ProfitLock.lockLevel(entry, ref, trail, cost, peak) else null
            when {
                stop != null && lo <= stop -> return Out("stop-loss", minOf(stop, op), at)
                target != null && hi >= target -> return Out("target", max(target, op), at)
                locked != null && lo <= locked -> return Out("profit lock", minOf(locked, op), at)
            }
            peak = max(peak, hi)
        }
        return null
    }

    /**
     * The lots each trade of [base] is priced at. A trade is one piece of an entry (partial exits
     * split it): the entry's [lots] are shared by the pieces' quantities, in whole lots, the
     * remainders going to the largest fractions (earliest first), so the pieces add up to [lots]
     * and a piece under one lot gets none. Open pieces count toward the share.
     */
    internal fun lotShares(base: List<Pine.Trade>, lots: Int): IntArray {
        val out = IntArray(base.size)
        val groups = base.indices.groupBy { Triple(base[it].entryId, base[it].entryBar, base[it].long) }
        for (idx in groups.values) {
            val total = idx.sumOf { base[it].qty }
            if (!(total > 0)) { idx.forEach { out[it] = lots }; continue }
            val exact = idx.map { base[it].qty / total * lots }
            val whole = exact.map { kotlin.math.floor(it + 1e-9).toInt() }.toIntArray()
            var left = lots - whole.sum()
            for (j in exact.indices.sortedByDescending { exact[it] - whole[it] }) { if (left <= 0) break; whole[j]++; left-- }
            idx.forEachIndexed { j, n -> out[n] = whole[j] }
        }
        return out
    }

    /**
     * The first minute of the underlying, from the bar's start [from] up to [to] (same IST day),
     * whose range reaches [level] - when an intrabar stop or limit most likely filled. Its end
     * (second 59: by its close) is returned, or null without minute data for the underlying.
     */
    private fun firstTouch(ix: Series?, from: Long, to: Long, level: Double): Long? {
        ix ?: return null
        val z0 = Instant.ofEpochSecond(from).atZone(Pine.IST); val z1 = Instant.ofEpochSecond(to).atZone(Pine.IST)
        if (z0.toLocalDate() != z1.toLocalDate()) return null
        val m0 = z0.hour * 60 + z0.minute; val m1 = z1.hour * 60 + z1.minute
        val midnight = z0.toLocalDate().atStartOfDay(Pine.IST).toEpochSecond()
        var prev = ix.lastAtOrBefore(m0 - 1).takeIf { it >= 0 }?.let { ix.close[it] }
        for (j in 0 until ix.size) {
            val m = ix.minutes[j]
            if (m < m0) continue
            if (m > m1) break
            val c = ix.close[j]
            val hi = ix.high?.get(j); val lo = ix.low?.get(j)
            val o = ix.open?.get(j) ?: prev ?: c
            val touched = if (hi != null && lo != null && hi >= lo) level in lo..hi else level in minOf(o, c)..maxOf(o, c)
            if (touched) return midnight + m * 60L + 59
            prev = c
        }
        return null
    }

    /**
     * The option's price at the instant [at] (epoch seconds). At a minute's first second: that
     * minute's open, else the last close before it. Later in the minute: that minute's close
     * (or the last one before it) - never a price from after the fill.
     */
    private fun priceAt(s: Series, at: Long): Double? {
        val z = Instant.ofEpochSecond(at).atZone(Pine.IST)
        val minute = z.hour * 60 + z.minute
        if (z.second == 0) {
            val exact = s.indexOf(minute)
            if (exact >= 0) s.open?.get(exact)?.takeIf { it > 0 }?.let { return it }
            // No open recorded: the close just before; the minute's own close only at the day's first minute.
            val i = s.lastAtOrBefore(minute - 1).takeIf { it >= 0 } ?: s.lastAtOrBefore(minute)
            return if (i >= 0) s.close[i].takeIf { it > 0 } else null
        }
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
