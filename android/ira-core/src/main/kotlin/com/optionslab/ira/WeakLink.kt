package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * The weakest link in Boss's setup (Jarvis reasoning, round 20): "what's the weakest link in my setup?", "what usually goes
 * wrong in my paper trades?", "where do my bots go wrong most?", "my weak spots", "mere trades mein sabse kamzor kadi kya
 * hai". The arms' last [WINDOW] closed paper trades from their own book (the exit reason as booked, the entry and exit
 * times, the rupees after charges), with BankNifty's 1-minute candles on the phone for each day's opening gap, are read as
 * facts with counts:
 *
 *  - the exit mix: how many went out at the stop, the target, the profit lock, the 15:10 square-off, a time or index stop,
 *    Boss's own close - each with its rupees together - and how many stops came within [QUICK_MINUTES] minutes of entry;
 *  - the entry side, each slice against the rest in rupees a trade: each arm, the day's opening gap ([ArmFit.GAP_BANDS]),
 *    the time of entry (the first [QUICK_MINUTES] minutes of the session, before 11:00, 11:00 to 13:00, 13:00 on);
 *  - then the slices that did worst against the rest, ranked (at most [SHOW]), each with a labelled conditional - the
 *    window's arithmetic without those trades, never a forecast.
 *
 * A slice with fewer than [MIN_SLICE] trades (or fewer than that left outside it) is counted, never ranked; fewer than
 * [MIN_TRADES] closed paper trades in all is said honestly. Facts and labelled arithmetic only - never a verdict on the
 * rules or advice: what stays armed is Boss's call, and nothing here arms, stops, places or closes anything. Boss's
 * account, so never on a locked phone. Pure.
 */
object WeakLink {
    /** One closed paper trade: the arm as Boss hears it, entry and exit times, the exit reason as booked, rupees after charges. */
    data class Trade(val arm: String, val entryTime: java.time.LocalDateTime, val exitTime: java.time.LocalDateTime, val why: String?, val net: Double)

    /** One entry-side slice: what it is in words, its trades. */
    internal data class Slice(val what: String, val trades: List<Trade>)

    /** At most this many of the latest closed trades are read. */
    const val WINDOW = 40
    /** Fewer closed paper trades than this: too few to read. */
    const val MIN_TRADES = 10
    /** Fewer trades than this in a slice (or outside it): counted, never ranked. */
    const val MIN_SLICE = 5
    /** A stop this soon after entry is a quick stop; an entry this soon after the open is an opening entry. */
    const val QUICK_MINUTES = 5L
    /** At most this many weak slices are ranked. */
    const val SHOW = 3

    val OPEN: LocalTime = LocalTime.of(9, 15)
    private val MORNING = LocalTime.of(11, 0)
    private val MIDDAY = LocalTime.of(13, 0)

    const val NOTE = "Facts from your arms' own paper book, Boss - counts and arithmetic, not a verdict on the rules and not advice; what stays armed is your call."
    const val LOCKED = "Unlock the phone for your trades' record, Boss."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "").replace("'", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun trades(k: Int) = if (k == 1) "1 trade" else "$k trades"

    // ---- the question ---------------------------------------------------------------------------------------------

    private const val MY = "(my |mere |meri |mera |hamare |our )"
    private const val WHAT = "((paper )?(trades|trading|setup|setups|system|bots|arms|strategies|strategy|algos|book))"
    private val ASK = listOf(
        // "What's the weakest link in my setup?", "the weak spots in my trading", "my weakest link"
        " (weakest|weak) (link|links|spot|spots|point|points|part|area|areas) (in|of) $MY$WHAT ",
        " $MY(weakest|weak) (link|links|spot|spots|point|points) ",
        // "What usually goes wrong in my paper trades?", "what keeps going wrong with my bots?", "what goes wrong most often in my trades?"
        " what (usually|mostly|most often|most|generally|typically|keeps|always) (goes|go|going) wrong (in|with|for) $MY$WHAT ",
        " what (goes|go) wrong (most|most often|the most|usually|mostly) (in|with|for) $MY$WHAT ",
        " what (goes|go) wrong (in|with|for) $MY$WHAT (most|most often|the most|usually|mostly) ",
        // "Where do my trades go wrong most?", "where do my bots usually go wrong?"
        " where (do|does) $MY$WHAT (usually |mostly |most often |most |keep )?(go|going) wrong ",
        // "What do my losing trades have in common?"
        " what (do|did) $MY(losing|losers|bad|red) (trades|paper trades)? ?(have|had) in common ",
        // Hinglish: "mere trades mein sabse kamzor kadi kya hai", "mere bots mein aksar kya galat hota hai"
        " (sabse )?(kamzor|kamjor) (kadi|kaddi) ",
        " $MY(trades|trade|bots|bot|arms|setup|strategy|strategies) (mein|me|main) (aksar|zyada tar|zyadatar|sabse zyada|baar baar) (kya|kahan|kaha) (galat|gadbad) ",
        // Round 18: "which part of my setup is weakest", "mera sabse kamzor point kya hai", "mere trade kahan galat jaate hain"
        " (which|what) (part|parts|area|areas|side|bit) of $MY$WHAT (is|are) (the )?(weakest|weak) ",
        " $MY(sabse )?(kamzor|kamjor) (point|points|hissa|cheez|pehlu|jagah) ",
        " $MY(trades|trade|bots|bot|arms|strategy|strategies) (aksar |zyada tar |baar baar )?(kahan|kaha|kidhar) (galat|gadbad) (jaate|jate|jata|jaata|hote|hota|hoti|ho jaate|ho jate|ho jaati|ho jati) ",
    ).map { rx(it) }
    // Another day or a single trade (ArmDay, BotTrades, TradeCase), Jarvis's own record, advice, a switch, a what-if.
    private val NOT = rx(" (today|todays|aaj|yesterday|kal|this trade|that trade|last trade|should|shall|will|would|" +
        "stop it|switch off|switch on|turn off|disarm|what if|suppose|agar) ")

    /** Asked what most often goes wrong in his paper trades: never one day, one trade, Jarvis's own record or advice. */
    fun asked(text: String): Boolean {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return false
        return ASK.any { it.containsMatchIn(t) }
    }

    // ---- the reading ----------------------------------------------------------------------------------------------

    /** Each day's opening gap (% of the previous whole session's last close) from 1-minute candles over several sessions. */
    fun gaps(bars: List<Candle>): Map<LocalDate, Double> {
        val byDay = bars.groupBy { it.t.toLocalDate() }.toSortedMap()
        val days = byDay.keys.toList()
        val out = HashMap<LocalDate, Double>()
        for (i in 1 until days.size) {
            val prev = byDay.getValue(days[i - 1]).maxByOrNull { it.t }?.c ?: continue
            val open = byDay.getValue(days[i]).minByOrNull { it.t } ?: continue
            if (prev <= 0.0 || open.t.toLocalTime().isAfter(OPEN.plusMinutes(QUICK_MINUTES))) continue
            out[days[i]] = (open.o - prev) / prev * 100.0
        }
        return out
    }

    private fun held(t: Trade): Long = Duration.between(t.entryTime, t.exitTime).toMinutes()

    private val EXITS = listOf(
        "at the stop" to setOf("stop"), "at the target" to setOf("target"), "at the profit lock" to setOf("profit_lock"),
        "at the 15:10 square-off" to setOf("session_end", "backstop_square_off"), "at a time stop" to setOf("time_stop"),
        "at an index stop, a failed break or new liquidity" to setOf("index_stop", "failed_break", "new_liquidity"),
        "closed or stopped by you" to setOf("closed_by_you", "operator_stop"),
    )

    /** The exit mix in words: each reason with its count and rupees together, largest count first. */
    internal fun exits(ts: List<Trade>): String {
        val known = EXITS.flatMap { it.second }.toSet()
        val groups = EXITS.map { (w, s) -> w to ts.filter { it.why in s } } + ("for another reason" to ts.filter { it.why !in known })
        val parts = groups.filter { it.second.isNotEmpty() }.sortedByDescending { it.second.size }
            .map { (w, g) -> "${g.size} $w (${rs(g.sumOf { it.net })})" }
        val stops = ts.filter { it.why == "stop" }
        val quick = stops.count { held(it) <= QUICK_MINUTES }
        return "Exits: " + parts.joinToString(", ") + "." +
            if (stops.isNotEmpty()) " Of the ${stops.size} stop${if (stops.size == 1) "" else "s"}, $quick came within $QUICK_MINUTES minutes of entry." else ""
    }

    /** The entry-side slices: each arm (when more than one traded), each gap band, each time of entry. */
    internal fun slices(ts: List<Trade>, gap: Map<LocalDate, Double>): List<Slice> {
        val out = ArrayList<Slice>()
        val arms = ts.groupBy { it.arm }
        if (arms.size > 1) arms.forEach { (a, g) -> out += Slice("$a's trades", g) }
        val names = listOf("entries on flat opens (under ${ArmFit.GAP_BANDS.first}% either way)",
            "entries on days BankNifty gapped ${ArmFit.GAP_BANDS.first}-${ArmFit.GAP_BANDS.second}%",
            "entries on days BankNifty gapped ${ArmFit.GAP_BANDS.second}% or more either way")
        val banded = ts.filter { it.entryTime.toLocalDate() in gap }.groupBy { ArmFit.gapBand(gap.getValue(it.entryTime.toLocalDate())) }
        for (b in 0..2) banded[b]?.let { out += Slice(names[b], it) }
        val first = OPEN.plusMinutes(QUICK_MINUTES)
        out += Slice("entries in the first $QUICK_MINUTES minutes of the session (09:15 to 09:20)", ts.filter { it.entryTime.toLocalTime().isBefore(first) })
        out += Slice("entries before 11:00", ts.filter { it.entryTime.toLocalTime().isBefore(MORNING) })
        out += Slice("entries from 11:00 to 13:00", ts.filter { val x = it.entryTime.toLocalTime(); !x.isBefore(MORNING) && x.isBefore(MIDDAY) })
        out += Slice("entries from 13:00 on", ts.filter { !it.entryTime.toLocalTime().isBefore(MIDDAY) })
        return out.filter { it.trades.isNotEmpty() }
    }

    /** A slice's rupees a trade below the rest's (negative: worse); null when it or the rest is too small to rank. */
    internal fun gapToRest(s: Slice, all: List<Trade>): Double? {
        val restN = all.size - s.trades.size
        if (s.trades.size < MIN_SLICE || restN < MIN_SLICE) return null
        val sum = s.trades.sumOf { it.net }
        return sum / s.trades.size - (all.sumOf { it.net } - sum) / restN
    }

    /**
     * "What's the weakest link in my setup?" from the arms' closed paper [closed] trades (any order; open ones are left
     * out by the caller) and BankNifty's 1-minute candles [bars] over several sessions.
     */
    fun answer(closed: List<Trade>, bars: List<Candle>): String {
        val ts = closed.sortedBy { it.exitTime }.takeLast(WINDOW)
        if (ts.size < MIN_TRADES)
            return "Your arms have only ${trades(ts.size)} closed on paper, Boss - too few to say what most often goes wrong (I read from $MIN_TRADES)."
        val gap = gaps(bars)
        val total = ts.sumOf { it.net }
        val losers = ts.count { it.net < 0 }
        val from = ts.first().exitTime.toLocalDate()
        val to = ts.last().exitTime.toLocalDate()
        val out = ArrayList<String>()
        out += "Your arms' last ${ts.size} closed paper trades ($from to $to): $losers lost, ${ts.size - losers} did not; ${rs(total)} together after charges."
        out += exits(ts)
        val all = slices(ts, gap)
        val ranked = all.mapNotNull { s -> gapToRest(s, ts)?.let { s to it } }
            .filter { (s, d) -> d < 0 && s.trades.sumOf { it.net } < 0 }.sortedBy { it.second }.take(SHOW)
        if (ranked.isEmpty()) out += "No slice of at least $MIN_SLICE trades - by arm, by the day's opening gap or by the time of entry - did worse than the rest while losing money together."
        else {
            out += "Doing worst against the rest:"
            ranked.forEachIndexed { i, (s, _) ->
                val g = s.trades
                val sum = g.sumOf { it.net }
                val restN = ts.size - g.size
                out += "${i + 1}. ${s.what.replaceFirstChar { it.uppercase() }}: ${g.size} trades, ${g.count { it.net < 0 }} lost, ${rs(sum)} together " +
                    "(${rs(sum / g.size)} a trade, against ${rs((total - sum) / restN)} a trade for the other $restN). " +
                    "Arithmetic, not a forecast: without those ${g.size}, the window would have netted ${rs(total - sum)} instead of ${rs(total)}."
            }
        }
        val small = all.filter { gapToRest(it, ts) == null }.map { "${it.what} (${trades(it.trades.size)})" }
        if (small.isNotEmpty()) out += "Too few to rank: " + small.joinToString(", ") + "."
        val noGap = ts.count { it.entryTime.toLocalDate() !in gap }
        if (noGap > 0) out += "${trades(noGap)} ${if (noGap == 1) "has" else "have"} no BankNifty candles on the phone for the day's gap, so ${if (noGap == 1) "it is" else "they are"} left out of the gap slices."
        out += NOTE
        return out.joinToString(" ")
    }
}
