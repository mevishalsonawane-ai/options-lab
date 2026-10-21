package com.optionslab.engine.signals

import com.optionslab.engine.NseHours
import com.optionslab.engine.Right
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.Watchlist
import com.optionslab.engine.signals.CallParser.Trig
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The Signal tester's SHADOW evaluation of one call (15 Oct 2026): what following it would have done, from the option's and the
 * underlying's own 1-minute bars (bar start times), with the app's paper cost model - never an order, paper or live.
 *
 * Before the call: the option's last price at the message (the last whole minute before it), its move and the underlying's
 * move in the 3 minutes before (in the call's direction: a CE call after the index rose), and whether the premium was already
 * above the entry when the message came ([Pre]; "moved before the call" is either - the pump-before-tip check).
 *
 * The fill: a reaction [Settings.delaySec] after the message (0 / 30 / 60 s), from the next whole minute on, within
 * [Settings.validityMin] minutes of the message: "above X" fills when the premium trades at or above X (at X, or the minute's
 * open when it opened above); "near / @ / range" when it trades within [ZONE] of the level (or the range); "cmp" at the next
 * minute's open. The fill price is moved by the app's paper slippage ([Watchlist.paperBuy]). Never triggered: "not triggered".
 *
 * The exits, each a separate variant of the same fill ([Variant]): T1, T2, T3 (out at that target), "as given" (the last
 * target and the message's own stop; no stop when none was given) and "with a stop" (the given stop, else [OUR_STOP] below the
 * entry, labelled "our stop"). A stop is checked before a target in the same minute (the worse case first); a target fills at
 * its price (a resting sell), a stop or the square-off at the minute's price less the paper slippage. Square-off at [SQ]
 * (15:15), or 15:05 on that index's expiry day ([NseHours.EXPIRY_SQUARE_OFF], CLAUDE.md). One lot, the real charges.
 * A market holiday, a weekend, or a message after the square-off: not tested. Prices missing or not yet in: waiting, then "no
 * data". Pure, and re-run from the bars each time, so a restart mid-call loses nothing.
 */
object CallEval {
    val SQ: LocalTime = LocalTime.of(15, 15)
    val OPEN: LocalTime = LocalTime.of(9, 15)
    /** Our stop when the message gives none: 20% below the entry. */
    const val OUR_STOP = 0.20
    /** "Near X": within 2% of X. */
    const val ZONE = 0.02
    /** The underlying's move before the call that counts as "moved before" (0.10%), or the option's (5%). */
    const val PUMP_UNDERLYING_PCT = 0.10
    const val PUMP_OPTION_PCT = 5.0
    /** Minutes without a bar inside a trade that count as a data gap. */
    const val GAP_MIN = 5

    data class Settings(val delaySec: Int = 30, val validityMin: Int = 15) {
        companion object { val DELAYS = listOf(0, 30, 60) }
    }

    enum class Variant(val key: String, val label: String) {
        T1("t1", "out at T1"), T2("t2", "out at T2"), T3("t3", "out at T3"), GIVEN("given", "as given"), STOPPED("stop", "with a stop");
        companion object { fun of(k: String): Variant? = entries.firstOrNull { it.key == k } }
    }

    /** One call to evaluate: the message's time (to the second), the option's side, entry, stop and targets, one lot. */
    data class Setup(val at: LocalDateTime, val underlying: String, val right: Right, val trig: Trig, val trigger: Double?, val triggerHigh: Double?,
                     val stop: Double?, val targets: List<Double>, val lot: Int, val expiryDay: Boolean, val tradingDay: Boolean = true)

    data class Pre(val ltp: Double?, val optionMove3: Double?, val underlyingMove3: Double?, val alreadyAbove: Boolean) {
        val movedBefore: Boolean get() = alreadyAbove || (underlyingMove3 ?: 0.0) >= PUMP_UNDERLYING_PCT || (optionMove3 ?: 0.0) >= PUMP_OPTION_PCT
    }

    /** One variant's would-be trade: [why] "target", "stop", "our stop", "square-off" or "last price". */
    data class Leg(val variant: Variant, val entryAt: LocalDateTime, val entryRaw: Double, val entryFill: Double, val exitAt: LocalDateTime,
                   val exitRaw: Double, val exitFill: Double, val why: String, val qty: Int, val charges: Double) {
        val net: Double get() = (exitFill - entryFill) * qty - charges
    }

    enum class Status(val key: String, val words: String) {
        WAITING("waiting", "waiting for the entry"), NOT_TRIGGERED("not_triggered", "not triggered"), OPEN("open", "in the trade (shadow)"),
        DONE("done", "done"), NO_DATA("no_data", "no prices for it"), CLOSED("closed", "market closed");
        companion object { fun of(k: String?): Status? = entries.firstOrNull { it.key == k } }
    }

    data class Result(val status: Status, val pre: Pre?, val fillAt: LocalDateTime? = null, val fillRaw: Double? = null, val legs: Map<Variant, Leg> = emptyMap(),
                      val note: String? = null) {
        val triggered: Boolean get() = fillAt != null
    }

    /** The square-off of [s]'s day. */
    fun squareOff(s: Setup): LocalTime = if (s.expiryDay) LocalTime.of(NseHours.EXPIRY_SQUARE_OFF / 60, NseHours.EXPIRY_SQUARE_OFF % 60) else SQ

    private fun closeAtOrBefore(bars: List<Bar>, t: LocalDateTime): Double? = bars.lastOrNull { !it.start.plusMinutes(1).isAfter(t) }?.close

    /** [now]'s prices of the day are final (after F&O's close, or a later day). */
    private fun final(s: Setup, now: LocalDateTime): Boolean =
        now.toLocalDate().isAfter(s.at.toLocalDate()) || !now.toLocalTime().isBefore(NseHours.foCloseTime(s.at.toLocalDate()))

    /** Before the call ([Pre]), from [opt] and [idx] (the option's and the underlying's bars of the day). */
    fun pre(s: Setup, opt: List<Bar>, idx: List<Bar>): Pre {
        val ltp = closeAtOrBefore(opt, s.at)
        val o3 = closeAtOrBefore(opt, s.at.minusMinutes(3))
        val i0 = closeAtOrBefore(idx, s.at)
        val i3 = closeAtOrBefore(idx, s.at.minusMinutes(3))
        val sign = if (s.right == Right.CE) 1 else -1
        val om = if (ltp != null && o3 != null && o3 > 0) (ltp / o3 - 1) * 100 else null
        val um = if (i0 != null && i3 != null && i3 > 0) sign * (i0 / i3 - 1) * 100 else null
        val e = s.trigger
        val above = ltp != null && e != null && when (s.trig) {
            Trig.ABOVE -> ltp >= e
            Trig.ZONE -> ltp > (s.triggerHigh ?: e) * (1 + ZONE)
            Trig.CMP -> false
        }
        return Pre(ltp, om?.let { CallParser.round2(it) }, um?.let { Math.round(it * 1000) / 1000.0 }, above)
    }

    /** [s] evaluated from the day's option bars [opt] and underlying bars [idx] known at [now], with [set]. */
    fun evaluate(s: Setup, opt: List<Bar>, idx: List<Bar>, now: LocalDateTime, set: Settings = Settings()): Result {
        val day = s.at.toLocalDate()
        val sq = day.atTime(squareOff(s))
        if (!s.tradingDay) return Result(Status.CLOSED, null, note = "the market was closed that day")
        if (!s.at.isBefore(sq)) return Result(Status.CLOSED, null, note = "it came after the ${squareOff(s)} square-off")
        val bars = opt.filter { it.start.toLocalDate() == day && !it.start.plusMinutes(1).isAfter(now) }.sortedBy { it.start }.distinctBy { it.start }
        val ix = idx.filter { it.start.toLocalDate() == day }.sortedBy { it.start }
        val pre = pre(s, bars, ix)
        val from = maxOf(s.at, day.atTime(OPEN))
        val act = from.plusSeconds(set.delaySec.toLong())
        val windowEnd = minOf(from.plusMinutes(set.validityMin.toLong()), sq)
        val fin = final(s, now)
        val e = s.trigger
        if (e == null && s.trig != Trig.CMP) return Result(Status.NO_DATA, pre, note = "no entry price")
        var fill: Pair<Bar, Double>? = null
        for (b in bars) {
            if (b.start.isBefore(act) || !b.start.isBefore(windowEnd)) continue
            val raw: Double? = when (s.trig) {
                Trig.CMP -> b.open
                Trig.ABOVE -> when { b.open >= e!! -> b.open; b.high >= e -> e; else -> null }
                Trig.ZONE -> {
                    val lo = e!! * (1 - ZONE); val hi = (s.triggerHigh ?: e) * (1 + ZONE)
                    when { b.open in lo..hi -> b.open; b.open > hi && b.low <= hi -> hi; b.open < lo && b.high >= lo -> lo; else -> null }
                }
            }
            if (raw != null && raw > 0) { fill = b to raw; break }
        }
        if (fill == null) {
            val covered = bars.any { !it.start.isBefore(windowEnd.minusMinutes(1)) }
            return when {
                !now.isAfter(windowEnd) || (!covered && !fin) -> Result(Status.WAITING, pre)
                bars.none { !it.start.isBefore(act) && it.start.isBefore(windowEnd) } ->
                    Result(Status.NO_DATA, pre, note = "no option prices in the ${set.validityMin} minutes after it")
                else -> Result(Status.NOT_TRIGGERED, pre)
            }
        }
        val (fb, raw) = fill
        val entryFill = Watchlist.paperBuy(s.underlying, raw)
        val legs = LinkedHashMap<Variant, Leg>()
        var open = false
        var gap = 0
        for (v in Variant.entries) {
            val target = when (v) {
                Variant.T1 -> s.targets.getOrNull(0); Variant.T2 -> s.targets.getOrNull(1); Variant.T3 -> s.targets.getOrNull(2)
                Variant.GIVEN, Variant.STOPPED -> s.targets.lastOrNull()
            }
            if (v in setOf(Variant.T1, Variant.T2, Variant.T3) && target == null) continue
            val ours = v == Variant.STOPPED && s.stop == null
            val stop = if (ours) CallParser.round2(entryFill * (1 - OUR_STOP)) else s.stop
            val leg = exit(v, s, bars, fb, raw, entryFill, target, stop, ours, sq, fin)
            if (leg == null) open = true else { legs[v] = leg; gap = maxOf(gap, maxGap(bars, fb.start, leg.exitAt)) }
        }
        val note = if (gap > GAP_MIN) "prices missing for $gap minutes inside the trade" else null
        return Result(if (open) Status.OPEN else Status.DONE, pre, fb.start, raw, legs, note)
    }

    private fun exit(v: Variant, s: Setup, bars: List<Bar>, fb: Bar, raw: Double, entryFill: Double, target: Double?, stop: Double?, ours: Boolean,
                     sq: LocalDateTime, fin: Boolean): Leg? {
        fun leg(at: LocalDateTime, x: Double, why: String): Leg {
            val fillX = if (why == "target") x else Watchlist.paperSell(s.underlying, x)
            val ch = Watchlist.charge(true, entryFill, s.lot) + Watchlist.charge(false, fillX, s.lot)
            return Leg(v, fb.start, raw, entryFill, at, x, fillX, why, s.lot, CallParser.round2(ch))
        }
        for (b in bars) {
            if (b.start.isBefore(fb.start)) continue
            if (!b.start.isBefore(sq)) return leg(sq, b.open, "square-off")
            val first = b.start == fb.start
            if (stop != null && b.low <= stop) return leg(b.start, if (!first && b.open <= stop) b.open else stop, if (ours) "our stop" else "stop")
            if (!first && target != null && b.high >= target) return leg(b.start, if (b.open >= target) b.open else target, "target")
        }
        if (!fin) return null
        // The day is over and no bar at or after the square-off: the last price before it.
        val last = bars.lastOrNull { !it.start.isBefore(fb.start) } ?: fb
        return leg(last.start.plusMinutes(1), last.close, "last price")
    }

    private fun maxGap(bars: List<Bar>, from: LocalDateTime, to: LocalDateTime): Int {
        val inside = bars.filter { !it.start.isBefore(from) && !it.start.isAfter(to) }.map { it.start }
        return inside.zipWithNext().maxOfOrNull { (a, b) -> java.time.Duration.between(a, b).toMinutes().toInt() - 1 } ?: 0
    }

    // ---- random-entry twins (the channel's p) ----------------------------------------------------------------------------

    const val TWINS = 20
    val TWIN_FROM: LocalTime = LocalTime.of(9, 20)
    val TWIN_TO: LocalTime = LocalTime.of(14, 45)

    /**
     * Up to [TWINS] random-entry twins of a triggered call (the Watchlist's method, [Watchlist.twins]): the same day and contract,
     * bought at the open of a random minute 09:20-14:45 (before the square-off), its stop and last target at the same distances
     * from the entry as the call's "with a stop" variant, out by the same square-off. Seeded by [key], so a re-run gives the
     * same twins. Their nets (rupees after charges); empty when the call was not triggered.
     */
    fun twins(s: Setup, r: Result, opt: List<Bar>, key: String): List<Double> {
        val leg = r.legs[Variant.STOPPED] ?: return emptyList()
        val day = s.at.toLocalDate()
        val sq = day.atTime(squareOff(s))
        val bars = opt.filter { it.start.toLocalDate() == day }.sortedBy { it.start }.distinctBy { it.start }
        val stopFrac = (s.stop ?: (leg.entryFill * (1 - OUR_STOP))) / leg.entryRaw
        val tgtFrac = s.targets.lastOrNull()?.let { it / leg.entryRaw }
        val to = minOf(TWIN_TO, sq.toLocalTime().minusMinutes(30))
        val span = java.time.Duration.between(TWIN_FROM, to).toMinutes().toInt() + 1
        val rng = java.util.Random(key.hashCode().toLong())
        val out = ArrayList<Double>()
        repeat(TWINS) {
            val m = day.atTime(TWIN_FROM).plusMinutes(rng.nextInt(span).toLong())
            val b = bars.firstOrNull { !it.start.isBefore(m) && it.start.isBefore(m.plusMinutes(3)) && it.open > 0 } ?: return@repeat
            val ef = Watchlist.paperBuy(s.underlying, b.open)
            val tw = s.copy(trig = Trig.CMP, trigger = null, stop = CallParser.round2(b.open * stopFrac), targets = listOfNotNull(tgtFrac?.let { CallParser.round2(b.open * it) }))
            exit(Variant.STOPPED, tw, bars, b, b.open, ef, tw.targets.lastOrNull(), tw.stop, false, sq, true)?.let { out += it.net }
        }
        return out
    }
}
