package com.optionslab.engine.orb

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "CISD + network" (29 Oct 2026, Boss: "Build CISD (ICT's version) + network + index stop + lock. And add it as a arm, and do forward
 * test too"): research R133's S3-V2-E3, ported exactly (frozen, nothing tuned). A PAPER arm that FAILED the research gates: Boss's
 * second exception (CLAUDE.md, 29 Oct). It never goes live, and it is labelled [SHOWN] ("failed gates, Boss's exception") wherever
 * it is shown.
 *
 *   signal   R131 S3-V2, CISD as ICT reads it, on each index's 5-minute bars (wicks for levels, opens and closes for green / red):
 *            a run of at least 2 red bars whose drop from the run's first open to its lowest low is at least 2 ATR (14 bars), then,
 *            within 12 bars and the same session, a bar that CLOSES above the run's first open ([detect]). Bearish setups are the
 *            same on the mirrored index. Entry at the open of the minute after that bar; the index stop 0.02% beyond the lowest
 *            low since the run began. R131's entry filters ([check]): not both sides at one minute, entry at or before 15:00, the
 *            index's entry price beyond the stop, and a structural target (an untouched 5-minute swing high, today or the previous
 *            4 sessions) beyond it.
 *   filter   R132 amendment A / R133: R123's network N1 ([S5Trail.Model], the same weights and scalers) at its latest 5-minute view
 *            at most 5 minutes old ([viewRow]); the trade is taken only when the mean EV of the side's 8 cells is above 0 ([keep]).
 *   trade    the ATM option (the last index close before the entry, half up), 1 lot, of the nearest expiry; on a day the nearest
 *            expiry is today, the monthly contract, and no trade when that expires today too ([expiryFor]: R131's series rule).
 *   exits    R132 E3: the index stop on the index's wicks; once the option's best price (earlier minutes' highs) is up ₹1,000 on
 *            the lot, out when it falls to the entry + 60% of that gain ([lockLevel]); else 15:20. One trade at a time per index,
 *            counting the trades the filter refused (R133 applies the one-at-a-time rule before the filter): [simExit].
 *   record   R133 design (2023 - 15 Mar 2026): 413 of 3,291 trades kept, 69% won, +Rs 261 a trade (+Rs 240 at 2x slippage), q 0.012;
 *            holdout period (Apr - Oct 2026, already seen): 35 trades, +Rs 77 each. Fails the >= 50 holdout trades gate.
 *
 * Signal source: the index (the default). The network's option-chain inputs are context only. Pure: no clock, no I/O, no orders.
 */
object CisdNet {
    const val SOURCE = "cisdnet"
    const val LABEL = "CISD + network"
    const val TAG = "failed gates, Boss's exception"
    /** The name everywhere it is shown (Boss's 29 Oct label). */
    const val SHOWN = "$LABEL ($TAG)"
    const val OWNER = "cisdnet"
    const val RESEARCH_ID = "R133 S3-V2-E3"
    const val NOT_PROVEN = "Failed research gates (R133): Boss's exception of 29 Oct - paper only, never live"
    const val SIGNAL = "Signal: the index's 5-minute bars (the default); the network's option-chain inputs are context only; the ATM " +
        "option is what is bought"
    const val RULES = "On NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX: a drop of 2+ red 5-minute bars worth 2 ATR, then a close " +
        "back above the drop's first open within 12 bars (CISD, ICT's reading; mirrored for puts); taken only when R123's network " +
        "expects that side to pay (mean EV of its 8 cells above ₹0); 1 lot of the ATM option; stop on the index 0.02% beyond the " +
        "drop's low; once up ₹1,000, out when it gives back 40% of the gain; else the square-off; paper only"
    const val RECORD = "R133 backtest: design 413 trades, 69% won, +₹261 a trade (+₹240 at double slippage); Apr–Oct 2026 only 35 " +
        "trades, +₹77 each, short of the 50 the gate needs; R134's weekly forward test keeps scoring it"
    /**
     * The input the phone does not have (29 Oct, measured in CisdNetParityTest): the network learned Dhan's implied volatility; the app
     * reads no Dhan data, so its two IV inputs (ATM IV, skew) are the app's own (S5 Trail's Black-76 + offset). On the recorded days
     * that turned 2 of the 4 research trades into refusals. So the arm is built switched OFF; Boss decides.
     */
    const val IV_GAP = "the phone has no Dhan IV, so the network's ATM IV and skew inputs are the app's own estimate; on the recorded " +
        "test days that changed 2 of the research's 4 trades"
    val ARM = Arm(SOURCE, SHOWN, paperOnly = true)

    val UNDERLYINGS = S5Trail.UNDERLYINGS
    const val N = PassedArms.Session.N
    const val TF = 5
    const val K_PIVOT = 3
    const val SESSIONS_BACK = 4
    const val ATR_N = 14
    const val N_CONF = 12
    const val STOP_PCT = 0.0002
    const val CISD_ATR = 2.0
    const val TICK = 0.05
    /** Entries at or before 15:00 (R131 LAST_ENTRY); everything out by 15:19's close (15:20, R131 EXIT_LAST). */
    val LAST_ENTRY = PassedArms.Session.row(15, 0)
    val EXIT_LAST = PassedArms.Session.row(15, 19)
    /** R123's views: the closes of rows 4, 9 .. 339 (09:20 .. 14:55); a view is used for at most 5 minutes (r132nn.view_row). */
    const val FIRST_VIEW = 4
    const val VIEW_STEP = 5
    val LAST_VIEW = PassedArms.Session.row(14, 54)
    const val MAX_AGE = 5
    const val LOCK_AFTER = 1000.0
    const val LOCK_KEEP = 0.60
    /** Earlier sessions the detector reads: the target's 4, one more so the oldest one's swings have bars on both sides. */
    const val SESSIONS_READ = SESSIONS_BACK + 2

    // ---- the 5-minute chart (r131.make_bars / Chart) -------------------------------------------------------------------------

    /** One side's 5-minute bars over [sessions] (index = the session's place in the list); bearish sides are the mirrored index. */
    class Chart(val day: IntArray, val start: IntArray, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray) {
        val size: Int get() = day.size
        val end: IntArray = IntArray(size) { start[it] + TF - 1 }
        /** The mean range of the 14 bars before each bar (NaN for the first 14). */
        val atr: DoubleArray = DoubleArray(size) { t ->
            if (t < ATR_N) Double.NaN else { var s = 0.0; for (k in t - ATR_N until t) s += h[k] - l[k]; s / ATR_N }
        }
        val green: BooleanArray = BooleanArray(size) { c[it] > o[it] }
        val red: BooleanArray = BooleanArray(size) { c[it] < o[it] }
        /** Swing highs: above the 3 highs before and at or above the 3 after (R130's pivot). */
        val swings: IntArray = swingHighs(h, K_PIVOT)
        fun gend(i: Int): Long = day[i].toLong() * N + end[i]
        fun same(a: Int, b: Int): Boolean = a in 0 until size && b in 0 until size && day[a] == day[b]
    }

    /** r131.swing_highs. */
    fun swingHighs(h: DoubleArray, k: Int): IntArray {
        if (h.size < 2 * k + 1) return IntArray(0)
        val out = ArrayList<Int>()
        for (i in k until h.size - k) {
            var left = Double.NEGATIVE_INFINITY; var right = Double.NEGATIVE_INFINITY
            for (j in i - k until i) left = max(left, h[j])
            for (j in i + 1..i + k) right = max(right, h[j])
            if (h[i] > left && h[i] >= right) out += i
        }
        return out.toIntArray()
    }

    /** A session's minute arrays on [side]'s coordinates: bullish as is; bearish high -> -low, low -> -high, open / close negated. */
    class SideDay(val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray)

    fun sideDay(d: PassedArms.DayBars, side: Int): SideDay = if (side > 0) SideDay(d.o, d.h, d.l, d.c)
        else SideDay(neg(d.o), neg(d.l), neg(d.h), neg(d.c))

    private fun neg(a: DoubleArray) = DoubleArray(a.size) { -a[it] }

    /** r131.make_bars: each session's 5-minute bars from 09:15; a bar with no minute in it is left out. */
    fun chart(days: List<SideDay>): Chart {
        val d = ArrayList<Int>(); val st = ArrayList<Int>(); val o = ArrayList<Double>(); val h = ArrayList<Double>()
        val l = ArrayList<Double>(); val c = ArrayList<Double>()
        for ((di, s) in days.withIndex()) for (b in 0 until N / TF) {
            var bo = Double.NaN; var bc = Double.NaN; var bh = Double.NaN; var bl = Double.NaN
            for (r in b * TF until b * TF + TF) {
                if (bo.isNaN() && s.o[r].isFinite()) bo = s.o[r]
                if (s.c[r].isFinite()) bc = s.c[r]
                if (s.h[r].isFinite()) bh = if (bh.isNaN()) s.h[r] else max(bh, s.h[r])
                if (s.l[r].isFinite()) bl = if (bl.isNaN()) s.l[r] else min(bl, s.l[r])
            }
            if (bo.isFinite() && bc.isFinite() && bh.isFinite() && bl.isFinite()) { d += di; st += b * TF; o += bo; h += bh; l += bl; c += bc }
        }
        return Chart(d.toIntArray(), st.toIntArray(), o.toDoubleArray(), h.toDoubleArray(), l.toDoubleArray(), c.toDoubleArray())
    }

    // ---- the setup (r131.det_cisd, level "open") --------------------------------------------------------------------------------

    /**
     * One CISD setup: [side] (+1 call, -1 put), session [d], entry minute [r0] (bought at its open), the stop in this side's
     * coordinates ([stopSide]) and on the index ([stop]), and the run's first open, the level the confirming bar closed beyond.
     */
    data class Cand(val side: Int, val d: Int, val r0: Int, val stopSide: Double, val levelSide: Double) {
        val stop: Double get() = side * stopSide
        val level: Double get() = side * levelSide
        val call: Boolean get() = side > 0
    }

    fun stopBelow(x: Double): Double = x - STOP_PCT * abs(x)

    /** r131.det_cisd on one side's chart. */
    fun detect(ch: Chart, side: Int): List<Cand> {
        val out = ArrayList<Cand>()
        var pa = -1; var pe = -1; var plv = Double.NaN
        for (t in 1 until ch.size) {
            if (!ch.same(t - 1, t)) pa = -1
            if (ch.same(t - 1, t) && ch.red[t - 1] && !ch.red[t]) {
                val e = t - 1
                var a = e
                while (ch.same(a - 1, e) && ch.red[a - 1]) a--
                var low = Double.POSITIVE_INFINITY
                for (k in a..e) low = min(low, ch.l[k])
                if (e - a + 1 >= 2 && ch.atr[a].isFinite() && ch.o[a] - low >= CISD_ATR * ch.atr[a]) { pa = a; pe = e; plv = ch.o[a] }
            }
            if (pa >= 0) {
                if (t > pe + N_CONF || !ch.same(pe, t)) pa = -1
                else if (ch.c[t] > plv) {
                    var low = Double.POSITIVE_INFINITY
                    for (k in pa..t) low = min(low, ch.l[k])
                    out += Cand(side, ch.day[t], ch.end[t] + 1, stopBelow(low), plv)
                    pa = -1
                }
            }
        }
        return out
    }

    /** One index's sessions (oldest first; the last one may be today so far), read once for both sides. */
    class Sides(val sessions: List<PassedArms.DayBars>) {
        private val days = mapOf(1 to sessions.map { sideDay(it, 1) }, -1 to sessions.map { sideDay(it, -1) })
        val charts: Map<Int, Chart> = days.mapValues { chart(it.value) }
        /** This side's minute highs flattened over the sessions (r131 Side.flat_h). */
        fun flatH(side: Int): DoubleArray {
            val out = DoubleArray(sessions.size * N)
            for ((i, s) in days.getValue(side).withIndex()) s.h.copyInto(out, i * N)
            return out
        }
    }

    /** Every setup on both sides, in time order (r131.candidates). */
    fun candidates(s: Sides): List<Cand> = (detect(s.charts.getValue(1), 1) + detect(s.charts.getValue(-1), -1))
        .sortedWith(compareBy({ it.d }, { it.r0 }, { it.side }))

    /**
     * r131.struct_target: the nearest swing high above [refSide] on [side]'s chart, confirmed before the entry minute, not traded
     * above (one tick) since it formed; today and the previous 4 sessions. Null: none.
     */
    fun structTarget(s: Sides, side: Int, d: Int, r0: Int, refSide: Double): Double? {
        val ch = s.charts.getValue(side)
        val g0 = d.toLong() * N + r0
        val flat = s.flatH(side)
        val sel = ch.swings.filter { p ->
            p + K_PIVOT < ch.size && ch.day[p] >= d - SESSIONS_BACK && ch.gend(minOf(p + K_PIVOT, ch.size - 1)) < g0 && ch.h[p] > refSide
        }.sortedBy { ch.h[it] }
        for (p in sel) {
            val lv = ch.h[p]
            var mx = Double.NEGATIVE_INFINITY
            for (q in (ch.gend(p) + 1).toInt() until g0.toInt()) if (flat[q].isFinite()) mx = max(mx, flat[q])
            if (mx < lv + TICK) return lv
        }
        return null
    }

    /** Why a setup is not an entry (R131 / R132's filters, in their order), or null when it is one. [ref]: the index at entry. */
    fun check(s: Sides, cand: Cand, all: List<Cand>, ref: Double): String? {
        if (all.any { it.d == cand.d && it.r0 == cand.r0 && it.side != cand.side }) return "both sides set up at one minute"
        if (cand.r0 > LAST_ENTRY) return "after 15:00"
        if (!ref.isFinite()) return "no index price at the entry"
        val refSide = cand.side * ref
        if (refSide <= cand.stopSide) return "the index is already past the stop"
        val tgt = structTarget(s, cand.side, cand.d, cand.r0, refSide)
        if (tgt == null || tgt <= refSide) return "no swing target beyond the entry"
        return null
    }

    // ---- the network's filter (r132nn) ---------------------------------------------------------------------------------------

    /** The latest view row t <= r0 - 1 (acting from t + 1), if at most [MAX_AGE] minutes old; null: none (r132nn.view_row). */
    fun viewRow(r0: Int): Int? {
        if (r0 - 1 < FIRST_VIEW) return null
        val t = min(FIRST_VIEW + VIEW_STEP * ((r0 - 1 - FIRST_VIEW) / VIEW_STEP), LAST_VIEW)
        return if (r0 - (t + 1) <= MAX_AGE) t else null
    }

    /** The mean EV of a side's 8 cells (column j = 2 x cell + side; 0 call, 1 put). */
    fun evSide(ev: DoubleArray, call: Boolean): Double {
        require(ev.size == S5Trail.NJ) { "expected ${S5Trail.NJ} EVs" }
        var s = 0.0
        for (c in 0 until 8) s += ev[2 * c + if (call) 0 else 1]
        return s / 8
    }

    /** N-EV: taken only when the side's mean EV is above 0 (a missing EV never passes). */
    fun keep(ev: DoubleArray, call: Boolean): Boolean = evSide(ev, call).let { it.isFinite() && it > 0 }

    // ---- the contract (R131's series) ----------------------------------------------------------------------------------------

    /**
     * The expiry bought on [today] from the listed [expiries]: the nearest; on its expiry day the monthly (the last listed expiry
     * of its month) instead; null when that expires today too (R131: both series expire, no trade) or nothing is listed.
     */
    fun expiryFor(today: LocalDate, expiries: Collection<LocalDate>): LocalDate? {
        val ahead = expiries.filter { !it.isBefore(today) }.toSortedSet()
        val nearest = ahead.firstOrNull() ?: return null
        if (nearest != today) return nearest
        val monthly = ahead.firstOrNull { e -> ahead.none { it.year == e.year && it.month == e.month && it > e } } ?: return null
        return if (monthly == today) null else monthly
    }

    /** The ATM strike from the last index close before the entry (half up to the step). */
    fun strike(underlying: String, lastClose: Double): Int = S5Trail.atm(lastClose, S5Trail.step(underlying))

    // ---- the exits (R132 E3) -------------------------------------------------------------------------------------------------

    /** The lock's sell level from [peak] (the best option price of earlier minutes): entry + 60% of the gain, once >= ₹1,000. */
    fun lockLevel(buy: Double, qty: Int, peak: Double): Double? {
        if (!peak.isFinite() || !buy.isFinite() || qty <= 0) return null
        val gain = (peak - buy) * qty
        return if (gain >= LOCK_AFTER) buy + LOCK_KEEP * gain / qty else null
    }

    /** The index stop is hit by a price range [low]..[high] (a call's stop is below, a put's above). */
    fun indexStopHit(call: Boolean, stop: Double, low: Double, high: Double): Boolean =
        if (call) low.isFinite() && low <= stop else high.isFinite() && high >= stop

    /** One exit as the research simulates it: the minute it is decided, why ("lock", "istop", "time") and the option price sold at. */
    data class Exit(val row: Int, val why: String, val price: Double)

    /**
     * r132.sim_exit with E3, without the money: the option [opt] (rows x open, high, low, close; NaN = no trade) bought at [buy] in
     * minute [r0]; [idxHigh] / [idxLow] the index's minutes (real coordinates); [stop] the index stop. Within a minute the lock uses
     * earlier minutes' highs only; if both exits come in one minute the worse (lower) price counts. Null: no option price at entry.
     */
    fun simExit(opt: Array<DoubleArray>, idxHigh: DoubleArray, idxLow: DoubleArray, call: Boolean, r0: Int, buy: Double, qty: Int,
                stop: Double, last: Int = EXIT_LAST): Exit? {
        val op = opt[r0][0]
        if (!(op.isFinite() && op > 0)) return null
        val lastClose = IntArray(opt.size)
        var lc = -1
        for (r in opt.indices) { if (opt[r][3].isFinite()) lc = r; lastClose[r] = lc }
        var peak = Double.NEGATIVE_INFINITY
        var tp = -1; var ti = -1; var lockAt = Double.NaN
        for (m in r0..last) {
            val lv = lockLevel(buy, qty, peak)
            if (tp < 0 && lv != null && opt[m][2].isFinite() && opt[m][2] <= lv) { tp = m; lockAt = lv }
            val il = if (call) idxLow[m] else -idxHigh[m]
            if (ti < 0 && il.isFinite() && il <= (if (call) stop else -stop)) ti = m
            if (tp >= 0 || ti >= 0) break
            if (opt[m][1].isFinite()) peak = max(peak, opt[m][1])
        }
        val t0 = listOf(tp, ti).filter { it >= 0 }.minOrNull()
        if (t0 != null) {
            val outs = ArrayList<Exit>()
            if (tp == t0) outs += Exit(t0, "lock", if (opt[t0][0].isFinite()) min(lockAt, opt[t0][0]) else lockAt)
            if (ti == t0) { val rr = lastClose[t0]; if (rr >= r0) outs += Exit(t0, "istop", opt[rr][3]) }
            if (outs.isNotEmpty()) return outs.minBy { it.price }
        }
        val rr = lastClose[last]
        if (rr < r0) return null
        return Exit(rr, "time", opt[rr][3])
    }
}
