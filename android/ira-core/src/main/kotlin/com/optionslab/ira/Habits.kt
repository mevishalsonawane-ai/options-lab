package com.optionslab.ira

/**
 * Boss's habits (Jarvis self-improvement, 2026-10-03): the market questions Boss asks, counted by the hour of day, so
 * "the usual" asks the one Boss asks most around now. Only market questions are counted - never commands, orders or the
 * account. Pure: the counts are kept by the app.
 */
/** HabitCounts per question key, 24 hours each. */
typealias HabitCounts = Map<String, IntArray>

object Habits {
    private val KINDS = listOf(Topic.LEVELS to "levels", Topic.TREND to "trend", Topic.PATTERNS to "patterns", Topic.NEWS to "news",
        Topic.VOLATILITY to "volatility", Topic.WHY to "why", Topic.OVERVIEW to "overview")

    /** "BANKNIFTY|levels" for a market question, else null. */
    fun key(text: String): String? {
        val q = Ask.parse(text)
        if (q.command != null || q.order != null || q.topics.any { it in setOf(Topic.COMMAND, Topic.ORDER, Topic.ACCOUNT, Topic.OFF_TOPIC, Topic.HELP, Topic.GREETING, Topic.BACKTEST) }) return null
        val kind = KINDS.firstOrNull { it.first in q.topics }?.second ?: return null
        val m = q.markets.firstOrNull() ?: Market.NIFTY
        return "${m.name}|$kind"
    }

    /** The question a key stands for, in words Jarvis reads. */
    fun question(key: String): String? {
        val (mn, kind) = key.split("|").takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return null
        val m = runCatching { Market.valueOf(mn) }.getOrNull() ?: return null
        return when (kind) {
            "levels" -> "what are the levels on ${m.label}"
            "trend" -> "what is the trend on ${m.label}"
            "patterns" -> "any patterns on ${m.label}"
            "news" -> "any news on ${m.label}"
            "volatility" -> "how volatile is ${m.label}"
            "why" -> "why is ${m.label} moving today"
            "overview" -> "how is ${m.label} doing"
            else -> null
        }
    }

    fun add(counts: HabitCounts, key: String, hour: Int): HabitCounts {
        val out = counts.mapValues { it.value.copyOf() }.toMutableMap()
        val row = out.getOrPut(key) { IntArray(24) }
        row[hour.coerceIn(0, 23)]++
        // Kept small: the 40 most-asked.
        return out.entries.sortedByDescending { it.value.sum() }.take(40).associate { it.key to it.value }
    }

    /** The question Boss asks most within an hour of [hour] (at least [min] times), else the most asked overall. */
    fun usual(counts: HabitCounts, hour: Int, min: Int = 3): String? {
        fun near(row: IntArray) = (-1..1).sumOf { d -> row[((hour + d) % 24 + 24) % 24] }
        val now = counts.maxByOrNull { near(it.value) }?.takeIf { near(it.value) >= min }
        val all = counts.maxByOrNull { it.value.sum() }?.takeIf { it.value.sum() >= min }
        return (now ?: all)?.key
    }

    /** How often a question must have been asked in this very hour before Jarvis offers it unasked. */
    const val OFFER_AT = 4

    /**
     * The market question Boss asks at [hour] so often ([OFFER_AT]+ times in that hour) that Jarvis says it unasked at the
     * hour's start, once a day ([told]: keys already offered today), else null. Only market questions are ever counted.
     */
    fun due(counts: HabitCounts, hour: Int, told: Set<String>, lastAsked: Map<String, java.time.LocalDate> = emptyMap(),
            today: java.time.LocalDate? = null): String? =
        counts.filter { it.key !in told && it.value[hour.coerceIn(0, 23)] >= OFFER_AT &&
            // Still a habit: asked within the last week (a question Boss stopped asking is not said unasked forever).
            (today == null || lastAsked[it.key]?.let { d -> !d.isBefore(today.minusDays(7)) } == true) }
            .maxByOrNull { it.value[hour.coerceIn(0, 23)] }?.key

    fun asked(text: String): Boolean = rx("^ ?(jarvis |hey jarvis )?(the usual|my usual|usual|same as always|the regular|you know what i want)( please| jarvis)? ?$")
        .matches(text.lowercase().replace(rx("[^a-z ]"), " ").replace(rx("\\s+"), " ").trim())
}
