package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * How Jarvis's own pattern calls played out (learning, round 8): each directional pattern he mentions to Boss is noted
 * (market, chart, kind, the candle and its close - nothing else), and the phone's own 1-minute candles then show whether
 * the price went the pattern's way [HORIZONS] minutes after the candle closed. [PatternBook] is the pattern's record on
 * every candle; this is the record of the ones Jarvis actually told Boss about, on this phone, the newest [LAST] of each
 * within [KEEP_DAYS] days, older ones counting less ([HALF_LIFE_DAYS]).
 *
 * What it learns only changes what is said: the record is quoted when the pattern is mentioned, and a kind whose record
 * is clearly poor ([quiet]) is no longer brought up unasked (asked, it is still answered, with its record). Nothing here
 * acts, and nothing is advice: facts, history, not a promise. Pure.
 */
object PatternCalls {
    /** Minutes after the pattern's candle closed that the price is checked. */
    val HORIZONS = listOf(15, 30, 60)
    /** The horizon quoted first and judged by. */
    const val QUOTE = 30
    /** Each kind's record is its newest this many measured calls. */
    const val LAST = 20
    /** Fewer measured calls than this and the record is said as "too few to go by". */
    const val FEW = 5
    /** A record is clearly poor with at least this many calls, going its way this often or less (also when faded). */
    const val QUIET_MIN = 12
    const val QUIET_RATE = 0.3
    const val HALF_LIFE_DAYS = 30.0
    const val KEEP_DAYS = 120L
    const val KEEP = 400
    /** A move smaller than this (%) either way is flat: not its way. */
    const val FLAT_PCT = 0.005
    /** The price at a horizon is the close of a 1-minute candle at most this many minutes before it (data gaps). */
    const val SLACK = 5L

    enum class Went { WAY, AGAINST, FLAT, NONE }

    /** A pattern Jarvis mentioned: [at] is its candle's start, [price] its close; [went] what each horizon showed. */
    data class Call(val market: Market, val minutes: Int, val kind: PatternKind, val at: LocalDateTime, val price: Double,
                    val went: Map<Int, Went> = emptyMap()) {
        val closeAt: LocalDateTime get() = at.plusMinutes(minutes.toLong())
        val key: String get() = "${market.name}|$minutes|${kind.name}|$at"
        val settled: Boolean get() = HORIZONS.all { it in went }
    }

    /** Only patterns that lean one way are followed (a doji or an inside bar says no direction). */
    fun tracked(k: PatternKind): Boolean = k.bias != 0

    /** The call for pattern [p] mentioned on [m] (null: not followed). */
    fun call(m: Market, p: Pattern): Call? =
        if (tracked(p.kind) && p.price.isFinite() && p.price > 0) Call(m, p.minutes, p.kind, p.at, p.price) else null

    /** [log] with the [new] calls added (a pattern mentioned again counts once), old ones dropped. */
    fun add(log: List<Call>, new: List<Call>, today: LocalDate): List<Call> {
        val have = log.map { it.key }.toHashSet()
        return prune(log + new.filter { have.add(it.key) }, today)
    }

    fun prune(log: List<Call>, today: LocalDate): List<Call> =
        log.filter { !it.at.toLocalDate().isBefore(today.minusDays(KEEP_DAYS)) }.sortedBy { it.at }.takeLast(KEEP)

    /** [log] with [m]'s calls measured on [bars] (1-minute, oldest first) where the horizon has passed. */
    fun settle(log: List<Call>, m: Market, bars: List<Candle>): List<Call> {
        if (bars.isEmpty()) return log
        return log.map { c -> if (c.market != m || c.settled) c else measure(c, bars) }
    }

    private fun measure(c: Call, bars: List<Candle>): Call {
        val last = bars.last().t
        val went = HashMap(c.went)
        for (h in HORIZONS) {
            if (h in went) continue
            // The close at the horizon: the 1-minute candle that ends then (or just before it, on a gap).
            val end = c.closeAt.plusMinutes(h.toLong() - 1)
            if (last.isBefore(end)) continue                                // not there yet
            val b = lastAtOrBefore(bars, end)?.takeIf { !it.t.isBefore(end.minusMinutes(SLACK)) && it.t.isAfter(c.at) }
            if (b == null) {
                // The session ended (or the phone has no candles then): never guessed. Waits an hour for late candles.
                if (last.isAfter(end.plusMinutes(60))) went[h] = Went.NONE
                continue
            }
            val move = (b.c - c.price) / c.price * 100 * c.kind.bias
            went[h] = when { move > FLAT_PCT -> Went.WAY; move < -FLAT_PCT -> Went.AGAINST; else -> Went.FLAT }
        }
        return c.copy(went = went)
    }

    private fun lastAtOrBefore(bars: List<Candle>, t: LocalDateTime): Candle? {
        var lo = 0; var hi = bars.size - 1; var found = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (!bars[mid].t.isAfter(t)) { found = mid; lo = mid + 1 } else hi = mid - 1 }
        return if (found < 0) null else bars[found]
    }

    /** One kind's record on one market and chart at horizon [horizon]: the newest [n] measured calls, [way] went its way. */
    data class Record(val market: Market, val minutes: Int, val kind: PatternKind, val horizon: Int, val n: Int, val way: Int,
                      /** Went its way, older calls counting less (0..1). */ val faded: Double) {
        val rate: Double get() = if (n == 0) 0.0 else way.toDouble() / n
    }

    fun record(log: List<Call>, m: Market, minutes: Int, k: PatternKind, today: LocalDate, horizon: Int = QUOTE): Record {
        val from = today.minusDays(KEEP_DAYS)
        val seen = log.filter { it.market == m && it.minutes == minutes && it.kind == k && !it.at.toLocalDate().isBefore(from) &&
            it.went[horizon].let { w -> w != null && w != Went.NONE } }.sortedBy { it.at }.takeLast(LAST)
        var wSum = 0.0; var wWay = 0.0
        for (c in seen) {
            val age = java.time.temporal.ChronoUnit.DAYS.between(c.at.toLocalDate(), today).coerceAtLeast(0)
            val w = Math.pow(0.5, age / HALF_LIFE_DAYS)
            wSum += w; if (c.went[horizon] == Went.WAY) wWay += w
        }
        return Record(m, minutes, k, horizon, seen.size, seen.count { it.went[horizon] == Went.WAY }, if (wSum > 0) wWay / wSum else 0.0)
    }

    /** A kind whose record here is clearly poor: no longer brought up unasked (only speaks less; asked, still answered). */
    fun quiet(log: List<Call>, m: Market, minutes: Int, k: PatternKind, today: LocalDate): Boolean {
        if (!tracked(k)) return false
        val r = record(log, m, minutes, k, today)
        return r.n >= QUIET_MIN && r.rate <= QUIET_RATE && r.faded <= QUIET_RATE
    }

    private fun chart(minutes: Int) = if (minutes == 60) "1-hour" else "$minutes-minute"
    private fun span(h: Int) = if (h == 60) "an hour" else "$h minutes"
    private fun way(k: PatternKind) = if (k.bias > 0) "up" else "down"

    /** The line said with a mentioned pattern: its record on this phone; null when it has none yet. */
    fun line(log: List<Call>, m: Market, p: Pattern, today: LocalDate): String? {
        if (!tracked(p.kind)) return null
        val r = record(log, m, p.minutes, p.kind, today)
        if (r.n == 0) return null
        val head = "When I told you of a ${p.kind.label} on the ${m.label} ${chart(p.minutes)} chart before, the price went its way (${way(p.kind)}) " +
            "within ${span(QUOTE)} ${r.way} of the last ${r.n} time${if (r.n == 1) "" else "s"} on this phone"
        if (r.n < FEW) return "$head - too few to go by."
        val others = HORIZONS.filter { it != QUOTE }.map { record(log, m, p.minutes, p.kind, today, it) }.filter { it.n > 0 }
        return head + (if (others.isEmpty()) "" else " (" + others.joinToString(", ") { "within ${span(it.horizon)}: ${it.way} of ${it.n}" } + ")") +
            (if (quiet(log, m, p.minutes, p.kind, today)) " - a poor record, so I no longer bring it up unless you ask." else ".")
    }

    /** "Which patterns work on Nifty?" / "how good are your pattern calls?": every kind's record on [markets] (empty: all). */
    fun say(log: List<Call>, markets: List<Market>, today: LocalDate): String {
        val mine = log.filter { markets.isEmpty() || it.market in markets }
        val where = if (markets.isEmpty()) "" else " on " + markets.joinToString(" and ") { it.label }
        val recs = mine.map { Triple(it.market, it.minutes, it.kind) }.distinct()
            .map { (m, min, k) -> record(log, m, min, k, today) }.filter { it.n > 0 }
        if (recs.isEmpty()) return "I haven't followed any of my pattern calls$where through yet, Boss. Each time I tell you of a pattern I note it, " +
            "and the phone's candles show whether the price went its way over the next 15, 30 and 60 minutes; ask again once I have."
        val all = recs.sumOf { it.n }; val ways = recs.sumOf { it.way }
        val enough = recs.filter { it.n >= FEW }.sortedWith(compareByDescending<Record> { it.rate }.thenByDescending { it.n })
        val few = recs.filter { it.n < FEW }
        val quietOnes = enough.filter { quiet(log, it.market, it.minutes, it.kind, today) }
        fun one(r: Record) = "${r.kind.label} on ${r.market.label} ${chart(r.minutes)}: ${r.way} of ${r.n}"
        return "My pattern calls$where on this phone, Boss: ${ways} of the last ${all} went their way within ${span(QUOTE)}. " +
            (if (enough.isEmpty()) "" else "By pattern, best first: " + enough.take(6).joinToString("; ") { one(it) } + ". ") +
            (if (few.isEmpty()) "" else "Too few to go by yet: " + few.take(4).joinToString("; ") { one(it) } + ". ") +
            (if (quietOnes.isEmpty()) "" else "I no longer bring up " + quietOnes.joinToString(" or ") { "${it.kind.label} on ${it.market.label} ${chart(it.minutes)}" } +
                " unless you ask - their record is poor. ") +
            "The newest $LAST of each count, older ones less. History, not a promise."
    }

    private val ASKED = Regex("(?i)\\b(which|what) (candle |chart )?patterns? (work|works|worked|are working|do well|did well|hold up|held up|went right|pay off)\\b" +
        "|\\bhow (good|accurate|reliable|right|often right|well) (are|is|were|have been) (your|jarvis s|jarvis's|the) (candle |chart )?pattern (calls?|reads?|spotting|alerts?|mentions?)\\b" +
        "|\\b(your|jarvis s|jarvis's) (candle |chart )?pattern (calls?|record|track record|hit rate|accuracy|score)\\b" +
        "|\\bpattern (calls? )?(hit rate|track record|accuracy)\\b" +
        // Hinglish (routing audit, round 8): "kaun se patterns kaam karte hain", "pattern calls kaise rahe".
        "|\\b(kaun se|kaunse|kon se|konse) (candle |chart )?patterns? (kaam karte|kaam kar rahe|kaam kiye|chalte|chal rahe|sahi (jaate|jate|nikle|rahe))\\b" +
        "|\\b(tumhare |aapke |your )?pattern calls? (kaise|kitne sahi) (rahe|hain|the|nikle)\\b" +
        // Which are trusted (round 9): "which patterns do you trust", "which patterns are reliable", "kaun se patterns pe bharosa hai".
        "|\\b(which|what) (candle |chart )?patterns? (do you|can i|can we|could i) (trust|rely on|go by|believe)\\b" +
        "|\\b(which|what) (candle |chart )?patterns? (are|have been|were) (reliable|trustworthy|dependable|accurate)\\b" +
        "|\\b(kaun se|kaunse|kon se|konse) (candle |chart )?patterns? (pe|par|pr) (bharosa|bharosaa|bharose|yakeen|vishwas|bharosa kar)\\b")

    fun asked(text: String): Boolean = ASKED.containsMatchIn(text.replace(Regex("\\s+"), " "))

    // ---- saved as plain text: one call a line (market, chart, kind, candle, close, then each horizon's result) ----

    fun save(log: List<Call>): String = log.joinToString("\n") { c ->
        listOf(c.market.name, c.minutes, c.kind.name, c.at, "%.4f".format(Locale.ENGLISH, c.price),
            HORIZONS.joinToString(",") { h -> c.went[h]?.name ?: "-" }).joinToString("|")
    }

    fun load(text: String): List<Call> = text.lineSequence().mapNotNull { line ->
        runCatching {
            val p = line.split('|')
            val w = p[5].split(',')
            Call(Market.valueOf(p[0]), p[1].toInt(), PatternKind.valueOf(p[2]), LocalDateTime.parse(p[3]), p[4].toDouble(),
                HORIZONS.indices.mapNotNull { i -> w.getOrNull(i)?.takeIf { it != "-" }?.let { HORIZONS[i] to Went.valueOf(it) } }.toMap())
        }.getOrNull()
    }.toList()
}
