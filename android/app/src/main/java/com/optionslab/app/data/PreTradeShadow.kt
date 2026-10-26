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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pre-trade checks in SHADOW (TODO I3 / I5, research/HUNT_R17_PRETRADE.md, 13 Oct 2026): at each paper entry of ORB,
 * ORB Fresh, ORB Sweep, Range Fade, Hero and VIX divergence, "costs take X% of the expected move" (and whether the 10% line
 * would have skipped it) and "smart stop would be N points; fixed stop is M" are noted - after the order, from what the
 * entry already had in hand. Both checks failed their holdout test, so nothing here skips, resizes or moves anything.
 *
 * Memory only: no disk, no settings vault, no network, no blocking, so the arms may call it beside an entry. Liquidity
 * books are never noted (Liquidity BANKNIFTY is frozen until 26 Oct; the others share its rules).
 * India VIX's previous close comes in off the order path ([vixFrom], from Jarvis's market refresh).
 */
object PreTradeShadow {
    /** India VIX's previous close for one day. One object behind one volatile, so a day's rollover never pairs them wrongly. */
    private class VixDay(val day: LocalDate, val prev: Double)
    @Volatile private var vixDay: VixDay? = null
    @Volatile private var orb: List<Bar> = emptyList()
    private val notes = ArrayList<Pair<LocalDate, PreTradeNote>>()

    /**
     * VIX's previous close for [today] from a VIX snapshot whose last bar is at [at]: that bar today, its prevClose; on the
     * previous trading session, its price. A snapshot from any older day (the phone did not read for days, a holiday gap)
     * is not used, so the move stays "not known" (review, 13 Oct; [DangerMeter.vix]). Off the order path (it reads the calendar).
     */
    fun vixFrom(price: Double?, prevClose: Double?, at: LocalDateTime?, today: LocalDate,
                isTradingDay: (LocalDate) -> Boolean = { Market.isTradingDay(it) }) {
        if (at == null) return
        val prevSession = DangerMeter.previousSession(today, isTradingDay)
        val v = DangerMeter.vix(DangerMeter.Quote(at, price ?: Double.NaN, prevClose, Double.NaN), today, prevSession).prev ?: return
        vixDay = VixDay(today, v)
    }

    private fun vix(day: LocalDate): Double? = vixDay?.takeIf { it.day == day }?.prev

    /** The ORB family's 5-minute bars of today (memory only; the cycle hands them over before it decides). */
    fun orbBars(bars: List<Bar>) { orb = bars }

    private fun add(day: LocalDate, n: PreTradeNote) = synchronized(notes) {
        val kept = PreTradeNote.prune(notes + (day to n), day)
        notes.clear(); notes.addAll(kept)
    }

    private fun note(arm: String, label: String, underlying: String, c: Paper.Contract, premium: Double, qty: Int, at: LocalDateTime,
                     w: RangeModel.Window?, fixedStop: Double?) {
        val day = at.toLocalDate()
        val v = vix(day)
        val e30 = w?.let { RangeModel.points(underlying, it, v) }
        val delta = w?.let { OptionDelta.of(it.price, c.strike, OptionDelta.minutesTo(c.expiry, at), premium, v, c.right.name == "CE") }
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

    /**
     * Hero: NIFTY's 1-minute bars keyed by the minute they closed ([spot], as the arm read them), the signal minute [at];
     * its stop is 60% of the fill. The window reads each bar's high and low, as the research does (a bar without a usable
     * high / low counts its close: [RangeModel.highLow]).
     */
    fun hero(arm: Arm, spot: Map<LocalTime, Upstox.Bar>, c: Paper.Contract, fill: Double, qty: Int, day: LocalDate, at: LocalTime) {
        val bars = spot.filterKeys { !it.isAfter(at) }.toSortedMap().values.toList()
        val w = bars.lastOrNull()?.let { RangeModel.window(bars.map { b -> RangeModel.highLow(b.high, b.low, b.close) }, 1, it.close, at.hour * 60 + at.minute) }
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
 * Today's danger meter (TODO I4; [DangerMeter]): read by the 09:00 morning check (before the open: provisional, the gap not
 * known yet) and again at 09:16 once BANKNIFTY's open is in ([DailyReports]'s DANGER run), said as advice with its sample
 * size. The one thing it may do is halve a PAPER arm's lots on a careful or paper-only day, and only when Boss switches that
 * on (Settings → Risk, off by default). Memory only on the entry path: the switch's copy ([halfSize]) is loaded at the app's
 * start and kept in step by every settings save and by a backup's restore ([AppSettings.save], [Backup.restore]).
 */
object DangerToday {
    /** One day's reading with the inputs it came from: one immutable object, so a rollover never mixes two days. */
    class Today(val day: LocalDate, val inputs: DangerMeter.Inputs, val reading: DangerMeter.Reading)

    private val held = MutableStateFlow<Today?>(null)
    /** The latest reading, for the Pre-trade checks card (it redraws when the 09:16 read replaces the 09:00 one). */
    val state: StateFlow<Today?> = held.asStateFlow()

    /** The switch "half size on careful days (paper arms)", copied here from the settings off the order path. */
    @Volatile var halfSize: Boolean = false

    fun read(i: DangerMeter.Inputs, day: LocalDate): DangerMeter.Reading = DangerMeter.read(i).also { held.value = Today(day, i, it) }

    fun today(day: LocalDate): DangerMeter.Reading? = held.value?.takeIf { it.day == day }?.reading

    /** Today's inputs from an earlier read (what a later read cannot see any more is kept from it), or null. */
    fun inputs(day: LocalDate): DangerMeter.Inputs? = held.value?.takeIf { it.day == day }?.inputs

    /**
     * Reads [today]'s meter from Jarvis's market snapshots: BANKNIFTY's gap once its first bar of today is in, VIX only from a
     * fresh snapshot ([DangerMeter.inputs]). Reads the calendar, the event days and the contract list: never on the order
     * path or the main thread (the morning check's and the 09:16 run's workers).
     */
    fun readFrom(snaps: Map<com.optionslab.ira.Market, com.optionslab.ira.Snapshot>, today: LocalDate): DangerMeter.Reading {
        fun q(s: com.optionslab.ira.Snapshot?) = s?.let { DangerMeter.Quote(it.at, it.price, it.prevClose, it.open) }
        val prevSession = DangerMeter.previousSession(today) { Market.isTradingDay(it) }
        val event = runCatching { EventDays.isEventDay(today) }.getOrDefault(false)
        val expiry = listOf("NIFTY", "BANKNIFTY").any { u -> runCatching { Market.isExpiryDay(u, today) }.getOrDefault(false) }
        val i = DangerMeter.inputs(today, prevSession, q(snaps[com.optionslab.ira.Market.BANKNIFTY]), q(snaps[com.optionslab.ira.Market.VIX]),
            event, expiry, inputs(today))
        return read(i, today)
    }

    /**
     * 26 Oct ("Erase everything", [PersonalStores]): today's reading and the switch's copy forgotten (memory only). The switch
     * goes back to its default, off, as the wiped settings read; the next settings save or start copies it again. Never reads
     * the vault or the settings. Paper only: the switch halves paper lots, and the wiped settings say off too.
     */
    fun wipe() { held.value = null; halfSize = false }

    /** 26 Oct: whether a reading or the switch's copy (on) is held in memory. */
    internal fun holdsForErase(): Boolean = held.value != null || halfSize

    /** A paper arm's lots for [day]: [planned], or half of it under the switch on a careful day. Never more than [planned]. */
    fun paperLots(planned: Int, day: LocalDate): Int = DangerMeter.lots(planned, today(day)?.level, halfSize)
}
