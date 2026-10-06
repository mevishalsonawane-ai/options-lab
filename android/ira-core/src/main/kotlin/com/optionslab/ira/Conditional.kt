package com.optionslab.ira

/**
 * A conditional instruction (understanding round 29): "agar Nifty 100 point gire to sab band kar do", "if Nifty falls below
 * 24000 exit all", "exit all if Nifty falls below 24000", "stop ORB if Nifty falls 100 points", "jab Nifty 25000 ho jaye tab
 * mera call sell kar do", "turn on the kill switch if I lose 5000". Jarvis cannot set an action to wait for a condition: the
 * words used to be read as the action itself, done now (CLOSE_ALL, KILL_ON) or a STOP of an arm with a garbled name ("agar
 * nifty 100 point fell to all"). Now such words are never a command, never an order and never a yes ([Commands], [Ask],
 * [Wake.yesNo]); the hub answers [SAY] - he can't, nothing was done, and the app's own alarm, stop loss and daily loss limit
 * in words. Nothing acts.
 *
 * Only a condition ("agar", "if", "jab", "when", "as soon as", "in case", "once Nifty ...") with an action said beside it
 * (stop, band karo, exit, sell, buy, square off, close, cancel, the kill switch, book profit, nikalo, becho, kharido...). Never
 * an alarm or a reminder ("alert me when BankNifty goes below 51000", "remind me to sell when Nifty hits 25000": the app's
 * own, as before), a question ("what if I buy Nifty calls", "when does the market close today", "should I exit if Nifty
 * falls", "agar Nifty gire to mera kya hoga" - the positions' what-if), a time alone ("close all at 3:15": [Later]'s), a
 * note, or the idioms that set no condition ("if possible", "if you can", "even if", "at once", "once more"). Pure.
 */
object Conditional {
    const val SAY = "I can't set an action to wait for a condition, Boss - I act only when you tell me to, and I've done nothing now. " +
        "What the app has instead: a price alarm (say \"alert me when Nifty goes below 24000\", or More, then Alerts) so you decide when it rings; " +
        "a stop loss and target on a position, which the app watches itself; and the daily loss limit and kill switch in More, then Bot settings. " +
        "When you want it done, say it plainly - \"exit all\" or \"stop all strategies\" - and I'll ask you to confirm."

    private fun words(text: String) = Spaced.joined(text)

    /** Words that set no condition, taken out before a condition is looked for. */
    private val IDIOMS = rx(" (?:if|agar) (?:possible|you can|u can|you could|needed|need be|required|necessary|any|there are any|there is any|you want|you like|" +
        "ok|okay|you dont mind|you do not mind|thats ok|its ok|ho sake|possible ho|mumkin ho|zaroori ho|koi ho|koi hai)(?= )" +
        "| (?:what|check|see|ask|wonder|know|find out|even|as|tell me) if(?= )| (?:at|all at|just|only) once(?= )| once (?:more|again|and for all)(?= )" +
        "| (?:since|till|until|from|say|tell me|know|ask) when(?= )| as soon as possible(?= )| asap(?= )")
    /** A condition: Hindi or English. */
    private val COND = rx(" (?:agar|agr|yadi|if|only if|jab|jab bhi|jab tak|jaise hi|when|whenever|as soon as|in case|the moment|" +
        "once (?:nifty|bank nifty|banknifty|finnifty|fin nifty|sensex|vix|it|its|the|my|price|market|loss|profit|mtm|we|i)) ")
    /** An action Jarvis could otherwise take (or place) in the app. */
    private val ACT = rx(" (?:stop|halt|pause|disarm|exit|sell|buy|square off|squareoff|close|cancel|kill|kill switch|switch off|turn off|" +
        "book (?:profit|profits|it|my|the|kar|karo|kar do|kar lo)|band (?:kar|karo|kardo|kar do|kar dena|kar dijiye|kijiye|karna)|" +
        "nikal|nikalo|nikaal|nikaalo|bech|becho|bech do|bech dena|kharid|kharido|khareed|khareedo|kaat|kaato|rok|roko|rok do|hata do|hatao)(?= )")
    /** Words that only look like an action: a stop loss, "close to 25000", a habit of Jarvis's ("stop telling me when..."). */
    private val NOT_ACT = listOf(
        rx(" (?:stop loss|stoploss|stop losses|stoplosses)(?= )") to " sl",
        rx(" (?:my|the|its|a|your|mera|meri|apna|apni|their) stops?(?= )") to " sl",
        rx(" close (?:to|by|above|below|near|at|over|under|of|is|was|price|hua|hoga|hogi)(?= )") to " near",
        rx(" stop (?!trading |everything |anything |something )[a-z]+ing(?= )") to " habit",
    )
    /** Asked, not told: a question about acting is the question's (an estimate, advice, a record). */
    private val ASKED = rx(" (?:what|whats|how|why|which|should|shall|would|could|can i|can we|do i|do we|does|did|is it|will i|will it|will my|will the|" +
        "kya|chahiye|kitna|kitne|kitni|kaun|kaunsa|konsa|kab|kyun|kyu|kyon|explain|suppose|imagine|scenario|hypothetically|matlab|mean|means|" +
        "kiya|kiye|liya|liye|becha|kharida|tha|thi|buy or sell|sell or buy|or not) ")
    /** An alarm or a reminder: the app sets those itself, as before. */
    private val ALARM = rx(" (?:alert|alerts|alarm|alarms|notify|remind|reminder|tell me|let me know|ping me|warn me|wake me|batana|bata dena|yaad dila|yaad dilana) ")

    /** A condition is set in [text] ("if ...", "agar ...", "jab ..."): the words that make a yes unclear ([Wake.yesNo]). */
    fun hedged(text: String): Boolean = COND.containsMatchIn(IDIOMS.replace(words(text), " "))

    /** [text] tells Jarvis to do something when a condition is met: never a command, an order or a yes; [SAY] answers it. */
    fun asked(text: String): Boolean = kept.of(text) { fresh(text) }

    private val kept = Kept<Boolean>(64)

    private fun fresh(text: String): Boolean {
        if (text.trim().endsWith("?")) return false
        var t = IDIOMS.replace(words(text), " ")
        if (!COND.containsMatchIn(t)) return false
        if (rx("^ (?:hey |ok |okay )?(?:jarvis )?(?:note|journal)(?= )").containsMatchIn(t)) return false
        if (ASKED.containsMatchIn(t) || ALARM.containsMatchIn(t)) return false
        for ((r, w) in NOT_ACT) t = r.replace(t, w)
        if (!ACT.containsMatchIn(t)) return false
        // A time alone ("close all when it's 3:15"): the timed request's ([Later]), never this.
        if (Later.mentionsTime(text) && Market.mentioned(text).isEmpty() && !rx(" (?:loss|profit|mtm|points?|percent|down|up|falls?|rises?|gire|gira|chadhe) ").containsMatchIn(t)) return false
        return true
    }
}
