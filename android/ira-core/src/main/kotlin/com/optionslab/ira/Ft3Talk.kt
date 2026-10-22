package com.optionslab.ira

import com.optionslab.engine.orb.Ft3Rules
import java.util.Locale

/**
 * Jarvis on "FT3 near-ATM morning" (21 Oct 2026, Boss's choice; the R98 forward test that did not pass research - [Ft3Rules]):
 * "how is FT3 doing", "what did ft3 do today", "ft3 kaisa chal raha hai", "how is forward test 3". Answered from the arm's
 * own book: its switch (paper; live only the standard way), today's ticket per index with its P&L, each index's last word, and
 * its own record (the forward test) - always with the label that it did not pass research. Read only: nothing here switches,
 * buys, sells or sets anything; a question said with something to do ("switch off ft3") is not read here. Pure.
 */
object Ft3Talk {
    /** Its names only: "forward test" alone could mean another arm's forward bar. */
    private val FT3 = Regex(" (ft3|ft 3|ft three|f t 3|forward test 3|forward test three|near atm morning) ")
    /** Words of doing: such a sentence is an action (or a setting), never this reader's question. */
    private val ACT = Regex(" (switch|switched|turn|band|chalu|enable|disable|set|change|buy|sell|close|cancel|square|start|stop|pause|resume|arm|disarm) ")
    /** Another forward test (FT1, FT2, FT4) or a forward check of another arm. */
    private val OTHER = Regex(" (ft1|ft2|ft4|ft 1|ft 2|ft 4|forward check) ")

    /** Is FT3 asked about? */
    fun asked(text: String): Boolean {
        val t = Spaced.joined(text)
        if (ACT.containsMatchIn(t) || OTHER.containsMatchIn(t)) return false
        return FT3.containsMatchIn(t)
    }

    /** One ticket: [net] after charges once sold, [mark] its last price while held. */
    data class Ticket(val index: String, val symbol: String, val qty: Int, val entry: Double, val exit: Double? = null,
                      val why: String? = null, val net: Double? = null, val mark: Double? = null)

    /**
     * What the answer reads: [armed] its switch (null: not read), [today] today's tickets, [status] each index's last word today,
     * [closed] / [net] / [won] its own record (every closed ticket, after charges; [won] of them above 0).
     */
    data class Facts(val armed: Boolean?, val today: List<Ticket>, val status: Map<String, String>, val closed: Int, val net: Double,
                     val won: Int = 0)

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x)

    private fun whyWords(why: String?): String = when (why) {
        Ft3Rules.EXIT_WHY -> "at 15:05"
        "operator_stop" -> "on your stop for today"
        null -> "sold"
        else -> "closed elsewhere ($why)"
    }

    /**
     * 21 Oct (review): said after its shadow / live line - those trades are at most Boss's lot cap, smaller than the paper ticket,
     * so their charges weigh more and their rupees are not the paper record's.
     */
    const val SIZE_NOTE = " Its shadow and live trades are at most your lot cap (all its lots with the cap off), smaller than " +
        "the ₹5,000 paper ticket, so charges weigh more there: judge them by their own record, not the paper rupees."

    /** The answer, plain: the switch and the label first, today's tickets, then its own record and how it is judged. */
    fun answer(f: Facts): String = buildString {
        append("${Ft3Rules.LABEL}: ")
        append(when (f.armed) {
            true -> "on, on paper (live only the standard way: Live with your PIN, \"AI trades go live\", its Trade live switch, Shadow off; " +
                "at most your lot cap)"
            false -> "off"
            null -> "its switch could not be read just now"
        })
        append(". ${Ft3Rules.RECORD}. ")
        if (f.today.isEmpty()) {
            val words = f.status.toSortedMap().entries.joinToString("; ") { (k, v) -> "$k $v" }
            append(if (words.isEmpty()) "No ticket today." else "No ticket today ($words).")
        } else {
            append("Today: ")
            append(f.today.joinToString("; ") { t ->
                if (t.exit == null) "${t.symbol} ${t.qty} @ ${px(t.entry)}" + (t.mark?.let { m -> ", now ${px(m)} (${rs((m - t.entry) * t.qty)} before charges)" } ?: ", held")
                else "${t.symbol} bought @ ${px(t.entry)}, sold @ ${px(t.exit)} ${whyWords(t.why)}, ${rs(t.net ?: 0.0)} after charges"
            })
            append(".")
        }
        append(" The forward test so far: ")
        append(if (f.closed == 0) "no closed ticket yet" else "${f.closed} ticket${if (f.closed == 1) "" else "s"}, ${f.won} won, ${rs(f.net)} after charges")
        append(". ${Ft3Rules.JUDGED}. ${Ft3Rules.MEASURED}. ${Ft3Rules.SIGNAL_SOURCE}.")
    }
}
