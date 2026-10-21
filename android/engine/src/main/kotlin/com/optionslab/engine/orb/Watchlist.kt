package com.optionslab.engine.orb

import com.optionslab.engine.sandbox.PaperSpread
import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * The Watchlist (15 Oct 2026, Boss: "Both"): research's NEAR MISSES, followed in SHADOW. Shadow, not traded; information only,
 * not a trade signal ([NOTE]): nothing here places, changes, skips or resizes an order, paper or live, and no line it writes
 * looks like a trade alert. It builds each rule's forward sample on data none of the research saw (from 15 Oct 2026).
 *
 * The written selection rule ([SELECTION]): a rule that failed a gate goes on the Watchlist only when its final test made money
 * (net > 0) with p < 0.10 on one of the research's random-entry twin tests, or its BH q was just above the 0.10 gate (<= 0.15).
 * On it ([Rule]):
 *  - [Rule.C4] R47 C4, BANKNIFTY "high PCR -> buy ITM calls" (research/HUNT_R47_RELAXED_OPTION_BUYING.md; the Nov 2024+ final
 *    test +Rs 1,125 a trade on 55 trades, p 0.072; full-span holdout +Rs 865 on 91, p 0.0499, q 0.50). Its signal is the
 *    option chain's OI: NOT the default (CLAUDE.md), labelled so ([Rule.nonDefault]). At 10:15, the put/call OI ratio of
 *    ATM-10..+10 (near expiry) as at 10:14, ranked among the previous 60 sessions' (needs 40): top fifth buys the ITM3 call,
 *    bottom fifth the ITM3 put; stop -30%, +30% locked once +60%, out 15:15 (15:05 on its expiry day). Every day.
 *  - [Rule.R4_8] R52 R4-8, the Gann 2-bar swing chart break (g03; final +Rs 38,895 on 242 trades, coin-flip p 0.001, same-side
 *    p 0.128): 15-minute bars of NIFTY, BANKNIFTY and FINNIFTY (09:15-anchored, continuous across sessions), a close through
 *    the last swing top (bottom) buys the 1-ITM call (put); signal bars closing 09:20-14:45; out 30 minutes after the entry,
 *    square-off 15:10; one at a time per index, at most 3 a day; expiry days included (as R4 ran it).
 *  - [Rule.H45_1] R52 H45-1, the Liquidity 15+5 entries (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5: [LiquidityRules]'
 *    own levels, room filter and 09:20-14:00 window) with a +/-10% premium bracket and a 30-minute time stop (g14; final
 *    +Rs 31,275 on 341 trades, p 0.101, coin-flip p 0.001), 1-ITM, square-off 15:10, one at a time per book, expiry days skipped.
 * Left out: R51 M4 (the SILVERM morning call: its arm decides and orders in one step, with no shadow mode, so following it
 * would mean writing it again); R53 MA2 (the US-night silver arm is already a paper arm).
 *
 * Each record: the rule, the day and time, the signal, the exact contract, the entry and exit (time and price) and the would-be
 * P&L with the APP'S paper cost model ([price]: a MARKET fill moved by max(5 bps, the index's half-spread) on the tick, a
 * target resting at its price, the real charges). Per rule a scorecard ([score]): trades, win rate, net, and from 40 trades a
 * p against random entry minutes on the same contract ([pVsRandom]). At 40 new trades Jarvis says once which passed on
 * unseen data and which to drop ([verdictLine]) - it never promotes a rule by itself. Pure.
 */
object Watchlist {
    const val NOTE = "shadow, not traded; information only, not a trade signal"
    const val SELECTION = "On the Watchlist: rules that failed a research gate but whose final test made money (net > 0) with p < 0.10 on one " +
        "of the research's random-entry tests, or whose BH q was just above the 0.10 gate (0.15 or less). Followed in shadow only; " +
        "never traded or promoted by the app."
    const val LEFT_OUT = "Not on it: R51 M4 (the SILVERM morning call has no shadow mode in its arm; following it would mean rewriting it) and " +
        "R53 MA2 (already a paper arm)."
    /** The new-trade count at which Jarvis reports a rule once. */
    const val REVIEW_TRADES = 40
    const val TWINS = 20
    const val RESAMPLES = 2000

    enum class Rule(val key: String, val label: String, val research: String, val signalSource: String, val nonDefault: Boolean,
                    val underlyings: List<String>, val exits: PassedArms.PremiumExits, val words: String, val record: String) {
        C4("r47_c4", "R47 C4 BANKNIFTY high PCR", "R47 (C4)", "option chain OI (NON-DEFAULT: option-chain signal)", true, listOf("BANKNIFTY"),
            PassedArms.PremiumExits(0.30, null, null, listOf(0.60 to 0.30), LocalTime.of(15, 15)),
            "At 10:15, BANKNIFTY's put/call OI ratio (ATM±10) in the top fifth of the last 60 sessions buys the ITM3 call, the bottom fifth " +
                "the ITM3 put; stop −30%, +30% locked once +60%, out 15:15",
            "Nov 2024+ final test +₹1,125 a trade on 55 trades, p 0.072, q 0.76"),
        R4_8("r52_r4_8", "R52 R4-8 Gann swing break", "R52 (R4-8:g03)", "index 15-minute bars", false, listOf("NIFTY", "BANKNIFTY", "FINNIFTY"),
            PassedArms.PremiumExits(null, null, 30, emptyList(), LocalTime.of(15, 10)),
            "On 15-minute index bars a close through the last Gann 2-bar swing top (bottom) buys the 1-ITM call (put), 09:20-14:45; " +
                "out after 30 minutes; at most 3 a day, one at a time",
            "Final test +₹38,895 on 242 trades, p 0.128 (coin-flip p 0.001), q 0.67"),
        H45_1("r52_h45_1", "R52 H45-1 Liquidity ±10%", "R52 (H45-1:g14)", "index 15/30- and 5-minute bars (Liquidity levels)", false,
            listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"),
            PassedArms.PremiumExits(0.10, 0.10, 30, emptyList(), LocalTime.of(15, 10)),
            "The Liquidity 15+5 entries (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5) with a ±10% premium bracket and a 30-minute " +
                "time stop, 1-ITM; never on expiry days",
            "Final test +₹31,275 on 341 trades, p 0.101 (coin-flip p 0.001), q 0.62");

        /** The UI's line for it. */
        val tag: String get() = if (nonDefault) "NON-DEFAULT (option-chain signal)" else "signal on the index"

        companion object { fun of(key: String): Rule? = entries.firstOrNull { it.key == key } }
    }

    /** One shadow signal: the rule, the index and its book, the side, the entry minute and the contract (strike, CE / PE). */
    data class Signal(val rule: Rule, val underlying: String, val book: String, val side: Int, val entry: LocalDateTime, val strike: Int,
                      val why: String) {
        val right: String get() = if (side > 0) "CE" else "PE"
        val key: String get() = "${rule.key}|$book|$entry|$side"
    }

    // ---- C4 --------------------------------------------------------------------------------------------------------------

    val C4_AT: LocalTime = LocalTime.of(10, 15)
    const val C4_LOOK = 60
    const val C4_MIN = 40
    const val C4_MONEY = 3
    const val C4_STRIKES = 10

    /** R47 `prev_rank`: the share of the previous 60 sessions' finite values below [x] (null with fewer than 40 of them). */
    fun prevRank(x: Double, history: List<Double>): Double? {
        if (!x.isFinite()) return null
        val w = history.takeLast(C4_LOOK).filter { it.isFinite() }
        if (w.size < C4_MIN) return null
        return w.count { it < x }.toDouble() / w.size
    }

    /** C4's side from the rank: top fifth (>= 0.8) the call, bottom fifth (<= 0.2) the put, else none. */
    fun c4Side(rank: Double?): Int = when { rank == null -> 0; rank >= 0.8 -> 1; rank <= 0.2 -> -1; else -> 0 }

    /** The put/call OI ratio of the strikes ATM-10..+10 around [spot] ([oi]: strike to (CE OI, PE OI)); null when either sum is 0. */
    fun pcr(spot: Double, step: Int, oi: Map<Double, Pair<Long?, Long?>>): Double? {
        val atm = OrbRules.atmStrike(spot, step).toDouble()
        var ce = 0L; var pe = 0L
        for ((k, v) in oi) if (abs(k - atm) <= C4_STRIKES * step + 1e-9) { ce += v.first ?: 0L; pe += v.second ?: 0L }
        return if (ce > 0 && pe > 0) pe.toDouble() / ce else null
    }

    // ---- R4-8: the Gann 2-bar swing chart (h37 bars, r4 signals4.gann_swing) ---------------------------------------------

    /** One h37 bar: [date], its last row [colEnd], and its prices (a missing open / high / low takes the close; high and low widen to them). */
    data class HBar(val date: LocalDate, val colEnd: Int, val open: Double, val high: Double, val low: Double, val close: Double)

    /** h37 `sigs.bars` of [days] (oldest first) at [tf] minutes, anchored 09:15: bars with at least one close. */
    fun hbars(days: List<PassedArms.DayBars>, tf: Int): List<HBar> {
        val out = ArrayList<HBar>()
        for (d in days) {
            var j = 0
            while (j * tf < PassedArms.Session.N) {
                val rows = (j * tf until minOf((j + 1) * tf, PassedArms.Session.N))
                val withC = rows.filter { !d.c[it].isNaN() }
                if (withC.isNotEmpty()) {
                    val c = d.c[withC.last()]
                    val o = rows.firstOrNull { !d.o[it].isNaN() }?.let { d.o[it] } ?: c
                    val hs = rows.map { d.h[it] }.filter { !it.isNaN() }
                    val ls = rows.map { d.l[it] }.filter { !it.isNaN() }
                    val h = maxOf(if (hs.isEmpty()) c else hs.max(), o, c)
                    val l = minOf(if (ls.isEmpty()) c else ls.min(), o, c)
                    out += HBar(d.date, rows.last, o, h, l, c)
                }
                j++
            }
        }
        return out
    }

    /**
     * r4 `gann_swing` on [b] (continuous bars, oldest first): (bar index, side) for each break - a close above the last swing
     * top flips the trend up (the call), below the last swing bottom flips it down (the put). Every bar is walked (the
     * state is the whole series'); the caller keeps the bars it may trade.
     */
    fun gannSwing(b: List<HBar>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        if (b.isEmpty()) return out
        var p = 0; var cu = 0; var cd = 0; var d = 0
        var extHi = b[0].high; var extLo = b[0].low
        var top: Double? = null; var bot: Double? = null
        var trend = 0
        for (t in 1 until b.size) {
            val inside = b[t].high <= b[p].high && b[t].low >= b[p].low
            if (!inside) {
                val hh = b[t].high > b[p].high; val ll = b[t].low < b[p].low
                if (hh && !ll) { cu += 1; cd = 0 } else if (ll && !hh) { cd += 1; cu = 0 }
                p = t
            }
            extHi = maxOf(extHi, b[t].high); extLo = minOf(extLo, b[t].low)
            if (d != 1 && cu >= 2) {
                if (d == -1) bot = extLo
                d = 1; extHi = b[t].high; extLo = b[t].low
            } else if (d != -1 && cd >= 2) {
                if (d == 1) top = extHi
                d = -1; extHi = b[t].high; extLo = b[t].low
            }
            var sig = 0
            val tp = top; val bt = bot
            if (tp != null && trend != 1 && b[t].close > tp) { trend = 1; sig = 1 }
            else if (bt != null && trend != -1 && b[t].close < bt) { trend = -1; sig = -1 }
            if (sig != 0 && (if (sig > 0) bot else top) != null) out += t to sig
        }
        return out
    }

    val R48_FROM: Int = PassedArms.Session.row(9, 20)
    val R48_TO: Int = PassedArms.Session.row(14, 45)
    const val R48_TF = 15
    const val R48_PER_DAY = 3

    /** R4-8's signals today on [underlying] from [days] (the earlier sessions, then today's rows known so far). */
    fun r48Signals(underlying: String, days: List<PassedArms.DayBars>, today: LocalDate): List<Signal> {
        val b = hbars(days, R48_TF)
        val out = ArrayList<Signal>()
        for ((t, side) in gannSwing(b)) {
            val x = b[t]
            if (x.date != today || x.colEnd !in R48_FROM..R48_TO) continue
            // The last bar of today is complete only when its last minute is in (the caller passes rows known so far).
            val d = days.last()
            if (x.colEnd > d.lastRow) continue
            out += Signal(Rule.R4_8, underlying, "r4_$underlying", side, today.atTime(PassedArms.Session.time(x.colEnd + 1)),
                PassedArms.itmStrike(underlying, side, x.close, 1), "Gann swing ${if (side > 0) "top" else "bottom"} broken on the 15-minute close")
        }
        return out
    }

    // ---- H45-1: the Liquidity entries ------------------------------------------------------------------------------------

    /** H45-1's books (h4 comps BOOKS_EXT): the minutes of its two charts per index. */
    val H45_BOOKS: Map<String, List<Int>> = linkedMapOf("BANKNIFTY" to listOf(15, 5), "FINNIFTY" to listOf(30, 5), "MIDCPNIFTY" to listOf(15, 5))
    val H45_FROM: LocalTime = LocalTime.of(9, 20)
    val H45_TO: LocalTime = LocalTime.of(14, 0)

    /**
     * H45-1's entries today on [underlying] from 1-minute bars [ones] (the earlier sessions and today's closed minutes, bar
     * start times): on each book, each completed bar today whose close takes a pool sitting on an active swing zone
     * ([LiquidityRules.signal]) with room to the next level ([LiquidityRules.hasRoom]), the bar ending 09:20-14:00.
     */
    fun h45Signals(underlying: String, ones: List<Bar>, today: LocalDate, now: LocalDateTime): List<Signal> {
        val out = ArrayList<Signal>()
        for (tf in H45_BOOKS[underlying].orEmpty()) {
            val bars = LiquidityRules.completed(LiquidityRules.fold(ones, tf), tf, now)
            if (bars.size < 2 * LiquidityRules.SWING_LOOKBACK + 2) continue
            val zones = LiquidityRules.zones(bars)
            for (i in bars.indices) {
                val bar = bars[i]
                if (bar.start.toLocalDate() != today) continue
                val end = bar.start.plusMinutes(tf.toLong())
                if (end.toLocalTime().isBefore(H45_FROM) || end.toLocalTime().isAfter(H45_TO)) continue
                val s = LiquidityRules.signal(bars.subList(0, i + 1), zones) ?: continue
                if (!LiquidityRules.hasRoom(s, bar.close, underlying)) continue
                out += Signal(Rule.H45_1, underlying, "liq${tf}_$underlying", s.side, end, PassedArms.itmStrike(underlying, s.side, bar.close, 1),
                    "Liquidity ${tf}-minute pool taken at %.2f".format(Locale.ENGLISH, s.level))
            }
        }
        return out.sortedBy { it.entry }
    }

    // ---- pricing (the app's paper cost model) ----------------------------------------------------------------------------

    private val FLOOR_BPS = BigDecimal("5")
    private val TICK = BigDecimal("0.05")

    fun halfSpread(underlying: String): Double = PaperSpread.DEFAULTS[underlying] ?: PaperSpread.UNKNOWN
    fun paperBuy(underlying: String, p: Double): Double = PaperSpread.fill(BigDecimal(p.toString()), "BUY", halfSpread(underlying), FLOOR_BPS, TICK).toDouble()
    fun paperSell(underlying: String, p: Double): Double =
        if (p <= 0) 0.0 else PaperSpread.fill(BigDecimal(p.toString()), "SELL", halfSpread(underlying), FLOOR_BPS, TICK).toDouble()
    fun charge(buy: Boolean, px: Double, qty: Int): Double =
        if (px <= 0 || qty <= 0) 0.0 else SandboxCosts.charge(if (buy) "BUY" else "SELL", BigDecimal(px.toString()), qty).toDouble()

    /** A shadow trade priced: the fills (raw and after the paper model), the exit's time and why, the rupees after charges. */
    data class Priced(val entryAt: LocalDateTime, val entryRaw: Double, val entryFill: Double, val exitAt: LocalDateTime, val exitRaw: Double,
                      val exitFill: Double, val why: String, val qty: Int, val charges: Double) {
        val net: Double get() = (exitFill - entryFill) * qty - charges
        val points: Double get() = if (qty > 0) net / qty else 0.0
    }

    sealed class Outcome {
        /** No traded minute within 2 minutes of the entry: it would not have filled. */
        object NoFill : Outcome()
        /** Bought, not out yet (bars to [Open.through] read). */
        data class Open(val entryAt: LocalDateTime, val entryRaw: Double, val through: LocalDateTime?) : Outcome()
        data class Done(val p: Priced) : Outcome()
    }

    /**
     * [s]'s would-be trade from its option's 1-minute [bars] (bar start times) known at [now]: bought at the open of the entry
     * minute (or the first traded one up to 2 minutes later, else no fill), then [exits] minute by minute as the research's
     * engine ([PassedArms.exitOnBars]); the square-off sells at that minute's open (else the last close). [expiryDay]: the
     * index's own expiry day (out by 15:05 at the latest, as the app would). [lot]: units of one lot.
     */
    fun price(s: Signal, bars: List<Bar>, now: LocalDateTime, lot: Int, expiryDay: Boolean = false): Outcome {
        val sq = if (expiryDay && s.rule.exits.squareOff.isAfter(EXPIRY_SQ)) EXPIRY_SQ else s.rule.exits.squareOff
        return priceAt(s.entry, s.underlying, bars, now, lot, s.rule.exits.copy(squareOff = sq))
    }

    private val EXPIRY_SQ: LocalTime = LocalTime.of(15, 5)

    /** [price] for an entry at [entry] (also the twins'). */
    fun priceAt(entry: LocalDateTime, underlying: String, bars: List<Bar>, now: LocalDateTime, lot: Int, x: PassedArms.PremiumExits): Outcome {
        val byMin = bars.filter { it.start.toLocalDate() == entry.toLocalDate() }.sortedBy { it.start }.distinctBy { it.start }
        val first = byMin.firstOrNull { !it.start.isBefore(entry) && it.start.isBefore(entry.plusMinutes(3)) && it.open > 0 && !it.start.plusMinutes(1).isAfter(now) }
        if (first == null) return if (now.isBefore(entry.plusMinutes(3))) Outcome.Open(entry, Double.NaN, null) else Outcome.NoFill
        val e = first.open
        val w = PassedArms.exitOnBars(e, first.start, byMin, null, now, x)
        val ex = w.exit ?: return Outcome.Open(first.start, e, byMin.lastOrNull { !it.start.plusMinutes(1).isAfter(now) }?.start)
        val raw = if (ex.kind == PassedArms.ExitKind.SQUARE_OFF) {
            val sq = entry.toLocalDate().atTime(x.squareOff)
            byMin.firstOrNull { !it.start.isBefore(sq) && it.open > 0 }?.open ?: byMin.lastOrNull { it.start.isBefore(sq) }?.close ?: e
        } else ex.price
        val fillE = paperBuy(underlying, e)
        val fillX = if (ex.kind == PassedArms.ExitKind.TARGET) raw else paperSell(underlying, raw)
        val ch = charge(true, fillE, lot) + charge(false, fillX, lot)
        return Outcome.Done(Priced(first.start, e, fillE, ex.at, raw, fillX, ex.kind.why, lot, ch))
    }

    /** The minutes a twin may enter at, per rule (the rule's own window). */
    fun twinWindow(r: Rule): Pair<LocalTime, LocalTime> = when (r) {
        Rule.R4_8 -> LocalTime.of(9, 21) to LocalTime.of(14, 46)
        else -> LocalTime.of(9, 20) to LocalTime.of(14, 0)
    }

    /**
     * Up to [TWINS] random-entry twins of a priced trade: the same contract and exits, bought at a random minute of the rule's
     * window ([twinWindow]), seeded by the trade's key so a re-run gives the same twins. Their nets (rupees after charges).
     * Cheap, but not the research's test: the twin keeps the trade's side and contract (so it may "know" the day's direction).
     */
    fun twins(s: Signal, bars: List<Bar>, now: LocalDateTime, lot: Int, expiryDay: Boolean = false): List<Double> {
        val (a, b) = twinWindow(s.rule)
        val span = PassedArms.Session.row(b) - PassedArms.Session.row(a) + 1
        val rng = java.util.Random(s.key.hashCode().toLong())
        val sq = if (expiryDay && s.rule.exits.squareOff.isAfter(EXPIRY_SQ)) EXPIRY_SQ else s.rule.exits.squareOff
        val out = ArrayList<Double>()
        repeat(TWINS) {
            val m = s.entry.toLocalDate().atTime(a).plusMinutes(rng.nextInt(span).toLong())
            (priceAt(m, s.underlying, bars, now, lot, s.rule.exits.copy(squareOff = sq)) as? Outcome.Done)?.let { out += it.p.net }
        }
        return out
    }

    // ---- the scorecard ---------------------------------------------------------------------------------------------------

    data class Card(val rule: Rule, val trades: Int, val wins: Int, val net: Double, val p: Double?) {
        val winRate: Double? get() = if (trades == 0) null else wins.toDouble() / trades
        /** Passed on unseen data: money made and better than random entries (p < 0.05), at 40 trades or more. */
        val passed: Boolean get() = trades >= REVIEW_TRADES && net > 0 && p != null && p < 0.05
    }

    /**
     * The p of the rule's total against random entry minutes: the share of [RESAMPLES] totals, each drawing one twin per trade
     * ([twins] per trade, as [Watchlist.twins] made them), at or above the real total. Null with fewer than 40 trades or a
     * trade without twins.
     */
    fun pVsRandom(nets: List<Double>, twins: List<List<Double>>, seed: Long = 47L): Double? {
        if (nets.size < REVIEW_TRADES || twins.size != nets.size || twins.any { it.isEmpty() }) return null
        val real = nets.sum()
        val rng = java.util.Random(seed)
        var hit = 0
        repeat(RESAMPLES) {
            var t = 0.0
            for (tw in twins) t += tw[rng.nextInt(tw.size)]
            if (t >= real - 1e-9) hit++
        }
        return (hit + 1).toDouble() / (RESAMPLES + 1)
    }

    fun score(rule: Rule, nets: List<Double>, twins: List<List<Double>>): Card =
        Card(rule, nets.size, nets.count { it > 0 }, nets.sum(), pVsRandom(nets, twins))

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))

    /** One rule's scorecard line. */
    fun line(c: Card): String = "${c.rule.label}: ${c.trades} shadow trade${if (c.trades == 1) "" else "s"}" +
        (c.winRate?.let { ", ${String.format(Locale.ENGLISH, "%.0f", it * 100)}% won" } ?: "") + ", would-be net ${rs(c.net)}" +
        (c.p?.let { ", p vs random ${String.format(Locale.ENGLISH, "%.3f", it)}" } ?: if (c.trades < REVIEW_TRADES) " (p from $REVIEW_TRADES trades)" else "")

    /** The evening line: every rule's card, with the note. */
    fun eveningLine(cards: List<Card>): String = "Watchlist ($NOTE): " + cards.joinToString("; ") { line(it) } + "."

    /** Jarvis's one-time word when a rule reaches 40 shadow trades: passed on unseen data, or drop it. Never a promotion. */
    fun verdictLine(c: Card): String = if (c.passed)
        "Boss, the Watchlist's ${c.rule.label} has ${c.trades} shadow trades on unseen data: would-be net ${rs(c.net)}, better than random entries " +
            "(p ${String.format(Locale.ENGLISH, "%.3f", c.p ?: 1.0)}). It passed on unseen data; it stays in shadow - whether it ever trades is your call after a proper research read."
    else "Boss, the Watchlist's ${c.rule.label} has ${c.trades} shadow trades on unseen data: would-be net ${rs(c.net)}" +
        (c.p?.let { ", p ${String.format(Locale.ENGLISH, "%.3f", it)} against random entries" } ?: "") + ". It did not pass: I would drop it from the Watchlist."
}
