package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * The day so far, scored honestly (reasoning, round 34, 2026-10-06): "how's my scorecard today?", "my trades so far today",
 * "did I trade against the trend today?", "how long did I hold my trades today?", "aaj ka scorecard", "aaj maine trend ke
 * against trade kiya kya".
 *
 * [TradeReplay] replays each trade (its best and worst while held, the rest of the day after it); [DayJournal] drafts the
 * evening's entry; [AfterLoss], [WhereIWin] and [SmallTrades] read his record over weeks. None set today's own trades, each
 * one, against three plain checks. Here, for Boss's own closed trades today (the bots' are counted, never scored), each
 * account apart (paper is never added to real money):
 *
 *  - the index's direction at the entry: its move over the [LOOK_BACK] minutes before the minute he got in (from the open
 *    for an entry earlier than that), against the way the trade leans (a bought call or a sold put leans up; a bought put
 *    or a sold call down; a future its own way) - a move under [FLAT_PCT]% of the index is said as flat;
 *  - how long it was held against his usual hold: the median of his own closed trades in the same account before today
 *    (needs [MIN_USUAL]); under half of it or over twice it is said so, else near it;
 *  - whether the exit came before or after the option's best price in the [AFTER] minutes after it, from the contract's own
 *    minute candles: a better price his way within those minutes is said with how much and when; none means the exit was at
 *    or past anything that came next. A window cut short by the clock or the close says how many minutes it covers.
 *
 * Facts from his trades and the app's minute candles only - never advice, never a verdict on a trade, never a forecast;
 * nothing here places, changes or cancels an order. His account, so the hub asks for this only on an unlocked phone
 * ([LOCKED]); not in IraGoldAlgo, and gold is never read. Pure.
 */
object DayScore {
    /** The minutes before an entry the index's direction is read over. */
    const val LOOK_BACK = 15L
    /** An index move smaller than this share of it (%) is flat. */
    const val FLAT_PCT = 0.05
    /** The minutes after an exit the option's best price is looked for in. */
    const val AFTER = 15L
    /** His usual hold needs at least this many of his own closed trades before today. */
    const val MIN_USUAL = 5
    /** At most this many of today's trades are read, the newest. */
    const val MAX_TRADES = 12
    /** A price better than the exit by less than this (a tick) is no better. */
    const val TICK = 0.05

    const val LOCKED = "Your trades stay out of it on a locked phone, Boss - unlock it for that."
    const val CLOSING = "Facts from today's trades and the app's own minute candles, Boss - not advice and not a verdict on any trade."

    /** A closed round trip; [direction] +1 bought first, -1 sold first; prices per unit; [owner] "Manual" for his own. */
    data class Trip(val symbol: String, val direction: Int, val qty: Int, val entry: Double, val exit: Double,
                    val openedAt: LocalDateTime, val closedAt: LocalDateTime, val net: Double, val owner: String = "Manual")

    /** The way a trade gains: [UP] with the index rising, [DOWN] with it falling. */
    enum class Lean { UP, DOWN }

    /** Where the index stood against the trade at its entry. */
    enum class Side { WITH, AGAINST, FLAT }

    /**
     * One trade scored: its [lean] (null for a contract not on an index), the index's [move] in points over [moveMin]
     * minutes before the entry and the [side] that puts it on (null when the index's candles do not cover it); the hold in
     * seconds, the [usual] hold (null with too few trades before today); after the exit, the best price his way [best] at
     * [bestAt] within [window] minutes of candles (null when there are none).
     */
    data class Card(val trip: Trip, val market: Market?, val lean: Lean?, val move: Double?, val moveMin: Long?, val side: Side?,
                    val heldSec: Long, val usualSec: Long?, val best: Double?, val bestAt: LocalDateTime?, val window: Long?) {
        /** Points the option went beyond the exit his way in the minutes after it (0 or more); null without candles. */
        val better: Double? get() = best?.let { ((it - trip.exit) * trip.direction).coerceAtLeast(0.0) }
        /** Did the exit come before a better price in the minutes after it? Null without candles. */
        val beforeBest: Boolean? get() = better?.let { it >= TICK }
    }

    // ---- the question -----------------------------------------------------------------------------------------------

    private const val MY = "(my |mera |meri |mere |todays |today s |the |aaj ka |aaj ki |aaj ke )?"
    private const val TODAY = "(today|so far|so far today|today so far|aaj|aaj tak|abhi tak)"
    private const val TRADES = "(trades|trade|trading|round trips|exits|entries)"
    private const val TREND = "(trend|index|market|nifty|bank ?nifty|banknifty|fin ?nifty|finnifty|sensex|move|direction|flow|tide)"

    private val ASK = rx(
        // "My scorecard today", "how's today's scorecard?", "aaj ka scorecard", "day so far scorecard", "report card for my trades today"
        " $MY(trading |trade |trades |day s |days |day )?(score ?card|report card|scoresheet|score sheet)s? " +
        // "My trades so far today", "how are my trades today so far", "my day so far in trades"
        "| (how are |how have |how s |hows |how is )?$MY$TRADES (so far today|today so far|so far) " +
        "| (my|todays|today s) (trading )?day so far (in|for|with) (my )?trades " +
        // "Did I trade against the trend today?", "were my trades against the index today", "how many trades against the market today"
        "| (did i|have i|was i|were my|was my|are my|how many of my|how many) (\\w+ ){0,2}?(trade|trades|trading|go|gone|enter|entries|get in) (were |was |are |went )?(against|with) (the )?$TREND( \\w+){0,4} $TODAY " +
        "| (against|with) (the )?$TREND (trades|entries) $TODAY " +
        // "How long did I hold my trades today?", "did I hold longer than usual today", "how long was i in my trades today"
        "| how long (did i|have i|was i) (hold|held|keep|kept|stay|stayed|in) (\\w+ ){0,3}?$TODAY " +
        "| did i (hold|keep|stay in) (my |the )?(trades |positions )?(longer|shorter|less|more) than (usual|normal|i usually do|i normally do) $TODAY " +
        // "Did my exits come before the best price today?"
        "| (did|were) (my|todays|today s) exits (come |too )?(before|after|ahead of|early|late|before the best|at the best)( \\w+){0,3} $TODAY " +
        // Hinglish: "aaj maine trend ke against trade kiya kya", "aaj mera trades ka hisaab"
        "| (aaj|abhi tak) (maine|mai|main|mera|meri|mere) (\\w+ ){0,2}?(trend|market|index|nifty) ke (against|ulta|ulte|khilaf|khilaaf|saath|sath) " +
        "| aaj (ke|ka) (mere |mera )?trades? (ka |ki )?(hisaab|hisab|scorecard|report card|lekha jokha) ")

    /** Something else is meant: an order, advice, a reason, a bot's trades, another day, gold or Jarvis's own record. */
    private val NOT = rx(" (should|shall|recommend|suggest|advise|advice|why|buy|sell|close|exit now|square|cancel|place|modify|" +
        "bot|bots|strategy|strategies|orb|arm|arms|algo|your|jarvis s|jarviss|yesterday|kal|last week|this week|week|month|weekly|monthly|gold|" +
        "karo|kar do|kardo|bech|bechna|kharid|kharido) ")

    private fun norm(text: String) = Spaced.joined(text)

    /** Does [text] ask for today's own trades scored against the index, his usual hold and the price after each exit? */
    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return false
        if (Market.GOLD in Market.mentioned(text)) return false
        return ASK.containsMatchIn(t)
    }

    // ---- the reading ------------------------------------------------------------------------------------------------

    /** The index [symbol] is on, null for anything else. */
    fun marketOf(symbol: String): Market? = when {
        symbol.startsWith("BANKNIFTY") -> Market.BANKNIFTY
        symbol.startsWith("FINNIFTY") -> Market.FINNIFTY
        symbol.startsWith("NIFTY") -> Market.NIFTY
        symbol.startsWith("SENSEX") -> Market.SENSEX
        else -> null
    }

    /** The way [symbol] traded [direction] gains: a bought call or sold put up, a bought put or sold call down, a future its own way. */
    fun lean(symbol: String, direction: Int): Lean? {
        val s = symbol.uppercase()
        val long = direction >= 0
        return when {
            OPTION_CE.containsMatchIn(s) -> if (long) Lean.UP else Lean.DOWN
            OPTION_PE.containsMatchIn(s) -> if (long) Lean.DOWN else Lean.UP
            s.endsWith("FUT") -> if (long) Lean.UP else Lean.DOWN
            else -> null
        }
    }

    /** An option's symbol ends in its strike and CE / PE ("RELIANCE" is no call). */
    private val OPTION_CE = rx("\\dCE$")
    private val OPTION_PE = rx("\\dPE$")

    private fun own(t: Trip) = t.owner.startsWith("Manual")

    /** The median hold (seconds) of his own trades in [book] closed before [today], or null with fewer than [MIN_USUAL]. */
    fun usual(book: List<Trip>, today: LocalDate): Pair<Long, Int>? {
        val holds = book.filter { own(it) && it.closedAt.toLocalDate().isBefore(today) }
            .map { Duration.between(it.openedAt, it.closedAt).seconds.coerceAtLeast(0) }
        if (holds.size < MIN_USUAL) return null
        val s = holds.sorted()
        val mid = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        return mid to s.size
    }

    /**
     * [trip] scored: [option] its contract's minute candles (start times), [index] its index's, [usualSec] his usual hold,
     * read up to [now].
     */
    fun card(trip: Trip, option: List<Candle>, index: List<Candle>?, usualSec: Long?, now: LocalDateTime): Card {
        val m = marketOf(trip.symbol)
        val ln = lean(trip.symbol, trip.direction)
        val inMin = trip.openedAt.truncatedTo(ChronoUnit.MINUTES)
        val day = trip.openedAt.toLocalDate()
        var move: Double? = null; var moveMin: Long? = null; var side: Side? = null
        val ix = index?.filter { it.t.toLocalDate() == day && it.t.isBefore(inMin) }?.sortedBy { it.t }.orEmpty()
        if (ix.isNotEmpty()) {
            val at = ix.last().c
            val from = inMin.minusMinutes(LOOK_BACK)
            val back = ix.lastOrNull { !it.t.isAfter(from) }
            // An entry within the first minutes: from the session's first price.
            val base = back?.c ?: ix.first().o
            val mins = if (back != null) LOOK_BACK else Duration.between(ix.first().t, inMin).toMinutes()
            if (base > 0 && mins > 0) {
                move = at - base; moveMin = mins
                side = when {
                    abs(at - base) / base * 100 < FLAT_PCT -> Side.FLAT
                    ln == null -> null
                    (at > base) == (ln == Lean.UP) -> Side.WITH
                    else -> Side.AGAINST
                }
            }
        }
        val outMin = trip.closedAt.truncatedTo(ChronoUnit.MINUTES)
        val until = outMin.plusMinutes(AFTER)
        val after = option.filter { it.t.isAfter(outMin) && !it.t.isAfter(until) && !it.t.isAfter(now) && it.t.toLocalDate() == trip.closedAt.toLocalDate() }
        var best: Double? = null; var bestAt: LocalDateTime? = null; var window: Long? = null
        if (after.isNotEmpty()) {
            val c = if (trip.direction >= 0) after.maxByOrNull { it.h }!! else after.minByOrNull { it.l }!!
            best = if (trip.direction >= 0) c.h else c.l; bestAt = c.t
            window = Duration.between(outMin, after.maxOf { it.t }).toMinutes()
        }
        val held = Duration.between(trip.openedAt, trip.closedAt).seconds.coerceAtLeast(0)
        return Card(trip, m, ln, move, moveMin, side, held, usualSec, best, bestAt, window)
    }

    // ---- the answer -------------------------------------------------------------------------------------------------

    private fun px(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun ixPts(x: Double) = "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.2f".format(Locale.ENGLISH, abs(x))
    private fun amt(x: Double) = "Rs " + "%,.2f".format(Locale.ENGLISH, abs(x))
    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"

    /** A hold said in minutes ("under a minute", "1 min", "12 min"). */
    fun hold(sec: Long): String = if (sec < 60) "under a minute" else "${sec / 60} min"

    /** Where [held] sits against [usual]: under half, over twice, or near it. */
    private fun holdWord(held: Long, usual: Long): String = when {
        usual <= 0 -> "near"
        held * 2 < usual -> "under half"
        held > usual * 2 -> "over twice"
        else -> "near"
    }

    private fun opened(t: Trip) = if (t.direction >= 0) "bought" else "sold"

    /** One trade, one finished sentence. */
    fun line(c: Card): String {
        val t = c.trip
        val head = "${hm(t.openedAt)}-${hm(t.closedAt)} ${opened(t)} ${t.symbol} ${px(t.entry)} to ${px(t.exit)}, net ${rs(t.net)}"
        val parts = ArrayList<String>()
        val m = c.market
        parts += when {
            m == null || c.lean == null -> "not on an index, so no index direction"
            c.move == null || c.moveMin == null || c.side == null -> "${m.label}'s candles before the entry could not be read"
            else -> {
                val span = if (c.moveMin == LOOK_BACK) "in the $LOOK_BACK minutes before" else "from the open to the entry (${c.moveMin} min)"
                val did = if (c.move > 0) "had risen" else if (c.move < 0) "had fallen" else "had not moved"
                val pts = if (c.move != 0.0) " ${ixPts(c.move)} points" else ""
                "${m.label} $did$pts $span (" + when (c.side) { Side.WITH -> "with the trade"; Side.AGAINST -> "against the trade"; Side.FLAT -> "flat" } + ")"
            }
        }
        parts += "held ${hold(c.heldSec)}" + (c.usualSec?.let { " (your usual ${hold(it)})" } ?: "")
        val better = c.better
        parts += when {
            better == null -> "no candles of ${t.symbol} after the exit to read"
            c.beforeBest == true -> "within ${c.window} min after the exit it traded ${pts(better)} better your way (${px(c.best!!)} at ${hm(c.bestAt!!)}, ${amt(better * t.qty)} on ${t.qty})"
            else -> "nothing in the ${c.window} min after the exit beat it"
        }
        return "$head: ${parts.joinToString("; ")}."
    }

    /**
     * Today's scorecard for one account ([label] "Paper" or "Zerodha"): [book] its whole trade book (his usual hold is read
     * from it), [option] each contract's minute candles today by symbol, [index] each index's, up to [now].
     */
    fun lines(label: String, book: List<Trip>, today: LocalDate, option: Map<String, List<Candle>>, index: Map<Market, List<Candle>>,
              now: LocalDateTime): List<String> {
        val todays = book.filter { it.closedAt.toLocalDate() == today }
        val mine = todays.filter { own(it) }.sortedBy { it.closedAt }
        val bots = todays.size - mine.size
        val botNote = if (bots > 0) " (${plural(bots, "trade")} by the bots today, not scored here)" else ""
        if (mine.isEmpty()) return listOf("$label: no closed trades of your own today yet$botNote.")
        val read = mine.takeLast(MAX_TRADES)
        val us = usual(book, today)
        val cards = read.map { card(it, option[it.symbol].orEmpty(), marketOf(it.symbol)?.let { m -> index[m] }, us?.first, now) }
        val out = ArrayList<String>()
        out += "$label, your own trades today so far: ${plural(mine.size, "closed trade")}, net ${rs(mine.sumOf { it.net })}$botNote" +
            (if (mine.size > read.size) "; the newest ${read.size} are read." else ".")
        // The index at the entry.
        val sided = cards.filter { it.side != null }
        if (sided.isEmpty()) out += "The index before each entry could not be read."
        else {
            val against = sided.count { it.side == Side.AGAINST }; val with = sided.count { it.side == Side.WITH }; val flat = sided.count { it.side == Side.FLAT }
            out += "Index direction: $against of ${sided.size} taken against the index's move just before the entry, $with with it" +
                (if (flat > 0) ", $flat when it was flat" else "") + (if (sided.size < cards.size) " (${cards.size - sided.size} could not be read)" else "") + "."
        }
        // The hold.
        if (us == null) out += "Hold: too few of your own trades before today (under $MIN_USUAL) to say your usual hold."
        else {
            val under = cards.count { holdWord(it.heldSec, us.first) == "under half" }
            val over = cards.count { holdWord(it.heldSec, us.first) == "over twice" }
            out += "Hold: your usual is ${hold(us.first)} (the median of ${us.second} of your trades before today); today $under held under half of it, " +
                "$over over twice it, ${cards.size - under - over} near it."
        }
        // The exits.
        val seen = cards.filter { it.better != null }
        if (seen.isEmpty()) out += "Exits: no candles after the exits to read."
        else {
            val before = seen.count { it.beforeBest == true }
            out += "Exits: $before of ${seen.size} came before a better price within $AFTER minutes, ${seen.size - before} at or past anything in the $AFTER minutes after" +
                (if (seen.size < cards.size) " (${cards.size - seen.size} without candles after)" else "") + "."
        }
        out += cards.map { line(it) }
        return out
    }
}
