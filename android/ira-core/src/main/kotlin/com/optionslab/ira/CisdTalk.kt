package com.optionslab.ira

/**
 * Jarvis on CISD + network (29 Oct 2026): "how is cisd doing", "cisd arm", "what did the cisd network trades do". Answered from the
 * arm's own book (today's setups, what was taken and why not, what is held, its record), always saying it FAILED the research gates
 * and runs on paper as Boss's exception. The trades and the record are Boss's, so never on a locked phone ([LOCKED]). Read only: a
 * sentence with something to do ("switch off cisd") is not read here. Pure.
 */
object CisdTalk {
    const val LOCKED = "Unlock the phone for that, Boss."

    private val ACT = Regex(" (switch|switched|turn|band|chalu|enable|disable|set|change|buy|sell|close|cancel|start|stop|pause|resume|add|remove|delete|clear) ")
    private val ABOUT = Regex(" cisd ")

    /** True when [text] asks about CISD + network. */
    fun asked(text: String): Boolean {
        val t = Spaced.joined(text)
        return !ACT.containsMatchIn(t) && ABOUT.containsMatchIn(t)
    }
}
