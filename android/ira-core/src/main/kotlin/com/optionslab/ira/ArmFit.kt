package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * Which of my arms suits today? (Jarvis reasoning, round 19): "which of my arms suits today?", "which bot fits today's
 * conditions?", "how do my arms do on days like today?", "ORB's record on days like this", "aaj ke din kaun sa bot suit
 * karta hai". Today's BankNifty start is read as [LikeToday] reads it - the opening gap from the previous whole session's
 * close, the range from 09:15 to 10:15 (to the minute now while the first hour trades) as a share of the open, India VIX at
 * that minute - and put in a plain band on each measure: the gap's size either way ([GAP_BANDS]), the range's third among
 * the phone's past sessions, VIX's band ([VIX_BANDS]). Then each armed arm's own records - its two-year backtest (one lot)
 * and its paper trades, both by the day - are split on each measure into the days in today's band and the rest: rupees a
 * day and how many days ended up, side by side, and the days like today on all three. Fewer than [MIN_DAYS] days in a
 * band are counted, never averaged. The daily regime ("which arms suit this market") stays the account's own answer.
 * Each arm's own record beside today's facts: never a verdict, a forecast or advice - what stays armed is Boss's call, and
 * nothing here arms, stops, places or closes anything. Paper trades and switches are Boss's account: never on a locked
 * phone. Pure.
 */
object ArmFit {
    /** One arm: its name as Boss hears it, its switch, and its rupees by the day in its backtest ([tested]) and on paper ([paper]). */
    data class Arm(val name: String, val armed: Boolean, val tested: Map<LocalDate, Double>, val paper: Map<LocalDate, Double>)

    /** The measures today is banded on. */
    enum class Measure { GAP, RANGE, VIX }

    /** A gap's size (either way, % of the last close) under the first is flat, under the second middling, else large. */
    val GAP_BANDS = 0.25 to 0.75
    /** India VIX under the first is low, under the second middling, else high. */
    val VIX_BANDS = 13.0 to 17.0
    /** Fewer days than this in a band: counted, not averaged. */
    const val MIN_DAYS = 5
    /** Fewer whole past sessions on the phone than this: too few to band the range in thirds. */
    const val MIN_SESSIONS = 20

    const val NOTE = "Each arm's own record on days in today's bands, Boss - facts beside facts, not a forecast for today and not advice; what stays armed is your call."
    const val LOCKED = "Unlock the phone for your arms' records, Boss."
    const val NONE = "I have no arms on the phone to set beside today, Boss."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(rx("[^a-z0-9+ ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun sp(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun p(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun days(k: Int) = if (k == 1) "1 day" else "$k days"

    // ---- the question ---------------------------------------------------------------------------------------------

    private const val ARMS = "(arm|arms|bot|bots|strategy|strategies|algo|algos|setup|setups|orb|orb fresh|orb sweep|range fade|liquidity|liquidity 15\\+5)"
    private const val TODAY = "(today|todays|today s|this morning|this session|aaj)"
    private const val FIT = "(suit|suits|suited|fit|fits|match|matches|is right for|are right for|is best for|are best for|is made for|works best|work best|does best|do best)"
    /** "which of my arms suits today", "which bot fits today's conditions", "arms that suit today". */
    private val FITS_TODAY = rx(" $ARMS (s )?(that |which )?$FIT( for)? ($TODAY|days like (today|this)|a day like (today|this)) ")
    /** "how do my arms do on days like today", "orb's record on days like this", "my bots on mornings like today". */
    private val ON_DAYS = rx(" $ARMS (s )?(record |records |do |did |does |done |go |goes |went |fare |fared |perform |performed |perform |trade |traded )?(on|in) (days|sessions|mornings|a day) like (today|this|todays|today s) ")
    /** "aaj ke din kaun sa bot suit karta hai", "aaj jaise din par mere arms". */
    private val HINDI = rx(" (aaj|aaj ke din|aaj ke market) (ke liye )?(kaun sa|kaunsa|kaun si|kaunsi|konsa|konsi) (arm|bot|strategy|algo) (suit|fit|theek baith|theek baithta|theek baithti|theek baithega|theek baithegi) | " +
        "aaj (jaise|jaisa|jaisi) (din|dino|dinon) (par|pe|mein|me) (mere |meri )?(arm|arms|bot|bots|strategy|strategies) ")
    // Advice, a switch, a forecast, another day, a loss's story (ArmDay's), the daily regime (the account's), a what-if.
    private val NOT = rx(" (should|shall|disarm|switch off|switch on|turn off|turn on|stop|start|arm it|buy|sell|will|would|going to|gonna|tomorrow|" +
        "yesterday|kal|lose|lost|loss|losing|why|wrong|regime|this market|the market|what if|suppose|agar|chalega|chalegi) ")

    /** Asked which arm suits today, or how the arms do on days like today: never advice, a switch or a forecast. */
    fun asked(text: String): Boolean {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return false
        if (!rx(" $TODAY | (days|sessions|mornings|a day) like ").containsMatchIn(t)) return false
        return FITS_TODAY.containsMatchIn(t) || ON_DAYS.containsMatchIn(t) || HINDI.containsMatchIn(t)
    }

    // ---- the bands ------------------------------------------------------------------------------------------------

    internal fun gapBand(g: Double): Int = abs(g).let { if (it < GAP_BANDS.first) 0 else if (it < GAP_BANDS.second) 1 else 2 }
    internal fun vixBand(v: Double): Int = if (v < VIX_BANDS.first) 0 else if (v < VIX_BANDS.second) 1 else 2
    /** The range's third among [past] (the cuts at a third and two thirds). */
    internal fun cuts(past: List<LikeToday.Day>): Pair<Double, Double> {
        val s = past.map { it.rangePct }.sorted()
        return s[s.size / 3] to s[2 * s.size / 3]
    }
    internal fun rangeBand(r: Double, c: Pair<Double, Double>): Int = if (r < c.first) 0 else if (r < c.second) 1 else 2

    private fun band(m: Measure, d: LikeToday.Day, c: Pair<Double, Double>): Int? = when (m) {
        Measure.GAP -> gapBand(d.gapPct)
        Measure.RANGE -> rangeBand(d.rangePct, c)
        Measure.VIX -> d.vix?.let { vixBand(it) }
    }

    private val GAP_SHORT = listOf("flat opens", "gaps of 0.25-0.75%", "gaps of 0.75% or more")
    private val GAP_TODAY = listOf("a flat open", "a gap of 0.25-0.75%", "a gap of 0.75% or more")
    private val RANGE_SHORT = listOf("narrow starts", "middle starts", "wide starts")
    private val VIX_SHORT = listOf("VIX under 13", "VIX 13 to 17", "VIX 17 or over")
    private fun short(m: Measure, b: Int) = when (m) { Measure.GAP -> GAP_SHORT[b]; Measure.RANGE -> RANGE_SHORT[b]; Measure.VIX -> VIX_SHORT[b] }

    // ---- one record -----------------------------------------------------------------------------------------------

    private fun stat(nets: List<Double>): String =
        if (nets.size < MIN_DAYS) "only ${days(nets.size)}" else "${rs(nets.sum() / nets.size)} a day over ${nets.size} (${nets.count { it > 0 }} up)"

    /** One record of an arm ([what]: "its backtest", "on paper") split on each measure around today's band. */
    internal fun record(what: String, nets: Map<LocalDate, Double>, cond: Map<LocalDate, LikeToday.Day>, t: LikeToday.Day,
                        c: Pair<Double, Double>, measures: List<Measure>): String {
        if (nets.isEmpty()) return "$what: no traded days kept"
        val known = nets.keys.filter { it in cond }.sorted()
        if (known.isEmpty()) return "$what: none of its ${days(nets.size)} has BankNifty candles on the phone"
        val parts = ArrayList<String>()
        for (m in measures) {
            val want = band(m, t, c) ?: continue
            val read = known.filter { band(m, cond.getValue(it), c) != null }
            val like = read.filter { band(m, cond.getValue(it), c) == want }
            val other = read.filter { band(m, cond.getValue(it), c) != want }
            val rest = if (other.size < MIN_DAYS) "the other ${days(other.size)}" else other.map { nets.getValue(it) }.let { o -> "the other ${o.size} ${rs(o.sum() / o.size)} a day (${o.count { it > 0 }} up)" }
            parts += "${short(m, want)} like today ${stat(like.map { nets.getValue(it) })}, $rest"
        }
        val all = known.filter { d -> measures.all { m -> band(m, t, c) == null || band(m, cond.getValue(d), c) == band(m, t, c) } }
        parts += "like today on ${if (measures.size == 3) "all three" else "both"}: " +
            if (all.size < MIN_DAYS) days(all.size) else stat(all.map { nets.getValue(it) })
        val left = nets.size - known.size
        return "$what (${days(known.size)} with candles on the phone" + (if (left > 0) ", $left more without" else "") + "): " + parts.joinToString("; ")
    }

    // ---- the answer -----------------------------------------------------------------------------------------------

    /**
     * "Which of my arms suits today?" from BankNifty's 1-minute candles over several sessions [bars], India VIX's [vix], the
     * [arms] with their records, at [now] on [today].
     */
    fun answer(arms: List<Arm>, bars: List<Candle>, vix: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        if (arms.isEmpty()) return NONE
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(LikeToday.CLOSE)
        val clock = now.toLocalTime().withSecond(0).withNano(0)
        if (live && clock.isBefore(LikeToday.EARLIEST))
            return "BankNifty has traded only since ${hm(LikeToday.OPEN)} today, Boss - I set your arms' records beside today from ${hm(LikeToday.EARLIEST)}, once there is a start to read."
        val cut = if (live && clock.isBefore(LikeToday.FIRST_HOUR)) clock else LikeToday.FIRST_HOUR
        val (t, past) = LikeToday.days(bars, vix, today, cut)
        if (t == null) return "I don't have today's BankNifty candles from the open with the previous session's close beside them, Boss, so I can't band today."
        if (past.size < MIN_SESSIONS)
            return "I have only ${past.size} whole BankNifty session${if (past.size == 1) "" else "s"} on the phone, Boss - too few to band today's start against (I need $MIN_SESSIONS)."
        val c = cuts(past)
        val measures = if (t.vix != null) Measure.values().toList() else listOf(Measure.GAP, Measure.RANGE)
        val span = if (cut == LikeToday.FIRST_HOUR) "its first hour (${hm(LikeToday.OPEN)} to ${hm(cut)})" else "${hm(LikeToday.OPEN)} to ${hm(cut)}"
        val g = gapBand(t.gapPct)
        val r = rangeBand(t.rangePct, c)
        val out = ArrayList<String>()
        out += "Today BankNifty opened ${sp(t.gapPct)} from the previous close (${GAP_TODAY[g]}: " +
            "flat is under ${GAP_BANDS.first}% either way, large ${GAP_BANDS.second}% or more); its range over $span was ${p(t.rangePct)} of the open, " +
            "the ${listOf("lowest", "middle", "top")[r]} third of ${past.size} past sessions read over the same minutes (thirds cut at ${p(c.first)} and ${p(c.second)})" +
            (t.vix?.let { "; India VIX stood at ${n(it)} (${VIX_SHORT[vixBand(it)]})." } ?: "; India VIX is left out: I have no VIX candles for today before ${hm(cut)}.")
        val armed = arms.filter { it.armed }
        val told = armed.ifEmpty { arms }
        if (armed.isEmpty()) out += "None of your arms is armed right now, so here are all ${told.size}."
        val cond = past.associateBy { it.day }
        for (a in told) {
            out += "${a.name}${if (a.armed) " (armed)" else ""} - " +
                record("its backtest, one lot", a.tested, cond, t, c, measures) + ". " +
                record("On paper", a.paper, cond, t, c, measures) + "."
        }
        val off = arms.size - armed.size
        if (armed.isNotEmpty() && off > 0) out += "${if (off == 1) "1 arm" else "$off arms"} not armed ${if (off == 1) "is" else "are"} left out."
        out += NOTE
        return out.joinToString(" ")
    }

    /** Rupees by the day from (day, net) pairs - one trade or many on a day add up. */
    fun daily(trades: List<Pair<LocalDate, Double>>): Map<LocalDate, Double> =
        trades.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum() }
}
