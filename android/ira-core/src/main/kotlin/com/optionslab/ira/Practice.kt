package com.optionslab.ira

import com.optionslab.engine.Session
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Practice on a past day (the owner's wish, 2026-10-03): the day's candles replayed close by close through the pattern
 * expert as it would have watched live, each suggestion played on that day's real option prices. Pure.
 */
object Practice {
    data class Event(val at: LocalDateTime, val text: String, val points: Double?)

    /** [prior]: earlier days' 1-minute candles (for levels and trend); [day]: the practice day's, in order. */
    fun run(m: Market, prior: List<Candle>, day: List<Candle>, edges: List<PatternExpert.Edge>, session: Session?, step: Int): List<Event> {
        if (day.isEmpty()) return emptyList()
        val out = ArrayList<Event>()
        val d = day.first().t.toLocalDate()
        var taken = 0
        val seen = HashSet<String>()
        var i = 0
        var minute = 9 * 60 + 20
        while (minute <= 15 * 60) {
            val now = d.atStartOfDay().plusMinutes(minute.toLong())
            while (i < day.size && !day[i].t.isAfter(now.minusMinutes(1))) i++
            val upTo = day.subList(0, i)
            if (upTo.isNotEmpty()) {
                val all = prior + upTo
                val snap = Brain.read(History(m, all))
                for (min in listOf(15, 5)) {
                    if (min == 15 && minute % 15 != 0) continue
                    val candles = Candles.closed(Candles.fold(all, min, m), min, now)
                    if (candles.isEmpty()) continue
                    val idea = PatternExpert.judge(min, candles, snap, edges, null, now, taken, false, false).idea ?: continue
                    val key = "${candles.last().t}|$min"
                    if (!seen.add(key)) continue
                    taken++
                    val spot = snap?.price ?: candles.last().c
                    val pts = session?.let { runCatching { JarvisTrades.OptionSim.trade(it, now, idea.call, spot, step) }.getOrNull() }
                    out += Event(now, "%02d:%02d: I would have suggested the ${m.label} ${if (idea.call) "call" else "put"} (${idea.kind?.lowercase()?.replace('_', ' ') ?: "pattern"}, ${min}-minute)"
                        .format(Locale.ENGLISH, now.hour, now.minute) +
                        (pts?.let { p -> " - it would have made %+.1f points.".format(Locale.ENGLISH, p) } ?: " - no option prices to replay it."), pts)
                    break
                }
            }
            minute += 5
        }
        return out
    }

    fun summary(m: Market, date: LocalDate, events: List<Event>): String {
        if (events.isEmpty()) return "Practice on $date (${m.label}): I would not have suggested any trade that day."
        val judged = events.mapNotNull { it.points }
        return "Practice on $date (${m.label}): ${events.size} suggestion${if (events.size > 1) "s" else ""}" +
            (if (judged.isEmpty()) "." else ", %+.1f points in all, %d of %d made money.".format(Locale.ENGLISH, judged.sum(), judged.count { it > 0 }, judged.size))
    }

    /** "Practice on last Thursday" / "practice on 24 September" / "practice yesterday": the day asked, or null. */
    fun day(text: String, today: LocalDate): LocalDate? {
        val t = " " + text.lowercase().replace(rx("[^a-z0-9 -]"), " ").replace(rx("\\s+"), " ") + " "
        if (t.contains(" yesterday ")) return today.minusDays(1)
        java.time.DayOfWeek.entries.firstOrNull { t.contains(" last ${it.name.lowercase()} ") || t.contains(" on ${it.name.lowercase()} ") }?.let { w ->
            var x = today.minusDays(1); while (x.dayOfWeek != w) x = x.minusDays(1); return x
        }
        return Events.date(text, today)?.let { if (it.isAfter(today)) it.minusYears(1) else it }
    }

    fun asked(text: String): Boolean = rx("(?i)\\b(practi[cs]e|replay|simulate|rehearse)\\b").containsMatchIn(text)
}

/**
 * The mistakes list (the owner's wish, 2026-10-03): "Jarvis, that was wrong" keeps what was said, what was heard and
 * what Jarvis answered. Pure.
 */
object Mistakes {
    data class Entry(val at: LocalDateTime, val said: String, val answered: String)

    fun lines(all: List<Entry>): List<String> =
        if (all.isEmpty()) listOf("No mistakes noted yet. Say \"Jarvis, that was wrong\" right after a bad answer.")
        else listOf("${all.size} mistake${if (all.size > 1) "s" else ""} noted:") + all.takeLast(10).reversed().map {
            // Only the time is formatted: what was said may hold a "%".
            "${it.at.toLocalDate()} " + "%02d:%02d".format(Locale.ENGLISH, it.at.hour, it.at.minute) + ": you said \"${it.said}\", I answered \"${it.answered.take(120)}\""
        }
}

/** The evening's word on how Jarvis did today (the owner's wish, 2026-10-03). Pure. */
object Usage {
    /**
     * [heard]: every question counted, mis-heard fragments too; [misheard]: those fragments ([MisHeard]; voice, round 26) -
     * said "say it again" to, not questions he did not understand.
     */
    data class Day(val heard: Int = 0, val misunderstood: Int = 0, val nameFirst: Int = 0, val failed: Int = 0, val mistakes: Int = 0,
                   val misheard: Int = 0) {
        /** Real questions: the mis-heard fragments left out. */
        val questions: Int get() = (heard - misheard.coerceAtLeast(0)).coerceAtLeast(0)
    }

    fun line(d: Day): String? {
        if (d.heard == 0 && d.failed == 0) return null
        val n = d.questions
        val parts = listOfNotNull(
            "I heard $n question${if (n == 1) "" else "s"} today",
            d.misunderstood.takeIf { it > 0 }?.let { "did not understand $it" },
            d.misheard.takeIf { it > 0 }?.let { "mis-heard $it short bit${if (it > 1) "s" else ""} and asked you to say ${if (it > 1) "them" else "it"} again" },
            d.nameFirst.takeIf { it > 0 }?.let { "asked you to say \"Jarvis\" first $it time${if (it > 1) "s" else ""}" },
            d.failed.takeIf { it > 0 }?.let { "$it thing${if (it > 1) "s" else ""} failed" },
            d.mistakes.takeIf { it > 0 }?.let { "you marked $it answer${if (it > 1) "s" else ""} wrong" },
        )
        return parts.joinToString(", ") + "."
    }
}

/** What a suggested trade risks (the owner's wish, 2026-10-03): the 15% stop's loss in rupees and of capital. Pure. */
object TradeRisk {
    fun say(premium: Double, lotSize: Int, lots: Int, capital: Double?): String? {
        if (premium <= 0 || lotSize <= 0) return null
        val loss = RiskSizing.perLot(premium, lotSize) * maxOf(1, lots)
        val share = capital?.takeIf { it > 0 }?.let { " (%.1f%% of your capital)".format(Locale.ENGLISH, loss / it * 100) } ?: ""
        return "If the stop hits you lose about ${AppFacts.amt(loss)}$share."
    }
}

/** The weekly loss cap for Jarvis's trades (the owner's wish, 2026-10-03): past it, no suggestions until Monday. Pure. */
object WeeklyCap {
    const val DEFAULT = 9_000.0

    /** [closed]: (day, rupees) of Jarvis's closed trades. */
    fun hit(closed: List<Pair<LocalDate, Double>>, today: LocalDate, limit: Double): Boolean {
        if (limit <= 0) return false
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        return closed.filter { !it.first.isBefore(monday) && !it.first.isAfter(today) }.sumOf { it.second } <= -limit
    }

    fun say(limit: Double): String = "My trades have lost ${AppFacts.amt(limit)} or more this week, my weekly limit: no more suggestions until Monday."
}

/** Wake-word sensitivity (the owner's wish, 2026-10-03). Pure. */
object WakeSense {
    enum class Level { NORMAL, STRICT }

    /** STRICT: the name must open what was heard (within the first two words), and only the recognizer's best reading counts. */
    fun accept(alternatives: List<String>, level: Level): List<String> = when (level) {
        Level.NORMAL -> alternatives
        Level.STRICT -> alternatives.take(1).filter { a ->
            a.lowercase().replace(rx("[^a-z ]"), " ").trim().split(rx("\\s+")).take(2).any { w -> w in setOf("jarvis", "jarvas", "jervis", "jarviss") }
        }
    }
}
