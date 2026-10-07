package com.optionslab.ira

import com.optionslab.ira.TodayNotes.Category
import com.optionslab.ira.TodayNotes.Note
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * "Catch me up" (Boss, 07 Oct 2026): Boss comes back to the phone, holds the mic and says "catch me up", "read my notes",
 * "notes padh do" or "kya hua jab main nahi tha" - Jarvis says a short digest of the notes he posted by himself
 * ([TodayNotes]) since Boss last had the Ira page in front of him, or since the last catch-up, whichever is later: at
 * most [MAX_SAID] headlines ([TodayNotes.headline]), grouped by category in [ORDER] (Liquidity's trades first), each
 * category's newest first, and a count of the rest ("and 3 more market notes - they're in Today's notes"). Nothing new:
 * [NOTHING]. On a locked phone, only how many of each category - never a note's words.
 *
 * "What did I miss" keeps its own answer (everything Jarvis said by himself since Boss last asked, [Reminder.missedAsked]).
 *
 * Read only: nothing here says, places, closes, arms or switches anything; the notes stay as they were. Pure.
 */
object CatchUp {
    /** How many headlines the digest says at most. */
    const val MAX_SAID = 5

    /** The categories, most important first (the digest's groups in this order). */
    val ORDER: List<Category> = listOf(Category.LIQUIDITY, Category.SOLO_HERO, Category.MARKET, Category.COACH, Category.PLANS, Category.NEWS, Category.OTHER)

    /** The settings kept for it (this phone only: [Upkeep.PRIVATE]). */
    const val KEY_PREFIX = "jarvis.catchup."
    /** When the Ira page was last in front of Boss (it stopped being visible). */
    const val KEY_SEEN = KEY_PREFIX + "seen"
    /** When the last catch-up was said. */
    const val KEY_DONE = KEY_PREFIX + "done"

    const val NOTHING = "Nothing new since you last looked, Boss."
    const val WHERE = "they're in Today's notes"

    /** The digest's group name for [c], as said. */
    fun label(c: Category): String = when (c) {
        Category.LIQUIDITY -> "Liquidity"
        Category.SOLO_HERO -> "Solo and Hero"
        else -> c.label
    }

    /** "market note" / "Liquidity note" - the counts' words. */
    private fun noun(c: Category): String = when (c) {
        Category.LIQUIDITY -> "Liquidity"
        Category.SOLO_HERO -> "Solo and Hero"
        Category.PLANS -> "plan"
        else -> c.label.lowercase()
    }

    private fun notes(n: Int, c: Category, more: Boolean): String =
        "$n ${if (more) "more " else ""}${noun(c)} note${if (n == 1) "" else "s"}"

    private fun and(parts: List<String>): String =
        if (parts.size <= 1) parts.joinToString("") else parts.dropLast(1).joinToString(", ") + " and " + parts.last()

    /** From when: the later of when the page was last seen and the last catch-up; null (all of today's) when neither is known. */
    fun anchor(lastSeen: LocalDateTime?, lastCatchUp: LocalDateTime?): LocalDateTime? = listOfNotNull(lastSeen, lastCatchUp).maxOrNull()

    /** [day]'s notes posted after [anchor] (all of [day]'s when null), newest first. */
    fun since(notes: List<Note>, day: LocalDate, anchor: LocalDateTime?): List<Note> =
        TodayNotes.of(notes, day).filter { anchor == null || it.at.isAfter(anchor) }

    /** What the digest says: the headlines' notes by category (in [ORDER], each newest first), and how many of each are left. */
    data class Pick(val said: List<Pair<Category, List<Note>>>, val rest: List<Pair<Category, Int>>)

    /**
     * Of [fresh] (newest first), at most [max] to say: each category's newest in [ORDER] first, then each one's next, and so
     * on - so Liquidity's newest trade is never crowded out by many market notes, and every category is heard before any
     * says a second.
     */
    fun pick(fresh: List<Note>, max: Int = MAX_SAID): Pick {
        val by = ORDER.map { c -> c to fresh.filter { it.category == c } }.filter { it.second.isNotEmpty() }
        val taken = IntArray(by.size)
        var left = max.coerceAtLeast(0)
        var round = 0
        while (left > 0 && by.any { it.second.size > round }) {
            for ((i, g) in by.withIndex()) {
                if (left == 0) break
                if (g.second.size > round) { taken[i]++; left-- }
            }
            round++
        }
        val said = by.withIndex().filter { taken[it.index] > 0 }.map { (i, g) -> g.first to g.second.take(taken[i]) }
        val rest = by.withIndex().map { (i, g) -> g.first to g.second.size - taken[i] }.filter { it.second > 0 }
        return Pick(said, rest)
    }

    /** A headline as said: [TodayNotes.headline] without the cut's ellipsis. */
    private fun said(n: Note): String = TodayNotes.headline(n.text).removeSuffix("…").trimEnd('.', ',', ';', ':', '-', ' ')

    /**
     * The catch-up on [day]'s [notes] posted after [anchor] ([CatchUp.anchor]). [locked]: only the counts by category, never
     * a note's words. Words only.
     */
    fun digest(notes: List<Note>, day: LocalDate, anchor: LocalDateTime?, locked: Boolean): String {
        val fresh = since(notes, day, anchor)
        if (fresh.isEmpty()) return NOTHING
        val lead = if (anchor == null) "Today so far" else "Since you last looked"
        val total = if (fresh.size == 1) "1 note" else "${fresh.size} notes"
        if (locked) {
            val counts = ORDER.map { c -> c to fresh.count { it.category == c } }.filter { it.second > 0 }.map { (c, n) -> notes(n, c, more = false) }
            return "$lead, Boss: ${and(counts)}. Unlock the phone to hear them."
        }
        val p = pick(fresh)
        val groups = p.said.joinToString(" ") { (c, ns) -> "${label(c)}: " + ns.joinToString("; ") { said(it) } + "." }
        val rest = if (p.rest.isEmpty()) "" else " And " + and(p.rest.map { (c, n) -> notes(n, c, more = true) }) + " - $WHERE."
        return "$lead, Boss, I posted $total. $groups$rest"
    }

    // ---- asked ----------------------------------------------------------------------------------------------------

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |okay jarvis |boss |so |please |ok |okay |hi |hello |can you |could you |just )*"
    private const val TAIL = "( boss| jarvis| please| now| yaar| na| bhai| jaldi se)* $"
    private val ASKED = Regex(
        // "catch me up", "catch me up on your notes", "catch me up on what i missed"
        "^ ${LEAD}catch me up( on (your |my |the )?notes| on what i missed| quickly)?$TAIL|" +
        // "read my notes", "read me my notes", "read out my notes", "read your notes to me", "read my notes out"
        "^ ${LEAD}read (me |out |out to me )?(my|your|the) notes( out| to me| out to me| aloud)?$TAIL|" +
        // "notes padh do", "mere notes padh do", "notes padho", "notes padh ke sunao", "notes suna do", "notes sunao"
        "^ $LEAD(mere |mera |apne |tumhare |aapke )?notes (padh|parh|padh ke|parh ke) ?(do|dena|ke sunao|sunao|ke suna do|suna do)$TAIL|" +
        "^ $LEAD(mere |mera |apne |tumhare |aapke )?notes (padho|parho|sunao|suna do)$TAIL|" +
        // "kya hua jab main nahi tha", "jab main nahi tha tab kya hua", "main nahi tha tab kya hua"
        "^ $LEAD(mere peeche |mere piche )?kya (kya )?hua jab (main|mai|mein|me) (nahi|nahin|nai) (tha|thi)$TAIL|" +
        "^ $LEAD(jab )?(main|mai|mein|me) (nahi|nahin|nai) (tha|thi) (tab|to|toh) kya (kya )?hua$TAIL"
    )

    /** "Catch me up", "read my notes", "notes padh do", "kya hua jab main nahi tha". */
    fun asked(text: String): Boolean = ASKED.containsMatchIn(Spaced.joined(text))
}
