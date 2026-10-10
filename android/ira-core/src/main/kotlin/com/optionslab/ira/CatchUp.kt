package com.optionslab.ira

import com.optionslab.ira.TodayNotes.Category
import com.optionslab.ira.TodayNotes.Note
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * "Catch me up" (Boss, 07 Oct 2026): Boss comes back to the phone, holds the mic and says "catch me up", "read your notes",
 * "notes padh do" or "kya hua jab main nahi tha" - Jarvis says a short digest of the notes he posted by himself
 * ([TodayNotes]) since Boss last had the Ira page in front of him, or since the last catch-up, whichever is later: at
 * most [MAX_SAID] headlines ([TodayNotes.headline]), grouped by category in [ORDER] (Liquidity's trades first), each
 * category's newest first, and a count of the rest ("and 3 more market notes - they're in Today's notes"). Nothing new:
 * [NOTHING]. On a locked phone, only how many of each category - never a note's words. The notes are kept in memory only:
 * when the anchor is before the app last started ([restarted]), the digest says so first rather than "nothing new".
 *
 * "Read my notes", "mere notes" are Boss's own trade notes ([Account]'s reasons), not these.
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
    /** After a restart with nothing posted since. */
    const val NOTHING_SINCE = "Nothing new since then, Boss."
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

    /**
     * [day]'s notes posted after [anchor] (all of [day]'s when null), newest first. Compared to the nanosecond, as kept: a
     * note posted later in the same second as the anchor is still after it.
     */
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
     * "I restarted at 10:05 - notes from before then aren't kept.": the notes live in memory only, so when [anchor] is before
     * [keptSince] (when this run of the app started keeping them) on [day], the ones in between are gone. Null otherwise
     * (no anchor: "today so far"; started before [day]: all of [day]'s are kept).
     */
    fun restarted(anchor: LocalDateTime?, keptSince: LocalDateTime?, day: LocalDate): String? =
        if (anchor != null && keptSince != null && keptSince.toLocalDate() == day && anchor.isBefore(keptSince))
            "I restarted at ${TodayNotes.time(keptSince)} - notes from before then aren't kept."
        else null

    /**
     * The catch-up on [day]'s [notes] posted after [anchor] ([CatchUp.anchor]). [locked]: only the counts by category, never
     * a note's words. [keptSince]: when this run of the app started keeping notes ([restarted]). Words only.
     */
    fun digest(notes: List<Note>, day: LocalDate, anchor: LocalDateTime?, locked: Boolean, keptSince: LocalDateTime? = null): String {
        val fresh = since(notes, day, anchor)
        val restart = restarted(anchor, keptSince, day)
        if (fresh.isEmpty()) return if (restart == null) NOTHING else "$restart $NOTHING_SINCE"
        val lead = when {
            restart != null -> "$restart Since then"
            anchor == null -> "Today so far"
            else -> "Since you last looked"
        }
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
        "^ ${LEAD}catch me up( on (your |the )?notes| on what i missed| quickly)?$TAIL|" +
        // "read your notes", "read me your notes", "read out the notes", "read your notes to me" ("read my notes" is Boss's own: Account)
        "^ ${LEAD}read (me |out |out to me )?(your|the) notes( out| to me| out to me| aloud)?$TAIL|" +
        // "notes padh do", "tumhare notes padh do", "notes padho", "notes padh ke sunao", "notes suna do", "notes sunao" ("mere notes": Boss's own)
        "^ $LEAD(tumhare |aapke )?notes (padh|parh|padh ke|parh ke) ?(do|dena|ke sunao|sunao|ke suna do|suna do)$TAIL|" +
        "^ $LEAD(tumhare |aapke )?notes (padho|parho|sunao|suna do)$TAIL|" +
        // "kya hua jab main nahi tha", "jab main nahi tha tab kya hua", "main nahi tha tab kya hua"
        "^ $LEAD(mere peeche |mere piche )?kya (kya )?hua jab (main|mai|mein|me) (nahi|nahin|nai) (tha|thi)$TAIL|" +
        "^ $LEAD(jab )?(main|mai|mein|me) (nahi|nahin|nai) (tha|thi) (tab|to|toh) kya (kya )?hua$TAIL"
    )

    /** "Catch me up", "read your notes", "notes padh do", "kya hua jab main nahi tha" (not "read my notes": Boss's own). */
    fun asked(text: String): Boolean = ASKED.containsMatchIn(Spaced.joined(text))
}
