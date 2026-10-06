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

    /**
     * What Jarvis can concretely offer instead (usefulness round 38): [alarm], a price alarm ([Command.Kind.ALARM_ADD]) at the
     * index level the condition named ("if Nifty falls below 24000 exit all": Nifty below 24,000) - only offered, and set
     * through the app's own alarm path on Boss's yes, never otherwise; or [loss], the amount of a loss condition ("if I lose
     * 5000"), answered with his daily loss limit and where to change it. Never a stop, an exit, an order or the kill switch.
     */
    data class Instead(val alarm: Command? = null, val loss: Double? = null)

    /** A loss said in the condition: "if I lose 5000", "agar loss 5000 ho jaye", "agar 5000 ka loss ho", "if I'm down 4k". */
    private val LOSS_WORD = rx(" (?:lose|loose|losing|lost|loss|losses|nuksan|nuksaan|nukhsan|(?:i am|im|we are|were|mtm|pnl) down)(?= )")
    private const val AMOUNT = "(\\d{3,7}(?:\\.\\d+)?|\\d{1,3}(?:\\.\\d+)? ?k)"
    private val LOSS_AFTER = rx(" (?:lose|loose|losing|lost|loss|losses|nuksan|nuksaan|nukhsan|down)(?: (?:of|is|crosses|reaches|hits|goes|to|past|above|over|more|than|beyond|exceeds|rs|rupees|inr))* $AMOUNT(?= )")
    private val LOSS_BEFORE = rx(" (?:rs |rupees |inr )?$AMOUNT(?: rs| rupees| rupaye| rupay| inr)?(?: ka| ki| ke| of| se zyada| tak)? (?:loss|nuksan|nuksaan|nukhsan)(?= )")
    /** A direction for the index: falling below a level, or rising above one. */
    private val BELOW = rx(" (?:below|under|beneath|neeche|niche|falls|fall|falling|drops|drop|dips|dip|slips|gire|gira|girta|tute|toote)(?= )")
    private val ABOVE = rx(" (?:above|over|crosses|cross|crossing|rises|rise|rising|upar|oopar|chadhe|chadh)(?= )")
    /** A level: a number never said as a move ("100 points", "1 percent") or an amount. */
    private val LEVEL = rx(" (\\d{2,6}(?:\\.\\d+)?)(?= )(?! (?:points?|pts?|percent|pc|rs|rupees|lots?|lot)(?= ))")

    /** The words with a number's thousands commas and a sentence's full stops gone ("24,000." is 24000; "52.5" kept). */
    private fun numbered(text: String): String = " " + spacedWords(text.lowercase().replace("'", "").replace("’", "")
        .replace(rx("(\\d),(?=\\d{3})"), "$1").replace("%", " percent ").replace(rx("\\.(?!\\d)"), " "), keep = ".") + " "

    /**
     * What to offer for a conditional instruction ([asked]): a price alarm on the one index named, with one clear direction
     * and one level, or the loss amount; null when neither is clear (then [SAY] alone, as before). Gold, two indices, a
     * move in points or percent, or no direction offers no alarm. Pure.
     */
    fun instead(text: String): Instead? {
        if (!asked(text)) return null
        val t = numbered(text).replace(rx(" (nifty|sensex) (?:50|30)(?= )"), " $1")
        if (LOSS_WORD.containsMatchIn(t)) {
            val n = (LOSS_AFTER.find(t) ?: LOSS_BEFORE.find(t))?.groupValues?.get(1) ?: return null
            val v = if (n.endsWith("k")) n.removeSuffix("k").trim().toDoubleOrNull()?.times(1000) else n.toDoubleOrNull()
            return v?.takeIf { it >= 100 }?.let { Instead(loss = it) }
        }
        val m = Market.mentioned(text).singleOrNull()?.takeIf { it != Market.GOLD } ?: return null
        val below = BELOW.containsMatchIn(t)
        if (below == ABOVE.containsMatchIn(t)) return null
        val lvl = LEVEL.findAll(t).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList().singleOrNull() ?: return null
        if (if (m == Market.VIX) lvl !in 5.0..100.0 else lvl < 1000) return null
        return Instead(alarm = Command(Command.Kind.ALARM_ADD, market = m, above = !below, level = lvl))
    }

    private fun figure(v: Double) = if (v % 1.0 == 0.0) "%,.0f".format(java.util.Locale.ENGLISH, v) else "%,.2f".format(java.util.Locale.ENGLISH, v)

    private const val CANT = "I can't set an action to wait for a condition, Boss - I act only when you tell me to, and I've done nothing now."

    /** The alarm in a few words, for the request and the spoken yes or no: "Nifty below 24,000". */
    fun alarmWhat(c: Command): String = "${c.market?.label ?: "?"} ${if (c.above == false) "below" else "above"} ${c.level?.let { figure(it) } ?: "?"}"

    /** Said with the alarm offered: he can't, nothing was done, and the alarm is put to Boss - set only on his yes. */
    fun alarmSay(c: Command): String = "$CANT What I can do is set a price alarm, ${alarmWhat(c)}, so you hear when it gets there and decide then - " +
        "an alarm only rings, it never trades. Shall I set it? Say yes to set it, or no. If you'd rather act now, say it plainly and I'll ask you to confirm."

    /** The question the request carries (the pop-up and the spoken yes or no). */
    fun alarmAsk(c: Command): String = "Set a price alarm, ${alarmWhat(c)}? It only rings; it never trades."

    /**
     * Said for a loss condition ("if I lose 5000"): his daily loss limit now ([limit], 0 = off; [account] "Paper" or
     * "Zerodha") set against the [amount] he said, and where to change it. His account, so on a [locked] phone (or no limit
     * read) only where it is. Changes nothing.
     */
    fun lossSay(amount: Double, limit: Double?, account: String, locked: Boolean): String {
        val where = "It's in More, then Bot settings, Daily loss limit."
        if (locked || limit == null) return "$CANT The app's own guard for that is the daily loss limit - unlock the phone and ask me for it, or see it yourself. $where"
        if (limit <= 0) return "$CANT The app's own guard for that is the daily loss limit, and it's off for $account just now. To have the app stop at a loss of Rs ${figure(amount)}, set it there. $where"
        val vs = when {
            limit == amount -> "the same as the Rs ${figure(amount)} you said"
            limit > amount -> "more than the Rs ${figure(amount)} you said"
            else -> "less than the Rs ${figure(amount)} you said"
        }
        return "$CANT The app's own guard for that is the daily loss limit: for $account it's Rs ${figure(limit)} now, $vs. " +
            "At that loss the app takes no new entries and stops the bots for the day. To change it: $where I haven't changed anything."
    }

    /** Words that set no condition, taken out before a condition is looked for. */
    private val IDIOMS = rx(" (?:if|agar) (?:possible|you can|u can|you could|needed|need be|required|necessary|any|there are any|there is any|you want|you like|" +
        "ok|okay|you dont mind|you do not mind|thats ok|its ok|ho sake|possible ho|mumkin ho|zaroori ho|koi ho|koi hai)(?= )" +
        "| (?:what|check|see|ask|wonder|know|find out|even|as|tell me) if(?= )| (?:at|all at|just|only) once(?= )| once (?:more|again|and for all)(?= )" +
        "| (?:since|till|until|from|say|tell me|know|ask) when(?= )| as soon as possible(?= )| asap(?= )")
    /** A condition: Hindi or English. */
    private val COND = rx(" (?:agar|agr|yadi|if|only if|jab|jab bhi|jab tak|jaise hi|when|whenever|as soon as|in case|the moment|" +
        "once (?:nifty|bank nifty|banknifty|finnifty|fin nifty|sensex|vix|it|its|the|my|price|market|loss|profit|mtm|we|i)) " +
        // Hinglish without "agar" (understanding round 30): "nifty 24000 aaye to exit kar dena", "loss 5000 ho to sab band kar do",
        // "profit 2000 hua to book kar lena", "banknifty 52000 tod de to put kharid lo" - a condition's verb, then "to"/"tab"...
        "| (?:aaye|aye|aae|aa jaye|aa jaaye|aa gaya|aa gayi|jaye|jaaye|ho|ho jaye|ho jaaye|hua|hui|ho gaya|ho gayi|kare|kar le|kar jaye|" +
        "gire|gira|chadhe|chadha|badhe|tode|toda|tod de|tod deta|toote|tute|toot jaye|tut jaye|pahunche|pahuche|pohche|pohonche|lage|lag jaye|" +
        "chhue|chhu le|bane|ban jaye|jata hai|jaata hai|jati hai|jaati hai|hota hai|hoti hai|(?<! theek | thik | thick | accha | acha | achha | sahi | ok | okay | haan | han | ha | ji )hai|hain) " +
        "(?:to|toh|tab|tabhi|to phir|toh phir) " +
        // ... or "-te hi" ("as soon as"): "nifty 24000 aate hi exit kar dena", "2000 profit hote hi book kar lo", "market khulte hi".
        "| [a-z]{2,}t[ei] hi ")
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
    /**
     * Boss supposing his own act ("if I buy 2 lots", "agar main exit karu", "when I sell my call"): a what-if about himself,
     * never an instruction to Jarvis (understanding round 30). Said with a figure he wants ("if I buy 2 lots margin", "agar
     * main call bechu to brokerage"), it is that question's; said alone it is still never acted on ([supposed]).
     */
    private val MINE = rx(" (?:agar|agr|yadi|if|when|jab) (?:i|main|mai|mein|hum|we)(?: (?:just|now|abhi|ab|today|aaj|only|sirf|also|bhi))? " +
        "(?:stop|exit|sell|buy|square off|squareoff|close|cancel|book|band|nikal|nikalo|bech|kharid|khareed|kaat|hold|keep|take|add|enter|go|switch|turn)(?= )" +
        "| (?:karu|karun|karoon|karein|karen|kharidu|kharidun|khareedu|bechu|bechun|bechoon|nikalu|nikaalu|kaatu|lu|loon|rakhu|rakhun|chhodu)(?= )")
    /** A figure he may want about his own supposed act. */
    private val FIGURE = rx(" (?:margin|margins|breakeven|break even|risk|charges|charge|brokerage|cost|costs|tax|taxes|payoff|premium|pnl|p l|p and l|mtm|" +
        "loss|profit|nuksan|nuksaan|fayda|faida|munafa|paisa|paise|kharcha|max loss|max profit|greeks|delta|theta|lot size|capital|funds|money) ")
    /** Told, not supposed: an action addressed to Jarvis beside his own ("agar main 5000 loss karu to kill switch on kar do"). */
    private val TOLD = rx(" (?:kar do|kardo|kar dena|kar dijiye|karo|kar lo|kar lena|do na|bech do|becho|kharido|khareedo|nikalo|nikaalo|kaato|roko|hatao|" +
        "kill switch|turn on|turn off|switch on|switch off|then (?:stop|exit|sell|buy|square off|close|cancel|book)|please (?:stop|exit|sell|buy|square off|close|cancel|book)) ")
    /** An alarm or a reminder: the app sets those itself, as before. */
    private val ALARM = rx(" (?:alert|alerts|alarm|alarms|notify|remind|reminder|tell me|let me know|ping me|warn me|wake me|batana|bata dena|yaad dila|yaad dilana) ")

    /** A condition is set in [text] ("if ...", "agar ...", "jab ..."): the words that make a yes unclear ([Wake.yesNo]). */
    fun hedged(text: String): Boolean = COND.containsMatchIn(IDIOMS.replace(words(text), " "))

    /** [text] tells Jarvis to do something when a condition is met: never a command, an order or a yes; [SAY] answers it. */
    fun asked(text: String): Boolean = kept.of(text) { fresh(text) }

    private val kept = Kept<Boolean>(64)

    /**
     * Boss supposing his own act with a condition ("if I buy 2 lots", "agar main exit karu to margin"): never a command or an
     * order ([Commands], [Ask]) whether it is [asked] or a question about a figure; Jarvis never acts on a what-if.
     */
    fun supposed(text: String): Boolean {
        val t = IDIOMS.replace(words(text), " ")
        return COND.containsMatchIn(t) && MINE.containsMatchIn(t)
    }

    private fun fresh(text: String): Boolean {
        if (text.trim().endsWith("?")) return false
        var t = IDIOMS.replace(words(text), " ")
        if (!COND.containsMatchIn(t)) return false
        if (rx("^ (?:hey |ok |okay )?(?:jarvis )?(?:note|journal)(?= )").containsMatchIn(t)) return false
        if (ASKED.containsMatchIn(t) || ALARM.containsMatchIn(t)) return false
        // His own act supposed, with a figure he wants about it ("if I buy 2 lots margin"): a question, never this one.
        if (MINE.containsMatchIn(t) && FIGURE.containsMatchIn(t) && !TOLD.containsMatchIn(t)) return false
        for ((r, w) in NOT_ACT) t = r.replace(t, w)
        if (!ACT.containsMatchIn(t)) return false
        // A time alone ("close all when it's 3:15"): the timed request's ([Later]), never this.
        if (Later.mentionsTime(text) && Market.mentioned(text).isEmpty() && !rx(" (?:loss|profit|mtm|points?|percent|down|up|falls?|rises?|gire|gira|chadhe) ").containsMatchIn(t)) return false
        return true
    }
}
