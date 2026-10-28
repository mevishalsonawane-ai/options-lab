package com.optionslab.ira

/**
 * Jarvis on S5 Trail (28 Oct 2026): "how is s5 trail doing", "s5 arm", "what did the s5 trades do". Answered from the arm's own
 * book (today's decisions at 09:20, 09:25 and 09:30, what was taken and why not, what is held, its record), always saying it FAILED
 * the research gates and runs on paper as Boss's exception. The trades and the record are Boss's, so never on a locked phone
 * ([LOCKED]). Read only: a sentence with something to do ("switch off s5 trail") is not read here. Pure.
 */
object S5Talk {
    const val LOCKED = "Unlock the phone for that, Boss."

    private val ACT = Regex(" (switch|switched|turn|band|chalu|enable|disable|set|change|buy|sell|close|cancel|start|stop|pause|resume|add|remove|delete|clear) ")
    private val ABOUT = Regex(" s ?5 (trail|arm|trades?) ")

    /** True when [text] asks about S5 Trail. */
    fun asked(text: String): Boolean {
        val t = Spaced.joined(text)
        return !ACT.containsMatchIn(t) && ABOUT.containsMatchIn(t)
    }
}
