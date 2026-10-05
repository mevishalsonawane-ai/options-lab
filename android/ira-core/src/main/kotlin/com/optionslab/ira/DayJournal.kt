package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The end-of-day journal assistant (usefulness round 8, 2026-10-05): after the close, and on "help me journal today",
 * Jarvis drafts Boss's trading journal entry for the day from facts - each of his own trades replayed against its
 * candles ([TradeReplay]), the reason he noted for it ("Jarvis, note: ..."), his rules kept or broken ([BossRules]),
 * the words before an order he was shown ([PreTrade]) and what happened after, and the day against his goals and
 * limits - then asks him one to three short reflective questions ("Your 11:05 trade came right after a loss - what
 * were you thinking?"). His spoken answers are kept in the journal as his own words (secrets hidden), and are never
 * acted on: not a rule, not a memory, not a command. The questions name times and words only, never an amount, so
 * they may be said aloud; they are asked only on an unlocked phone. Facts and questions only, never advice. Pure.
 */
object DayJournal {
    /** A note Boss gave ("Jarvis, note: I bought because of the hammer"), when. */
    data class Noted(val at: LocalDateTime, val text: String)
    /** A word before an order ([PreTrade]) shown to Boss at [at], with its reminders. */
    data class Shown(val at: LocalDateTime, val reminders: List<String>)
    /** A reflective question; [key] says what it is about (an answered one is not asked again). */
    data class Question(val key: String, val text: String)
    /** One question answered: the question, and Boss's own words. */
    data class Answer(val key: String, val question: String, val answer: String, val at: LocalDateTime)

    data class Draft(val day: LocalDate, val trades: Int, val lines: List<String>, val spoken: String, val questions: List<Question>)

    /** A note belongs to the trade opened nearest it, within this many minutes. */
    const val NOTE_MINUTES = 60L
    /** A trade opened within this many minutes of a word before an order is the order it was about. */
    const val AFTER_WORD_MINUTES = 10L
    /** The same word shown again within this many minutes (the review reopened) counts once. */
    const val SAME_WORD_MINUTES = 2L
    /** At most this many questions a day. */
    const val MAX_QUESTIONS = 3
    /** A question is open for Boss's answer this long after it is asked. */
    const val OPEN_MINUTES = 3L
    /** An answer is kept up to this many characters. */
    const val MAX_ANSWER = 400

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("'", "").replace("’", "")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private val ASKED = Regex(
        " help (me )?(with |to )?(write |do |fill (in )?)?(my |the |todays |today s )?(trading )?journal" +
        "| (write|draft|do|fill|fill in|start|make|prepare|prep) (my|the|todays|today s|a) (trading )?(journal|journal entry|diary)( for today| for the day)? $" +
        "| journal (my|the|todays|today s) (day|trades|session|trading) (please )?$" +
        // Hinglish (routing audit, 5 Oct): "aaj ka journal likhne mein madad karo" (it read as the P&L history), "journal
        // likhne mein help karo" (as a note).
        "| (aaj ka |aaj ki |mera |meri |aaj )?(journal|diary) (likhne|bharne|banane) (mein|me|main) (help|madad|maddad) (karo|kar do|kardo|chahiye|kijiye) $" +
        "| (aaj ka |mera )?(journal|diary) (likhwao|likhwa do|likhne mein help|banwao) $" +
        "| (lets|let us|time to) journal( today| the day| my day)? $" +
        "| (my|todays|today s) journal entry $" +
        "| journal (for )?today (please )?$")

    /** Does [text] ask for help with today's journal ("help me journal today", "draft my journal")? Never a note ("journal: I bought because..."). */
    fun asked(text: String): Boolean {
        val t = norm(text).replace(Regex("^ (hey |ok |okay )?jarvis "), " ").replace(Regex("^ (please |can you |could you |will you )+"), " ")
        return ASKED.containsMatchIn(t) && !Regex(" because | bought | sold | as | since ").containsMatchIn(t)
    }

    /** What Boss said while a question is open: his answer, "skip" (pass this one), or "stop" (no more questions). */
    enum class Heard { ANSWER, SKIP, STOP }

    private val SKIP = Regex("^ (skip|skip it|skip this( one)?|pass|next|next one|next question|no comment|nothing|dont know|i dont know|not sure|no idea) $")
    private val STOP = Regex("^ (stop|stop it|enough|thats all|that is all|thats it|done|im done|i am done|later|not now|no more|no more questions|finish|end|cancel|leave it) $")

    fun heard(text: String): Heard {
        val t = norm(text).replace(Regex("^ (jarvis |boss )"), " ").replace(Regex(" (please|jarvis|thanks|thank you) $"), " ")
        return when {
            STOP.containsMatchIn(t) -> Heard.STOP
            SKIP.containsMatchIn(t) -> Heard.SKIP
            else -> Heard.ANSWER
        }
    }

    /** Boss's answer as kept: his own words, secrets hidden, trimmed. */
    fun kept(answer: String): String = Secrets.redact(answer).replace(Regex("\\s+"), " ").trim().take(MAX_ANSWER)

    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun minuteOf(t: LocalDateTime) = t.hour * 60 + t.minute
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun day(d: LocalDate) = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun pct(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)

    /** The rule of Boss's (from [rules], what he asked Jarvis to remember) that trade [t] went against, or null. */
    internal fun broke(t: TradeReplay.Trip, rules: List<BossRules.Rule>, expiry: Set<Market>): BossRules.Rule? {
        val m = PreTrade.marketOf(t.symbol)
        val minute = minuteOf(t.openedAt)
        return rules.firstOrNull { r ->
            when (r.kind) {
                BossRules.Kind.AVOID_MARKET -> m != null && r.market == m
                BossRules.Kind.AVOID_EXPIRY -> m != null && m in expiry
                BossRules.Kind.NOT_BEFORE -> minute < r.minute!!
                BossRules.Kind.NOT_AFTER -> minute >= r.minute!!
            }
        }
    }

    /** Opened within [PreTrade.AFTER_LOSS_MINUTES] after another of the day's trades closed at a loss. */
    internal fun afterLoss(t: TradeReplay.Trip, all: List<TradeReplay.Trip>): Boolean = all.any { l ->
        l !== t && l.net < 0 && !t.openedAt.isBefore(l.closedAt) && !t.openedAt.isAfter(l.closedAt.plusMinutes(PreTrade.AFTER_LOSS_MINUTES))
    }

    /** The note nearest [t]'s opening, within [NOTE_MINUTES]. */
    internal fun noteFor(t: TradeReplay.Trip, notes: List<Noted>): Noted? =
        notes.filter { kotlin.math.abs(Duration.between(t.openedAt, it.at).toMinutes()) <= NOTE_MINUTES }
            .minByOrNull { kotlin.math.abs(Duration.between(t.openedAt, it.at).toMinutes()) }

    /** What a word before an order was about, in a few words. */
    internal fun about(reminders: List<String>): String = reminders.map { r ->
        val l = r.lowercase(Locale.ENGLISH)
        when {
            l.contains("last two trades") -> "two losses in a row"
            l.contains("last trade lost") -> "just after a loss"
            l.contains("own goal") -> "past your own trade goal"
            l.contains("usual day") -> "past your usual number of trades"
            l.contains("first five minutes") -> "the first five minutes"
            l.contains("remember") -> "a rule you gave me"
            else -> "a reminder"
        }
    }.distinct().joinToString(", ")

    /** The words before an order, the same one reshown within [SAME_WORD_MINUTES] counted once. */
    internal fun words(shown: List<Shown>, day: LocalDate): List<Shown> {
        val out = ArrayList<Shown>()
        shown.filter { it.at.toLocalDate() == day && it.reminders.isNotEmpty() }.sortedBy { it.at }.forEach { s ->
            val last = out.lastOrNull()
            if (last != null && last.reminders == s.reminders && !s.at.isAfter(last.at.plusMinutes(SAME_WORD_MINUTES))) return@forEach
            out += s
        }
        return out
    }

    /** The trade a word before an order was about: the first opened within [AFTER_WORD_MINUTES] after it, or null (none sent). */
    internal fun followed(s: Shown, trips: List<TradeReplay.Trip>): TradeReplay.Trip? =
        trips.filter { !it.openedAt.isBefore(s.at.withSecond(0).withNano(0)) && !it.openedAt.isAfter(s.at.plusMinutes(AFTER_WORD_MINUTES)) }.minByOrNull { it.openedAt }

    /**
     * Today's journal entry drafted. [replays]: Boss's own trades (not the bots') closed on [day], replayed; [notes]: his
     * notes; [rules]: what he asked Jarvis to remember (the rules in it are checked); [shown]: the words before an order
     * he was shown; [goals]: his goals as they stand; [expiry]: the indices whose expiry is [day]; [lossLimit] and
     * [tradeLimit]: the account's daily loss and trade limits; [answered]: the question keys already answered today.
     */
    fun draft(account: String, day: LocalDate, replays: List<TradeReplay.Replay>, notes: List<Noted> = emptyList(), rules: List<String> = emptyList(),
              shown: List<Shown> = emptyList(), goals: List<Goals.Status> = emptyList(), expiry: Set<Market> = emptySet(),
              lossLimit: Double? = null, tradeLimit: Int? = null, answered: Set<String> = emptySet()): Draft {
        val rs = replays.filter { it.trip.closedAt.toLocalDate() == day }.sortedBy { it.trip.openedAt }
        val trips = rs.map { it.trip }
        val todayNotes = notes.filter { it.at.toLocalDate() == day }
        if (rs.isEmpty()) return Draft(day, 0, listOf("$account journal, ${day(day)}: no trades of your own closed today, Boss."),
            "Boss, no trades of your own closed today, so there is nothing to journal.", emptyList())

        val ruleList = rules.mapNotNull { BossRules.of(it) }
        val won = trips.count { it.net > 0 }; val lost = trips.count { it.net < 0 }
        val net = trips.sumOf { it.net }
        val out = ArrayList<String>()
        out += "$account journal, ${day(day)} - drafted by Jarvis from the facts: ${plural(trips.size, "trade")} of your own, $won won, $lost lost, net ${AppFacts.rs(net)}."

        // Each trade: its replay, the reason noted, and what stood out.
        rs.forEachIndexed { i, r ->
            val t = r.trip
            val n = noteFor(t, todayNotes)
            val flags = listOfNotNull(
                if (afterLoss(t, trips)) "opened right after a loss" else null,
                broke(t, ruleList, expiry)?.let { "against your rule \"${it.note}\"" },
            )
            out += "Trade ${i + 1}: " + TradeReplay.short(r).removeSuffix(".") + "; net ${AppFacts.rs(t.net)}." +
                (n?.let { " Your reason: \"${it.text}\"." } ?: " No reason noted.") +
                (if (flags.isEmpty()) "" else " It was ${flags.joinToString(" and ")}.")
        }
        TradeReplay.exits(rs).forEach { out += "Exits: $it" }

        // Rules kept or broken.
        ruleList.forEach { r ->
            val hit = trips.filter { broke(it, listOf(r), expiry) != null }
            out += if (hit.isEmpty()) "Rule \"${r.note}\": kept all day."
                else "Rule \"${r.note}\": broken by ${if (hit.size == 1) "the ${hm(hit[0].openedAt)} trade" else "the trades at " + hit.joinToString(", ") { hm(it.openedAt) }}."
        }

        // The words before an order, and what followed each.
        val ws = words(shown, day)
        ws.forEach { s ->
            val f = followed(s, trips)
            out += "A word before an order at ${hm(s.at)} (${about(s.reminders)}): " + (if (f == null) "no trade of yours followed within $AFTER_WORD_MINUTES minutes."
                else "you sent it; that ${hm(f.openedAt)} trade ${if (f.net > 0) "won" else if (f.net < 0) "lost" else "closed flat"}, net ${AppFacts.rs(f.net)}.")
        }

        // The day against his goals and limits.
        goals.forEach { out += "Goal: ${it.text}" }
        if (lossLimit != null && lossLimit > 0 && net < 0) out += "Daily loss limit ${AppFacts.amt(lossLimit)}: the day closed at ${AppFacts.rs(net)}, ${pct(-net / lossLimit)} of it" +
            (if (-net >= lossLimit) " - reached." else ".")
        if (tradeLimit != null && tradeLimit > 0) out += "Trades: ${trips.size} of the $tradeLimit a day your limit allows" + (if (trips.size >= tradeLimit) " - reached." else ".")

        val qs = questions(rs, todayNotes, ruleList, ws, goals, expiry).filter { it.key !in answered }
        if (qs.isNotEmpty()) out += "Questions for you (${qs.size}): answer by voice and your words go in this journal as said - never acted on."
        return Draft(day, trips.size, out, spoken(trips, ruleList, ws, expiry, qs.size), qs)
    }

    /**
     * Up to [MAX_QUESTIONS] reflective questions, one per trade at most, in this order: a rule broken, a word before an
     * order and the trade sent anyway that lost, a trade right after a loss, the day's biggest loss, a winner that ran on
     * after the exit, a goal broken, the best trade. Times and words only, never an amount.
     */
    internal fun questions(rs: List<TradeReplay.Replay>, notes: List<Noted>, rules: List<BossRules.Rule>, ws: List<Shown>,
                           goals: List<Goals.Status>, expiry: Set<Market>): List<Question> {
        val trips = rs.map { it.trip }
        val out = ArrayList<Question>()
        val asked = HashSet<LocalDateTime>()
        fun add(t: TradeReplay.Trip?, key: String, text: String) {
            if (out.size >= MAX_QUESTIONS) return
            if (t != null && !asked.add(t.openedAt)) return
            if (out.any { it.key == key }) return
            out += Question(key, text)
        }
        trips.filter { broke(it, rules, expiry) != null }.forEach { t ->
            add(t, "rule@${hm(t.openedAt)}", "Your ${hm(t.openedAt)} trade went against a rule you gave me - what made you take it?") }
        ws.forEach { s -> followed(s, trips)?.takeIf { it.net < 0 }?.let { t ->
            add(t, "word@${hm(t.openedAt)}", "I put a word in before your ${hm(t.openedAt)} trade and you sent it anyway - what made you go ahead?") } }
        trips.filter { afterLoss(it, trips) }.forEach { t ->
            add(t, "afterloss@${hm(t.openedAt)}", "Your ${hm(t.openedAt)} trade came right after a loss - what were you thinking?") }
        trips.filter { it.net < 0 }.minByOrNull { it.net }?.let { t ->
            add(t, "worst@${hm(t.openedAt)}", "Your ${hm(t.openedAt)} trade was the day's biggest loss" +
                (if (noteFor(t, notes) == null) " and had no reason noted" else "") + " - what would you do differently?") }
        rs.filter { it.trip.net > 0 && (it.leftAfter ?: 0.0) > 0 && (it.leftAfter ?: 0.0) >= (it.againstAfter ?: 0.0) }.maxByOrNull { it.leftAfter ?: 0.0 }?.let { r ->
            add(r.trip, "early@${hm(r.trip.openedAt)}", "Your ${hm(r.trip.openedAt)} trade kept going your way after you got out - what made you exit when you did?") }
        if (goals.any { it.broken }) add(null, "goal", "You went past one of your own goals today - what happened there?")
        trips.filter { it.net > 0 }.maxByOrNull { it.net }?.let { t ->
            add(t, "best@${hm(t.openedAt)}", "Your ${hm(t.openedAt)} trade was your best today - what did you see in it?") }
        return out
    }

    /** The draft said aloud: counts only, never an amount or a symbol. */
    internal fun spoken(trips: List<TradeReplay.Trip>, rules: List<BossRules.Rule>, ws: List<Shown>, expiry: Set<Market>, questions: Int): String {
        val won = trips.count { it.net > 0 }; val lost = trips.count { it.net < 0 }
        val broken = rules.count { r -> trips.any { broke(it, listOf(r), expiry) != null } }
        val parts = ArrayList<String>()
        parts += "${plural(trips.size, "trade")} of your own, $won won and $lost lost"
        if (rules.isNotEmpty()) parts += if (broken == 0) "every rule of yours kept" else "${plural(broken, "rule")} of yours broken"
        if (ws.isNotEmpty()) parts += "${plural(ws.size, "word")} from me before an order"
        return "Boss, I've drafted today's journal in the chat: ${parts.joinToString(", ")}." +
            (if (questions > 0) " I have ${if (questions == 1) "one short question" else "$questions short questions"} for you." else "")
    }

    /** Said to start the questions: the first question comes first, so it is heard even when only one sentence is said. */
    fun first(q: Question, total: Int): String = "For your journal, Boss: ${q.text.replaceFirstChar { it.lowercase() }} " +
        "(${if (total == 1) "one question" else "question 1 of $total"}; say \"skip\" to pass or \"stop\" to finish - your words are kept as said, never acted on.)"

    /** Said after an answer, a skip or a stop: the next question in the same sentence, or that the journal is done. */
    fun next(heard: Heard, q: Question?): String {
        val head = when (heard) { Heard.ANSWER -> "Noted in your journal, Boss"; Heard.SKIP -> "Skipped, Boss"; Heard.STOP -> "Done, Boss" }
        return when {
            heard == Heard.STOP -> "$head: the rest of the questions are left; say \"help me journal today\" to go on."
            q == null -> "$head - that's all my questions; today's journal is done."
            else -> "$head - next: ${q.text.replaceFirstChar { it.lowercase() }}"
        }
    }

    /** The journal's lines for a day: the draft, then each answer in Boss's own words. */
    fun entry(lines: List<String>, answers: List<Answer>): List<String> =
        lines.filterNot { it.startsWith("Questions for you") } + answers.map { "Q: ${it.question} You said: \"${it.answer}\"" }
}
