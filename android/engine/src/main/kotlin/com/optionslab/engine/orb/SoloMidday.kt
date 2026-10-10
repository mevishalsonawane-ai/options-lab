package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Solo (midday), the "risk-reduced Solo" Boss approved on 06 Oct 2026: PAPER ONLY, and NOT PROVEN. It replaces Solo's
 * online learner and the big-candle rules, which both lost on the research harness (research: solo2/SPEC.md - the learner
 * -Rs 4.13 L on 2,776 trades, the big-candle rules -Rs 2.12 L on 2,745, neither with a positive half-year). This one lost
 * far less on every test but passed no test bar: on the unseen period (Jul 2024 - Oct 2026) it made +Rs 31k on 196 trades,
 * profit factor 1.14, on four indices; on the three it can trade (below) +Rs 4k on 178, profit factor 1.02 ([RESEARCH]).
 * Pure: no clock, no network, no storage; the app feeds the bars and places the paper order.
 *
 * Every rule and constant is pinned exactly as the research code ran it (solo2.py `md`, lib.py `run_trade` / `pick_leg`,
 * solo2.py `portfolio` with `max_open=1`, the setup's `itm=4`, `lock=[(0.75, 0.0)]`, `tx=315`):
 *
 * - Indices: [UNDERLYINGS] (NIFTY, BANKNIFTY, FINNIFTY); one decision per index a day, on the 11:59 1-minute bar. SENSEX,
 *   in the research's four, is left out (review of 06 Oct): the app's paper account and instrument master are NSE F&O only
 *   (no BFO contract to buy), so a SENSEX signal could never be traded. The research's portfolio was re-run on the three
 *   (solo3: the same M08 candidates, max_open 1, strongest first - exact, as each index's candidates stand alone): on a
 *   day SENSEX was the strongest, the next strongest of the three is taken instead.
 * - The bars: minutes from 09:15 (bar start times). `o` = the first minute's open (09:15), `c` = the 11:59 bar's close,
 *   `H`/`L` = the high/low of the bars 09:15..11:59.
 * - Signal: `|c - o| >= 0.5 x ATR14` ([MOVE_ATR]); up when `c > o`, else down; the close in the outer 25% of the range
 *   ([OUTER]): up `(H - c) / (H - L) <= 0.25`, down `(c - L) / (H - L) <= 0.25` (no tolerance: the research's `posr > pos`).
 * - Strength = `|c - o| / ATR14`. Several indices signal: the strongest is taken; ties go to the index name in A-Z order (the
 *   research sorted by -strength, then setup, then index). One that may not be taken ([gate]: its expiry day, or another
 *   automatic position on that index - AutoExposure, "one index one side", which covers the Liquidity arm) passes the turn
 *   to the next strongest ([pick]). At most [MAX_OPEN] Solo position, one trade a day, no re-entry.
 * - ATR14 ([ShadowRules.atr14]): the mean true range of the 14 completed daily candles before today; no VIX filter.
 * - The 12:00 price: the 12:00 minute's open once the feed has it, else the 11:59 close (the research priced the 12:00
 *   minute's open). Entry from 12:00 to 12:03 only ([ENTRY_SLACK_MINUTES]: the research's first option open within 3
 *   minutes); missed after that.
 * - Strike: ATM of the 12:00 price, half up to the step ([step]: NIFTY 50, BANKNIFTY 100, FINNIFTY 50, SENSEX 100), 4
 *   steps in the money ([ITM_STEPS]: a call ATM - 4 steps, a put ATM + 4); the nearest expiry (never today: no trade on an
 *   expiry day). That strike not tradable: the nearest within one step, the less in-the-money first ([strikesToTry], as
 *   `pick_leg`), else skipped. 1 lot ([LOTS]), a MARKET buy as the paper account fills it.
 * - Index stop: `entry index - side x 0.3 x ATR14` ([STOP_ATR]), on a 1-minute CLOSE at or beyond it.
 * - Breakeven lock: the virtual target is 2R (`0.6 x ATR14`, [TARGET_R]); once the index has gone 75% of the way ([LOCK_AT],
 *   0.45 ATR, on the bar's high/low), the stop moves to the entry index level ([LOCK_KEEP] 0); a later bar touching it exits.
 *   Earned by the bars BEFORE the one checked; the index stop is checked first.
 * - Time exit at 14:30 ([EXIT]); no target, no premium stop, no day stop.
 * - The 30/60 rule (Boss's fixed 30-point stop and +60 target with the ladder) is NOT applied to Solo: the research's
 *   ablation (solo2/out/ablation.csv) found it cost -Rs 2.18 L against Solo's own exits on the same trades.
 * - Forward test (pre-registered, [judge]): after [FORWARD_TRADES] closed paper trades the record is reported; a net per
 *   trade at or below 0 then, or a drawdown beyond -Rs 25,000 at any time ([FORWARD_MAX_DRAWDOWN]), switches Solo off.
 *   After Boss switches it back on, the drawdown is measured from then ([Baseline]); the net bar is judged once, when the
 *   record reaches [FORWARD_TRADES] trades. (On the three indices the research's own unseen period fell -Rs 29,279 at
 *   worst: this bar can trip on the research's own path.)
 *
 * Only for the live minute data checks it adds what the O08 shadow ([ShadowRules.momoEntry]) pinned for the same 12:00
 * read: the first minute by 09:16 ([FIRST_BY]) and at least [MIN_MINUTES] minutes before 12:00.
 */
object SoloMidday {
    /** The tag on every trade of this Solo: its record (and the forward test) counts only these, from its first day. */
    const val TAG = "Solo (midday)"
    val UNDERLYINGS = listOf("NIFTY", "BANKNIFTY", "FINNIFTY")

    const val MOVE_ATR = 0.5
    const val OUTER = 0.25
    const val ITM_STEPS = 4
    const val STOP_ATR = 0.3
    const val TARGET_R = 2.0
    const val LOCK_AT = 0.75
    const val LOCK_KEEP = 0.0
    const val ENTRY_SLACK_MINUTES = 3L
    const val LOTS = 1
    const val MAX_OPEN = 1
    const val MIN_MINUTES = 100
    const val FORWARD_TRADES = 60
    const val FORWARD_MAX_DRAWDOWN = 25_000.0

    val DECIDE_AT: LocalTime = LocalTime.of(12, 0)
    val LAST_ENTRY: LocalTime = DECIDE_AT.plusMinutes(ENTRY_SLACK_MINUTES)
    val EXIT: LocalTime = LocalTime.of(14, 30)
    val FIRST_BY: LocalTime = LocalTime.of(9, 16)

    /** The research line shown with every Solo record (the TEST period re-run on its three indices: solo3_trades.csv). */
    const val RESEARCH = "Research, unseen period (Jul 2024 - Oct 2026), on NIFTY, BANKNIFTY and FINNIFTY: +₹4k on 178 trades, " +
        "profit factor 1.02 - about break-even, not proven."

    // Exit reasons, as the research named them.
    const val INDEX_STOP = "index_stop"
    const val LOCK = "lock"
    const val TIME = "time"

    private const val EPS = 1e-9

    /** The strike step: NIFTY and FINNIFTY 50, BANKNIFTY (and SENSEX, not traded) 100. */
    fun step(underlying: String): Int = if (underlying == "NIFTY" || underlying == "FINNIFTY") 50 else 100

    /** ATM of [index], half up to the step (the research's `floor(price / step + 0.5) * step`). */
    fun atm(underlying: String, index: Double): Int = OrbRules.atmStrike(index, step(underlying))

    /** The strike bought: [ITM_STEPS] steps in the money (below ATM for a call, [side] +1; above for a put, -1). */
    fun strikeFor(underlying: String, side: Int, index: Double): Int = atm(underlying, index) - side * ITM_STEPS * step(underlying)

    /** The strikes tried in order: 4 ITM, then one step either way, the less in-the-money first (`pick_leg`'s tie rule). */
    fun strikesToTry(underlying: String, side: Int, index: Double): List<Int> {
        val w = strikeFor(underlying, side, index)
        val s = step(underlying)
        return listOf(w, w + side * s, w - side * s)
    }

    fun stopLevel(side: Int, index: Double, atr: Double): Double = index - side * STOP_ATR * atr
    fun targetLevel(side: Int, index: Double, atr: Double): Double = index + side * TARGET_R * STOP_ATR * atr
    /** Where the index must reach (at its high for a call, its low for a put) for the stop to move to breakeven. */
    fun lockTrigger(side: Int, index: Double, atr: Double): Double = index + side * LOCK_AT * TARGET_R * STOP_ATR * atr
    /** The level the lock holds once earned: the entry index level ([LOCK_KEEP] of the way to 2R). */
    fun lockLevel(side: Int, index: Double, atr: Double): Double = index + side * LOCK_KEEP * TARGET_R * STOP_ATR * atr

    // ---- the signal ---------------------------------------------------------------------------------------------------

    /** One index's signal at 12:00: the morning's [open], 11:59 [close], [high]/[low], its [atr] and the 12:00 [index] price. */
    data class Signal(val underlying: String, val side: Int, val open: Double, val close: Double, val high: Double, val low: Double,
                      val atr: Double, val index: Double, val at: LocalDateTime) {
        val move: Double get() = close - open
        /** |c - o| / ATR14: how the indices are ranked. */
        val strength: Double get() = abs(move) / atr
        /** Where the close sits from its own side's edge of the range (0: at the high for an up move, at the low for a down). */
        val position: Double get() = if (side > 0) (high - close) / (high - low) else (close - low) / (high - low)
        val call: Boolean get() = side > 0
        val strike: Int get() = strikeFor(underlying, side, index)
        val stop: Double get() = stopLevel(side, index, atr)
        val target: Double get() = targetLevel(side, index, atr)
        val lockAt: Double get() = lockTrigger(side, index, atr)
        val lockLevel: Double get() = lockLevel(side, index, atr)
    }

    /** The day's decision for one index: the [signal], or why none ([why]); [strength] and [position] when they were read. */
    data class Decision(val underlying: String, val signal: Signal?, val why: String, val strength: Double? = null, val position: Double? = null)

    /**
     * [underlying]'s decision at [now] from today's 1-minute bars [ones] (bar start times; a forming bar may be there) and
     * [atr] (ATR14). From 12:00 to 12:03 only, once the 11:59 bar is in.
     */
    fun signal(underlying: String, ones: List<Bar>, atr: Double?, now: LocalDateTime): Decision {
        fun no(why: String, k: Double? = null, q: Double? = null) = Decision(underlying, null, why, k, q)
        val t = now.toLocalTime()
        if (t.isBefore(DECIDE_AT)) return no("before_12")
        if (!inEntryWindow(t)) return no("missed_12")
        if (atr == null || !(atr > 0)) return no("no_atr")
        val at = now.toLocalDate().atTime(DECIDE_AT)
        val today = ones.filter { it.start.toLocalDate() == now.toLocalDate() }
        val pre = today.filter { it.start.isBefore(at) }.sortedBy { it.start }.distinctBy { it.start }
        if (pre.isEmpty() || pre.last().start != at.minusMinutes(1)) return no("waiting_for_1159")
        if (pre.first().start.toLocalTime().isAfter(FIRST_BY)) return no("late_first_minute")
        if (pre.size < MIN_MINUTES) return no("too_few_minutes")
        val open = pre.first().open
        val c = pre.last().close
        val hi = pre.maxOf { it.high }
        val lo = pre.minOf { it.low }
        val move = c - open
        val k = abs(move) / atr
        if (abs(move) < MOVE_ATR * atr) return no("move_too_small", k)
        val side = if (move > 0) 1 else -1
        val pos = if (side > 0) (hi - c) / (hi - lo) else (c - lo) / (hi - lo)
        if (pos > OUTER) return no("mid_range", k, pos)
        val index = today.firstOrNull { it.start == at }?.open ?: c
        return Decision(underlying, Signal(underlying, side, open, c, hi, lo, atr, index, at), "signal", k, pos)
    }

    /** [t] within the entry minutes, 12:00 to 12:03 (the 12:03 minute included; nothing from 12:04). */
    fun inEntryWindow(t: LocalTime): Boolean = !t.isBefore(DECIDE_AT) && t.isBefore(LAST_ENTRY.plusMinutes(1))

    /**
     * May the paper order go in at [now] for a decision made on [day]? Read again right before it is placed (a slow read
     * after the decision must not carry the entry past 12:03).
     */
    fun mayPlace(day: LocalDate, now: LocalDateTime): Boolean = now.toLocalDate() == day && inEntryWindow(now.toLocalTime())

    /** The start of an open trade's walk: the 12:00 decision minute (the research's entry bar), whatever minute it filled. */
    fun entryTime(day: LocalDate): LocalDateTime = day.atTime(DECIDE_AT)

    // ---- the one chosen ------------------------------------------------------------------------------------------------

    /** The signals strongest first; a tie in strength goes to the index name, A-Z. */
    fun rank(signals: List<Signal>): List<Signal> = signals.sortedWith(compareByDescending<Signal> { it.strength }.thenBy { it.underlying })

    /** Why an index may not be taken: its expiry day (null: the instrument master unreadable - skipped too), or [autoConflict]. */
    fun gate(expiryToday: Boolean?, autoConflict: String?): String? = when {
        expiryToday == null -> "expiry_unknown"
        expiryToday -> "expiry_today"
        autoConflict != null -> autoConflict
        else -> null
    }

    /** The strongest signal [blocked] lets through ([taken], null: none), and those passed over with why. */
    data class Choice(val taken: Signal?, val skipped: List<Pair<Signal, String>>)

    fun pick(signals: List<Signal>, blocked: (Signal) -> String?): Choice {
        val skipped = ArrayList<Pair<Signal, String>>()
        for (s in rank(signals)) {
            val why = blocked(s)
            if (why == null) return Choice(s, skipped)
            skipped += s to why
        }
        return Choice(null, skipped)
    }

    // ---- the exit -------------------------------------------------------------------------------------------------------

    /** An open Solo trade as the exit reads it: the index [index] it came in at (the 12:00 price), its [atr], its entry time. */
    data class Open(val underlying: String, val side: Int, val index: Double, val atr: Double, val entryTime: LocalDateTime)

    /**
     * The walk over the closed bars since the entry minute: [exit] ([INDEX_STOP], [LOCK], [TIME]; null: hold), the bar it
     * was decided on ([decidedOn]; 14:30 for [TIME]), the [best] index move its way so far and whether the lock was earned.
     */
    data class Walk(val exit: String?, val decidedOn: LocalDateTime?, val best: Double, val locked: Boolean)

    /** [p]'s exit at [now] from today's 1-minute index bars [ones] (only those closed by [now] and starting before 14:30 are read). */
    fun walk(p: Open, ones: List<Bar>, now: LocalDateTime): Walk {
        val from = p.entryTime.withSecond(0).withNano(0)
        val exitAt = p.entryTime.toLocalDate().atTime(EXIT)
        val stop = stopLevel(p.side, p.index, p.atr)
        val lockLvl = lockLevel(p.side, p.index, p.atr)
        val earn = LOCK_AT * TARGET_R * STOP_ATR * p.atr
        var best = 0.0
        var locked = false
        val bars = ones.filter { !it.start.isBefore(from) && it.start.isBefore(exitAt) && !it.start.plusMinutes(1).isAfter(now) }
            .sortedBy { it.start }.distinctBy { it.start }
        for (b in bars) {
            val fav = if (p.side > 0) b.high - p.index else p.index - b.low
            // (The best move told with an exit includes the deciding bar; the lock only ever counts the bars before it.)
            if ((b.close - stop) * p.side <= 0) return Walk(INDEX_STOP, b.start, maxOf(best, fav), locked)
            if (locked && (if (p.side > 0) b.low <= lockLvl else b.high >= lockLvl)) return Walk(LOCK, b.start, maxOf(best, fav), true)
            best = maxOf(best, fav)
            if (best >= earn - EPS) locked = true
        }
        return if (!now.isBefore(exitAt)) Walk(TIME, exitAt, best, locked) else Walk(null, null, best, locked)
    }

    // ---- the forward test ------------------------------------------------------------------------------------------------

    /** The record so far: closed [trades], [net] after charges, [wins], the drawdown from the best now and the worst ever (<= 0). */
    data class Record(val trades: Int, val net: Double, val wins: Int, val drawdown: Double, val worstDrawdown: Double) {
        val perTrade: Double? get() = if (trades == 0) null else net / trades
    }

    /** The record of the closed trades' [nets] in order (the drawdown measured from the best, starting at 0). */
    fun record(nets: List<Double>): Record {
        var eq = 0.0
        var peak = 0.0
        var worst = 0.0
        for (n in nets) { eq += n; peak = maxOf(peak, eq); worst = minOf(worst, eq - peak) }
        return Record(nets.size, eq, nets.count { it > 0 }, eq - peak, worst)
    }

    enum class Verdict { RUNNING, PASSED, FAILED_NET, FAILED_DRAWDOWN }

    /**
     * Where the forward test is judged from: [from] closed trades in and the equity then ([peak], the level the drawdown
     * is measured from at first). Boss switching Solo back on sets it to the record at that moment; 0/0: from the start.
     */
    data class Baseline(val from: Int = 0, val peak: Double = 0.0) {
        /** The key a verdict is acted on once under ([verdict] at this baseline). */
        fun key(v: Verdict): String = "${v.name}@$from"

        /**
         * True when [acted] (the stored key) already covers [v] at this baseline. An older build stored the bare verdict
         * name with no baseline; with no switch-on recorded since ([from] 0) that counts as acted on, so Solo does not
         * switch itself off again after Boss turned it back on.
         */
        fun actedOn(acted: String?, v: Verdict): Boolean = acted == key(v) || (from == 0 && acted == v.name)
    }

    /** The baseline for a switch-on now, after the closed trades' [nets]. */
    fun baseline(nets: List<Double>): Baseline = Baseline(nets.size, nets.sum())

    /** The worst drawdown (<= 0) of [nets] after [b]: the equity from [Baseline.from] on, the peak starting at [Baseline.peak]. */
    fun drawdownSince(nets: List<Double>, b: Baseline): Double {
        var eq = nets.take(b.from).sum()
        var peak = b.peak
        var worst = 0.0
        for (n in nets.drop(b.from)) { eq += n; peak = maxOf(peak, eq); worst = minOf(worst, eq - peak) }
        return worst
    }

    /** The forward test's state: the [verdict], the whole [record], and the [drawdown] since the [base] (<= 0). */
    data class Check(val verdict: Verdict, val record: Record, val drawdown: Double, val base: Baseline)

    /**
     * The pre-registered bar on the closed trades' [nets] since [base]: a drawdown beyond -Rs 25,000 since the baseline
     * fails at any time; the net bar is judged once the record has 60 trades - a net per trade <= 0 fails - unless the
     * baseline is at or past 60 (Boss switched it back on after the 60-trade verdict: then only the drawdown watches).
     */
    fun judge(nets: List<Double>, base: Baseline = Baseline()): Check {
        val r = record(nets)
        val dd = drawdownSince(nets, base)
        val v = when {
            dd < -FORWARD_MAX_DRAWDOWN -> Verdict.FAILED_DRAWDOWN
            r.trades < FORWARD_TRADES -> Verdict.RUNNING
            r.net <= 0 && base.from < FORWARD_TRADES -> Verdict.FAILED_NET
            r.net <= 0 -> Verdict.RUNNING
            else -> Verdict.PASSED
        }
        return Check(v, r, dd, base)
    }

    /** [judge]'s verdict from the start (no switch-on since). */
    fun verdict(nets: List<Double>, base: Baseline = Baseline()): Verdict = judge(nets, base).verdict

    /** Switching off lowers risk: Solo does it by itself on a failed bar (turning it back on is Boss's choice). */
    fun switchOff(v: Verdict): Boolean = v == Verdict.FAILED_NET || v == Verdict.FAILED_DRAWDOWN

    // ---- words ----------------------------------------------------------------------------------------------------------

    private val DAY = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)
    fun rs(x: Double): String = (if (x < 0) "−₹" else "₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun pts(x: Double) = String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun lvl(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x).removeSuffix(".00")
    private fun k2(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)
    private fun pct(x: Double) = String.format(Locale.ENGLISH, "%.0f", x * 100)

    /** Why it bought: what it saw (the move in ATRs, where it closed in the range, the strongest of [signalled]). */
    fun why(s: Signal, signalled: Int): String =
        "${s.underlying} is ${if (s.side > 0) "up" else "down"} ${pts(s.move)} points from the open, ${k2(s.strength)} x its daily ATR " +
            "(${pts(s.atr)}), and it closed in the ${if (s.side > 0) "top" else "bottom"} ${pct(s.position)}% of the morning's range: a midday trend. " +
            (if (signalled > 1) "Of the $signalled indices that signalled, it was the strongest." else "It was the only index that signalled.")

    /** The exit plan: the index stop, the breakeven lock, 14:30. */
    fun plan(s: Signal): String =
        "Out if ${s.underlying} closes ${if (s.side > 0) "at or below" else "at or above"} ${lvl(s.stop)} (0.3 ATR); the stop moves to " +
            "breakeven (${lvl(s.lockLevel)}) once ${s.underlying} reaches ${lvl(s.lockAt)}; out at 14:30 at the latest. Record: this setup " +
            "made money before Jul 2024 and was about flat since. It is a test, not an edge."

    /** Why an index gave no signal today, in words ([d]: its decision). */
    fun skip(d: Decision): String = when (d.why) {
        "move_too_small" -> "${d.underlying} moved only ${k2(d.strength ?: 0.0)} ATR from the open (it needs 0.5)"
        "mid_range" -> "${d.underlying}'s close was mid-range (${pct(d.position ?: 0.0)}% from its edge; it needs the outer 25%)"
        "no_atr" -> "${d.underlying}'s daily ATR could not be read"
        "missed_12" -> "${d.underlying}: the 12:00 entry was missed (12:00-12:03 only)"
        "waiting_for_1159", "before_12" -> "${d.underlying}: waiting for the 11:59 minute"
        else -> "${d.underlying}: the morning's minutes were incomplete"
    }

    /** Why a signalling index was passed over: [why] from [gate] or the paper fill, or the stronger one already held. */
    fun passedOver(s: Signal, why: String, taken: Signal?): String = when (why) {
        "expiry_today" -> "${s.underlying} expires today"
        "expiry_unknown" -> "${s.underlying}'s expiry could not be read"
        "stronger" -> "I already hold ${taken?.underlying}, the stronger move (${s.underlying} was ${k2(s.strength)} ATR)"
        else -> "${s.underlying}: $why"
    }

    /** The exit in words: which rule fired, how far the index went its way at best, and an option lost despite the index. */
    fun exitSay(p: Open, w: Walk, indexAtExit: Double?, optionPnl: Double?): String {
        val two = TARGET_R * STOP_ATR * p.atr
        val rule = when (w.exit) {
            INDEX_STOP -> "the index stop (${p.underlying} closed through ${lvl(stopLevel(p.side, p.index, p.atr))})"
            LOCK -> "breakeven after reaching ${pct(w.best / two)}% of 2R (${p.underlying} came back to ${lvl(p.index)})"
            else -> "14:30"
        }
        val gain = indexAtExit?.let { (it - p.index) * p.side }
        return "Out by $rule. At best ${p.underlying} went ${pts(w.best)} points its way (${pct(w.best / two)}% of 2R)." +
            (if (gain != null && gain > 0 && optionPnl != null && optionPnl < 0) " The option lost although the index gained (time value)." else "")
    }

    /** The forward-test line: "x of 60", net, a trade, drawdown. */
    fun forwardLine(r: Record): String =
        "Forward test: ${minOf(r.trades, FORWARD_TRADES)} of $FORWARD_TRADES trades" + (if (r.trades > FORWARD_TRADES) " (${r.trades} in all)" else "") +
            ", net ${rs(r.net)}" + (r.perTrade?.let { " (${rs(it)} a trade)" } ?: "") + ", drawdown ${rs(r.drawdown)} (worst ${rs(r.worstDrawdown)})."

    /** What Solo's card shows: the label, the record since [since], "x of 60", the bar and the research line. */
    fun card(r: Record, since: LocalDate?): List<String> = listOf(
        "Not proven: paper only, never Zerodha.",
        "Trades since start" + (since?.let { " (${it.format(DAY)})" } ?: "") + ": ${r.trades}, net ${rs(r.net)}, " +
            (r.perTrade?.let { "${rs(it)} a trade" } ?: "no ₹/trade yet") + ", drawdown ${rs(r.drawdown)}.",
        "${minOf(r.trades, FORWARD_TRADES)} of $FORWARD_TRADES trades of the forward test.",
        "The bar (set in advance): after $FORWARD_TRADES trades it must make more than ₹0 a trade, and it switches itself off if it " +
            "is ever more than ${rs(FORWARD_MAX_DRAWDOWN)} below its best.",
        RESEARCH,
    )

    /** What Jarvis says of the forward test at [v]: the report at 60 trades, or why Solo switched itself off. */
    fun verdictSay(c: Check): String = verdictSay(c.record, c.verdict, c.drawdown, c.base.from > 0)

    /** As [verdictSay]; [drawdown] the drawdown judged (since the last switch-on when [sinceOn]). */
    fun verdictSay(r: Record, v: Verdict, drawdown: Double = r.worstDrawdown, sinceOn: Boolean = false): String = when (v) {
        Verdict.FAILED_DRAWDOWN -> "Boss, Solo (midday) is ${rs(drawdown)} below its best" + (if (sinceOn) " since you switched it back on" else "") +
            " - beyond the ${rs(FORWARD_MAX_DRAWDOWN)} set in " +
            "advance - so it has switched itself off (paper only; switching it back on is your choice). ${forwardLine(r)}"
        Verdict.FAILED_NET -> "Boss, Solo (midday) has ${r.trades} closed paper trades and makes ${rs(r.perTrade ?: 0.0)} a trade - the bar set in " +
            "advance needed more than ₹0 - so it has switched itself off (switching it back on is your choice). ${forwardLine(r)}"
        Verdict.PASSED -> "Boss, Solo (midday) has ${r.trades} closed paper trades: net ${rs(r.net)}, ${rs(r.perTrade ?: 0.0)} a trade, " +
            "worst drawdown ${rs(r.worstDrawdown)} - it passed the bar set in advance and stays on paper. Still not proven: real money is " +
            "not on the table unless it is positive without its best 3 days and the bootstrap says so."
        Verdict.RUNNING -> forwardLine(r)
    }
}
