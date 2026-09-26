package com.optionslab.engine.strategy

import com.optionslab.engine.Right
import com.optionslab.engine.Session
import com.optionslab.engine.Series
import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

/**
 * Ready-made option sellers' baskets - short straddle, short strangle, iron fly,
 * iron condor - as Strategy Module definitions, and a replay of each over the
 * harvested one-minute sessions so the owner sees how it would have done before
 * arming it.
 *
 * The replay is deliberately simple and says so: enter every leg at the entry
 * minute's close, mark the basket to market each minute, leave on the basket
 * stop, the basket target or the exit time, pay the paper account's charges on
 * every fill. No slippage beyond that, no per-leg stops.
 */
object Presets {
    /** One leg: [otm] listed strikes out of the money for its side (0 = ATM). */
    data class Leg(val position: Position, val type: OptionType, val otm: Int)

    data class Preset(val id: String, val name: String, val blurb: String, val legs: List<Leg>)

    private fun s(t: OptionType, otm: Int) = Leg(Position.S, t, otm)
    private fun b(t: OptionType, otm: Int) = Leg(Position.B, t, otm)

    val ALL = listOf(
        Preset("short_straddle", "Short straddle", "Sell the ATM call and put. Earns the most decay, loses the most on a trend.",
            listOf(s(OptionType.CE, 0), s(OptionType.PE, 0))),
        Preset("short_strangle", "Short strangle", "Sell the call and put two strikes out. Wider room, less premium.",
            listOf(s(OptionType.CE, 2), s(OptionType.PE, 2))),
        Preset("iron_fly", "Iron fly", "Short straddle with wings three strikes out: the worst loss is capped.",
            listOf(s(OptionType.CE, 0), s(OptionType.PE, 0), b(OptionType.CE, 3), b(OptionType.PE, 3))),
        Preset("iron_condor", "Iron condor", "Short strangle two out with wings four out: capped both ways.",
            listOf(s(OptionType.CE, 2), s(OptionType.PE, 2), b(OptionType.CE, 4), b(OptionType.PE, 4))),
    )

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    /** NIFTY trades weekly expiries; the others only monthly. */
    private fun weekly(underlying: String) = underlying.uppercase() == "NIFTY"

    /**
     * The preset as a strategy, born sandbox-only with its scheduler off: the
     * owner arms it from the Strategies page like any other.
     */
    fun def(
        p: Preset, underlying: String, lots: Int, entry: LocalTime, exit: LocalTime,
        stopMtm: Double?, targetMtm: Double?,
    ): StrategyDef {
        val w = weekly(underlying)
        return StrategyDef(
            name = "${p.name} · ${underlying.uppercase()}",
            underlying = underlying.uppercase(), underlyingExchange = "NSE_INDEX",
            universeTab = if (w) UniverseTab.WEEKLY_MONTHLY else UniverseTab.MONTHLY_ONLY,
            strategyType = StrategyType.INTRADAY, entryTime = entry, exitTime = exit, product = Product.MIS,
            legs = p.legs.mapIndexed { i, l ->
                LegDef(i + 1, Segment.OPTIONS, l.position, lots, l.type, StrikeMode.ATM, if (l.otm == 0) "ATM" else "OTM${l.otm}",
                    expiry = if (w) "weekly" else "monthly")
            },
            overallSlMtm = stopMtm, overallTargetMtm = targetMtm,
            scheduler = SchedulerConfig(enabled = false, days = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                startTime = entry, autoStopTime = exit),
        )
    }

    // ---- the replay ---------------------------------------------------------------------------

    data class Day(
        val day: LocalDate, val spot: Double, val strikes: List<Double>, val credit: Double,
        val gross: Double, val charges: Double, val exitMinute: Int, val reason: String,
    ) {
        val net: Double get() = gross - charges
    }

    data class Result(val preset: Preset, val days: List<Day>, val skipped: Int) {
        val net: Double get() = days.sumOf { it.net }
        val wins: Int get() = days.count { it.net > 0 }
        val winRate: Double get() = if (days.isEmpty()) 0.0 else wins.toDouble() / days.size
        val average: Double get() = if (days.isEmpty()) 0.0 else net / days.size
        val best: Double get() = days.maxOfOrNull { it.net } ?: 0.0
        val worst: Double get() = days.minOfOrNull { it.net } ?: 0.0
        val maxDrawdown: Double get() {
            var peak = 0.0; var cum = 0.0; var dd = 0.0
            days.sortedBy { it.day }.forEach { cum += it.net; peak = maxOf(peak, cum); dd = minOf(dd, cum - peak) }
            return dd
        }
        val profitFactor: Double? get() {
            val loss = abs(days.filter { it.net < 0 }.sumOf { it.net })
            return if (loss == 0.0) null else days.filter { it.net > 0 }.sumOf { it.net } / loss
        }
    }

    /** A bar older than this at the entry minute means the contract was not trading: skip the day. */
    private const val STALE_MINUTES = 15

    private fun priceAt(s: Series, minute: Int): Double? {
        val i = s.lastAtOrBefore(minute)
        return if (i >= 0) s.close[i] else null
    }

    /**
     * Replay one session. Null when the day cannot be priced: no index, the
     * entry minute missing, a leg's strike off the listed ladder, or a leg not
     * trading at entry.
     */
    fun day(p: Preset, session: Session, lot: Int, lots: Int, entry: Int, exit: Int, stopMtm: Double?, targetMtm: Double?): Day? {
        val ix = session.index ?: return null
        val e = ix.lastAtOrBefore(entry)
        if (e < 0 || entry - ix.minutes[e] > STALE_MINUTES) return null
        val spot = ix.close[e]
        val expiry = session.options.mapNotNull { it.expiry }.filter { !it.isBefore(session.day) }.minOrNull() ?: return null
        val chain = session.options.filter { it.expiry == expiry }
        val ladder = chain.map { it.strike }.distinct().sorted()
        if (ladder.isEmpty()) return null
        val atm = ladder.indices.minByOrNull { abs(ladder[it] - spot) }!!
        val qty = lot * lots
        class Held(val sign: Int, val series: Series, val entry: Double)
        val held = p.legs.map { l ->
            val k = ladder.getOrNull(if (l.type == OptionType.CE) atm + l.otm else atm - l.otm) ?: return null
            val right = if (l.type == OptionType.CE) Right.CE else Right.PE
            val s = chain.firstOrNull { it.strike == k && it.right == right } ?: return null
            val i = s.lastAtOrBefore(entry)
            if (i < 0 || entry - s.minutes[i] > STALE_MINUTES || s.close[i] <= 0) return null
            Held(if (l.position == Position.B) 1 else -1, s, s.close[i])
        }
        fun mtm(minute: Int) = held.sumOf { h -> h.sign * ((priceAt(h.series, minute) ?: h.entry) - h.entry) * qty }
        var out = exit; var reason = "Time"
        for (m in ix.minutes) {
            if (m <= entry) continue
            if (m >= exit) break
            val v = mtm(m)
            if (stopMtm != null && v <= -stopMtm) { out = m; reason = "Stop"; break }
            if (targetMtm != null && v >= targetMtm) { out = m; reason = "Target"; break }
        }
        val gross = mtm(out)
        val charges = held.sumOf { h ->
            val close = priceAt(h.series, out) ?: h.entry
            val open = if (h.sign > 0) "BUY" else "SELL"; val shut = if (h.sign > 0) "SELL" else "BUY"
            SandboxCosts.charge(open, BigDecimal(h.entry), qty).toDouble() + SandboxCosts.charge(shut, BigDecimal(close), qty).toDouble()
        }
        val credit = -held.sumOf { it.sign * it.entry }
        return Day(session.day, spot, held.map { it.series.strike }, credit, gross, charges, out, reason)
    }

    /** Replay every session, one at a time (they are streamed, never held together). */
    fun backtest(
        p: Preset, sessions: Sequence<Session>, lotOn: (Session) -> Int?, lots: Int, entry: Int, exit: Int,
        stopMtm: Double?, targetMtm: Double?, progress: (Int) -> Unit = {},
    ): Result {
        val days = ArrayList<Day>(); var skipped = 0; var n = 0
        for (s in sessions) {
            progress(++n)
            val lot = lotOn(s)
            val d = if (lot == null || lot <= 0) null else day(p, s, lot, lots, entry, exit, stopMtm, targetMtm)
            if (d == null) skipped++ else days += d
        }
        return Result(p, days, skipped)
    }
}
