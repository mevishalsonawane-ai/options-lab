package com.optionslab.ira

import com.optionslab.engine.sandbox.PaperSpread
import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Boss's "OI wall blast" framework as a SHADOW LOG (Boss approved, 14 Oct 2026; research/HUNT_R26_OI_WALL_BLAST.md and
 * research/hunt/r26/PREREG.md on branch claude/r26-oi-wall-blast). R26 tested it on 770 expiries: 0 of 10 versions passed,
 * so this NEVER places, changes, skips or resizes an order, paper or live, and raises no alert that looks like a trade
 * signal. It only writes down, on expiry days, whether the pre-registered rule P fired and what that ticket would have
 * done, to build the forward sample R26 asked for (look again after 30 triggers).
 *
 * Rule P, exactly as research/hunt/r26/r26lib.py (row t = the 1-minute bar starting 09:15 + t, closing at t + 1; times are
 * bar CLOSE times; a decision at row t reads rows <= t only and acts at row t + 1):
 *  - the Call Wall (Put Wall) is the strike with the most call (put) OI at the 13:00 close among the strikes "stored" then
 *    (a reading in rows r-4 .. r); a tie goes to the strike nearest spot;
 *  - UP at row t (13:45 .. 14:30): spot's 16 closes t-15 .. t all above the wall, and a close at or below it from 13:00 to
 *    t-16; the wall's call OI (each strike's last OI > 0, carried forward) down >= [THETA] (15% NIFTY / SENSEX, 25%
 *    BANKNIFTY / FINNIFTY) from its peak since 12:00; the chain's last-15-minute volume >= 2x the session's average per 15
 *    minutes; spot up over 15 minutes; the summed call OI of the strikes stored at both t and t-15 lower than at t-15;
 *  - DOWN mirrors it with the Put Wall; UP is checked first;
 *  - the ATM+1 CE (ATM-1 PE) must have traded in the last 5 bars at Rs 15-30; else that minute does not qualify;
 *  - the first minute that qualifies is the one trigger of the index's expiry.
 *
 * The ticket (Rs 2,000 in whole lots, at least one) is priced with the APP'S PAPER COST MODEL (not R25's): a buy limit at
 * 1.2x the signal price + 0.10 worked for 3 minutes from the next bar's open, each aggressive fill moved by the larger of
 * 5 bps and the index's measured half-spread ([PaperSpread]), a target resting at its price, the real charges
 * ([SandboxCosts]). Four exits, as R26 reported them: [Exit.TARGET_4X] (4x, else the 15:05 square-off: the rule's own),
 * [Exit.HOLD_1505], [Exit.HOLD_1520] and [Exit.STEP_LOCK] (Boss's style: half at 2x, then a step lock 2x -> 1x, 3x -> 2x,
 * ...; else 15:05). Results are in POINTS a unit after costs. Pure.
 *
 * Live data (the app's [com.optionslab.app.data.OiWallShadowHost]): spot closes, each option's OI, its cumulative day
 * volume and price, from what the phone already holds in memory (the price stream's ticks, the last chain read). The
 * chain's volume is read over the options with a volume reading at both t and t-15 (the lake summed every stored option's
 * minute volume); a strike's readings count as stored for 5 rows, as a lake bar did.
 */
object OiWallBlast {
    const val N = 375
    const val HOLD = 16
    const val LOOK = 15
    const val VOL_MULT = 2.0
    const val BAND_LO = 15.0
    const val BAND_HI = 30.0
    const val TARGET = 4.0
    const val BUDGET = 2000.0
    const val TICK = 0.05
    const val EPS = 1e-9
    const val TRADED_BARS = 5
    const val FILL_TRIES = 3
    /** The floor the app's paper MARKET fills move by (Paper.kt: spreadFallbackBps 5). */
    val FLOOR_BPS: BigDecimal = BigDecimal("5")

    val THETA: Map<String, Double> = linkedMapOf("NIFTY" to 0.15, "SENSEX" to 0.15, "BANKNIFTY" to 0.25, "FINNIFTY" to 0.25)
    val STEP: Map<String, Double> = linkedMapOf("NIFTY" to 50.0, "FINNIFTY" to 50.0, "BANKNIFTY" to 100.0, "SENSEX" to 100.0)
    /** The indices R26 studied, in the order said. */
    val INDICES: List<String> = THETA.keys.toList()

    /** Row of the bar that STARTS at [t] (R25's minute()). */
    fun minute(t: LocalTime): Int = t.hour * 60 + t.minute - (9 * 60 + 15)
    /** Row of the bar that CLOSES at [t] (R25's close_row()). */
    fun closeRow(t: LocalTime): Int = minute(t) - 1
    /** When row [r]'s bar closes. */
    fun closeTime(r: Int): LocalTime = LocalTime.of(9, 15).plusMinutes((r + 1).toLong())

    val ANCHOR = closeRow(LocalTime.of(13, 0))
    val NOON = closeRow(LocalTime.of(12, 0))
    val FIRST = closeRow(LocalTime.of(13, 45))
    val LAST = closeRow(LocalTime.of(14, 30))
    val EXIT_1505 = minute(LocalTime.of(15, 5))
    val EXIT_1520 = minute(LocalTime.of(15, 20))

    // ---- one expiry day's readings ---------------------------------------------------------------------------------

    /**
     * One option's reading at a row: its OI (units; null or 0: none), cumulative day volume (null: none), and [close]: the
     * price it traded at in that minute (null: it did not trade then). [open] / [high] / [low]: the minute's bar when known.
     */
    data class Obs(
        val strike: Double, val right: String, val oi: Long? = null, val cumVol: Long? = null, val close: Double? = null,
        val open: Double? = null, val high: Double? = null, val low: Double? = null,
    )

    internal class Track {
        val oi = DoubleArray(N) { Double.NaN }
        val vol = DoubleArray(N) { Double.NaN }
        val has = BooleanArray(N)
        val o = DoubleArray(N) { Double.NaN }
        val h = DoubleArray(N) { Double.NaN }
        val l = DoubleArray(N) { Double.NaN }
        val c = DoubleArray(N) { Double.NaN }
    }

    /** One index's expiry day, filled a row at a time ([record]); every read at row r uses rows <= r only. */
    class Day(val index: String) {
        val spot = DoubleArray(N) { Double.NaN }
        private val tracks = HashMap<Pair<Double, String>, Track>()
        /** Rows recorded (a gap is a row never recorded). */
        val rows = BooleanArray(N)

        /** Row [r]: spot's close and each option seen (a reading of an option makes it "stored" from this row). */
        fun record(r: Int, spotClose: Double?, obs: List<Obs>) {
            if (r !in 0 until N) return
            rows[r] = true
            if (spotClose != null && spotClose.isFinite() && spotClose > 0) spot[r] = spotClose
            for (x in obs) {
                if (x.right != "CE" && x.right != "PE") continue
                val t = tracks.getOrPut(x.strike to x.right) { Track() }
                t.has[r] = true
                x.oi?.takeIf { it > 0 }?.let { t.oi[r] = it.toDouble() }
                x.cumVol?.takeIf { it >= 0 }?.let { t.vol[r] = it.toDouble() }
                x.close?.takeIf { it.isFinite() && it > 0 }?.let { c ->
                    t.c[r] = c
                    t.o[r] = x.open?.takeIf { it.isFinite() && it > 0 } ?: c
                    t.h[r] = maxOf(x.high?.takeIf { it.isFinite() && it > 0 } ?: c, c)
                    t.l[r] = minOf(x.low?.takeIf { it.isFinite() && it > 0 } ?: c, c)
                }
            }
        }

        /** Only an option's minute bar at row [r] (after a trigger: the held option's path). */
        fun bar(r: Int, strike: Double, right: String, open: Double?, high: Double?, low: Double?, close: Double?) {
            if (r !in 0 until N || close == null || !close.isFinite() || close <= 0) return
            val t = tracks.getOrPut(strike to right) { Track() }
            t.c[r] = close
            t.o[r] = open?.takeIf { it.isFinite() && it > 0 } ?: close
            t.h[r] = maxOf(high?.takeIf { it.isFinite() && it > 0 } ?: close, close)
            t.l[r] = minOf(low?.takeIf { it.isFinite() && it > 0 } ?: close, close)
        }

        internal fun strikes(right: String): List<Double> = tracks.keys.filter { it.second == right }.map { it.first }.sorted()

        /** The strike's OI at row r: its last OI > 0 in rows <= r (NaN: none yet). */
        fun oiAt(k: Double, right: String, r: Int): Double {
            val t = tracks[k to right] ?: return Double.NaN
            for (i in minOf(r, N - 1) downTo 0) if (t.oi[i].isFinite()) return t.oi[i]
            return Double.NaN
        }

        /** The option had a reading in rows r-4 .. r ("stored", as a lake bar in the last 5 rows). */
        fun present(k: Double, right: String, r: Int): Boolean {
            val t = tracks[k to right] ?: return false
            for (i in maxOf(0, r - 4)..minOf(r, N - 1)) if (t.has[i]) return true
            return false
        }

        /** The option's cumulative volume at row r: its last reading in rows r-4 .. r (NaN: none). */
        fun volAt(k: Double, right: String, r: Int): Double {
            val t = tracks[k to right] ?: return Double.NaN
            for (i in minOf(r, N - 1) downTo maxOf(0, r - 4)) if (t.vol[i].isFinite()) return t.vol[i]
            return Double.NaN
        }

        /** The last traded close at or before r and its row (NaN, -1: none). */
        fun ltp(k: Double, right: String, r: Int): Pair<Double, Int> {
            val t = tracks[k to right] ?: return Double.NaN to -1
            for (i in minOf(r, N - 1) downTo 0) if (t.c[i].isFinite()) return t.c[i] to i
            return Double.NaN to -1
        }

        internal fun bars(k: Double, right: String): Track? = tracks[k to right]
    }

    // ---- the walls and the conditions ------------------------------------------------------------------------------

    /** (strike, outermost stored strike?) of the highest-OI stored strike of [right] at row [r]; null when none. */
    fun wall(d: Day, right: String, r: Int): Pair<Double, Boolean>? {
        if (r < 0) return null
        val stored = d.strikes(right).filter { d.present(it, right, r) }
        val ok = stored.mapNotNull { k -> d.oiAt(k, right, r).takeIf { it.isFinite() }?.let { k to it } }
        if (ok.isEmpty()) return null
        val best = ok.maxOf { it.second }
        val cand = ok.filter { it.second == best }.map { it.first }
        val s = d.spot[r]
        val k = if (s.isFinite()) cand.minBy { abs(it - s) } else cand.first()
        val edge = k == (if (right == "CE") stored.max() else stored.min())
        return k to edge
    }

    /** Summed OI at r minus at r-[LOOK], over the strikes stored at both rows with OI at both (NaN: none). */
    fun sumOiChange(d: Day, right: String, r: Int): Double {
        if (r - LOOK < 0) return Double.NaN
        var a = 0.0; var b = 0.0; var any = false
        for (k in d.strikes(right)) {
            if (!d.present(k, right, r) || !d.present(k, right, r - LOOK)) continue
            val x = d.oiAt(k, right, r); val y = d.oiAt(k, right, r - LOOK)
            if (!x.isFinite() || !y.isFinite()) continue
            a += x; b += y; any = true
        }
        return if (any) a - b else Double.NaN
    }

    /** The chain's last-15-minute volume >= 2x the session's average 15 minutes (over options read at both rows). */
    fun volSpike(d: Day, r: Int): Boolean {
        if (r < LOOK) return false
        var last = 0.0; var total = 0.0
        for (right in listOf("CE", "PE")) for (k in d.strikes(right)) {
            val now = d.volAt(k, right, r); val then = d.volAt(k, right, r - LOOK)
            if (!now.isFinite() || !then.isFinite()) continue
            last += maxOf(0.0, now - then); total += now
        }
        val avg = total * LOOK / (r + 1)
        return avg > 0 && last >= VOL_MULT * avg - 1e-9
    }

    /** Every condition at row [t] for one direction ([sgn] +1 UP, -1 DOWN). */
    data class Conditions(
        val wall: Double?, val edge: Boolean, val brk: Boolean, val oiFall: Boolean, val oiFallPct: Double?, val vol: Boolean,
        val price: Boolean, val sumOi: Boolean,
    ) {
        val fires: Boolean get() = brk && vol && price && oiFall && sumOi
    }

    private val NONE = Conditions(null, false, false, false, null, false, false, false)

    fun conditions(d: Day, sgn: Int, t: Int): Conditions {
        val right = if (sgn > 0) "CE" else "PE"
        if (t - HOLD < 0 || t - HOLD < ANCHOR || t >= N) return NONE
        val (w, edge) = wall(d, right, ANCHOR) ?: return NONE
        val c = d.spot
        var brk = false
        val run = (t - HOLD + 1..t).map { c[it] }
        if (run.none { it.isNaN() }) {
            val beyond = if (sgn > 0) run.all { it > w } else run.all { it < w }
            val was = (ANCHOR..t - HOLD).any { if (sgn > 0) c[it] <= w else c[it] >= w }
            brk = beyond && was
        }
        val now = d.oiAt(w, right, t)
        var ref = Double.NaN
        for (r in NOON..t) { val x = d.oiAt(w, right, r); if (x.isFinite() && (ref.isNaN() || x > ref)) ref = x }
        val theta = THETA[d.index] ?: 0.15
        val fall = now.isFinite() && ref.isFinite() && ref > 0 && now <= (1 - theta) * ref + 1e-9
        val pct = if (now.isFinite() && ref.isFinite() && ref > 0) (1 - now / ref) * 100 else null
        val mv = c[t] - c[t - LOOK]
        val price = !mv.isNaN() && mv * sgn > 0
        val dOi = sumOiChange(d, right, t)
        return Conditions(w, edge, brk, fall, pct, volSpike(d, t), price, !dOi.isNaN() && dOi < 0)
    }

    /** The trigger: the row, the side, the bought strike, its signal price, the wall. */
    data class Signal(val row: Int, val side: Int, val strike: Double, val ref: Double, val wall: Double, val edge: Boolean,
                      val oiFallPct: Double?) {
        val right: String get() = if (side > 0) "CE" else "PE"
        val time: LocalTime get() = closeTime(row)
    }

    /** (strike, signal price) of ATM+1 CE / ATM-1 PE at row t, traded in the last 5 bars at Rs 15-30; null otherwise. */
    fun pick(d: Day, sgn: Int, t: Int): Pair<Double, Double>? {
        val s = d.spot.getOrNull(t) ?: return null
        if (s.isNaN()) return null
        val st = STEP[d.index] ?: return null
        val k = floor(s / st + 0.5) * st + sgn * st
        val (p, row) = d.ltp(k, if (sgn > 0) "CE" else "PE", t)
        if (row < 0 || row < t - (TRADED_BARS - 1) || p.isNaN() || p <= 0) return null
        if (!(BAND_LO - EPS <= p && p <= BAND_HI + EPS)) return null
        return k to p
    }

    /**
     * The first minute in [from] .. [upTo] (within 13:45 .. 14:30) at which rule P (UP first, then DOWN) and the price filter
     * hold. The live log checks each minute once, as it closes ([from] = the first one not checked yet).
     */
    fun signal(d: Day, upTo: Int = LAST, from: Int = FIRST): Signal? {
        if (d.index !in THETA) return null
        for (t in maxOf(FIRST, from)..minOf(LAST, upTo)) for (sgn in intArrayOf(1, -1)) {
            val c = conditions(d, sgn, t)
            if (!c.fires) continue
            val p = pick(d, sgn, t) ?: continue
            return Signal(t, sgn, p.first, p.second, c.wall!!, c.edge, c.oiFallPct)
        }
        return null
    }

    // ---- the ticket ------------------------------------------------------------------------------------------------

    enum class Exit(val label: String, val code: String) {
        TARGET_4X("4x target, else 15:05", "4x"), HOLD_1505("held to 15:05", "1505"), HOLD_1520("held to 15:20", "1520"),
        STEP_LOCK("half at 2x, step lock", "step"),
    }

    data class Sale(val row: Int, val units: Int, val price: Double, val why: String)

    /** One exit's result: the sales, the rupees after charges (kept in the encrypted log only), points a unit after costs. */
    data class Outcome(val exit: Exit, val sales: List<Sale>, val net: Double, val charges: Double, val points: Double, val peakMult: Double)

    data class Ticket(val fillRow: Int, val fill: Double, val lots: Int, val lot: Int, val outcomes: List<Outcome>) {
        val units: Int get() = lots * lot
        fun of(e: Exit): Outcome? = outcomes.firstOrNull { it.exit == e }
    }

    private fun floorTick(x: Double): Double = Math.round(floor(x / TICK + 1e-6) * TICK * 100) / 100.0

    private fun hs(index: String): Double = PaperSpread.DEFAULTS[index] ?: PaperSpread.UNKNOWN

    /** The app's paper MARKET buy / sell of a price (max(5 bps, the half-spread), on the tick, against the order). */
    fun paperBuy(index: String, p: Double): Double =
        PaperSpread.fill(BigDecimal(p.toString()), "BUY", hs(index), FLOOR_BPS, BigDecimal("0.05")).toDouble()
    fun paperSell(index: String, p: Double): Double =
        if (p <= 0) 0.0 else PaperSpread.fill(BigDecimal(p.toString()), "SELL", hs(index), FLOOR_BPS, BigDecimal("0.05")).toDouble()

    private fun charge(buy: Boolean, px: Double, units: Int): Double =
        if (px <= 0 || units <= 0) 0.0 else SandboxCosts.charge(if (buy) "BUY" else "SELL", BigDecimal(px.toString()), units).toDouble()

    /** max(the bar's close, the next bar's open): a target fills only when the high trades a tick through it AND this holds. */
    private fun confirmed(t: Track, r: Int): Double {
        val c = t.c[r]; val nx = if (r + 1 < N) t.o[r + 1] else Double.NaN
        return when { c.isNaN() && nx.isNaN() -> Double.NaN; c.isNaN() -> nx; nx.isNaN() -> c; else -> maxOf(c, nx) }
    }

    private fun nextOpen(t: Track, r: Int): Pair<Int, Double>? {
        for (rr in maxOf(r, 0) until N) if (!t.o[rr].isNaN()) return rr to t.o[rr]
        return null
    }

    /** The entry (row, fill): a buy limit at 1.2x [ref] + 0.10 worked for 3 bars from the next bar's open; null: not filled. */
    fun enter(d: Day, s: Signal): Pair<Int, Double>? {
        val t = d.bars(s.strike, s.right) ?: return null
        val cap = floorTick(s.ref * 1.2 + 0.10)
        for (r in s.row + 1 until minOf(s.row + 1 + FILL_TRIES, EXIT_1505)) {
            val o = t.o[r]
            if (o.isNaN()) continue
            val ask = paperBuy(d.index, o)
            if (ask <= cap + EPS) return r to ask
            val lo = t.l[r]
            if (!lo.isNaN() && paperBuy(d.index, lo) <= cap + EPS) return r to cap
        }
        return null
    }

    /**
     * What the ticket of [s] would have done ([lot]: the contract's lot), from the option's bars in [d]; null when it would
     * not have filled. Each exit as R26's simulate (E2 / E1 / E1b / E3), with the paper cost model.
     */
    fun ticket(d: Day, s: Signal, lot: Int): Ticket? {
        if (lot <= 0) return null
        val (fr, e) = enter(d, s) ?: return null
        val lots = maxOf(1, floor(BUDGET / (e * lot) + 1e-9).toInt())
        val t = d.bars(s.strike, s.right) ?: return null
        return Ticket(fr, e, lots, lot, Exit.entries.map { simulate(d.index, t, fr, e, lots, lot, it) })
    }

    private fun simulate(index: String, a: Track, fr: Int, e: Double, lots: Int, lot: Int, exit: Exit): Outcome {
        val exitRow = if (exit == Exit.HOLD_1520) EXIT_1520 else EXIT_1505
        val sales = ArrayList<Sale>()
        var held = lots
        val t2 = floorTick(2.0 * e); val t4 = floorTick(TARGET * e)
        var halfSold = false
        var bestHi = e
        var lock = Double.NEGATIVE_INFINITY
        var confPeak = e
        var r = fr
        while (r < exitRow && held > 0) {
            val hi = a.h[r]; val c = a.c[r]
            if (!hi.isNaN()) {
                val conf = confirmed(a, r)
                if (!conf.isNaN()) confPeak = maxOf(confPeak, minOf(hi, conf))
                if (exit == Exit.TARGET_4X && hi >= t4 + TICK - EPS && !conf.isNaN() && conf >= t4 - EPS) {
                    sales += Sale(r, held * lot, t4, "4x"); held = 0; break
                }
                if (exit == Exit.STEP_LOCK && !halfSold && hi >= t2 + TICK - EPS && !conf.isNaN() && conf >= t2 - EPS) {
                    val n = if (held == 1) held else minOf(held, maxOf(1, Math.rint(held * 0.5).toInt()))
                    sales += Sale(r, n * lot, t2, "2x"); held -= n; halfSold = true
                    if (held == 0) break
                }
            }
            var why: String? = null
            if (exit == Exit.STEP_LOCK && r > fr && r <= exitRow - 2 && !c.isNaN() && c <= lock + EPS) why = "lock"
            if (!hi.isNaN()) bestHi = maxOf(bestHi, hi)
            if (exit == Exit.STEP_LOCK && bestHi >= 2 * e - EPS) {                 // takes effect from the next minute's close
                val m = floor(bestHi / e + 1e-9)
                lock = maxOf(lock, (m - 1) * e)
            }
            if (why != null) {
                val x = nextOpen(a, r + 1)
                if (x != null) { sales += Sale(x.first, held * lot, paperSell(index, x.second), why); held = 0; break }
            }
            r++
        }
        // The confirmed peak up to the exit (for the reached-2x / 4x figures), also after an early sale.
        for (rr in r until exitRow) {
            val hi = a.h[rr]
            if (!hi.isNaN()) { val conf = confirmed(a, rr); if (!conf.isNaN()) confPeak = maxOf(confPeak, minOf(hi, conf)) }
        }
        if (held > 0) {
            val x = nextOpen(a, exitRow)
            val (rr, oo) = x ?: run {
                var p = Double.NaN; var row = -1
                for (i in minOf(exitRow - 1, N - 1) downTo 0) if (!a.c[i].isNaN()) { p = a.c[i]; row = i; break }
                row to (if (p.isNaN()) 0.0 else p)
            }
            sales += Sale(rr, held * lot, paperSell(index, oo), exit.code)
        }
        val units = lots * lot
        val charges = charge(true, e, units) + sales.sumOf { charge(false, it.price, it.units) }
        val gross = sales.sumOf { it.price * it.units } - e * units
        val net = gross - charges
        return Outcome(exit, sales, net, charges, if (units > 0) net / units else 0.0, confPeak / e)
    }

    // ---- the words (points only; no rupee amount leaves the encrypted log) ------------------------------------------

    /**
     * How every line of the log starts (15 Oct review): a shadow, never traded, and information only - never a trade signal.
     */
    const val HEAD = "OI-wall check (shadow, not traded; information only, not a trade signal)"
    /** The diagnostics line's start (points only). */
    const val DIAG = "oi_wall (shadow; information only, not a trade signal)"

    /** One index's day in the log: fired (with its ticket), or not, or why it could not be judged. */
    data class Result(val index: String, val day: LocalDate, val signal: Signal?, val ticket: Ticket?, val note: String? = null)

    private fun pts(x: Double) = (if (x >= 0) "+" else "-") + "%.2f".format(Locale.ENGLISH, abs(x))
    private fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun k(x: Double) = "%.0f".format(Locale.ENGLISH, x)
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    /** Why a day without a trigger could not be fully judged, from what was recorded ([Day.rows]); null when it could. */
    fun gaps(d: Day, upTo: Int = LAST): String? {
        val missing = ArrayList<String>()
        if (wall(d, "CE", ANCHOR) == null && wall(d, "PE", ANCHOR) == null) missing += "no option chain at 13:00"
        if (d.spot[ANCHOR].isNaN()) missing += "no spot at 13:00"
        val window = (FIRST - HOLD + 1..minOf(LAST, upTo)).count { d.spot[it].isNaN() }
        if (window > 0) missing += "$window minute${if (window == 1) "" else "s"} of spot missing in the window"
        if (upTo < LAST) missing += "the window was not over"
        return missing.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** "fired at 14:12, 52100 CE at 22.40 (wall 52000)" / "did not fire" (+ why it could not be judged). */
    fun summary(r: Result): String {
        val s = r.signal ?: return "did not fire" + (r.note?.let { " ($it)" } ?: "")
        val head = "fired at ${hm(s.time)}, ${k(s.strike)} ${s.right} at ${px(s.ref)} (wall ${k(s.wall)}${if (s.edge) ", the edge strike" else ""})"
        val t = r.ticket ?: return "$head; no ticket: ${r.note ?: "it would not have filled"}"
        val o = Exit.entries.mapNotNull { t.of(it) }.joinToString(", ") { "${it.exit.label} ${pts(it.points)}" }
        return "$head; points a unit after costs: $o" + (r.note?.let { " ($it)" } ?: "")
    }

    /** Jarvis's evening line on an expiry day (points only). */
    fun eveningLine(results: List<Result>): String {
        if (results.isEmpty()) return "$HEAD: no expiry today for the indices it watches."
        val parts = results.map { r ->
            val who = OrderFlow.label(r.index)
            val s = r.signal
            val t = r.ticket
            when {
                s == null -> "didn't fire on $who" + (r.note?.let { " ($it)" } ?: "")
                t == null -> "fired on $who at ${hm(s.time)} (${k(s.strike)} ${s.right} at ${px(s.ref)}); no ticket: ${r.note ?: "it would not have filled"}"
                else -> {
                    val main = t.of(Exit.TARGET_4X)!!
                    val rest = listOf(Exit.HOLD_1505, Exit.HOLD_1520, Exit.STEP_LOCK).mapNotNull { t.of(it) }
                        .joinToString(", ") { "${it.exit.label} ${pts(it.points)}" }
                    "fired on $who at ${hm(s.time)} (${k(s.strike)} ${s.right} at ${px(s.ref)}); would have made ${pts(main.points)} points a unit " +
                        "after costs by its rule (4x, else 15:05; $rest)"
                }
            }
        }
        return "$HEAD: " + parts.joinToString("; ") + ". A record only: R26 found no edge, so nothing trades on it."
    }

    /** The diagnostics line (points only): "oi_wall 2026-10-14: NIFTY fired 14:12 CE 25100 @ 22.40 4x +12.00 ...". */
    fun diagLine(results: List<Result>): String =
        if (results.isEmpty()) "$DIAG: no expiry watched today" else "$DIAG: " + results.joinToString("; ") { "${it.index} ${summary(it)}" }

    /** The tally over every logged day: triggers, and the rule's (4x) points total, for "how is the OI-wall check doing". */
    fun tally(results: List<Result>): String {
        val fired = results.filter { it.signal != null }
        val tickets = fired.mapNotNull { it.ticket }
        val total = tickets.sumOf { it.of(Exit.TARGET_4X)?.points ?: 0.0 }
        return "$HEAD: ${results.size} expiry-index day${if (results.size == 1) "" else "s"} logged, " +
            "${fired.size} trigger${if (fired.size == 1) "" else "s"}" +
            (if (tickets.isEmpty()) "" else ", ${pts(total)} points a unit after costs by its rule over ${tickets.size} ticket${if (tickets.size == 1) "" else "s"}") +
            ". R26 asked to look again after 30 triggers; nothing trades on it."
    }

    private val ASK = Regex(" oi walls? (check|shadow|blast|rule|framework|log) | wall blast | oi blast | short covering (check|rule|shadow) |" +
        " (did|has|have) (the |my )?oi walls? (check |rule |shadow )?(fire|fired|trigger|triggered) ")

    /**
     * "How is the OI-wall check doing?", "did the oi wall fire today". Where the walls stand now ("where is the oi wall") stays
     * the chain's own answer ([ChainDrift]).
     */
    fun asked(text: String): Boolean {
        val t = " " + text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        return ASK.containsMatchIn(t) && !Regex(" (turn|switch|explain|what is|meaning) ").containsMatchIn(t)
    }
}
