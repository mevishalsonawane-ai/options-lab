package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a closed Liquidity 15+5 trade looks like against its research (Boss, 06 Oct 2026): after each trade closes, Jarvis
 * says in one or two plain sentences whether it was a normal win or loss for this arm, so Boss learns what normal looks
 * like - "A normal loss: the index stop hit after 14 min - 30% of Liquidity's losers end this way ..." or "A big win: +Rs
 * 4,200 a lot is in the top 10% of its wins ...". Also the one wrap-up line for the day's Liquidity trades ([day]).
 *
 * The research is pinned below (never recomputed on the phone): the losing-trades study of Liquidity 15+5 under its new
 * rules (BANKNIFTY 15 + 5 min and FINNIFTY 30 + 5 min books, 05 Oct 2021 - 05 Oct 2026, 1,651 trades of one lot: 600
 * winners, 1,051 losers after charges). Facts about the trade and the record only - never advice: nothing here suggests
 * changing a rule, a size or a switch, and nothing is placed, changed or closed. Pure.
 */
object TradeLesson {
    // ---- the research, pinned -------------------------------------------------------------------------------------
    // Source (read-only, the losing-trades study): scratchpad/losers/out/forensics_tagged.parquet (fam == "Liquidity 15+5
    // (new)", net after charges, one lot of the lot size of the day), its loser groups in loser_groups.csv and the hindsight
    // totals in hindsight.csv. Rupees are per lot; premium points per unit.

    /** Trades in the study: winners and losers (after charges). */
    const val WINNERS = 600
    const val LOSERS = 1051

    /** Each exit reason's share (%) of the winners and of the losers. */
    internal val WIN_SHARE = mapOf("next_liquidity" to 52.0, "time_stop" to 24.0, "new_liquidity" to 11.2, "session_end" to 9.2,
        "index_stop" to 2.0, "failed_break" to 1.7, "stop" to 0.0)
    internal val LOSS_SHARE = mapOf("index_stop" to 30.4, "failed_break" to 24.8, "time_stop" to 24.1, "stop" to 16.9,
        "next_liquidity" to 2.0, "new_liquidity" to 1.4, "session_end" to 0.3)

    /** Winners' net per lot at the 10th, 25th, 50th, 75th and 90th percentile (Rs). */
    internal val WIN_PCT = doubleArrayOf(101.0, 330.0, 689.0, 1842.0, 4078.0)
    /** Losers' net per lot at the 10th, 25th, 50th, 75th and 90th percentile (Rs; the 10th is the worst). */
    internal val LOSS_PCT = doubleArrayOf(-1471.0, -938.0, -554.0, -300.0, -157.0)

    /** Median minutes held: winners, losers. */
    const val WIN_HOLD = 20
    const val LOSS_HOLD = 10

    /** Loser groups' shares (%) of the losers (loser_groups.csv: G2 to G6). */
    const val G_CHARGES = 3.9        // G2: up before charges, the charges ate the small win
    const val G_WRONG_ALL_DAY = 6.4  // G3: never green, wrong way from the start and all day
    const val G_TURNED = 16.4        // G4: never green, stopped, then the market turned our way after the exit
    const val G_GAVE_BACK = 25.8     // G5: green by Rs 500 a lot or more, then gave it back
    const val G_BRIEF = 47.6         // G6: briefly green (under Rs 500 a lot), then lost
    /** The study's line between "gave it back" and "briefly green": the best net (after charges) per lot, Rs. */
    const val GREEN_BIG = 500.0

    /** An exit reason sharing less than this (%) of its kind (winners or losers) is unusual for it. */
    const val RARE = 5.0

    // ---- a trade ---------------------------------------------------------------------------------------------------

    /**
     * One closed trade of the arm: [book] its book ("BANKNIFTY 15-min"), [right] "CE"/"PE", the premium at [entry] and
     * [exit], [why] the exit reason as booked, [heldMin] minutes held, [netPerLot] its P&L per lot after charges,
     * [chargesPerLot] the charges per lot, [lotSize] units a lot; [best]/[worst] the best and worst premium seen while held
     * (null: not recorded - an entry from before it was kept).
     */
    data class Closed(
        val book: String, val right: String, val entry: Double, val exit: Double, val why: String, val heldMin: Long,
        val netPerLot: Double, val chargesPerLot: Double, val lotSize: Int, val best: Double? = null, val worst: Double? = null,
    ) {
        /** Max favourable excursion in premium points (the exit counts as seen), or null when not recorded. */
        val mfe: Double? get() = best?.let { maxOf(it, exit, entry) - entry }
        /** Max adverse excursion in premium points (the exit counts as seen), or null when not recorded. */
        val mae: Double? get() = worst?.let { entry - minOf(it, exit, entry) }
    }

    enum class Size { BIG_WIN, GOOD_WIN, NORMAL_WIN, SMALL_WIN, SMALL_LOSS, NORMAL_LOSS, LARGE_LOSS, BIG_LOSS }

    /** The study's loser groups as the close can tell them (G3 and G4 differ only after the exit: both are [NEVER_GREEN]). */
    enum class Group { CHARGES_ATE_WIN, NEVER_GREEN, GAVE_BACK, BRIEFLY_GREEN }

    /**
     * [text] the one or two sentences, [short] its first alone (brief answers, saving battery); [unusual] what was unusual,
     * in a few words (null: a normal trade).
     */
    data class Lesson(val text: String, val size: Size, val group: Group?, val unusual: String?, val win: Boolean, val short: String = text)

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun pct(x: Double): String = if (x < 1.0) "under 1%" else "${x.roundToInt()}%"

    /** The size bucket of [netPerLot] against its kind's percentiles. */
    fun size(netPerLot: Double): Size = if (netPerLot > 0) when {
        netPerLot >= WIN_PCT[4] -> Size.BIG_WIN
        netPerLot >= WIN_PCT[3] -> Size.GOOD_WIN
        netPerLot >= WIN_PCT[1] -> Size.NORMAL_WIN
        else -> Size.SMALL_WIN
    } else when {
        netPerLot <= LOSS_PCT[0] -> Size.BIG_LOSS
        netPerLot <= LOSS_PCT[1] -> Size.LARGE_LOSS
        netPerLot >= LOSS_PCT[3] -> Size.SMALL_LOSS
        else -> Size.NORMAL_LOSS
    }

    private fun sizeHead(s: Size) = when (s) {
        Size.BIG_WIN -> "A big win"; Size.GOOD_WIN -> "A good win"; Size.NORMAL_WIN -> "A normal win"; Size.SMALL_WIN -> "A small win"
        Size.SMALL_LOSS -> "A small loss"; Size.NORMAL_LOSS -> "A normal loss"; Size.LARGE_LOSS -> "A larger loss than usual"; Size.BIG_LOSS -> "A big loss"
    }

    private fun sizeWhere(s: Size) = when (s) {
        Size.BIG_WIN -> "in the top 10% of its wins"
        Size.GOOD_WIN -> "in the top quarter of its wins"
        Size.NORMAL_WIN -> "near its usual win (the middle win is ${rs(WIN_PCT[2])})"
        Size.SMALL_WIN -> "in the smallest quarter of its wins"
        Size.SMALL_LOSS -> "in the smallest quarter of its losses"
        Size.NORMAL_LOSS -> "near its usual loss (the middle loss is ${rs(LOSS_PCT[2])})"
        Size.LARGE_LOSS -> "in the worst quarter of its losses"
        Size.BIG_LOSS -> "among the worst 10% of its losses"
    }

    /** The loser group of a losing trade, or null for a winner or when the best price seen was not recorded. */
    fun group(c: Closed): Group? {
        if (c.netPerLot > 0) return null
        val gross = c.netPerLot + c.chargesPerLot
        if (gross >= 0) return Group.CHARGES_ATE_WIN
        val mfe = c.mfe ?: return null
        val bestNet = mfe * c.lotSize - c.chargesPerLot
        return when {
            bestNet < 0 -> Group.NEVER_GREEN
            bestNet >= GREEN_BIG -> Group.GAVE_BACK
            else -> Group.BRIEFLY_GREEN
        }
    }

    private fun groupWords(g: Group) = when (g) {
        Group.CHARGES_ATE_WIN -> "it was up before charges and the charges ate the small win - ${pct(G_CHARGES)} of losers are this kind"
        Group.NEVER_GREEN -> "it was never green after charges (the 'wrong way from the start' kind, ${pct(G_WRONG_ALL_DAY + G_TURNED)} of losers: " +
            "${pct(G_WRONG_ALL_DAY)} stayed wrong all day, ${pct(G_TURNED)} turned our way after the stop)"
        Group.GAVE_BACK -> "it was green by Rs ${GREEN_BIG.toInt()} a lot or more, then gave it back - ${pct(G_GAVE_BACK)} of losers are this kind"
        Group.BRIEFLY_GREEN -> "it was only briefly green (under Rs ${GREEN_BIG.toInt()} a lot), then lost - ${pct(G_BRIEF)} of losers, the most common kind"
    }

    /** How the exit reads ("the index stop hit"), or null for an exit the research has no record of (Boss's own close...). */
    private fun reasonWords(why: String): String? = when (why) {
        "index_stop" -> "the index stop hit"
        "failed_break" -> "a failed break closed it"
        "time_stop" -> "the 20-minute time stop closed it"
        "stop" -> "the 15% premium stop hit"
        "next_liquidity" -> "it reached the next liquidity level"
        "new_liquidity" -> "new liquidity formed on its side"
        "session_end" -> "the 15:10 square-off closed it"
        else -> null
    }

    private fun closedBy(why: String) = when (why) {
        "operator_stop" -> "it was closed when you stopped the bots for the day"
        "closed_by_you" -> "you closed it yourself"
        "backstop_square_off" -> "the app's backstop square-off closed it"
        else -> "it closed (${why.replace('_', ' ')})"
    }

    private fun mins(m: Long) = "$m min"

    /** The lesson of one closed trade. */
    fun of(c: Closed): Lesson {
        val win = c.netPerLot > 0
        val s = size(c.netPerLot)
        val g = group(c)
        val share = (if (win) WIN_SHARE else LOSS_SHARE)[c.why]
        val kind = if (win) "winners" else "losers"
        val reason = reasonWords(c.why)
        val unusual = mutableListOf<String>()
        if (s == Size.BIG_WIN) unusual += "a big win, top 10% of its wins"
        if (s == Size.BIG_LOSS) unusual += "a big loss, worst 10% of its losses"
        if (reason == null) unusual += "closed outside the arm's own exits"
        else if (share != null && share < RARE) unusual += "${if (win) "a win" else "a loss"} by ${c.why.replace('_', ' ')}, rare for its $kind"
        val head = sizeHead(s)
        val exitPart = if (reason == null) "${closedBy(c.why)} after ${mins(c.heldMin)} - the research has no such exits to compare"
        else "$reason after ${mins(c.heldMin)} - ${share?.let { pct(it) } ?: "under 1%"} of Liquidity's $kind end this way " +
            "(their middle hold is ${if (win) WIN_HOLD else LOSS_HOLD} min)"
        val (first, second) = if (win) {
            val best = c.mfe?.let { m -> (m * c.lotSize - c.chargesPerLot).takeIf { it > c.netPerLot + 1 } }
                ?.let { "; at its best it was ${rs(it)} a lot" }.orEmpty()
            "$head: ${rs(c.netPerLot)} a lot is ${sizeWhere(s)}." to "${exitPart.replaceFirstChar { it.uppercase(Locale.ENGLISH) }}$best."
        } else {
            "$head: $exitPart." to (g?.let { "At ${rs(c.netPerLot)} a lot it is ${sizeWhere(s)}; ${groupWords(it)}." }
                ?: "At ${rs(c.netPerLot)} a lot it is ${sizeWhere(s)}; how far it went our way while held was not recorded.")
        }
        val text = "$first $second"
        return Lesson(text, s, g, unusual.firstOrNull(), win, first)
    }

    // ---- the day ---------------------------------------------------------------------------------------------------

    private val WORDS = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    private fun count(n: Int) = WORDS.getOrNull(n) ?: n.toString()

    /**
     * The wrap-up's one line on the day's Liquidity trades in research terms ("Liquidity 15+5 in research terms: 2 trades,
     * both normal; nothing unusual."), or null when it closed none today.
     */
    fun day(lessons: List<Lesson>): String? {
        if (lessons.isEmpty()) return null
        val n = lessons.size
        val odd = lessons.mapNotNull { it.unusual }
        val wins = lessons.count { it.win }
        val tally = "$n trade${if (n == 1) "" else "s"} ($wins won, ${n - wins} lost)"
        val body = when {
            odd.isEmpty() && n == 1 -> "$tally, normal for it; nothing unusual."
            odd.isEmpty() && n == 2 -> "$tally, both normal; nothing unusual."
            odd.isEmpty() -> "$tally, all normal; nothing unusual."
            odd.size == n -> "$tally, ${if (n == 1) "unusual" else "all unusual"}: ${odd.joinToString("; ")}."
            else -> "$tally: ${count(n - odd.size)} normal, ${count(odd.size)} unusual (${odd.joinToString("; ")})."
        }
        return "Liquidity 15+5 in research terms: $body"
    }
}
