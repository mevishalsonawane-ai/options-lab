package com.optionslab.ira

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The trade manager's rules as research round 12 tested them (research/HUNT_R12_TRADE_MANAGER.md; the exact code is
 * research/hunt/r12/specs.py and lib12.py). Each is a plain function of the held option's 1-minute bars (or the index's
 * minutes) so the specialists ([ManagerTeam]) can vote with them and the tests can check them against the Python by hand.
 *
 * The research's timing, kept here:
 *  - a column is one minute; the entry's column (c0) is the minute the entry filled in, and it counts as minute 1 held;
 *  - a rule is decided on a minute's CLOSE, using data up to that minute only (the app then exits at its next look);
 *  - only minutes that have closed (start + 60 s at or before now) count, and only the bars the app has loaded: a minute
 *    not loaded yet is never assumed (a late bar only makes a decision later, never wrong);
 *  - locks are levels from the next minute on, raised only, a proposal at or above 99% of its own minute's close dropped
 *    (lib12.replay: `pr = where(v & (pr < cap), pr, NEG)`).
 * Pure.
 */
object R12Rules {
    const val TICK = 0.05
    const val MINUTE_MS = 60_000L
    /** The option's ATR: the mean 1-minute range (high − low) of the previous 10 minutes (lib12.rolling_mean_prev(rng, 10)). */
    const val ATR_MINUTES = 10

    fun col(ms: Long): Long = Math.floorDiv(ms, MINUTE_MS)

    /** The bars that have closed by [nowMs] with finite prices, oldest first. */
    fun closed(bars: List<TradeManager.Bar>, nowMs: Long): List<TradeManager.Bar> =
        bars.filter { it.startMs + MINUTE_MS <= nowMs && it.high.isFinite() && it.low.isFinite() && it.close.isFinite() }.sortedBy { it.startMs }

    /** The best high from the entry's minute to [k] (inclusive), never under the entry (specs._peak_incl). */
    fun peak(bars: List<TradeManager.Bar>, entry: Double, c0: Long, k: Long): Double {
        var best = entry
        for (b in bars) { val c = col(b.startMs); if (c in c0..k && b.high > best) best = b.high }
        return best
    }

    /**
     * PA(T, m), "no progress" (specs.PA): T minutes after the entry (the entry's minute is minute 1) the best high is still
     * under entry × (1 + m) -> exit, decided on that minute's close. The best high only rises, so it can only fire at that one
     * minute: the minute's column, or null (not yet, or it made progress).
     */
    fun pa(bars: List<TradeManager.Bar>, entry: Double, entryMs: Long, nowMs: Long, t: Int, m: Double): Long? {
        val cs = closed(bars, nowMs)
        val c0 = col(entryMs)
        val k = c0 + t - 1
        // The minute has closed AND its bar (or a later one) is loaded: the best high through it is known.
        val last = cs.lastOrNull()?.let { col(it.startMs) } ?: return null
        if (last < k) return null
        return if (peak(cs, entry, c0, k) < entry * (1 + m)) k else null
    }

    /** PB(a), adverse close (specs.PB): the first minute from the entry's that closes at or under entry × (1 − a); null: none. */
    fun pb(bars: List<TradeManager.Bar>, entry: Double, entryMs: Long, nowMs: Long, a: Double): Long? {
        val c0 = col(entryMs)
        return closed(bars, nowMs).firstOrNull { col(it.startMs) >= c0 && it.close <= entry * (1 - a) }?.let { col(it.startMs) }
    }

    /** The mean range of the bars in the [ATR_MINUTES] minutes before column [k] (NaN-aware: the ones that printed); null: none. */
    fun atr(bars: List<TradeManager.Bar>, k: Long): Double? {
        var s = 0.0; var n = 0
        for (b in bars) { val c = col(b.startMs); if (c >= k - ATR_MINUTES && c <= k - 1) { s += b.high - b.low; n++ } }
        return if (n > 0) s / n else null
    }

    private fun cap(close: Double, tick: Double) = floor(close * 0.99 / tick + 1e-9) * tick
    private fun clean(x: Double) = Math.round(x * 1e6) / 1e6

    /**
     * VC(k, a), the volatility trail lock (specs.VC): at each closed minute from the entry's, once the best high is at least
     * a × ATR above the entry, the lock wanted from the next minute is floor_tick(best high − k × ATR), ATR = the mean
     * 1-minute range of the previous 10 minutes. Raised only. Returns the lock standing after the last closed minute (null:
     * none yet). The caller never lets it below the stop already resting (it only tightens).
     */
    fun vc(bars: List<TradeManager.Bar>, entry: Double, entryMs: Long, nowMs: Long, k: Double, a: Double, tick: Double = TICK): Double? {
        val cs = closed(bars, nowMs)
        val c0 = col(entryMs)
        var pk = entry
        var lock = Double.NEGATIVE_INFINITY
        for (b in cs) {
            val c = col(b.startMs)
            if (c < c0) continue
            if (b.high > pk) pk = b.high
            val atr = atr(cs, c) ?: continue
            val lv = pk - k * atr
            if (!(pk - entry >= a * atr) || !lv.isFinite()) continue
            val pr = floor(lv / tick) * tick
            if (pr < cap(b.close, tick) && pr > lock) lock = pr
        }
        return if (lock.isFinite()) clean(lock) else null
    }

    /**
     * BE(b), the breakeven lock (specs.BE): once the best high reaches entry × (1 + b), the lock is breakeven plus the round
     * trip's charges ([breakeven]), rounded up to the tick. Null: not yet.
     */
    fun be(bars: List<TradeManager.Bar>, entry: Double, entryMs: Long, nowMs: Long, b: Double, breakeven: Double, tick: Double = TICK): Double? {
        val lv = ceil(breakeven / tick - 1e-9) * tick
        val c0 = col(entryMs)
        var pk = entry
        for (x in closed(bars, nowMs)) {
            if (col(x.startMs) < c0) continue
            if (x.high > pk) pk = x.high
            if (pk >= entry * (1 + b) && lv < cap(x.close, tick)) return clean(lv)
        }
        return null
    }

    /** One minute of the index (the future in the app): its close and the session VWAP with its standard deviation then. */
    data class IndexMinute(val startMs: Long, val close: Double, val vwap: Double? = null, val sd: Double? = null)

    /**
     * VW(z, p), the VWAP exit (specs.VW): the index closes on the wrong side of the session VWAP by more than z SD
     * (side × (close − VWAP) < −z × SD) for p minutes in a row -> exit, the first such minute from the entry's. The run of
     * minutes counts from before the entry too (as lib12's `_persist` over the whole day). Null: none.
     */
    fun vw(minutes: List<IndexMinute>, side: Int, entryMs: Long, nowMs: Long, z: Double, p: Int): Long? {
        val c0 = col(entryMs)
        var run = 0
        for (x in minutes.filter { it.startMs + MINUTE_MS <= nowMs }.sortedBy { it.startMs }) {
            val vw = x.vwap; val sd = x.sd
            val wrong = vw != null && sd != null && side * (x.close - vw) < -z * sd
            run = if (wrong) run + 1 else 0
            if (col(x.startMs) >= c0 && run >= maxOf(p, 1)) return col(x.startMs)
        }
        return null
    }

    /**
     * MP(prior, p), back inside the prior day's value area (specs.MP with which = "prior"): the index was outside the prior
     * day's value area the trade's way (above its high for a call, below its low for a put) at the minute before the entry or
     * at any minute since, and has closed back inside for p minutes in a row -> exit, the first such minute. Null: none, or
     * no prior value area.
     */
    fun mp(minutes: List<IndexMinute>, side: Int, entryMs: Long, nowMs: Long, priorHigh: Double?, priorLow: Double?, p: Int): Long? {
        if (priorHigh == null || priorLow == null) return null
        val c0 = col(entryMs)
        val ms = minutes.filter { it.startMs + MINUTE_MS <= nowMs }.sortedBy { it.startMs }
        fun out(c: Double) = if (side > 0) c > priorHigh else c < priorLow
        fun back(c: Double) = if (side > 0) c < priorHigh else c > priorLow
        var was = ms.lastOrNull { col(it.startMs) == c0 - 1 }?.let { out(it.close) } ?: false
        var run = 0
        for (x in ms) {
            val c = col(x.startMs)
            run = if (back(x.close)) run + 1 else 0
            if (c < c0) continue
            if (out(x.close)) was = true
            if (was && run >= maxOf(p, 1)) return c
        }
        return null
    }

    /** OI(z0) (specs.OI): the put-minus-call OI change z against the trade at −z0 or worse. Null reading: no vote. */
    fun oi(z: Double?, side: Int, z0: Double): Boolean = z != null && z.isFinite() && side * z <= -z0

    /** DP(z0), the delta PROXY (specs.DP): the 10-minute signed option flow z against the trade at −z0 or worse. */
    fun dp(z: Double?, side: Int, z0: Double): Boolean = z != null && z.isFinite() && side * z <= -z0

    /** The extension's VWAP vote (specs.EXT xVW): side × (close − VWAP) at least [x] SD the trade's way. */
    fun xvw(close: Double?, vwap: Double?, sd: Double?, side: Int, x: Double): Boolean =
        close != null && vwap != null && sd != null && side * (close - vwap) >= x * sd

    /** The extension's flow vote (specs.EXT xDP): the signed option flow z at least [x] the trade's way. */
    fun xdp(z: Double?, side: Int, x: Double): Boolean = z != null && z.isFinite() && side * z >= x
}
