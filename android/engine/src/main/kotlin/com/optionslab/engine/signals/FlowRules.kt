package com.optionslab.engine.signals

import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * IraThinker's flow (28 Oct 2026, Boss: "Also check flow page on Irathinker and implement that too"; "Use live data from zerodha
 * instead"; "Dont add alert, just take orders from flow, and keep it for index only"): the rules of IraThinker's Flow page,
 * ported from irathinker-app's Analysis.java and BuyAlerts.java as they stand, pure (no clock, no network, no orders). The app's
 * FlowArm carries them out on Zerodha's own stream candles and places PAPER orders.
 *
 * NOT PROVEN ([NOT_PROVEN]): research R110 found no IraThinker strategy that passed - the ORB alert this flow ends in lost on every
 * index (NIFTY -₹869 a trade over 35 trades, BANKNIFTY -₹36 over 38). Boss asked for it on paper; every screen and Jarvis say so.
 *
 * SIGNAL SOURCE ([SIGNAL]): the index itself (CLAUDE.md: signal on the underlying, trade the option). The flow's four stages:
 *  1. Scanner ([scan]): the 15-minute opening range (the 5-minute bars starting before 09:30), then the first 5-minute CLOSE
 *     beyond it - above: ORB_UP, below: ORB_DOWN. The plan: stop at the range's midpoint, target 1R. A day with no break gets
 *     OVERSOLD_BOUNCE (daily RSI(14) under 35 and the day up over 0.5%) or the 20-day average side - those carry no plan.
 *  2. IraScore ([liteScore], [fullScore]): the 0-100 score; with the Time Machine's odds, half the lite score and half the odds.
 *     The alert gate ([alertWorthy], [vixConfirms]): score above [MIN_SCORE], at least [MIN_TARGET_POINTS] to the 1R target, an
 *     opening range at most [MAX_RANGE_WIDTH_PCT] of the price, and India VIX's own opening range broken UP no later than the
 *     index's break - so only breakdowns (a PUT) ever pass (IraThinker's 23 Oct study: breakout buys never cleared the bar).
 *  3. Time Machine ([timeMachine]): [PATHS] Monte Carlo replays of the rest of the session from the index's own 5-minute
 *     returns, plus every past session's same-direction break walked to the close. Passes when target-first is at least
 *     stop-first (IraThinker's selection path) and the replays could run (at least [MIN_RETURNS] returns).
 *  4. Risk Gate ([budget]): reward to risk at least 0.9, a quantity that fits the max-loss budget, and 09:30-15:00 IST.
 * A plan that passes all four (and is fresh: its break bar closed, at most [MAX_SIGNAL_AGE_SEC] after it) is the flow's
 * "fired" signal; the arm takes it as a paper entry instead of an alert. The trade's index exits are IraThinker's alert's own:
 * the stop at the range midpoint, the target 2R ([alertTarget]); flat by the app's 15:15 square-off.
 *
 * Indices only ([INDICES]): IraThinker's alertable indices - NIFTY, BANKNIFTY, FINNIFTY (its own rule never alerted SENSEX,
 * MIDCAP or VIX). No stock, MCX, commodity or crypto. Not ported: IraThinker's gap fade (a separate alert, not the flow's plan),
 * its news, regime and prediction-engine lines (context only, never a gate), and its Live Ledger (IraAlgo has none of its rows).
 */
object FlowRules {
    const val SOURCE = "irathinker_flow"
    const val LABEL = "IraThinker's flow"
    /** What every screen, the ticket and Jarvis say of it (Boss's label). */
    const val NOT_PROVEN = "IraThinker's flow — not proven (R110: no IraThinker strategy passed)"
    /** The owner tag of its paper orders (the ticket). */
    const val OWNER = "IraThinker's flow (paper)"
    /** CLAUDE.md: the signal source of the rule. */
    const val SIGNAL = "the index's own 5-minute opening-range break (09:15-09:30) on Zerodha's stream, confirmed by India VIX's " +
        "own break up; buys the index option by the strike rule (ATM, nearest expiry)"

    /** IraThinker's alertable indices (Universe.alertable): the only ones the flow trades. */
    val INDICES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY")
    const val VIX = "INDIAVIX"

    /** IraThinker's ALERT_MIN_SCORE (BuyAlerts.MIN_CONFIDENCE): a plan's score must be ABOVE it. */
    const val MIN_SCORE = 65
    /** ALERT_MIN_TARGET_POINTS: at least this many index points to the 1R target. */
    const val MIN_TARGET_POINTS = 10.0
    /** ALERT_MAX_RANGE_WIDTH_PCT: the opening range at most this % of the entry. */
    const val MAX_RANGE_WIDTH_PCT = 0.8
    /** MAX_ALERT_AGE_SEC: a break found later than this after its bar closed is skipped, never taken as fresh. */
    const val MAX_SIGNAL_AGE_SEC = 600L
    /** BuyAlerts.DAILY_TOP_N: at most this many flow signals a day, all indices together. */
    const val DAILY_TOP_N = 3
    /** The Time Machine's replays (IraThinker's board computes 3,000). */
    const val PATHS = 3000
    /** The Monte Carlo needs at least this many 5-minute returns. */
    const val MIN_RETURNS = 100
    /** IraThinker's default budget: max loss 1% of the capital. */
    const val MAX_LOSS_PCT = 1.0
    /** IraThinker's Time Machine reads 60 sessions of 5-minute bars. */
    const val SESSIONS_KEPT = 60
    /** The app's own premium stop (as ntfy alerts' "our stop"): 20% under the fill, resting in the paper book. */
    const val PREMIUM_STOP = NtfyRules.OUR_STOP

    val OR_END: LocalTime = LocalTime.of(9, 30)
    val OPEN: LocalTime = LocalTime.of(9, 15)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)

    /** Whether [u] is an index the flow may trade (never a stock, MCX, a commodity or crypto). */
    fun tradable(u: String): Boolean = u.uppercase(Locale.ROOT) in INDICES

    enum class Setup { NONE, ORB_UP, ORB_DOWN, OVERSOLD_BOUNCE, ABOVE_20DMA, BELOW_20DMA }

    /** The flow's four stages, in order. */
    enum class Stage(val words: String) { SCANNER("Scanner"), IRASCORE("IraScore"), TIME_MACHINE("Time Machine"), RISK_GATE("Risk Gate") }

    fun istDate(epochSec: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(epochSec + 19800L, 86400L))
    fun istTime(epochSec: Long): LocalTime = Instant.ofEpochSecond(epochSec).atZone(IST).toLocalTime()

    /** One index's scan of a day (IraThinker's Analysis.Scan). NaN: not known. */
    class Scan(val index: String) {
        var ltp = Double.NaN
        var dayPct = Double.NaN
        var prevClose = Double.NaN
        var orHigh = Double.NaN
        var orLow = Double.NaN
        var setup = Setup.NONE
        var orReady = false
        var breakTs = 0L
        var lastTs = 0L
        var entry = Double.NaN
        var stop = Double.NaN
        var target = Double.NaN
        var rsi = Double.NaN
        var dma20 = Double.NaN
        var paBody = Double.NaN
        var paEngulf = false
        var paThrust = Double.NaN
        var paVwap = 0
        var paStruct = 0
        var runMove = Double.NaN
        var error: String? = null
        val why = ArrayList<String>()
        fun hasPlan(): Boolean = !entry.isNaN() && !stop.isNaN()
        val up: Boolean get() = setup == Setup.ORB_UP
    }

    private fun dayBars(bars: List<Upstox.Bar>, day: LocalDate): List<Upstox.Bar> =
        bars.filter { istDate(it.epochSecond) == day }.sortedBy { it.epochSecond }

    /**
     * Scan [index] from its 5-minute [bars] (today's, and any past sessions'), on [today]. [prevClose]: yesterday's close from
     * Zerodha's stream (its tick's close), else the last [daily] close before the scanned day. [daily]: past sessions' closes
     * (oldest first), for the 20-day average and RSI(14); today's last price is added as today's close, as IraThinker's daily
     * chart carries today's bar.
     */
    fun scan(index: String, bars: List<Upstox.Bar>, today: LocalDate, prevClose: Double? = null,
             daily: List<Pair<LocalDate, Double>> = emptyList()): Scan {
        val s = Scan(index)
        var t = dayBars(bars, today)
        if (t.isEmpty() && bars.isNotEmpty()) t = dayBars(bars, istDate(bars.maxOf { it.epochSecond }))
        if (t.isEmpty()) { s.error = "no bars"; return s }
        s.lastTs = t.last().epochSecond
        t.lastOrNull { !it.close.isNaN() }?.let { s.ltp = it.close }
        val scanDay = istDate(s.lastTs)
        val pc = prevClose?.takeIf { it > 0 && !it.isNaN() }
            ?: daily.lastOrNull { it.first.isBefore(scanDay) && it.second > 0 && !it.second.isNaN() }?.second ?: Double.NaN
        s.prevClose = pc
        if (!pc.isNaN() && pc > 0 && !s.ltp.isNaN()) s.dayPct = (s.ltp / pc - 1) * 100

        var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY; var orBars = 0
        for (b in t) if (istTime(b.epochSecond).isBefore(OR_END)) {
            if (!b.high.isNaN()) { hi = maxOf(hi, b.high); orBars++ }
            if (!b.low.isNaN()) lo = minOf(lo, b.low)
        }
        if (orBars >= 2 && hi > lo) { s.orHigh = hi; s.orLow = lo; s.orReady = true }

        if (s.orReady) {
            var pos = 0
            for (b in t) {
                if (!istTime(b.epochSecond).isBefore(OR_END)) {
                    val c = b.close
                    if (c.isNaN()) { pos++; continue }
                    if (c > s.orHigh) { s.setup = Setup.ORB_UP; s.entry = c; s.breakTs = b.epochSecond; break }
                    if (c < s.orLow) { s.setup = Setup.ORB_DOWN; s.entry = c; s.breakTs = b.epochSecond; break }
                }
                pos++
            }
            if (!s.entry.isNaN()) {
                val mid = (s.orHigh + s.orLow) / 2
                val risk = abs(s.entry - mid)
                s.stop = mid
                s.target = if (s.setup == Setup.ORB_UP) s.entry + risk else s.entry - risk
            }
            if (s.hasPlan()) {
                computePa(s, t, pos, s.up)
                s.runMove = runMoveOf(t, pos, s.up)
            }
        }

        val closes = daily.filter { it.first.isBefore(scanDay) }.map { it.second } + listOf(s.ltp)
        if (closes.size > 20) {
            val cl = closes.filter { !it.isNaN() && it > 0 }.takeLast(40)
            if (cl.size > 20) {
                s.dma20 = cl.takeLast(20).sum() / 20
                s.rsi = rsi14(cl)
                if (s.setup == Setup.NONE) {
                    if (!s.rsi.isNaN() && s.rsi < 35 && !s.dayPct.isNaN() && s.dayPct > 0.5) s.setup = Setup.OVERSOLD_BOUNCE
                    else if (!s.ltp.isNaN()) s.setup = if (s.ltp > s.dma20) Setup.ABOVE_20DMA else Setup.BELOW_20DMA
                }
            }
        }
        return s
    }

    /** RSI(14) of the last 15 closes (IraThinker's simple average form). */
    fun rsi14(closes: List<Double>): Double {
        if (closes.size < 15) return Double.NaN
        var gain = 0.0; var loss = 0.0
        for (i in closes.size - 14 until closes.size) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        if (loss == 0.0) return 100.0
        val rs = (gain / 14) / (loss / 14)
        return 100 - 100 / (1 + rs)
    }

    /** The 30-minute run-up into the entry bar: the net % move over the six bars before it, signed in the trade's direction. */
    fun runMoveOf(t: List<Upstox.Bar>, pos: Int, up: Boolean): Double {
        if (pos <= 0 || pos >= t.size) return Double.NaN
        val cEntry = t[pos].close
        val cFrom = t[maxOf(0, pos - 6)].close
        if (cEntry.isNaN() || cFrom.isNaN() || cFrom <= 0) return Double.NaN
        return (cEntry / cFrom - 1) * 100 * (if (up) 1 else -1)
    }

    /** The entry bar's price-action fingerprint: body, engulfing, 3-bar thrust, VWAP side, the day's swing structure. */
    fun computePa(s: Scan, t: List<Upstox.Bar>, pos: Int, up: Boolean) {
        if (pos < 0 || pos >= t.size) return
        val b = t[pos]
        val o = b.open; val h = b.high; val l = b.low; val c = b.close
        if (o.isNaN() || h.isNaN() || l.isNaN() || c.isNaN() || h <= l) return
        s.paBody = (if (up) c - o else o - c) / (h - l)
        if (pos > 0) {
            val j = t[pos - 1]
            if (!j.open.isNaN() && !j.close.isNaN()) {
                s.paEngulf = if (up) c > j.open && o < j.close && c > o else c < j.open && o > j.close && c < o
            }
            val c0 = t[maxOf(0, pos - 3)].close
            if (!c0.isNaN() && c0 > 0) s.paThrust = (c / c0 - 1) * 100 * (if (up) 1 else -1)
        }
        var pv = 0.0; var vv = 0.0
        for (p in 0..pos) {
            val x = t[p]
            val tp = (x.high + x.low + x.close) / 3
            if (tp.isNaN() || x.volume <= 0) continue
            pv += tp * x.volume; vv += x.volume.toDouble()
        }
        if (vv > 0) { val vwap = pv / vv; s.paVwap = if (c > vwap) 1 else if (c < vwap) -1 else 0 }
        s.paStruct = dayStruct(t, pos)
    }

    /** +1 when the last two 3-bar pivot highs AND lows both step up, -1 both down, 0 otherwise. */
    fun dayStruct(t: List<Upstox.Bar>, pos: Int): Int {
        val ph = ArrayList<Double>(); val pl = ArrayList<Double>()
        for (p in 1 until pos) {
            val h0 = t[p - 1].high; val h1 = t[p].high; val h2 = t[p + 1].high
            val l0 = t[p - 1].low; val l1 = t[p].low; val l2 = t[p + 1].low
            if (!h1.isNaN() && !h0.isNaN() && !h2.isNaN() && h1 >= h0 && h1 >= h2) ph += h1
            if (!l1.isNaN() && !l0.isNaN() && !l2.isNaN() && l1 <= l0 && l1 <= l2) pl += l1
        }
        if (ph.size < 2 || pl.size < 2) return 0
        val hA = ph[ph.size - 2]; val hB = ph.last(); val lA = pl[pl.size - 2]; val lB = pl.last()
        if (hB > hA && lB > lA) return 1
        if (hB < hA && lB < lA) return -1
        return 0
    }

    // ---- IraScore ------------------------------------------------------------------------------------------------------

    /** IraThinker's fast 0-100 score (fills [Scan.why]). */
    fun liteScore(s: Scan): Int {
        s.why.clear()
        if (s.setup == Setup.OVERSOLD_BOUNCE) {
            s.why += "Oversold bounce, RSI " + "%.0f".format(Locale.ENGLISH, s.rsi)
            return maxOf(35.0, minOf(68.0, 75 - s.rsi)).toInt()
        }
        if (!s.hasPlan()) {
            return when (s.setup) {
                Setup.ABOVE_20DMA -> { s.why += "Uptrend, above 20DMA"; 45 }
                Setup.BELOW_20DMA -> { s.why += "Downtrend, below 20DMA"; 35 }
                else -> { s.why += "No setup"; 25 }
            }
        }
        val up = s.up
        var score = 25
        val width = s.orHigh - s.orLow
        val pen = if (up) s.entry - s.orHigh else s.orLow - s.entry
        val ratio = if (width > 0) pen / width else 0.0
        val strength = maxOf(0.0, minOf(20.0, 5 + ratio * 20)).toInt()
        score += strength
        if (strength >= 14) s.why += "Strong break, " + "%.1f".format(Locale.ENGLISH, ratio) + "x the range beyond it"
        if (!s.dma20.isNaN() && !s.ltp.isNaN()) {
            val aligned = if (up) s.ltp > s.dma20 else s.ltp < s.dma20
            score += if (aligned) 18 else 4
            s.why += if (aligned) "Trend agrees (20DMA)" else "Against the 20DMA trend"
        } else score += 10
        if (!s.rsi.isNaN()) {
            val r = if (up) s.rsi else 100 - s.rsi
            when {
                r in 50.0..75.0 -> { score += 15; s.why += "RSI in the power zone" }
                r > 85 -> { score += 3; s.why += "RSI stretched - chasing risk" }
                r > 75 -> score += 8
                else -> score += 9
            }
        } else score += 8
        if (!s.ltp.isNaN() && s.ltp > 0 && width > 0) {
            val wPct = width / s.ltp * 100
            score += if (wPct in 0.2..1.0) 12 else if (wPct < 0.2) 5 else 7
        }
        val bt = istTime(s.breakTs)
        if (bt.isBefore(LocalTime.of(10, 30))) { score += 8; s.why += "Early break - full session ahead" }
        else if (bt.isBefore(LocalTime.of(13, 0))) score += 5
        else score += 1
        return maxOf(0, minOf(100, score))
    }

    /** The full score once the Time Machine's odds exist: half the lite score, half the odds' quality. */
    fun fullScore(s: Scan, o: Odds?): Int {
        val lite = liteScore(s)
        if (o == null) return lite
        var tm = o.targetFirst * 0.7
        val decided = o.analogTarget + o.analogStop
        tm += if (o.analogFires >= 3) (if (decided > 0) o.analogTarget * 30.0 / decided else 12.0)
            else if (o.analogFires > 0) (if (decided > 0) o.analogTarget * 15.0 / decided + 5 else 10.0)
            else 12.0
        return maxOf(0, minOf(100, Math.round(0.5 * lite + 0.5 * tm).toInt()))
    }

    // ---- Time Machine --------------------------------------------------------------------------------------------------

    /** The tuple Time Machine 2.0 matches setups on. */
    class Feat {
        var width = Double.NaN; var body = Double.NaN; var thrust = Double.NaN; var run = Double.NaN; var hour = Double.NaN
        var score = 0; var struct = 0; var vwap = 0; var engulf = 0
    }

    fun featOf(s: Scan, score: Int): Feat {
        val f = Feat()
        if (s.entry > 0) f.width = (s.orHigh - s.orLow) / s.entry * 100
        f.score = score
        f.body = s.paBody; f.thrust = s.paThrust; f.run = s.runMove
        if (s.breakTs > 0) istTime(s.breakTs).let { f.hour = it.hour + it.minute / 60.0 }
        f.struct = s.paStruct; f.vwap = s.paVwap; f.engulf = if (s.paEngulf) 1 else 0
        return f
    }

    /** The scaled distance between two tuples; infinity when fewer than five features are shared. */
    fun featDistance(a: Feat, b: Feat): Double {
        val terms = ArrayList<Double>()
        var n = 0.0
        fun term(x: Double, y: Double, scale: Double) { if (!x.isNaN() && !y.isNaN()) { terms += abs(x - y) / scale; n++ } }
        term(a.width, b.width, 1.0)
        term(a.score.toDouble(), b.score.toDouble(), 25.0)
        term(a.body, b.body, 0.4)
        term(a.thrust, b.thrust, 1.0)
        term(a.run, b.run, 1.5)
        term(a.hour, b.hour, 2.0)
        terms += if (a.struct == b.struct) 0.0 else if (a.struct == 0 || b.struct == 0) 0.7 else 1.4; n++
        terms += if (a.vwap == b.vwap) 0.0 else if (a.vwap == 0 || b.vwap == 0) 0.5 else 1.0; n++
        terms += if (a.engulf == b.engulf) 0.0 else 0.5; n++
        if (n < 5) return Double.POSITIVE_INFINITY
        return sqrt(terms.sumOf { it * it })
    }

    fun kernelW(d: Double): Double = exp(-(d * d) / 2.0)

    /** The Time Machine's odds (percentages). [ran]: the Monte Carlo had enough returns to run. */
    class Odds {
        var paths = 0
        var targetFirst = 0.0; var stopFirst = 0.0; var neither = 0.0
        var analogFires = 0; var analogTarget = 0; var analogStop = 0; var analogNeither = 0
        var simTarget = 0.0; var simStop = 0.0; var simNeither = 0.0; var simEffN = 0.0
        var simRaw = 0
        var simOk = false
        var ran = false
        var returns = 0
        /** IraThinker's selection path: the stage passes when the replays ran and target-first is at least stop-first. */
        val passes: Boolean get() = ran && targetFirst >= stopFirst
    }

    /**
     * Replay the rest of the session from [s]'s break bar: [paths] Monte Carlo paths over the 5-minute log returns of every
     * session in [bars], plus each past session in [bars] where the same-direction break fired, walked to the close.
     */
    fun timeMachine(bars: List<Upstox.Bar>, s: Scan, paths: Int = PATHS): Odds {
        val o = Odds()
        o.paths = paths
        if (!s.hasPlan()) return o
        val up = s.up
        if (abs(s.entry - s.stop) <= 0) return o
        val liveFeat = featOf(s, liteScore(s))
        var wT = 0.0; var wS = 0.0; var wN = 0.0; var wSum = 0.0; var wSq = 0.0; var rawNear = 0
        val today = istDate(s.breakTs)
        val sorted = bars.sortedBy { it.epochSecond }
        val byDay = sorted.groupBy { istDate(it.epochSecond) }.toSortedMap()
        for ((d, dayList) in byDay) {
            if (d == today) continue
            val past = scanDayOnly(s.index, dayList) ?: continue
            if (past.up != up) continue
            o.analogFires++
            val res = walkToClose(dayList, past)
            if (res > 0) o.analogTarget++ else if (res < 0) o.analogStop++ else o.analogNeither++
            past.ltp = past.entry
            val dist = featDistance(liveFeat, featOf(past, liteScore(past)))
            val w = kernelW(dist)
            if (w > 0) {
                wSum += w; wSq += w * w
                if (res > 0) wT += w else if (res < 0) wS += w else wN += w
                if (dist <= 2.0) rawNear++
            }
        }
        if (wSum > 0) {
            o.simTarget = wT / wSum * 100; o.simStop = wS / wSum * 100; o.simNeither = wN / wSum * 100
            o.simEffN = wSum * wSum / wSq; o.simRaw = rawNear; o.simOk = o.simEffN >= 8 && rawNear >= 5
        }
        val rets = ArrayList<Double>()
        for ((_, dayList) in byDay) {
            var prev = Double.NaN
            for (b in dayList) {
                val c = b.close
                if (!c.isNaN() && !prev.isNaN() && prev > 0) rets += ln(c / prev)
                if (!c.isNaN()) prev = c
            }
        }
        o.returns = rets.size
        if (rets.size < MIN_RETURNS) return o
        val trig = istTime(s.breakTs)
        val barsLeft = maxOf(1, (CLOSE.toSecondOfDay() - trig.toSecondOfDay()) / 300)
        val rnd = java.util.Random(19)
        var tf = 0; var sf = 0; var nn = 0
        for (p in 0 until paths) {
            var price = s.entry
            var outcome = 0
            for (b in 0 until barsLeft) {
                price *= exp(rets[rnd.nextInt(rets.size)])
                if (if (up) price >= s.target else price <= s.target) { outcome = 1; break }
                if (if (up) price <= s.stop else price >= s.stop) { outcome = -1; break }
            }
            if (outcome > 0) tf++ else if (outcome < 0) sf++ else nn++
        }
        o.targetFirst = tf * 100.0 / paths; o.stopFirst = sf * 100.0 / paths; o.neither = nn * 100.0 / paths
        o.ran = true
        return o
    }

    /** The ORB plan of one past session from its bars alone (at least 10 bars), or null. */
    fun scanDayOnly(index: String, t0: List<Upstox.Bar>): Scan? {
        val t = t0.sortedBy { it.epochSecond }
        if (t.size < 10) return null
        val s = Scan(index)
        var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY; var orBars = 0
        for (b in t) if (istTime(b.epochSecond).isBefore(OR_END)) {
            if (!b.high.isNaN()) { hi = maxOf(hi, b.high); orBars++ }
            if (!b.low.isNaN()) lo = minOf(lo, b.low)
        }
        if (orBars < 2 || hi <= lo) return null
        s.orHigh = hi; s.orLow = lo; s.orReady = true
        var pos = -1
        for ((p, b) in t.withIndex()) {
            if (istTime(b.epochSecond).isBefore(OR_END) || b.close.isNaN()) continue
            if (b.close > hi) { s.setup = Setup.ORB_UP; s.entry = b.close; s.breakTs = b.epochSecond; pos = p; break }
            if (b.close < lo) { s.setup = Setup.ORB_DOWN; s.entry = b.close; s.breakTs = b.epochSecond; pos = p; break }
        }
        if (s.entry.isNaN()) return null
        val mid = (hi + lo) / 2
        val risk = abs(s.entry - mid)
        s.stop = mid
        s.target = if (s.up) s.entry + risk else s.entry - risk
        computePa(s, t, pos, s.up)
        s.runMove = runMoveOf(t, pos, s.up)
        return s
    }

    /** Walk a past plan bar by bar to the close: +1 its target first, -1 its stop first, 0 neither. */
    fun walkToClose(t: List<Upstox.Bar>, past: Scan): Int {
        val up = past.up
        for (b in t.sortedBy { it.epochSecond }) {
            if (b.epochSecond < past.breakTs) continue
            if (b.high.isNaN() || b.low.isNaN()) continue
            if (up) { if (b.low <= past.stop) return -1; if (b.high >= past.target) return 1 }
            else { if (b.high >= past.stop) return -1; if (b.low <= past.target) return 1 }
        }
        return 0
    }

    // ---- Risk Gate -----------------------------------------------------------------------------------------------------

    class Plan {
        var qty = 0
        var riskPerUnit = 0.0; var maxLoss = 0.0; var positionValue = 0.0; var rr = 0.0
        val checks = ArrayList<String>()
        var pass = false
    }

    /** IraThinker's budget and risk gate on [capital] and [maxLossPct], at IST time [now]. */
    fun budget(s: Scan, capital: Double, maxLossPct: Double, now: LocalTime): Plan {
        val p = Plan()
        p.maxLoss = capital * maxLossPct / 100.0
        if (!s.hasPlan()) return p
        p.riskPerUnit = abs(s.entry - s.stop)
        if (p.riskPerUnit <= 0) return p
        p.qty = if (p.maxLoss.isNaN()) 0 else (p.maxLoss / p.riskPerUnit).toInt()
        p.positionValue = p.qty * s.entry
        p.rr = abs(s.target - s.entry) / p.riskPerUnit
        p.checks += "Reward:risk %.2f : 1 %s".format(Locale.ENGLISH, p.rr, if (p.rr >= 0.9) "(ok)" else "(below 1R - FAIL)")
        p.checks += "Max loss at stop Rs %.0f of Rs %.0f budget %s".format(Locale.ENGLISH, p.qty * p.riskPerUnit, p.maxLoss,
            if (p.qty > 0) "(ok)" else "(qty 0 - FAIL)")
        val window = now.isAfter(OR_END) && now.isBefore(LocalTime.of(15, 0))
        p.checks += "Entry window 09:30-15:00 IST " + if (window) "(ok)" else "(outside - FAIL)"
        p.pass = p.qty > 0 && p.rr >= 0.9 && window
        return p
    }

    // ---- the alert gate ------------------------------------------------------------------------------------------------

    fun rangeNarrow(s: Scan): Boolean {
        if (s.entry <= 0 || !(s.orHigh > s.orLow)) return false
        return (s.orHigh - s.orLow) / s.entry * 100.0 <= MAX_RANGE_WIDTH_PCT
    }

    /** The exit target the alert trades: 2R from the entry (the plan's own target stays 1R). */
    fun alertTarget(s: Scan): Double {
        val risk = abs(s.stop - s.entry)
        return if (s.up) s.entry + 2 * risk else s.entry - 2 * risk
    }

    /** Part 1: an ORB plan scoring above [minScore], with [MIN_TARGET_POINTS] to its 1R target and a narrow range. */
    fun alertWorthy(s: Scan, score: Int, minScore: Int = MIN_SCORE): Boolean {
        if (!s.hasPlan()) return false
        if (s.setup != Setup.ORB_UP && s.setup != Setup.ORB_DOWN) return false
        if (abs(s.target - s.entry) < MIN_TARGET_POINTS) return false
        if (!rangeNarrow(s)) return false
        return score > minScore
    }

    /** Part 2: India VIX broke UP (same day, closed bar) no later than the index broke DOWN. Breakouts never pass. */
    fun vixConfirms(vixSetup: Setup, vixBreakTs: Long, s: Scan): Boolean {
        if (s.setup != Setup.ORB_DOWN) return false
        return vixSetup == Setup.ORB_UP && vixBreakTs > 0 && vixBreakTs <= s.breakTs
    }

    fun breakBarClosed(s: Scan, nowSec: Long): Boolean = s.breakTs > 0 && nowSec >= s.breakTs + 300

    fun fresh(barTs: Long, nowSec: Long): Boolean = barTs > 0 && nowSec <= barTs + 300 + MAX_SIGNAL_AGE_SEC

    // ---- the flow's verdict --------------------------------------------------------------------------------------------

    /**
     * One index's way through the flow. [failed] the first stage it did not pass (null: it passed every stage - [take]);
     * [why] in words. [score] the score the gate used (full with odds, else lite), [lite] the lite score.
     */
    data class Verdict(
        val index: String, val failed: Stage?, val why: String, val setup: Setup, val score: Int? = null, val lite: Int? = null,
        val odds: Odds? = null, val plan: Plan? = null, val entry: Double? = null, val stop: Double? = null, val target: Double? = null,
        val breakTs: Long = 0L, val reasons: List<String> = emptyList(),
    ) {
        val take: Boolean get() = failed == null
        val put: Boolean get() = setup == Setup.ORB_DOWN
        /** The last stage this index reached (passed or not): what the page lights up. */
        val reached: Stage get() = failed ?: Stage.RISK_GATE
    }

    /**
     * The flow's decision for [index] at [nowSec]: its [today] 5-minute bars (closed ones only, no gap), [history] (past
     * sessions' 5-minute bars), India VIX's [vix] bars of today, [prevClose] and [daily] closes, the paper [capital].
     */
    fun judge(index: String, today: List<Upstox.Bar>, history: List<Upstox.Bar>, vix: List<Upstox.Bar>, day: LocalDate, nowSec: Long,
              capital: Double, prevClose: Double? = null, daily: List<Pair<LocalDate, Double>> = emptyList(), paths: Int = PATHS): Verdict {
        if (!tradable(index)) return Verdict(index, Stage.SCANNER, "not an index the flow trades (indices only: ${INDICES.joinToString()})", Setup.NONE)
        val s = scan(index, today, day, prevClose, daily)
        if (s.error != null) return Verdict(index, Stage.SCANNER, "no Zerodha bars today", Setup.NONE)
        val lite = liteScore(s)
        val reasons = s.why.toList()
        if (!s.hasPlan()) {
            val why = when {
                !s.orReady -> "opening range 09:15-09:30 not complete yet"
                s.setup == Setup.OVERSOLD_BOUNCE -> "oversold bounce - a watch setup, no plan to trade"
                else -> "still inside its opening range - no signal"
            }
            return Verdict(index, Stage.SCANNER, why, s.setup, lite = lite, reasons = reasons)
        }
        val base = Verdict(index, Stage.SCANNER, "", s.setup, lite = lite, entry = s.entry, stop = s.stop, target = alertTarget(s),
            breakTs = s.breakTs, reasons = reasons)
        if (istDate(s.breakTs) != day) return base.copy(why = "the break is from an earlier session")
        if (!breakBarClosed(s, nowSec)) return base.copy(why = "the break bar has not closed yet")
        if (!fresh(s.breakTs, nowSec)) return base.copy(why = "found too late (over ${MAX_SIGNAL_AGE_SEC / 60} min after its bar closed) - skipped, never taken as fresh")
        val odds = timeMachine(history + today, s, paths)
        val score = if (odds.ran) fullScore(s, odds) else lite
        val withOdds = base.copy(failed = Stage.IRASCORE, score = score, odds = odds)
        if (!alertWorthy(s, score)) return withOdds.copy(why = when {
            abs(s.target - s.entry) < MIN_TARGET_POINTS -> "under ${MIN_TARGET_POINTS.toInt()} points to its target"
            !rangeNarrow(s) -> "opening range wider than $MAX_RANGE_WIDTH_PCT% of the price"
            else -> "IraScore $score, needs above $MIN_SCORE"
        })
        val v = scan(VIX, vix, day)
        val vixOk = vix.isNotEmpty() && istDate(v.breakTs.takeIf { it > 0 } ?: 0L) == day && breakBarClosed(v, nowSec) &&
            vixConfirms(v.setup, v.breakTs, s)
        if (!vixOk) return withOdds.copy(why = if (s.setup == Setup.ORB_UP) "a breakout: IraThinker's gate takes breakdowns only (VIX must break up first)"
            else if (vix.isEmpty()) "no India VIX bars from the stream" else "India VIX had not broken up before the index broke down")
        if (!odds.passes) return withOdds.copy(failed = Stage.TIME_MACHINE, why = if (!odds.ran)
            "not enough Zerodha history yet (${odds.returns} five-minute returns, needs $MIN_RETURNS)"
            else "target first %.0f%% below stop first %.0f%%".format(Locale.ENGLISH, odds.targetFirst, odds.stopFirst))
        val plan = budget(s, capital, MAX_LOSS_PCT, istTime(nowSec))
        if (!plan.pass) return withOdds.copy(failed = Stage.RISK_GATE, plan = plan, why = "risk gate: " + plan.checks.filter { it.contains("FAIL") }.joinToString("; "))
        return withOdds.copy(failed = null, plan = plan, why = "passed the flow: IraScore $score, target first %.0f%% vs stop first %.0f%%".format(
            Locale.ENGLISH, odds.targetFirst, odds.stopFirst))
    }

    /** IraThinker's alert exit on the index for a held trade: "index stop" / "index target" when [price] reached it, else null. */
    fun indexExit(put: Boolean, price: Double?, stop: Double, target: Double): String? {
        if (price == null || price.isNaN() || price <= 0) return null
        return if (put) when { price >= stop -> "index stop"; price <= target -> "index target"; else -> null }
            else when { price <= stop -> "index stop"; price >= target -> "index target"; else -> null }
    }

    /** The paper stop for a fill at [fill] (the app's own: [PREMIUM_STOP] under it, on the 0.05 tick, never under one tick). */
    fun premiumStop(fill: Double): Double = maxOf(0.05, NtfyRules.round2(NtfyRules.tickDown(fill * (1 - PREMIUM_STOP))))

    /**
     * Whether a NEW entry may go now (null) or why not: the trading day and times (09:30-15:00, before the app's square-off), the
     * index's own expiry day (skipped: the app's convention for comparable arms), the switch, the guards, Zerodha's stream, the
     * day's cap ([DAILY_TOP_N]) and one trade per index a day.
     */
    fun entryRefusal(index: String, tradingDay: Boolean, now: LocalTime, expiryDay: Boolean, on: Boolean, kill: Boolean, stopped: Boolean,
                     dayLock: String?, streamLive: Boolean, takenToday: Int, indexDone: Boolean): String? = when {
        !tradable(index) -> "not an index the flow trades"
        !on -> "the arm is off"
        !tradingDay -> "market closed today (a holiday or weekend)"
        now.isBefore(OR_END) || !now.isBefore(LocalTime.of(15, 0)) -> "outside the entry window 09:30-15:00"
        expiryDay -> "$index's own expiry day - skipped (the app's convention for intraday option arms)"
        kill -> "the kill switch is on"
        stopped -> "the bot is stopped for today"
        dayLock != null -> dayLock
        !streamLive -> "Zerodha's stream is not live"
        takenToday >= DAILY_TOP_N -> "the day's $DAILY_TOP_N flow signals are taken"
        indexDone -> "$index already traded today"
        else -> null
    }

    // ---- Zerodha's minutes into the flow's 5-minute bars ----------------------------------------------------------------

    /** The folded day: [bars] the whole 5-minute bars (every minute there), [gapAt] the first missing minute (null: none). */
    data class Fold(val bars: List<Upstox.Bar>, val gapAt: Long?, val minutes: Int)

    /**
     * [ones] (one instrument's 1-minute candles from Zerodha's stream) on [day], from 09:15 to the last minute closed at [nowSec]
     * (15:29 at most, the index's last), folded into 5-minute bars starting 09:15, 09:20 ... A 5-minute bar is made only when all
     * five of its minutes are there; [Fold.gapAt] names the first missing minute (the stream started late or dropped), on which
     * the arm never decides.
     */
    fun fold5(ones: List<Upstox.Bar>, day: LocalDate, nowSec: Long): Fold {
        val open = day.atTime(OPEN).atZone(IST).toEpochSecond()
        val lastStart = minOf(Math.floorDiv(nowSec, 60L) * 60 - 60, day.atTime(CLOSE).atZone(IST).toEpochSecond() - 60)
        val byMinute = ones.filter { it.epochSecond in open..lastStart && it.close > 0 }.associateBy { Math.floorDiv(it.epochSecond, 60L) * 60 }
        var gap: Long? = null
        var t = open
        while (t <= lastStart) { if (t !in byMinute) { gap = t; break }; t += 60 }
        val bars = ArrayList<Upstox.Bar>()
        var start = open
        while (start + 240 <= lastStart) {
            val g = (0 until 5).mapNotNull { byMinute[start + 60L * it] }
            if (g.size == 5) bars += Upstox.Bar(start, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close,
                g.sumOf { it.volume }, 0)
            start += 300
        }
        return Fold(bars, gap, byMinute.size)
    }

    // ---- the session record (Zerodha's own 5-minute bars, kept for the Time Machine) --------------------------------------

    /** One session's 5-minute bars as text: "epoch,open,high,low,close;..." (prices to 2 decimals). */
    fun encode(bars: List<Upstox.Bar>): String = bars.sortedBy { it.epochSecond }.joinToString(";") { b ->
        "${b.epochSecond},${fmt(b.open)},${fmt(b.high)},${fmt(b.low)},${fmt(b.close)}"
    }

    private fun fmt(x: Double) = "%.2f".format(Locale.ENGLISH, x)

    /** [encode]'s text back; a damaged entry is skipped. */
    fun decode(text: String): List<Upstox.Bar> = text.split(';').mapNotNull { e ->
        val f = e.split(',')
        if (f.size != 5) return@mapNotNull null
        runCatching { Upstox.Bar(f[0].toLong(), f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), 0, 0) }.getOrNull()
            ?.takeIf { it.high >= it.low && it.close > 0 }
    }

    /** The sessions kept: the newest [SESSIONS_KEPT] days, each day's newest record winning. */
    fun keep(sessions: Map<LocalDate, String>, add: Pair<LocalDate, String>?): Map<LocalDate, String> {
        val m = java.util.TreeMap(sessions)
        if (add != null && add.second.isNotEmpty()) m[add.first] = add.second
        while (m.size > SESSIONS_KEPT) m.pollFirstEntry()
        return m
    }
}
