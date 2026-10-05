package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Closes beside round numbers (market intelligence, round 24): "does Nifty close near round numbers?", "are round numbers
 * a magnet for Nifty?", "how often does BankNifty end near a round thousand?", "round number record", "nifty gol figure ke
 * paas kitni baar band hota hai". From the whole sessions of 1-minute candles on the phone: each past close against the
 * nearest round number (Nifty and FinNifty every 500 points, BankNifty and Sensex every 1,000, or the step named - 100,
 * 250, 500 or 1,000), how often it ended within [BAND_PCT] of one, and - so a "magnet" is not passed off as more than it is
 * - how often closes landing anywhere would have done so by chance alone (the band's share of the step, day by day), with
 * the spread chance allows over that many days; the average distance from the nearest round number against the quarter
 * step chance gives; the last such close, the round number met most often, and where the price stands now. A record of
 * past days on this phone, said with its counts; never a forecast or advice; nothing acts. What sits at one price stays
 * [LevelInfo]'s, the expiry-day pin [ExpiryPin]'s. Pure.
 */
object RoundCloses {
    /** What was asked: the round-number step named, or null for the index's own ([step]). */
    data class Q(val step: Double? = null)

    /** One past whole session's [close] on [day], its [nearest] round number at [step]. */
    data class Day(val day: LocalDate, val close: Double, val step: Double) {
        val nearest: Double get() = Math.round(close / step) * step
        val off: Double get() = abs(close - nearest)
        /** Within [BAND_PCT] of [nearest]. */
        val near: Boolean get() = off <= close * BAND_PCT / 100
        /** The chance a close landing anywhere in the step would fall that near: the band's share of the step. */
        val chance: Double get() = minOf(1.0, 2 * close * BAND_PCT / 100 / step)
    }

    /** "Near" a round number: within this much of it (%). */
    const val BAND_PCT = 0.1
    /** Fewer whole sessions than this: too few to say anything. */
    const val MIN_DAYS = 30
    /** At most this many of the newest sessions are read. */
    const val MAX_DAYS = 250
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val STEPS = listOf(100.0, 250.0, 500.0, 1000.0)
    private val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past closes on this phone, Boss, not a forecast - a round number is a reading, never a pull on price."
    const val NOT_HERE = "I keep the round-number record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades on its own clock, and India VIX is a reading of fear, not a price with round levels."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(",", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun k(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun share(a: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(a * 100.0 / of))
    private fun pctOf(x: Double) = "%d%%".format(Locale.ENGLISH, Math.round(x * 100))
    private fun date(d: LocalDate) = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** Round numbers, named. */
    private val ROUND = Regex(" (round number|round numbers|round figure|round figures|round level|round levels|round hundred|round hundreds|" +
        "round thousand|round thousands|round 100|round 100s|round 250|round 500|round 500s|round 1000|round 1000s|round five hundred|" +
        "psychological level|psychological levels|psychological number|psychological numbers|gol number|gol numbers|gol figure|gol figures|gol ank) ")
    /** A record asked of: how often, a magnet, closes that land there. */
    private val RECORD = Regex(" (how often|how many times|how many days|how frequently|how common|how rare|what share|what percentage|usually|normally|" +
        "typically|generally|tend to|tends to|on average|record|records|stats|statistics|history|historically|magnet|magnets|magnetic|pull|pulls|" +
        "attract|attracts|attraction|gravitate|gravitates|stick|sticks|respect|respects|cluster|clusters|close near|closes near|closed near|close at|closes at|" +
        "end near|ends near|ended near|settle near|settles near|finish near|finishes near|kitni baar|kitne din|aksar|band hota|band hoti) ")
    // A forecast or advice, Boss's own book, a single price or the day now, the expiry pin and the chain, a meaning, the last time.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|predict|prediction|forecast|target|targets|should|shall|buy|sell|enter|exit|" +
        "trade|trades|trading|stop loss|stoploss|sl|i|me|my|mine|we|our|what if|suppose|agar|today|todays|now|right now|abhi|aaj|this week|" +
        "expiry|expiries|pin|pinned|pinning|max pain|oi|open interest|strike|strikes|chain|option|options|alert|alerts|notify|remind|" +
        "bot|bots|algo|strategy|backtest|mean|means|meaning|define|explain|what is a|what are|last time|when did|support|resistance|" +
        "gold|vix) ")

    /** What was asked, or null: the record of past closes beside round numbers only, never a forecast, advice or one price. */
    fun asked(text: String): Q? {
        val t = norm(text)
        if (!ROUND.containsMatchIn(t) || !RECORD.containsMatchIn(t) || NOT.containsMatchIn(t)) return null
        // A single price named ("is 25000 a round number") is LevelInfo's: only a step beside "round" or "points" counts.
        return Q(step(t))
    }

    /** The step named ("round 500", "round thousands", "1000 point round numbers"), or null. */
    private fun step(t: String): Double? {
        if (rx(" round (thousand|thousands) ").containsMatchIn(t)) return 1000.0
        if (rx(" round (hundred|hundreds) ").containsMatchIn(t)) return 100.0
        if (rx(" round five hundred ").containsMatchIn(t)) return 500.0
        val m = rx(" round (\\d{3,4})s? | (\\d{3,4}) (point|points|pt|pts) (round|levels?|marks?) | every (\\d{3,4}) (point|points|pts)? ?").find(t) ?: return null
        return m.groupValues.drop(1).firstOrNull { rx("^\\d{3,4}$").matches(it) }?.toDouble()?.takeIf { it in STEPS }
    }

    /** The index asked about: the first index named, Nifty when none is; null when only gold or India VIX is. */
    fun market(markets: List<Market>): Market? {
        val idx = markets.firstOrNull { it in INDICES }
        if (idx == null && markets.any { it == Market.GOLD || it == Market.VIX }) return null
        return idx ?: Market.NIFTY
    }

    /** The index's own round-number step: 500 for Nifty and FinNifty, 1,000 for BankNifty and Sensex. */
    fun step(m: Market): Double = if (m == Market.BANKNIFTY || m == Market.SENSEX) 1000.0 else 500.0

    // ---- the record -----------------------------------------------------------------------------------------------

    private fun whole(s: MarketStory.Session) = s.bars.isNotEmpty() && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) && !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    /** Each whole session's close before [today] against round numbers [step] apart - newest [MAX_DAYS], oldest first. */
    fun past(bars: List<Candle>, today: LocalDate, step: Double): List<Day> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) && whole(it) && it.close > 0 }.map { Day(it.day, it.close, step) }.takeLast(MAX_DAYS)

    /** [q] answered for [m] from its 1-minute candles over several days, at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val step = q.step ?: step(m)
        val days = past(bars, today, step)
        val every = "every ${k(step)} points"
        if (days.size < MIN_DAYS)
            return "I have only ${days.size} whole ${if (days.size == 1) "session" else "sessions"} of ${m.label} on the phone, Boss - too few to say how often it closes near round numbers (I need $MIN_DAYS)."
        val lines = ArrayList<String>()
        val near = days.filter { it.near }
        val kk = near.size
        val expected = days.sumOf { it.chance }
        val sd = sqrt(days.sumOf { it.chance * (1 - it.chance) })
        val avgChance = expected / days.size
        lines += "Over the last ${days.size} whole sessions on this phone, ${m.label} closed within ${"%.1f".format(Locale.ENGLISH, BAND_PCT)}% of a round number ($every) on $kk (${share(kk, days.size)}). " +
            "Closes landing anywhere would do that about ${pctOf(avgChance)} of the time - about ${Math.round(expected)} of ${days.size} days."
        lines += when {
            kk > expected + 2 * sd -> "That is more often than chance alone gives over this many days - round numbers have held closes more than chance on this record."
            kk < expected - 2 * sd -> "That is less often than chance alone gives over this many days - on this record closes have tended to end away from round numbers."
            else -> "That is within what chance alone gives over this many days (about ${Math.round(maxOf(0.0, expected - 2 * sd))} to ${Math.round(expected + 2 * sd)}), so this record shows no clear pull to round numbers."
        }
        val avgOff = days.map { it.off }.average()
        lines += "On average a close ended ${k(avgOff)} points from the nearest round number; closes landing anywhere would average about ${k(step / 4)}."
        if (near.isNotEmpty()) {
            val last = near.last()
            lines += "The last was ${date(last.day)}: ${n(last.close)}, ${k(last.off)} points from ${k(last.nearest)}."
            near.groupingBy { it.nearest }.eachCount().filterValues { it >= 2 }.maxWithOrNull(compareBy<Map.Entry<Double, Int>> { it.value }.thenBy { it.key })
                ?.let { lines += "The round number closes ended beside most often was ${k(it.key)}, ${it.value} times." }
        }
        bars.lastOrNull { it.t.toLocalDate() == today }?.let { b ->
            val r = Math.round(b.c / step) * step
            val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
            lines += "${if (live) "Now" else "Today"} ${m.label} ${if (live) "is" else "closed"} at ${n(b.c)}, ${k(abs(b.c - r))} points ${if (b.c >= r) "above" else "below"} ${k(r)}."
        }
        lines += NOTE
        return lines.joinToString(" ")
    }
}
