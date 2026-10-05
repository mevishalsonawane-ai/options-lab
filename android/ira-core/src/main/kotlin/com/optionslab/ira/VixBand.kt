package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The VIX band record (market intelligence, round 33): "how often does Nifty stay within the VIX expected move?", "does
 * Nifty really move as much as VIX implies?", "how often does BankNifty move more than VIX implies?", "does India VIX
 * usually overstate the move?", "VIX expected move record", "implied vs realised volatility for Nifty", "vix ke expected
 * move se nifty kitni baar bahar jata hai". From the whole sessions of 1-minute candles on the phone (the newest
 * [MAX_SESSIONS] before today), each set against the whole session before it and India VIX's close that evening: one
 * day's move from VIX is the previous close times VIX / 100 / the square root of 252 (the way [ExpectedRange] reads it),
 * and each day is measured against it ([Q.k] of it when a multiple is asked, "twice the VIX move", "2 sigma"): how often
 * the close stayed inside, above or below it - beside what a normal spread would put inside, so a record is not passed off
 * as more or less than that - how often the day's high or low went beyond it, the median close and furthest point in moves,
 * the realised volatility over those days against VIX's average (whether the moves that came were smaller or bigger than
 * VIX had priced), and the biggest miss. Beside today: today's band from VIX's last close and where the index stands in it.
 * A record of past days on this phone, said with its counts and plainly when there are too few; never a forecast or
 * advice; nothing acts. The expected move itself stays [ExpectedRange]'s, the move by expiry [ChainIntel]'s, VIX's own
 * level [VixRank]'s and the day after a VIX jump [VixNext]'s. Pure.
 */
object VixBand {
    /** What was asked: the band as [k] times one day's VIX move (1 when no multiple is named). */
    data class Q(val k: Double = 1.0)

    /**
     * One whole session [day] against the whole session before it: that session's close [prevClose], India VIX's close
     * the same evening [vix], and this session's [high], [low] and [close].
     */
    data class Day(val day: LocalDate, val prevClose: Double, val vix: Double, val high: Double, val low: Double, val close: Double) {
        /** One day's move from VIX, in points. */
        val band: Double get() = prevClose * vix / 100 / sqrt(252.0)
        /** The close's move from the previous close, % of it. */
        val movePct: Double get() = (close - prevClose) / prevClose * 100
        /** The close's distance from the previous close, in moves. */
        val closeMoves: Double get() = abs(close - prevClose) / band
        /** The day's furthest point from the previous close either way, in moves. */
        val reachMoves: Double get() = maxOf(high - prevClose, prevClose - low) / band
    }

    /** How the days measured against [k] moves went. */
    data class Record(
        val k: Double, val sessions: Int, val inside: Int, val above: Int, val below: Int,
        val touched: Int, val touchedUp: Int, val touchedDown: Int,
        val medianClose: Double, val medianReach: Double, val medianBandPct: Double,
        /** Realised volatility over the days (% a year) and India VIX's average over the same evenings. */
        val realised: Double?, val vixMean: Double,
        val biggest: Day,
    )

    /** Fewer sessions with a whole one and a VIX close before them than this: too few to say anything. */
    const val MIN_SESSIONS = 20
    /** Fewer than this: said as a small record. */
    const val FEW_SESSIONS = 60
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    /** The session before counts only when at most this many days earlier (a weekend and a holiday). */
    const val MAX_DAYS_APART = 4L
    /** Realised and implied within this many points of each other are said as about the same. */
    const val SAME_VOL = 1.0
    private val FIRST_BY = LocalTime.of(9, 20)
    private val LAST_FROM = LocalTime.of(15, 25)
    private val CLOSE = LocalTime.of(15, 30)
    /** The indices the record is kept for (gold is not measured by India VIX, which is itself not traded). */
    internal val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the VIX band record for Nifty, BankNifty, FinNifty and Sensex only, Boss: India VIX does not measure gold."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9%. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun n0(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun n2(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun s2(x: Double) = (if (x > 0) "+" else "") + "%.2f%%".format(Locale.ENGLISH, x)
    private fun x2(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun p1(x: Double) = "%.1f%%".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun kWord(k: Double) = when (k) { 1.0 -> "one day's VIX move"; 2.0 -> "twice the day's VIX move"
        else -> "${if (k == Math.floor(k)) "%.0f".format(Locale.ENGLISH, k) else "%.1f".format(Locale.ENGLISH, k)} times the day's VIX move" }

    // ---- the questions --------------------------------------------------------------------------------------------

    /** India VIX named. */
    private val VIXW = Regex(" (vix|india vix|volatility index|fear gauge|fear index) ")
    /** A priced move named. */
    private val PRICED = Regex(" (expected move|expected moves|expected range|expected daily move|expected daily range|implied move|implied moves|" +
        "implied range|implied daily move|implied volatility|implied vol) ")
    /** A move, range or band (so VIX's own level is never asked of here). */
    private val MOVE = Regex(" (move|moves|moved|moving|movement|range|ranges|band|bands|swing|swings|implies|implied|imply|priced|prices|pricing|" +
        "expected|volatility|vol|chalta|chalti|chale|jata|jaata|jaati) ")
    /** How the moves came against the band. */
    private val OUTCOME = Regex(" (within|inside|stay in|stays in|stayed in|stay inside|stays inside|beyond|outside|exceed|exceeds|exceeded|exceeding|" +
        "break|breaks|broke|breach|breaches|breached|more than|less than|bigger than|smaller than|wider than|as much as|as big as|implies|implied|imply|" +
        "realised|realized|actual|actually|really|bahar|andar|zyada|accurate|accuracy|match|matches|right|correct) ")
    /** Said of VIX on its own: it over- or understates the moves. */
    private val STRONG = Regex(" (overstate|overstates|overstated|overstating|understate|understates|understated|understating|overestimate|" +
        "overestimates|overestimated|underestimate|underestimates|underestimated|overprice|overprices|overpriced|overpricing|underprice|underprices|" +
        "underpriced|underpricing|exaggerate|exaggerates|exaggerated) ")
    /** Asked of the record. */
    private val HOW = Regex(" (how often|how many times|how many days|how frequently|what share|what percent|what percentage|usually|normally|" +
        "typically|generally|tend to|tends to|on average|most times|historically|record|history|stats|statistics|hit rate|really|actually|" +
        "kitni baar|kitne din|kitne baar|aksar|zyada tar|mostly|often) ")
    /** Named outright: "VIX expected move record", "implied vs realised volatility". */
    private val NAMED = Regex(" ((vix|implied|expected) (move|range|band) (record|records|stats|statistics|history|accuracy|hit rate|check|track record)|" +
        "vix band|(realised|realized|actual) (vol|volatility|move|moves) (vs|versus|against|compared to) (vix|implied|india vix|implied volatility)|" +
        "(vix|implied|implied volatility|implied vol|india vix) (vs|versus|against|compared to) (realised|realized|actual) (vol|volatility|move|moves)) ")
    /** VIX itself judged right or wrong. */
    private val TRUE = Regex(" (is|was|has been) (india )?vix (usually |normally |generally |really |actually |even |)(accurate|reliable|right|wrong|correct|any good|trustworthy|off) |" +
        " (how often|how many times|how frequently) (is|does|has) (india )?vix (been )?(accurate|reliable|right|wrong|correct|off|get it right|gets it right|got it right|get it wrong|miss|misses) |" +
        " how (good|accurate|reliable) is (india )?vix (at (predicting|calling|forecasting|pricing|guessing) (the )?(moves?|range|ranges|swings?)|) |" +
        " (does|do|did) (india )?vix (get|gets|got) (it|the range|the move|the moves|the ranges) (right|wrong) ")
    /** VIX as the yardstick: "as much as VIX says". */
    private val SAYS = Regex(" (vix|india vix) (says|said|suggests|suggested|indicates|indicated|shows|showed|signals|signalled|signaled|points to|pointed to) ")
    /** VIX's own feed or figure: DataAge's and the quote's, never this record. */
    private val FEED = Regex(" (data|feed|quote|price|figure|number|reading|live|stale|updated|level) ")
    /** A size in % or points: VIX's own move, a what-if, a gap - others'. */
    private val SIZE = Regex(" \\d+(\\.\\d+)? ?(%|percent|per cent|pc|pct|points|point|pts) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, a definition, a reason, today
    // or now (the day's own expected move), a week's or an expiry's move, options, VIX jumping (VixNext's), VIX's level
    // (VixRank's), gold.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|kal|next|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|" +
        "trade|trades|trading|i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|backtest|" +
        "mean|means|meaning|define|explain|what is|what s|what are|why|kyun|kyon|today|todays|aaj|now|right now|abhi|so far|this|week|weekly|month|monthly|" +
        "expiry|expiries|straddle|strangle|premium|premiums|option|options|chain|call|calls|put|puts|ce|pe|strike|" +
        "jump|jumps|jumped|spike|spikes|spiked|percentile|rank|cheap|expensive|high or low|gold|position|positions) ")

    /** What was asked, or null: the record of past days against VIX's priced move only, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t) || SIZE.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        // Round 22: "is VIX accurate?", "how often is VIX wrong?", "does the market move as much as VIX says?" - VIX's record
        // against the moves, asked without the band's own words.
        if (VIXW.containsMatchIn(t) && !FEED.containsMatchIn(t) && (TRUE.containsMatchIn(t) || SAYS.containsMatchIn(t) && OUTCOME.containsMatchIn(t) && MOVE.containsMatchIn(t)))
            return Q(multiple(t))
        if (!NAMED.containsMatchIn(t)) {
            if (!VIXW.containsMatchIn(t) && !PRICED.containsMatchIn(t)) return null
            if (!MOVE.containsMatchIn(t)) return null
            if (!STRONG.containsMatchIn(t) && !(OUTCOME.containsMatchIn(t) && HOW.containsMatchIn(t))) return null
        }
        return Q(multiple(t))
    }

    /** The multiple named ("twice", "2 sigma", "1.5 times"), else 1; only 0.5 to 3 is read as one. */
    private fun multiple(t: String): Double {
        val m = rx(" (\\d+(?:\\.\\d+)?) ?(x|times|sd|sds|sigma|sigmas|standard deviation|standard deviations) ").find(t)
        m?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.5..3.0 }?.let { return it }
        if (rx(" (twice|double|two times|two sd|two sigma|two sigmas|two standard deviations|do guna|dugna) ").containsMatchIn(t)) return 2.0
        return 1.0
    }

    /** The index asked about (Nifty when none is named; India VIX itself is the measure), or null for gold alone. */
    fun market(markets: List<Market>): Market? {
        if (markets.any { it == Market.GOLD } && markets.none { it in INDICES }) return null
        return markets.firstOrNull { it in INDICES } ?: Market.NIFTY
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    private fun whole(s: MarketStory.Session) =
        s.bars.isNotEmpty() && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) && !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    /**
     * The whole sessions before [today] in [bars] whose session before on the phone was whole, at most [MAX_DAYS_APART]
     * days earlier, and has a whole India VIX session in [vix] the same day; newest [MAX_SESSIONS], oldest first.
     */
    fun past(bars: List<Candle>, vix: List<Candle>, today: LocalDate): List<Day> {
        val vixAt = MarketStory.sessions(vix).filter { it.day.isBefore(today) && whole(it) && it.close > 0 }.associate { it.day to it.close }
        val ss = MarketStory.sessions(bars).filter { it.day.isBefore(today) && it.bars.isNotEmpty() }
        val out = ArrayList<Day>()
        for (i in 1 until ss.size) {
            val before = ss[i - 1]; val s = ss[i]
            if (!whole(before) || !whole(s) || ChronoUnit.DAYS.between(before.day, s.day) > MAX_DAYS_APART || before.close <= 0) continue
            val v = vixAt[before.day] ?: continue
            out += Day(s.day, before.close, v, s.bars.maxOf { it.h }, s.bars.minOf { it.l }, s.close)
        }
        return out.takeLast(MAX_SESSIONS)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** The days in [days] measured against [k] moves. [days] must not be empty. */
    fun record(days: List<Day>, k: Double): Record {
        val logs = days.map { ln(it.close / it.prevClose) }
        val realised = if (logs.size < 2) null else {
            val mean = logs.average()
            sqrt(logs.sumOf { (it - mean) * (it - mean) } / (logs.size - 1)) * sqrt(252.0) * 100
        }
        return Record(k, days.size,
            days.count { it.closeMoves <= k }, days.count { it.close - it.prevClose > k * it.band }, days.count { it.prevClose - it.close > k * it.band },
            days.count { it.reachMoves > k }, days.count { it.high - it.prevClose > k * it.band }, days.count { it.prevClose - it.low > k * it.band },
            median(days.map { it.closeMoves }), median(days.map { it.reachMoves }), median(days.map { it.band / it.prevClose * 100 }),
            realised, days.map { it.vix }.average(), days.maxBy { it.closeMoves })
    }

    /** The share (%) a normal spread puts within [k] standard moves either way. */
    internal fun normalInside(k: Double): Double = erf(k / sqrt(2.0)) * 100

    /** Abramowitz and Stegun 7.1.26 (good to about 1e-7), for x >= 0. */
    private fun erf(x: Double): Double {
        val t = 1 / (1 + 0.3275911 * x)
        val y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return y
    }

    /** [m]'s record from its 1-minute candles over many days and India VIX's ([vix]), for [q], at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, vix: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, vix, today)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole session${if (days.size == 1) "" else "s"} of ${m.label} with the one before and India VIX's close that evening on the phone, Boss - " +
                "too few to say how its moves compared with what VIX priced (I need $MIN_SESSIONS)."
        val r = record(days, q.k)
        val band = kWord(q.k)
        val lines = ArrayList<String>()
        lines += "Over the last ${r.sessions} whole sessions of ${m.label} on this phone (${date(days.first().day)} to ${date(days.last().day)}), each measured from the previous close " +
            "against $band - India VIX's close that evening over the square root of 252, a median ±${p2(r.medianBandPct * q.k)} of the price:"
        lines += "${m.label} closed inside it on ${r.inside} of ${r.sessions} (${share(r.inside, r.sessions)}; a normal spread would put about " +
            "${"%.0f".format(Locale.ENGLISH, normalInside(q.k))}% inside), above it on ${r.above} and below it on ${r.below}."
        lines += "In the day, its high or low went beyond it on ${r.touched} (${share(r.touched, r.sessions)}): above on ${r.touchedUp}, below on ${r.touchedDown}."
        lines += "The median close was ${x2(r.medianClose)} of one day's VIX move from the previous close, and the day's furthest point ${x2(r.medianReach)} of it."
        r.realised?.let { rv ->
            val how = when {
                abs(rv - r.vixMean) < SAME_VOL -> "about what VIX had priced"
                rv < r.vixMean -> "the moves that came were smaller than VIX had priced"
                else -> "the moves that came were bigger than VIX had priced"
            }
            lines += "Over those days ${m.label}'s realised volatility, close to close, was ${p1(rv)} a year against India VIX averaging ${n2(r.vixMean)} - $how."
        }
        val b = r.biggest
        lines += "The biggest miss was ${date(b.day)}: ${s2(b.movePct)}, ${x2(b.closeMoves)} of that day's VIX move (VIX ${n2(b.vix)} the evening before)."
        if (m != Market.NIFTY) lines += "India VIX is worked out from Nifty's options, so for ${m.label} it is a rough yardstick."
        if (r.sessions < FEW_SESSIONS) lines += "That is only ${r.sessions} sessions, so a few days move these figures a lot."
        todayLine(q, m, bars, vix, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today against its band from VIX's last close, when the phone has today's session and a whole one with VIX before it. */
    private fun todayLine(q: Q, m: Market, bars: List<Candle>, vix: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val ss = MarketStory.sessions(bars)
        val t = ss.lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() } ?: return null
        val before = ss.lastOrNull { it.day.isBefore(today) }?.takeIf { whole(it) && it.close > 0 } ?: return null
        val v = MarketStory.sessions(vix).lastOrNull { it.day == before.day }?.takeIf { whole(it) && it.close > 0 }?.close ?: return null
        val pc = before.close
        val pts = pc * v / 100 / sqrt(252.0) * q.k
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val hi = t.bars.maxOf { it.h }; val lo = t.bars.minOf { it.l }
        val move = t.close - pc
        val where = if (abs(move) <= pts) "inside it" else if (move > 0) "above it" else "below it"
        val broke = listOfNotNull(if (hi - pc > pts) "above" else null, if (pc - lo > pts) "below" else null)
        return "Today's band from India VIX's close of ${n2(v)} on ${date(before.day)} is ±${n0(pts)} points around ${n2(pc)}, ${n0(pc - pts)} to ${n0(pc + pts)}; " +
            "${m.label} ${if (live) "is" else "ended"} ${s2(move / pc * 100)} on the previous close, $where" +
            (if (broke.isEmpty()) ", and its high and low have stayed within it${if (live) " so far" else ""}."
            else ", and in the day it went beyond it ${broke.joinToString(" and ")}.")
    }
}
