package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The health of Boss's own running strategies (usefulness round 10, 2026-10-05): "how are my bots doing?", "is the ORB
 * arm behaving?", "which strategy is losing?" - each ORB arm, Pine auto-trade script, strategy and Solo, today and this
 * week: trades, won, net, deepest fall, losses in a row, against its tested record (its backtest, or its own record
 * before this week when it has no backtest on the phone), its daily loss limit, its last signal, and whether it is
 * behaving unusually - far more trades today than its tested average a day, or a losing run beyond its tested worst.
 * Facts only: stopping one stays Boss's call (an unasked word only offers it, asked first unless he chose automatic
 * stops; nothing here stops, arms or places anything). Pure.
 */
object BotHealth {
    /** One closed trade of a strategy. */
    data class Trade(val openedAt: LocalDateTime, val closedAt: LocalDateTime, val net: Double)

    /**
     * A tested record: [source] in words ("its backtest over 240 BankNifty days", "its own record before this week");
     * [perDay] trades a day ([perDayWords] what "a day" means there); [winRate] 0..1; [worstStreak] its longest run of
     * losses; [maxDrawdown] its deepest fall in rupees (null when not known in rupees).
     */
    data class Tested(val source: String, val trades: Int, val perDay: Double?, val perDayWords: String, val winRate: Double?,
                      val worstStreak: Int?, val maxDrawdown: Double? = null)

    /**
     * One strategy. [kind] "ORB arm", "Pine script", "strategy", "Solo"; [where] "Paper" or "Zerodha"; [trades] every
     * closed trade on the phone (any order); [dayLossLimit] its own daily loss limit in rupees (null: none of its own);
     * [limitHit] the app paused it for that limit today; [lastSignal] its last signal (null: the last entry is said);
     * [holding] it holds a position now.
     */
    data class Bot(val name: String, val kind: String, val where: String, val on: Boolean, val trades: List<Trade>,
                   val tested: Tested? = null, val dayLossLimit: Double? = null, val limitHit: Boolean = false,
                   val lastSignal: LocalDateTime? = null, val holding: Boolean = false)

    /** [streak]: losses in a row at the end; [worstStreak]: the longest run of losses in the trades. */
    data class Stats(val trades: Int, val wins: Int, val net: Double, val maxDrawdown: Double, val streak: Int, val worstStreak: Int) {
        val winRate: Double? get() = if (trades == 0) null else wins.toDouble() / trades
    }

    enum class Flag { BUSY, STREAK }

    /** Something unusual about [bot], said without amounts ([text]). */
    data class Unusual(val bot: String, val where: String, val flag: Flag, val text: String)

    /** At least this many trades of its own before this week to stand in for a missing backtest. */
    const val MIN_OWN = 15
    /** Busy: this many times its tested trades a day, and at least [BUSY_MIN] trades today. */
    const val BUSY_TIMES = 3.0
    const val BUSY_MIN = 3

    fun stats(ts: List<Trade>): Stats {
        val s = ts.sortedBy { it.closedAt }
        var peak = 0.0; var run = 0.0; var dd = 0.0; var streak = 0; var worst = 0
        for (t in s) {
            run += t.net; peak = maxOf(peak, run); dd = maxOf(dd, peak - run)
            if (t.net < 0) { streak++; worst = maxOf(worst, streak) } else streak = 0
        }
        return Stats(s.size, s.count { it.net > 0 }, s.sumOf { it.net }, dd, streak, worst)
    }

    /** A backtest's record from its trades' rupees in order ([nets]) over [days] sessions. */
    fun tested(source: String, days: Int, nets: List<Double>): Tested? {
        if (days <= 0 || nets.isEmpty()) return null
        val st = stats(nets.mapIndexed { i, n -> val t = LocalDateTime.of(2000, 1, 1, 0, 0).plusMinutes(i.toLong()); Trade(t, t, n) })
        return Tested(source, nets.size, nets.size.toDouble() / days, "a day in its backtest", st.winRate, st.worstStreak, st.maxDrawdown)
    }

    /** Its own record before [weekStart] (at least [MIN_OWN] trades), trades counted on the days it traded. */
    fun own(ts: List<Trade>, weekStart: LocalDate): Tested? {
        val before = ts.filter { it.closedAt.toLocalDate() < weekStart }
        if (before.size < MIN_OWN) return null
        val st = stats(before)
        val days = before.map { it.closedAt.toLocalDate() }.distinct().size
        return Tested("its own record before this week (${before.size} trades)", before.size, before.size.toDouble() / days,
            "on a day it traded, by its own record", st.winRate, st.worstStreak, st.maxDrawdown)
    }

    /** Monday of [today]'s week. */
    fun weekStart(today: LocalDate): LocalDate = today.with(DayOfWeek.MONDAY)

    /** The record [b] is compared with: its backtest, else its own before this week. */
    fun record(b: Bot, today: LocalDate): Tested? = b.tested ?: own(b.trades, weekStart(today))

    private fun todays(b: Bot, today: LocalDate) = b.trades.filter { it.closedAt.toLocalDate() == today }
    private fun weeks(b: Bot, today: LocalDate) = b.trades.filter { val d = it.closedAt.toLocalDate(); d >= weekStart(today) && d <= today }

    /** Today's net reached [Bot.dayLossLimit], or the app paused it for it. */
    fun limitHit(b: Bot, today: LocalDate): Boolean = b.limitHit || b.dayLossLimit?.takeIf { it > 0 }?.let { l -> todays(b, today).sumOf { it.net } <= -l } == true

    /** What is unusual about [b] today, in words without amounts. */
    fun unusual(b: Bot, today: LocalDate): List<Unusual> {
        val rec = record(b, today) ?: return emptyList()
        val out = ArrayList<Unusual>()
        val n = todays(b, today).size
        val per = rec.perDay
        if (per != null && per > 0 && n >= BUSY_MIN && n >= BUSY_TIMES * per)
            out += Unusual(b.name, b.where, Flag.BUSY, "${b.name} has taken ${words(n)} trades today, against about ${dec(per)} ${rec.perDayWords}" +
                (if (n / per >= 2) " - about ${(n / per).roundToInt()} times as many" else "") + ".")
        val all = stats(b.trades)
        val last = b.trades.maxByOrNull { it.closedAt }
        val worst = rec.worstStreak
        if (worst != null && all.streak >= 2 && all.streak > worst && last != null && last.closedAt.toLocalDate() == today)
            out += Unusual(b.name, b.where, Flag.STREAK, "${b.name} has lost ${words(all.streak)} in a row, beyond the worst run of ${words(worst)} in ${short(rec)}.")
        return out
    }

    private fun short(t: Tested) = if (t.source.startsWith("its backtest")) "its backtest" else "its own record"

    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
    private fun dec(x: Double) = if (x >= 10) "%.0f".format(Locale.ENGLISH, x) else "%.1f".format(Locale.ENGLISH, x)
    private fun fall(x: Double) = if (x <= 0.005) "no fall" else "deepest fall -Rs " + "%,.0f".format(Locale.ENGLISH, x)
    private fun rs0(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun at(t: LocalDateTime, today: LocalDate) = if (t.toLocalDate() == today) "${t.format(HM)} today" else "${t.format(HM)} ${t.format(DAY)}"

    private fun period(label: String, s: Stats, run: Boolean): String = if (s.trades == 0) "$label no trades" else
        "$label ${s.trades} trade${if (s.trades == 1) "" else "s"}, ${s.wins} won (${pct(s.winRate!!)}), net ${rs0(s.net)}, ${fall(s.maxDrawdown)}" +
            (if (run) ", ${if (s.worstStreak == 0) "no losses in a row" else "worst run ${s.worstStreak} loss${if (s.worstStreak == 1) "" else "es"} in a row"}" else "")

    private fun line(b: Bot, now: LocalDateTime): String {
        val today = now.toLocalDate()
        val d = stats(todays(b, today)); val w = stats(weeks(b, today)); val all = stats(b.trades)
        val parts = ArrayList<String>()
        parts += "${b.name} (${b.kind}, ${b.where.lowercase().let { if (it == "paper") "paper" else "Zerodha" }}, ${if (b.on) "on" else "off"}${if (b.holding) ", holding a position now" else ""}): " +
            period("today", d, false)
        parts += period("this week", w, true)
        if (all.streak > 0) parts += "${all.streak} loss${if (all.streak == 1) "" else "es"} in a row now"
        val rec = record(b, today)
        parts += if (rec == null) "no backtest on the phone and too few earlier trades of its own to compare with" else {
            val c = ArrayList<String>()
            rec.perDay?.let { c += "about ${dec(it)} trades ${rec.perDayWords}" }
            rec.winRate?.let { r -> c += "won ${pct(r)}" + (w.winRate?.let { " (this week ${pct(it)})" } ?: "") }
            rec.worstStreak?.let { c += "worst run ${it} loss${if (it == 1) "" else "es"} in a row" }
            rec.maxDrawdown?.let { c += fall(it) }
            "against ${rec.source}: ${c.joinToString(", ")}"
        }
        val limit = b.dayLossLimit?.takeIf { it > 0 }
        if (limit != null) parts += "its daily loss limit Rs " + "%,.0f".format(Locale.ENGLISH, limit) + ": " + if (limitHit(b, today)) "hit today" else "not hit today"
        else if (b.limitHit) parts += "paused today for its daily loss limit"
        val sig = b.lastSignal
        val entry = b.trades.maxByOrNull { it.openedAt }?.openedAt
        parts += when {
            sig != null -> "last signal ${at(sig, today)}"
            entry != null -> "last entry ${at(entry, today)}"
            else -> "no signal or entry recorded"
        }
        return parts.joinToString("; ") + "."
    }

    /** The bots [text] names (an "ORB" in it names the ORB arm, not "ORB Fresh"); empty when it names none. */
    fun named(text: String, names: Collection<String>): Set<String> {
        val t = norm(text)
        val hit = names.filter { n -> norm(n).trim().let { it.isNotEmpty() && t.contains(" $it ") } }.sortedByDescending { norm(it).length }
        // A name inside a longer one named ("orb" in "orb fresh") is that longer one's.
        val kept = ArrayList<String>()
        for (n in hit) if (kept.none { k -> norm(k).contains(norm(n)) }) kept += n
        return kept.toSet()
    }

    /**
     * The health of [bots] (those named in [question] when it names any; off ones without a trade this week are only
     * counted), worst week first, then what is unusual, what is losing, and the account's breaker. [now]: IST.
     */
    fun lines(bots: List<Bot>, now: LocalDateTime, question: String = "", breakerTripped: Boolean = false): List<String> {
        if (bots.isEmpty()) return listOf("You have no strategies, arms, Pine auto-trade scripts or Solo running or on record, Boss: nothing to check.")
        val today = now.toLocalDate()
        val named = named(question, bots.map { it.name })
        val pool = if (named.isEmpty()) bots else bots.filter { it.name in named }
        val (shown, idle) = pool.partition { it.on || it.holding || weeks(it, today).isNotEmpty() }
        val out = ArrayList<String>()
        out += "Your strategies' health, Boss, at ${now.toLocalTime().format(HM)} - today, and this week from ${weekStart(today).format(DAY)}:"
        if (shown.isEmpty()) out += "None of ${if (named.isEmpty()) "them" else named.joinToString(", ")} is on or has traded this week."
        shown.sortedWith(compareBy<Bot> { weeks(it, today).sumOf { t -> t.net } }.thenBy { it.name }).forEach { out += line(it, now) }
        if (idle.isNotEmpty() && shown.isNotEmpty()) out += "${idle.size} more ${if (idle.size == 1) "is" else "are"} off with no trades this week: ${idle.take(5).joinToString(", ") { it.name }}${if (idle.size > 5) " and ${idle.size - 5} more" else ""}."
        val odd = shown.flatMap { unusual(it, today) }
        out += if (odd.isEmpty()) "Nothing unusual against their tested records today." else "Unusual: " + odd.joinToString(" ") { it.text }
        val losing = shown.map { it to weeks(it, today).sumOf { t -> t.net } }.filter { it.second < 0 }.sortedBy { it.second }
        if (losing.isNotEmpty()) out += "Losing this week: " + losing.joinToString(", ") { (b, n) -> "${b.name} ${rs0(n)}" } + "."
        if (breakerTripped) out += "The daily loss breaker tripped today: the bots sold and stopped."
        out += "Facts from the app's own books, not advice - stopping one is your call, Boss (say \"stop\" and its name)."
        return out
    }

    /** Said unasked when something is unusual: no amount (a locked phone may be heard); null when nothing is. */
    fun spoken(odd: List<Unusual>): String? {
        if (odd.isEmpty()) return null
        return "Boss, a word on your strategies: " + odd.joinToString(" ") { it.text } + " The details are in the chat - your call, Boss."
    }

    private fun words(k: Int) = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten").getOrElse(k) { "$k" }

    private fun norm(text: String) = " " + spacedWords(text.lowercase()) + " "

    // (An ORB arm by its name too - "is ORB 5 behaving?": routing audit, round 8; the retired arms' shadows - "how are the shadows doing?" - 06 Oct.)
    private val BOTS = "(bots?|algos?|strateg(y|ies)|arms?|scripts?|pine scripts?|auto ?trades?|orb \\d+|shadows?)"
    private val ASK = Regex(" (how (are|is|re) (my|our|the|mere|meri) (\\w+ )?$BOTS (doing|performing|working|going|behaving|holding up|faring)|" +
        "(is|are) (my |our |the )?(\\w+ )?(\\w+ )?$BOTS (behaving|ok|okay|fine|healthy|working (ok|okay|fine|properly|right)|alright|all right|misbehaving|acting up|going crazy|overtrading)|" +
        "(which|what) (of my )?$BOTS (is|are) (losing|lose|in loss|bleeding|down|worst)|(which|what) $BOTS lost|" +
        "$BOTS health|health (check )?(of|on|for) (all )?(my|our) $BOTS|" +
        "(my|our|mere|meri) $BOTS (kaise|kesa|kaisa|kaisi|kese) (chal|kar)|$BOTS (theek|thik|sahi|ok) (chal|hai|hain)|" +
        // Hinglish (routing audit, round 8): "mere bots ka haal", "kaun si strategy loss mein hai".
        "(my|our|mere|meri) $BOTS (ka|ki|ke) (haal|halat|haalat)|(kaun si|kaunsi|konsi|kon si|kaun sa|kaunsa|konsa) $BOTS (loss|nuksan|nuksaan|ghate) (mein|me|main) (hai|hain|he|chal))")
    /** Not this check: a command, a backtest, the arms' regime fit, the paper tests or making one. */
    private val NOT = Regex(" (stop|start|arm it|disarm|switch|turn (on|off)|pause|backtest|back test|suit|suits|held up|create|make|build|write|add|set) ")

    /** "How are my bots doing?", "is the ORB arm behaving?", "which strategy is losing?", "mere bots kaise chal rahe hain". */
    fun asked(text: String): Boolean = listOf(text, Ask.reading(text)).any { s ->
        val t = norm(s)
        ASK.containsMatchIn(t) && !NOT.containsMatchIn(t)
    }
}
