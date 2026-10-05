package com.optionslab.ira

/**
 * What the phone's recognizer leaves in when Boss talks (2026-10-05): "umm", "uh", "you know", "matlab", a word said
 * twice ("how how is Nifty"), a false start ("how is Nifty, I mean BankNifty"). [clean] reads past them. For QUESTIONS
 * only: [Understand] uses it only when neither the words as heard nor the words cleaned ask for anything to be done, so
 * nothing cleaned here can ever start, stop, close, cancel or place anything. Pure.
 */
object Filler {
    /** Sounds and stock phrases that say nothing, anywhere in the words. */
    private val NOISE = Regex("(?i)[\\s,]*(?<![a-z0-9&])(?:u+m+|u+h+m*|e+r+m+|h+m+|a+h+|er|basically|like i said|kind of like)(?![a-z0-9&])[\\s,]*")
    /** "You know" set apart by a pause ("nifty, you know, is it up"): never inside "what do you know about me". */
    private val YOU_KNOW = Regex("(?i)(^|,)\\s*you know\\s*(?=,|$)")
    /** Said first and meaning nothing: "so", "well", "okay so", "acha", "arre". */
    private val LEAD = Regex("(?i)^(?:so|well|okay so|ok so|alright so|acha|achha|accha|arre|arey|haan to|to)(?:[\\s,.]+)")
    /** A false start: what comes after replaces what came before ("Nifty - I mean BankNifty"). */
    private val RESTART = Regex("(?i)[\\s,.]+(?:i mean|i meant|matlab|sorry|no wait|or rather)[\\s,.]+")
    /** "Doji matlab kya": "matlab" there means "meaning", not a false start. */
    private val MEANING = Regex("(?i)^(?:kya|kya hai|kya hota hai|kya hota|what)\\b")
    /** One to three words said again at once ("how how", "is it is it"); never numbers ("10 10" is a time). */
    private val AGAIN = Regex("(?i)(?<![a-z0-9'&])([a-z']+(?:\\s+[a-z']+){0,2})(?:[\\s,]+\\1)+(?![a-z0-9'&])")

    /** [text] with fillers, repeats and false starts left out; [text] itself (exactly) when there are none. */
    fun clean(text: String): String {
        var s = NOISE.replace(YOU_KNOW.replace(text, "$1 "), " ")
        s = tidy(s)
        repeat(2) { s = tidy(LEAD.replace(s, "")) }
        s = restart(s)
        s = tidy(AGAIN.replace(s, "$1"))
        return if (squash(s) == squash(text)) text else s
    }

    private fun restart(s: String): String {
        val m = RESTART.findAll(s).lastOrNull() ?: return s
        val left = s.substring(0, m.range.first).trim().trimEnd(',', '-')
        val right = s.substring(m.range.last + 1).trim()
        if (right.isEmpty()) return left
        if (left.isEmpty()) return right
        if (m.value.lowercase().contains("matlab") && MEANING.containsMatchIn(right)) return s
        // "How is Nifty, I mean BankNifty": the market put right; "Nifty kaisa hai matlab Sensex" too.
        if (FollowUp.onlyMarkets(right)) {
            val from = Market.mentioned(left).firstOrNull()
            val to = Market.mentioned(right).first()
            if (from != null) return FollowUp.swap(left, from, to) ?: left
        }
        // "What's the trend, I mean the levels on BankNifty": said again whole.
        return if (right.split(rx("\\s+")).size >= 2) right else "$left $right"
    }

    private fun tidy(s: String) = s.replace(rx("\\s*,(\\s*,)+"), ",").replace(rx("\\s+([,?.!])"), "$1").replace(rx("\\s+"), " ")
        .trim().trim(',').trim()

    private fun squash(s: String) = s.lowercase().replace(rx("[^a-z0-9&]"), "")
}

/**
 * Two questions in one breath (2026-10-05): "how is Nifty and what's my P&L", "Nifty kaisa hai aur mera P&L kitna
 * hai", "how is Nifty? any news?" - each part read as its own question, in the order said. QUESTIONS only: words that
 * hold anything that could act (stop, start, close, cancel, order, live, kill switch, alarm, mute, buy, sell...) are
 * never split, and a part splits off only where a new question begins ("how is Nifty and BankNifty" stays one). Pure.
 */
object Compound {
    const val MAX_PARTS = 4

    /** Any word that could ask for something to be done: such words are never split (they go on whole, as heard). */
    internal val ACTION = Regex(" (stop|stops|stopped|start|starts|close|closes|closing|exit|exits|cancel|cancels|order|orders|live|kill|switch|alarm|alarms|" +
        "alert|alerts|mute|unmute|buy|sell|square|squareoff|place|book|trail|pause|resume|set|turn|enable|disable|disarm|activate|deactivate|" +
        "remind|reminder|note|journal|mode|paper|autopilot|emergency|panic|flatten|backtest|band|roko|rok|chalu|shuru|hatao|hata|khareedo|" +
        "kharido|becho|lagao|karo|kardo|kar do|lelo|le lo) ")

    /** Where a new question can begin. */
    private val START = Regex("^ (how|hows|how s|what|whats|what s|where|wheres|where s|when|why|which|who|is|are|am|was|were|did|do|does|has|have|will|" +
        "would|can|could|should|any|show|tell|give|list|my|mera|meri|mere|kya|kitna|kitne|kitni|kaisa|kaise|kaisi|koi|kab|kahan|kyun|kyu) ")
    /** "and", "aur", "also", "plus", commas: a new question may start after them. */
    private val WEAK = Regex("(?i)\\s*,\\s*(?:and|aur|also|plus)\\s+|\\s+(?:and also|and|aur|also|plus)\\s+|\\s*,\\s*")
    /** "?" or ";" ends a question outright. */
    private val STRONG = Regex("\\s*[?;]\\s*")

    private fun norm(s: String) = " " + spacedWords(s.lowercase().replace("'", "")) + " "

    /** The questions in [text] as said, or null when it is one question - or holds a word that could act. */
    fun split(text: String): List<String>? {
        val t = text.trim()
        if (ACTION.containsMatchIn(norm(t)) || ACTION.containsMatchIn(norm(Hinglish.normalize(t)))) return null
        val parts = t.split(STRONG).map { it.trim() }.filter { it.isNotEmpty() }.flatMap { weak(it) }
        if (parts.size < 2 || parts.size > MAX_PARTS) return null
        // Each part a question of at least two words ("how is Nifty and BankNifty" never splits off "BankNifty").
        if (parts.any { norm(it).trim().split(" ").size < 2 }) return null
        return parts
    }

    /** [p] cut where a new question begins after "and" / "aur" / a comma; the rest stays with the part before it. */
    private fun weak(p: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        for (m in WEAK.findAll(p)) {
            if (m.range.first < from || !begins(p.substring(m.range.last + 1))) continue
            out += p.substring(from, m.range.first).trim()
            from = m.range.last + 1
        }
        out += p.substring(from).trim()
        return out.filter { it.isNotEmpty() }
    }

    /** Does [rest] start a question of its own ("what's my P&L", "mera P&L kitna hai", "Sensex kaisa hai")? */
    private fun begins(rest: String): Boolean {
        val upTo = rest.split(WEAK).first()
        if (START.containsMatchIn(norm(upTo))) return true
        val h = Heard.fix(upTo)
        return Hinglish.question(h) != h
    }
}

/**
 * Understanding what Boss said in context (2026-10-05): the recognizer's fillers left out ([Filler]), numbers and times
 * said in words read as digits ([Spoken.question]), two questions in
 * one breath answered both ([Compound]), and a follow-up read with the question before it ([FollowUp]) - also between
 * the parts ("how is Nifty and what about BankNifty"). QUESTIONS only: when anything said could act, only the
 * follow-up reading that [FollowUp] always did is given, and every question given is checked not to act. Pure.
 */
object Understand {
    /**
     * The question(s) to answer in place of [now], in order; null when [now] is to be handled as said (anything that
     * acts included). [prev]: Boss's previous question, or null when there is none recent.
     */
    fun questions(prev: String?, now: String): List<String>? {
        val said = now.trim()
        if (said.isEmpty()) return null
        // Words that could act: never cleaned or split - only the follow-up reading (which never acts) as before.
        if (FollowUp.acts(said) || Compound.ACTION.containsMatchIn(" " + spacedWords(said.lowercase()) + " "))
            return FollowUp.resolve(prev, said)?.let { listOf(it) }
        // Numbers and times said in words ("how far is Nifty from twenty five thousand"): as digits, questions only.
        val clean = Spoken.question(Filler.clean(said).ifBlank { return null })
        if (FollowUp.acts(clean)) return null
        val parts = Compound.split(clean) ?: listOf(clean)
        val out = mutableListOf<String>()
        var before = prev
        for (p in parts) {
            val q = FollowUp.resolve(before, p) ?: p
            if (FollowUp.acts(q)) return null
            // In two questions, each one must be a question Jarvis can answer ("hello and..." is not split).
            if (parts.size > 1 && !asks(q)) return null
            out += q
            before = q
        }
        return out.takeIf { it.size > 1 || it.single() != said }
    }

    private val ANSWERS = setOf(Topic.OVERVIEW, Topic.WHY, Topic.TREND, Topic.LEVELS, Topic.PATTERNS, Topic.NEWS, Topic.VOLATILITY,
        Topic.ACCOUNT, Topic.TRADE_CHECK, Topic.EXPLAIN, Topic.ADVICE, Topic.HELP)

    /** Is [q] a question (never anything that acts, never off-topic or a greeting alone)? */
    private fun asks(q: String): Boolean = Ask.parse(q).let { a ->
        a.command == null && a.order == null && a.topics.isNotEmpty() && a.topics.all { it in ANSWERS || it == Topic.GREETING } && a.topics.any { it in ANSWERS }
    }
}

/**
 * Words that bundle a question with something to do ("where is the call writing, then exit all"): the question handlers
 * that answer before the multi-step plan must not take them, or the action is silently dropped (review, 5 Oct). True when
 * the whole or any part split on then / and / commas / phir / aur would act. Pure.
 */
object Bundle {
    private val SPLIT = Regex("(?i)\\s*(?:,|;|\\band then\\b|\\bthen\\b|\\band\\b|\\bphir\\b|\\baur\\b|\\buske baad\\b)\\s*")

    fun acts(text: String): Boolean = runCatching {
        acted.of(text) {
            Corrections.acts(text) || SPLIT.split(text).map { it.trim() }.filter { it.isNotEmpty() }.let { parts ->
                // ("Explain my put then close it": a close by a pronoun is never a command alone, but said after something
                // else it asks for a close - left to the multi-step plan, which names the position and asks first. Round 12.)
                parts.size > 1 && parts.any { Corrections.acts(it) || Plan.pronounClose(it) }
            }
        }
    }.getOrDefault(true)

    /** The last words read ([Kept]; pure, and a reading that fails keeps nothing). */
    private val acted = Kept<Boolean>(64)

    // ---- Boss's reminders said with something else to do (review, 5 Oct) ----------------------------------------------

    /** Said when Boss's reminders are asked of in the same breath as something to do: nothing done, nothing cancelled. */
    const val REMINDER_AND_MORE = "Boss, ask me about the reminders on their own, then the rest - one at a time. Nothing was done and no reminder was cancelled."

    private val REMINDER_WORD = Regex("(?i)\\breminders?\\b")
    private val REMINDER_TAIL = Regex("(?i)\\breminders?\\b\\s*(.*)$")
    /** Words after "reminder" that are its own words ("the reminder to exit all trades" names a reminder, does nothing). */
    private val ITS_WORDS = Regex("(?i)^(about|to|for|of|that|saying|regarding|on|at|wala|waala|vala)\\b")

    /** Marks that bring in a reminder's own words straight after "reminder". */
    private const val INTRO = ":\"'\u201C\u2018"

    private fun actsAlone(s: String): Boolean = Corrections.acts(s) || Plan.pronounClose(s) || Ask.parse(s).let { it.order != null || it.command != null }

    /**
     * "Cancel the 3 pm reminder and close my nifty put": Boss's reminders named with something else to do - which the
     * reminder answers (list, cancel one, cancel all) must never take, or the close is silently dropped. The reminder's
     * own clause (and the words it is about) is never the action: only another part, or words straight after "reminder"
     * that are not its own ("cancel the 3 pm reminder close my put"). Pure; a reading that fails says yes (asked alone).
     */
    fun reminderAndMore(text: String): Boolean = REMINDER_WORD.containsMatchIn(text) && runCatching {
        SPLIT.split(text).map { it.trim() }.filter { it.isNotEmpty() }.any { part ->
            val tail = REMINDER_TAIL.find(part)
            if (tail == null) actsAlone(part)
            else tail.groupValues[1].trim().let { t ->
                // "set a reminder: exit all at 3", 'a reminder "close nifty"': words brought in by a colon or a quote are
                // the reminder's own, and so are its own words after one ("reminder: to exit all").
                val bare = t.trimStart { c -> c.isWhitespace() || c in INTRO }
                bare.isNotEmpty() && bare.length == t.length && !ITS_WORDS.containsMatchIn(bare) && actsAlone(bare)
            }
        }
    }.getOrDefault(true)
}
