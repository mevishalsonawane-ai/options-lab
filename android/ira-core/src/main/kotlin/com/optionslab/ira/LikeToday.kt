package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Is today like any past day? (Jarvis reasoning, round 18): "is today like any past day?", "has there been a day like
 * today?", "which past days started like today?", "how did days like today end?", "similar days to today for BankNifty",
 * "aaj jaisa din pehle kab tha". Today's start is read on three measures from the phone's 1-minute candles - the opening
 * gap from the previous whole session's close, the range from 09:15 to 10:15 (to the minute now while the first hour is
 * still trading) as a share of the open, and India VIX at that minute - and each whole past session is read the same way
 * at the same minute. A past day matches when all three sit close to today's ([GAP_BAND] points of a percent of the gap,
 * [RANGE_BAND] of the range, [VIX_BAND] of VIX). How many matched, and how those days went on from that minute to their
 * close - the median move, the lowest and highest, how many closed above or below that minute's price - with the nearest
 * few named with their own numbers, and today's own move since that minute beside. Past days on this phone, said with
 * their counts: never a forecast, advice or a promise that today ends the same way; market data only; nothing acts.
 * Two named days side by side stay [DayCompare]'s, the gap's own record [GapRecord]'s. Pure.
 */
object LikeToday {
    /** One session's start (to [cut]) and how it went on from there. */
    data class Day(
        val day: LocalDate, val gapPct: Double, val rangePct: Double, val vix: Double?,
        /** The price at the cut minute, the session's open and its close. */
        val atCut: Double, val open: Double, val close: Double,
    ) {
        /** From the cut minute's price to the close, % of that price. */
        val restPct: Double get() = (close - atCut) / atCut * 100
    }

    /** A past day's gap within this many points of a percent of today's matches. */
    const val GAP_BAND = 0.25
    /** A past day's first-hour range within this share of today's (either way) matches. */
    const val RANGE_BAND = 0.25
    /** A past day's India VIX within this share of today's matches. */
    const val VIX_BAND = 0.10
    /** Fewer whole past sessions than this: too few to look for a like day. */
    const val MIN_SESSIONS = 20
    /** Fewer matches than this: too few to say how such days ended (the nearest are still named). */
    const val MIN_MATCH = 5
    /** The nearest days named. */
    const val NEAREST = 3
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    const val MAX_DAYS_APART = 4L
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val FIRST_HOUR: LocalTime = LocalTime.of(10, 15)
    /** Before this minute today's start is too short to read. */
    val EARLIEST: LocalTime = LocalTime.of(9, 30)
    val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)

    const val NOTE = "Past days on this phone that started like today, Boss - how they ended is a record, not a forecast for today."
    const val NOT_HERE = "I look for days like today on Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so it has no opening gap or first hour, and India VIX is one of the measures."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun sp(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun p(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.year}"

    // ---- the question ---------------------------------------------------------------------------------------------

    private const val TODAY = "(today|today s|todays|this morning|this session|aaj|aaj ke|aaj ka)"
    private const val DAY = "(day|days|session|sessions|morning|mornings|start|starts|open|opens|din)"
    /** "days like today", "sessions similar to today", "days that started like today", "a day like today". */
    private val LIKE = rx(" $DAY (that |which |when |where )?(were |was |looked |look |looks |started |start |opened |began |went )?(like|similar to|resembling|same as|just like) $TODAY ")
    /** "is today like any past day", "today resembles an earlier session", "was today similar to any other day". */
    private val TODAY_LIKE = rx(" $TODAY (is |was )?(like|similar to|resembles|resemble|the same as) (any|a|some|an|another|other)( past| earlier| previous| other| older| old)? $DAY ")
    /** "similar days to today", "lookalike days", "past days like this", "aaj jaisa din". */
    private val SIMILAR = rx(" (similar|lookalike|look alike|matching|comparable) (past |earlier |previous |other )?(day|days|session|sessions)( to today| as today| like today)? | " +
        "(aaj|aaj ke) (jaisa|jaise|jaisi) (din|session|subah) | (past|earlier|previous|old|older) (day|days|session|sessions) like (this|today) | " +
        // Round 16: "is there a past day that matches today"
        "(past|earlier|previous|old|older) (day|days|session|sessions) (that |which )?(match|matches|matched) today ")
    /** Round 16: "have we seen a day like this before" - the "we" is the market's past, never Boss's book (asked before [NOT]). */
    private val SEEN = rx("^ (jarvis )?have we (ever )?(seen|had) (a|any|another) (day|session) like (this|today)( before| earlier)? $")
    // Forecasts, advice, Boss's own book or bots, a named day (DayCompare's), gaps alone (GapRecord's), what-ifs, meanings.
    private val NOT = rx(" (will|would|going to|gonna|tomorrow|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|trade|trades|trading|" +
        "i|my|mine|we|our|what if|suppose|imagine|scenario|agar|yesterday|kal|parso|monday|tuesday|wednesday|thursday|friday|" +
        "gap|gaps|arm|arms|bot|bots|algo|algos|strategy|strategies|orb|backtest|alert|alerts|remind|news|mean|means|meaning|define) ")

    /** Asked whether today is like any past day: never a forecast, advice, a named day or Boss's own book. */
    fun asked(text: String): Boolean {
        val t = norm(text)
        if (SEEN.containsMatchIn(t)) return true
        if (NOT.containsMatchIn(t)) return false
        if (Market.mentioned(text).any { it == Market.GOLD }) return false
        return LIKE.containsMatchIn(t) || TODAY_LIKE.containsMatchIn(t) || SIMILAR.containsMatchIn(t)
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        val m = markets.firstOrNull() ?: return Market.NIFTY
        return if (m == Market.GOLD || m == Market.VIX) markets.firstOrNull { it != Market.GOLD && it != Market.VIX } else m
    }

    // ---- the reading ----------------------------------------------------------------------------------------------

    private fun whole(s: MarketStory.Session) = !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) && !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    /** India VIX's last price before [cut] on each day in [vix]. */
    private fun vixAt(vix: List<Candle>, cut: LocalTime): Map<LocalDate, Double> =
        vix.filter { !it.t.toLocalTime().isBefore(OPEN) && it.t.toLocalTime().isBefore(cut) && it.c > 0 }
            .groupBy { it.t.toLocalDate() }.mapValues { (_, b) -> b.maxBy { it.t }.c }

    /** [s] read to [cut] against [prevClose], or null when it holds no bar before [cut]. */
    private fun read(s: MarketStory.Session, prevClose: Double, cut: LocalTime, vix: Double?): Day? {
        val start = s.bars.filter { it.t.toLocalTime().isBefore(cut) }
        if (start.isEmpty() || s.open <= 0) return null
        val range = start.maxOf { it.h } - start.minOf { it.l }
        return Day(s.day, (s.open - prevClose) / prevClose * 100, range / s.open * 100, vix, start.last().c, s.open, s.close)
    }

    /** Today's start to [cut] (null when today is not on the phone from its open or has no whole session before it) and the past days read the same way. */
    fun days(bars: List<Candle>, vix: List<Candle>, today: LocalDate, cut: LocalTime): Pair<Day?, List<Day>> {
        val ss = MarketStory.sessions(bars).filter { it.bars.isNotEmpty() }
        val vx = vixAt(vix, cut)
        val past = ArrayList<Day>()
        var now: Day? = null
        for (i in 1 until ss.size) {
            val prev = ss[i - 1]; val s = ss[i]
            if (!whole(prev) || prev.close <= 0 || ChronoUnit.DAYS.between(prev.day, s.day) > MAX_DAYS_APART) continue
            if (s.day == today) {
                if (!s.bars.first().t.toLocalTime().isAfter(FIRST_BY)) now = read(s, prev.close, cut, vx[s.day])
            } else if (s.day.isBefore(today) && whole(s)) read(s, prev.close, cut, vx[s.day])?.let { past += it }
        }
        return now to past.takeLast(MAX_SESSIONS)
    }

    /** True when [d] sits close to [t] on every measure today has ([t]'s VIX unknown: on the gap and range alone). */
    fun matches(d: Day, t: Day): Boolean {
        if (abs(d.gapPct - t.gapPct) > GAP_BAND) return false
        if (t.rangePct <= 0 || abs(d.rangePct / t.rangePct - 1) > RANGE_BAND) return false
        if (t.vix != null) { val v = d.vix ?: return false; if (abs(v / t.vix - 1) > VIX_BAND) return false }
        return true
    }

    /** How far [d] sits from [t], each measure in its own band. */
    fun distance(d: Day, t: Day): Double {
        val g = (d.gapPct - t.gapPct) / GAP_BAND
        val r = if (t.rangePct > 0) (d.rangePct / t.rangePct - 1) / RANGE_BAND else 0.0
        val v = if (t.vix != null && d.vix != null) (d.vix / t.vix - 1) / VIX_BAND else if (t.vix != null) 3.0 else 0.0
        return sqrt(g * g + r * r + v * v)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    private fun line(d: Day, cut: LocalTime) = "${date(d.day)} (gap ${sp(d.gapPct)}, range ${p(d.rangePct)}" +
        (d.vix?.let { ", VIX ${n(it)}" } ?: "") + "; from ${hm(cut)} to the close ${sp(d.restPct)}, closed ${if (d.close > d.open) "above" else if (d.close < d.open) "below" else "at"} its open)"

    /** "Is today like any past day?" for [m] from [bars] (1-minute candles over several sessions) and India VIX's [vix] at [now] on [today]. */
    fun answer(m: Market, bars: List<Candle>, vix: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val clock = now.toLocalTime().withSecond(0).withNano(0)
        if (live && clock.isBefore(EARLIEST))
            return "Today ${m.label} has traded only since ${hm(OPEN)}, Boss - I set a day against the past from ${hm(EARLIEST)}, once there is a start to read."
        val cut = if (live && clock.isBefore(FIRST_HOUR)) clock else FIRST_HOUR
        val (t, past) = days(bars, vix, today, cut)
        if (t == null) return "I don't have today's ${m.label} candles from the open with the previous session's close beside them, Boss, so I can't set today against the past."
        if (past.size < MIN_SESSIONS)
            return "I have only ${past.size} whole ${m.label} session${if (past.size == 1) "" else "s"} on the phone with the previous close beside them, Boss - too few to look for a day like today (I need $MIN_SESSIONS)."
        val span = if (cut == FIRST_HOUR) "the first hour (${hm(OPEN)} to ${hm(cut)})" else "${hm(OPEN)} to ${hm(cut)}"
        val out = ArrayList<String>()
        out += "Today ${m.label} opened ${sp(t.gapPct)} from the previous close, its range over $span was ${p(t.rangePct)} of the open" +
            (t.vix?.let { ", and India VIX stood at ${n(it)} at ${hm(cut)}." } ?: ". India VIX is left out: I have no VIX candles for today before ${hm(cut)}.")
        val bands = "a gap within $GAP_BAND points of a percent of today's, a range within ${(RANGE_BAND * 100).toInt()}% of today's" +
            (if (t.vix != null) " and VIX within ${(VIX_BAND * 100).toInt()}% of today's" else "")
        val noVix = if (t.vix != null) past.count { it.vix == null } else 0
        val hit = past.filter { matches(it, t) }
        out += "Of the last ${past.size} whole ${m.label} sessions on this phone, each read over the same minutes, ${hit.size} matched - $bands" +
            (if (noVix > 0) " ($noVix had no VIX on the phone and could not match)" else "") + "."
        if (hit.size >= MIN_MATCH) {
            val rest = hit.map { it.restPct }
            val up = hit.count { it.close > it.atCut }; val down = hit.count { it.close < it.atCut }
            val aboveOpen = hit.count { it.close > it.open }
            out += "From ${hm(cut)} to the close those ${hit.size} days moved a median ${sp(median(rest))}, from ${sp(rest.min())} to ${sp(rest.max())}; " +
                "$up closed above their ${hm(cut)} price, $down below, and $aboveOpen of ${hit.size} closed above their open."
        } else if (hit.isNotEmpty()) {
            out += "Too few to say how such days ended (I need $MIN_MATCH)."
        } else out += "No past day on the phone started close enough on all of them."
        val near = past.sortedBy { distance(it, t) }.take(NEAREST)
        out += (if (hit.size >= MIN_MATCH) "The nearest: " else "The nearest, matched or not: ") + near.joinToString("; ") { line(it, cut) } + "."
        val todayBars = bars.filter { it.t.toLocalDate() == today }
        val last = todayBars.maxByOrNull { it.t }
        if (last != null && last.t.toLocalTime() >= cut) {
            val mv = (last.c - t.atCut) / t.atCut * 100
            out += if (live) "Today so far from ${hm(cut)}: ${sp(mv)} (${n(t.atCut)} to ${n(last.c)})." else "Today itself went ${sp(mv)} from ${hm(cut)} to the close."
        }
        out += NOTE
        return out.joinToString(" ")
    }
}
