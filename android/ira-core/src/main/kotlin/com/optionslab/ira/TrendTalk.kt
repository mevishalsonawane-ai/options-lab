package com.optionslab.ira

import com.optionslab.engine.trend.TrendWatch

/**
 * Jarvis on the Trend watch (16 Oct 2026, Boss: "check the current trend, and if the trend changes, sell the orders";
 * research R63): "has the trend turned on my trades?", "did the trend change against my positions", "trend watch",
 * "kya trend badla". Answered from the watch's own log and its last round: each turn today (source, contract, rule,
 * time, what was done), what is watched now and each one's state, and the plain rule - only the Hero ticket's paper legs
 * are sold on a turn (R63's one pass); everything else is a note; live never acts. Read only: a sentence with something to
 * do ("switch off the trend watch", "sell") is not read here. Its lines name positions and premiums, so the caller never
 * answers on a locked phone ([LOCKED]). Pure.
 */
object TrendTalk {
    const val LOCKED = "Unlock the phone for that, Boss."

    private val ACT = Regex(" (switch|switched|turn on|turn off|enable|disable|set|buy|sell|close|cancel|square|start|stop|pause|resume|undo|exit) ")
    private val TREND = Regex(" (trend|trends|supertrend) ")
    private val TURNED = Regex(" (turned|turn|turning|changed|change|changing|flipped|flip|reversed|reverse|badla|badal|palta|against) ")
    private val MINE = Regex(" (my|mere|meri|mera|open|held|trades|trade|positions|position|orders|order|arms|tickets) ")
    private val WATCH = Regex(" trend watch ")

    /** Whether [text] asks about the Trend watch. */
    fun asked(text: String): Boolean {
        val t = Spaced.joined(text)
        if (WATCH.containsMatchIn(t)) return !Regex(" (switch|turn on|turn off|enable|disable) ").containsMatchIn(t)
        if (ACT.containsMatchIn(t)) return false
        return TREND.containsMatchIn(t) && TURNED.containsMatchIn(t) && MINE.containsMatchIn(t)
    }

    /** One turn today, as the watch logged it. [act]: SELL (a Hero leg asked to be sold on paper), NOTE, or PENDING. */
    data class Turn(val line: String, val act: String, val live: Boolean)

    /** One position watched now: its source, contract, rule and state ("trend holding", "turned at 11:05", "warming up"). */
    data class Held(val source: String, val symbol: String, val rule: String, val state: String, val live: Boolean)

    data class Facts(val on: Boolean, val live: Boolean, val turns: List<Turn>, val held: List<Held>)

    /** The answer. */
    fun answer(f: Facts): String {
        if (!f.on) return "The Trend watch is off, Boss: no position is watched for a trend turn. Switch it on in Strategies → Trend watch."
        val sb = StringBuilder()
        val notes = f.turns.filter { it.act != "PENDING" }
        val pending = f.turns.filter { it.act == "PENDING" }
        val sold = notes.count { it.act == TrendWatch.Act.SELL.name }
        when {
            notes.isEmpty() && f.held.isEmpty() -> sb.append("Nothing is open to watch, Boss, and the trend has not turned against anything today.")
            notes.isEmpty() -> sb.append("No, Boss: the trend has not turned against any of your ${f.held.size} open position${if (f.held.size == 1) "" else "s"} today.")
            else -> sb.append("Yes, Boss: the trend turned against ${notes.size} position${if (notes.size == 1) "" else "s"} today" +
                (if (sold > 0) " - $sold Hero ticket leg${if (sold == 1) " was" else "s were"} sold on paper." else " - notes only, nothing was sold."))
        }
        for (t in notes.takeLast(6)) sb.append("\n• ").append(t.line)
        if (pending.isNotEmpty()) {
            sb.append("\nPending orders the trend turned against (a note only; nothing cancelled):")
            for (t in pending.takeLast(3)) sb.append("\n• ").append(t.line)
        }
        if (f.held.isNotEmpty()) {
            sb.append("\nWatched now:")
            for (h in f.held.take(8)) sb.append("\n• ${h.source} ${h.symbol}${if (h.live) " (Zerodha)" else ""}: ${h.rule} - ${h.state}")
        }
        sb.append("\n").append(RULE)
        if (!f.live) sb.append(" Zerodha positions are not watched (\"Live positions too\" is off).")
        return sb.toString()
    }

    const val RULE = "How it works (research R63): every rule reads the index (or the stock / MCX future), never the option. Only the " +
        "Hero ₹1,000 ticket's paper legs are sold on a turn (T3, the one rule that passed: +₹14,730 on 74 changed tickets in the " +
        "final test); for every other strategy the trend exit lost against random exits, so those are notes only. Live never acts."
}
