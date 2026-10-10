package com.optionslab.engine.mcx

import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ln

/**
 * "US-night silver (not proven, futures)": research/HUNT_R2_US_MCX.md paper-arm candidate 1 (rule C2 "follow US overnight",
 * SILVER, 09:05 -> close; research/hunt/r2/hourly.py us_on / inr_on and cand.py), for a PAPER-ONLY arm (Boss's yes, 9 Oct
 * 2026). It passed its holdout (166 trades, +Rs 1,189 a trade) but only in silver's 2025-26 tripling year; the 09:05 minute
 * check in design lost. Not proven: 1 lot of SILVERMIC, long AND short, never cleared for Zerodha.
 *
 *  - Signal at 09:05 IST ([signal]): s = ln(COMEX silver front month at 08:30 IST / the same at the previous MCX close)
 *    + ln(USD/INR at 08:30 / USD/INR at the previous MCX close). The previous close is the previous MCX trading day's
 *    session end on MCX's calendar ([prevClose]: 23:30 IST in US summer time, 23:55 in US winter time). Each price is the
 *    open of the bar starting at that time, else the close of the last bar before it within two hours ([priceAt]: hourly.py
 *    px_at). Either price missing: no trade that day.
 *  - Entry: s > 0 buys 1 lot of the near-month SILVERMIC, s < 0 sells 1 lot, from 09:05 for [ENTRY_GRACE] minutes ([entryWindow]).
 *    Skipped the day the near contract changed overnight ([rolled]: cand.py skips a segment change), and while MCX's
 *    expiry rule refuses a new delivery future ([McxExpiry.entryRefusal]).
 *  - Exit: 10 minutes before MCX's close that day ([exitMinute]: 23:20 / 23:45, or 10 minutes before an earlier end). A
 *    DISASTER STOP 3% against the fill on the future's 1-minute high or low ([DISASTER_STOP]) is NOT part of the research
 *    (the research suggested one for risk and reported it apart; it is labelled so wherever it shows).
 *
 * Research deviation, said plainly: hourly.py read the US price at 18:00 UTC (23:30 IST) all year; the arm reads it at the
 * real MCX close (23:55 IST in US winter), as Boss's rule says. Pure: no clock, no network, no orders.
 */
object McxUsSilverRules {
    const val SOURCE = "mcx_us_silver"
    const val LABEL = "US-night silver (not proven, futures)"
    const val UNDERLYING = "SILVERMIC"
    /** COMEX silver's front month and USD/INR on Yahoo's chart feed. */
    const val US_SYMBOL = "SI=F"
    const val FX_SYMBOL = "INR=X"

    /** The US reading's time (IST minutes): 08:30, before MCX opens. */
    const val REF_MINUTE = 8 * 60 + 30
    /** The entry (IST minutes): 09:05, and [ENTRY_GRACE] - 1 minutes more if the watch was a moment late. */
    const val ENTRY = 9 * 60 + 5
    const val ENTRY_GRACE = 5
    /** Out this many minutes before MCX's close. */
    const val BEFORE_CLOSE_MIN = 10
    /** Not part of the research: 3% against the fill on the future's minute high / low. */
    const val DISASTER_STOP = 0.03
    /** A reference price may be the close of a bar at most this old (hourly.py px_at: 2 hours). */
    const val MAX_AGE_SEC = 2 * 3600L

    const val RECORD = "Research: holdout 166 trades, +₹1,189/trade (+₹1,115/day), won 56%, worst drawdown ₹29.6k, 10 of 13 months green — " +
        "but in silver's 2025-26 tripling year; older check lost; futures (sells too); margin ~₹25–35k"
    const val RULES = "09:05: COMEX silver and USD/INR from the last MCX close to 08:30 up buys 1 lot of SILVERMIC (near month), down sells 1 lot · " +
        "out 10 minutes before MCX's close · skipped the day after a roll and near expiry · disaster stop 3% on the minute's high or low (not part of the research)"
    const val STOP_NOTE = "not part of the research"

    /** The previous MCX trading day's session end before [today] (IST), on [cal]; null when none is found. */
    fun prevClose(today: LocalDate, cal: McxSession.Calendar): LocalDateTime? {
        val d = cal.tradingDaysBefore(today, 1)
        val w = cal.window(d) ?: return null
        return d.atTime(w.close)
    }

    /** 08:30 IST on [today]. */
    fun refAt(today: LocalDate): LocalDateTime = today.atTime(REF_MINUTE / 60, REF_MINUTE % 60)

    /** [t] (IST) as epoch seconds. */
    fun epoch(t: LocalDateTime): Long = t.atZone(IST).toEpochSecond()

    /**
     * The price at [atSec] from [bars] (epoch-second starts, any order): the open of the bar starting exactly then, else the
     * close of the last bar starting before it, at most [MAX_AGE_SEC] earlier; null without one (hourly.py px_at).
     */
    fun priceAt(bars: List<Upstox.Bar>, atSec: Long): Double? {
        bars.firstOrNull { it.epochSecond == atSec }?.open?.takeIf { it > 0 && it.isFinite() }?.let { return it }
        val before = bars.filter { it.epochSecond < atSec }.maxByOrNull { it.epochSecond } ?: return null
        if (atSec - before.epochSecond > MAX_AGE_SEC) return null
        return before.close.takeIf { it > 0 && it.isFinite() }
    }

    /** s = ln(silver now / then) + ln(USD/INR now / then); null when any price is missing or not positive. */
    fun signal(silverNow: Double?, silverPrev: Double?, fxNow: Double?, fxPrev: Double?): Double? {
        val p = listOf(silverNow, silverPrev, fxNow, fxPrev)
        if (p.any { it == null || !it.isFinite() || it <= 0 }) return null
        return ln(silverNow!! / silverPrev!!) + ln(fxNow!! / fxPrev!!)
    }

    /** +1 buy, -1 sell, 0 nothing (s exactly 0). */
    fun side(s: Double): Int = when { s > 0 -> 1; s < 0 -> -1; else -> 0 }

    /** Whether [nowMinute] may still enter: 09:05 and the next [ENTRY_GRACE] - 1 minutes. */
    fun entryWindow(nowMinute: Int): Boolean = nowMinute >= ENTRY && nowMinute < ENTRY + ENTRY_GRACE

    /** True when the near contract is not the one seen on an earlier day ([previousNear]; null: not known, not rolled). */
    fun rolled(previousNear: LocalDate?, near: LocalDate): Boolean = previousNear != null && previousNear != near

    /** The minute it goes out: 10 minutes before the day's close ([window]: MCX's session; null: the ordinary close). */
    fun exitMinute(day: LocalDate, window: McxSession.Window?): Int {
        val close = window?.close ?: McxSession.close(day)
        return close.hour * 60 + close.minute - BEFORE_CLOSE_MIN
    }

    /** The disaster stop's level: 3% against [fill] for [side]. */
    fun disasterLevel(fill: Double, side: Int): Double = fill * (1 - side * DISASTER_STOP)

    /** One finished minute of the future: its label, high and low (the wicks). */
    data class Minute(val minute: Int, val high: Double, val low: Double)

    /**
     * The exit due now, or null to hold: "close_10_min" when [nowDay] is past [entryDay] (held over by a closed app) or
     * from [exitMinute]; "disaster_stop" when a finished minute from the entry ([bars]) reached the stop on its low (a long)
     * or high (a short). The stop is checked first.
     */
    fun exit(side: Int, fill: Double, entryDay: LocalDate, entryMinute: Int, bars: List<Minute>, nowDay: LocalDate, nowMinute: Int, exitMinute: Int): String? {
        if (nowDay.isAfter(entryDay)) return "close_10_min"
        val level = disasterLevel(fill, side)
        val hit = bars.any { it.minute >= entryMinute && it.minute < nowMinute && (if (side > 0) it.low <= level else it.high >= level) }
        if (hit) return "disaster_stop"
        return if (nowMinute >= exitMinute) "close_10_min" else null
    }
}
