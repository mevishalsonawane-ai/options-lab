package com.optionslab.ira

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * "Explain this move" (Jarvis self-improvement, 2026-10-05): when an index moves sharply within minutes - at least
 * [MIN_PCT] in [WINDOW] minutes and [TIMES] times its usual [WINDOW]-minute move - Jarvis lists what coincided with it:
 * the headlines on the phone published from [NEWS_BEFORE] minutes before the move began to [NEWS_AFTER] after it ended
 * (with their source and how their time sits against the move), India VIX over the same minutes, and the other indices
 * over the same minutes. Also asked: "explain this move", "what coincided with that fall", "what happened at 11:20".
 *
 * Timing facts only, every number from the candles and headlines on the phone: a headline out at the same time is never
 * called the cause (it is said so), never a forecast, never advice. Pure.
 */
object SharpMove {
    /** The minutes a sharp move is measured over. */
    const val WINDOW = 10
    /** A move smaller than this (%) is never sharp, however quiet the usual is. */
    const val MIN_PCT = 0.3
    /** A move at least this many times the usual [WINDOW]-minute move (when the phone knows it) is sharp. */
    const val TIMES = 3.0
    /** Headlines from this many minutes before the move began to [NEWS_AFTER] after it ended. */
    const val NEWS_BEFORE = 10L
    const val NEWS_AFTER = 5L
    /** At most this many headlines are listed. */
    const val MAX_NEWS = 3
    /** An index moving less than this (%) in the same minutes is said to have barely moved. */
    const val FLAT_PCT = 0.1
    /** Earlier [WINDOW]-minute samples needed before "usual" is known. */
    const val MIN_SAMPLES = 20

    /** The indices compared with the one that moved. */
    val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    /** A move of [market] from the close of the candle at [from] to the close of the candle at [to]. */
    data class Move(val market: Market, val from: LocalDateTime, val to: LocalDateTime, val fromPx: Double, val toPx: Double,
                    /** The usual [WINDOW]-minute move (%, median), or null when the phone holds too few sessions. */
                    val usualPct: Double?) {
        val points: Double get() = toPx - fromPx
        val pct: Double get() = points / fromPx * 100
        val minutes: Long get() = Duration.between(from, to).toMinutes()
        val up: Boolean get() = points > 0
    }

    /** What coincided with a move: headlines (with their time in [zone]), VIX's change and the other indices'. */
    data class Context(val news: List<Pair<Headline, LocalDateTime>>, val vix: Pair<Double, Double>?, val others: List<Pair<Market, Double>>)

    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun pctAbs(x: Double) = "%.2f%%".format(Locale.ENGLISH, abs(x))
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun norm(text: String) = " " + text.lowercase().replace(rx("[^a-z0-9: ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    /** The candles of [day] in [bars], in time order. */
    private fun dayOf(bars: List<Candle>, day: java.time.LocalDate) = bars.filter { it.t.toLocalDate() == day }.sortedBy { it.t }

    /**
     * The usual [WINDOW]-minute move (median of |%|) in the sessions of [bars] before [day], sampled every [WINDOW] candles
     * within each session; null with fewer than [MIN_SAMPLES] samples.
     */
    fun usual(bars: List<Candle>, day: java.time.LocalDate): Double? {
        // Only the sessions before [day] count, and they do not change while the day goes on: the watch looks at every
        // pass, so the reading is kept by those candles ([BarsKept]) and read again only when they differ.
        val before = bars.filter { it.t.toLocalDate() < day }
        return if (before.size > KEEP_UP_TO) usualOf(before) else usualKept.of(before, null) { usualOf(before) }
    }

    private const val KEEP_UP_TO = 30_000
    private val usualKept = BarsKept<Double?>(8)

    internal fun usualOf(before: List<Candle>): Double? {
        val moves = ArrayList<Double>()
        before.groupBy { it.t.toLocalDate() }.values.forEach { d ->
            val b = d.sortedBy { it.t }
            var i = WINDOW
            while (i < b.size) { if (b[i - WINDOW].c > 0) moves += abs((b[i].c - b[i - WINDOW].c) / b[i - WINDOW].c * 100); i += WINDOW }
        }
        if (moves.size < MIN_SAMPLES) return null
        moves.sort()
        return moves[moves.size / 2].takeIf { it > 0 }
    }

    /** Is a move of [p] % sharp against [usual]? */
    fun sharp(p: Double, usual: Double?): Boolean = abs(p) >= MIN_PCT && (usual == null || abs(p) >= TIMES * usual)

    /**
     * The latest sharp move of [m] on the last session in [bars] (1-minute candles): of the [WINDOW]-minute stretches that
     * qualify, the run ending latest, and in it the biggest one. Null when there is none.
     */
    fun latest(m: Market, bars: List<Candle>): Move? {
        val last = bars.maxByOrNull { it.t } ?: return null
        val day = dayOf(bars, last.t.toLocalDate())
        if (day.size <= WINDOW) return null
        // No stretch of [MIN_PCT] or more today: none is sharp whatever the usual, so the earlier sessions are not read
        // (the watch looks at every pass, and most passes find a quiet market).
        if ((WINDOW until day.size).none { i -> day[i - WINDOW].c > 0 && abs((day[i].c - day[i - WINDOW].c) / day[i - WINDOW].c * 100) >= MIN_PCT }) return null
        val u = usual(bars, last.t.toLocalDate())
        fun at(i: Int) = Move(m, day[i - WINDOW].t, day[i].t, day[i - WINDOW].c, day[i].c, u)
        var end = -1
        for (i in day.size - 1 downTo WINDOW) if (day[i - WINDOW].c > 0 && sharp(at(i).pct, u)) { end = i; break }
        if (end < 0) return null
        val up = at(end).up
        var best = at(end)
        var i = end - 1
        while (i >= WINDOW) {
            val mv = at(i)
            if (!sharp(mv.pct, u) || mv.up != up) break
            if (abs(mv.pct) > abs(best.pct)) best = mv
            i--
        }
        return best
    }

    /** The move of [m] over the minutes around [time] on the last session in [bars] (from 5 before to 5 after); null without candles there. */
    fun around(m: Market, bars: List<Candle>, time: LocalTime): Move? {
        val last = bars.maxByOrNull { it.t } ?: return null
        val day = dayOf(bars, last.t.toLocalDate())
        val at = last.t.toLocalDate().atTime(time)
        val inside = day.filter { !it.t.isBefore(at.minusMinutes(WINDOW / 2L)) && !it.t.isAfter(at.plusMinutes(WINDOW / 2L)) }
        if (inside.size < 2 || inside.first().c <= 0) return null
        return Move(m, inside.first().t, inside.last().t, inside.first().c, inside.last().c, usual(bars, last.t.toLocalDate()))
    }

    /** The close of the last candle at or before [t] on [t]'s day in [bars], or null. */
    private fun closeAt(bars: List<Candle>, t: LocalDateTime): Double? =
        bars.filter { it.t.toLocalDate() == t.toLocalDate() && !it.t.isAfter(t) }.maxByOrNull { it.t }?.c

    /** The change (%) of [bars] over [mv]'s minutes, or null when the candles do not cover them. */
    private fun over(bars: List<Candle>, mv: Move): Pair<Double, Double>? {
        val a = closeAt(bars, mv.from) ?: return null
        val b = closeAt(bars, mv.to) ?: return null
        if (a <= 0) return null
        return a to b
    }

    /** What coincided with [mv]: from [news], India VIX's candles and the other indices' candles in [bars]. */
    fun context(mv: Move, bars: Map<Market, List<Candle>>, news: List<Headline>, zone: ZoneId): Context {
        val hs = news.mapNotNull { h -> h.at?.let { h to LocalDateTime.ofInstant(it, zone) } }
            .filter { (h, t) -> (h.markets.isEmpty() || h.markets.any { it != Market.GOLD }) &&
                !t.isBefore(mv.from.minusMinutes(NEWS_BEFORE)) && !t.isAfter(mv.to.plusMinutes(NEWS_AFTER)) }
            .sortedBy { it.second }.distinctBy { it.first.title.lowercase() }.take(MAX_NEWS)
        val vix = bars[Market.VIX]?.let { over(it, mv) }
        val others = INDICES.filter { it != mv.market }.mapNotNull { m -> bars[m]?.let { over(it, mv) }?.let { (a, b) -> m to (b - a) / a * 100 } }
        return Context(hs, vix, others)
    }

    /** Where a headline's time sits against the move. */
    private fun whenWord(t: LocalDateTime, mv: Move): String {
        fun mins(a: LocalDateTime, b: LocalDateTime) = Duration.between(a, b).toMinutes().let { "$it minute${if (it == 1L) "" else "s"}" }
        return when {
            t.isBefore(mv.from) -> "${mins(t, mv.from)} before it began"
            t.isAfter(mv.to) -> "${mins(mv.to, t)} after it"
            else -> "during it"
        }
    }

    /** The move in one sentence. [lead]: how it is introduced ("Nifty fell ..." or "Around 11:20, Nifty fell ..."). */
    private fun head(mv: Move, lead: String = ""): String {
        val way = if (abs(mv.pct) < 0.02) "was flat" else if (mv.up) "rose ${n(abs(mv.points))} points" else "fell ${n(abs(mv.points))} points"
        val usual = mv.usualPct?.takeIf { it > 0 && abs(mv.pct) >= 2 * it }
            ?.let { " - about ${"%.0f".format(Locale.ENGLISH, abs(mv.pct) / it)} times its usual $WINDOW-minute move of ${pctAbs(it)}" } ?: ""
        return "$lead${mv.market.label} $way (${pct(mv.pct)}) in ${mv.minutes} minutes, from ${n(mv.fromPx)} at ${hm(mv.from)} to ${n(mv.toPx)} at ${hm(mv.to)}$usual."
    }

    /** The full account for the chat: the move, then what coincided, then that timing is not a cause. */
    fun say(mv: Move, c: Context, lead: String = "", expiry: Boolean = false): String {
        val parts = ArrayList<String>()
        parts += head(mv, lead)
        val same = ArrayList<String>()
        c.vix?.let { (a, b) -> same += "India VIX went from ${n(a)} to ${n(b)} (${pct((b - a) / a * 100)})" }
        if (c.others.isNotEmpty()) {
            val moved = c.others.filter { abs(it.second) >= FLAT_PCT }
            val with = moved.count { (it.second > 0) == mv.up }
            val list = c.others.joinToString(", ") { (m, p) -> "${m.label} ${pct(p)}" }
            same += when {
                moved.isEmpty() -> "the other indices barely moved ($list)"
                with == c.others.size -> "every other index moved the same way ($list)"
                with == 0 -> "the other indices that moved went the other way ($list)"
                else -> "$with of ${c.others.size} other indices moved the same way ($list)"
            }
        }
        if (same.isNotEmpty()) parts += "In the same minutes, " + same.joinToString("; ") + "."
        parts += if (c.news.isEmpty()) "No headline on the phone was published between ${hm(mv.from.minusMinutes(NEWS_BEFORE))} and ${hm(mv.to.plusMinutes(NEWS_AFTER))}."
            else "Headlines published around then: " + c.news.joinToString("; ") { (h, t) -> "${hm(t)} \"${h.title}\" (${h.source}, ${whenWord(t, mv)})" } + "."
        if (expiry) parts += "It is ${mv.market.label}'s expiry day."
        parts += "That is what coincided, Boss - timing only: none of it says why the market moved."
        return parts.joinToString(" ")
    }

    /** The few words said aloud when Jarvis tells it unasked (the rest is in the chat). */
    fun spoken(mv: Move, c: Context): String {
        val bits = ArrayList<String>()
        c.vix?.let { (a, b) -> bits += "India VIX ${if (b >= a) "up" else "down"} ${pctAbs((b - a) / a * 100)}" }
        val with = c.others.count { abs(it.second) >= FLAT_PCT && (it.second > 0) == mv.up }
        if (c.others.isNotEmpty()) bits += if (with == c.others.size) "the other indices moved with it" else if (with == 0) "the other indices did not follow" else "$with of ${c.others.size} other indices moved with it"
        bits += if (c.news.isEmpty()) "no headline around then" else "${c.news.size} headline${if (c.news.size > 1) "s" else ""} around then"
        return "Boss, ${mv.market.label} ${if (mv.up) "rose" else "fell"} ${pctAbs(mv.pct)} in ${mv.minutes} minutes. In the same minutes: " +
            bits.joinToString(", ") + ". Timing only, not a cause; the details are in the chat."
    }

    /** What was asked: the latest sharp move ([at] null), or the minutes around a time of day. */
    data class Ask(val at: LocalTime?)

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |tell me |please )*"
    private const val MOVE = "(move|moves|fall|drop|dip|crash|spike|jump|rally|rise|surge|selloff|sell off|slide|swing)"
    private val ASK = Regex("^ $LEAD(" +
        "explain (this|that|the|today s|the last|the latest|the recent) (sudden |sharp |big |quick )?$MOVE|" +
        "(what|anything) (coincided|happened at the same time|came out at the same time|was there) (with )?(this|that|the) (sudden |sharp |big |quick )?$MOVE|" +
        "what coincided( with it)?|" +
        "what (was|is) (going on|happening) (when|as) [a-z ]{0,20}(fell|dropped|dipped|crashed|spiked|jumped|rallied|rose|surged|moved)( so)?( suddenly| sharply| fast| just now)?|" +
        "why did [a-z ]{0,20}(suddenly|just) (fall|drop|dip|crash|spike|jump|rally|rise|surge|move)|" +
        "why (is|s) [a-z ]{0,20}(suddenly) (falling|dropping|rising|jumping|moving|spiking)|" +
        "(any|was there any) (news|headline) (with|behind|around|for) (this|that|the) (sudden |sharp |big |quick )?$MOVE" +
        ")( now| just now| today)?( boss| jarvis)? $")
    private val AT = Regex("^ $LEAD(what happened|what was happening|what went on) (in the market |to [a-z ]{0,20})?(at|around) (\\d{1,2})(?::(\\d{2}))? ?(am|pm)?( today)?( boss| jarvis)? $")

    /** "Explain this move" and its kin, or "what happened at 11:20"; null for anything else. */
    fun asked(text: String): Ask? {
        val t = norm(text)
        AT.find(t)?.let { m ->
            var h = m.groupValues[5].toInt(); val mi = m.groupValues[6].toIntOrNull() ?: 0
            val ap = m.groupValues[7]
            if (ap == "pm" && h < 12) h += 12
            if (ap.isEmpty() && h in 1..3) h += 12                     // "at 2:10" in a trading day is 2 pm
            return if (h in 0..23 && mi in 0..59) Ask(LocalTime.of(h, mi)) else null
        }
        return if (ASK.containsMatchIn(t)) Ask(null) else null
    }

    /**
     * The answer to [a] for [m]: the minutes around the time asked, else the latest sharp move today, else the session's
     * biggest [WINDOW]-minute move said as such. Null when the phone has no candles for it.
     */
    fun answer(a: Ask, m: Market, bars: Map<Market, List<Candle>>, news: List<Headline>, zone: ZoneId, expiry: Boolean = false): String? {
        val b = bars[m] ?: return null
        if (a.at != null) {
            val mv = around(m, b, a.at) ?: return null
            return say(mv, context(mv, bars, news, zone), "Around ${"%02d:%02d".format(Locale.ENGLISH, a.at.hour, a.at.minute)}, ", expiry)
        }
        latest(m, b)?.let { mv -> return say(mv, context(mv, bars, news, zone), "The latest sharp move: ", expiry) }
        val big = biggest(m, b) ?: return null
        return "No sharp move today, Boss (none of ${pctAbs(MIN_PCT)} or more in $WINDOW minutes" +
            (big.usualPct?.let { u -> " and ${"%.0f".format(Locale.ENGLISH, TIMES)} times the usual ${pctAbs(u)}" } ?: "") + "). " +
            say(big, context(big, bars, news, zone), "The day's biggest $WINDOW-minute move: ", expiry)
    }

    /** The biggest [WINDOW]-minute move of the last session in [bars], or null. */
    fun biggest(m: Market, bars: List<Candle>): Move? {
        val last = bars.maxByOrNull { it.t } ?: return null
        val day = dayOf(bars, last.t.toLocalDate())
        if (day.size <= WINDOW) return null
        val u = usual(bars, last.t.toLocalDate())
        return (WINDOW until day.size).filter { day[it - WINDOW].c > 0 }
            .map { Move(m, day[it - WINDOW].t, day[it].t, day[it - WINDOW].c, day[it].c, u) }.maxByOrNull { abs(it.pct) }
    }

    /**
     * For the watcher: [mv] is worth telling unasked at [now] when it ended within [freshMinutes] of now (an old move
     * found after a restart is not news) and after the move last told for the market ([lastTold], its end) plus
     * [gapMinutes] (one move, told once, not again at each look).
     */
    fun worthTelling(mv: Move, now: LocalDateTime, lastTold: LocalDateTime?, freshMinutes: Long = 20, gapMinutes: Long = 30): Boolean =
        !mv.to.isBefore(now.minusMinutes(freshMinutes)) && (lastTold == null || mv.to.isAfter(lastTold.plusMinutes(gapMinutes)))
}
