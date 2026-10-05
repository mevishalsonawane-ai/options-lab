package com.optionslab.ira

import com.optionslab.engine.options.ChainSnapshot
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * The expiry-day companion (Jarvis self-improvement, 2026-10-05): on an index's expiry day, a few timed facts from its
 * expiring chain - the at-the-money straddle's premium and how much of it has gone since the first read, where spot
 * stands against max pain (and whether it is close enough that traders talk of a pin), the day's range as the last
 * hour starts, and how wide that last hour has been. Also "how is expiry going?", from the reads kept today. Facts from
 * the chain and the candles on the phone, every number from the data: never a forecast, never advice. Pure.
 */
object ExpiryDay {
    /** When the companion speaks (IST): each slot once a day, and only within [WINDOW] minutes of its time. */
    enum class Slot(val at: LocalTime) {
        OPEN(LocalTime.of(9, 30)), MIDDAY(LocalTime.of(12, 0)), AFTERNOON(LocalTime.of(13, 30)), LAST_HOUR(LocalTime.of(14, 30)), CLOSE(LocalTime.of(15, 20))
    }

    /** A slot is said up to this many minutes after its time (a later look skips it: an old fact is not news). */
    const val WINDOW = 10L
    /** Spot within this % of max pain: told as near it (where traders watch for a pin). */
    const val PIN_PCT = 0.3
    /** The last hour: from 14:30. */
    val LAST_HOUR: LocalTime = LocalTime.of(14, 30)

    /** One read of the expiring chain: spot, the at-the-money strike and its call and put premiums, max pain. */
    data class Read(val at: LocalDateTime, val spot: Double, val strike: Double, val ce: Double, val pe: Double, val maxPain: Double?) {
        val straddle: Double get() = ce + pe
    }

    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun k(x: Double) = if (x % 1.0 == 0.0) "%,.0f".format(Locale.ENGLISH, x) else "%,.1f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun norm(text: String) = " " + spacedWords(text.lowercase()) + " "

    /** The slot due at [now], or null: at or after its time and within [WINDOW] minutes. */
    fun due(now: LocalTime): Slot? = Slot.entries.lastOrNull { !now.isBefore(it.at) && now.isBefore(it.at.plusMinutes(WINDOW)) }

    /**
     * [c] read at [at]: null unless [c] is the chain expiring that day, and without an at-the-money strike priced on both
     * sides.
     */
    fun read(c: ChainSnapshot, at: LocalDateTime): Read? {
        if (c.expiry != at.toLocalDate() || !c.spot.isFinite() || c.spot <= 0) return null
        val atm = c.atm ?: return null
        val row = c.rows.firstOrNull { it.strike == atm } ?: return null
        val ce = row.ce?.ltp?.takeIf { it > 0 } ?: return null
        val pe = row.pe?.ltp?.takeIf { it > 0 } ?: return null
        return Read(at, c.spot, atm, ce, pe, c.maxPain?.maxPainStrike)
    }

    /** The straddle now, and against the first read of the day when there is an earlier one. */
    private fun straddle(m: Market, first: Read?, now: Read): String {
        val head = "the at-the-money straddle (${k(now.strike)}) is at ${n(now.straddle)} - call ${n(now.ce)}, put ${n(now.pe)}"
        if (first == null || !first.at.isBefore(now.at) || first.straddle <= 0) return "${m.label} expires today, Boss: $head."
        val ch = (now.straddle - first.straddle) / first.straddle * 100
        val word = if (ch <= 0) "down" else "up"
        val then = if (first.strike == now.strike) "" else " (then the ${k(first.strike)} strike)"
        return "${m.label} expiry, Boss, ${hm(now.at)}: $head; $word ${"%.0f".format(Locale.ENGLISH, abs(ch))}% from ${n(first.straddle)} at ${hm(first.at)}$then."
    }

    /** Spot against max pain, with how far it was at the first read. */
    private fun pain(first: Read?, now: Read): String? {
        val mp = now.maxPain ?: return null
        val d = now.spot - mp
        val where = if (d == 0.0) "right at" else "${pts(d)} points ${if (d > 0) "above" else "below"}"
        val near = abs(d) / now.spot * 100 <= PIN_PCT
        val was = first?.takeIf { it.at.isBefore(now.at) && it.maxPain != null }?.let { f ->
            val fd = f.spot - f.maxPain!!
            " At ${hm(f.at)} it was ${pts(fd)} points ${if (fd >= 0) "above" else "below"} max pain ${k(f.maxPain)}."
        } ?: ""
        return "Spot ${n(now.spot)} is $where max pain ${k(mp)}" +
            (if (near) " - within ${"%.1f".format(Locale.ENGLISH, PIN_PCT)}%, where traders watch for a pin." else ".") + was
    }

    /** Today's candles of [bars] (the day of [now]) up to [now]. */
    private fun today(bars: List<Candle>, now: LocalDateTime) = bars.filter { it.t.toLocalDate() == now.toLocalDate() && !it.t.isAfter(now) }

    /** The day's range so far, as the last hour starts. */
    private fun dayRange(today: List<Candle>): String? {
        if (today.isEmpty()) return null
        val hi = today.maxOf { it.h }; val lo = today.minOf { it.l }
        return "The last hour starts with the day's range at ${n(lo)} to ${n(hi)}, ${pts(hi - lo)} points."
    }

    /** The last hour's range so far, against the whole day's. */
    private fun lastHour(today: List<Candle>): String? {
        val last = today.filter { !it.t.toLocalTime().isBefore(LAST_HOUR) }
        if (last.isEmpty()) return null
        val hi = last.maxOf { it.h }; val lo = last.minOf { it.l }
        val day = today.maxOf { it.h } - today.minOf { it.l }
        val share = if (day > 0) ", ${"%.0f".format(Locale.ENGLISH, (hi - lo) / day * 100)}% of the day's ${pts(day)}-point range" else ""
        return "The last hour so far: ${n(lo)} to ${n(hi)}, ${pts(hi - lo)} points$share."
    }

    /**
     * What is said at [slot] for [m]: the straddle, spot against max pain, and at [Slot.LAST_HOUR] the day's range or at
     * [Slot.CLOSE] the last hour's. [first] is the day's first read (null or [now] itself when this is the first);
     * [bars] the index's 1-minute candles.
     */
    fun say(m: Market, slot: Slot, first: Read?, now: Read, bars: List<Candle>): String {
        val t = today(bars, now.at)
        return listOfNotNull(straddle(m, first, now), pain(first, now), when (slot) {
            Slot.LAST_HOUR -> dayRange(t)
            Slot.CLOSE -> lastHour(t)
            else -> null
        }).joinToString(" ")
    }

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |tell me |please )*"
    private const val IDX = "((the )?(nifty|nifty 50|bank nifty|banknifty|fin nifty|finnifty|sensex|index|indices) )?"
    private val ASKED = Regex("^ $LEAD(how is|how s|hows|how has|how was) (the |today s |todays )?$IDX(expiry|expiry day)( going| doing| been| looking| so far)*( today)?( boss| jarvis)? $|" +
        "^ $LEAD(give me |what s |whats |what is )?(the |an |today s |todays )?$IDX(expiry|expiry day) (update|so far|check|status|read)( today)?( boss| jarvis)? $|" +
        "^ $LEAD(how much has |how much did )?(the )?(atm |at the money )?straddle (decayed|fallen|dropped|come down|lost)( today)?( boss| jarvis)? $")

    /** "How is expiry going?", "Nifty expiry update", "how much has the straddle decayed?". */
    fun asked(text: String): Boolean = ASKED.containsMatchIn(norm(text))

    /**
     * "How is expiry going?" from today's [reads] per index (oldest first): the latest straddle against the first and
     * spot against max pain. Null when nothing was read today (no expiry today, or the app has not read the chain yet).
     */
    fun soFar(reads: Map<Market, List<Read>>): String? {
        val lines = reads.entries.filter { it.value.isNotEmpty() }.map { (m, rs) ->
            listOfNotNull(straddle(m, rs.first(), rs.last()), pain(rs.first(), rs.last())).joinToString(" ")
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
