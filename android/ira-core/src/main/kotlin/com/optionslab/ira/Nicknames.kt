package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Jarvis learning the nicknames Boss uses for his arms and positions (learning round 24, 2026-10-05). When Boss names an
 * arm or a position in words Jarvis cannot match to exactly one ("stop the scalper") Jarvis asks "Which one? 1. ...
 * 2. ..." as always ([asking]). When Boss's very next command of that kind, within [REACT_MINUTES] minutes, picks one of
 * that very list - by its number ("stop strategy 2") or by a name that matches exactly one - that is his "yes, I meant
 * X" ([picked]): only the words he first used and the one name he picked are noted (never a secret: words that look like
 * one are never kept), one name per nickname.
 *
 * Next time his command names an arm or a position in those words and they still match none or several by themselves,
 * the nickname is read for that one name ([resolve]) - only when exactly one arm or position of that kind now has it;
 * else Jarvis asks which, as before. Understanding only, never wider: a nickname resolves to exactly one arm or position
 * (never to "all", never to two), it never stands for a command, and the action it names still goes through its normal
 * confirm - said with the reading ("by \"the scalper\" I took ORB BankNifty, as you told me before") so Boss can say no.
 * Nothing learned acts: the app uses it only for a command Boss said himself, never for a reading of his words, a plan,
 * a later command or Jarvis's own checks.
 *
 * "What nicknames do I use?" names them ([Request.WHICH], unlocked only: they name his arms); "forget my nicknames for my
 * arms" ([UNDO], [Request.RESET]) forgets them all. Shown in the Learnings ledger with that undo; "undo everything you
 * learned this week" forgets those learned in the last [Learnings.DAYS] days ([forgetWeek]). Pure: the app keeps the log.
 */
object Nicknames {
    /** A pick counts for the "Which one?" asked at most this many minutes before it. */
    const val REACT_MINUTES = 3L
    /** Nicknames kept at most (the oldest dropped first). */
    const val KEEP = 40
    /** A nickname is at most this many words... */
    const val MAX_WORDS = 4
    /** ...and this many characters. */
    const val MAX_CHARS = 40

    const val UNDO = "forget my nicknames for my arms"

    /** What a nickname names: one of Boss's arms or strategies, or one of his open positions. */
    enum class Family(val noun: String) { ARM("arm"), POSITION("position") }

    /** One nickname: Boss's words (spaced, lower case) and the one name he picked for them, and when. */
    data class Note(val at: LocalDateTime, val family: Family, val words: String, val name: String)

    /** The nicknames kept. */
    data class Log(val notes: List<Note> = emptyList())

    /** Jarvis's "Which one?": the words Boss used, the kind of thing, and the names listed, in order (in memory only). */
    data class Asked(val at: LocalDateTime, val family: Family, val words: String, val names: List<String>)

    /** The family a command of [kind] names one of, or null (only a single arm or a single position). */
    fun family(kind: Command.Kind): Family? = when (kind) {
        Command.Kind.STOP_ONE, Command.Kind.START_ONE -> Family.ARM
        Command.Kind.CLOSE_ONE -> Family.POSITION
        else -> null
    }

    /** Words that never make a nickname by themselves: they name everything, nothing, or only a kind. */
    private val EMPTY = setOf("the", "my", "that", "this", "it", "one", "wala", "wali", "vala", "vali", "strategy", "strategies",
        "arm", "arms", "bot", "bots", "algo", "algos", "script", "position", "positions", "trade", "trades", "please", "boss", "jarvis")
    /** Words that would widen a nickname to more than one thing: never kept. */
    private val WIDE = setOf("all", "every", "everything", "each", "both", "sab", "sabhi", "saare", "sare", "any", "others", "rest")
    /** Words that are a number or an ordinal: a pick, never a name. */
    private val COUNTED = rx("^(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|first|second|third|fourth|fifth|last|number|no)$")

    /**
     * [words] as a nickname (spaced, lower case, "the" and "my" dropped at the start), or null when they cannot be one:
     * empty, only a kind ("the strategy"), a number, longer than [MAX_WORDS] words or [MAX_CHARS] characters, anything
     * that would widen it ("all", "both"), or anything that looks like a secret.
     */
    fun key(words: String?): String? {
        if (words == null || Secrets.hasSecret(words)) return null
        val toks = spacedWords(words.lowercase().replace("'", "").replace("’", "")).split(' ').filter { it.isNotEmpty() }
            .dropWhile { it == "the" || it == "my" || it == "that" || it == "this" }
        if (toks.isEmpty() || toks.size > MAX_WORDS) return null
        if (toks.all { it in EMPTY || COUNTED.matches(it) }) return null
        if (toks.any { it in WIDE }) return null
        val k = toks.joinToString(" ")
        return k.takeIf { it.length <= MAX_CHARS }
    }

    /** [name] as kept: a position without its quantity ("paper NIFTY25O2124500PE (75)" -> "paper NIFTY25O2124500PE"). */
    fun nameKey(family: Family, name: String): String =
        if (family == Family.POSITION) name.replace(rx("\\s*\\([^()]*\\)\\s*$"), "").trim() else name.trim()

    /** Jarvis asks which of [names] Boss meant by [words] at [at]: kept for his pick, or null when the words cannot be a nickname. */
    fun asking(family: Family, words: String?, names: List<String>, at: LocalDateTime): Asked? {
        val k = key(words) ?: return null
        if (names.isEmpty()) return null
        return Asked(at, family, k, names)
    }

    /**
     * Boss's command of [kind] at [now] picked [index] of [names]: the nickname noted, or null - only for the "Which one?"
     * [asked] within [REACT_MINUTES] minutes, of the same family, over the very same list, and never when his words are
     * already that name.
     */
    fun picked(asked: Asked?, kind: Command.Kind, index: Int?, names: List<String>, now: LocalDateTime): Note? {
        if (asked == null || index == null || index !in names.indices) return null
        if (family(kind) != asked.family || names != asked.names) return null
        if (now.isBefore(asked.at) || now.isAfter(asked.at.plusMinutes(REACT_MINUTES))) return null
        val name = nameKey(asked.family, names[index])
        if (name.isEmpty() || key(name) == asked.words) return null
        // Exactly one thing in that list carries the name kept: else the nickname could later stand for two.
        if (names.count { nameKey(asked.family, it) == name } != 1) return null
        return Note(now, asked.family, asked.words, name)
    }

    /** [log] with [note] kept: the same words in the same family name only the newest pick. */
    fun heard(log: Log, note: Note): Log =
        Log((log.notes.filterNot { it.family == note.family && it.words == note.words } + note).sortedBy { it.at }.takeLast(KEEP))

    /**
     * The index in [names] Boss's [words] stand for, by a nickname of [family], or null: only when exactly one of [names]
     * carries the name the nickname was kept for - never more, never by a guess.
     */
    fun resolve(log: Log, family: Family, words: String?, names: List<String>): Int? {
        val k = key(words) ?: return null
        val n = log.notes.lastOrNull { it.family == family && it.words == k } ?: return null
        val hits = names.indices.filter { nameKey(family, names[it]) == n.name }
        return hits.singleOrNull()
    }

    /** The nickname [words] are read as, when [resolve] took them ([took] says it). */
    fun note(log: Log, family: Family, words: String?): Note? {
        val k = key(words) ?: return null
        return log.notes.lastOrNull { it.family == family && it.words == k }
    }

    /** "Forget my nicknames for my arms": none kept. */
    fun forget(): Log = Log()

    /** Those learned in the last [Learnings.DAYS] days up to [today]. */
    fun week(log: Log, today: LocalDate): List<Note> = log.notes.filter { Learnings.thisWeek(it.at.toLocalDate(), today) }

    /** [log] without those learned in the last [Learnings.DAYS] days ("undo everything you learned this week"). */
    fun forgetWeek(log: Log, today: LocalDate): Log = Log(log.notes.filterNot { Learnings.thisWeek(it.at.toLocalDate(), today) })

    // ---- what is said ----------------------------------------------------------------------------------------------

    /** Beside the action's confirm when a nickname was read: "by "the scalper" I took ORB BankNifty, as you told me before". */
    fun took(n: Note): String = "by \"${n.words}\" I took ${n.name}, as you told me before, Boss"

    /** After Boss picked: what was kept. */
    fun learned(n: Note): String = "Noted, Boss: by \"${n.words}\" you mean ${n.name}. Next time I'll take it so and say so - " +
        "you still confirm every action, and it only ever means that one ${n.family.noun}. Say \"$UNDO\" to undo it."

    const val LOCKED = "Unlock the phone for that, Boss."
    const val ONLY_UNDERSTANDING = "They only help me understand you: each means exactly one arm or position, never more, and every action still waits for your confirm."

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private fun norm(text: String) = " " + spacedWords(text.lowercase().replace("'", "").replace("’", "")) + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| for me)* $"
    private const val THINGS = "(arms|arm|bots|bot|strategies|strategy|algos|positions|position|trades)"
    private const val NICK = "(nicknames|nick names|nickname|nick name|pet names|short names)"

    private val WHICH = rx(LEAD + "(what|which) $NICK do (i|you know i) (use|have|say)( for (my |the )?$THINGS)?" + TAIL + "|" +
        LEAD + "(what|which) $NICK (have you|did you) (learned|learnt|learn|pick up|picked up)( for (my |the )?$THINGS)?" + TAIL + "|" +
        LEAD + "(what|which) $NICK do you know( for (my |the )?$THINGS)?" + TAIL + "|" +
        LEAD + "(what|which) (names|words) do i use for (my |the )?$THINGS" + TAIL + "|" +
        LEAD + "(what|how) do i (usually )?call my $THINGS" + TAIL + "|" +
        LEAD + "(show|list|tell) (me )?my $NICK( for (my |the )?$THINGS)?" + TAIL + "|" +
        LEAD + "my $NICK( for (my |the )?$THINGS)?" + TAIL + "|" +
        LEAD + "(mere|meri|mera) $THINGS ke $NICK (kya|kaun se|kaunse) (hain|hai|he)" + TAIL + "|" +
        LEAD + "(mere|meri|mera) $NICK (kya|kaun se|kaunse) (hain|hai|he)" + TAIL)
    private val RESET = rx(LEAD + "(forget|unlearn|clear|drop|reset|delete|erase) (all )?(my|the) $NICK( (i use )?for (my |the )?$THINGS)?" + TAIL + "|" +
        LEAD + "(forget|unlearn|clear|drop) (the )?(names|words) i use for (my |the )?$THINGS" + TAIL + "|" +
        LEAD + "(mere|meri|mera) ($THINGS ke )?$NICK (bhool|bhul) (jao|jaao|ja)" + TAIL)

    /** "What nicknames do I use?" or "forget my nicknames for my arms", else null. */
    fun asked(text: String): Request? = askedKept.of(text) { askedFresh(text) }

    private val askedKept = Kept<Request?>(64)

    private fun askedFresh(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    /** "What nicknames do I use?". */
    fun say(log: Log): String {
        if (log.notes.isEmpty()) return "I haven't learned any nicknames for your arms or positions, Boss. When I ask \"which one?\" and you pick, " +
            "I keep the words you first used for that one. $ONLY_UNDERSTANDING"
        val xs = log.notes.sortedByDescending { it.at }
        return "Boss, the nicknames I know: " + xs.joinToString("; ") { "\"${it.words}\" is ${it.name} (${it.family.noun}, since ${Learnings.day(it.at.toLocalDate())})" } +
            ". $ONLY_UNDERSTANDING Say \"$UNDO\" to undo them."
    }

    /** "Forget my nicknames for my arms". */
    fun sayReset(log: Log): String =
        if (log.notes.isEmpty()) "I had no nicknames for your arms or positions to forget, Boss."
        else "Done, Boss: I've forgotten ${log.notes.size} nickname${if (log.notes.size == 1) "" else "s"} - I'll ask which one again when your words fit more than one."

    /** The ledger's lines for [n]. */
    fun ledgerWhat(n: Note): String = "\"${n.words}\" means ${n.name} (${n.family.noun})"
    fun ledgerWhy(n: Note): String = "you picked it when I asked which one; exactly one ${n.family.noun}, and every action still waits for your confirm"
}
