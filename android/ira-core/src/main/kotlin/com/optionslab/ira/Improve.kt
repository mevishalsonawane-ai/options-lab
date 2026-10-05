package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Jarvis improving himself, week by week (Boss, 5 Oct: "let him get better by himself"): at each weekend review he reads
 * his own week from what the app recorded - the words he could not place, the answers Boss marked wrong, how long Boss
 * waited for spoken answers, how often Boss cut him short, muted him or switched a group of alerts off, the ideas his
 * calibration sat out and the questions asked most - grades the goals he set himself the weekend before, and sets up to
 * [MAX_GOALS] new, measurable ones for the week ahead ("understand at least half of the phrasings I missed", "keep my
 * typical answer under 3 s", "fewer than 2 answers marked wrong"). The agenda checks them midweek, the wrap-up says how
 * they stand, and "how are you improving?" / "what are your goals?" answers from them.
 *
 * Every goal is about HIS OWN behaviour, and the only means a goal has ([Means]) are to speak, to study, to ask Boss to
 * teach him or to work on paper: no goal can place, change or close anything, switch anything on or off, or add risk -
 * there is no goal of fewer ideas sat out (that would push risk up), only of understanding, accuracy, speed and saying
 * less when Boss finds it too much. A lesson from them is kept only on Boss's yes, and only as a question
 * ([Corrections.learn]). Pure: the app keeps the counts, the plan and the history.
 */
object Improve {
    const val MAX_GOALS = 3
    /** A spoken answer that starts within this is fast. */
    const val FAST_MS = 3_000L
    /** Missed phrasings a learning goal is set on, at most. */
    const val MAX_WORDS = 6
    /** Too little to judge a share by below these (an unknown grade, never a met one). */
    const val MIN_TIMED = 5
    const val MIN_HEARD = 10
    /** Weeks of grades kept. */
    const val KEEP_WEEKS = 8

    // ---- the day's counts (the app keeps them per day under these names) ------------------------------------------

    const val HEARD = "heard"
    const val MISUNDERSTOOD = "misunderstood"
    const val MISTAKES = "mistakes"
    const val HUSH = "hush"
    const val TIMED = "timed"
    const val SLOW = "slow"
    const val MUTED = "muted"
    const val ALERT_OFF = "alertOff"
    const val SIT_OUT = "sitout"
    /** "q.BANKNIFTY|levels": a market question asked ([Habits.key]). */
    const val ASKED_PREFIX = "q."

    /** All a goal may do. There is nothing else: no order, no setting, no switch, no risk. */
    enum class Means(val label: String) { SPEAK("I say it"), STUDY("I study it"), ASK("I ask you to teach me"), PAPER("on paper") }

    /**
     * [atMost]: met at or under the target (else at or over it). [how]: what Jarvis does toward it - only words, study, a
     * question to Boss or paper.
     */
    enum class Kind(val means: Means, val atMost: Boolean, val how: String) {
        LEARN_MISSED(Means.ASK, false, "I'll ask you to teach me them, one or two a day"),
        FEWER_MISTAKES(Means.STUDY, true, "I study every answer you mark wrong overnight"),
        SPEED(Means.SPEAK, false, "I keep spoken answers short and tell you when the faster model would help - your choice"),
        UNDERSTAND(Means.ASK, false, "when I miss something I ask you to say it another way"),
        QUIETER(Means.ASK, true, "I'll ask you which of my words are too many - nothing is switched off by me"),
    }

    /** One goal for a week. [baseline]: the week before's figure. [words]: for [Kind.LEARN_MISSED], the phrasings (Boss's words; never said in [text]). */
    data class Goal(val kind: Kind, val target: Int, val baseline: Int, val words: List<String> = emptyList()) {
        /** What he aims at, with no word of Boss's in it. */
        fun text(): String = when (kind) {
            Kind.LEARN_MISSED -> "understand at least $target of the ${words.size} phrasings I missed"
            Kind.FEWER_MISTAKES -> if (target == 0) "have no answer marked wrong (last week $baseline)"
                else "fewer than ${target + 1} answers marked wrong (last week $baseline)"
            Kind.SPEED -> if (target <= 50) "keep my typical spoken answer under ${FAST_MS / 1000} s (last week $baseline% were)"
                else "start at least $target% of spoken answers within ${FAST_MS / 1000} s (last week $baseline%)"
            Kind.UNDERSTAND -> "understand at least $target% of what you ask (last week $baseline%)"
            Kind.QUIETER -> "be cut short, muted or switched off $target time${if (target == 1) "" else "s"} or fewer (last week $baseline)"
        }
    }

    /** One week of his own record, Monday [start] on. [asked]: market question key ([Habits.key]) to times asked. */
    data class Week(val start: LocalDate, val heard: Int = 0, val misunderstood: Int = 0, val mistakes: Int = 0,
                    val timed: Int = 0, val slow: Int = 0, val hushed: Int = 0, val muted: Int = 0, val alertsOff: Int = 0,
                    val sitOuts: Int = 0, val missed: List<String> = emptyList(), val asked: Map<String, Int> = emptyMap()) {
        val fastShare: Int? get() = if (timed < MIN_TIMED) null else ((timed - slow).coerceAtLeast(0) * 100) / timed
        val understoodShare: Int? get() = if (heard < MIN_HEARD) null else ((heard - misunderstood).coerceAtLeast(0) * 100) / heard
        val cutShort: Int get() = hushed + muted + alertsOff
    }

    enum class Grade { MET, MISSED, UNKNOWN }

    data class Progress(val goal: Goal, val value: Int?, val grade: Grade, val text: String)

    /** A week's score: goals met of those set. */
    data class Score(val week: LocalDate, val met: Int, val total: Int)

    /** The goals for the week starting Monday [week], and the weeks graded before it (oldest first). */
    data class Plan(val week: LocalDate, val goals: List<Goal>, val history: List<Score> = emptyList(), val lastGrade: String? = null)

    // ---- weeks -------------------------------------------------------------------------------------------------------

    /** The Monday whose goals hold on [day] (a weekend looks to the week ahead). */
    fun weekOf(day: LocalDate): LocalDate =
        if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) day.with(DayOfWeek.MONDAY).plusWeeks(1) else day.with(DayOfWeek.MONDAY)

    /** At the weekend review on [day] (after the week's last session): the Monday of the week the new goals are for. */
    fun nextWeek(day: LocalDate): LocalDate = day.with(DayOfWeek.MONDAY).plusWeeks(1)

    /** The days counted for the week starting [start], up to [upTo] (never past the Sunday). */
    fun days(start: LocalDate, upTo: LocalDate): List<LocalDate> {
        val end = minOf(upTo, start.plusDays(6))
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
    }

    /** The week from its days' counts (each as kept: name to count) and the phrasings missed in it. */
    fun week(start: LocalDate, counts: List<Map<String, Int>>, missed: List<String> = emptyList()): Week {
        fun sum(k: String) = counts.sumOf { (it[k] ?: 0).coerceAtLeast(0) }
        val asked = HashMap<String, Int>()
        counts.forEach { c -> c.forEach { (k, v) -> if (k.startsWith(ASKED_PREFIX) && v > 0) asked.merge(k.removePrefix(ASKED_PREFIX), v, Int::plus) } }
        return Week(start, sum(HEARD), sum(MISUNDERSTOOD), sum(MISTAKES), sum(TIMED), sum(SLOW), sum(HUSH), sum(MUTED), sum(ALERT_OFF), sum(SIT_OUT),
            missed.distinctBy { Corrections.normalize(it) }, asked)
    }

    /** The phrasings missed over the days, kept: [list] with [w] added on [day], the last 14 days and 60 at most, each once a day. */
    fun addMissed(list: List<Pair<LocalDate, String>>, day: LocalDate, w: String): List<Pair<LocalDate, String>> {
        val t = Secrets.redact(w).replace(Regex("\\s+"), " ").trim().take(120)
        val kept = list.filter { !it.first.isBefore(day.minusDays(14)) }
        if (t.isEmpty()) return kept
        return (kept.filterNot { it.first == day && it.second.equals(t, ignoreCase = true) } + (day to t)).takeLast(60)
    }

    /** The phrasings of [list] missed in the week starting [start]. */
    fun missedIn(list: List<Pair<LocalDate, String>>, start: LocalDate): List<String> =
        list.filter { !it.first.isBefore(start) && !it.first.isAfter(start.plusDays(6)) }.map { it.second }

    // ---- choosing the goals --------------------------------------------------------------------------------------

    private val SECRETISH = Regex("(?i)\\b(m?pin|tpin|pass(word|code|wd|phrase|key)?|pwd|otp|totp|2fa|api[ _-]?key|secret|token|cvv|cvc|card number|account number|hidden)\\b")

    private fun acts(text: String): Boolean = runCatching { Commands.parse(text) != null ||
        Ask.parse(text).let { it.order != null || it.command != null || Topic.ORDER in it.topics || Topic.COMMAND in it.topics } }.getOrDefault(true)

    private fun learnedSet(learned: List<Corrections.Learned>) = learned.map { Corrections.normalize(it.wrong) }.toSet()

    /** Phrasings worth learning: once each, no secret, nothing that would act, not learned already. */
    fun toLearn(missed: List<String>, learned: List<Corrections.Learned>): List<String> {
        val known = learnedSet(learned)
        return missed.asSequence().filter { !Secrets.hasSecret(it) && !SECRETISH.containsMatchIn(it) }
            .map { it.replace(Regex("\\s+"), " ").trim().take(120) }
            .filter { w -> Corrections.normalize(w).let { it.isNotEmpty() && it !in known } && w.split(" ").size <= 12 }
            .filter { !acts(it) }
            .distinctBy { Corrections.normalize(it) }.take(MAX_WORDS).toList()
    }

    private fun roundUp5(n: Int) = ((n + 4) / 5) * 5

    /**
     * Up to [MAX_GOALS] goals from [w], the week just gone: each only where the record shows room to improve, a kind he
     * missed last week ([missedLast]) first, then understanding, accuracy, speed and saying less.
     */
    fun choose(w: Week, learned: List<Corrections.Learned>, missedLast: Set<Kind> = emptySet()): List<Goal> {
        val out = ArrayList<Goal>()
        val words = toLearn(w.missed, learned)
        if (words.size >= 2) out += Goal(Kind.LEARN_MISSED, (words.size + 1) / 2, words.size, words)
        if (w.mistakes >= 2) out += Goal(Kind.FEWER_MISTAKES, (w.mistakes - maxOf(1, w.mistakes / 3)).coerceAtLeast(0), w.mistakes)
        w.fastShare?.takeIf { it < 80 }?.let { b -> out += Goal(Kind.SPEED, if (b < 50) 50 else minOf(80, roundUp5(b + 10)), b) }
        w.understoodShare?.takeIf { it < 90 }?.let { b -> out += Goal(Kind.UNDERSTAND, minOf(95, roundUp5(b + 5)), b) }
        if (w.cutShort >= 3) out += Goal(Kind.QUIETER, w.cutShort / 2, w.cutShort)
        return out.sortedWith(compareBy({ it.kind !in missedLast }, { it.kind.ordinal })).take(MAX_GOALS)
    }

    // ---- tracking and grading -------------------------------------------------------------------------------------

    /** Where [g] stands on [w] (the week so far, or the whole week at the weekend). */
    fun progress(g: Goal, w: Week, learned: List<Corrections.Learned>): Progress {
        val value: Int? = when (g.kind) {
            Kind.LEARN_MISSED -> learnedSet(learned).let { k -> g.words.count { Corrections.normalize(it) in k } }
            Kind.FEWER_MISTAKES -> w.mistakes
            Kind.SPEED -> w.fastShare
            Kind.UNDERSTAND -> w.understoodShare
            Kind.QUIETER -> w.cutShort
        }
        val grade = when {
            value == null -> Grade.UNKNOWN
            g.kind.atMost -> if (value <= g.target) Grade.MET else Grade.MISSED
            else -> if (value >= g.target) Grade.MET else Grade.MISSED
        }
        val text = when (g.kind) {
            Kind.LEARN_MISSED -> "$value of ${g.words.size} learned, ${g.target} needed"
            Kind.FEWER_MISTAKES -> "$value marked wrong, ${g.target} at most"
            Kind.SPEED -> if (value == null) "too few spoken answers timed yet" else "$value% within ${FAST_MS / 1000} s, ${g.target}% needed"
            Kind.UNDERSTAND -> if (value == null) "too few questions yet" else "$value% understood, ${g.target}% needed"
            Kind.QUIETER -> "$value so far, ${g.target} at most"
        }
        return Progress(g, value, grade, text)
    }

    private fun gradeWord(p: Progress, final: Boolean) = when (p.grade) {
        Grade.MET -> if (final) "met" else if (p.goal.kind.atMost) "within it so far" else "reached"
        Grade.MISSED -> if (final) "missed" else if (p.goal.kind.atMost) "already over" else "not yet"
        Grade.UNKNOWN -> if (final) "too little to judge" else "too early to say"
    }

    /** The weekend's grade of [plan] on [w]: one line, and the score (null with no goals). */
    fun grade(plan: Plan, w: Week, learned: List<Corrections.Learned>): Pair<String, Score>? {
        if (plan.goals.isEmpty()) return null
        val ps = plan.goals.map { progress(it, w, learned) }
        val met = ps.count { it.grade == Grade.MET }
        val judged = ps.count { it.grade != Grade.UNKNOWN }
        val line = "Last week's goals: met $met of ${ps.size}" + (if (judged < ps.size) " (${ps.size - judged} too little to judge)" else "") + ": " +
            ps.joinToString("; ") { "${it.goal.text()} - ${gradeWord(it, true)} (${it.text})" } + "."
        return line to Score(plan.week, met, ps.size)
    }

    /** The kinds [plan] missed on [w] (tried first again). */
    fun missedKinds(plan: Plan?, w: Week, learned: List<Corrections.Learned>): Set<Kind> =
        plan?.goals.orEmpty().map { progress(it, w, learned) }.filter { it.grade == Grade.MISSED }.map { it.goal.kind }.toSet()

    // ---- the weekend: review, grade, new goals --------------------------------------------------------------------

    /** The weekend's result: the new plan, and what is said (chat; [spoken] holds no word of Boss's and no figure of his account). */
    data class Cycle(val plan: Plan, val said: String, val spoken: String)

    /**
     * The weekend review: [past] (the week just gone) read, [old] graded on it when it was that week's plan, and new goals
     * set for the week starting [next]. [askedName]: how a question key is said ([Habits.question]).
     */
    fun weekend(old: Plan?, past: Week, learned: List<Corrections.Learned>, next: LocalDate,
                askedName: (String) -> String? = { Habits.question(it) }): Cycle {
        val graded = old?.takeIf { it.week == past.start }?.let { grade(it, past, learned) }
        val missedLast = if (old?.week == past.start) missedKinds(old, past, learned) else emptySet()
        val goals = choose(past, learned, missedLast)
        val history = (old?.history.orEmpty() + listOfNotNull(graded?.second)).distinctBy { it.week }.takeLast(KEEP_WEEKS)
        val plan = Plan(next, goals, history, graded?.first)
        val review = review(past, askedName)
        val set = goalsLine(goals, next = true)
        val said = listOfNotNull(review, graded?.first, set, trend(history)).joinToString(" ")
        val spoken = listOfNotNull(graded?.second?.let { "Last week I met ${it.met} of my ${plural(it.total, "goal")}." },
            if (goals.isEmpty()) "No new goals for myself this week: my record needs none." else "For next week I've set myself ${plural(goals.size, "goal")}: " +
                goals.joinToString("; ") { it.text() } + ".").joinToString(" ")
        return Cycle(plan, said, "Boss, my own review. $spoken")
    }

    /** What his week shows, in one or two sentences (null when nothing was recorded). */
    fun review(w: Week, askedName: (String) -> String? = { Habits.question(it) }): String? {
        val parts = ArrayList<String>()
        if (w.heard > 0) parts += "I heard ${plural(w.heard, "question")}" + (if (w.misunderstood > 0) " and could not place ${w.misunderstood}" else "")
        if (w.missed.isNotEmpty()) parts += "${plural(w.missed.size, "phrasing")} I didn't understand are kept to learn"
        if (w.mistakes > 0) parts += "you marked ${plural(w.mistakes, "answer")} wrong"
        w.fastShare?.let { parts += "$it% of my spoken answers started within ${FAST_MS / 1000} s" }
        if (w.cutShort > 0) parts += listOfNotNull(
            w.hushed.takeIf { it > 0 }?.let { "cut me short ${times(it)}" },
            w.muted.takeIf { it > 0 }?.let { "muted me ${times(it)}" },
            w.alertsOff.takeIf { it > 0 }?.let { "switched ${plural(it, "group")} of my alerts off" }).joinToString(", ").let { "you $it" }
        if (w.sitOuts > 0) parts += "I sat out ${plural(w.sitOuts, "idea")} of my own where my record is poor (each still put to you and scored on paper)"
        w.asked.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.let { (k, n) ->
            askedName(k)?.let { parts += "you asked most \"$it\" (${times(n)})" } }
        if (parts.isEmpty()) return null
        return "My own week, Boss: " + parts.joinToString("; ") + "."
    }

    private fun goalsLine(goals: List<Goal>, next: Boolean): String =
        if (goals.isEmpty()) "No goals for myself ${if (next) "next" else "this"} week: nothing in my record needs one. I keep watching it."
        else "My goals for ${if (next) "next" else "this"} week: " + goals.mapIndexed { n, g -> "${n + 1}) ${g.text()} (${g.kind.how})" }.joinToString("; ") +
            ". $ONLY"

    /** How the last weeks went, oldest first ("1 of 3, then 2 of 3"), or null with fewer than two. */
    fun trend(history: List<Score>): String? {
        val h = history.takeLast(4)
        if (h.size < 2) return null
        val first = h.first().met.toDouble() / maxOf(1, h.first().total)
        val last = h.last().met.toDouble() / maxOf(1, h.last().total)
        val way = when { last > first -> "getting better"; last < first -> "slipping"; else -> "holding steady" }
        return "Over the last ${h.size} weeks I met " + h.joinToString(", then ") { "${it.met} of ${it.total}" } + " - $way."
    }

    const val ONLY = "Each only changes how I speak, study or ask you - never a trade, never a setting, never more risk."

    // ---- what is said during the week ----------------------------------------------------------------------------

    /** "How are you improving?", "what are your goals?" (his own, never Boss's: "my goals" is Boss's). */
    fun asked(text: String): Boolean =
        Regex("(?i)^\\W*(hey\\s+|ok\\s+)?(jarvis,?\\s+)?(so\\s+)?(how are you (improving|getting better|doing on your( own)? goals)|are you (improving|getting better)|" +
            "what are your (own )?(goals|targets)( (for )?(this|the) week)?|(tell me|show me) your (own )?(goals|targets)|your (own )?goals( this week)?|" +
            "how are your (own )?goals( going| coming along)?|what are you (trying|working) to (improve|get better at)|what are you improving( on)?|" +
            "how is your self[- ]?improvement( going)?|tum kaise (improve|behtar) ho rahe ho|tumhare goals( kya hai)?)\\s*(jarvis|boss)?\\W*$").containsMatchIn(text)

    private fun planIsFor(plan: Plan?, today: LocalDate) = plan != null && plan.week == weekOf(today)

    /** The answer: this week's goals with where each stands on [w] (the week so far), and how the last weeks went. */
    fun say(plan: Plan?, w: Week, learned: List<Corrections.Learned>, today: LocalDate): String {
        if (!planIsFor(plan, today)) return "I haven't set goals for myself this week yet, Boss: I set them at the weekend review, from what my week shows " +
            "(phrasings I missed, answers marked wrong, how fast I answered, how often you cut me short)." + (plan?.lastGrade?.let { " $it" } ?: "")
        plan!!
        val head = if (plan.goals.isEmpty()) "No goals for myself this week, Boss: my record last week needed none."
            else "My goals this week, Boss: " + plan.goals.mapIndexed { n, g ->
                val p = progress(g, w, learned)
                "${n + 1}) ${g.text()} - ${gradeWord(p, false)} (${p.text})"
            }.joinToString("; ") + "."
        return listOfNotNull(head, plan.lastGrade, trend(plan.history), if (plan.goals.isEmpty()) null else ONLY).joinToString(" ")
    }

    /** The agenda's midweek check of his own goals (null: no goals this week). */
    fun checkLine(plan: Plan?, w: Week, learned: List<Corrections.Learned>, today: LocalDate): String? {
        if (!planIsFor(plan, today) || plan!!.goals.isEmpty()) return null
        return "Checking my own goals for the week, Boss: " + plan.goals.joinToString("; ") { g ->
            val p = progress(g, w, learned); "${g.text()} - ${gradeWord(p, false)} (${p.text})" } + "."
    }

    /** The wrap-up's line: how many of his goals are on track (null: no goals this week). */
    fun wrap(plan: Plan?, w: Week, learned: List<Corrections.Learned>, today: LocalDate): String? {
        if (!planIsFor(plan, today) || plan!!.goals.isEmpty()) return null
        val ps = plan.goals.map { progress(it, w, learned) }
        val ok = ps.count { it.grade == Grade.MET }
        val behind = ps.filter { it.grade == Grade.MISSED }
        return "My own goals this week: $ok of ${ps.size} on track" +
            (if (behind.isEmpty()) "." else "; behind on " + behind.joinToString("; ") { "${it.goal.text()} (${it.text})" } + ".")
    }

    /** The phrasings of this week's learning goal not learned yet: given to the agenda's study, put to Boss as questions. */
    fun toStudy(plan: Plan?, learned: List<Corrections.Learned>, today: LocalDate): List<String> {
        if (!planIsFor(plan, today)) return emptyList()
        val known = learnedSet(learned)
        return plan!!.goals.filter { it.kind == Kind.LEARN_MISSED }.flatMap { it.words }.filter { Corrections.normalize(it) !in known }
    }

    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"
    private fun times(n: Int) = if (n == 1) "once" else if (n == 2) "twice" else String.format(Locale.ENGLISH, "%d times", n)
}
