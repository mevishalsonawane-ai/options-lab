package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * "Today's notes" (Boss, 06 Oct 2026): Jarvis posts many notes in the chat by himself - the opening read, Liquidity
 * heads-ups, trade lessons, Solo and Hero, the coach's words, the 15:35 wrap-up, tomorrow's plan, news on a position - and
 * they get lost among the answers. Each note is kept with its time and a category (from the automation that posted it
 * when known, else read from its first words), so they can be listed newest first, filtered by category, and summed up
 * when asked ("what did you tell me today", "today's notes", "aaj kya bataya").
 *
 * Read only: nothing here says, places, closes, arms or switches anything - what was said and when stays as it was. Pure.
 */
object TodayNotes {
    /** A note's category: the filter chips in [FILTERS] order; [OTHER] for the rest (the app's own health, reminders). */
    enum class Category(val label: String) {
        MARKET("Market"), LIQUIDITY("Liquidity"), SOLO_HERO("Solo/Hero"), COACH("Coach"), PLANS("Plans"), NEWS("News"), OTHER("Other"),
    }

    /** The filter chips always shown; [Category.OTHER] joins them only when a note of it is there ([chips]). */
    val FILTERS: List<Category> = listOf(Category.MARKET, Category.LIQUIDITY, Category.SOLO_HERO, Category.COACH, Category.PLANS, Category.NEWS)

    /** At most this many notes are kept (the newest). */
    const val MAX = 200

    /** How many of the latest notes the digest names. */
    const val DIGEST_LATEST = 3

    /** A headline is cut (at a word) to about this many characters. */
    const val HEADLINE_MAX = 90

    /** The source Jarvis's own Solo arm is tagged with (its switch is Solo's own, not an automation's). */
    const val SOLO = "SOLO"

    /**
     * A kept note: when it was posted, its words exactly as posted, its category and - when known - the automation that
     * posted it (its name, e.g. "LIQUIDITY", or [SOLO]).
     */
    data class Note(val at: LocalDateTime, val text: String, val category: Category, val source: String? = null)

    /** A note's category and source. */
    data class Tag(val category: Category, val source: String?)

    /** Each automation (by its name in the app's list) and the category its notes go under. */
    private val BY_SOURCE: Map<String, Category> = buildMap {
        for (s in listOf("GAP", "ORB", "MOMENTS", "VIX", "SHARPMOVE", "OI", "EXPIRYDAY", "OPENING")) put(s, Category.MARKET)
        for (s in listOf("LIQUIDITY", "LIQSKIP")) put(s, Category.LIQUIDITY)
        put(SOLO, Category.SOLO_HERO)
        for (s in listOf("OVERTRADE", "TARGET", "HEADSUP", "USUAL", "PRETRADE", "WORDS", "BOTS", "HEALTH", "MIS", "EXPIRY", "TRAIL", "GUARD",
            "RESCUE", "STALE", "COOLOFF", "WEEK", "MONTH", "JOURNAL", "SUMMARY", "FORWARD", "LIQINSIGHT")) put(s, Category.COACH)
        for (s in listOf("TOMORROW", "AGENDA", "PLAN", "MORNING_VOICE")) put(s, Category.PLANS)
        for (s in listOf("POSNEWS", "ACT_PAPER")) put(s, Category.NEWS)
        for (s in listOf("RELAY", "FEED", "BACKUP", "SELFHEAL", "QUIET")) put(s, Category.OTHER)
    }

    /** The category an automation's notes go under, or null for a name not known. */
    fun categoryOf(source: String?): Category? = source?.let { BY_SOURCE[it] }

    /**
     * A note's tag: the automation's category when [source] is known, else [kind] (the poster's own word), else read from
     * the note's first words (the opening read, Solo's and Hero's lines), else [Category.OTHER]. Never changes the words.
     */
    fun tag(text: String, source: String? = null, kind: Category? = null): Tag {
        categoryOf(source)?.let { return Tag(it, source) }
        if (kind != null) return Tag(kind, null)
        val t = text.trimStart()
        return when {
            t.startsWith("Opening read") -> Tag(Category.MARKET, "OPENING")
            t.startsWith("Solo") -> Tag(Category.SOLO_HERO, SOLO)
            t.startsWith("Hero") -> Tag(Category.SOLO_HERO, null)
            else -> Tag(Category.OTHER, null)
        }
    }

    /** [kept] with [add] joined: only [add]'s day kept (an older day's notes go), at most [max], oldest first. */
    fun keep(kept: List<Note>, add: Note, max: Int = MAX): List<Note> {
        val day = add.at.toLocalDate()
        return (kept.filter { it.at.toLocalDate() == day } + add).takeLast(max.coerceAtLeast(1))
    }

    /** [day]'s notes, newest first (the order posted kept for the same minute). */
    fun of(notes: List<Note>, day: LocalDate): List<Note> =
        notes.withIndex().filter { it.value.at.toLocalDate() == day }
            .sortedWith(compareByDescending<IndexedValue<Note>> { it.value.at }.thenByDescending { it.index }).map { it.value }

    /** The notes of [category] (null: all), in the order given. */
    fun filter(notes: List<Note>, category: Category?): List<Note> = if (category == null) notes else notes.filter { it.category == category }

    /** How many notes each category has, in [Category] order, only the ones with any. */
    fun counts(notes: List<Note>): List<Pair<Category, Int>> =
        Category.entries.map { c -> c to notes.count { it.category == c } }.filter { it.second > 0 }

    /** The filter chips: [FILTERS], and [Category.OTHER] when a note of it is there. */
    fun chips(notes: List<Note>): List<Category> = if (notes.any { it.category == Category.OTHER }) FILTERS + Category.OTHER else FILTERS

    /** The automations behind [category]'s notes, newest first, each once (for its "Turn these off" shortcut). */
    fun sources(notes: List<Note>, category: Category): List<String> = notes.filter { it.category == category }.mapNotNull { it.source }.distinct()

    /** "09:20". */
    fun time(at: LocalDateTime): String = "%02d:%02d".format(java.util.Locale.ENGLISH, at.hour, at.minute)

    /** A note's first line, its first sentence, cut at a word to about [HEADLINE_MAX] characters. */
    fun headline(text: String): String {
        val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val end = Regex("[.!?](\\s|$)").find(line)?.range?.first
        val first = (if (end != null && end > 0) line.substring(0, end) else line).trim().trimEnd('.', ',', ';', ':', '-', ' ')
        if (first.length <= HEADLINE_MAX) return first
        val cut = first.substring(0, HEADLINE_MAX)
        val space = cut.lastIndexOf(' ')
        return (if (space > HEADLINE_MAX / 2) cut.substring(0, space) else cut).trimEnd('.', ',', ';', ':', '-', ' ') + "…"
    }

    /** "3 notes" / "1 note". */
    private fun count(n: Int) = if (n == 1) "1 note" else "$n notes"

    /** Where every note can be read. */
    const val WHERE = "Every one is under Today's notes on the Ira page."
    const val NONE = "I haven't posted any notes by myself today yet, Boss. When I do, they're listed under Today's notes on the Ira page."
    const val LOCKED = "Unlock the phone for today's notes, Boss - they may name your account."

    /**
     * The digest of [day]'s notes: how many, by category, and the [DIGEST_LATEST] most recent headlines with their time.
     * Words only; nothing is said again in full.
     */
    fun digest(notes: List<Note>, day: LocalDate): String {
        val today = of(notes, day)
        if (today.isEmpty()) return NONE
        val by = counts(today).joinToString(", ") { (c, n) -> "${c.label} ${n}" }
        val latest = today.take(DIGEST_LATEST).joinToString("; ") { "${time(it.at)} (${it.category.label}) ${headline(it.text)}" }
        val lead = if (today.size == 1) "The latest" else "The latest ${minOf(DIGEST_LATEST, today.size)}"
        return "Today I've posted ${count(today.size)} by myself, Boss: $by. $lead: $latest. $WHERE"
    }

    // ---- asked ----------------------------------------------------------------------------------------------------

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |okay jarvis |boss |so |and |please |ok |okay |hi |hello |bata |batao |can you |could you )*"
    private const val TAIL = "( boss| jarvis| please| then| now| yaar| na| bhai)* $"
    private const val TODAY = "(today|today so far|so far today|this morning)"
    private val ASKED = Regex(
        // "what did you tell me today", "what have you told me today", "what all did you say today", "what did you post today"
        "^ $LEAD(what|wat) (all )?(did|have|has) you (tell|told|say|said|post|posted) (to )?(me |us )?(all )?$TODAY$TAIL|" +
        // "what notes did you post today", "which notes have you posted today"
        "^ $LEAD(what|which) notes (did|have) you (post|posted|put|send|sent|give|given) (me )?$TODAY$TAIL|" +
        // "today's notes", "show today's notes", "your notes today", "notes for today", "your notes from today"
        "^ $LEAD(show |show me |read |read me |give me |open |list |tell me |what are )?(your )?(todays |today s |today )notes( please| so far)?$TAIL|" +
        "^ $LEAD(show |show me |read |read me |give me |open |list |tell me |what are )?(your )?notes (for |from |of )?today( please| so far)?$TAIL|" +
        // "aaj kya bataya", "aaj tumne kya bataya", "tumne aaj kya bataya", "aaj kya kya bataya", "aaj ke notes"
        "^ $LEAD(tumne |aapne |apne |tune )?(aaj|aj) (tumne |aapne |apne |tune )?(kya|kya kya) (bataya|batayaa|bola|kaha|likha)( tumne| aapne| hai| tha| mujhe)?$TAIL|" +
        "^ $LEAD(aaj|aj) ke notes( dikhao| batao| dikha| bata)?$TAIL"
    )
    /** Never this: another day, a topic named (its own answer), Boss's own notes, an act. */
    private val NOT = Regex(" (yesterday|kal|tomorrow|last|week|month|about|on|nifty|banknifty|finnifty|sensex|vix|gold|my|mine|i|buy|sell|" +
        "trade|close|exit|stop|start|arm|remind|alert|delete|clear|forget) ")

    /** "What did you tell me today", "today's notes", "aaj kya bataya". */
    fun asked(text: String): Boolean {
        val t = Spaced.joined(text)
        if (NOT.containsMatchIn(t)) return false
        return ASKED.containsMatchIn(t)
    }
}
