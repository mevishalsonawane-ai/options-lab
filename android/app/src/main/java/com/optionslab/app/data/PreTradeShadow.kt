package com.optionslab.app.data

import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Arm
import com.optionslab.engine.orb.Bar
import com.optionslab.ira.ChargesCheck
import com.optionslab.ira.DangerMeter
import com.optionslab.ira.OptionDelta
import com.optionslab.ira.PreTradeNote
import com.optionslab.ira.RangeModel
import com.optionslab.ira.SmartStop
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The pre-trade checks in SHADOW (TODO I3 / I5, research/HUNT_R17_PRETRADE.md, 13 Oct 2026): at each paper entry of ORB,
 * ORB Fresh, ORB Sweep, Range Fade, Hero and VIX divergence, "costs take X% of the expected move" (and whether the 10% line
 * would have skipped it) and "smart stop would be N points; fixed stop is M" are noted - after the order, from what the
 * entry already had in hand. Both checks failed their holdout test, so nothing here skips, resizes or moves anything.
 *
 * Memory only: no disk, no settings vault, no network, no blocking, so the arms may call it beside an entry. Liquidity
 * books are never noted (Liquidity BANKNIFTY is frozen until 26 Oct; the others share its rules).
 * India VIX's previous close comes in off the order path ([vixFrom], from Jarvis's market refresh and the morning check).
 */
object PreTradeShadow {
    @Volatile private var vixPrev: Double? = null
    @Volatile private var vixFor: LocalDate? = null
    @Volatile private var orb: List<Bar> = emptyList()
    private val notes = ArrayList<Pair<LocalDate, PreTradeNote>>()
    private const val MAX = 300

    /** VIX's previous close for [today] from a VIX snapshot: before today's first bar its last price, after it its prevClose. */
    fun vixFrom(price: Double?, prevClose: Double?, at: LocalDateTime?, today: LocalDate) {
        val v = if (at != null && at.toLocalDate() == today) prevClose else price
        if (v != null && v > 0) { vixPrev = v; vixFor = today }
    }

    private fun vix(day: LocalDate): Double? = vixPrev.takeIf { vixFor == day }

    /** The ORB family's 5-minute bars of today (memory only; the cycle hands them over before it decides). */
    fun orbBars(bars: List<Bar>) { orb = bars }

    private fun minutesTo(expiry: LocalDate, at: LocalDateTime): Double =
        Duration.between(at, expiry.atTime(15, 30)).toMinutes().toDouble()

    private fun add(day: LocalDate, n: PreTradeNote) = synchronized(notes) {
        notes.removeAll { it.first.isBefore(day.minusDays(7)) }
        notes += day to n
        while (notes.size > MAX) notes.removeAt(0)
    }

    private fun note(arm: String, label: String, underlying: String, c: Paper.Contract, premium: Double, qty: Int, at: LocalDateTime,
                     w: RangeModel.Window?, fixedStop: Double?) {
        val day = at.toLocalDate()
        val v = vix(day)
        val e30 = w?.let { RangeModel.points(underlying, it, v) }
        val delta = w?.let { OptionDelta.of(it.price, c.strike, minutesTo(c.expiry, at), premium, v, c.right.name == "CE") }
        add(day, PreTradeNote(arm, label, at.hour * 60 + at.minute, ChargesCheck.read(premium, qty, e30, delta), SmartStop.points(e30, delta), fixedStop))
    }

    /** ORB, ORB Fresh, ORB Sweep, Range Fade: [premium] the price the entry expected, [signalBar] its 5-minute bar's start. */
    fun orb(arm: Arm, c: Paper.Contract, premium: Double, qty: Int, signalBar: LocalDateTime, stopPoints: Double) {
        if (arm.liquidity || arm.hero) return
        val bars = orb.filter { !it.start.isAfter(signalBar) }
        val decided = signalBar.plusMinutes(5)
        val w = bars.lastOrNull()?.let { RangeModel.window(bars.map { b -> b.high to b.low }, 5, it.close, decided.hour * 60 + decided.minute) }
        note(arm.source, arm.label, c.underlying, c, premium, qty, decided, w, stopPoints)
    }

    /** Hero: NIFTY's 1-minute closes by the minute they closed ([spot]), the signal minute [at]; its stop is 60% of the fill. */
    fun hero(arm: Arm, spot: Map<LocalTime, Double>, c: Paper.Contract, fill: Double, qty: Int, day: LocalDate, at: LocalTime) {
        val closes = spot.filterKeys { !it.isAfter(at) }.toSortedMap().values.toList()
        val w = closes.lastOrNull()?.let { RangeModel.window(closes.map { x -> x to x }, 1, it, at.hour * 60 + at.minute) }
        note(arm.source, arm.label, c.underlying, c, fill, qty, day.atTime(at), w, fill * 0.6)
    }

    /** VIX divergence: today's index minutes, the check minute, and NIFTY's 15% stop in points (BANKNIFTY: none). */
    fun vixDiv(source: String, label: String, index: List<Upstox.Bar>, c: Paper.Contract, fill: Double, qty: Int, at: LocalDateTime, stopPoints: Double?) {
        val m = at.hour * 60 + at.minute
        val bars = index.filter { it.istMinute < m }.sortedBy { it.epochSecond }
        val w = bars.lastOrNull()?.let { RangeModel.window(bars.map { b -> b.high to b.low }, 1, it.close, m) }
        note(source, label, c.underlying, c, fill, qty, at, w, stopPoints)
    }

    fun day(day: LocalDate): List<PreTradeNote> = synchronized(notes) { notes.filter { it.first == day }.map { it.second } }

    /** The arm's day detail: one line per paper entry today. */
    fun lines(arm: String, day: LocalDate): List<String> = day(day).filter { it.arm == arm }.map { it.line() }

    /** Order Review's day notes: the summary, then each entry. */
    fun reviewNotes(day: LocalDate): List<String> = day(day).let { n -> PreTradeNote.summary(n) + n.map { it.line() } }
}

/**
 * Today's danger meter (TODO I4; [DangerMeter]): read by the morning check, said there as advice with its sample size. The
 * one thing it may do is halve a PAPER arm's lots on a careful or paper-only day, and only when Boss switches that on
 * (Settings → Risk, off by default). Memory only on the entry path: the switch's copy is set off it ([halfSize]).
 */
object DangerToday {
    @Volatile private var reading: DangerMeter.Reading? = null
    @Volatile private var readFor: LocalDate? = null
    /** The switch "half size on careful days (paper arms)", copied here from the settings off the order path. */
    @Volatile var halfSize: Boolean = false

    fun read(i: DangerMeter.Inputs, day: LocalDate): DangerMeter.Reading = DangerMeter.read(i).also { reading = it; readFor = day }

    fun today(day: LocalDate): DangerMeter.Reading? = reading.takeIf { readFor == day }

    /** A paper arm's lots for [day]: [planned], or half of it under the switch on a careful day. Never more than [planned]. */
    fun paperLots(planned: Int, day: LocalDate): Int = DangerMeter.lots(planned, today(day)?.level, halfSize)
}
