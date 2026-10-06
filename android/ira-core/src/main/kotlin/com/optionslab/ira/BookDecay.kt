package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * What time decay does to Boss's whole book (usefulness round 29, 2026-10-05): "what's my theta?", "how much am I losing
 * to time decay?", "is theta working for me or against me?", "how much decay over the weekend on my positions?", "mera
 * theta kitna hai", "decay se kitna nuksan ho raha hai".
 *
 * [PositionHealth] says each position's decay one by one, inside a long check; nothing put the book together. Here, from
 * each open option leg's theta now (paper, and Zerodha when logged in): the rupees a day the whole book gains or loses to
 * time decay, for each account apart (paper is never added to real money), what the bought legs pay and the sold legs
 * collect, the legs that weigh most, what at today's theta comes to by the next session when that is more than a day away
 * (a weekend, a holiday), and - for a leg that expires today - the time value still in it, which is gone by 15:30.
 *
 * A rough figure from theta now with spot and IV held still, said so; never a forecast and never what to do. Nothing here
 * places, changes or closes anything. Boss's account, so never on a locked phone. Pure.
 */
object BookDecay {
    const val LOCKED = "Your positions stay out of it on a locked phone, Boss - unlock it for that."

    /** At most this many legs are named as weighing most. */
    const val TOP = 3

    private fun rs(x: Double) = "Rs " + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun signed(x: Double) = (if (x < 0) "-" else "+") + rs(x)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun side(q: Int) = if (q < 0) "short ${-q}" else "long $q"

    /** One leg's decay a day in rupees on the position (theta per unit per calendar day times the signed quantity). */
    fun perDay(p: PositionHealth.Pos): Double? = p.theta?.takeIf { p.option && it.isFinite() }?.let { it * p.qty }

    /** The time value left on [p] in rupees on the position (positive), or null when spot or strike is not known. */
    fun timeValue(p: PositionHealth.Pos): Double? {
        val itm = p.itmBy ?: return null
        val tv = p.ltp - maxOf(0.0, itm)
        return maxOf(0.0, tv) * abs(p.qty)
    }

    /**
     * The answer. [ps]: Boss's open positions (Paper and Zerodha), each with its theta now when it could be worked out;
     * [now]: the time now; [nextSession]: the next trading day after today (null: not known); [zerodhaLoggedIn]: whether
     * Zerodha was there to read (logged out, only paper was read - said so).
     */
    fun answer(ps: List<PositionHealth.Pos>, now: LocalDateTime, nextSession: LocalDate?, zerodhaLoggedIn: Boolean): String =
        answer(ps, now, nextSession, if (zerodhaLoggedIn) SinceMorning.Zerodha.READ else SinceMorning.Zerodha.LOGGED_OUT)

    /**
     * As above, with [zerodha] saying whether Zerodha was read, logged out, or logged in but not read (review, 5 Oct: a
     * failed or timed-out read is never "no positions" - said so, and only paper is given).
     */
    fun answer(ps: List<PositionHealth.Pos>, now: LocalDateTime, nextSession: LocalDate?, zerodha: SinceMorning.Zerodha): String {
        val today = now.toLocalDate()
        val afterClose = !now.toLocalTime().isBefore(PositionHealth.CLOSE)
        val failed = zerodha == SinceMorning.Zerodha.FAILED
        // Never a Zerodha leg from a read that failed (none is passed then; this only makes sure).
        val open = ps.filter { it.qty != 0 && !(failed && it.where == "Zerodha") }
        val options = open.filter { it.option }
        val zerodhaNote = when (zerodha) {
            SinceMorning.Zerodha.READ -> ""
            SinceMorning.Zerodha.LOGGED_OUT -> " Zerodha is not logged in today, so only the paper account was read."
            SinceMorning.Zerodha.FAILED -> " I could not read Zerodha just now, Boss, so only the paper account is given - ask me again in a moment."
        }
        if (options.isEmpty()) {
            val other = open.size
            if (failed) return "I could not read Zerodha just now, Boss, so I cannot say what your live book loses to time decay - ask me again in a moment. " +
                (if (other == 0) "Your paper account has no open option positions." else "None of your open paper positions is an option, so they have no time decay.")
            return (if (other == 0) "You have no open option positions, Boss - nothing in your book is decaying."
                else if (other == 1) "Your open position is not an option, Boss - it has no time decay."
                else "None of your $other open positions is an option, Boss - they have no time decay.") + zerodhaNote
        }
        // Expired or past today's close: no decay left to speak of.
        val live = options.filter { p -> p.expiry.let { e -> e == null || e.isAfter(today) || (e == today && !afterClose) } }
        val expiringToday = live.filter { it.expiry == today }
        val later = live.filter { it.expiry != today }
        val parts = ArrayList<String>()

        val read = later.filter { perDay(it) != null }
        val unread = later.size - read.size
        if (read.isNotEmpty()) {
            val byWhere = read.groupBy { it.where }
            val order = (listOf("Zerodha", "Paper") + byWhere.keys).distinct().filter { it in byWhere }
            for (w in order) {
                val legs = byWhere.getValue(w)
                val net = legs.sumOf { perDay(it)!! }
                val paid = legs.filter { it.qty > 0 }.sumOf { perDay(it)!! }
                val got = legs.filter { it.qty < 0 }.sumOf { perDay(it)!! }
                val head = when {
                    abs(net) < 1 -> "On $w your option legs are about flat on time decay"
                    net < 0 -> "On $w your options lose about ${rs(net)} a day to time decay"
                    else -> "On $w your options gain about ${rs(net)} a day from time decay"
                }
                val split = if (paid < -0.5 && got > 0.5) " (the bought legs pay ${rs(paid)}, the sold legs collect ${rs(got)})" else ""
                parts += "$head$split."
            }
            val top = read.sortedByDescending { abs(perDay(it)!!) }.take(TOP)
            if (read.size > 1) parts += "Weighing most: " + top.joinToString("; ") { "${it.where} ${it.symbol} ${side(it.qty)}, ${signed(perDay(it)!!)} a day" } + "."
            // A weekend or a holiday before the next session: the calendar days at today's theta.
            val gap = nextSession?.let { ChronoUnit.DAYS.between(today, it) } ?: 0L
            if (gap > 1) {
                val carried = read.filter { p -> p.expiry.let { e -> e == null || !e.isBefore(nextSession) } }
                val byW = carried.groupBy { it.where }
                val words = (listOf("Zerodha", "Paper") + byW.keys).distinct().filter { it in byW }.map { w ->
                    val n = byW.getValue(w).sumOf { perDay(it)!! } * gap
                    "${signed(n)} on $w"
                }
                if (words.isNotEmpty()) parts += "Till the next session on ${nextSession!!.format(DAY)} ($gap calendar days), at today's theta that is about " +
                    words.joinToString(" and ") + " - though the market often prices a weekend or a holiday in before it comes, so some of it may already be in today's prices."
            }
        }
        if (unread > 0) parts += "${if (unread == 1) "One option leg's" else "$unread option legs'"} theta could not be worked out just now (its index price was not known), so ${if (unread == 1) "it is" else "they are"} left out."

        for (p in expiringToday) {
            val tv = timeValue(p)
            val whose = if (p.qty > 0) "yours to lose" else "yours to keep as the seller"
            parts += if (tv == null) "${p.where} ${p.symbol} ${side(p.qty)} expires today: whatever time value is left in it goes by 15:30 ($whose); its index price is not known just now, so not how much."
                else if (tv < 1) "${p.where} ${p.symbol} ${side(p.qty)} expires today with next to no time value left in it."
                else "${p.where} ${p.symbol} ${side(p.qty)} expires today with about ${rs(tv)} of time value left on the position - gone by 15:30 ($whose)."
        }
        if (parts.isEmpty()) parts += "Your option legs have expired or the session is over for them, Boss - no time decay is left to count."
        val nonOptions = open.size - options.size
        if (nonOptions > 0) parts += "${if (nonOptions == 1) "One position is" else "$nonOptions positions are"} not an option and ${if (nonOptions == 1) "has" else "have"} no time decay."
        parts += "A rough figure from each option's theta now, spot and IV held still - a move in the index or in IV outweighs it on most days. Nothing was changed."
        return "Boss: " + parts.joinToString(" ") + zerodhaNote
    }

    // ---- asked -------------------------------------------------------------------------------------------------

    private fun norm(text: String) = Spaced.joined(text)

    private const val DECAY = "(theta|time decay|decay|thetas|time value decay)"
    private const val MINE = "(my|our|mera|meri|mere|hamara|hamari|hamare)"
    private const val BOOK = "(book|books|positions?|portfolio|options?|option positions?|trades?|legs?|puts?|calls?|nifty puts?|nifty calls?|banknifty puts?|banknifty calls?|bank nifty puts?|bank nifty calls?|open positions?|straddles?|strangles?|spreads?|iron condors?|condors?|iron flys?|butterfly|butterflies)"

    private val ASK = rx(
        // "What's my theta?", "my net theta", "my total time decay", "my positions' theta", "my book's decay"
        " $MINE (net |total |overall |whole |daily |)($BOOK s |$BOOK |)(net |total |overall |)$DECAY " +
        // "theta on my positions", "time decay of my book", "decay on all my options"
        "| $DECAY (on|of|in|for|across) (all )?(of )?$MINE (whole |open |)$BOOK " +
        // "How much am I losing to time decay?", "how much do I pay in theta a day?", "how much are my positions bleeding to decay?"
        "| how much (am i|do i|will i|would i|are we|are my positions|is my book|are my options|does my book|do my positions|do my options) (losing|lose|paying|pay|making|make|earning|earn|collecting|collect|bleeding|bleed|getting|get|giving up|give up) (to|from|in|on|through|by|with) $DECAY " +
        "| how much $DECAY (am i|do i|will i|are we|is my book|are my positions) (paying|pay|losing|lose|earning|earn|collecting|collect|making|make|getting|get|bleeding|bleed) " +
        // "Am I paying theta?", "am I collecting decay?", "is theta working for me or against me?", "am I long or short theta?"
        "| (am i|are we) (paying|losing|bleeding|collecting|earning|making|getting) $DECAY " +
        "| is $DECAY (working )?(for|against) (me|us) " +
        // Round 22: "what's theta doing for me?", "is theta on my side?", "am I theta positive?", "how much theta does my strangle make?"
        "| (whats|what is) $DECAY (doing|giving|making|costing|paying) (for |to )?(me|us) " +
        "| is $DECAY on (my|our) side " +
        "| (am i|are we|is my book|are my positions) (net )?(theta|decay) (positive|negative) " +
        "| how much $DECAY (does|do|will) $MINE $BOOK (make|earn|collect|lose|pay|bleed|give up) " +
        "| (am i|are we|is my book|are my positions) (net )?(long |short |long or short |short or long )(theta|time decay|decay|gamma and theta)( or (long|short) (theta|decay))? " +
        // "How much decay over the weekend on my positions?", "weekend theta on my book"
        "| (weekend|holiday|overnight) $DECAY (on|of|for|in) $MINE " +
        "| $DECAY (over|through|during|till|until|across) (the |this |a )?(long )?(weekend|holiday|holidays) (on|for|in|of) $MINE " +
        "| $MINE $BOOK (decay|lose to decay|bleed|bleed to decay) (over|through|during|till|until) (the |this |a )?(long )?(weekend|holiday|holidays) " +
        // Hinglish: "meri positions ka theta kitna hai", "time decay se kitna nuksan ho raha hai", "decay kitna kha raha hai"
        "| $MINE ($BOOK )?(ka|ki|ke|par|pe|mein|me) $DECAY " +
        "| $DECAY (se|me|mein|mai|ka|ki) (kitna|kitni|kitne) (nuksan|nuksaan|loss|kharcha|kamai|munafa|profit|paisa|paise|ja raha|jaa raha|ja rahi|kha raha|kat raha|ho raha|lag raha|mil raha|kama raha|kama rahe|kama rahi|kamaa raha|bana raha) " +
        "| $DECAY (kitna|kitni|kitne) (kha raha|kat raha|ho raha|lag raha|ja raha|jaa raha|mil raha|kha rahi|ho rahi|kama raha) ")

    /** Something else is meant: the word's meaning, a what-if, an order, a strategy's test, another day. */
    private val NOT = rx(" (what is theta|what is time decay|what does theta|what does time decay|meaning|mean by|define|explain theta|explain time decay|" +
        "kya hota hai|kya hai matlab|if|suppose|should|shall|recommend|suggest|advise|advice|buy|sell|close|exit|square|hedge|set|place|cancel|" +
        "strategy|strategies|backtest|backtested|yesterday|last week|last month|chain|option chain|your|jarvis s) ")

    /** "What's my theta?", "how much am I losing to time decay?", "is theta working for me?", "mera theta kitna hai". */
    fun asked(text: String): Boolean {
        for (s in listOf(text, Ask.reading(text))) {
            val t = norm(s)
            if (NOT.containsMatchIn(t)) continue
            if (ASK.containsMatchIn(t)) return true
        }
        return false
    }
}
