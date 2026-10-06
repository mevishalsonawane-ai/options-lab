package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * A Liquidity 15+5 level on the chart made actionable: which drawn level a tap meant, the small sheet's words about it,
 * and the price alarm it may set through the app's own alarms (the same store, the same minute check, the same
 * notification). An alarm only notifies: nothing here places, closes or arms an order. Pure: no clock, no store, no drawing.
 */
object LevelAlarm {
    /** Every level alarm's note starts so (the sheet lists the symbol's alarms with it). */
    const val PREFIX = "Liquidity level "
    /** The alarm page's own limit on a note. */
    const val NOTE_MAX = 80
    /** Two levels closer than this (points) are the same price. */
    const val SAME = 0.005

    /**
     * One drawn level: [price] its edge, [side] +1 a swing-high (above the price, buy-side liquidity) / -1 a swing-low,
     * [pool] a liquidity pool (a dashed line) or else a swing zone (a band), drawn over window bars [from]..[to];
     * [taken] a close went through it (at bar [to]).
     */
    data class Level(val price: Double, val side: Int, val pool: Boolean, val from: Int, val to: Int, val taken: Boolean)

    /** An alarm already standing, as the store keeps it. */
    data class Standing(val id: Long, val symbol: String, val above: Boolean, val level: Double, val note: String)

    /** The price at [y] px down a plot [height] px high showing [lo]..[hi] (top = [hi]). */
    fun priceAt(y: Double, lo: Double, hi: Double, height: Double): Double =
        if (height <= 0) hi else hi - (y / height) * (hi - lo)

    /** [px] pixels as points on a plot [height] px high showing [lo]..[hi]. */
    fun tolerance(px: Double, lo: Double, hi: Double, height: Double): Double =
        if (height <= 0) 0.0 else abs(px * (hi - lo) / height)

    /**
     * The index in [levels] of the level a tap at [price] meant: within [tolerance] points, drawn over window bar [bar]
     * (give or take [slack] bars; null: anywhere, e.g. the label column), the nearest; at the same distance a level still
     * in play before a taken one. [activeOnly]: taken levels are not candidates. Null: none near enough.
     */
    fun hit(levels: List<Level>, price: Double, tolerance: Double, bar: Int? = null, slack: Int = 1, activeOnly: Boolean = false): Int? =
        levels.indices.filter { i ->
            val l = levels[i]
            abs(l.price - price) <= tolerance && (!activeOnly || !l.taken) && (bar == null || bar in (l.from - slack)..(l.to + slack))
        }.minWithOrNull(compareBy<Int>({ abs(levels[it].price - price) }, { if (levels[it].taken) 1 else 0 }))

    /** The alarm's direction: true (rises to it) when the level is at or above the last price, as the chart's own alert. */
    fun above(last: Double, level: Double): Boolean = level >= last

    /** "swing-high pool", "swing-low zone". */
    fun kind(side: Int, pool: Boolean): String = "swing-${if (side > 0) "high" else "low"} ${if (pool) "pool" else "zone"}"

    /** A price as the sheet says it: thousands grouped, no ".00" ("54,180", "54,180.35"). */
    fun price(x: Double): String = String.format(Locale.ENGLISH, "%,.2f", x).removeSuffix(".00")

    /** The alarm's note: "Liquidity level 54,180 (swing-high pool, 15-min)", within [NOTE_MAX]. */
    fun note(l: Level, minutes: Int): String = "$PREFIX${price(l.price)} (${kind(l.side, l.pool)}, $minutes-min)".take(NOTE_MAX)

    /** How far the level is from [last], in points. */
    fun distance(level: Double, last: Double): String {
        val d = level - last
        return if (abs(d) < SAME) "At the last price ${price(last)}"
        else String.format(Locale.ENGLISH, "%,.2f pts %s the last price %s", abs(d), if (d > 0) "above" else "below", price(last))
    }

    private fun hhmm(t: LocalDateTime) = String.format(Locale.ENGLISH, "%02d:%02d", t.hour, t.minute)
    private fun dayMonth(d: LocalDate) = "${d.dayOfMonth} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"

    /** Taken today or not: [takenAt] the close that went through it (null when not taken), [today] the session's day. */
    fun takenLine(l: Level, takenAt: LocalDateTime?, today: LocalDate): String = when {
        !l.taken -> "Not taken today: still in play"
        takenAt == null -> "Taken: a close went through it"
        takenAt.toLocalDate() == today -> "Taken today at ${hhmm(takenAt)}"
        else -> "Taken on ${dayMonth(takenAt.toLocalDate())}, not today"
    }

    /** The sheet: the level, its kind and chart, its distance from [last] (null: not known yet), taken or not, and when an alarm would fire. */
    fun sheet(l: Level, minutes: Int, last: Double?, takenAt: LocalDateTime?, today: LocalDate): List<String> = listOfNotNull(
        "Level ${price(l.price)}",
        "${kind(l.side, l.pool).replaceFirstChar { it.uppercase() }} · $minutes-min chart",
        last?.let { distance(l.price, it) } ?: "The last price is not known yet",
        takenLine(l, takenAt, today),
        last?.let { "An alert fires when the price ${if (above(it, l.price)) "rises" else "falls"} to it" },
    )

    /** An alarm on [symbol] at [level] in that direction already stands (the same symbol, price and direction). */
    fun duplicate(standing: List<Standing>, symbol: String, level: Double, above: Boolean): Boolean =
        standing.any { it.symbol == symbol && it.above == above && abs(it.level - level) < SAME }

    /** The alarms set on [symbol]'s liquidity levels (newest first). */
    fun onLevels(standing: List<Standing>, symbol: String): List<Standing> =
        standing.filter { it.symbol == symbol && it.note.startsWith(PREFIX) }.sortedByDescending { it.id }

    /** A standing level alarm's line: "54,180 · rises to it · swing-high pool, 15-min". */
    fun line(s: Standing): String {
        val what = s.note.substringAfter("(", "").substringBefore(")")
        return "${price(s.level)} · ${if (s.above) "rises to it" else "falls to it"}" + (if (what.isNotBlank()) " · $what" else "")
    }
}
