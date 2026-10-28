package com.optionslab.ira

/**
 * Jarvis on IraThinker's flow (28 Oct 2026): "how is the IraThinker flow doing", "flow arm", "what did the flow page take".
 * Answered from the arm's own book (today's candidates, what was taken or skipped and why, what is held, its record), always
 * saying it is not proven (R110). The trades and the record are Boss's, so never on a locked phone ([LOCKED]). Read only: a
 * sentence with something to do ("switch off the flow arm") is not read here. Never "order flow" (the order flow is its own
 * feature, with its own questions). Pure.
 */
object FlowTalk {
    const val LOCKED = "Unlock the phone for that, Boss."

    private val ACT = Regex(" (switch|switched|turn|band|chalu|enable|disable|set|change|buy|sell|close|cancel|start|stop|pause|resume|add|remove|delete|clear) ")
    private val ABOUT = Regex(" (irathinker'?s? flow|ira thinker'?s? flow|flow arm|flow page|flow trades?|flow pipeline) ")
    private val ORDER_FLOW = Regex(" order ?flow ")

    /** True when [text] asks about IraThinker's flow (never the order flow). */
    fun asked(text: String): Boolean {
        val t = Spaced.joined(text)
        return !ACT.containsMatchIn(t) && !ORDER_FLOW.containsMatchIn(t) && ABOUT.containsMatchIn(t)
    }
}
