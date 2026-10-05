package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale

/**
 * Jarvis's own plan for the day (Boss, 5 Oct: "let him direct his own work"): each morning he builds a prioritised
 * agenda from what he knows - today's events and expiries, the positions Boss carries in, Boss's goals at risk and the
 * rules in his notes, the hours his own trades lose in, the paper tests still running, and what he studied overnight
 * (the words he could not place and the answers Boss marked wrong). He works through it during the day, each item a
 * check he set himself at its time, answers "what's your plan today?" / "what are you working on?", and at the
 * wrap-up says what he did and what he carries to tomorrow.
 *
 * Every item only SPEAKS, STUDIES or works on PAPER ([Means]): an agenda has no way to place, change or close anything
 * real, and nothing in it adds risk. What he studies is put to Boss as a question; a lesson is kept only on Boss's yes,
 * and only as a question ([Corrections.learn]) - nothing learned may act. Pure.
 */
object Agenda {
    const val MAX_ITEMS = 10
    /** "Teach me" questions a day, at most (Boss is not quizzed). */
    const val MAX_TEACH = 2
    /** An unfinished study item is carried this many days at most, then dropped. */
    const val MAX_CARRY = 2

    /** All an item may do. There is nothing else: no order, no setting, no switch. */
    enum class Means(val label: String) { SPEAK("I say it"), STUDY("I study it"), PAPER("on paper") }

    /**
     * [rank]: priority (1 first). [open]: its words hold none of Boss's account or his own words, so it may be spoken
     * on a locked phone.
     */
    enum class Kind(val means: Means, val rank: Int, val open: Boolean) {
        GOAL(Means.SPEAK, 1, false),
        RULE(Means.SPEAK, 1, false),
        /** What Boss told about himself that bears on today ([AboutBoss]): his own words, so not on a locked phone. */
        ABOUT(Means.SPEAK, 1, false),
        EXPIRY(Means.SPEAK, 2, false),
        EVENT(Means.SPEAK, 2, true),
        POSITIONS(Means.SPEAK, 3, false),
        WEAK_HOUR(Means.SPEAK, 3, true),
        PAPER_TEST(Means.PAPER, 4, false),
        /** His own goals for the week ([Improve]), checked midweek: about him only (no word or figure of Boss's), so it may be said aloud. */
        SELF(Means.SPEAK, 4, true),
        TEACH(Means.STUDY, 5, false),
    }

    /**
     * One thing on the plan, at minute of day [at]. [done]: what came of it (null while open). [words] / [guess]: for a
     * study item, Boss's words and the question Jarvis thinks they meant (null: he asks Boss to say it another way);
     * for an event, its name.
     */
    data class Item(val id: String, val kind: Kind, val at: Int, val text: String, val done: String? = null, val carried: Int = 0,
                    val words: List<String> = emptyList(), val guess: String? = null)

    /** Studied overnight: wordings alike, and the question they may mean (only ever a question), or null. */
    data class Teach(val words: List<String>, val guess: String?)

    data class Facts(
        val today: LocalDate,
        val tradingDay: Boolean,
        /** The coming events (the index expiries among them, "NIFTY expiry"); only today's count. */
        val events: List<Events.Event> = emptyList(),
        /** Positions open as the day starts (null: not known). */
        val openPositions: Int? = null,
        val goals: List<Goals.Status> = emptyList(),
        /** Boss's kept notes: the rules among them ([BossRules]). */
        val notes: List<String> = emptyList(),
        /** Hours (9 to 15) Jarvis's own trades lost in. */
        val badHours: List<Int> = emptyList(),
        /** Paper tests still running. */
        val paperTests: List<String> = emptyList(),
        val teach: List<Teach> = emptyList(),
        /** Yesterday's agenda (its unfinished study is carried). */
        val yesterday: List<Item> = emptyList(),
        /** His own goals for this week ([Improve.Plan.goals]), checked once a day. */
        val selfGoals: Int = 0,
    )

    private const val OPEN = 9 * 60 + 15
    private fun hm(m: Int) = "%02d:%02d".format(Locale.ENGLISH, m / 60, m % 60)
    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"

    /** The day's plan: the [MAX_ITEMS] most important things, in the order of their time. */
    fun build(f: Facts): List<Item> {
        val out = ArrayList<Item>()
        if (f.tradingDay) {
            val todays = f.events.filter { it.day == f.today }
            val expiring = todays.filter { it.name.endsWith(" expiry") }.map { it.name.removeSuffix(" expiry") }.distinct()
            todays.filterNot { it.name.endsWith(" expiry") }.distinctBy { it.name.lowercase() }.forEach { e ->
                out += Item("event:${e.name.lowercase().take(40)}", Kind.EVENT, 9 * 60 + 10, "remind you of today's event (${e.name}) before the open", words = listOf(e.name))
            }
            if (expiring.isNotEmpty())
                out += Item("expiry", Kind.EXPIRY, 14 * 60 + 40, "count what you hold before the ${expiring.joinToString(", ")} expiry square-off at 15:05", words = expiring)
            f.notes.mapNotNull { BossRules.of(it) }.forEach { r ->
                when (r.kind) {
                    BossRules.Kind.AVOID_EXPIRY -> if (expiring.isNotEmpty())
                        out += Item("rule:expiry", Kind.RULE, 9 * 60 + 10, "keep to your rule on expiry days: no trade ideas from me today")
                    BossRules.Kind.NOT_AFTER -> if (r.minute!! in OPEN until 15 * 60 + 30)
                        out += Item("rule:after", Kind.RULE, r.minute, "tell you at ${hm(r.minute)} that your rule stops new trade ideas from then")
                    BossRules.Kind.NOT_BEFORE -> if (r.minute!! in OPEN + 1 until 15 * 60 + 30)
                        out += Item("rule:before", Kind.RULE, OPEN, "hold trade ideas back until ${hm(r.minute)}, as your rule says")
                    // (A market Boss avoids holds all day; it needs no time of its own.)
                    BossRules.Kind.AVOID_MARKET -> {}
                }
            }
            // What Boss told about himself that bears on today ("I don't trade on Fridays", his target, what to watch in him) - his
            // rules aside (each is its own item above).
            runCatching { AboutBoss.recall(f.notes.filter { BossRules.of(it) == null }, AboutBoss.Moment(f.today, AboutBoss.At.MORNING, expiryToday = expiring.isNotEmpty())) }.getOrNull()?.let { said ->
                out += Item("about", Kind.ABOUT, 9 * 60 + 5, "remind you of what you told me about yourself that matters today", words = listOf(said))
            }
            val risky = f.goals.filter { it.broken || it.near }
            if (risky.isNotEmpty()) {
                val why = if (risky.any { it.broken }) "one is broken" else "one is close"
                listOf(12 * 60, 14 * 60 + 30).forEach { m -> out += Item("goal:$m", Kind.GOAL, m, "check your goals at ${hm(m)} ($why)") }
            } else if (f.goals.isNotEmpty()) out += Item("goal:${14 * 60 + 30}", Kind.GOAL, 14 * 60 + 30, "check your goals at 14:30, before the close")
            f.openPositions?.takeIf { it > 0 }?.let { n ->
                out += Item("positions", Kind.POSITIONS, 9 * 60 + 20, "look over the ${plural(n, "position")} you carry into today, after the open")
            }
            f.badHours.filter { it in 9..15 }.distinct().sorted().take(2).forEach { h ->
                out += Item("weak:$h", Kind.WEAK_HOUR, maxOf(h * 60, OPEN), "leave ${hm(h * 60)}-${hm(h * 60 + 60)} alone (my own trades lost in that hour)")
            }
            if (f.selfGoals > 0)
                out += Item("self", Kind.SELF, 15 * 60 + 25, "check how I'm doing on my own ${plural(f.selfGoals, "goal")} for the week")
            if (f.paperTests.isNotEmpty())
                out += Item("paper", Kind.PAPER_TEST, 15 * 60 + 20, "read how ${f.paperTests.take(3).joinToString(", ")}${if (f.paperTests.size > 3) " and the rest" else ""} are doing on paper")
        }
        // Study: put at the midday lull on a trading day (never at the open), mid-morning on a day off.
        val teachAt = if (f.tradingDay) 12 * 60 + 30 else 10 * 60
        val teach = (carry(f.yesterday).map { it.copy(at = teachAt) } + f.teach.map { teachItem(it, teachAt) })
            .distinctBy { it.id }.take(MAX_TEACH)
        return (out.distinctBy { it.id } + teach).sortedWith(compareBy({ it.kind.rank }, { it.at })).take(MAX_ITEMS).sortedBy { it.at }
    }

    private fun teachItem(t: Teach, at: Int): Item {
        val w = t.words.first()
        val text = if (t.guess != null) "ask you whether \"$w\" meant \"${t.guess}\"" else "ask you what \"$w\" means"
        return Item("teach:" + Corrections.normalize(w).take(60), Kind.TEACH, at, text, words = t.words, guess = t.guess)
    }

    /** What is carried from [yesterday]: unfinished study only (the rest is planned afresh each day), [MAX_CARRY] times at most. */
    fun carry(yesterday: List<Item>): List<Item> =
        yesterday.filter { it.kind == Kind.TEACH && it.done == null && it.carried < MAX_CARRY }.map { it.copy(carried = it.carried + 1) }

    /** The items due at [minute] and not done yet, the most important first. */
    fun due(items: List<Item>, minute: Int): List<Item> = items.filter { it.done == null && it.at <= minute }.sortedWith(compareBy({ it.kind.rank }, { it.at }))

    /** How late a timed word may still be said; past it the item is marked [MISSED] (said hours late it would mislead). */
    const val LATE_MIN = 60
    const val MISSED = "missed its time"

    /** [i] is a word for its moment (an event, a rule, an hour, the morning look) and that moment passed over [LATE_MIN] ago. */
    fun late(i: Item, minute: Int): Boolean = i.done == null && i.kind in setOf(Kind.EVENT, Kind.RULE, Kind.ABOUT, Kind.WEAK_HOUR, Kind.POSITIONS) && minute - i.at > LATE_MIN

    /** [id] done, with what came of it (a few words, no figures). */
    fun done(items: List<Item>, id: String, what: String): List<Item> = items.map { if (it.id == id) it.copy(done = what) else it }

    // ---- the work: what each item says when its time comes -----------------------------------------------------------

    /** The words of an item that needs nothing read (an event, a rule, an hour left alone), else null. */
    fun line(i: Item): String? = when (i.kind) {
        Kind.EVENT -> "Boss, today: ${i.words.firstOrNull() ?: "an event"}. I keep my ideas small around it and say what it does to the market."
        Kind.ABOUT -> i.words.firstOrNull()
        Kind.WEAK_HOUR -> i.id.removePrefix("weak:").toIntOrNull()?.let { h ->
            "From now to ${hm(h * 60 + 60)} I take no ideas by myself, Boss: my own trades lost in this hour before. I only watch." }
        Kind.RULE -> when (i.id) {
            "rule:expiry" -> "Expiry day, Boss, and you asked me to skip expiry days: no trade ideas from me today."
            "rule:after" -> "It's ${hm(i.at)}, Boss: by your rule, no new trade ideas from me from now."
            else -> "Boss, by your rule I hold trade ideas back until " + (rx("until (\\d{2}:\\d{2})").find(i.text)?.groupValues?.get(1) ?: "the time you set") + "."
        }
        else -> null
    }

    /** The goal check's words (chat; it holds Boss's figures). */
    fun goalLine(i: Item, st: List<Goals.Status>): String =
        if (st.isEmpty()) "My ${hm(i.at)} goal check: you have no goals set now, Boss."
        else "My ${hm(i.at)} goal check, Boss: " + st.joinToString(" ") { it.text } +
            if (st.any { it.broken || it.near }) " I'd go easy for the rest of the day." else " All within bounds."

    /** The positions check's words ([open]: positions open now, null when they could not be read). */
    fun positionsLine(i: Item, open: Int?): String = when {
        open == null -> "I could not read your positions for my ${hm(i.at)} check, Boss; I'll look again later."
        open == 0 -> "My ${hm(i.at)} check: you hold no open positions now, Boss."
        i.kind == Kind.EXPIRY -> "My ${hm(i.at)} expiry check, Boss: ${plural(open, "position")} open. Options of ${i.words.joinToString(", ")} expiring today are squared off at 15:05; " +
            "I give you the heads-up at 14:55. Nothing is closed by me."
        else -> "My ${hm(i.at)} check, Boss: ${plural(open, "position")} open. Ask me to explain them; a position with no stop gets my offer of one."
    }

    /** The paper tests' standing ([verdicts]: each one's words). */
    fun paperLine(verdicts: List<String>): String =
        if (verdicts.isEmpty()) "No paper tests to read today, Boss." else "My paper tests, Boss: " + verdicts.take(4).joinToString(" ")

    /** The "teach me" question put to Boss for a study item. */
    fun ask(i: Item): String {
        val w = i.words.firstOrNull() ?: ""
        val more = i.words.size - 1
        val also = if (more > 0) " (and ${plural(more, "wording")} like it)" else ""
        return if (i.guess != null) "Boss, a question from my study: you said \"$w\"$also and I couldn't place it. Did you mean \"${i.guess}\"? " +
            "If yes, I'll read it that way from now - only ever as a question, never anything that acts."
        else "Boss, from my study: you said \"$w\"$also and I couldn't place it. Say it to me another way and I'll learn it (as a question only)."
    }

    /** What Boss's yes teaches: each wording read as the guessed question - only where that is a question (never an action). */
    fun lessons(i: Item): List<Corrections.Learned> {
        val g = i.guess ?: return emptyList()
        if (i.kind != Kind.TEACH) return emptyList()
        return i.words.mapNotNull { Corrections.learn(it, g) }
    }

    /** What an item came to, in a few words (no figures): kept as its [Item.done]. */
    fun summary(i: Item, learned: Boolean = false): String = when (i.kind) {
        Kind.EVENT -> "reminded you of ${i.words.firstOrNull() ?: "today's event"}"
        Kind.RULE -> "kept your rule at ${hm(i.at)}"
        Kind.ABOUT -> "reminded you of what you told me about yourself"
        Kind.GOAL -> "checked your goals at ${hm(i.at)}"
        Kind.EXPIRY -> "counted what you hold before expiry"
        Kind.POSITIONS -> "looked over your positions"
        Kind.WEAK_HOUR -> "left ${hm(i.at - i.at % 60)}-${hm(i.at - i.at % 60 + 60)} alone"
        Kind.PAPER_TEST -> "read the paper tests"
        Kind.SELF -> "checked my own goals for the week"
        Kind.TEACH -> when {
            learned -> "learned \"${i.words.firstOrNull()}\""
            i.guess != null -> "asked you about \"${i.words.firstOrNull()}\""
            else -> "asked you to say \"${i.words.firstOrNull()}\" another way"
        }
    }

    // ---- nightly study ------------------------------------------------------------------------------------------------

    private val STOP = setOf("the", "and", "you", "your", "what", "how", "does", "did", "can", "please", "jarvis", "boss", "this", "that",
        "are", "for", "with", "tell", "show", "give", "kya", "hai", "batao")

    private fun content(s: String): Set<String> = Regex("[a-z]+").findAll(s.lowercase()).map { it.value }.filter { it.length > 2 && it !in STOP }.toSet()

    private val SECRETISH = Regex("(?i)\\b(m?pin|tpin|pass(word|code|wd|phrase|key)?|pwd|otp|totp|2fa|api[ _-]?key|secret|token|cvv|cvc|card number|account number|hidden)\\b")

    /** Words that would act (a command or an order): said again by Boss, never learned. */
    private fun acts(text: String): Boolean = Commands.parse(text) != null ||
        Ask.parse(text).let { it.order != null || it.command != null || Topic.ORDER in it.topics || Topic.COMMAND in it.topics }

    /**
     * Overnight: the words Jarvis could not place ([missed]) and the ones Boss marked wrong ([wrong]) - each once,
     * secrets out, never what would act, never what is already learned - grouped when alike, the biggest group first,
     * each with the question it may mean ([guess]: the closest thing he knows), kept only when that is a question.
     */
    fun study(missed: List<String>, wrong: List<String>, learned: List<Corrections.Learned>,
              guess: (String) -> String? = { Suggest.closest(it) }): List<Teach> {
        val known = learned.map { it.wrong }.toSet()
        // Anything that held or names a secret is dropped whole (never studied, never put to Boss aloud).
        val words = (wrong + missed).asSequence().filter { !Secrets.hasSecret(it) && !SECRETISH.containsMatchIn(it) }
            .map { it.replace(Regex("\\s+"), " ").trim().take(120) }
            .filter { it.isNotEmpty() && it.split(" ").size <= 12 }
            .filter { w -> Corrections.normalize(w).let { it.isNotEmpty() && it !in known } }
            .filter { runCatching { !acts(it) }.getOrDefault(false) }
            .distinctBy { Corrections.normalize(it) }.toList()
        val groups = ArrayList<MutableList<String>>()
        for (w in words) {
            val n = Corrections.normalize(w); val c = content(w)
            val g = groups.firstOrNull { gr -> gr.any { o -> Corrections.similarity(Corrections.normalize(o), n) >= 0.6 || (content(o) intersect c).size >= 2 } }
            if (g != null) g += w else groups += mutableListOf(w)
        }
        return groups.sortedByDescending { it.size }.take(MAX_TEACH + 1).map { gr ->
            Teach(gr.take(3), gr.firstNotNullOfOrNull { w -> runCatching { guess(w) }.getOrNull()?.takeIf { g -> Corrections.learn(w, g) != null } })
        }
    }

    // ---- what is said about the plan --------------------------------------------------------------------------------

    private val ASKED = Regex("(?i)^\\s*(hey\\s+|ok\\s+)?(jarvis[,!.]?\\s+)?(so\\s+)?(what('s|s| is) (your|the) (plan|agenda)( for)?( (today|the day))?|" +
        "what('s|s| is) your plan|what are you working on( (today|now|right now))?|what('s|s| is) on your (agenda|list|plate)( (for )?today)?|" +
        "what are you (doing|up to|working on) today|(show|tell) me your (plan|agenda)( for (today|the day))?|(your|jarvis'?s?) (plan|agenda) (today|for today)|" +
        "aaj (tumhara|aapka|tera|apka) plan( kya hai)?|what do you plan to do today)\\s*(jarvis|boss)?\\s*\\??\\s*$")

    /** "What's your plan today?", "what are you working on?" (not tomorrow's plan, not the market's). */
    fun asked(text: String): Boolean = ASKED.containsMatchIn(text) && !Regex("(?i)\\b(tomorrow|tmrw|kal)\\b").containsMatchIn(text)

    private const val ONLY = "All of it only speaks, studies or works on paper - nothing real is placed, changed or closed by it."

    /** The plan when asked, at [minute]: what is done, what is due, what comes next. */
    fun say(items: List<Item>, minute: Int): String {
        if (items.isEmpty()) return "Nothing on my own list today, Boss: no events, no goals at risk and nothing to study. I'm watching the market as usual."
        val done = items.filter { it.done != null && it.done != MISSED }
        val missed = items.filter { it.done == MISSED }
        val now = items.filter { it.done == null && it.at <= minute }
        val next = items.filter { it.done == null && it.at > minute }
        val parts = ArrayList<String>()
        if (done.isNotEmpty()) parts += "Done: " + done.joinToString("; ") { it.done!! } + "."
        if (missed.isNotEmpty()) parts += "Missed its time (I wasn't running then): " + missed.joinToString("; ") { it.text } + "."
        if (now.isNotEmpty()) parts += "Working on now: " + now.joinToString("; ") { it.text } + "."
        if (next.isNotEmpty()) parts += "Next: " + next.joinToString("; ") { "${hm(it.at)} ${it.text}" } + "."
        return "My plan today, Boss (${plural(items.size, "thing")}). " + parts.joinToString(" ") + " " + ONLY
    }

    /** Said when the plan is made in the morning. */
    fun morning(items: List<Item>): String? = if (items.isEmpty()) null
        else "My plan for today, Boss: " + items.mapIndexed { n, i -> "${n + 1}) ${hm(i.at)} ${i.text}" }.joinToString("; ") +
            ". Ask \"what's your plan?\" any time. " + ONLY

    /** The wrap-up's line: what was done, what is carried to tomorrow, what was not reached. Null with no plan. */
    fun wrap(items: List<Item>): String? {
        if (items.isEmpty()) return null
        val done = items.filter { it.done != null && it.done != MISSED }
        val carried = carry(items)
        val missed = items.filter { it.done == null && it.kind != Kind.TEACH || it.done == MISSED }
        val sb = StringBuilder("My own plan today: ${done.size} of ${items.size} done")
        sb.append(if (done.isEmpty()) "." else " (" + done.take(4).joinToString("; ") { it.done!! } + (if (done.size > 4) "; and more" else "") + ").")
        if (carried.isNotEmpty()) sb.append(" Carrying to tomorrow: " + carried.joinToString("; ") { it.text } + ".")
        if (missed.isNotEmpty()) sb.append(" Not reached: " + missed.joinToString("; ") { it.text } + ".")
        return sb.toString()
    }
}
