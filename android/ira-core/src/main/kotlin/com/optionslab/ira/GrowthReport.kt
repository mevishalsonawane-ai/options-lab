package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The weekly self-report (Boss, 11 Oct: "enhance the self improvement" - report its growth weekly): on Saturday morning, or
 * the first open after Friday ends, one report of the week Monday to Friday ("Week of 12-16 Oct"):
 *
 *  - Jarvis's lessons learned or confirmed, and retired, this week; how many are strong and building now;
 *  - Autopilot's changes made this week, those kept and reverted this week, and their rupee effect against what would have
 *    happened without them (the counterfactual its probation keeps);
 *  - the specialists muted and un-muted this week;
 *  - the app's health: stalls, ANRs, crashes, and the fastest and slowest day's order-path median (signal to order sent);
 *  - the learning goals' progress, the goals met, and next week's goals.
 *
 * Plain English; every number comes from the records given ([summarize]) - nothing is estimated or invented, and a part with
 * nothing in it says so. Pure: no clock (the app passes the time), no file.
 */
object GrowthReport {
    /**
     * The week's report is due from Saturday 00:00 (the first open after it, or Saturday's morning report), not at Friday's
     * close (G6-5): F&O trades to 15:40, Jarvis's learning pass and Autopilot's night run come at 15:45 or later, and a
     * report made before them missed Friday's lessons, keeps and reverts - and was never made again.
     */
    val DUE_FROM: LocalTime = LocalTime.MIDNIGHT
    const val KEEP_WEEKS = 26

    data class Week(
        val monday: LocalDate,
        val learned: Int = 0, val retired: Int = 0, val strongNow: Int = 0, val buildingNow: Int = 0,
        /** The newest lessons learned or confirmed this week (at most two), as said. */
        val newest: List<String> = emptyList(),
        val made: Int = 0, val kept: Int = 0, val reverted: Int = 0,
        /** The changes made this week: their rupee effect against what would have happened without them, over [effectN] cases. */
        val effectRs: Double = 0.0, val effectN: Int = 0,
        val muted: List<String> = emptyList(), val unmuted: List<String> = emptyList(),
        val stalls: Int = 0, val anrs: Int = 0, val crashes: Int = 0,
        /** The fastest and slowest day's order-path median: the day and its median in ms. */
        val fastestDay: LocalDate? = null, val fastestMs: Long? = null, val slowestDay: LocalDate? = null, val slowestMs: Long? = null,
        /** The goals and how many cases each gained (since set), the goals met this week, and next week's goals. */
        val goals: List<Pair<String, Int>> = emptyList(), val met: Int = 0, val next: List<String> = emptyList(),
    ) {
        val friday: LocalDate get() = monday.plusDays(4)
        val sunday: LocalDate get() = monday.plusDays(6)
    }

    fun mondayOf(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

    /** The week whose report is due at [now]: this week's from Saturday 00:00 on ([DUE_FROM]), else last week's. Its Monday. */
    fun dueWeek(now: LocalDateTime): LocalDate {
        val mon = mondayOf(now.toLocalDate())
        val close = mon.plusDays(5).atTime(DUE_FROM)
        return if (!now.isBefore(close)) mon else mon.minusWeeks(1)
    }

    /** Is a report due at [now], the last one being the week of [lastMonday] (null: none yet)? */
    fun due(now: LocalDateTime, lastMonday: LocalDate?): Boolean = lastMonday == null || lastMonday.isBefore(dueWeek(now))

    private fun mon(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    /** "Week of 12–16 Oct" ("Week of 28 Sep–2 Oct" across months). */
    fun title(monday: LocalDate): String {
        val fri = monday.plusDays(4)
        return if (fri.month == monday.month) "Week of ${monday.dayOfMonth}–${fri.dayOfMonth} ${mon(fri)}"
            else "Week of ${monday.dayOfMonth} ${mon(monday)}–${fri.dayOfMonth} ${mon(fri)}"
    }

    private fun inWeek(d: LocalDate?, monday: LocalDate) = d != null && !d.isBefore(monday) && !d.isAfter(monday.plusDays(6))

    /** The week of [monday] from the records: Jarvis's lessons, Autopilot's log, the app's health, the learning goals. */
    fun summarize(monday: LocalDate, lessons: AgentLessons.Book, auto: AutoChange.Book, health: HealthBook.Book, goals: LearningGoals.Book): Week {
        val active = AgentLessons.active(lessons)
        val learnedLs = active.filter { it.confidence != AgentLessons.Confidence.WEAK && inWeek(it.statusOn, monday) &&
            (it.status == AgentLessons.Status.NEW || it.status == AgentLessons.Status.CONFIRMED) }
        val retired = lessons.lessons.count { it.status == AgentLessons.Status.RETIRED && inWeek(it.statusOn, monday) }
        val made = auto.entries.filter { inWeek(it.day, monday) && (it.applied || it.status == AutoChange.Status.REVERTED || it.status == AutoChange.Status.UNDONE) }
        val kept = auto.entries.count { it.status == AutoChange.Status.KEPT && inWeek(it.closedOn, monday) }
        val reverted = auto.entries.count { it.status == AutoChange.Status.REVERTED && inWeek(it.closedOn, monday) }
        val mutes = made.filter { it.change.kind == AutoChange.Kind.SPECIALIST_MUTE }
        val days = health.days.filter { inWeek(it.day, monday) }
        val exits = health.exits.filter { inWeek(HealthBook.dayOf(it.atMs), monday) }
        val timed = days.filter { it.orderMedianMs != null && it.orderN > 0 }
        val fast = timed.minWithOrNull(compareBy<HealthBook.Day> { it.orderMedianMs }.thenBy { it.day })
        val slow = timed.maxWithOrNull(compareBy<HealthBook.Day> { it.orderMedianMs }.thenByDescending { it.day })
        return Week(
            monday,
            learned = learnedLs.size, retired = retired,
            strongNow = active.count { it.confidence == AgentLessons.Confidence.STRONG },
            buildingNow = active.count { it.confidence == AgentLessons.Confidence.BUILDING },
            newest = AgentLessons.ranked(learnedLs).take(2).map { AgentLessons.statement(it) },
            made = made.size, kept = kept, reverted = reverted,
            effectRs = made.sumOf { it.effect }, effectN = made.sumOf { it.afterN },
            muted = mutes.filter { it.change.to == AutoChange.MUTED }.map { it.change.what }.distinct(),
            unmuted = mutes.filter { it.change.from == AutoChange.MUTED }.map { it.change.what }.distinct(),
            stalls = days.sumOf { it.stalls },
            anrs = exits.count { it.kind == HealthBook.ExitKind.ANR },
            crashes = exits.count { it.kind == HealthBook.ExitKind.CRASH || it.kind == HealthBook.ExitKind.NATIVE_CRASH },
            fastestDay = fast?.day, fastestMs = fast?.orderMedianMs, slowestDay = slow?.day, slowestMs = slow?.orderMedianMs,
            goals = goals.goals.map { it.text to it.progress },
            met = goals.met.count { inWeek(it.on, monday) },
            next = goals.goals.map { it.text },
        )
    }

    // ---- words ---------------------------------------------------------------------------------------------------------------

    private fun n(k: Int, one: String, many: String = one + "s") = "$k ${if (k == 1) one else many}"
    private fun dd(d: LocalDate) = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} ${mon(d)}"

    /** The report's sections: a heading and its lines each, in order. */
    fun sections(w: Week): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        out += "Lessons" to listOf(
            (if (w.learned + w.retired == 0) "No lesson learned or retired this week." else "${n(w.learned, "lesson")} learned or confirmed, ${w.retired} retired.") +
                " Now ${w.strongNow} strong and ${w.buildingNow} building.",
        ) + w.newest.map { "New: $it." }
        out += "Autopilot" to if (w.made == 0 && w.kept == 0 && w.reverted == 0) listOf("Autopilot made no changes this week.") else listOf(
            "${n(w.made, "change")} made, ${w.kept} kept and ${w.reverted} reverted this week.",
            if (w.made == 0) "No change made this week to set against what would have happened." else if (w.effectN == 0)
                "Their effect against what would have happened without them: no settled case yet."
            else "Their effect against what would have happened without them: ${AgentLessons.rs(w.effectRs)} over ${n(w.effectN, "settled case")}.",
        )
        out += "Specialists" to if (w.muted.isEmpty() && w.unmuted.isEmpty()) listOf("No specialist muted or un-muted.") else
            listOfNotNull(w.muted.takeIf { it.isNotEmpty() }?.let { "Muted: ${it.joinToString(", ")}." },
                w.unmuted.takeIf { it.isNotEmpty() }?.let { "Un-muted: ${it.joinToString(", ")}." })
        out += "App health" to listOf(
            "${n(w.stalls, "stall")} of ${String.format(Locale.ENGLISH, "%.1f", HealthBook.STALL_MS / 1000.0)} s or more, " +
                (if (w.anrs == 0) "no ANR" else n(w.anrs, "ANR")) + ", " + (if (w.crashes == 0) "no crash" else n(w.crashes, "crash", "crashes")) + ".",
            if (w.fastestMs == null || w.slowestMs == null) "No order timed this week." else
                "Order path (signal to order sent), day medians: fastest ${HealthBook.msWords(w.fastestMs)} (${dd(w.fastestDay!!)}), " +
                    "slowest ${HealthBook.msWords(w.slowestMs)} (${dd(w.slowestDay!!)}).",
        )
        out += "Learning goals" to (if (w.goals.isEmpty()) listOf("No learning goal was set.") else w.goals.map { (t, p) -> "$t: +$p since it was set." }) +
            (if (w.met > 0) listOf("${n(w.met, "goal")} met this week.") else emptyList())
        out += "Next week" to (if (w.next.isEmpty()) listOf("No goal yet: I set them each evening after I study the agents.") else w.next.map { "I'll try to learn: $it." })
        return out
    }

    /** The whole report as text (the notification's long text and Jarvis's answer). */
    fun text(w: Week): String = title(w.monday) + "\n" + sections(w).joinToString("\n") { (h, ls) -> "$h: " + ls.joinToString(" ") }

    /** The notification's one line. */
    fun notice(w: Week): String = "${n(w.learned, "lesson")} learned, ${n(w.made, "Autopilot change")} (${w.kept} kept, ${w.reverted} reverted), " +
        "${n(w.stalls, "stall")}" + (if (w.anrs + w.crashes > 0) ", ${w.anrs + w.crashes} ANR or crash" else "") + ". Tap for the report."

    // ---- asked -----------------------------------------------------------------------------------------------------------------

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis)* $"
    private val ASK = Regex(LEAD + "(how (did|have) you (grow|grown|improve|improved|get better|got better) (this|over the|in the|last|the past) week|" +
        "how much did you grow this week|your (weekly self report|growth this week|week of growth)|(show me |give me )?your weekly self report|" +
        "what did you improve this week|how did you improve this week|did you grow this week)" + TAIL)

    /** "How did you grow this week?" */
    fun asked(text: String): Boolean = ASK.containsMatchIn(Spaced.joined(text))

    /** Said for the weekly report on a locked phone (G6-1): it carries Autopilot's rupee effect on the bots. */
    const val LOCKED = "Unlock the phone for my weekly report, Boss: it carries the bots' rupees."

    /** Jarvis's answer from the latest report (null: none made yet; [locked]: the phone is locked). */
    fun say(w: Week?, locked: Boolean = false): String = if (w == null) "No weekly report yet, Boss: the first comes after Friday's close."
        else if (locked) LOCKED else
        "How I grew, ${title(w.monday).replaceFirstChar { it.lowercase() }}, Boss:\n" + sections(w).joinToString("\n") { (h, ls) -> "• $h: " + ls.joinToString(" ") }

    // ---- the file ----------------------------------------------------------------------------------------------------------------

    private fun clean(s: String) = s.replace('|', '/').replace('~', '-').replace('^', ' ').replace('\n', ' ')
    private fun list(xs: List<String>) = xs.joinToString("~") { clean(it) }
    private fun unlist(s: String) = if (s.isEmpty()) emptyList() else s.split('~')

    /** One line a week. */
    fun encode(w: Week): String = listOf("W", w.monday, w.learned, w.retired, w.strongNow, w.buildingNow, list(w.newest), w.made, w.kept, w.reverted,
        w.effectRs, w.effectN, list(w.muted), list(w.unmuted), w.stalls, w.anrs, w.crashes, w.fastestDay ?: "", w.fastestMs ?: "", w.slowestDay ?: "",
        w.slowestMs ?: "", w.goals.joinToString("~") { clean(it.first) + "^" + it.second }, w.met, list(w.next)).joinToString("|")

    fun decode(line: String): Week? = runCatching {
        val p = line.split('|')
        if (p.size < 24 || p[0] != "W") return null
        fun d(s: String) = s.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
        Week(LocalDate.parse(p[1]), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt(), unlist(p[6]), p[7].toInt(), p[8].toInt(), p[9].toInt(),
            p[10].toDouble(), p[11].toInt(), unlist(p[12]), unlist(p[13]), p[14].toInt(), p[15].toInt(), p[16].toInt(), d(p[17]), p[18].toLongOrNull(),
            d(p[19]), p[20].toLongOrNull(), unlist(p[21]).map { it.substringBeforeLast('^') to (it.substringAfterLast('^').toIntOrNull() ?: 0) }, p[22].toInt(), unlist(p[23]))
    }.getOrNull()

    /** [weeks] with [w] in its Monday's place, newest last, at most [KEEP_WEEKS]. */
    fun keep(weeks: List<Week>, w: Week): List<Week> = (weeks.filter { it.monday != w.monday } + w).sortedBy { it.monday }.takeLast(KEEP_WEEKS)

}
