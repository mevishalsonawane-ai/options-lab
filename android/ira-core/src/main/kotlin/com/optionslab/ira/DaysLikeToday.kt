package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * "Days like today" for the morning brief (TODO I11, 13 Oct 2026; research/DAYS_LIKE_TODAY.md): at 09:20, the nearest past
 * sessions by five simple things - the opening gap, India VIX, the previous day's range, expiry day and event day - with
 * what happened after 09:20 and how each arm did on them. The research found such neighbours do NOT forecast the direction
 * (worse than all past days in 4 of 4 checks), so every brief says "context, not a forecast" and its sample size.
 *
 * Past days come from the small table shipped with the app (research/context/results/days_<UND>.csv: the research-data
 * release, 2024-26, with the research replay's ORB / ORB Fresh net after costs) and from the phone's own 1-minute candles
 * for later sessions ([fromCandles], with the paper account's results per arm). Only days before today are read. Pure.
 */
object DaysLikeToday {
    /** One session: its 09:20 features and what followed. [arms]: net per arm that day; [armsFrom]: whose numbers they are. */
    data class Row(
        val day: LocalDate, val gapPct: Double, val vix: Double, val prevRangePct: Double, val expiry: Boolean, val event: Boolean,
        val restRangePct: Double? = null, val restRetPct: Double? = null,
        val arms: Map<String, Double> = emptyMap(), val armsFrom: String = "",
    )

    const val K = 10
    const val MIN_PAST = 40
    val READY_AT: LocalTime = LocalTime.of(9, 21)
    const val LABEL = "context, not a forecast"
    const val RESEARCH = "research replay after costs"

    // ---- the shipped table --------------------------------------------------------------------------------------------

    /** research/context/results/days_<UND>.csv: day,gap_pct,vix_0920,prev_range_pct,is_expiry,event,rest_range_pct,rest_ret_pct,range_pct,cc_pct[,net_ORB,net_ORB_Fresh]. */
    fun parseCsv(text: String): List<Row> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val head = lines.first().split(',').map { it.trim() }
        fun ix(n: String) = head.indexOf(n)
        val armCols = head.withIndex().filter { it.value.startsWith("net_") }
        return lines.drop(1).mapNotNull { l ->
            val f = l.split(',')
            fun d(n: String) = ix(n).takeIf { it >= 0 && it < f.size }?.let { f[it].trim().toDoubleOrNull() }
            runCatching {
                Row(LocalDate.parse(f[0].trim()), d("gap_pct")!!, d("vix_0920")!!, d("prev_range_pct")!!, (d("is_expiry") ?: 0.0) > 0.5,
                    (d("event") ?: 0.0) > 0.5, d("rest_range_pct"), d("rest_ret_pct"),
                    armCols.mapNotNull { c -> f.getOrNull(c.index)?.trim()?.toDoubleOrNull()?.let { c.value.removePrefix("net_").replace('_', ' ') to it } }.toMap(),
                    if (armCols.isEmpty()) "" else RESEARCH)
            }.getOrNull()
        }
    }

    // ---- the phone's own sessions ---------------------------------------------------------------------------------------

    private val OPEN = LocalTime.of(9, 15)
    private val AT = LocalTime.of(9, 20)

    /**
     * Rows from the phone's 1-minute [bars] (several sessions) and India VIX [vix]: one per session with a previous whole
     * session; [today]'s row (if on the phone from 09:15 and past 09:20) has no outcome. [expiries], [events]: the days that
     * were expiry / event days. [arms]: the paper account's net per arm by day.
     */
    fun fromCandles(bars: List<Candle>, vix: List<Candle>, expiries: Set<LocalDate>, events: Set<LocalDate>,
                    today: LocalDate, arms: Map<LocalDate, Map<String, Double>> = emptyMap()): List<Row> {
        val days = bars.filter { !it.t.toLocalTime().isBefore(OPEN) && it.t.toLocalTime().isBefore(LocalTime.of(15, 30)) }
            .groupBy { it.t.toLocalDate() }.toSortedMap()
        val vx = vix.groupBy { it.t.toLocalDate() }
        val out = ArrayList<Row>()
        var prev: List<Candle>? = null
        for ((d, b0) in days) {
            val b = b0.sortedBy { it.t }
            val p = prev
            prev = b
            if (p == null || b.first().t.toLocalTime().isAfter(OPEN.plusMinutes(2))) continue
            val at = b.lastOrNull { !it.t.toLocalTime().isAfter(AT) } ?: continue
            if (at.t.toLocalTime().isBefore(AT)) continue      // 09:20's bar not on the phone yet
            val pc = p.last().c
            if (pc <= 0) continue
            val v = vx[d]?.lastOrNull { !it.t.toLocalTime().isAfter(AT) }?.c ?: vx.filterKeys { it < d }.maxByOrNull { it.key }?.value?.lastOrNull()?.c ?: continue
            val rest = b.filter { it.t.toLocalTime().isAfter(AT) }
            val whole = !d.isEqual(today) && b.last().t.toLocalTime() >= LocalTime.of(15, 25)
            out += Row(d, (b.first().o - pc) / pc * 100, v, (p.maxOf { it.h } - p.minOf { it.l }) / pc * 100, d in expiries, d in events,
                if (whole && rest.isNotEmpty()) (rest.maxOf { it.h } - rest.minOf { it.l }) / at.c * 100 else null,
                if (whole) (b.last().c - at.c) / at.c * 100 else null, arms[d].orEmpty(), if (arms[d] != null) "your paper account" else "")
        }
        return out
    }

    /** The shipped rows with the phone's rows after them (the phone wins on a day both hold, for its own arm results). */
    fun merge(shipped: List<Row>, phone: List<Row>): List<Row> {
        val byDay = shipped.associateBy { it.day }.toMutableMap()
        for (r in phone) byDay[r.day] = byDay[r.day]?.let { s -> r.copy(arms = r.arms.ifEmpty { s.arms }, armsFrom = if (r.arms.isEmpty()) s.armsFrom else r.armsFrom) } ?: r
        return byDay.values.sortedBy { it.day }
    }

    // ---- the neighbours ------------------------------------------------------------------------------------------------

    private fun sd(xs: List<Double>): Double {
        if (xs.size < 2) return 1.0
        val m = xs.average()
        val s = sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
        return if (s > 0) s else 1.0
    }

    /** The [k] nearest earlier sessions with an outcome (standardised gap, VIX, previous range; expiry and event as 0/1). */
    fun nearest(rows: List<Row>, t: Row, k: Int = K): List<Pair<Row, Double>> {
        val past = rows.filter { it.day < t.day && it.restRetPct != null }
        val sg = sd(past.map { it.gapPct }); val sv = sd(past.map { it.vix }); val sr = sd(past.map { it.prevRangePct })
        fun b(x: Boolean) = if (x) 1.0 else 0.0
        return past.map { p ->
            val dist = sqrt(((p.gapPct - t.gapPct) / sg).let { it * it } + ((p.vix - t.vix) / sv).let { it * it } +
                ((p.prevRangePct - t.prevRangePct) / sr).let { it * it } + (b(p.expiry) - b(t.expiry)).let { it * it } +
                (b(p.event) - b(t.event)).let { it * it })
            p to dist
        }.sortedWith(compareBy({ it.second }, { it.first.day })).take(k)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun sp(x: Double) = String.format(Locale.ENGLISH, "%+.2f%%", x)
    private fun p(x: Double) = String.format(Locale.ENGLISH, "%.2f%%", x)
    private fun rs(x: Double) = (if (x < 0) "−Rs " else "Rs ") + String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.year}"

    /** The brief for [index]: today's row [t] against [rows]; null when fewer than [MIN_PAST] past sessions. */
    fun brief(index: String, t: Row, rows: List<Row>): List<String>? {
        val pastN = rows.count { it.day < t.day && it.restRetPct != null }
        if (pastN < MIN_PAST) return null
        val nb = nearest(rows, t)
        val hit = nb.map { it.first }
        val ret = hit.mapNotNull { it.restRetPct }
        val rng = hit.mapNotNull { it.restRangePct }
        val out = ArrayList<String>()
        out += "Days like today on $index ($LABEL): the $K nearest of $pastN past sessions by the gap (${sp(t.gapPct)} today), India VIX " +
            "(${String.format(Locale.ENGLISH, "%.1f", t.vix)}), the previous day's range (${p(t.prevRangePct)})" +
            (if (t.expiry) ", expiry day" else "") + (if (t.event) ", event day" else "") + "."
        if (ret.isNotEmpty()) out += "After 09:20 those days moved a median ${sp(median(ret))} (from ${sp(ret.min())} to ${sp(ret.max())}); " +
            "${ret.count { it > 0 }} of ${ret.size} closed above their 09:20 price; their range after 09:20 was a median ${p(median(rng))}."
        val armNames = hit.flatMap { it.arms.keys }.distinct().sorted()
        for (a in armNames) {
            val with = hit.filter { a in it.arms }
            val traded = with.count { (it.arms[a] ?: 0.0) != 0.0 }
            val from = with.map { it.armsFrom }.filter { it.isNotEmpty() }.distinct().joinToString(" and ")
            out += "$a on those days: ${rs(with.sumOf { it.arms[a] ?: 0.0 })} in all ($traded of ${with.size} days with a trade; $from)."
        }
        out += "Nearest: " + hit.take(3).joinToString("; ") { "${date(it.day)} (gap ${sp(it.gapPct)}, VIX ${String.format(Locale.ENGLISH, "%.1f", it.vix)}" +
            (it.restRetPct?.let { r -> ", then ${sp(r)}" } ?: "") + ")" } + "."
        out += "The research found such matches did not forecast the direction (research/DAYS_LIKE_TODAY.md)."
        return out
    }
}
