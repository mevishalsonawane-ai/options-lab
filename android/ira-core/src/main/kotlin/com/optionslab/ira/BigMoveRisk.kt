package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The big-move risk read (Boss, 06 Oct 2026): "is a big move likely now?", "abhi kitna risk hai", "volatile hai kya" for
 * Nifty, BankNifty, Sensex and FinNifty. A scorer built on the real-data study of big 5-minute candles (Dhan 1-minute
 * data, Oct 2021 - Oct 2026, six indices, no look-ahead; the study's REPORT): a big candle is a 5-minute move of
 * [BIG_X] times or more its usual size (its mean over the last [USUAL_DAYS] sessions) - about 2% of all 5-minute candles.
 *
 * What raised the chance of one, held up on unseen data, and what this read multiplies:
 *  - the time: the opening 09:15 candle alone held ~20% of big candles and 09:15-09:30 ~30% ([OPEN_X], [EARLY_X]);
 *    15:00-15:05 about 4x the usual rate ([THREE_PM_X]); the rest of the day is a little calmer than average ([REST_X]);
 *  - right after a big candle the next one is big 6-8x more often ([AFTER_X]; within the last three, [RECENT_X]);
 *  - volatility begets volatility: the last 15/30/60 minutes' realised volatility, or today's range so far against a
 *    usual day's, in the top 20% for this time of day (1.7-2.5x: [HOT_X], both kinds [HOTTER_X]); a wide range yesterday
 *    ([WIDE_YESTERDAY_X]);
 *  - falling markets are jumpier: below the day's average price (TWAP), far below the day's high, falling over the last
 *    30-60 minutes, India VIX (or the ATM IV) up since the open (1.5-2x: [FALLING_X], two or more [FALLING_MORE_X]);
 *  - the quietest 20% of moments: about 3x less ([QUIET_X]).
 *
 * Only candles that ended before now are read (never the one still forming). The SIZE of the next candles is what this
 * reads; their DIRECTION cannot be told from it (the study's direction read was a coin flip) - every answer says so
 * ([CAVEAT]) and none ever suggests a trade. Unknown (no candles today, too few past days) is said as unknown. Pure.
 */
object BigMoveRisk {
    enum class Level(val word: String) { LOW("low"), NORMAL("normal"), HIGH("high"), VERY_HIGH("very high") }

    /**
     * One read: [level] and [times] (x normal) - both null when it cannot be read ([unknown] says why); [reasons], most
     * weighty first; [usualPts] the usual 5-minute move in points and [bigPts] what counts as big now; [asOf] the last candle read.
     */
    data class Read(
        val market: Market, val level: Level?, val times: Double?, val reasons: List<String>,
        val usualPts: Double? = null, val bigPts: Double? = null, val asOf: LocalDateTime? = null, val unknown: String? = null,
    )

    /** A 5-minute move this many times its usual size is big. */
    const val BIG_X = 3.0
    /** The usual size: the mean 5-minute move over this many past sessions. */
    const val USUAL_DAYS = 20
    /** Fewer past sessions than this: the usual size is not known. */
    const val MIN_DAYS = 5
    /** Roughly this share of 5-minute candles is big on a usual day (the study: 2.2-2.5%). */
    const val BASE_PCT = 2.3

    const val OPEN_X = 10.0
    const val EARLY_X = 4.0
    const val THREE_PM_X = 4.0
    const val REST_X = 0.75
    const val AFTER_X = 7.0
    const val RECENT_X = 3.0
    const val HOT_X = 2.0
    const val HOTTER_X = 2.5
    const val WIDE_YESTERDAY_X = 1.5
    const val FALLING_X = 1.5
    const val FALLING_MORE_X = 1.8
    const val QUIET_X = 0.33
    const val MIN_X = 0.2
    const val MAX_X = 15.0
    /** The top / bottom fifth of recent days at this time of day. */
    private const val TOP = 0.8
    private const val BOTTOM = 0.2
    /** India VIX up this % or more since the open counts as rising. */
    const val VIX_UP_PCT = 2.0
    /** The ATM IV up this many points or more since the open counts as rising. */
    const val IV_UP_PTS = 0.5
    /** Today's last candle older than this (minutes) in market hours: the read is said as of then. */
    const val STALE_MIN = 10L

    const val CAVEAT = "direction can't be told from this"
    const val NOT_HERE = "I read the big-move risk for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private val OPEN: LocalTime = LocalTime.of(9, 15)
    private val EARLY_END: LocalTime = LocalTime.of(9, 30)
    private val THREE_PM: LocalTime = LocalTime.of(15, 0)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    // ---- the question ---------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    /** A big move named: "big move", "sharp swing", "bada move", "tez move". */
    private val BIG = Regex(" (big|bigger|large|huge|sharp|violent|sudden|wild|massive|bada|badi|bade|tez|tagda|zordar) (move|moves|swing|swings|jump|jumps|spike|spikes|candle|candles) ")
    /** Asked of now, or of its chance: "likely", "coming", "aa sakta", "abhi". */
    private val NOWISH = Regex(" (now|right now|abhi|currently|at the moment|likely|chance|chances|odds|probability|possible|coming|due|soon|aane wala|aane wali|aayega|aa sakta|aa sakti|ho sakta|hoga|expected|expect) ")
    /** Volatile asked: "is it volatile", "volatile hai kya", "how jumpy". */
    private val VOL = Regex(" (volatile|jumpy|choppy|whipsaw|whippy|swingy) ")
    private val VOL_ASK = Regex(" (is|are|hai|kya|how|kitna|kitni|right now|now|abhi|today|aaj|currently) ")
    /** Risk asked of the market now: "abhi kitna risk hai", "is the market risky right now". */
    private val RISK = Regex(" (risky|khatra|dangerous) ")
    /** The noun "risk" only with how much ("abhi kitna risk hai"): "market mein risk hai kya" / "what is the risk" stay the account's. */
    private val RISK_HOW_MUCH = Regex(" (kitna|kitni|how much) risk ")
    private val RISK_NOW = Regex(" (now|right now|abhi|currently|at the moment|market|nifty|banknifty|bank nifty|sensex|finnifty|fin nifty) ")
    /**
     * Not this read: Boss's own book or limits, a trade or its reward, a record of past days, a range or how far, a
     * forecast of direction, why something moved, a definition, VIX or IV themselves, alerts, news, gold.
     */
    private val NOT = Regex(" (risk on|risk off|buy|buying|sell|selling|i|me|my|mine|we|our|mera|meri|mere|hamara|position|positions|trade|trades|trading|limit|limits|lot|lots|portfolio|account|capital|" +
        "reward|ratio|appetite|tolerance|order|orders|record|history|how often|usually|usual|typically|generally|past|yesterday|kal|tomorrow|week|month|" +
        "why|kyun|kyon|kyu|what is a|what is an|what s a|what is volatility|what s volatility|mean|means|meaning|define|explain|vix|implied|iv|historical|range|how much can|how far|how big|points|" +
        "up or down|direction|which way|which side|which|when|what time|most|more|than|compared|vs|versus|sabse|zyada|hota|hote|day|days|din|hour|hours|weekday|weekdays|" +
        "monday|mondays|tuesday|tuesdays|wednesday|wednesdays|thursday|thursdays|friday|fridays|expiry|expiries|upar|neeche|bullish|bearish|after|ke baad|first hour|alert|alarm|remind|news|chart|gold|strategy|bot|bots|option|options|premium) ")

    /** Is the big-move risk asked of now (never a forecast of direction, the account's risk or a record of past days)? */
    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return false
        return BIG.containsMatchIn(t) && NOWISH.containsMatchIn(t) ||
            VOL.containsMatchIn(t) && VOL_ASK.containsMatchIn(t) ||
            (RISK.containsMatchIn(t) || RISK_HOW_MUCH.containsMatchIn(t)) && RISK_NOW.containsMatchIn(t)
    }

    /** The index asked about: the first index named, Nifty when none is; null when only gold or India VIX is. */
    fun market(markets: List<Market>): Market? {
        val idx = markets.firstOrNull { it in INDICES }
        if (idx == null && markets.any { it == Market.GOLD || it == Market.VIX }) return null
        return idx ?: Market.NIFTY
    }

    // ---- the read -------------------------------------------------------------------------------------------------

    /** A 5-minute candle of one session, from 09:15. */
    internal data class Five(val start: LocalDateTime, val o: Double, val h: Double, val l: Double, val c: Double) {
        val move: Double get() = if (o <= 0) 0.0 else abs(c - o) / o
    }

    private fun slot(t: LocalTime): Int = (t.toSecondOfDay() - OPEN.toSecondOfDay()) / 300

    /** One session's 1-minute [bars] (in hours) as 5-minute candles; [whole]: only those whose five minutes ended by [until]. */
    internal fun fives(bars: List<Candle>, until: LocalDateTime? = null): List<Five> =
        bars.filter { val t = it.t.toLocalTime(); !t.isBefore(OPEN) && t.isBefore(CLOSE) }.sortedBy { it.t }
            .groupBy { slot(it.t.toLocalTime()) }.toSortedMap().values
            .map { g ->
                val d = g.first().t.toLocalDate()
                Five(d.atTime(OPEN.plusSeconds(slot(g.first().t.toLocalTime()) * 300L)), g.first().o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c)
            }
            .filter { until == null || !it.start.plusMinutes(5).isAfter(until) }

    /** Realised volatility of the [minutes] 1-minute closes up to and including index [i] of one session's [bars]. */
    private fun rv(bars: List<Candle>, i: Int, minutes: Int): Double? {
        if (i < minutes) return null
        var s = 0.0
        for (k in i - minutes + 1..i) {
            val a = bars[k - 1].c; val b = bars[k].c
            if (a <= 0 || b <= 0) return null
            val r = ln(b / a); s += r * r
        }
        return sqrt(s)
    }

    /** The index of the last bar of [bars] at or before minute [t] of its day, or -1. */
    private fun at(bars: List<Candle>, t: LocalTime): Int = bars.indexOfLast { !it.t.toLocalTime().isAfter(t) }

    private fun pct(xs: List<Double>, q: Double): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return s[((s.size - 1) * q).roundToInt().coerceIn(0, s.size - 1)]
    }

    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun pts(x: Double) = "%,d".format(Locale.ENGLISH, x.roundToInt())

    /** The time-of-day factor of the 5-minute candle starting at [s], with its reason (null: an ordinary time). */
    private fun timeOf(s: LocalTime): Pair<Double, String?> = when {
        s == OPEN -> OPEN_X to "it's the opening 09:15 candle - about a fifth of all big candles come in it"
        s.isBefore(EARLY_END) -> EARLY_X to "it's the first 15 minutes (09:15-09:30), which hold about 30% of big candles"
        s == THREE_PM -> THREE_PM_X to "15:00-15:05 has about 4x the usual rate of big candles"
        else -> REST_X to null
    }

    /**
     * [m]'s big-move risk at [now] from its 1-minute [bars] (several sessions; only those ended before [now] are read),
     * India VIX's 1-minute [vix] (may be empty) and, when known, the ATM IV's change since the open in points ([ivUp]).
     * [tradingDay]: today has a session (a closed day says so).
     */
    fun read(m: Market, bars: List<Candle>, vix: List<Candle>, now: LocalDateTime, tradingDay: Boolean = true, ivUp: Double? = null): Read {
        val today = now.toLocalDate()
        val t = now.toLocalTime()
        if (!tradingDay) return Read(m, null, null, emptyList(), unknown = "The market is closed today, Boss - there is no ${m.label} candle to read the big-move risk for.")
        if (!t.isBefore(CLOSE)) return Read(m, null, null, emptyList(), unknown = "The market has closed for today, Boss; the next ${m.label} candle to watch is the 09:15 opening one, the riskiest of the day.")
        val done = bars.filter { !it.t.plusMinutes(1).isAfter(now) }
        val sessions = MarketStory.sessions(done)
        val past = sessions.filter { it.day.isBefore(today) && it.bars.size >= 30 }.takeLast(USUAL_DAYS)
        if (past.size < MIN_DAYS)
            return Read(m, null, null, emptyList(), unknown = "I have only ${past.size} past ${m.label} session${if (past.size == 1) "" else "s"} on the phone, Boss - too few to know its usual 5-minute move, so I can't read the big-move risk (I need $MIN_DAYS).")
        val pastFives = past.map { fives(it.bars) }
        val usual = pastFives.flatten().map { it.move }.average()
        if (usual <= 0.0 || usual.isNaN()) return Read(m, null, null, emptyList(), unknown = "I can't work out ${m.label}'s usual 5-minute move from the candles on the phone, Boss.")
        val tb = sessions.firstOrNull { it.day == today }?.bars.orEmpty().filter { !it.t.toLocalTime().isBefore(OPEN) }
        val preOpen = t.isBefore(OPEN)
        if (!preOpen && tb.isEmpty())
            return Read(m, null, null, emptyList(), unknown = "I don't have today's ${m.label} candles on the phone yet, Boss, so I can't read the big-move risk now.")
        val reasons = ArrayList<Pair<Double, String>>()
        val state = ArrayList<Double>()
        val atr = past.takeLast(14).map { it.range }.average()
        val lastPx = (tb.lastOrNull() ?: past.last().bars.last()).c

        // The time: the candle forming now and the next one (before the open: the 09:15 candle).
        val tf = if (preOpen) timeOf(OPEN) else {
            val cur = OPEN.plusSeconds(slot(t) * 300L)
            listOf(timeOf(cur), cur.plusMinutes(5).takeIf { it.isBefore(CLOSE) }?.let { timeOf(it) }).filterNotNull().maxBy { it.first }
        }
        val timeX = tf.first
        tf.second?.let { reasons += tf.first to it }

        // Yesterday's range wide against the last 20 sessions'.
        val ranges = past.map { it.range }
        pct(ranges.dropLast(1), TOP)?.let { top -> if (past.size > MIN_DAYS && ranges.last() >= top) {
            state += WIDE_YESTERDAY_X; reasons += WIDE_YESTERDAY_X to "yesterday's range (${pts(ranges.last())} pts) was among the widest fifth of recent days"
        } }

        if (!preOpen) {
            val todayFives = fives(tb, now)
            // Right after a big candle.
            val lastFives = todayFives.takeLast(3)
            val bigs = lastFives.filter { it.move >= BIG_X * usual }
            val lastOne = lastFives.lastOrNull()
            if (lastOne != null && lastOne.move >= BIG_X * usual) {
                state += AFTER_X
                reasons += AFTER_X to "the last 5-minute candle (${hm(lastOne.start.toLocalTime())}) was a big one, ${"%.1f".format(Locale.ENGLISH, lastOne.move / usual)}x its usual size - right after one, the next is big 6-8x more often"
            } else if (bigs.isNotEmpty()) {
                state += RECENT_X
                reasons += RECENT_X to "there was a big 5-minute candle at ${hm(bigs.last().start.toLocalTime())}, a few minutes ago - they come in clusters"
            }

            // Volatility now against recent days at this time of day.
            val i = tb.lastIndex
            val tNow = tb[i].t.toLocalTime()
            val hot = ArrayList<Int>(); var quiet = true; var quietKnown = false
            for (w in listOf(15, 30, 60)) {
                val v = rv(tb, i, w) ?: continue
                val others = past.mapNotNull { s -> val j = at(s.bars, tNow); if (j < 0) null else rv(s.bars, j, w) }
                if (others.size < MIN_DAYS) continue
                if (v >= pct(others, TOP)!!) hot += w
                if (w >= 30) { quietKnown = true; if (v > pct(others, BOTTOM)!!) quiet = false }
            }
            val rangeNow = tb.maxOf { it.h } - tb.minOf { it.l }
            val rangeOthers = past.mapNotNull { s -> val j = at(s.bars, tNow); if (j < 0) null else s.bars.subList(0, j + 1).let { b -> b.maxOf { it.h } - b.minOf { it.l } } }
            val wide = rangeOthers.size >= MIN_DAYS && atr > 0 && rangeNow >= pct(rangeOthers, TOP)!!
            if (hot.isNotEmpty() || wide) {
                val f = if (hot.isNotEmpty() && wide) HOTTER_X else HOT_X
                state += f
                if (hot.isNotEmpty()) reasons += f to "the last ${hot.joinToString("/")} minutes moved more than on 80% of recent days at this time"
                if (wide) reasons += f to "today's range so far (${pts(rangeNow)} pts, ${(rangeNow / atr * 100).roundToInt()}% of a usual day's) is wider than on 80% of recent days by now"
            } else if (quietKnown && quiet) {
                state += QUIET_X
                reasons += QUIET_X to "the last hour is among the quietest fifth for this time of day - big candles are about 3x rarer then"
            }

            // Falling markets are jumpier.
            val falling = ArrayList<String>()
            val twap = tb.map { it.c }.average()
            if (lastPx < twap) falling += "below today's average price"
            val high = tb.maxOf { it.h }
            if (atr > 0 && high - lastPx >= 0.4 * atr) falling += "${pts(high - lastPx)} pts below the day's high"
            val back = listOf(30, 60).mapNotNull { w -> if (i >= w) tb[i - w].c else null }
            if (back.isNotEmpty() && back.all { lastPx - it <= -usual * lastPx }) falling += "falling over the last ${if (back.size == 2) "30-60" else "30"} minutes"
            val vt = vix.filter { it.t.toLocalDate() == today && !it.t.toLocalTime().isBefore(OPEN) && !it.t.plusMinutes(1).isAfter(now) }.sortedBy { it.t }
            if (vt.size >= 2 && vt.first().o > 0 && (vt.last().c - vt.first().o) / vt.first().o * 100 >= VIX_UP_PCT) falling += "India VIX up since the open"
            else if (ivUp != null && ivUp >= IV_UP_PTS) falling += "the ATM IV up since the open"
            if (falling.isNotEmpty()) {
                val f = if (falling.size >= 2) FALLING_MORE_X else FALLING_X
                state += f
                reasons += f to "falling markets are jumpier: it is " + falling.joinToString(", ")
            }
        }
        // The signals overlap (a big candle is also a burst of volatility, often in a falling market): the weightiest counts
        // in full, each further one by its square root.
        val ordered = state.sortedByDescending { abs(ln(it)) }
        val x = (timeX * ordered.foldIndexed(1.0) { k, acc, f -> acc * if (k == 0) f else sqrt(f) }).coerceIn(MIN_X, MAX_X)
        val level = when { x < 0.6 -> Level.LOW; x < 1.6 -> Level.NORMAL; x < 3.5 -> Level.HIGH; else -> Level.VERY_HIGH }
        val asOf = tb.lastOrNull()?.t
        return Read(m, level, x, reasons.sortedByDescending { abs(ln(it.first)) }.map { it.second }, usual * lastPx, BIG_X * usual * lastPx, asOf)
    }

    /** "about 2.5x the usual chance", "about a third of the usual chance". */
    private fun times(x: Double): String = when {
        x >= 10 -> "about ${x.roundToInt()}x the usual chance"
        x >= 0.95 && x < 1.05 -> "about the usual chance"
        x < 0.4 -> "about a third of the usual chance or less"
        x < 0.7 -> "about half the usual chance"
        else -> "about " + "%.1f".format(Locale.ENGLISH, x) + "x the usual chance"
    }

    /**
     * The read in words: one line first ("Big-move risk for Nifty now: high, about 2.5x the usual chance - direction can't be told
     * from this."), then the top reasons and what "big" means in points (the rest is for "more"). Never a trade suggestion.
     */
    fun say(r: Read, now: LocalDateTime): String {
        r.unknown?.let { return it }
        val lvl = r.level ?: return "I can't read the big-move risk just now, Boss."
        val before = now.toLocalTime().isBefore(OPEN)
        val lead = if (before) "Big-move risk for ${r.market.label} at the open" else "Big-move risk for ${r.market.label} now"
        val out = StringBuilder("$lead: ${lvl.word}, ${times(r.times!!)} - $CAVEAT.")
        if (r.reasons.isNotEmpty()) out.append(" Why: ").append(r.reasons.take(3).joinToString("; ")).append('.')
        else out.append(" Nothing stands out: an ordinary time of day, volatility in its usual band.")
        if (r.bigPts != null && r.usualPts != null)
            out.append(" A big move here is a 5-minute candle of about ${pts(r.bigPts)} pts or more (3x the usual ${pts(r.usualPts)}); usually about 1 candle in ${(100 / BASE_PCT).roundToInt()} is one.")
        val asOf = r.asOf
        if (!before && asOf != null && asOf.plusMinutes(1).plusMinutes(STALE_MIN).isBefore(now))
            out.append(" My last ${r.market.label} candle is from ${hm(asOf.toLocalTime())}, so this is as of then.")
        out.append(" It reads the size of the next moves, not their side - not a reason to enter or exit.")
        return out.toString()
    }

    /** The answer for [m] at [now] (see [read] and [say]). */
    fun answer(m: Market, bars: List<Candle>, vix: List<Candle>, now: LocalDateTime, tradingDay: Boolean = true, ivUp: Double? = null): String =
        say(read(m, bars, vix, now, tradingDay, ivUp), now)
}
