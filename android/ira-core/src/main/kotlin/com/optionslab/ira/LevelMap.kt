package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * The level map on the chart (TODO I12, 13 Oct 2026; research/LEVEL_MAP.md): today's support and resistance - previous day
 * high / low / close, classic pivots, the nearest swing high and low of the last 5 sessions, the morning's largest-OI call
 * and put strikes, and VWAP with its bands when the app has them - each with "held N of M times" counted point in time on
 * the phone's own candles: for every earlier session the level is built only from the sessions before it, exactly as the
 * research does it (research/context/level_map.py), so a count never peeks ahead.
 *
 * Tested: the index came within [TOL] of the level. Held: no 5-minute close after the first touch went beyond it by more
 * than [TOL] for the rest of that session (resistance from below, support from above, by the side the index came from).
 * The research found NO kind of level held more often than a random price nearby (placebo 24-32%), so each label carries
 * the placebo rate. Display only. Pure: the app computes it off the main thread and the chart reads the result.
 */
object LevelMap {
    const val TOL = 0.0005
    const val SWING_DAYS = 5
    /** Sessions counted back for the held record (the phone's stored history; the newest this many). */
    const val HISTORY = 120

    enum class Type(val label: String, val words: String, val researchHeld: String) {
        PDH("PDH", "previous day high", "23-30%"), PDL("PDL", "previous day low", "23-25%"), PDC("PDC", "previous day close", "18-25%"),
        P("P", "pivot", "28-29%"), R1("R1", "pivot R1", "28-29%"), S1("S1", "pivot S1", "25%"), R2("R2", "pivot R2", "33-37%"), S2("S2", "pivot S2", "25-28%"),
        SWING_HIGH("Swing H", "swing high (5 sessions)", "20-26%"), SWING_LOW("Swing L", "swing low (5 sessions)", "23-24%"),
        MAX_OI_CALL("OI call", "largest call OI strike", "24-29%"), MAX_OI_PUT("OI put", "largest put OI strike", "25-26%"),
        VWAP("VWAP", "VWAP", ""), VWAP_BAND("VWAP band", "VWAP band", ""),
    }

    /** The research's placebo rate: random levels nearby held this often (research/LEVEL_MAP.md). */
    const val PLACEBO = "24-32%"

    data class Bar5(val open: Double, val high: Double, val low: Double, val close: Double)

    /** A level today. [held] / [tested]: the phone's record (null: no history for this kind, e.g. the OI strikes, VWAP). */
    data class Level(val type: Type, val price: Double, val held: Int? = null, val tested: Int? = null, val name: String = type.label) {
        fun label(): String = "$name ${String.format(Locale.ENGLISH, "%,.0f", price)}" + when {
            tested != null && tested > 0 -> " · held $held of $tested"
            type.researchHeld.isNotEmpty() -> " · 2024-26: held ${type.researchHeld}"
            else -> ""
        }
    }

    /** One session's 1-minute candles as 5-minute bars (09:15 start; a 5-minute slot with no candle is null). */
    fun bars5(day: List<Candle>): List<Bar5?> {
        val open = LocalTime.of(9, 15).toSecondOfDay() / 60
        val slots = arrayOfNulls<MutableList<Candle>>(75)
        for (c in day) {
            val m = c.t.toLocalTime().toSecondOfDay() / 60 - open
            if (m < 0 || m >= 375) continue
            (slots[m / 5] ?: ArrayList<Candle>().also { slots[m / 5] = it }).add(c)
        }
        return slots.map { s -> s?.sortedBy { it.t }?.let { Bar5(it.first().o, it.maxOf { b -> b.h }, it.minOf { b -> b.l }, it.last().c) } }
    }

    /** (tested, held) on [b] from bar [start] (the research's `test`). */
    fun test(b: List<Bar5?>, level: Double, start: Int = 0): Pair<Boolean, Boolean> {
        var prior: Double? = null
        for (k in start - 1 downTo 0) { val x = b.getOrNull(k); if (x != null) { prior = x.close; break } }
        val first = (start until b.size).firstOrNull { b[it] != null } ?: return false to false
        val pr = prior ?: b[first]!!.open
        val tol = level * TOL
        val resist = pr < level
        var touch = -1
        for (k in first until b.size) {
            val x = b[k] ?: continue
            if ((resist && x.high >= level - tol) || (!resist && x.low <= level + tol)) { touch = k; break }
        }
        if (touch < 0) return false to false
        for (k in touch until b.size) {
            val x = b[k] ?: continue
            if ((resist && x.close > level + tol) || (!resist && x.close < level - tol)) return true to false
        }
        return true to true
    }

    /** 5-minute fractal highs and lows (a bar above / below the two each side) of [sessions]. */
    fun swings(sessions: List<List<Bar5?>>): Pair<List<Double>, List<Double>> {
        val hs = ArrayList<Double>(); val ls = ArrayList<Double>()
        for (s in sessions) {
            val b = s.filterNotNull()
            for (i in 2 until b.size - 2) {
                val around = listOf(b[i - 2], b[i - 1], b[i + 1], b[i + 2])
                if (b[i].high > around.maxOf { it.high }) hs += b[i].high
                if (b[i].low < around.minOf { it.low }) ls += b[i].low
            }
        }
        return hs to ls
    }

    /** The price levels for a session opening at [open] from the sessions before it ([prev], oldest first, bars5). */
    fun levelsFrom(prev: List<List<Bar5?>>, open: Double): Map<Type, Double> {
        val y = prev.lastOrNull()?.filterNotNull()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        val h = y.maxOf { it.high }; val l = y.minOf { it.low }; val c = y.last().close
        val p = (h + l + c) / 3
        val out = linkedMapOf(Type.PDH to h, Type.PDL to l, Type.PDC to c, Type.P to p, Type.R1 to 2 * p - l, Type.S1 to 2 * p - h,
            Type.R2 to p + (h - l), Type.S2 to p - (h - l))
        val (hs, ls) = swings(prev.takeLast(SWING_DAYS))
        hs.filter { it > open }.minOrNull()?.let { out[Type.SWING_HIGH] = it }
        ls.filter { it < open }.maxOrNull()?.let { out[Type.SWING_LOW] = it }
        return out
    }

    /** A whole session: at least this many of its 75 five-minute slots hold a candle. */
    const val WHOLE_SLOTS = 60

    /** The whole sessions in [bars] before [today], oldest first, with their days (a partial session is left out). */
    fun sessionsBefore(bars: List<Candle>, today: LocalDate): List<Pair<LocalDate, List<Bar5?>>> =
        bars.groupBy { it.t.toLocalDate() }.toSortedMap().filterKeys { it < today }
            .map { (d, c) -> d to bars5(c) }.filter { (_, s) -> s.count { it != null } >= WHOLE_SLOTS }

    /**
     * The sessions [record] counts: the newest [HISTORY] after the first [SWING_DAYS], and only those whose session before
     * is held whole on the phone (13 Oct review: with a gap or a partial day, "yesterday" would be an older day).
     */
    fun scored(sessions: List<Pair<LocalDate, List<Bar5?>>>, isTrading: (LocalDate) -> Boolean = MarketContextCore::weekday): List<Int> {
        val from = maxOf(SWING_DAYS, sessions.size - HISTORY)
        return (from until sessions.size).filter { i ->
            sessions[i - 1].first == MarketContextCore.previousSession(sessions[i].first, isTrading)
        }
    }

    /** "Held N of M" per kind, over the [scored] sessions in [sessions] (oldest first), each from its own past. */
    fun record(sessions: List<Pair<LocalDate, List<Bar5?>>>, isTrading: (LocalDate) -> Boolean = MarketContextCore::weekday): Map<Type, Pair<Int, Int>> {
        val held = HashMap<Type, Int>(); val tested = HashMap<Type, Int>()
        val bars = sessions.map { it.second }
        for (i in scored(sessions, isTrading)) {
            val s = bars[i]
            val open = s.firstOrNull { it != null }?.open ?: continue
            for ((t, lv) in levelsFrom(bars.subList(0, i), open)) {
                val (te, he) = test(s, lv, 0)
                if (te) { tested[t] = (tested[t] ?: 0) + 1; if (he) held[t] = (held[t] ?: 0) + 1 }
            }
        }
        return Type.entries.associateWith { (held[it] ?: 0) to (tested[it] ?: 0) }
    }

    /**
     * Today's map for an index from its 1-minute [bars] (several sessions, today's included or not) on [today], with the
     * largest-OI strikes [maxOiCall] / [maxOiPut] of 09:20 and the VWAP lines ([vwap]: name to price) when known.
     * The previous-day, pivot and swing levels come only when the previous trading day ([isTrading]) is on the phone whole
     * (13 Oct review): otherwise none of them - never an older day's high or low passed off as yesterday's. The OI and VWAP
     * lines are today's own and stay.
     */
    fun map(bars: List<Candle>, today: LocalDate, maxOiCall: Double? = null, maxOiPut: Double? = null,
            vwap: List<Pair<String, Double>> = emptyList(), isTrading: (LocalDate) -> Boolean = MarketContextCore::weekday): List<Level> {
        val out = ArrayList<Level>()
        val sessions = sessionsBefore(bars, today)
        if (sessions.isNotEmpty() && sessions.last().first == MarketContextCore.previousSession(today, isTrading)) {
            val past = sessions.map { it.second }
            val todays = bars.filter { it.t.toLocalDate() == today }.takeIf { it.isNotEmpty() }?.let { bars5(it) }
            val open = todays?.firstOrNull { it != null }?.open ?: past.last().filterNotNull().last().close
            val rec = record(sessions, isTrading)        // earlier sessions only: today's own bars never count
            for ((t, lv) in levelsFrom(past, open)) {
                val (h, n) = rec[t] ?: (0 to 0)
                out += Level(t, lv, h, n)
            }
        }
        maxOiCall?.let { out += Level(Type.MAX_OI_CALL, it) }
        maxOiPut?.let { out += Level(Type.MAX_OI_PUT, it) }
        for ((name, px) in vwap) out += Level(if (name == "VWAP") Type.VWAP else Type.VWAP_BAND, px, name = name)
        return out
    }

    /** How many sessions [map]'s counts read from [bars] (today left out); 0 when [map] has no previous-day levels. */
    fun counted(bars: List<Candle>, today: LocalDate, isTrading: (LocalDate) -> Boolean = MarketContextCore::weekday): Int {
        val s = sessionsBefore(bars, today)
        if (s.isEmpty() || s.last().first != MarketContextCore.previousSession(today, isTrading)) return 0
        return scored(s, isTrading).size
    }

    // ---- the 09:20 OI strikes (13 Oct review: the label quotes the research's 09:20 rate, so the line is the 09:20 OI) ----

    /** 09:20 as an IST minute of the day. */
    const val OI_MINUTE = 9 * 60 + 20

    /**
     * The largest-OI strike of [right] at 09:20 from one chain read today at IST minute [readMinute]: each contract's OI at
     * its last 1-minute bar from 09:16 to 09:20 ([snapshot] false: minute candles; the chain must be read after 09:20's bar
     * closed), or, for a quote snapshot ([snapshot] true: one row per contract), only when the snapshot was taken at 09:21
     * or 09:22 (the state at 09:20's close). Null when the chain does not hold 09:20 (read before it, or a later snapshot):
     * the app keeps the first 09:20 answer of the day and uses it all day.
     */
    fun maxOiStrikeAt0920(series: List<com.optionslab.engine.Series>, right: com.optionslab.engine.Right, readMinute: Int, snapshot: Boolean): Double? {
        if (readMinute <= OI_MINUTE) return null
        var best: Pair<Double, Long>? = null
        for (s in series) {
            if (s.right != right || s.size == 0) continue
            val i = if (snapshot) (s.size - 1).takeIf { s.minutes[it] in OI_MINUTE + 1..OI_MINUTE + 2 } ?: -1
                else s.lastAtOrBefore(OI_MINUTE).takeIf { it >= 0 && s.minutes[it] > OI_MINUTE - 5 } ?: -1
            if (i < 0 || i >= s.oi.size) continue
            val oi = s.oi[i]
            if (oi > 0 && (best == null || oi > best.second)) best = s.strike to oi
        }
        return best?.first
    }

    /** The legend line when the previous session is not on the phone whole: no previous-day, pivot or swing levels. */
    const val NO_HISTORY = "Level map: the previous session is not complete on this phone, so no previous-day, pivot or swing levels today " +
        "(never an older day's passed off as yesterday's)."

    /** The legend line under the chart's level map. */
    fun note(sessions: Int): String =
        "Level map: held N of M on this phone's last $sessions sessions. In 2024-26 no kind of level held more often than a random price " +
            "nearby (about $PLACEBO) - places to watch, not places that hold."
}
