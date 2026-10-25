package com.optionslab.ira

/**
 * Jarvis on ntfy alerts (25 Oct 2026): "what did ntfy say", "last ntfy alerts", "ntfy alerts kya aaya", "how is ntfy doing".
 * Answered from the arm's own Alerts list (the last few messages, each with what was decided and why) and its record, always
 * saying what it is: an external message, not a researched or proven strategy. The messages and the record are Boss's, so
 * never on a locked phone ([LOCKED]). Read only: a sentence with something to do ("switch on ntfy", "buy from ntfy") is not read
 * here, and nothing is switched, bought or sold. Pure.
 */
object NtfyTalk {
    const val LOCKED = "Unlock the phone for that, Boss."

    private val ACT = Regex(" (switch|switched|turn|band|chalu|enable|disable|set|change|buy|sell|trade|close|cancel|start|stop|pause|resume|add|remove|delete|clear|type|save) ")
    private val ABOUT = Regex(" (ntfy|notify alerts?|ntfy alerts?|alert messages?|ntfy messages?) ")

    /** True when [text] asks about ntfy alerts. */
    fun asked(text: String): Boolean {
        val t = Spaced.joined(text)
        return !ACT.containsMatchIn(t) && ABOUT.containsMatchIn(t)
    }

    /**
     * The answer: [on] its switch, [lines] the last messages (newest first, "10:12 NIFTY PE: traded (bought ...)"), [open] its
     * open trades in words, [net] its closed paper rupees and [closed] how many.
     */
    fun answer(on: Boolean, lines: List<String>, open: List<String>, net: Double, closed: Int): String {
        val head = "ntfy alerts is ${if (on) "on, on paper" else "off"} - an external message, not a researched or proven strategy."
        val msgs = if (lines.isEmpty()) " No messages read yet." else " The last ${if (lines.size == 1) "message" else "${lines.size} messages"}: " +
            lines.joinToString("; ") + "."
        val held = if (open.isEmpty()) "" else " Open: ${open.joinToString(", ")}."
        val rec = if (closed == 0) "" else " $closed closed, ${rs(net)} after charges (paper)."
        return head + msgs + held + rec
    }

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(java.util.Locale.ENGLISH, kotlin.math.abs(x))
}
