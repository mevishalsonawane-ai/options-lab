package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * The market's day, reasoned over (Jarvis self-improvement, 2026-10-05): "what happened in the market today?" - the
 * indices' moves from the previous close, Nifty's gap and whether it filled (and when), its high and low with their
 * times, when the 15-minute trend tracker turned, the biggest 15-minute move and any headline that came out at the
 * same time, how the day's range and move compare with the average of the last sessions on the phone, and India VIX.
 * And "what's different about today?" - only what stands out against those averages (ranges, moves, gaps, VIX, new
 * highs or lows, indices pulling apart), most unusual first. Facts from the candles and headlines on the phone, every
 * number from the data: never a cause claimed, never a forecast, never advice. Pure.
 */
object MarketStory {
    /** Sessions averaged at most for "usual"; fewer than [MIN_PAST] earlier sessions on the phone and nothing is compared. */
    const val PAST = 20
    const val MIN_PAST = 5
    /** A ratio to the average at or past these is wider / narrower than usual. */
    const val WIDE = 1.3
    const val NARROW = 0.7
    /** A gap smaller than this (% of the previous close) is not told. */
    const val GAP_MIN_PCT = 0.2

    /** The indices told, in order. */
    val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    private val CLOSE: LocalTime = LocalTime.of(15, 30)

    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%+,.2f".format(Locale.ENGLISH, x)
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun pctAbs(x: Double) = "%.2f%%".format(Locale.ENGLISH, abs(x))
    private fun times(x: Double) = "%.1f".format(Locale.ENGLISH, x)
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun norm(text: String) = Spaced.words(text)

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |tell me |please )*"
    private const val MKT = "(the )?(markets?|stock market|share market|indices|indian market)"
    private val STORY = Regex("^ $LEAD(what happened|what all happened|what has happened|what s happened|whats happened|what went on)" +
        "( in| on| with| to)?( $MKT)? today( in $MKT)?( boss| jarvis)? $|" +
        "^ $LEAD(how did|how have|how has) $MKT (do|done|go|gone|been) today( boss| jarvis)? $")
    private val DIFFERENT = Regex("^ $LEAD(what s|whats|what is|what was|anything|is there anything|was there anything) (different|unusual|special|out of the ordinary)" +
        " (about |in |with )?(today|today s market|todays market|the market today|the markets today)( boss| jarvis)? $|" +
        "^ $LEAD(is|was) (today|the market today|the market) (an |a )?(unusual|normal|usual|typical|ordinary)( day)?( today)?( boss| jarvis)? $|" +
        "^ $LEAD(how is|how was|how s|hows) today (different|unusual)( from (a |the )?(usual|normal|typical) day)?( boss| jarvis)? $|" +
        "^ $LEAD(today|today s market) (vs|versus|against|compared to|compared with) (a |the )?(usual|normal|typical|average) (day|session)( boss| jarvis)? $")

    enum class Kind { STORY, DIFFERENT }

    /** Which of the two was asked, or null. Whole questions only, no index named: a question about one index is its own. */
    fun asked(text: String): Kind? {
        val t = norm(text)
        if (Market.mentioned(text).isNotEmpty()) return null
        return when {
            DIFFERENT.containsMatchIn(t) -> Kind.DIFFERENT
            STORY.containsMatchIn(t) -> Kind.STORY
            else -> null
        }
    }

    /** One session of an index from its 1-minute candles. */
    data class Session(val day: LocalDate, val bars: List<Candle>) {
        val open: Double get() = bars.first().o
        val close: Double get() = bars.last().c
        val high: Candle get() = bars.maxBy { it.h }
        val low: Candle get() = bars.minBy { it.l }
        val range: Double get() = bars.maxOf { it.h } - bars.minOf { it.l }
    }

    /** The sessions in [bars], oldest first. */
    fun sessions(bars: List<Candle>): List<Session> =
        // Speed round 9: a market-history answer (MoveTime, GiveBack, MultiDay, OpenReach, DayAfter and the rest) split
        // the same months of 1-minute candles into sessions twice per ask (the record, then today's line), about 2 ms a
        // split on a desktop. The split depends on nothing but the candles (no clock, no day, no account figure: index
        // prices only), so it is kept by them ([BarsKept]: given back only for equal candles) and every answer is as before.
        if (bars.size in SPLIT_KEEP_FROM..SPLIT_KEEP_UP_TO) split.of(bars, null) { sessionsNow(bars) } else sessionsNow(bars)

    /** Histories of this many candles have their split kept (a day or more, up to about 130 sessions of 1-minute bars). */
    private const val SPLIT_KEEP_FROM = 300
    private const val SPLIT_KEEP_UP_TO = 50_000
    /** A few histories at once (each index and India VIX). */
    private val split = BarsKept<List<Session>>(6)
    private val splitsRead = java.util.concurrent.atomic.AtomicInteger()

    private fun sessionsNow(bars: List<Candle>): List<Session> {
        splitsRead.incrementAndGet()
        return bars.groupBy { it.t.toLocalDate() }.toSortedMap().map { (d, b) -> Session(d, b.sortedBy { it.t }) }
    }

    /** How many times candles were split into sessions afresh (tests count work by this, never by the clock). */
    internal val splitsDone: Int get() = splitsRead.get()
    /** How many splits are kept (tests). */
    internal val splitsKept: Int get() = split.size

    /** Every kept split forgotten and the count reset (the reset hook for tests). */
    internal fun forgetSplits() { split.clear(); splitsRead.set(0) }

    /** What the phone says about one index today against its own recent sessions. */
    data class Read(
        val market: Market, val today: Session, val prevClose: Double?, val past: Int,
        val changePct: Double?, val gapPct: Double?, val gapFilledAt: LocalDateTime?,
        /** Today's range against the average range of the earlier sessions over the same number of minutes. */
        val avgRange: Double?, val rangeRatio: Double?,
        /** Today's move from the previous close against the average size of a day's move. */
        val avgMovePct: Double?, val moveRatio: Double?,
        val avgGapPct: Double?,
        /** Today's high above every earlier session's on the phone (or the low below every one). */
        val newHigh: Boolean, val newLow: Boolean,
    )

    /** [m]'s day [day] from [bars] (1-minute candles over several days), or null without enough of today. */
    fun read(m: Market, bars: List<Candle>, day: LocalDate): Read? {
        val all = sessions(bars).filter { it.day <= day }
        val today = all.lastOrNull()?.takeIf { it.day == day && it.bars.size >= 15 } ?: return null
        val earlier = all.dropLast(1)
        val prevClose = earlier.lastOrNull()?.close
        val past = earlier.takeLast(PAST)
        val k = today.bars.size
        // Each earlier session cut to as many minutes as today has, so a day in progress is fairly compared.
        val cut = past.map { Session(it.day, it.bars.take(k)) }.filter { it.bars.size >= maxOf(15, k * 9 / 10) }
        val enough = cut.size >= MIN_PAST
        val avgRange = if (enough) cut.map { it.range }.average().takeIf { it > 0 } else null
        // Close-to-close moves (and opening gaps) of the earlier sessions need the session before each.
        val pairs = earlier.takeLast(PAST + 1).zipWithNext()
        val moves = pairs.map { (a, b) -> (b.close - a.close) / a.close * 100 }
        val gaps = pairs.map { (a, b) -> (b.open - a.close) / a.close * 100 }
        val avgMove = if (moves.size >= MIN_PAST) moves.map { abs(it) }.average().takeIf { it > 0 } else null
        val avgGap = if (gaps.size >= MIN_PAST) gaps.map { abs(it) }.average() else null
        val change = prevClose?.let { (today.close - it) / it * 100 }
        val gap = prevClose?.let { (today.open - it) / it * 100 }
        val filled = if (prevClose == null || gap == null || abs(gap) < GAP_MIN_PCT) null
            else today.bars.firstOrNull { if (gap > 0) it.l <= prevClose else it.h >= prevClose }?.t
        val highs = earlier.takeLast(PAST)
        return Read(
            m, today, prevClose, past.size, change, gap, filled,
            avgRange, avgRange?.let { today.range / it },
            avgMove, if (avgMove != null && change != null) abs(change) / avgMove else null,
            avgGap,
            highs.size >= MIN_PAST && today.high.h > highs.maxOf { it.high.h },
            highs.size >= MIN_PAST && today.low.l < highs.minOf { it.low.l },
        )
    }

    /** When the 15-minute trend tracker turned during [day]: (time the candle closed, up). */
    fun turns(m: Market, bars: List<Candle>, day: LocalDate, now: LocalDateTime): List<Pair<LocalDateTime, Boolean>> {
        val upTo = bars.filter { it.t.toLocalDate() <= day }
        if (upTo.isEmpty()) return emptyList()
        val end = upTo.last().t.plusMinutes(1).let { if (now.isBefore(it)) now else it }
        val c = Candles.closed(Candles.fold(upTo, 15, m), 15, end)
        if (c.size < 12) return emptyList()
        val (dir, _) = Candles.tracker(c)
        return (1 until c.size).filter { c[it].t.toLocalDate() == day && dir[it] != dir[it - 1] && c[it - 1].t.toLocalDate() == day }
            .map { c[it].t.plusMinutes(15) to (dir[it] > 0) }
    }

    /** The day's biggest 15-minute move: (start, end, points, %). */
    fun biggest(m: Market, today: Session): Biggest? {
        val c = Candles.fold(today.bars, 15, m)
        val b = c.maxByOrNull { abs(it.c - it.o) / it.o } ?: return null
        if (b.o <= 0) return null
        return Biggest(b.t, b.t.plusMinutes(15), b.c - b.o, (b.c - b.o) / b.o * 100)
    }
    data class Biggest(val from: LocalDateTime, val to: LocalDateTime, val points: Double, val pct: Double)

    /** Headlines about [m] (or no index in particular) published from 10 minutes before [b] began to 5 after it ended. */
    fun alongside(m: Market, b: Biggest, news: List<Headline>, zone: ZoneId): List<Pair<Headline, LocalDateTime>> =
        news.mapNotNull { h -> h.at?.let { h to LocalDateTime.ofInstant(it, zone) } }
            .filter { (h, t) -> (h.markets.isEmpty() || m in h.markets) && !t.isBefore(b.from.minusMinutes(10)) && !t.isAfter(b.to.plusMinutes(5)) }
            .sortedBy { it.second }.distinctBy { it.first.title.lowercase() }.take(2)

    private fun closed(day: LocalDate, now: LocalDateTime) = now.toLocalDate().isAfter(day) || !now.toLocalTime().isBefore(CLOSE)

    private fun usualWord(r: Double) = when { r >= WIDE -> "wider than usual"; r <= NARROW -> "narrower than usual"; else -> "about usual" }

    /** One thing that stands out today, with how far from usual it is (bigger first). */
    data class Fact(val score: Double, val text: String, val market: Market? = null, val kind: String = "")

    /**
     * What stands out today: ranges and moves at least [WIDE] times (or at most [NARROW] times) their average, a gap
     * twice the usual, a high or low past every earlier session on the phone, indices moving opposite ways, VIX
     * moving twice its usual. Most unusual first.
     */
    fun differences(reads: List<Read>, vix: Read?, done: Boolean): List<Fact> {
        val out = ArrayList<Fact>()
        val sofar = if (done) "" else " so far"
        for (r in reads) {
            val m = r.market.label
            r.rangeRatio?.let { q ->
                if (q >= WIDE) out += Fact(q, market = r.market, kind = "range", text = "${m}'s range of ${n(r.today.range)} points$sofar is ${times(q)} times its average of ${n(r.avgRange!!)} over the last ${r.past} sessions.")
                else if (q <= NARROW) out += Fact(1 / q, market = r.market, kind = "range", text = "${m}'s range of ${n(r.today.range)} points$sofar is only ${times(q)} times its average of ${n(r.avgRange!!)} over the last ${r.past} sessions - a quiet day.")
            }
            if (r.moveRatio != null && r.changePct != null && r.moveRatio >= 2.0)
                out += Fact(r.moveRatio, market = r.market, kind = "move", text = "$m ${if (done) "closed" else "is"} ${pct(r.changePct)} from the previous close, ${times(r.moveRatio)} times its average day's move of ${pctAbs(r.avgMovePct!!)}.")
            if (r.gapPct != null && r.avgGapPct != null && abs(r.gapPct) >= 0.3 && abs(r.gapPct) >= 2 * r.avgGapPct)
                out += Fact(abs(r.gapPct) / maxOf(r.avgGapPct, 0.05), market = r.market, kind = "gap", text = "$m opened with a gap ${if (r.gapPct > 0) "up" else "down"} of ${pctAbs(r.gapPct)}, against an average gap of ${pctAbs(r.avgGapPct)}.")
            if (r.newHigh) out += Fact(2.0, "$m made a high of ${n(r.today.high.h)} at ${hm(r.today.high.t)}, above every one of the last ${r.past} sessions on the phone.")
            if (r.newLow) out += Fact(2.0, "$m made a low of ${n(r.today.low.l)} at ${hm(r.today.low.t)}, below every one of the last ${r.past} sessions on the phone.")
        }
        // Indices pulling apart: the strongest and the weakest moving opposite ways, each by 0.3% or more.
        val moved = reads.filter { it.changePct != null }
        val hi = moved.maxByOrNull { it.changePct!! }; val lo = moved.minByOrNull { it.changePct!! }
        if (hi != null && lo != null && hi.changePct!! >= 0.3 && lo.changePct!! <= -0.3)
            out += Fact(1.5, "The indices pulled apart: ${hi.market.label} ${pct(hi.changePct)} while ${lo.market.label} ${pct(lo.changePct)}.")
        if (vix?.changePct != null) {
            val v = vix.changePct
            val q = vix.avgMovePct?.let { abs(v) / it }
            if ((q != null && q >= 2.0) || abs(v) >= 8.0)
                out += Fact(q ?: (abs(v) / 4), market = Market.VIX, kind = "vix", text = "India VIX ${if (v > 0) "rose" else "fell"} ${pctAbs(v)} to ${n(vix.today.close)}" +
                    (vix.avgMovePct?.let { ", against an average daily change of ${pctAbs(it)}." } ?: "."))
        }
        return out.sortedByDescending { it.score }
    }

    /** "What's different about today?": what stands out, or that nothing does (with Nifty's range against usual). */
    fun different(bars: Map<Market, List<Candle>>, now: LocalDateTime, day: LocalDate = now.toLocalDate()): String? {
        val reads = INDICES.mapNotNull { m -> bars[m]?.let { read(m, it, day) } }
        if (reads.isEmpty()) return null
        val vix = bars[Market.VIX]?.let { read(Market.VIX, it, day) }
        val done = closed(day, now)
        if (reads.none { it.avgRange != null }) return "I have too few earlier sessions on the phone to say what a usual day looks like, Boss."
        val facts = differences(reads, vix, done)
        if (facts.isEmpty()) {
            val nifty = reads.firstOrNull { it.rangeRatio != null } ?: reads.first()
            return "Nothing stands out today, Boss: ranges, moves and gaps are within their usual sizes over the last ${nifty.past} sessions" +
                (nifty.rangeRatio?.let { " (${nifty.market.label}'s range${if (done) "" else " so far"} is ${times(it)} times its average)" } ?: "") +
                (vix?.changePct?.let { ", and India VIX is ${pct(it)} on the day." } ?: ".")
        }
        return "What's different about today, Boss: " + facts.take(4).joinToString(" ") { it.text }
    }

    /**
     * "What happened in the market today?": the indices' moves, Nifty's gap, its high and low, the trend tracker's turns,
     * the biggest 15-minute move (with headlines out at the same time), the day against the usual, and India VIX.
     */
    fun story(bars: Map<Market, List<Candle>>, news: List<Headline>, now: LocalDateTime, day: LocalDate = now.toLocalDate(),
              zone: ZoneId = ZoneId.of("Asia/Kolkata")): String? {
        val reads = INDICES.mapNotNull { m -> bars[m]?.let { read(m, it, day) } }
        val lead = reads.firstOrNull() ?: return null
        val done = closed(day, now)
        val out = ArrayList<String>()
        val l = lead.market.label
        // The indices' moves from the previous close.
        val others = reads.drop(1).filter { it.changePct != null }.joinToString(", ") { "${it.market.label} ${pct(it.changePct!!)}" }
        out += if (lead.prevClose != null)
            "${if (done) "Today's market" else "The market so far"}, Boss: $l ${if (done) "closed at" else "is at"} ${n(lead.today.close)}, " +
                "${pts(lead.today.close - lead.prevClose)} (${pct(lead.changePct!!)}) from the previous close" + (if (others.isNotEmpty()) "; $others." else ".")
        else "${if (done) "Today's market" else "The market so far"}, Boss: $l opened at ${n(lead.today.open)} and ${if (done) "closed at" else "is at"} ${n(lead.today.close)}."
        // The gap and whether it filled.
        lead.gapPct?.takeIf { abs(it) >= GAP_MIN_PCT }?.let { g ->
            val up = g > 0
            out += "It opened with a gap ${if (up) "up" else "down"} of ${pctAbs(g)}" + (lead.gapFilledAt?.let { ", which filled at ${hm(it)}." }
                ?: ", which ${if (done) "never filled" else "has not filled yet"}: the ${if (up) "low" else "high"} of ${n(if (up) lead.today.low.l else lead.today.high.h)} stayed ${if (up) "above" else "below"} the previous close of ${n(lead.prevClose!!)}.")
        }
        // The path: which came first, the high or the low.
        val hi = lead.today.high; val lo = lead.today.low
        out += if (!hi.t.isAfter(lo.t)) "The high of ${n(hi.h)} came at ${hm(hi.t)} and the low of ${n(lo.l)} at ${hm(lo.t)}."
            else "The low of ${n(lo.l)} came at ${hm(lo.t)} and the high of ${n(hi.h)} at ${hm(hi.t)}."
        // When the 15-minute trend tracker turned.
        val turns = turns(lead.market, bars[lead.market].orEmpty(), day, now)
        if (turns.isNotEmpty()) out += "The 15-minute trend tracker turned " + turns.take(3).joinToString(", then ") { (t, up) -> "${if (up) "up" else "down"} at ${hm(t)}" } +
            (if (turns.size > 3) " (${turns.size} turns in all)." else ".")
        // The biggest 15-minute move, and any headline out at the same time (said as timing only, never as the cause).
        biggest(lead.market, lead.today)?.let { b ->
            out += "The biggest 15-minute move was ${pts(b.points)} points (${pct(b.pct)}) from ${hm(b.from)} to ${hm(b.to)}."
            val hs = alongside(lead.market, b, news, zone)
            if (hs.isNotEmpty()) out += "Out around then: " + hs.joinToString("; ") { (h, t) -> "\"${h.title}\" (${h.source}, ${hm(t)})" } + "."
        }
        // The day against the usual.
        lead.rangeRatio?.let { q ->
            out += "The day's range of ${n(lead.today.range)} points${if (done) "" else " so far"} is ${times(q)} times its average of ${n(lead.avgRange!!)} over the last ${lead.past} sessions - ${usualWord(q)}."
        }
        // India VIX.
        val vix = bars[Market.VIX]?.let { read(Market.VIX, it, day) }
        vix?.let { v ->
            if (v.changePct != null) out += "India VIX ${if (done) "ended" else "is"} at ${n(v.today.close)}, ${pct(v.changePct)} on the day" +
                (v.avgMovePct?.let { a -> if (abs(v.changePct) >= 2 * a) ", a bigger change than its average of ${pctAbs(a)}." else "." } ?: ".")
        }
        // What else stands out (not what is said above: the lead's move, gap and range, and India VIX).
        val extra = differences(reads, vix, done).filterNot { f -> f.kind == "vix" || (f.market == lead.market && f.kind in setOf("range", "move", "gap")) }.take(2)
        out += extra.map { it.text }
        return out.joinToString(" ")
    }

    /**
     * For the 15:45 wrap-up: the one thing that stood out most today (or that it was a usual day), which index led and
     * which lagged when they were apart, and how to hear the whole story. Null without today's candles or enough earlier sessions.
     */
    fun wrapLine(bars: Map<Market, List<Candle>>, now: LocalDateTime, day: LocalDate = now.toLocalDate()): String? {
        val reads = INDICES.mapNotNull { m -> bars[m]?.let { read(m, it, day) } }
        if (reads.none { it.avgRange != null }) return null
        val vix = bars[Market.VIX]?.let { read(Market.VIX, it, day) }
        val top = differences(reads, vix, closed(day, now)).firstOrNull()?.text ?: "Nothing unusual stood out in the market today."
        // Which index led and which lagged ([Breadth]; not when the top fact already says the indices pulled apart).
        val lead = Breadth.leadership(reads.mapNotNull { r -> r.changePct?.let { r.market to it } })
            ?.takeUnless { top.startsWith("The indices pulled apart") }?.let { "$it " }.orEmpty()
        return "$top ${lead}Ask me \"what happened in the market today\" for the whole story, Boss."
    }
}
