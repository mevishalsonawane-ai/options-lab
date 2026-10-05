package com.optionslab.ira

/**
 * Mis-heard fragments (voice, round 26 - Boss's diagnostics, 5 Oct, Pixel 9 on the en-US recognizer): "bus", "pause",
 * "calculation", "office schedule", "what s the piano" were taken as questions and sent to the slow on-device model
 * ("One moment, Boss..." then "That's outside what I know"). Short words that match nothing Jarvis knows are most
 * likely a mis-hear: Jarvis says at once he didn't catch them, without the model, and they are counted as mis-heard,
 * not as questions he did not understand. A near miss of something known is read as that first ([rescue]) - a question
 * only, never a command or an order.
 *
 * Nothing here acts: it only ever turns words into "say it again" or into a question. Pure.
 */
object MisHeard {
    /** Said for a fragment, at once. */
    const val SAY = "Sorry Boss, I didn't catch that — say it again?"

    /** The day's count of fragments (kept beside "heard" and "misunderstood"; [Improve.MISHEARD]). */
    const val COUNT = "misheard"

    /** Fragments are this many words at most. */
    const val MAX_WORDS = 3

    /** Acknowledgements and short replies: real words to Jarvis, never a mis-hear (they go on as before). */
    private val KNOWN = setOf(
        "ok", "okay", "ok boss", "okay boss", "hmm", "hm", "mm", "right", "alright", "all right", "fine", "cool", "great", "nice", "good",
        "very good", "got it", "i see", "understood", "sure", "wow", "oh", "oh ok", "ah", "thanks", "thank you", "thank you boss",
        "acha", "achha", "accha", "acchha", "theek", "thik", "theek hai", "thik hai", "haan", "han", "ha", "ji", "haan ji", "ji haan",
        "shukriya", "dhanyavad", "namaste", "bye", "good night", "good bye", "goodbye", "see you", "later", "never mind", "nevermind",
        "what", "huh", "sorry", "pardon", "come again", "say again", "again", "what did you say", "yes", "no", "yeah", "nope",
        "jarvis", "boss", "hello", "hi", "hey", "help", "why", "how", "when", "where", "which", "who", "and", "so", "then",
        "repeat", "repeat that", "go on", "continue", "more", "tell me more", "stop", "cancel", "mute", "unmute", "status",
    )

    /** Words that mean a market or Boss's own account: never a fragment, however short. */
    private val TRADING = rx(" (nifty|banknifty|bank nifty|finnifty|fin nifty|sensex|vix|gold|market|markets|index|indices|p l|pnl|p&l|mtm|m2m|" +
        "position|positions|holding|holdings|order|orders|trade|trades|fund|funds|margin|profit|loss|strategy|strategies|arm|arms|bot|bots|algo|algos|" +
        "option|options|call|calls|put|puts|ce|pe|strike|expiry|premium|chain|level|levels|support|resistance|news|price|prices|stock|stocks|" +
        "theta|delta|gamma|vega|iv|oi|pcr|vwap|atm|otm|itm|straddle|strangle|account|balance|capital|paper|live|kill|switch|alarm|alarms|reminder|reminders) ")

    /** The words of [text], lower case, spaced. */
    private fun words(text: String): List<String> = spacedWords(text.lowercase(), "&").split(' ').filter { it.isNotEmpty() }

    /**
     * Is [text] most likely a mis-hear? Heard ([voice]): 1-[MAX_WORDS] words that match no command, no order, no market
     * or account word, no known short reply and nothing Jarvis can answer. Typed: only a single non-word ("sdfg").
     */
    fun fragment(text: String, voice: Boolean): Boolean = runCatching { fragmentOf(text, voice) }.getOrDefault(false)

    private fun fragmentOf(text: String, voice: Boolean): Boolean {
        // ("what s the piano": the recognizer's "s" of "what's" is no word of its own.)
        val w = words(text).filter { it != "s" }
        if (w.isEmpty() || w.size > MAX_WORDS) return false
        if (!voice && (w.size != 1 || !nonWord(w[0]))) return false
        val t = " " + w.joinToString(" ") + " "
        if (w.joinToString(" ") in KNOWN) return false
        if (TRADING.containsMatchIn(t) || Market.mentioned(text).isNotEmpty()) return false
        // Hinglish words stay as today: never read as a mis-hear.
        if (Hinglish.hasHindi(text)) return false
        // Any digit: a level or a strike said short ("24500?") is a real question.
        if (w.any { x -> x.any { it.isDigit() } }) return false
        // A word that may act ("exit", "close it", "square off", "flatten", "pause"...) is never a mis-hear (review, 5 Oct):
        // it goes on to be read as before ([exitHint] for the exits), never acted on from here.
        if (ACTS.containsMatchIn(t) || runCatching { Intents.mayMean(text) }.getOrDefault(true)) return false
        val q = Ask.parse(text)
        if (q.command != null || q.order != null || q.markets.isNotEmpty()) return false
        // Only words read as nothing - or "why you": a "why" at Jarvis with nothing to ask about.
        val nothing = q.topics == setOf(Topic.OFF_TOPIC) || (q.topics == setOf(Topic.WHY) && Chat.personal(text) && w.size <= 2)
        if (!nothing) return false
        if (Wake.yesNo(text) != null || Wake.hush(text) || BargeIn.goOn(text) || Again.read(text) != null) return false
        if (Chat.smallTalk(text, 0) != null) return false
        if (Glossary.explain(text) != null || Intents.quick(text) != null) return false
        if (Suggest.closest(text) != null) return false
        return true
    }

    /** A typed word that is no word: no vowel at all ("sdfg"), or one letter three times running ("aaaa"). */
    private fun nonWord(w: String): Boolean {
        if (w.length < 2 || !w.all { it in 'a'..'z' }) return false
        if (w in ABBREVIATIONS) return false
        return w.none { it in "aeiouy" } || rx("(.)\\1\\1").containsMatchIn(w)
    }

    /** Short trading words with no vowel: never a non-word. */
    private val ABBREVIATIONS = setOf("pnl", "mtm", "ltp", "pcr", "nse", "bse", "sl", "tp", "hmm", "hm", "mm", "nfty", "bnf", "fnf", "cpi", "gdp", "fd", "pl")

    /**
     * Mis-heard words close to a question Jarvis knows, read as it: words said twice dropped ("face the face the"), the
     * recognizer's known slips ([Heard.fix]), near-miss spellings ([Spelling.fix]), and an index name one letter off
     * ("sensei" for Sensex). The question meant, or null. Never a command or an order (each is read from the words as
     * heard, with its own checks): only a question that is not "nothing" again.
     */
    fun rescue(text: String): String? = runCatching { rescueOf(text) }.getOrNull()

    private fun rescueOf(text: String): String? {
        val w = words(text)
        if (w.isEmpty()) return null
        val base = w.joinToString(" ")
        val tries = LinkedHashSet<String>()
        val once = withoutRepeats(base)
        tries += once
        tries += Heard.fix(once)
        tries += Spelling.fix(once)
        indexNear(once)?.let { tries += it }
        for (c in tries) {
            if (c.isBlank() || c == base) continue
            // A word that may act, a yes or a no, or "stop": never a rescued question (said again as heard, with its checks).
            if (ACTS.containsMatchIn(" $c ") || Wake.yesNo(c) != null || Wake.hush(c)) continue
            val q = Ask.parse(c)
            if (q.command != null || q.order != null) continue
            if (Topic.COMMAND in q.topics || Topic.ORDER in q.topics || Topic.OFF_TOPIC in q.topics) continue
            if (runCatching { Bundle.acts(c) }.getOrDefault(true)) continue
            // A greeting is no rescue: it says nothing more than the words did.
            if (q.topics == setOf(Topic.GREETING)) continue
            return c
        }
        return null
    }

    /** Words that may act: a rescued reading never holds one, and they are never a fragment. */
    private val ACTS = rx(" (stop|start|cancel|close|exit|kill|square|buy|sell|place|pause|resume|switch|turn|mute|unmute|approve|reject|confirm|" +
        "enable|disable|set|remind|alarm|book|modify|trail|hedge|roll|run|arm|arms|live|paper|autopilot|flatten|halt|get out|shut) ")

    /** Said for an exit-like word that is no command by itself ("exit", "close it", "square off"): how to ask for it. */
    const val EXIT_HINT = "Say 'close all' or 'exit all', Boss, and I'll ask you to confirm."

    private val EXIT_LIKE = rx("^((ok |okay |please |jarvis )*)(exit|exit it|exit now|exit trade|exit everything|close|close it|close now|close everything|" +
        "square off|square it off|square up|square off now|flatten|flatten it|flatten everything|get out|get me out|get out now|get us out)( please| now| boss| jarvis)*$")

    /**
     * [text] is an exit-like word ("exit", "close it", "square off", "square up", "flatten", "get out") that is no command
     * by itself: the hint ([EXIT_HINT]) to say instead, else null. Words only - nothing is ever closed from here; "close
     * all" / "exit all" are commands with their own confirm.
     */
    fun exitHint(text: String): String? = runCatching {
        val t = words(text).joinToString(" ")
        if (!EXIT_LIKE.matches(t)) return@runCatching null
        val q = Ask.parse(text)
        if (q.command != null || q.order != null) null else EXIT_HINT
    }.getOrNull()

    /** "face the face the laws" -> "face the laws": a run of one to three words said twice in a row, once. */
    fun withoutRepeats(text: String): String {
        var t = " " + text.trim() + " "
        repeat(3) { t = REPEAT.replace(t, "$1") }
        return t.trim().replace(rx("\\s+"), " ")
    }

    /** One to three letter-words, then the same again (digits never: "50 50" may be meant). */
    private val REPEAT = rx("(?<= )((?:[a-z&]+ ){1,3})\\1")

    /** The index names a mis-heard word may be one letter off from (five letters or more, so "hold" never reads as gold). */
    private val INDEX = listOf("nifty", "banknifty", "finnifty", "sensex")

    /** [text] with a word one letter off an index name read as it (only when exactly one such word and one name fits). */
    private fun indexNear(text: String): String? {
        val w = text.split(' ')
        var hit = -1
        var name: String? = null
        for ((i, x) in w.withIndex()) {
            if (x.length < 5 || x in INDEX || !x.all { it in 'a'..'z' }) continue
            val near = INDEX.filter { kotlin.math.abs(it.length - x.length) <= 1 && Spelling.distance(x, it) <= 1 }
            if (near.size != 1) continue
            if (hit >= 0) return null
            hit = i; name = near[0]
        }
        val n = name ?: return null
        return w.mapIndexed { i, x -> if (i == hit) n else x }.joinToString(" ")
    }
}
