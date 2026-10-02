package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The automatic trailing stop for the owner's own trades (the owner's wish, 2026-10-02: "make it automatic"): once a
 * bought option is up [TRIGGER] (20%), its stop is moved to at least the price paid, then trails [TRAIL] (15%) under
 * the best price seen. It only ever moves up, and only by a real step, so the broker is not flooded. Pure.
 */
object AutoTrail {
    const val TRIGGER = 0.20
    const val TRAIL = 0.15

    /** The new stop, or null to leave [stop] where it is. */
    fun next(entry: Double, peak: Double, stop: Double?): Double? {
        if (entry <= 0 || peak < entry * (1 + TRIGGER)) return null
        val level = Math.round(maxOf(entry, peak * (1 - TRAIL)) * 20) / 20.0      // on the 0.05 tick
        val step = maxOf(0.5, entry * 0.02)
        if (stop != null && level < stop + step) return null
        return level
    }

    fun say(symbol: String, stop: Double, entry: Double): String =
        if (stop <= entry + 0.001) "Moved the stop on $symbol to %.2f, the price you paid: this trade can no longer lose (before costs).".format(Locale.ENGLISH, stop)
        else "Trailed the stop on $symbol up to %.2f (%.0f%% above what you paid).".format(Locale.ENGLISH, stop, (stop / entry - 1) * 100)
}

/** Too many trades too fast (the owner's wish, 2026-10-02): [MAX] manual trades in [WINDOW_MINUTES] minutes. Pure. */
object Overtrade {
    const val MAX = 3
    const val WINDOW_MINUTES = 30L

    /** How many manual trades in the window ending [now], when more than [MAX]; else null. */
    fun count(times: List<LocalDateTime>, now: LocalDateTime): Int? =
        times.count { !it.isBefore(now.minusMinutes(WINDOW_MINUTES)) && !it.isAfter(now) }.takeIf { it > MAX }

    fun say(n: Int): String = "Boss, that's $n trades in $WINDOW_MINUTES minutes. Want a 15-minute break? Fast trading after a loss is how small losses become big ones."
}

/** Losses growing bigger than wins (the owner's wish, 2026-10-02): told in the evening, with the numbers. Pure. */
object LossSize {
    /** A warning when the average loss is 1.2x the average win or more (at least 3 losses and 2 wins), else null. */
    fun check(nets: List<Double>): String? {
        val wins = nets.filter { it > 0 }; val losses = nets.filter { it < 0 }
        if (losses.size < 3 || wins.size < 2) return null
        val aw = wins.average(); val al = -losses.average()
        if (al < aw * 1.2) return null
        return "Your losses are bigger than your wins this week: the average loss is ${AppFacts.rs(-al).removePrefix("-")}, the average win ${AppFacts.rs(aw).removePrefix("+")} " +
            "(%.1f times). Cut losers sooner or let winners run longer.".format(Locale.ENGLISH, al / aw)
    }
}

/** Is there money for the trade? (the owner's wish, 2026-10-02): the funds must cover it with 20% to spare. Pure. */
object MarginCheck {
    const val SPARE = 1.2

    fun problem(available: Double?, cost: Double, where: String): String? {
        if (available == null || cost <= 0) return null
        if (available >= cost * SPARE) return null
        return "Not enough money in $where for this trade: it needs about ${AppFacts.amt(cost * SPARE)} with room to spare, and ${AppFacts.amt(maxOf(0.0, available))} is free."
    }
}

/**
 * What the owner trusts (the owner's wish, 2026-10-02): the kinds of suggestion the owner always rejects stop being
 * offered once there is enough to tell ([MIN_TOTAL] answers in all, [MIN_KIND] of that kind, every one a no). Pure.
 */
object Preference {
    const val MIN_TOTAL = 20
    const val MIN_KIND = 5

    /** "news", or "pattern <kind>" from a suggestion's source ("pattern: HAMMER|NIFTY|5|..."). */
    fun kind(source: String): String = if (source.startsWith("pattern")) "pattern " + source.substringAfter(':').trim().substringBefore('|').lowercase() else "news"

    /** [answers]: (kind, approved) for answered suggestions only (not lapsed). */
    fun skip(kind: String, answers: List<Pair<String, Boolean>>): Boolean {
        if (answers.size < MIN_TOTAL) return false
        val mine = answers.filter { it.first == kind }
        return mine.size >= MIN_KIND && mine.none { it.second }
    }

    fun say(kind: String): String = "You have turned down every $kind suggestion I made, so I'll stop offering those. Say \"Jarvis, reset my preferences\" to have them back."
}

/**
 * The opening gap (the owner's wish, 2026-10-02): today's gap from yesterday's close, and how each arm did on past
 * days with the same kind of gap. Pure.
 */
object GapPlan {
    enum class Gap(val label: String) { UP("gap-up"), DOWN("gap-down"), FLAT("flat-open") }
    const val BAND = 0.3

    fun of(gapPct: Double): Gap = when { gapPct >= BAND -> Gap.UP; gapPct <= -BAND -> Gap.DOWN; else -> Gap.FLAT }

    /** Each arm's record on days of each gap kind (5 trades or more), best first. */
    fun arms(trades: List<ArmHealth.T>, gaps: Map<LocalDate, Double>): Map<Gap, List<String>> =
        Gap.entries.associateWith { g ->
            trades.filter { d -> gaps[d.day]?.let { of(it) } == g }.groupBy { it.arm }.filter { it.value.size >= 5 }
                .entries.sortedByDescending { e -> e.value.sumOf { it.net } }
                .map { (arm, ts) -> "$arm ${AppFacts.rs(ts.sumOf { it.net })} over ${ts.size} (${ts.count { it.net > 0 }} won)" }
        }

    fun say(market: Market, gapPct: Double, record: List<String>): String {
        val g = of(gapPct)
        return "${market.label} opened %+.2f%%: a ${g.label} day. ".format(Locale.ENGLISH, gapPct) +
            (if (record.isEmpty()) "Not enough past ${g.label} days to say how the arms do." else "On past ${g.label} days: ${record.take(3).joinToString("; ")}.")
    }
}

/** The biggest open interest moving to a new strike (the owner's wish, 2026-10-02): support or resistance shifting. Pure. */
object OiShift {
    data class Walls(val call: Double?, val put: Double?)

    fun say(underlying: String, before: Walls, now: Walls): List<String> {
        val out = ArrayList<String>()
        fun px(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
        if (before.call != null && now.call != null && before.call != now.call)
            out += "$underlying: the biggest call open interest moved from ${px(before.call)} to ${px(now.call)} - resistance ${if (now.call > before.call) "moved up" else "came down"}."
        if (before.put != null && now.put != null && before.put != now.put)
            out += "$underlying: the biggest put open interest moved from ${px(before.put)} to ${px(now.put)} - support ${if (now.put > before.put) "moved up" else "slipped down"}."
        return out
    }
}

/** "Explain my position" (the owner's wish, 2026-10-02): P&L, room to the stop and target, time left, decay. Pure. */
object PositionTalk {
    data class Pos(val symbol: String, val where: String, val qty: Int, val avg: Double, val ltp: Double, val stop: Double?, val target: Double?,
                   /** Minutes to 15:15 for an intraday trade, or to expiry. */ val minutesLeft: Int?, /** Points the option loses a day (theta). */ val thetaPerDay: Double?)

    fun lines(p: Pos): String {
        val pnl = (p.ltp - p.avg) * p.qty
        val sb = StringBuilder("${p.where} ${p.symbol}, ${p.qty} at %.2f, now %.2f: ${AppFacts.rs(pnl)}".format(Locale.ENGLISH, p.avg, p.ltp))
        if (p.qty > 0) {
            sb.append(p.stop?.let { "; %.2f points to the stop at %.2f".format(Locale.ENGLISH, p.ltp - it, it) } ?: "; no stop")
            p.target?.let { sb.append("; %.2f points to the target at %.2f".format(Locale.ENGLISH, it - p.ltp, it)) }
        } else {
            sb.append(p.stop?.let { "; %.2f points to the stop at %.2f".format(Locale.ENGLISH, it - p.ltp, it) } ?: "; no stop")
        }
        p.minutesLeft?.let { m -> sb.append("; ${m / 60}h ${m % 60}m left") }
        p.thetaPerDay?.let { t -> sb.append("; time decay about ${AppFacts.rs(-kotlin.math.abs(t) * kotlin.math.abs(p.qty)).removePrefix("-")} a day") }
        return "$sb."
    }
}

/** The 15:35 spoken wrap-up (the owner's wish, 2026-10-02). Pure. */
object DaySummary {
    fun say(pnl: String?, scorecard: String?, events: List<String>): String =
        listOfNotNull("Day done, Boss.", pnl, scorecard?.takeIf { it != "No trades suggested today." },
            if (events.isEmpty()) "Nothing on the calendar for tomorrow." else "Coming up: ${events.take(2).joinToString(" ")}").joinToString(" ")
}

/** Jarvis's microphone gone quiet (the owner's wish, 2026-10-02): restart after [SILENT_MS] with no listening turn. Pure. */
object VoiceHealth {
    const val SILENT_MS = 3 * 60_000L

    fun stuck(nowMs: Long, lastReadyMs: Long, speaking: Boolean, wanted: Boolean): Boolean =
        wanted && !speaking && nowMs - lastReadyMs > SILENT_MS
}

/** Commands and orders while the phone is locked (the owner's wish, 2026-10-02): questions only. Pure. */
object LockRule {
    fun refuse(locked: Boolean, acts: Boolean, account: Boolean, boss: Boolean): String? = when {
        !locked -> null
        acts -> "Boss, unlock the phone first: I don't trade or change anything while it's locked."
        account && !boss -> "Unlock the phone to hear your account."
        else -> null
    }
}
