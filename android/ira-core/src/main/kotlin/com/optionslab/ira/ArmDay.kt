package com.optionslab.ira

import com.optionslab.engine.orb.OrbRules
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * Why a strategy lost today, tied to what the index did (reasoning, round 16): "why did my strategy lose today?", "why
 * did ORB lose today?", "what went wrong with Range Fade today?", "why was today a bad day for my bots?", "ORB ka aaj loss
 * kyun hua". [BotTrades] tells each trade against its arm's written rule; this sets each of today's arm trades beside the
 * index it traded, step by step, from the phone's own 1-minute candles:
 *
 *  - the day first: the gap from the last close and when (if) it filled, the opening range (09:15-10:00) and each
 *    5-minute close beyond it - and back inside it - with the time, the day's high and low with their times, and where the
 *    index is now against the open;
 *  - each trade: the index at the entry (and where that was against the opening range), its best for the trade's side
 *    while held and when, the index at the exit and how far that was for or against the side, the exit as booked, the
 *    rupees after charges; then what the index did after the exit (index points only - the phone holds no minute prices
 *    for the option, so what the option would have done is never said);
 *  - each losing trade's shape from those facts alone: a break of the opening range the index came back inside of by the
 *    exit ([Shape.FAILED_BREAK]), a move that went the side's way and came back past the entry ([Shape.GAVE_BACK]), an
 *    index that never went the side's way ([Shape.NEVER_WENT]), or an index that ended the hold the side's way while the
 *    option still lost ([Shape.PREMIUM]) - and the shapes counted across the losing trades.
 *
 * Facts beside facts, never a cause proven, never a forecast or advice; what stays armed is Boss's call and nothing here
 * arms, stops, places or closes anything. Paper and Zerodha alike are Boss's account: never said on a locked phone. Pure.
 */
object ArmDay {
    /** What was asked: one arm group's day ([bot] as [BotTrades.Q]'s), or all arms' (null). */
    data class Q(val bot: String? = null)

    /** The shape of a trade's hold, from the index's own candles. */
    enum class Shape { FAILED_BREAK, GAVE_BACK, NEVER_WENT, PREMIUM, WON, OPEN, UNKNOWN }

    /** One trade beside its index: the index at [atEntry] and [atExit], its [best] for the side while held, [shape]. */
    data class Hold(val trade: BotTrades.Trade, val label: String, val shape: Shape, val text: String,
                    val atEntry: Double? = null, val atExit: Double? = null, val best: Double? = null, val bestAt: LocalDateTime? = null)

    /** A move of the index under this share of its price (percent) is "barely its way". */
    const val SMALL_PCT = 0.10
    /** At most this many trades are told one by one. */
    const val MAX_TOLD = 6

    const val NOTE = "Facts from the arms' own books and the candles on the phone, Boss - what the index did beside each trade, not a proven cause, " +
        "not a forecast and not advice; what stays armed is your call."
    const val LOCKED = "Unlock the phone for that, Boss."

    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun p0(x: Double) = "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun signed(x: Double) = (if (x < 0) "-" else "+") + p0(x)
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun leg(right: String) = if (right.equals("PE", true)) "put" else "call"
    private fun side(right: String) = if (right.equals("PE", true)) -1 else 1
    private fun indexName(u: String) = if (u == "FINNIFTY") "FinNifty" else "BankNifty"

    /** The index's close at minute [t] (the last candle at or before it, today), or null. */
    private fun at(ones: List<Candle>, t: LocalDateTime): Double? = ones.lastOrNull { !it.t.isAfter(t) && it.t.toLocalDate() == t.toLocalDate() }?.c

    // ---- the day -------------------------------------------------------------------------------------------------

    /** The 5-minute closes beyond the opening range [range] (high, low) after 10:00 and back inside, in time order. */
    internal fun breaks(ones: List<Candle>, range: Pair<Double, Double>, day: LocalDate, until: LocalDateTime): List<Pair<LocalDateTime, Int>> {
        val (hi, lo) = range
        val out = ArrayList<Pair<LocalDateTime, Int>>()
        var where = 0
        var start = day.atTime(OrbRules.OR_END).plusMinutes(5)
        while (!start.plusMinutes(5).isAfter(until.plusMinutes(1))) {
            val b = BotTrades.bar5(ones, start)
            if (b != null) {
                val now = if (b.c > hi) 1 else if (b.c < lo) -1 else 0
                if (now != where) { out += start.plusMinutes(5) to now; where = now }
            }
            start = start.plusMinutes(5)
        }
        return out
    }

    /** One line on [u]'s day so far from its 1-minute [ones], to [now]. */
    internal fun dayLine(u: String, ones: List<Candle>, range: Pair<Double, Double>?, now: LocalDateTime): String? {
        val today = now.toLocalDate()
        val day = ones.filter { it.t.toLocalDate() == today && !it.t.isAfter(now) }.sortedBy { it.t }
        if (day.isEmpty()) return null
        val name = indexName(u)
        val parts = ArrayList<String>()
        val prev = ones.filter { it.t.toLocalDate().isBefore(today) }.maxByOrNull { it.t }?.c
        val open = day.first().o
        if (prev != null && prev > 0) {
            val gap = (open - prev) / prev * 100
            if (abs(gap) >= 0.15) {
                val fill = day.firstOrNull { if (gap > 0) it.l <= prev else it.h >= prev }
                parts += "it opened ${pct(gap)} from the last close (${n(prev)} to ${n(open)})" +
                    (fill?.let { ", the gap filled at ${hm(it.t)}" } ?: ", the gap still open")
            } else parts += "it opened flat to the last close (${pct(gap)})"
        }
        if (range != null) {
            val (hi, lo) = range
            val moves = breaks(ones, range, today, now)
            val said = moves.map { (t, w) -> when (w) { 1 -> "a 5-minute close above it at ${hm(t)}"; -1 -> "a close below it at ${hm(t)}"; else -> "back inside at ${hm(t)}" } }
            parts += "the opening range was ${n(hi)}-${n(lo)} (09:15-10:00)" + (if (said.isEmpty()) ", with no 5-minute close beyond it since" else ": " + said.joinToString(", "))
        }
        val hiC = day.maxBy { it.h }; val loC = day.minBy { it.l }
        parts += "the day's high ${n(hiC.h)} at ${hm(hiC.t)} and low ${n(loC.l)} at ${hm(loC.t)}"
        val last = day.last()
        parts += "at ${hm(last.t.plusMinutes(1))} ${n(last.c)}, ${pct((last.c - open) / open * 100)} from the open"
        return "The day on $name: " + parts.joinToString("; ") + "."
    }

    // ---- one trade beside its index ------------------------------------------------------------------------------

    /** [t] beside its index's 1-minute [ones] and the opening [range] (high, low), to [now]. */
    fun hold(t: BotTrades.Trade, ones: List<Candle>, range: Pair<Double, Double>?, now: LocalDateTime): Hold {
        val label = BotTrades.label(t.source)
        val name = indexName(BotTrades.underlying(t))
        val s = side(t.right)
        val lg = leg(t.right)
        val end = t.exitTime ?: now
        val pnl = t.exit?.let { (it - t.entry) * t.qty - t.charges }
        val head = "$label ${lg}, ${hm(t.entryTime)}-${t.exitTime?.let { hm(it) } ?: "open"}" +
            (if (pnl != null) ", ${rs(pnl)} after charges${whyWords(t.why)?.let { ", $it" } ?: ""}" else ", still open")
        val e = at(ones, t.entryTime)
        val held = ones.filter { !it.t.isBefore(t.entryTime.withSecond(0)) && !it.t.isAfter(end) && it.t.toLocalDate() == t.entryTime.toLocalDate() }
        if (e == null || held.isEmpty()) {
            val shape = when { pnl == null -> Shape.OPEN; pnl >= 0 -> Shape.WON; else -> Shape.UNKNOWN }
            return Hold(t, label, shape, "$head. $name's candles for the hold are not on the phone.")
        }
        val x = at(ones, end) ?: held.last().c
        val bestC = if (s > 0) held.maxBy { it.h } else held.minBy { it.l }
        val best = if (s > 0) bestC.h else bestC.l
        val fav = (s * (best - e)).coerceAtLeast(0.0)
        val move = s * (x - e)
        val small = e * SMALL_PCT / 100
        val words = ArrayList<String>()
        val rangeAt = range?.let { (hi, lo) -> when { e > hi -> " above the opening range high ${n(hi)}"; e < lo -> " below the opening range low ${n(lo)}"; else -> " inside the opening range" } } ?: ""
        words += "$name ${n(e)} at the entry$rangeAt"
        words += if (fav >= 1) "its best for the $lg ${n(best)} at ${hm(bestC.t)} (${signed(fav)} points)" else "it never went the $lg's way while held"
        words += (if (t.exitTime != null) "by the exit" else "now") + " ${n(x)} (${signed(move)} points for the $lg)"
        val beyond = range?.let { (hi, lo) -> (s > 0 && e > hi) || (s < 0 && e < lo) } == true
        val backInside = range?.let { (hi, lo) -> if (s > 0) x <= hi else x >= lo } == true
        val shape = when {
            pnl == null -> Shape.OPEN
            pnl >= 0 -> Shape.WON
            beyond && backInside -> Shape.FAILED_BREAK
            move < 0 && fav >= small -> Shape.GAVE_BACK
            move < 0 -> Shape.NEVER_WENT
            else -> Shape.PREMIUM
        }
        val tie = when (shape) {
            Shape.FAILED_BREAK -> "back inside the range by the exit: the break didn't hold"
            Shape.GAVE_BACK -> "it went ${p0(fav)} points the $lg's way, then came back past the entry"
            Shape.NEVER_WENT -> if (fav < 1) "the index never went the $lg's way" else "the index went barely ${p0(fav)} points the $lg's way"
            Shape.PREMIUM -> "the index ended the hold the $lg's way, yet the option's price lost - the phone holds no minute prices for the option, " +
                "so time and volatility can't be told apart"
            else -> null
        }
        var text = "$head. " + words.joinToString("; ") + (tie?.let { ": $it" } ?: "") + "."
        // After the exit: index points only, never what the option would have done.
        if (t.exitTime != null) {
            val last = ones.lastOrNull { it.t.toLocalDate() == t.exitTime.toLocalDate() && !it.t.isAfter(now) }
            if (last != null && last.t.isAfter(t.exitTime)) {
                val after = s * (last.c - x)
                text += " Since the exit $name is at ${n(last.c)} (${hm(last.t.plusMinutes(1))}), ${p0(after)} points " +
                    (if (abs(after) < 1) "from where it was" else if (after > 0) "further the $lg's way" else "further against the $lg") + "."
            }
        }
        return Hold(t, label, shape, text, e, x, best, bestC.t)
    }

    private fun whyWords(why: String?): String? = when (why) {
        null -> null
        "stop" -> "out at its stop"
        "target" -> "out at its target"
        "profit_lock" -> "out at the profit lock"
        "session_end" -> "out at the ${"%02d:%02d".format(Locale.ENGLISH, OrbRules.SQUARE_OFF.hour, OrbRules.SQUARE_OFF.minute)} square-off"
        "time_stop" -> "out at its time stop"
        "index_stop" -> "out at its index stop"
        "failed_break" -> "out on a failed break"
        "new_liquidity" -> "out on new liquidity"
        "operator_stop" -> "out when you stopped it"
        "closed_by_you" -> "closed by you"
        "backstop_square_off" -> "out at the backstop square-off"
        else -> "out (${why.replace('_', ' ')})"
    }

    private fun shapeWords(shape: Shape, k: Int): String = when (shape) {
        Shape.FAILED_BREAK -> "$k ${if (k == 1) "was a break" else "were breaks"} of the opening range the index came back inside of"
        Shape.GAVE_BACK -> "$k ${if (k == 1) "was a move" else "were moves"} that went its way and came back past the entry"
        Shape.NEVER_WENT -> "$k ${if (k == 1) "was a trade" else "were trades"} the index never really went the way of"
        Shape.PREMIUM -> "$k lost though the index ended the hold its way"
        else -> "$k other"
    }

    // ---- the answer ----------------------------------------------------------------------------------------------

    /**
     * The answer to [q] from today's arm [trades], each index's 1-minute candles by underlying ("BANKNIFTY", "FINNIFTY")
     * in [bars], BankNifty's opening range as the arms read it ([range]; else from the candles), at [now].
     */
    fun answer(q: Q, trades: List<BotTrades.Trade>, bars: Map<String, List<Candle>>, range: Pair<Double, Double>?, now: LocalDateTime): String {
        val day = now.toLocalDate()
        val todays = trades.filter { it.entryTime.toLocalDate() == day && !it.entryTime.isAfter(now) }
        val asked = (q.bot?.let { b -> todays.filter { BotTrades.group(it.source) == b } } ?: todays).sortedBy { it.entryTime }
        val who = q.bot?.let { BotTrades.groupName(it) } ?: "your bots"
        if (asked.isEmpty()) return (if (q.bot != null) "$who has not traded today, Boss" else "None of your bots has traded today, Boss") +
            ", so there is no day of trades to set beside the index."
        val ranges = HashMap<String, Pair<Double, Double>?>()
        fun rangeOf(u: String) = ranges.getOrPut(u) { if (u == OrbRules.UNDERLYING && range != null) range else BotTrades.openingRange(bars[u].orEmpty(), day) }
        val holds = asked.map { t -> val u = BotTrades.underlying(t); hold(t, bars[u].orEmpty(), rangeOf(u), now) }
        val closed = asked.filter { it.exit != null }
        val net = closed.sumOf { (it.exit!! - it.entry) * it.qty - it.charges }
        val losers = holds.filter { it.trade.exit != null && (it.trade.exit - it.trade.entry) * it.trade.qty - it.trade.charges < 0 }
        val out = ArrayList<String>()
        val live = asked.count { it.live }
        out += "Boss, ${if (q.bot != null) who else "your bots"} took ${asked.size} trade${if (asked.size == 1) "" else "s"} today" +
            (if (live > 0) " ($live at Zerodha, ${asked.size - live} on paper)" else " (paper)") +
            (if (closed.isNotEmpty()) ": ${closed.size} closed for ${rs(net)} after charges, ${losers.size} of them at a loss" else "") +
            (if (closed.size < asked.size) ", ${asked.size - closed.size} still open" else "") + "." +
            (if (closed.isNotEmpty() && net >= 0) " The day's closed trades are not a loss; here is each beside the index all the same." else "")
        for (u in asked.map { BotTrades.underlying(it) }.distinct()) dayLine(u, bars[u].orEmpty(), rangeOf(u), now)?.let { out += it }
        holds.take(MAX_TOLD).forEachIndexed { i, h -> out += "${i + 1}. ${h.text}" }
        if (holds.size > MAX_TOLD) out += "And ${holds.size - MAX_TOLD} more trade${if (holds.size - MAX_TOLD == 1) "" else "s"}; ask for one arm by name for those."
        val shapes = losers.groupingBy { it.shape }.eachCount().filterKeys { it != Shape.OPEN && it != Shape.WON && it != Shape.UNKNOWN }
            .entries.sortedByDescending { it.value }
        if (shapes.isNotEmpty()) out += "Tied together: of the ${losers.size} losing trade${if (losers.size == 1) "" else "s"}, " +
            shapes.joinToString("; ") { shapeWords(it.key, it.value) } + "."
        out += NOTE
        return out.joinToString("\n")
    }

    // ---- the question --------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "").replace("'", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private const val BOT = "(strateg(y|ies)|paper bots?|bots?|algos?|arms?|orb fresh|orb sweep|orb|range fade|liquidity( 15 5| 15| 5| 30| bot| arm)?)"
    private const val MY = "(my |our |the |mere |meri |mera |hamare |todays |today s )?"
    private const val LOSE = "(lose|lost|lose money|lost money|go wrong|went wrong|do badly|do so badly|did badly|underperform|bleed|get stopped out|get stopped|end in the red|end red|make a loss|take a loss|fail|failed|go bad|went bad)"
    private val ASK = listOf(
        // "Why did my strategy lose today?", "why did ORB lose money?", "why did my bots do badly today?"
        " why (did|has|have) $MY(paper )?$BOT (s )?(trades? )?$LOSE ",
        // "Why is my strategy losing today?", "why are my bots in the red today?" (present tense only with today)
        " why (is|are) $MY(paper )?$BOT (losing|losing money|in the red|in loss|down)( so much)? (today|aaj) ",
        // "What went wrong with ORB today?", "what went wrong for my strategy?"
        " what (went|has gone|s gone|is going) wrong (with|for|in) $MY(paper )?$BOT ",
        // "Why was today a bad day for my bots?", "why was today a losing day for ORB?"
        " why (was|is) (today|it) (a |such a )?(bad|losing|red|poor|terrible) (day )?for $MY(paper )?$BOT ",
        // "How did the market beat my strategy today?", "connect my strategy's loss to what the index did"
        " how did (the market|the index|banknifty|bank nifty|nifty|today) (beat|hurt|catch out|trap|kill) $MY(paper )?$BOT ",
        " (connect|tie|link) $MY(paper )?$BOT (s )?(loss|losses|trades?)( today)? (to|with) (what )?(the market|the index|banknifty|bank nifty|nifty|the day) ",
        // Hinglish: "ORB ka aaj loss kyun hua", "meri strategy ne aaj loss kyun kiya", "aaj bot ko nuksan kyun hua"
        " $MY$BOT (ka|ki|ko|ne) (aaj )?(loss|nuksan|nuksaan|nuksan) (kyun|kyu|kyon|kaise) ",
        " $MY$BOT (ne )?(aaj )?(loss|nuksan|nuksaan) (kyun|kyu|kyon) (kiya|kara|hua|hui|aaya|diya) ",
        " aaj $MY$BOT (ka|ki|ko) (loss|nuksan|nuksaan) (kyun|kyu|kyon) ",
        " $MY$BOT (aaj )?(kyun|kyu|kyon) (haara|hara|haari|hari|loss mein gaya|loss mein gayi|loss me gaya|loss me gayi|fail hua|fail hui|fail ho gaya|fail ho gayi) ",
    ).map { rx(it) }
    /** Not this: a command, another day, a forecast or advice, a backtest, or Boss's own last trade. */
    private val NOT = rx(" (stop|start|disarm|arm it|switch|turn on|turn off|pause|backtest|back test|create|build|write|should|shall|will|would|tomorrow|" +
        "yesterday|kal|week|month|year|last trade|my trade|how much|limit) ")

    /** "Why did my strategy lose today?", "why did ORB lose?", "what went wrong with Range Fade today?", "ORB ka aaj loss kyun hua". */
    fun asked(text: String): Q? {
        for (s in listOf(text, Ask.reading(text))) {
            val t = norm(s)
            if (NOT.containsMatchIn(t) || ASK.none { it.containsMatchIn(t) }) continue
            val bot = when {
                rx(" orb fresh ").containsMatchIn(t) -> "orb_fresh"
                rx(" orb sweep ").containsMatchIn(t) -> "orb_sweep"
                rx(" range fade ").containsMatchIn(t) -> "range_fade"
                rx(" liquidity ").containsMatchIn(t) -> "liquidity"
                rx(" orbs? ").containsMatchIn(t) -> "orb"
                else -> null
            }
            return Q(bot)
        }
        return null
    }
}
