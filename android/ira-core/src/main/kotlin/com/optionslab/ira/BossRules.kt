package com.optionslab.ira

import java.util.Locale

/**
 * Rules in what Boss asked Jarvis to remember (part 4 of "an AGI for this app"): a kept note ([Memory]) such as "I
 * don't trade BankNifty", "skip expiry days", "no trades before 9:30" or "no new trades after 2 pm" is also a rule,
 * and a trade idea that breaks one is not offered or taken. Rules only ever hold trades back. Pure.
 */
object BossRules {
    enum class Kind { AVOID_MARKET, AVOID_EXPIRY, NOT_BEFORE, NOT_AFTER }

    data class Rule(val note: String, val kind: Kind, val market: Market? = null, val minute: Int? = null)

    private val TRADING = Regex(" (trade|trades|trading|buy|buying|enter|entry|entries|suggest|suggestions|ideas?) ")

    /** The rule in a kept note, or null when it holds none. */
    fun of(note: String): Rule? {
        val t = " " + note.lowercase(Locale.ENGLISH).replace("'", " ").replace(rx("[^a-z0-9: ]"), " ").replace(rx("\\s+"), " ").trim() + " "
        val avoid = rx(" (don t|dont|do not|never|no|avoid|skip|stay away from|not) ").containsMatchIn(t)
        fun minute(): Int? = rx(" (\\d{1,2})[:.](\\d{2}) ?(am|pm)? | (\\d{1,2}) ?(am|pm) ").find(t)?.let { m ->
            val pm = (m.groupValues[3].ifEmpty { m.groupValues[5] }) == "pm"
            var h = (m.groupValues[1].ifEmpty { m.groupValues[4] }).toInt(); val mm = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (pm && h < 12) h += 12
            if (!pm && m.groupValues[3].isEmpty() && m.groupValues[5].isEmpty() && h in 1..3) h += 12
            (h * 60 + mm).takeIf { h in 0..23 && mm in 0..59 }
        }
        val markets = Market.mentioned(note).filter { it != Market.VIX && it != Market.GOLD }
        return when {
            avoid && t.contains(" expiry") -> Rule(note, Kind.AVOID_EXPIRY)
            t.contains(" before ") && TRADING.containsMatchIn(t) -> minute()?.let { Rule(note, Kind.NOT_BEFORE, minute = it) }
            t.contains(" after ") && TRADING.containsMatchIn(t) -> minute()?.let { Rule(note, Kind.NOT_AFTER, minute = it) }
            avoid && markets.size == 1 && TRADING.containsMatchIn(t) -> Rule(note, Kind.AVOID_MARKET, market = markets.first())
            else -> null
        }
    }

    /** Why a trade idea on [market] breaks one of Boss's rules in [notes], or null. [minute]: minute of day now. */
    fun blocks(notes: List<String>, market: Market, expiryToday: Boolean, minute: Int): String? = notes.mapNotNull { of(it) }.firstNotNullOfOrNull { r ->
        val hit = when (r.kind) {
            Kind.AVOID_MARKET -> r.market == market
            Kind.AVOID_EXPIRY -> expiryToday
            Kind.NOT_BEFORE -> minute < r.minute!!
            Kind.NOT_AFTER -> minute >= r.minute!!
        }
        if (hit) "you asked me to remember \"${r.note}\"" else null
    }

    /** Said after "Noted": the rule Jarvis now follows, or "". */
    fun saidBack(note: String): String = when (val r = of(note)) {
        null -> ""
        else -> " I'll follow it: " + when (r.kind) {
            Kind.AVOID_MARKET -> "no ${r.market!!.label} trades offered or taken."
            Kind.AVOID_EXPIRY -> "no trade on an index's expiry day."
            Kind.NOT_BEFORE -> "no trade ideas before %02d:%02d.".format(Locale.ENGLISH, r.minute!! / 60, r.minute % 60)
            Kind.NOT_AFTER -> "no trade ideas from %02d:%02d.".format(Locale.ENGLISH, r.minute!! / 60, r.minute % 60)
        }
    }
}
