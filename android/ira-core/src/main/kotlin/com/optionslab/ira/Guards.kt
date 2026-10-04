package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * A cooling-off after losses (the owner's wish, 2026-10-02): after two of Jarvis's trades in a row close at a loss,
 * it suggests nothing for [MINUTES] minutes from the second close, and says why. Pure.
 */
object CoolOff {
    const val MINUTES = 30L
    const val STREAK = 2

    /** Until when no trade is suggested (null: suggestions may go on). [closed]: (close time, rupees), any order. */
    fun until(closed: List<Pair<LocalDateTime, Double>>, now: LocalDateTime): LocalDateTime? {
        val today = closed.filter { it.first.toLocalDate() == now.toLocalDate() }.sortedBy { it.first }
        val last = today.takeLast(STREAK)
        if (last.size < STREAK || last.any { it.second >= 0 }) return null
        val end = last.last().first.plusMinutes(MINUTES)
        return end.takeIf { now.isBefore(it) }
    }

    fun say(until: LocalDateTime): String =
        "Two of my trades lost in a row, so I'm cooling off: no suggestions until ${"%02d:%02d".format(Locale.ENGLISH, until.hour, until.minute)}."
}

/**
 * A position with no stop (the owner's wish, 2026-10-02): a bought option open [GRACE_MINUTES] minutes with no
 * protection gets a stop offered at 15% below the price paid (the Liquidity arm's rule) - asked, never set alone. Pure.
 */
object Rescue {
    const val GRACE_MINUTES = 2L
    const val STOP_SHARE = 0.15

    data class Open(val symbol: String, val live: Boolean, val qty: Int, val avg: Double, val ltp: Double?)

    /** The stop to offer for [p], or null (a short, or the price is already at or under the stop: close it instead). */
    fun stopFor(p: Open): Double? {
        if (p.qty <= 0 || p.avg <= 0) return null
        val stop = Math.round(p.avg * (1 - STOP_SHARE) * 20) / 20.0   // on the 0.05 tick
        if (p.ltp != null && p.ltp <= stop) return null
        return stop
    }

    /**
     * May the stop be set without asking (Boss, 4 Oct: "guard my live positions")? Only with his switch on, only on a
     * bought option (a stop that can only close it, never open or add), and only when a stop under the price exists.
     */
    fun setAlone(switchOn: Boolean, p: Open, stop: Double?): Boolean = switchOn && p.qty > 0 && p.avg > 0 && stop != null

    /** Told after the guard set a stop by itself ([result]: what the app said). */
    fun saySet(p: Open, stop: Double, result: String): String =
        "${p.symbol} (${if (p.live) "Zerodha" else "paper"}, ${p.qty}) had no stop, so I set one at %.2f, 15%% under the %.2f you paid. ".format(Locale.ENGLISH, stop, p.avg) + result

    fun say(p: Open, stop: Double?): String = if (stop != null)
        "${p.symbol} (${if (p.live) "Zerodha" else "paper"}, ${p.qty}) has no stop. Shall I set one at %.2f, 15%% under the %.2f you paid?".format(Locale.ENGLISH, stop, p.avg)
    else if (p.qty < 0) "${p.symbol} (${if (p.live) "Zerodha" else "paper"}) is a short with no stop: set one from the position."
    else "${p.symbol} has no stop and is already below 15%% under what you paid: close it or set a stop from the position.".format(Locale.ENGLISH)
}

/** The 14:55 heads-up on expiry day: what the 15:05 square-off will close. Pure. */
object ExpiryPreview {
    const val AT = 14 * 60 + 55

    fun lines(expiring: List<String>, squareOffOn: Boolean): List<String> = when {
        expiring.isEmpty() -> emptyList()
        !squareOffOn -> listOf("Expiry day: ${expiring.size} position${if (expiring.size > 1) "s expire" else " expires"} today and the 15:05 square-off is OFF: " +
            expiring.joinToString(", ") + ". Close them yourself if you don't want them settled.")
        else -> listOf("At 15:05 the expiry square-off will close ${expiring.size} position${if (expiring.size > 1) "s" else ""}: ${expiring.joinToString(", ")}.")
    }
}

/**
 * How sure Jarvis is about a suggestion, 1 to 5 (the owner's wish, 2026-10-02): the pattern's own record, whether
 * the market's regime fits the side, how dear options are, and the trade check. Each point says why. Pure.
 */
object Confidence {
    data class Score(val stars: Int, val why: List<String>) {
        fun text() = "Confidence $stars/5 (${why.joinToString("; ")})."
    }

    fun score(call: Boolean, hitRate: Double?, regime: Regime.Kind?, ivRank: Double?, check: TradeCheck.Level?): Score {
        var s = 3.0
        val why = ArrayList<String>()
        hitRate?.let { h ->
            when { h >= 0.6 -> { s += 1; why += "the pattern worked %.0f%% of the time".format(Locale.ENGLISH, h * 100) }
                h < 0.5 -> { s -= 1; why += "the pattern worked only %.0f%% of the time".format(Locale.ENGLISH, h * 100) }
                else -> why += "the pattern's record is middling" }
        }
        regime?.let { r ->
            when { r == Regime.Kind.UP && call || r == Regime.Kind.DOWN && !call -> { s += 1; why += "the market is ${r.label}, with it" }
                r == Regime.Kind.UP || r == Regime.Kind.DOWN -> { s -= 1; why += "the market is ${r.label}, against it" }
                else -> { s -= 0.5; why += "the market is ${r.label}" } }
        }
        ivRank?.let { v ->
            when { v >= IvRank.CAREFUL -> { s -= 1; why += "options are dear" }
                v <= 0.25 -> { s += 0.5; why += "options are cheap" } }
        }
        when (check) {
            TradeCheck.Level.CAREFUL -> { s -= 1; why += "the trade check says careful" }
            TradeCheck.Level.GO -> Unit
            null -> Unit
            else -> { s -= 2; why += "the trade check says don't trade" }
        }
        if (why.isEmpty()) why += "little to go on"
        return Score(Math.round(s).toInt().coerceIn(1, 5), why)
    }
}

/**
 * "What if I had taken the 10:30 suggestion?" (the owner's wish, 2026-10-02): the time asked, read from the words.
 * Pure (the replay itself is [JarvisTrades.OptionSim]).
 */
object WhatIf {
    /** The minute of day named ("10:30", "10 30", "2 pm", "half past ten" not read), within market hours, or null. */
    fun minute(text: String): Int? {
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9: ]"), " ").replace(Regex("\\s+"), " ") + " "
        fun at(m: MatchResult): Int? {
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (m.groupValues[3] == "pm" && h < 12) h += 12
            if (m.groupValues[3].isEmpty() && h in 1..3) h += 12
            return (h * 60 + min).takeIf { min < 60 && it in (9 * 60 + 15)..(15 * 60 + 30) }
        }
        // A written time ("10:30", "2 pm") first; else the first number that is a market-hours time.
        Regex(" (\\d{1,2}):(\\d{2}) ?(am|pm)? | (\\d{1,2})() ?(am|pm) ").findAll(t).forEach { m ->
            val g = if (m.groupValues[1].isNotEmpty()) listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]) else listOf(m.groupValues[4], "", m.groupValues[6])
            var h = g[0].toInt(); val min = g[1].ifEmpty { "0" }.toInt()
            if (g[2] == "pm" && h < 12) h += 12
            if (g[2].isEmpty() && h in 1..3) h += 12
            (h * 60 + min).takeIf { min < 60 && it in (9 * 60 + 15)..(15 * 60 + 30) }?.let { return it }
        }
        return Regex(" (\\d{1,2})(?: (\\d{2}))? ?(am|pm)? ").findAll(t).firstNotNullOfOrNull { at(it) }
    }

    fun asked(text: String): Boolean = Regex("(?i)\\bwhat if (i|we) (had )?(taken|took|take|bought|approved)|\\bwould (i|it) have (made|lost)|\\bif i had (taken|approved|bought)").containsMatchIn(text)
}

/** Quiet hours (the owner's wish, 2026-10-02): nothing spoken unasked between [from] and [to] (minutes of day). Pure. */
object Quiet {
    const val FROM = 22 * 60
    const val TO = 7 * 60

    fun now(t: LocalTime, from: Int = FROM, to: Int = TO): Boolean {
        val m = t.hour * 60 + t.minute
        return if (from <= to) m in from until to else m >= from || m < to
    }
}

/**
 * "Tell me if BankNifty falls 1% from here" (the owner's wish, 2026-10-02): the move in percent, its direction, and
 * the level it means from [price]. Pure.
 */
object MoveAlarm {
    data class Move(val pct: Double, val up: Boolean)

    fun read(s: String): Move? {
        // Only a condition ("if / when it falls 1%"), never a question about a move that happened ("why is it down 2%").
        if (!Regex(" (if|when|once|whenever) ").containsMatchIn(s)) return null
        val m = Regex(" (falls|drops|goes down|rises|goes up|jumps|crashes|gains|loses|sinks) (by )?(\\d+(?:\\.\\d+)?) ?(%|percent|per cent) ").find(s)
            ?: Regex(" (\\d+(?:\\.\\d+)?) ?(%|percent|per cent) (fall|drop|rise|jump|gain|loss) ").find(s)?.let { x ->
                return x.groupValues[1].toDouble().takeIf { it > 0 && it <= 20 }?.let { Move(it, x.groupValues[3] in setOf("rise", "jump", "gain")) } }
            ?: return null
        val pct = m.groupValues[3].toDouble().takeIf { it > 0 && it <= 20 } ?: return null
        return Move(pct, m.groupValues[1] in setOf("rises", "goes up", "jumps", "gains"))
    }

    fun level(price: Double, move: Move): Double = Math.round(price * (1 + (if (move.up) 1 else -1) * move.pct / 100) * 100) / 100.0
}

/** Live prices stopped (the owner's wish, 2026-10-02): told once when the feed is older than [STALE_MINUTES] in market hours. Pure. */
object FeedHealth {
    const val STALE_MINUTES = 2L

    fun stale(lastPrice: LocalDateTime?, now: LocalDateTime, marketOpen: Boolean): Boolean =
        marketOpen && now.toLocalTime() >= LocalTime.of(9, 20) && (lastPrice == null || lastPrice.isBefore(now.minusMinutes(STALE_MINUTES)))

    fun say(lastPrice: LocalDateTime?): String = "Boss, live prices have stopped" +
        (lastPrice?.let { " since %02d:%02d".format(Locale.ENGLISH, it.hour, it.minute) } ?: "") +
        ". The arms may act on old prices: check the connection, or switch them off until prices are back."
}

/** Every change to the app's limits, for "what did I change this week" (the owner's wish, 2026-10-02). Pure. */
object SettingsHistory {
    data class Change(val at: LocalDateTime, val key: SettingsTalk.Key, val old: Double, val new: Double, val by: String)

    fun lines(all: List<Change>, today: LocalDate, days: Long = 7): List<String> {
        val recent = all.filter { !it.at.toLocalDate().isBefore(today.minusDays(days - 1)) }.sortedBy { it.at }
        if (recent.isEmpty()) return listOf("No limits changed in the last $days days.")
        return listOf("${recent.size} change${if (recent.size > 1) "s" else ""} in the last $days days:") + recent.map {
            "${it.at.toLocalDate()} " + "%02d:%02d".format(Locale.ENGLISH, it.at.hour, it.at.minute) +
                ": ${it.key.label} ${SettingsTalk.show(it.key, it.old)} to ${SettingsTalk.show(it.key, it.new)} (${it.by})" +
                if (SettingsTalk.loosens(it.key, it.old, it.new)) ", more risk." else "."
        }
    }
}

/**
 * The Saturday report card (the owner's wish, 2026-10-02): the week's P&L, Jarvis's suggestions taken and skipped
 * and how they did, the best and worst arm, and one habit to fix. Pure.
 */
object ReportCard {
    data class ArmWeek(val arm: String, val net: Double, val trades: Int)

    fun lines(pnl: Double?, trades: Int, suggestions: List<JarvisTrades.Suggestion>, arms: List<ArmWeek>, habit: String?): List<String> {
        val out = ArrayList<String>()
        out += if (pnl == null || trades == 0) "This week: no trades closed." else "This week: ${AppFacts.rs(pnl)} over $trades trade${if (trades > 1) "s" else ""}."
        if (suggestions.isNotEmpty()) {
            val mine = suggestions.filter { it.answer == JarvisTrades.SELF }
            val asked = suggestions - mine.toSet()
            val taken = asked.filter { it.answer == "approved" }; val skipped = asked.filter { it.answer != "approved" }
            fun pts(l: List<JarvisTrades.Suggestion>) = l.mapNotNull { it.points }.let { p -> if (p.isEmpty()) "" else ", %+.1f points".format(Locale.ENGLISH, p.sum()) }
            if (asked.isNotEmpty()) out += "My suggestions: ${asked.size}; you took ${taken.size}${pts(taken)}, skipped ${skipped.size}${pts(skipped)}."
            if (mine.isNotEmpty()) out += "I took ${mine.size} on paper by myself${pts(mine)}."
        } else out += "I suggested no trades this week."
        val ranked = arms.filter { it.trades > 0 }.sortedByDescending { it.net }
        if (ranked.isNotEmpty()) {
            out += "Best arm: ${ranked.first().arm} ${AppFacts.rs(ranked.first().net)}."
            if (ranked.size > 1) out += "Worst arm: ${ranked.last().arm} ${AppFacts.rs(ranked.last().net)}."
        }
        habit?.let { out += "One habit to fix: $it" }
        return out
    }
}

/**
 * When Jarvis may act on its own idea without asking (Boss, 4 Oct: "independent"): only with the switch on, only when
 * the trade would go to the PAPER account (never Zerodha), and only with at least [MIN_STARS] of confidence. Pure.
 */
object ActAlone {
    const val MIN_STARS = 3
    /** Trades at one confidence level before its result is trusted to move the bar. */
    const val JUDGE = 8
    /** Above 5 stars: Jarvis acts alone on nothing until its record says otherwise. */
    const val NONE = 6

    fun ok(switchOn: Boolean, goesLive: Boolean, stars: Int, bar: Int = MIN_STARS): Boolean = switchOn && !goesLive && stars >= bar

    /**
     * The bar Jarvis sets itself from its own closed trades ([results]: the confidence it had, the rupees made): from
     * [MIN_STARS] up, a level that has lost money over [JUDGE] trades or more is not good enough, so the bar moves above
     * it. It moves back down by itself when that level's record turns (it keeps being traded when Boss approves). Pure.
     */
    fun bar(results: List<Pair<Int, Double>>): Int {
        var bar = MIN_STARS
        while (bar < NONE) {
            val at = results.filter { it.first == bar }
            if (at.size >= JUDGE && at.sumOf { it.second } < 0) bar++ else break
        }
        return bar
    }

    /** One line for "how are your trades doing": each level's record and the bar it set. */
    fun say(results: List<Pair<Int, Double>>): String? {
        if (results.isEmpty()) return null
        val levels = results.groupBy { it.first }.toSortedMap().map { (s, l) -> "$s/5: ${l.size} trade${if (l.size > 1) "s" else ""} ${AppFacts.rs(l.sumOf { it.second })}" }
        val b = bar(results)
        return "By my confidence: ${levels.joinToString(", ")}. " + when {
            b >= NONE -> "Every level from $MIN_STARS/5 up has lost, so I take nothing on paper by myself now; I only ask."
            b > MIN_STARS -> "Below $b/5 my trades lost, so I now take one by myself only at $b/5 or more."
            else -> "I take one by myself at $MIN_STARS/5 or more."
        }
    }
}
