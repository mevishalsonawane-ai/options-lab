package com.optionslab.ira

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Learning from the owner's corrections (Jarvis self-improvement, 2026-10-03): after "that was wrong", the next question
 * Boss asks and Jarvis understands is taken as what was meant, and the misunderstood words are read that way from then
 * on. Only questions are ever learned or rewritten - never a command or an order, so nothing learned can act. Pure.
 *
 * Learning Boss's words safely at scale (5 Oct): a rephrase within [REPHRASE_MS] of words Jarvis could not place (or
 * that Boss marked wrong) is never kept by itself - Jarvis asks "Shall I take ... to mean ... from now on?" ([propose],
 * [offer]) and keeps it only on Boss's yes, on an unlocked phone. Neither the words nor their meaning may act ([acts]:
 * commands, orders, alarms, reminders, notes, goals, settings), checked when proposed, when kept and when used. A
 * wording unused for [EXPIRE_DAYS] days is forgotten ([fresh]); "what words have you learned?" lists them ([words]),
 * "forget the word X" drops one ([forgetWordAsked], [forget]).
 */
object Corrections {
    /**
     * [used]: the day the wording was last kept or read as meant (null: kept before days were noted); [since]: the day Boss
     * said yes to it (null: kept before that was noted) - for what was learned when ([Learnings]).
     */
    data class Learned(val wrong: String, val right: String, val used: LocalDate? = null, val since: LocalDate? = null)

    /** A learned wording not used for this many days is forgotten. */
    const val EXPIRE_DAYS = 60L

    const val KEEP = 100
    /** How alike two wordings must be (0..1, by edit distance) to count as the same. */
    const val SAME = 0.85

    private val FILLER = Regex(" (jarvis|hey|ok|okay|please|boss|um|uh|so|tell me|can you|could you) ")

    fun normalize(text: String): String {
        var t = Spaced.words(text)
        repeat(3) { t = FILLER.replace(t, " ") }
        return t.replace(rx("\\s+"), " ").trim()
    }

    /** A question (asks, never acts) that Jarvis understands. */
    fun understood(text: String): Boolean {
        val q = Ask.parse(text)
        return q.command == null && q.order == null && Topic.COMMAND !in q.topics && Topic.ORDER !in q.topics && Topic.OFF_TOPIC !in q.topics
    }

    /** What to learn from [wrong] (what Boss said first) and [right] (the rephrasing Jarvis understood), or null. */
    fun learn(wrong: String, right: String): Learned? {
        val w = normalize(wrong); val r = normalize(right)
        if (w.isEmpty() || r.isEmpty() || w == r || w.length > 200) return null
        if (!understood(right)) return null
        // A misheard command or order is never learned as a question in its place (it must be said again); a meaning
        // that would act (an alarm, a reminder, a setting...) is never learned either.
        if (acts(wrong) || acts(right)) return null
        // Jarvis's own words about his wordings ("what words have you learned", "forget the word ...") are never learned.
        if (meta(wrong) || meta(right)) return null
        // Words that were understood are learned only as a near rewording, never as an unrelated next question.
        if (understood(wrong) && similarity(w, r) < 0.5) return null
        return Learned(w, r)
    }

    /** How long after a missed question (or "that was wrong") its rephrasing is still taken as one. */
    const val REPHRASE_MS = 60_000L

    private val ABOUT = setOf(Topic.OVERVIEW, Topic.TREND, Topic.LEVELS, Topic.PATTERNS, Topic.NEWS, Topic.VOLATILITY, Topic.WHY,
        Topic.EXPLAIN, Topic.BACKTEST, Topic.TRADE_CHECK, Topic.ACCOUNT)

    /**
     * Words Jarvis could not place (he answered "I'm not sure") that may be learned from Boss's next wording: a short
     * question with no numbers, not small talk and not about Jarvis himself (Boss, 4 Oct: "what's going on").
     */
    fun missed(text: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty() || t.split(" ").size > 8 || rx("\\d").containsMatchIn(t)) return false
        if (acts(text) || Chat.personal(text) || Chat.smallTalk(text, 0) != null) return false
        return Topic.OFF_TOPIC in Ask.parse(text).topics
    }

    /**
     * Boss rephrased a missed question (no "that was wrong"): learned only when the new words ask about the markets or
     * the account - never small talk, help, a command or an order.
     */
    fun rephrase(missed: String, right: String): Learned? {
        if (!missed(missed)) return null
        val q = Ask.parse(right)
        if (q.topics.none { it in ABOUT } || Topic.SUGGEST in q.topics) return null
        return learn(missed, right)
    }

    /**
     * What to put to Boss after he rephrased [original] (words Jarvis could not place when [missed], else words he marked
     * wrong) as [rephrase], or null: only a rephrase understood as a question (not small talk), neither wording acting,
     * and not a wording already learned so. It is kept only on his yes ([offer]; checked again then by [safe]).
     */
    fun propose(original: String, rephrase: String, missed: Boolean, learned: List<Learned> = emptyList()): Learned? {
        if (Chat.smallTalk(rephrase, 0) != null) return null
        val l = (if (missed) rephrase(original, rephrase) else learn(original, rephrase)) ?: return null
        if (learned.any { it.wrong == l.wrong && it.right == l.right }) return null
        return l
    }

    /** How [offer] begins (a note beside the answer, never the answer itself). */
    const val OFFER = "Shall I take \""

    /** The question put to Boss (yes or no). */
    fun offer(l: Learned): String = OFFER + "${l.wrong}\" to mean \"${l.right}\" from now on, Boss? It stays a question only."

    /** Said on his yes. */
    fun kept(l: Learned): String = "Learned, Boss: \"${l.wrong}\" now means \"${l.right}\" - a question only. Say \"forget the word ${l.wrong}\" to undo."

    /** May [l] be kept or used? The same checks as learning it, again (nothing that acts, ever). */
    fun safe(l: Learned): Boolean = runCatching { learn(l.wrong, l.right)?.let { it.wrong == l.wrong && it.right == l.right } == true }.getOrDefault(false)

    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val n = a.length; val m = b.length
        if (n == 0 || m == 0) return 0.0
        var prev = IntArray(m + 1) { it }
        for (i in 1..n) {
            val cur = IntArray(m + 1); cur[0] = i
            for (j in 1..m) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return 1.0 - prev[m].toDouble() / maxOf(n, m)
    }

    /**
     * [text] as Boss meant it, from what was learned, or null. Only when the words as said are not understood (or are
     * exactly a learned wording), and only to a question - never to anything that acts.
     */
    fun apply(text: String, learned: List<Learned>): String? = match(text, learned)?.right

    /** The learned wording [text] is read as (see [apply]), or null - to note it as used ([touch]). */
    fun match(text: String, learned: List<Learned>): Learned? {
        if (learned.isEmpty() || acts(text) || meta(text)) return null
        val t = normalize(text)
        val exact = learned.lastOrNull { it.wrong == t }
        // Nearly the same words: only when they were not understood, or name no market where the correction does
        // (a misheard "nifti"), so a clear question is never re-read.
        val named = Market.mentioned(text).isNotEmpty()
        val hit = exact ?: learned.filter { l -> similarity(l.wrong, t) >= SAME && (!understood(text) || !named && Market.mentioned(l.right).isNotEmpty()) }
            .maxByOrNull { similarity(it.wrong, t) }
        val h = hit ?: return null
        // Used only while it is still a question that does not act (an entry kept before the checks grew stays unused).
        return h.takeIf { understood(it.right) && !acts(it.right) && !meta(it.right) }
    }

    /**
     * Words that would act or set anything: a command, an order, an alarm, a reminder, a note or fact to keep (or to
     * forget), a goal, a setting, the automatic-stop choice, forgetting a learned word - as said or read from Hinglish.
     * Anything unreadable counts as acting.
     */
    fun acts(text: String): Boolean = runCatching { acted.of(text) { actsAsSaid(text) || Hinglish.normalize(text).let { it != text && actsAsSaid(it) } } }.getOrDefault(true)

    /** The last words read ([Kept]: every reader here reads only the words; one that fails keeps nothing). */
    private val acted = Kept<Boolean>(64)

    private fun actsAsSaid(text: String): Boolean = Commands.parse(text) != null ||
        Ask.parse(text).let { it.order != null || it.command != null || Topic.ORDER in it.topics || Topic.COMMAND in it.topics } ||
        FollowUp.acts(text) || Reminder.asked(text) || Reminder.cancelAsked(text) || Memory.toKeep(text) != null || Memory.forgetAsked(text) ||
        AboutBoss.forgetAsked(text) != null || AboutBoss.fact(text) != null || Goals.read(text) != null || Goals.clearAsked(text) ||
        AutoStop.read(text) != null || forgetWordAsked(text) != null

    /** About Jarvis's learned wordings themselves: never learned, never rewritten. */
    private fun meta(text: String): Boolean = wordsAsked(text) || forgetWordAsked(text) != null ||
        Learnings.asked(text) != null || Learnings.undoAsked(text)

    // ---- the wordings kept: expiry, listing, forgetting ---------------------------------------------------------------

    /** [learned] without the wordings unused for more than [EXPIRE_DAYS] days by [today] (undated ones are kept). */
    fun fresh(learned: List<Learned>, today: LocalDate): List<Learned> =
        learned.filter { it.used == null || !it.used.plusDays(EXPIRE_DAYS).isBefore(today) }

    /** [learned] with [l] noted as used [today]. */
    fun touch(learned: List<Learned>, l: Learned, today: LocalDate): List<Learned> =
        learned.map { if (it.wrong == l.wrong) it.copy(used = today) else it }

    private fun norm(text: String) = Spaced.words(text)

    private val WORDS_ASKED = Regex("^ (jarvis )?((what|which) (words|wordings|phrases)( of mine)? (have|did|do) you (learned|learnt|learn|know)( from me)?|" +
        "(show|list|tell)( me)? (the |your )?(words|wordings|phrases) you (have )?(learned|learnt)|(your )?learned (words|wordings)|" +
        "what have you learned from my (words|wording|wordings)) $")

    /** "What words have you learned?" */
    fun wordsAsked(text: String): Boolean = WORDS_ASKED.containsMatchIn(norm(text))

    private val FORGET_WORD = Regex("^ (jarvis )?(please )?(forget|drop|unlearn) (the )?(word|words|wording|phrase) (.+?)( please)? $")

    /** "Forget the word X": X (as normalized), or null. */
    fun forgetWordAsked(text: String): String? =
        FORGET_WORD.find(norm(text))?.groupValues?.get(6)?.let { normalize(it) }?.takeIf { it.isNotEmpty() }

    /** [learned] split into what is kept and what [word] names (the wording itself, nearly it, or all its words in it). */
    fun forget(learned: List<Learned>, word: String): Pair<List<Learned>, List<Learned>> {
        val w = normalize(word)
        if (w.isEmpty()) return learned to emptyList()
        val ws = w.split(" ").toSet()
        val (gone, kept) = learned.partition { l -> l.wrong == w || similarity(l.wrong, w) >= SAME || ws.all { it in l.wrong.split(" ") } }
        return kept to gone
    }

    fun forgot(removed: List<Learned>, word: String): String = when {
        removed.isEmpty() -> "I haven't learned \"${normalize(word)}\", Boss. Ask \"what words have you learned?\" to hear them."
        removed.size == 1 -> "Done, Boss: \"${removed[0].wrong}\" no longer means \"${removed[0].right}\"."
        else -> "Done, Boss: I've forgotten ${removed.size} wordings: " + removed.joinToString(", ") { "\"${it.wrong}\"" } + "."
    }

    /** "What words have you learned?": the newest first, when each was last used, and the rule that drops them. */
    fun words(learned: List<Learned>, today: LocalDate, max: Int = 8): String {
        val ls = fresh(learned, today)
        if (ls.isEmpty()) return "I haven't learned any of your wordings yet, Boss. When I miss something and you say it another way, I'll ask before I keep it."
        val shown = ls.takeLast(max).reversed().joinToString("; ") { l ->
            val ago = l.used?.let { ChronoUnit.DAYS.between(it, today) }
            "\"${l.wrong}\" means \"${l.right}\"" + when { ago == null -> ""; ago <= 0L -> " (used today)"; ago == 1L -> " (used yesterday)"; else -> " (used $ago days ago)" }
        }
        val more = if (ls.size > max) " And ${ls.size - max} more." else ""
        return "Boss, I've learned ${ls.size} of your wording${if (ls.size == 1) "" else "s"} - questions only: $shown.$more " +
            "Each is forgotten after $EXPIRE_DAYS days unused; say \"forget the word ...\" to drop one."
    }

    fun lines(learned: List<Learned>): List<String> =
        if (learned.isEmpty()) emptyList() else listOf("What I've learned from your corrections:") + learned.takeLast(10).reversed().map { "\"${it.wrong}\" means \"${it.right}\"." }
}
