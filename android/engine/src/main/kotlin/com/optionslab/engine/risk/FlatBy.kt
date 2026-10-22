package com.optionslab.engine.risk

import com.optionslab.engine.NseHours
import com.optionslab.engine.mcx.McxSession
import java.time.LocalDate

/**
 * The hard flat-by (22 Oct 2026, borrowed from Jake Schincariol's day-trader skill, MIT: nothing is held past 15:55 there):
 * the minute of the day by which every open INTRADAY arm position in a segment is closed, after the arm's own exit has had
 * its chance - a last resort, never the first exit. Per segment:
 *
 *  - NSE index options and futures: 15:25 (five minutes before the index closes; the arms' own 15:05 .. 15:15 exits go first,
 *    and on an expiry day the 15:05 square-off still comes first);
 *  - NSE stock or fund options and futures: 15:20;
 *  - MCX: ten minutes before that day's close (MCX's own MIS square-off minute, 23:20 in US summer time, 23:45 in US winter;
 *    an evening-only session keeps the same close).
 *
 * A position designed to be held (NRML by design: Night (R3)'s overnight option, the Expiry Put ticket, the MCX arms' NRML
 * futures, a strategy defined as positional) is not intraday and is left alone; so is anything that is not an arm's.
 * Pure: no clock, no I/O.
 */
object FlatBy {
    enum class Segment(val label: String) { NSE_INDEX("NSE index options"), NSE_STOCK("NSE stock and fund options"), MCX("MCX") }

    /** NSE index options and futures: 15:25. */
    const val NSE_INDEX_MINUTE = 15 * 60 + 25
    /** NSE stock and fund options and futures: 15:20. */
    const val NSE_STOCK_MINUTE = 15 * 60 + 20

    /** The index underlyings whose options the NSE index minute covers (SENSEX trades on BFO with the same hours). */
    val INDEX_UNDERLYINGS: Set<String> = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50", "SENSEX", "BANKEX")

    /** The segment of a contract from its exchange and underlying. */
    fun segment(exchange: String, underlying: String): Segment = when {
        exchange.equals("MCX", ignoreCase = true) -> Segment.MCX
        underlying.trim().uppercase() in INDEX_UNDERLYINGS -> Segment.NSE_INDEX
        else -> Segment.NSE_STOCK
    }

    /** The flat-by minute of [segment] on [day]. */
    fun minute(segment: Segment, day: LocalDate): Int = when (segment) {
        Segment.NSE_INDEX -> NSE_INDEX_MINUTE
        Segment.NSE_STOCK -> NSE_STOCK_MINUTE
        Segment.MCX -> McxSession.misCut(day).let { it.hour * 60 + it.minute }
    }

    /** The last minute (exclusive) the flat-by may still send an exit on [day]: the segment's close. */
    fun until(segment: Segment, day: LocalDate): Int = when (segment) {
        Segment.NSE_INDEX, Segment.NSE_STOCK -> NseHours.foClose(day)
        Segment.MCX -> McxSession.close(day).let { it.hour * 60 + it.minute }
    }

    /** Whether the flat-by of [segment] is due at [minuteNow] on [day]: from its minute until the segment's close. */
    fun due(segment: Segment, day: LocalDate, minuteNow: Int): Boolean = minuteNow >= minute(segment, day) && minuteNow < until(segment, day)

    /** Intraday: a MIS position; NRML is held by design. */
    fun intraday(product: String): Boolean = product.equals("MIS", ignoreCase = true)

    /** "15:25". */
    fun text(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    /** The Automation card's line: every segment's minute on [day]. */
    fun line(day: LocalDate): String =
        "Flat-by: index options ${text(NSE_INDEX_MINUTE)} · stock and fund ${text(NSE_STOCK_MINUTE)} · MCX ${text(minute(Segment.MCX, day))} " +
            "(10 min before its close) · expiry day ${text(NseHours.EXPIRY_SQUARE_OFF)} first. Closes any intraday arm position still open; " +
            "positions held by design are left alone."
}
