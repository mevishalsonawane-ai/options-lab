package com.optionslab.ira

/**
 * Open intraday (MIS) positions on Zerodha at 15:10: the broker squares them off itself from about 15:20, with a
 * charge - Boss is told once, to close them himself (words only; nothing is closed). Pure.
 */
object MisNudge {
    const val AT = 15 * 60 + 10
    const val UNTIL = 15 * 60 + 18

    fun due(minute: Int): Boolean = minute in AT..UNTIL

    /** [open]: symbol to quantity of the open MIS positions. */
    /** [named]: the symbols in it (the chat); spoken aloud (a locked phone may be heard) only the count. */
    fun say(open: List<Pair<String, Int>>, named: Boolean = true): String? {
        if (open.isEmpty()) return null
        val names = if (!named) "" else ": " + open.take(3).joinToString(", ") { it.first } + if (open.size > 3) " and ${open.size - 3} more" else ""
        return "Boss, ${open.size} intraday (MIS) position${if (open.size > 1) "s are" else " is"} still open on Zerodha$names. " +
            "Zerodha squares them off itself from about 15:20, with a charge - close ${if (open.size > 1) "them" else "it"} yourself before then if you want to choose the price."
    }
}
