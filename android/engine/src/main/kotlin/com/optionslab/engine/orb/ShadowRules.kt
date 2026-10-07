package com.optionslab.engine.orb

import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The shadow tracker Boss approved on 06 Oct 2026: for each retired arm ([RetiredArms]) the research's best variant, and
 * one new candidate, each recording what it WOULD have done - never an order, paper or live. Bought options only (no
 * selling, no spreads), 1 lot, priced at the live option LTP with the paper account's fills (MARKET +5 bps, a stop -10 bps)
 * and the real charges ([SandboxCosts.charge]) on both legs. Pure: no clock, no network, no storage.
 *
 * The rules are pinned exactly as the research replays ran them (arms/orb, arms/fade, arms/momo, 06 Oct):
 *
 * [ORB_V43] "V43" (ORB report finalist F3; shared by ORB and ORB Fresh). BANKNIFTY. The ORB's opening range (5-minute bars
 *   09:15..10:00), the 09:20 bar's ATM strike (half-up to 100), the expiry on or after today. Decide on completed 5-minute
 *   bars after 10:00 and before 14:00: a close above the range buys the CE, below buys the PE - a FRESH break only (the bar
 *   before was not already out on that side: ORB Fresh's rule; ORB's first trade of a day is always that bar). PCR filter:
 *   PCR(OI) = put OI / call OI summed over the strikes ATM-10..ATM+10, each at its last minute before 10:00; a CE needs
 *   PCR >= 0.95, a PE needs PCR <= 0.85 (unknown PCR: no entry). At most one trade a day. A premium at or under 40 is
 *   refused. Exits: the -40 SL-M ([OrbRules.stopTrigger]); no target, no ladder; out at 14:30.
 *
 * [SWEEP_S17] "S17" (fade report finalist F3: "S17_oi_struct_mid"). BANKNIFTY, the ORB's range, strike and expiry. ORB
 *   Sweep's signal ([SweepRules.entrySignal]: bars after 10:00 and before 14:30, at most 2 entries a day, re-entry from the
 *   bar after an exit). OI wall (strict): for a PE (a failed break of the high) the CE strike at the range high rounded up
 *   to 100 must hold the most CE OI among the strikes ATM..ATM+10; for a CE the PE strike at the range low rounded down must
 *   hold the most PE OI among ATM-10..ATM; OI at the last minute before the signal bar closed. ATM option. Exits: the -40
 *   SL-M; an index STOP on a 1-minute close beyond the sweep bar's extreme (its high for a PE, its low for a CE); an index
 *   TARGET on a 1-minute close at or through the range mid; no premium target, no ladder; out at 15:10.
 *
 * [FADE_R20] "R20" (fade report, "R20_1200_oi": Range Fade's best variant on FIT with a single bought option - net -2,720 on
 *   134 trades, PF 0.88; the only better one, R23, is a debit spread and so not allowed here). BANKNIFTY, the ORB's range,
 *   strike and expiry. Range Fade's signal ([RangeFadeRules.entrySignal]: edge 10%, at most 2 a day, re-entry after the exit
 *   bar) on bars from 12:00 (not 10:30) to before 14:00, with the same OI wall as S17. ATM option. Exits: -40 SL-M, +40
 *   target, the plain profit-lock ladder on +40 ([ProfitLock.level], no charges in the breakeven rung), out at 15:10.
 *
 * [MOMO_O08] "O08", "Midday momentum (NIFTY)" (momo report finalist F2, NIFTY only; a new candidate, not a retired arm).
 *   At 12:00, on NIFTY's 1-minute bars before 12:00: the last close (11:59) is at least 0.5 x ATR14 from the day's open
 *   (ATR14: the mean true range of the 14 sessions before today) and in the outer 25% of the day's range so far on that
 *   side. Buy the option 4 strikes in the money (CE up, PE down; ATM of the 12:00 index price, half-up to 50) on the
 *   expiry on or after today. Index stop: 0.3 x ATR14 against the 12:00 index price, on a 1-minute close; out at 14:30. No
 *   premium stop. Entered only from 12:00 to 12:03 (the research priced the 12:00 minute, 3 minutes' slack).
 *
 * The losing-trades study's five ([ORB_P10], [ORBF_P10], [SWEEP_H1], [SWEEP_14], [FADE_P10]; Boss, 06 Oct): pinned and walked
 * in [ShadowStudy]. Every shadow trade also keeps its best and worst move and why it lost ([ShadowStudy.lossGroup]).
 */
object ShadowRules {
    /** One shadowed rule: [id] (its key in the book), [name] (the research's), the [arms] it shadows (none: a new candidate). */
    data class Variant(val id: String, val name: String, val label: String, val arms: List<Arm>, val underlying: String, val description: String)

    val ORB_V43 = Variant("orb_v43", "V43", "ORB / ORB Fresh", listOf(OrbRules.ORB, OrbRules.ORB_FRESH), OrbRules.UNDERLYING,
        "BANKNIFTY: the first fresh close out of the 09:15-10:00 range (10:05-13:55 bars), only when PCR(OI) at 10:00 agrees " +
            "(CE at 0.95 or more, PE at 0.85 or less); 1 a day, ATM of 09:20, -40 stop, no target, out 14:30")
    val SWEEP_S17 = Variant("sweep_s17", "S17", SweepRules.ARM.label, listOf(SweepRules.ARM), OrbRules.UNDERLYING,
        "BANKNIFTY: ORB Sweep's failed break (10:05-14:25 bars, 2 a day) only at an OI wall at the range edge; ATM, -40 stop, " +
            "out on a 1-minute index close beyond the sweep's extreme or at the range mid, else 15:10")
    val FADE_R20 = Variant("fade_r20", "R20", RangeFadeRules.ARM.label, listOf(RangeFadeRules.ARM), OrbRules.UNDERLYING,
        "BANKNIFTY: Range Fade's edge touch from 12:00 (12:00-13:55 bars, 2 a day) only at an OI wall at the range edge; ATM, " +
            "-40 / +40 with the profit-lock ladder, out 15:10")
    val MOMO_O08 = Variant("momo_o08", "O08", "Midday momentum (NIFTY)", emptyList(), "NIFTY",
        "NIFTY at 12:00: 0.5 x ATR14 from the open and in the outer 25% of the day's range; buys 4 strikes in the money, " +
            "index stop 0.3 x ATR14 on a 1-minute close, out 14:30")

    // The losing-trades study's shadows (Boss, 06 Oct: "enhance the shadows"), pinned in [ShadowStudy]: for each retired arm
    // the finalist with the best TEST net, and one long shot. None made money out of sample; tracked, never traded.
    val ORB_P10 = Variant("orb_p10", "OP10", OrbRules.ORB.label, listOf(OrbRules.ORB), OrbRules.UNDERLYING,
        "BANKNIFTY: ORB's break, first entry only, when the last 30 minutes' volatility is not in the quietest fifth (2.73 bp a " +
            "minute or more); a LIMIT 10 under the price, 15 minutes to fill; ATM of 09:20, -40 / +40 with the profit lock, out 15:10")
    val ORBF_P10 = Variant("orbf_p10", "FP10", OrbRules.ORB_FRESH.label, listOf(OrbRules.ORB_FRESH), OrbRules.UNDERLYING,
        "BANKNIFTY: ORB Fresh's fresh break, first entry only, when the last 30 minutes' volatility is not in the quietest fifth " +
            "(2.73 bp a minute or more); a LIMIT 10 under the price, 15 minutes to fill; ATM of 09:20, -40 / +40 with the profit lock, out 15:10")
    val SWEEP_H1 = Variant("sweep_h1", "SH1", SweepRules.ARM.label, listOf(SweepRules.ARM), OrbRules.UNDERLYING,
        "BANKNIFTY: ORB Sweep's failed break, first entry only; ATM of 09:20, -40 stop, no target and no profit lock, out 15:10")
    val SWEEP_14 = Variant("sweep_14", "S14", SweepRules.ARM.label, listOf(SweepRules.ARM), OrbRules.UNDERLYING,
        "BANKNIFTY, a long shot: ORB Sweep's own rules with entries only from 14:00 (13:55-14:25 bars, 2 a day); ATM of 09:20, " +
            "-40 / +80 with the profit lock, out 15:10")
    val FADE_P10 = Variant("fade_p10", "RP10", RangeFadeRules.ARM.label, listOf(RangeFadeRules.ARM), OrbRules.UNDERLYING,
        "BANKNIFTY: Range Fade's edge touch (10:30-13:55 bars), first entry only, when the last 30 minutes' volatility is not in " +
            "the quietest fifth (2.73 bp a minute or more); a LIMIT 10 under the price, 15 minutes to fill; ATM of 09:20, -40 / +40 " +
            "with the profit lock, out 15:10")

    val ALL: List<Variant> = listOf(ORB_V43, SWEEP_S17, FADE_R20, MOMO_O08, ORB_P10, ORBF_P10, SWEEP_H1, SWEEP_14, FADE_P10)

    fun of(id: String): Variant? = ALL.firstOrNull { it.id == id }
    /** The first variant shadowing the retired arm [source] (the research's own), or null. */
    fun forArm(source: String): Variant? = ALL.firstOrNull { v -> v.arms.any { it.source == source } }
    /** Every variant shadowing the retired arm [source], the research's own first. */
    fun allForArm(source: String): List<Variant> = ALL.filter { v -> v.arms.any { it.source == source } }

    /** The index of the best record among [nets] (the highest net; a tie keeps the first), or -1 with none. */
    fun bestIndex(nets: List<Double>): Int = nets.indices.maxWithOrNull(compareBy<Int> { nets[it] }.thenByDescending { it }) ?: -1

    /** The Shadows section's one line for an arm with [n] shadows: the best one's [line], and that there are more. */
    fun bestOf(n: Int, line: String): String = if (n <= 1) line else "Best of $n shadows · $line"

    // ---- pinned constants ---------------------------------------------------------------------------------------------

    const val STOP_POINTS = 40.0
    const val FADE_TARGET = 40.0
    const val PCR_CE_MIN = 0.95
    const val PCR_PE_MAX = 0.85
    /** Strikes either side of the ATM in the PCR sum and the OI wall's look (the research's ATM-10..ATM+10 data). */
    const val CHAIN_STRIKES = 10
    const val STEP_BANK = 100
    const val STEP_NIFTY = 50
    const val MOMO_MOVE_ATR = 0.5
    const val MOMO_OUTER = 0.25
    const val MOMO_STOP_ATR = 0.3
    const val MOMO_ITM = 4
    const val MOMO_MIN_MINUTES = 100
    const val ATR_DAYS = 14
    const val MARKET_BPS = 5
    const val STOP_BPS = 10
    const val PROMOTE_TRADES = 60
    const val PROMOTE_PF = 1.2

    val PCR_AT: LocalTime = LocalTime.of(10, 0)
    val ORB_LAST_BAR: LocalTime = OrbRules.LAST_ENTRY_BAR
    val ORB_EXIT: LocalTime = LocalTime.of(14, 30)
    val FADE_FIRST_BAR: LocalTime = LocalTime.of(12, 0)
    val MOMO_AT: LocalTime = LocalTime.of(12, 0)
    val MOMO_LAST_ENTRY: LocalTime = LocalTime.of(12, 3)
    val MOMO_EXIT: LocalTime = LocalTime.of(14, 30)
    /** The momentum rule's data must start by 09:16 (the research dropped a day whose first minute came later). */
    val MOMO_FIRST_BY: LocalTime = LocalTime.of(9, 16)
    private const val EPS = 1e-9

    // ---- entries --------------------------------------------------------------------------------------------------------

    /**
     * One entry the shadow takes: [side] +1 buys the CE, -1 the PE; [strike]; [signalBar] the bar (or minute) it decided on;
     * [extreme] the sweep bar's extreme (S17's index stop), [mid] the range mid (S17's index target), [indexStop] O08's level.
     */
    data class Entry(val side: Int, val strike: Int, val signalBar: LocalDateTime, val extreme: Double? = null, val mid: Double? = null,
                     val indexStop: Double? = null)

    /** A decision: the entry, or why none (a word the app shows). */
    data class Decision(val entry: Entry?, val why: String)

    private fun no(why: String) = Decision(null, why)

    /** Whether PCR [pcr] confirms a break to [side]: CE at [PCR_CE_MIN] or more, PE at [PCR_PE_MAX] or less. */
    fun pcrConfirms(side: Int, pcr: Double): Boolean = if (side > 0) pcr >= PCR_CE_MIN else pcr <= PCR_PE_MAX

    /** The strikes summed into the PCR around the day's [atm] (both rights). */
    fun pcrStrikes(atm: Int): List<Int> = (-CHAIN_STRIKES..CHAIN_STRIKES).map { atm + it * STEP_BANK }

    /** Put OI / call OI over the strikes read ([ce], [pe]: strike -> OI); null when there is no call OI. */
    fun pcr(ce: Map<Int, Long>, pe: Map<Int, Long>): Double? {
        val c = ce.values.sum()
        return if (c > 0) pe.values.sum().toDouble() / c else null
    }

    /** The OI as of the last minute that started before [cutoff] ([minutes]: start -> OI), or null. */
    fun oiBefore(minutes: List<Pair<LocalDateTime, Long>>, cutoff: LocalDateTime): Long? =
        minutes.filter { it.first.isBefore(cutoff) }.maxByOrNull { it.first }?.second

    /** The day's ATM from the first bar at or after 09:20 (there is one whenever the opening range is complete). */
    private fun dayStrike(bars: List<Bar>): Int = OrbRules.atmStrike(bars.first { !it.start.toLocalTime().isBefore(OrbRules.STRIKE_BAR) }.close)

    /** V43's decision on the last of the day's completed 5-minute BANKNIFTY [bars]; [taken]: its trades today; [pcr] at 10:00. */
    fun orbEntry(bars: List<Bar>, taken: Int, pcr: Double?): Decision {
        val rng = OrbRules.openingRange(bars) ?: return no("waiting_for_opening_range")
        val strike = dayStrike(bars)
        if (taken >= 1) return no("done_for_today")
        val last = bars.last()
        if (!OrbRules.mayDecide(last, ORB_LAST_BAR)) return no("no_decision_bar")
        val side = OrbRules.breakDirection(last, rng.first, rng.second)
        if (side == 0) return no("inside_range")
        // (The bar before is always there: the range's 10:00 bar at least.)
        if (OrbRules.breakDirection(bars[bars.size - 2], rng.first, rng.second) == side) return no("not_a_fresh_break")
        if (pcr == null) return no("no_pcr")
        if (!pcrConfirms(side, pcr)) return no("pcr_against")
        return Decision(Entry(side, strike, last.start), "break")
    }

    /**
     * A raw signal before the OI wall: S17's ([sweep] true) or R20's on the last completed bar of [bars]; [entries] today
     * and the [lastExit]. The entry's [Entry.extreme] is the signal bar's high (a PE) or low (a CE).
     */
    fun edgeSignal(sweep: Boolean, bars: List<Bar>, entries: Int, lastExit: LocalDateTime?): Decision {
        val rng = OrbRules.openingRange(bars) ?: return no("waiting_for_opening_range")
        val strike = dayStrike(bars)
        val last = bars.last()
        if (!sweep && last.start.toLocalTime().isBefore(FADE_FIRST_BAR)) return no("no_decision_bar")
        val (side, why) = if (sweep) SweepRules.entrySignal(bars, rng, lastExit, entries) else RangeFadeRules.entrySignal(bars, rng, lastExit, entries)
        if (side == 0) return no(why)
        return Decision(Entry(side, strike, last.start, if (side < 0) last.high else last.low, (rng.first + rng.second) / 2), why)
    }

    /** The right whose OI makes the wall for an entry to [side]: the CE above the range for a PE, the PE below for a CE. */
    fun wallRight(side: Int): String = if (side < 0) "CE" else "PE"

    /** The strikes the wall is looked for among: ATM..ATM+10 (a PE's CE wall) or ATM-10..ATM (a CE's PE wall). */
    fun wallStrikes(side: Int, atm: Int): List<Int> = (0..CHAIN_STRIKES).map { if (side < 0) atm + it * STEP_BANK else atm - it * STEP_BANK }

    /** The edge strike: the range high rounded up to 100 (a PE), or the range low rounded down (a CE). */
    fun edgeStrike(side: Int, rng: Pair<Double, Double>): Int =
        if (side < 0) (ceil(rng.first / STEP_BANK) * STEP_BANK).toInt() else (floor(rng.second / STEP_BANK) * STEP_BANK).toInt()

    /** The strict OI wall: the edge strike holds the most OI among [oi] (strike -> OI, the wall's strikes read), above none. */
    fun oiWall(side: Int, rng: Pair<Double, Double>, oi: Map<Int, Long>): Boolean {
        val edge = oi[edgeStrike(side, rng)] ?: return false
        val best = oi.values.max()
        return best > 0 && edge >= best
    }

    /**
     * ATR14 for [day] from daily bars ([daily], any order, one a session): the mean true range (high-low, or the gap to the
     * previous close when larger) of the 14 sessions before [day]; null with fewer than 15 sessions before it.
     */
    fun atr14(daily: List<Bar>, day: LocalDate): Double? {
        val d = daily.filter { it.start.toLocalDate().isBefore(day) }.sortedBy { it.start }.distinctBy { it.start.toLocalDate() }
        if (d.size < ATR_DAYS + 1) return null
        val tail = d.takeLast(ATR_DAYS + 1)
        return (1..ATR_DAYS).map { i ->
            val b = tail[i]; val pc = tail[i - 1].close
            maxOf(b.high - b.low, abs(b.high - pc), abs(b.low - pc))
        }.average()
    }

    /** O08's strike: ATM of [index] (half-up to 50), 4 strikes in the money (below for a CE, above for a PE). */
    fun momoStrike(side: Int, index: Double): Int = OrbRules.atmStrike(index, STEP_NIFTY) - side * MOMO_ITM * STEP_NIFTY

    /**
     * O08's decision at [now] on today's NIFTY 1-minute [ones] (each bar labelled by its start, today's only) with [atr]
     * (ATR14, [atr14]). Decided from 12:00, once the 11:59 minute is in, up to [MOMO_LAST_ENTRY].
     */
    fun momoEntry(ones: List<Bar>, atr: Double?, now: LocalDateTime): Decision {
        val t = now.toLocalTime()
        if (t.isBefore(MOMO_AT)) return no("before_12")
        if (t.isAfter(MOMO_LAST_ENTRY.plusSeconds(59))) return no("missed_12")
        if (atr == null || !(atr > 0)) return no("no_atr")
        val day = now.toLocalDate()
        val at = day.atTime(MOMO_AT)
        val pre = ones.filter { it.start.isBefore(at) }.sortedBy { it.start }
        if (pre.isEmpty() || pre.last().start != at.minusMinutes(1)) return no("waiting_for_1159")
        if (pre.first().start.toLocalTime().isAfter(MOMO_FIRST_BY)) return no("late_first_minute")
        if (pre.size < MOMO_MIN_MINUTES) return no("too_few_minutes")
        val open = pre.first().open
        val c = pre.last().close
        val hi = pre.map { it.high }.max()
        val lo = pre.map { it.low }.min()
        val move = c - open
        if (abs(move) < MOMO_MOVE_ATR * atr) return no("move_too_small")
        val side = if (move > 0) 1 else -1
        val pos = if (side > 0) (hi - c) / (hi - lo) else (c - lo) / (hi - lo)
        if (pos > MOMO_OUTER + EPS) return no("not_in_outer_quarter")
        // The 12:00 index price: that minute's open once it prints, the 11:59 close until then.
        val index = ones.firstOrNull { it.start == at }?.open ?: c
        return Decision(Entry(side, momoStrike(side, index), at, indexStop = index - side * MOMO_STOP_ATR * atr), "momentum")
    }

    // ---- exits ----------------------------------------------------------------------------------------------------------

    /** An open shadow position as the exits read it; [peak] is the best LTP seen before this look (R20's ladder). */
    data class Open(val variant: String, val side: Int, val entry: Double, val entryTime: LocalDateTime, val peak: Double = entry,
                    val extreme: Double? = null, val mid: Double? = null, val indexStop: Double? = null)

    /**
     * Why [p] exits now, or null to hold: at [now] with the option at [ltp] (null: no price, so only the clock and the index
     * decide) and the index's 1-minute bars [ones] (S17 and O08 read those that started at or after the entry minute and
     * have closed by [now]).
     */
    fun exitReason(p: Open, ltp: Double?, now: LocalDateTime, ones: List<Bar>): String? {
        val t = now.toLocalTime()
        val since = ones.filter { !it.start.isBefore(p.entryTime.withSecond(0).withNano(0)) && !it.start.plusMinutes(1).isAfter(now) }
            .sortedBy { it.start }
        val stop = OrbRules.stopTrigger(p.entry)
        fun stopped() = ltp != null && stop != null && ltp <= stop + EPS
        return when (p.variant) {
            ORB_V43.id -> when {
                !t.isBefore(ORB_EXIT) -> "time_exit"
                stopped() -> "stop"
                else -> null
            }
            SWEEP_S17.id -> when {
                !t.isBefore(OrbRules.SQUARE_OFF) -> "session_end"
                else -> since.firstNotNullOfOrNull { b -> sweepIndexExit(p, b.close) } ?: if (stopped()) "stop" else null
            }
            FADE_R20.id -> when {
                !t.isBefore(OrbRules.SQUARE_OFF) -> "session_end"
                stopped() -> "stop"
                ltp != null && ProfitLock.exits(p.entry, FADE_TARGET, p.peak, ltp) -> "profit_lock"
                ltp != null && ltp >= p.entry + FADE_TARGET - EPS -> "target"
                else -> null
            }
            MOMO_O08.id -> when {
                !t.isBefore(MOMO_EXIT) -> "time_exit"
                p.indexStop != null && since.any { (it.close - p.indexStop) * p.side <= 0 } -> "index_stop"
                else -> null
            }
            // The study's shadows exit on the option's candles ([ShadowStudy.walk]); on the LTP only at 15:10, when no 15:10
            // candle has closed yet.
            else -> if (ShadowStudy.ruleOf(p.variant) == null) "unknown_variant" else if (!t.isBefore(OrbRules.SQUARE_OFF)) "session_end" else null
        }
    }

    /** S17's index exits on one closed minute's [close]: the stop beyond the sweep's extreme first, then the mid. */
    private fun sweepIndexExit(p: Open, close: Double): String? {
        val ext = p.extreme
        val mid = p.mid
        return when {
            ext != null && ((p.side < 0 && close > ext) || (p.side > 0 && close < ext)) -> "index_stop"
            mid != null && ((p.side < 0 && close <= mid) || (p.side > 0 && close >= mid)) -> "index_target"
            else -> null
        }
    }

    // ---- fills and charges ---------------------------------------------------------------------------------------------

    private fun slip(price: Double, action: String, bps: Int): Double =
        SandboxCosts.adverse(BigDecimal(price.toString()), action, BigDecimal(bps)).toDouble()

    /** The paper account's MARKET buy at [ltp] (no bid/ask: the LTP slipped 5 bps against). */
    fun entryFill(ltp: Double): Double = slip(ltp, "BUY", MARKET_BPS)

    /** Whether an entry at [fill] is refused: a premium at or under 40 (a 40-point stop has no level), as the arms refuse it. */
    fun refused(fill: Double): Boolean = OrbRules.stopTrigger(fill) == null

    /** The exit fill: a stop at its trigger or the LTP below it, -10 bps; anything else a MARKET sell at [ltp], -5 bps. */
    fun exitFill(why: String, entry: Double, ltp: Double): Double {
        val trig = OrbRules.stopTrigger(entry)
        return if (why == "stop" && trig != null) slip(minOf(trig, ltp), "SELL", STOP_BPS) else slip(ltp, "SELL", MARKET_BPS)
    }

    /** Both legs' charges for [qty] units bought at [entry] and sold at [exit]. */
    fun charges(entry: Double, exit: Double, qty: Int): Double =
        SandboxCosts.charge("BUY", BigDecimal(entry.toString()), qty).toDouble() + SandboxCosts.charge("SELL", BigDecimal(exit.toString()), qty).toDouble()

    // ---- the record ----------------------------------------------------------------------------------------------------

    /** One closed shadow trade's [net] rupees after charges. */
    data class Summary(val trades: Int, val net: Double, val won: Double, val lost: Double) {
        val perTrade: Double? get() = if (trades == 0) null else net / trades
        /** Gross won / gross lost after charges; null with no losing trade. */
        val profitFactor: Double? get() = if (lost > 0) won / lost else null
    }

    fun summarize(nets: List<Double>): Summary =
        Summary(nets.size, nets.sum(), nets.filter { it > 0 }.sum(), -nets.filter { it < 0 }.sum())

    /** Whether Jarvis puts the variant to Boss: [PROMOTE_TRADES] closed trades, net after charges above 0, PF at least 1.2. */
    fun promotionDue(s: Summary): Boolean =
        s.trades >= PROMOTE_TRADES && s.net > 0 && (s.profitFactor ?: Double.POSITIVE_INFINITY) >= PROMOTE_PF - EPS

    private val DAY = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)
    private fun rs(x: Double) = (if (x < 0) "−₹" else "₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))

    /** "Shadow (V43, no orders): 12 trades since 06 Oct, net −₹3,400 (−₹283 a trade)". */
    fun line(v: Variant, s: Summary, since: LocalDate): String =
        "Shadow (${v.name}, no orders): ${s.trades} trade${if (s.trades == 1) "" else "s"} since ${since.format(DAY)}, net ${rs(s.net)}" +
            (if (s.trades > 0) " (${rs(s.net / s.trades)} a trade)" else "")

    /** The arm a promotion re-arms: the retired arm's label (ORB for the shared one), or the candidate's own. */
    fun armName(v: Variant): String = if (v.arms.isEmpty()) v.label else v.arms.first().label

    /** Jarvis's one request when [promotionDue]: re-arm the arm with the variant, on paper only. */
    fun ask(v: Variant, s: Summary): String {
        val f = s.profitFactor
        val pf = if (f == null) "no losing trade" else String.format(Locale.ENGLISH, "%.2f", f)
        return "Boss, the ${v.name} shadow of ${v.label} has ${s.trades} closed trades, net ${rs(s.net)} after charges, profit factor $pf " +
            "(the bar: $PROMOTE_TRADES trades, net above zero, ${PROMOTE_PF}). Re-arm ${armName(v)} with ${v.name} on paper? Paper only, " +
            "never Zerodha; nothing changes unless you say yes."
    }

    /** The title of that request: "re-arm ORB with V43 on paper?". */
    fun askTitle(v: Variant): String = "re-arm ${armName(v)} with ${v.name} on paper?"

    /**
     * What Jarvis says of every shadow: each one's line, which is ready to put to Boss, and why its losers lost ([reasons]:
     * variant id -> each losing trade's group, [ShadowStudy.lossGroup]; the most common one is named).
     */
    fun answer(rows: List<Triple<Variant, Summary, LocalDate>>, promoted: Set<String> = emptySet(),
               reasons: Map<String, List<ShadowStudy.LossGroup>> = emptyMap()): String {
        if (rows.isEmpty()) return "No shadow has run yet, Boss: they start recording on the next market day."
        val parts = rows.map { (v, s, since) ->
            "${v.label}: ${line(v, s, since)}" + when {
                v.id in promoted -> " - re-armed on paper with ${v.name} on your yes."
                promotionDue(s) -> " - it has met the bar; I'll ask you about re-arming it on paper."
                else -> "."
            } + (ShadowStudy.reasonLine(reasons[v.id].orEmpty())?.let { " Its losers: $it." } ?: "")
        }
        return "The shadows place no orders, Boss - they record what each rule would have done, at live prices and real charges. " +
            parts.joinToString(" ") + " A shadow is put to you only at $PROMOTE_TRADES trades, net above zero and a profit factor of " +
            "$PROMOTE_PF or more."
    }
}
