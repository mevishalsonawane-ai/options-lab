package com.optionslab.ira

import com.optionslab.engine.orb.HeroTicketRules
import java.util.Locale

/**
 * Jarvis on the "Hero ₹1,000 ticket" (15 Oct 2026, Boss's choice; it did not pass research R50 - [HeroTicketRules]): "how is
 * the hero ticket doing", "what did the 1000 ticket do today", "hero ticket kaisa chal raha hai". Answered from the arm's own
 * book: its switch (paper only), the exit Boss picked, today's tickets with their P&L, each index's last word, and its own
 * record - always with the label that it did not pass research. Read only: nothing here switches, buys, sells or sets
 * anything; a question said with something to do ("switch off the hero ticket") is not read here. Pure.
 */
object HeroTicketTalk {
    const val LOCKED = "Unlock the phone for that, Boss."

    private val TICKET = Regex(" (ticket|tickets|tikat|tikit) ")
    private val HERO = Regex(" (hero|heros) ")
    private val THOUSAND = Regex(" (1000|1 000|thousand|hazaar|hazar|hajar) (rupee |rupees |rs |inr |ka |ke |wala |vala |wale )?(ticket|tickets|tikat|tikit) ")
    /** Words of doing: such a sentence is an action (or a setting), never this reader's question. */
    private val ACT = Regex(" (switch|switched|turn|band|chalu|enable|disable|set|change|buy|sell|close|cancel|square|start|stop|pause|resume|arm|disarm) ")
    /** Another ticket: the expiry ticket of the Ledger and the like. */
    private val OTHER = Regex(" (expiry ticket|ledger|put ticket|order ticket) ")

    /** Is the Hero ₹1,000 ticket asked about? */
    fun asked(text: String): Boolean {
        val t = Spaced.joined(text)
        if (!TICKET.containsMatchIn(t) || ACT.containsMatchIn(t) || OTHER.containsMatchIn(t)) return false
        return HERO.containsMatchIn(t) || THOUSAND.containsMatchIn(t)
    }

    /** One ticket: [net] after charges once sold, [mark] its last price while held. */
    data class Ticket(val index: String, val symbol: String, val qty: Int, val entry: Double, val exit: Double? = null,
                      val why: String? = null, val net: Double? = null, val mark: Double? = null)

    /**
     * What the answer reads: [armed] its switch (null: not read), [target] the exit Boss picked, [today] today's tickets,
     * [status] each index's last word today, [closed] / [net] its own record (every closed ticket, after charges).
     */
    data class Facts(val armed: Boolean?, val target: HeroTicketRules.Target, val today: List<Ticket>, val status: Map<String, String>,
                     val closed: Int, val net: Double)

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x)

    private fun exitWords(target: HeroTicketRules.Target): String = when (target) {
        HeroTicketRules.Target.TRAIL_ONLY -> "your 25% trail, else 15:05 (no fixed target)"
        else -> "a ${target.words} or your 25% trail, whichever comes first, else 15:05"
    }

    private fun whyWords(why: String?): String = when (why) {
        HeroTicketRules.ExitKind.TRAIL.why -> "on the 25% trail"
        HeroTicketRules.ExitKind.TARGET.why -> "at its target"
        HeroTicketRules.ExitKind.TIME.why -> "at 15:05"
        null -> "sold"
        else -> "closed elsewhere ($why)"
    }

    /** The answer, plain: the switch and the label first, today's tickets, then its own record. */
    fun answer(f: Facts): String = buildString {
        append("${HeroTicketRules.LABEL}: ")
        append(when (f.armed) { true -> "on, paper only"; false -> "off"; null -> "its switch could not be read just now" })
        append(". ${HeroTicketRules.RECORD}. ")
        append("Exit: ${exitWords(f.target)}. ")
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
        append(" Its own record: ")
        append(if (f.closed == 0) "no closed ticket yet" else "${f.closed} ticket${if (f.closed == 1) "" else "s"}, ${rs(f.net)} after charges")
        append(", kept apart from the arms that passed and from the plain-rules record for 26 Oct. ")
        append("${HeroTicketRules.MEASURED}. ${HeroTicketRules.SIGNAL_SOURCE}.")
    }
}
