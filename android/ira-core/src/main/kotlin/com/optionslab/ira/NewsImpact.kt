package com.optionslab.ira

import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * The market recorder's news timeline, after the fact (Boss's "Market data" viewer, 06 Oct 2026): each recorded headline
 * ("N" record, [MarketRecord.news]) at the time the app FIRST SAW it, with the index's level then and its move
 * [HORIZONS] minutes later on the recorded minute levels ("S" records), and whether that move went the headline's tone's
 * way. Only what was recorded that day: a move is measured only where a level was recorded at (or just before) both
 * ends ([STALE_MIN]); nothing is filled in or carried past the day's last level.
 *
 * Timing facts, never a cause: a headline before a move does not say it moved the market (it may even report the move).
 * Information only; nothing here acts. Pure: the lines come from the app's own day file.
 */
object NewsImpact {
    /** Minutes after first seen that the move is measured over. */
    val HORIZONS = listOf(5, 15, 30)
    /** The horizon the lessons use. */
    const val LESSON_HORIZON = 30
    /** The indices the timeline measures. */
    val INDICES = listOf("NIFTY", "BANKNIFTY")
    /** A level recorded more than this many minutes before the moment does not stand for it (a gap in the recording). */
    const val STALE_MIN = 3L

    /** A recorded headline: when the app first had it, where from, its tone and the markets it was tagged with. */
    data class Seen(
        val firstSeen: LocalTime, val source: String, val tone: Double?, val toneWord: String, val markets: List<String>,
        val matters: Boolean, val title: String, val link: String,
    ) {
        /** +1 positive, -1 negative, 0 neutral or unknown (the record's own word, as the app scored it then). */
        val direction: Int get() = when (toneWord.lowercase(Locale.ENGLISH)) { "positive" -> 1; "negative" -> -1; else -> 0 }
    }

    /** One N record back, or null for anything else (or a broken one). */
    fun headline(line: String): Seen? {
        if (!line.startsWith("N,")) return null
        val f = MarketRecord.fields(line)
        if (f.size < 11) return null
        val t = runCatching { LocalTime.parse(f[1]) }.getOrNull() ?: return null
        return Seen(t, f[2], f[4].toDoubleOrNull(), f[5], f[6].split(';').filter { it.isNotBlank() }, f[7] == "1", f[9], f[10])
    }

    /** A recorded level at a moment. */
    data class Level(val t: LocalTime, val value: Double)

    /** The level standing at [t]: the last recorded at or before it, no older than [STALE_MIN] minutes; else null. */
    fun levelAt(series: List<Level>, t: LocalTime): Level? {
        var best: Level? = null
        for (l in series) { if (l.t.isAfter(t)) break; best = l }
        return best?.takeIf { !it.t.isBefore(t.minusMinutes(STALE_MIN)) && !t.isBefore(it.t) }
    }

    /** A move [minutes] after: points and per cent from the level at first seen. */
    data class Move(val minutes: Int, val points: Double, val pct: Double)

    /** One index after a headline: its level at first seen and the moves at [HORIZONS] that the recording has. */
    data class After(val index: String, val base: Double, val moves: List<Move>) {
        fun at(minutes: Int): Move? = moves.firstOrNull { it.minutes == minutes }
    }

    /**
     * [series] (sorted by time) after [t]: null without a level at [t]; a horizon past midnight, past the day's last
     * level or over a gap in the recording is left out.
     */
    fun after(index: String, series: List<Level>, t: LocalTime): After? {
        val base = levelAt(series, t) ?: return null
        if (base.value <= 0) return null
        val last = series.lastOrNull()?.t ?: return null
        val moves = HORIZONS.mapNotNull { h ->
            val at = t.plusMinutes(h.toLong())
            if (at.isBefore(t) || at.isAfter(last)) return@mapNotNull null       // past midnight, or past the recording
            val l = levelAt(series, at) ?: return@mapNotNull null
            val pts = l.value - base.value
            Move(h, pts, pts / base.value * 100)
        }
        return After(index, base.value, moves)
    }

    /** Did the move go the tone's way? */
    enum class Agreement(val label: String) { AGREED("agreed"), AGAINST("against"), FLAT("flat"), NO_TONE("neutral tone") }

    /** [direction] (+1 / -1 / 0) against a move of [points]: no tone, no move, the same way or the other. */
    fun agreement(direction: Int, points: Double): Agreement = when {
        direction == 0 -> Agreement.NO_TONE
        points == 0.0 -> Agreement.FLAT
        (points > 0) == (direction > 0) -> Agreement.AGREED
        else -> Agreement.AGAINST
    }

    /** A headline with what each index did after it. */
    data class Impact(val seen: Seen, val after: Map<String, After>) {
        /** The agreement on [index] at [minutes], or null where the move is not recorded. */
        fun agreement(index: String, minutes: Int): Agreement? = after[index]?.at(minutes)?.let { agreement(seen.direction, it.points) }
    }

    /** The day's headlines in first-seen order, each with the [INDICES]' moves after it. */
    fun impacts(news: List<Seen>, levels: Map<String, List<Level>>): List<Impact> =
        news.sortedBy { it.firstSeen }.map { s ->
            Impact(s, INDICES.mapNotNull { ix -> levels[ix]?.let { after(ix, it, s.firstSeen) }?.let { ix to it } }.toMap())
        }

    /** "+12.5 (+0.05%)". */
    fun moveText(m: Move): String {
        val sign = if (m.points > 0) "+" else ""
        return sign + MarketRecord.num(m.points, 1) + " (" + sign + String.format(Locale.ENGLISH, "%.2f", m.pct) + "%)"
    }

    /** What the timeline says under its heading. */
    const val CAVEAT = "After the fact: the index's recorded move after the app first saw each headline. A headline before a move " +
        "does not say it moved the market; it may even report the move."

    // ---- the tally (the lessons') -------------------------------------------------------------------------------------

    /** How often a toned headline's [LESSON_HORIZON]-minute NIFTY move went its way: [agreed] of [n] (flat moves count in n). */
    data class Tally(val agreed: Int, val n: Int) {
        val rate: Double? get() = if (n == 0) null else agreed.toDouble() / n
        operator fun plus(o: Tally) = Tally(agreed + o.agreed, n + o.n)
    }

    fun tally(impacts: List<Impact>, index: String = "NIFTY", minutes: Int = LESSON_HORIZON): Tally {
        var agreed = 0; var n = 0
        for (i in impacts) {
            val a = i.agreement(index, minutes) ?: continue
            if (a == Agreement.NO_TONE) continue
            n++
            if (a == Agreement.AGREED) agreed++
        }
        return Tally(agreed, n)
    }
}
