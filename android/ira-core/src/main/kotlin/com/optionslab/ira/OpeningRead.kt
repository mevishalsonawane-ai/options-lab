package com.optionslab.ira

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityOverlay
import com.optionslab.engine.orb.LiquidityRules
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The opening read (Boss, 06 Oct 2026): once a trading day, just after the first 5-minute candle closes (from [POST_AT]
 * through [POST_LAST], in the words lane), one short note in the chat on how the market opened; and the same when asked
 * ("how did the market open", "opening read", "market kaisa khula", "where did we open vs the levels"). For Nifty and
 * Liquidity 15+5's two indices, BankNifty and FinNifty:
 *
 *  - the gap against the previous session's close, in points and %, and for Nifty what GIFT Nifty pointed to in the
 *    morning (the market recorder's last reading, as the 09:00 check reads it - [MorningCues]; never fetched here);
 *  - where the open sits against Liquidity 15+5's levels: the nearest untaken level above and below the open and how far
 *    (the levels as they stood at the open - the books' closed bars up to the previous close, [LiquidityRules.zones]), and
 *    the levels the gap already took overnight;
 *  - the first 5-minute candle's range against the usual opening candle (its mean over the last [USUAL_DAYS] sessions,
 *    at least [MIN_DAYS]; else not said);
 *  - Liquidity 15+5's switch and lots and its first trigger (the nearest break with room, [LiquidityWhyNot.nearestWords]);
 *  - the big-move read's one line ([BigMoveRisk]) when it reads.
 *
 * Facts only, at most [MAX_LINES] lines, each only when its data is there; the brief form (saving battery, short answers)
 * is the first two. Words only: nothing here places, closes, arms or changes anything, and it never suggests a trade. Pure.
 */
object OpeningRead {
    /** The note is put in the chat from this time on a trading day (the first 5-minute candle has closed)... */
    val POST_AT: LocalTime = LocalTime.of(9, 20)
    /** ... through this minute (later, the day has moved on: asked, it is still answered). */
    val POST_LAST: LocalTime = LocalTime.of(9, 25)
    private val OPEN: LocalTime = LocalTime.of(9, 15)
    private val FIRST_LAST_MINUTE: LocalTime = LocalTime.of(9, 19)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)

    /** The usual opening candle: its mean range over this many past sessions... */
    const val USUAL_DAYS = 20
    /** ... and not said with fewer than this many. */
    const val MIN_DAYS = 5
    /** The note is never longer than this. */
    const val MAX_LINES = 6
    /** The brief form: this many lines. */
    const val BRIEF_LINES = 2

    /** The indices read, in the order said: Nifty, then Liquidity 15+5's BankNifty and FinNifty. */
    val MARKETS: List<Market> = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY)

    const val CLOSED = "No session today, Boss - the market is closed, so there is no opening to read."
    const val NOT_YET = "The market opens at 09:15, Boss - the opening read comes once the first 5-minute candle closes at 09:20."
    const val NO_CANDLES = "I don't have today's opening candles on the phone yet, Boss - ask again in a minute."

    // ---- the facts ------------------------------------------------------------------------------------------------

    /** An index's gap: [prevClose] of [prevDay] (the last 1-minute close) against today's [open] (the 09:15 minute's open). */
    data class Gap(val market: Market, val prevDay: LocalDate, val prevClose: Double, val open: Double) {
        val points: Double get() = open - prevClose
        val pct: Double get() = if (prevClose > 0) points / prevClose * 100 else 0.0
    }

    /** The first 5-minute candle's [high] and [low], and the [usual] opening candle's range over [days] past sessions (null: too few). */
    data class First(val market: Market, val high: Double, val low: Double, val usual: Double?, val days: Int) {
        val range: Double get() = high - low
    }

    /** One index read: its [gap] and [first] candle (each null when the candles do not hold it). */
    data class Index(val market: Market, val gap: Gap?, val first: First?)

    /** The market recorder's last GIFT Nifty reading: its price [last], taken at [at]. */
    data class Gift(val last: Double, val at: LocalDateTime)

    /** One untaken Liquidity level of a book ([minutes]-min) of [underlying], on [side] (+1 a high's, -1 a low's), at [edge]. */
    data class Lvl(val underlying: String, val minutes: Int, val side: Int, val edge: Double, val name: String)

    /** Where [underlying] opened against its levels: the nearest [above] and [below] the [open], and those the gap [took]. */
    data class Place(val underlying: String, val open: Double, val gapped: Boolean, val above: Lvl?, val below: Lvl?, val took: List<Lvl>)

    /** Liquidity 15+5: its switch [armed] and [lots] (null: not read), and its first [trigger] in words (null: none ahead). */
    data class Arm(val armed: Boolean?, val lots: Int?, val trigger: String?, val levelsRead: Boolean = true)

    /**
     * What the read is made of at [now]: [tradingDay] today a session; [indices] each index read; [gift] the recorder's
     * last GIFT Nifty reading (null: none); [places] BankNifty's and FinNifty's place against the levels; [arm] Liquidity
     * 15+5's switch, lots and first trigger (null: not read); [bigMove] Nifty's big-move read now.
     */
    data class Facts(
        val now: LocalDateTime, val tradingDay: Boolean = true,
        val indices: List<Index> = emptyList(), val gift: Gift? = null,
        val places: List<Place> = emptyList(), val arm: Arm? = null,
        val bigMove: BigMoveRisk.Read? = null,
    )

    // ---- when -----------------------------------------------------------------------------------------------------

    /** The note is due at [now]: a trading day, [POST_AT] through [POST_LAST], and not yet put today ([doneOn]). */
    fun due(now: LocalDateTime, tradingToday: Boolean, doneOn: LocalDate?): Boolean {
        if (!tradingToday || doneOn == now.toLocalDate()) return false
        val t = now.toLocalTime()
        return !t.isBefore(POST_AT) && t.isBefore(POST_LAST.plusMinutes(1))
    }

    /** Something to say: at least one index's gap is read. */
    fun ready(f: Facts): Boolean = f.indices.any { it.gap != null }

    // ---- from the candles -----------------------------------------------------------------------------------------

    private fun todayBars(bars: List<Candle>, today: LocalDate): List<Candle> =
        bars.filter { it.t.toLocalDate() == today && !it.t.toLocalTime().isBefore(OPEN) && it.t.toLocalTime().isBefore(CLOSE) }.sortedBy { it.t }

    /**
     * [bars] hold today's opening at [now]: the 09:15 minute and, once the first 5-minute candle has closed (from 09:20),
     * its last minute (09:19) too - and an earlier session to set the open against.
     */
    fun hasOpening(bars: List<Candle>, today: LocalDate, now: LocalDateTime): Boolean {
        val tb = todayBars(bars, today)
        if (tb.firstOrNull()?.t?.toLocalTime() != OPEN) return false
        if (!now.toLocalTime().isBefore(POST_AT) && tb.none { it.t.toLocalTime() == FIRST_LAST_MINUTE }) return false
        return bars.any { it.t.toLocalDate().isBefore(today) }
    }

    /** [m]'s gap today from its 1-minute [bars]: the last earlier session's close against today's 09:15 open; null without either. */
    fun gap(m: Market, bars: List<Candle>, today: LocalDate): Gap? {
        val first = todayBars(bars, today).firstOrNull()?.takeIf { it.t.toLocalTime() == OPEN } ?: return null
        val prev = MarketStory.sessions(bars.filter { it.t.toLocalDate().isBefore(today) }).lastOrNull { it.bars.isNotEmpty() } ?: return null
        if (prev.close <= 0 || first.o <= 0) return null
        return Gap(m, prev.day, prev.close, first.o)
    }

    /** The 09:15 5-minute candle of [bars] on [day], whole (its five minutes all there), or null. */
    private fun openingFive(bars: List<Candle>, day: LocalDate): BigMoveRisk.Five? {
        val ones = todayBars(bars, day).filter { it.t.toLocalTime().isBefore(POST_AT) }
        if (ones.firstOrNull()?.t?.toLocalTime() != OPEN || ones.last().t.toLocalTime() != FIRST_LAST_MINUTE) return null
        return BigMoveRisk.fives(ones).firstOrNull()
    }

    /**
     * [m]'s first 5-minute candle today, once closed at [now] (from 09:20), with the usual opening candle's range (the
     * mean of the last [USUAL_DAYS] earlier sessions' whole 09:15 candles; null with fewer than [MIN_DAYS]).
     */
    fun first(m: Market, bars: List<Candle>, now: LocalDateTime): First? {
        if (now.toLocalTime().isBefore(POST_AT)) return null
        val today = now.toLocalDate()
        val five = openingFive(bars, today) ?: return null
        val past = MarketStory.sessions(bars.filter { it.t.toLocalDate().isBefore(today) })
            .mapNotNull { s -> openingFive(s.bars, s.day) }.takeLast(USUAL_DAYS)
        val usual = if (past.size < MIN_DAYS) null else past.map { it.h - it.l }.average()
        return First(m, five.h, five.l, usual, past.size)
    }

    /** [m] read from its 1-minute [bars] at [now]. */
    fun index(m: Market, bars: List<Candle>, now: LocalDateTime): Index = Index(m, gap(m, bars, now.toLocalDate()), first(m, bars, now))

    // ---- the levels -----------------------------------------------------------------------------------------------

    private fun overlaps(a: LiquidityRules.Zone, b: LiquidityRules.Zone) = a.bottom <= b.top && b.bottom <= a.top

    /**
     * One book's untaken levels after its closed [bars] and their [zones] ([LiquidityRules.zones]): every zone known by the
     * last bar and not closed through since, named as [LiquidityMap] names them ("pool on a swing high", "swing low").
     */
    fun levelsOf(bars: List<Bar>, zones: List<LiquidityRules.Zone>, underlying: String, minutes: Int): List<Lvl> {
        val i = bars.lastIndex
        val active = zones.filter { it.known <= i && it.broken < 0 }
        val swings = active.filter { it.kind == "swing" }
        return active.map { z ->
            val onSwing = z.kind == "pool" && swings.any { s -> s.side == z.side && overlaps(s, z) }
            Lvl(underlying, minutes, z.side, z.edge, LiquidityMap.Level(z.kind, z.side, z.edge, onSwing).name)
        }.distinctBy { Triple(it.side, it.edge, it.name) }
    }

    /**
     * A book's levels as they stood at today's open: its [minutes]-minute chart folded from the index's 1-minute [ones]
     * before 09:15 [today] (as the arm folds them), or null when too few bars are closed to read them ([LiquidityMap.MIN_BARS]).
     */
    fun levelsAtOpen(ones: List<Bar>, underlying: String, minutes: Int, today: LocalDate): List<Lvl>? {
        val at = today.atTime(OPEN)
        val bars = LiquidityOverlay.closedBars(ones.filter { it.start.isBefore(at) }, minutes, at, inputMinutes = 1)
        if (bars.size < LiquidityMap.MIN_BARS) return null
        return levelsOf(bars, LiquidityRules.zones(bars), underlying, minutes)
    }

    /**
     * Where [underlying] opened ([open], the previous close [prevClose] when known) against its books' [levels] at the
     * open: on each side the nearest level beyond the open (a high's above, a low's below; the smaller book first on a tie),
     * and the levels of the gap's side between the close and the open - taken by the gap before the first candle.
     */
    fun place(underlying: String, open: Double, prevClose: Double?, levels: List<Lvl>): Place {
        val mine = levels.filter { it.underlying == underlying }
        val above = mine.filter { it.side > 0 && it.edge > open }.minWithOrNull(compareBy<Lvl> { it.edge - open }.thenBy { it.minutes })
        val below = mine.filter { it.side < 0 && it.edge < open }.minWithOrNull(compareBy<Lvl> { open - it.edge }.thenBy { it.minutes })
        val took = when {
            prevClose == null || open == prevClose -> emptyList()
            open > prevClose -> mine.filter { it.side > 0 && it.edge > prevClose && it.edge <= open }.sortedBy { it.edge }
            else -> mine.filter { it.side < 0 && it.edge < prevClose && it.edge >= open }.sortedByDescending { it.edge }
        }.distinctBy { it.edge }
        return Place(underlying, open, prevClose != null && open != prevClose, above, below, took)
    }

    // ---- GIFT Nifty -----------------------------------------------------------------------------------------------

    /**
     * What GIFT Nifty pointed to for Nifty's [gap] (points), from the recorder's last reading [g] at [now]: only a reading
     * taken after the previous session's close, outside market hours and younger than [MorningCues.GIFT_OLD_HOURS] - the
     * one the 09:00 check reads. Null otherwise.
     */
    fun giftPointed(g: Gift?, gap: Gap?, now: LocalDateTime): Double? {
        if (g == null || gap == null || g.last <= 0) return null
        if (g.at.isBefore(gap.prevDay.atTime(CLOSE)) || g.at.isAfter(now)) return null
        if (MorningCues.inSession(g.at) || Duration.between(g.at, now).toHours() >= MorningCues.GIFT_OLD_HOURS) return null
        return g.last - gap.prevClose
    }

    // ---- the words ------------------------------------------------------------------------------------------------

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun n(x: Double) = String.format(Locale.ENGLISH, "%,.0f", x)
    private fun pts(x: Double) = String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun signed(x: Double) = (if (x < 0) "-" else "+") + pts(x)
    private fun signedPct(x: Double) = (if (x < 0) "-" else "+") + String.format(Locale.ENGLISH, "%.2f", abs(x)) + "%"
    private fun times(x: Double) = String.format(Locale.ENGLISH, "%.1f", x) + "x"
    private fun hm(t: LocalTime) = String.format(Locale.ENGLISH, "%02d:%02d", t.hour, t.minute)
    private fun s(k: Int) = if (k == 1) "" else "s"

    /** "Nifty +62 pts (+0.25%; GIFT Nifty pointed to +55)", "BankNifty -120 pts (-0.22%)". */
    fun gapWords(g: Gap, gift: Double?): String =
        "${g.market.label} ${signed(g.points)} pts (${signedPct(g.pct)}" + (gift?.let { "; GIFT Nifty pointed to ${signed(it)}" } ?: "") + ")"

    private fun gapLine(f: Facts): String {
        val day = f.now.toLocalDate().format(DAY)
        val gaps = f.indices.mapNotNull { i -> i.gap?.let { g -> gapWords(g, if (g.market == Market.NIFTY) giftPointed(f.gift, g, f.now) else null) } }
        val prev = f.indices.mapNotNull { it.gap?.prevDay }.distinct().singleOrNull()
        val against = if (prev != null && prev != f.now.toLocalDate().minusDays(1)) "against ${prev.format(DAY)}'s close" else "against yesterday's close"
        return "Opening read, Boss ($day) - the open $against: ${gaps.joinToString(", ")}."
    }

    private fun lvlWords(l: Lvl, open: Double) = "${n(l.edge)} (${l.name}, ${l.minutes}-min) ${pts(l.edge - open)} pts away"

    /** "BankNifty opened 54,120: above 54,180 (pool on a swing high, 15-min) 60 pts away; below 53,900 (swing low, 5-min) 220 pts away; the gap took 54,050 (swing high, 5-min)." */
    fun placeLine(p: Place): String {
        val above = p.above?.let { "above ${lvlWords(it, p.open)}" } ?: "nothing untaken above"
        val below = p.below?.let { "below ${lvlWords(it, p.open)}" } ?: "nothing untaken below"
        val took = when {
            p.took.isNotEmpty() -> "; the gap took " + p.took.take(2).joinToString(" and ") { "${n(it.edge)} (${it.name}, ${it.minutes}-min)" } +
                (if (p.took.size > 2) " and ${p.took.size - 2} more" else "") + " before the first candle"
            p.gapped -> "; the gap took no level"
            else -> ""
        }
        return "${LiquidityMap.indexName(p.underlying)} opened ${n(p.open)}: $above; $below$took."
    }

    private fun firstWords(x: First): String? {
        val usual = x.usual?.takeIf { it > 0 } ?: return null
        return "${x.market.label} ${pts(x.range)} pts (${times(x.range / usual)} its usual ${pts(usual)})"
    }

    private fun firstLine(f: Facts): String? {
        if (f.now.toLocalTime().isBefore(POST_AT)) return "First 5-minute candle: it closes at ${hm(POST_AT)}."
        val xs = f.indices.mapNotNull { it.first?.let(::firstWords) }
        if (xs.isEmpty()) return null
        return "First 5-minute candle's range: ${xs.joinToString(", ")}."
    }

    /** "Liquidity 15+5: armed, 2 lots a trade - first trigger: BankNifty 5-min, a close above 54,180 - 60 pts away (it would buy a call)." */
    fun armLine(a: Arm): String {
        val size = a.lots?.let { "$it lot${s(it)} a trade" }
        val state = when (a.armed) {
            true -> "armed" + (size?.let { ", $it" } ?: "")
            false -> "switched off" + (size?.let { " ($it when on)" } ?: "")
            null -> "switch not read" + (size?.let { ", $it" } ?: "")
        }
        val lead = if (a.armed == false) "were it on, its first trigger" else "first trigger"
        val trigger = when {
            a.trigger != null -> "$lead: ${a.trigger}"
            !a.levelsRead -> "its levels not read just now"
            else -> "no break with room ahead just now"
        }
        return "Liquidity 15+5: $state - $trigger."
    }

    /** "Big-move risk for Nifty now: high, about 2.5x the usual - direction can't be told from this." (null: no read). */
    fun bigMoveLine(r: BigMoveRisk.Read?): String? {
        val lvl = r?.level ?: return null
        val x = r.times ?: return null
        return "Big-move risk for ${r.market.label} now: ${lvl.word}, about ${times(x)} the usual - ${BigMoveRisk.CAVEAT}."
    }

    /** Every line, in the order said (at most [MAX_LINES]; [brief]: the first [BRIEF_LINES]). */
    fun lines(f: Facts, brief: Boolean = false): List<String> {
        if (!f.tradingDay) return listOf(CLOSED)
        if (f.now.toLocalTime().isBefore(OPEN)) return listOf(NOT_YET)
        if (!ready(f)) return listOf(NO_CANDLES)
        val out = ArrayList<String>()
        out += gapLine(f)
        f.places.forEach { out += placeLine(it) }
        firstLine(f)?.let { out += it }
        f.arm?.let { out += armLine(it) }
        bigMoveLine(f.bigMove)?.let { out += it }
        return out.take(if (brief) BRIEF_LINES else MAX_LINES)
    }

    /** The read in words, one line each. */
    fun say(f: Facts, brief: Boolean = false): String = lines(f, brief).joinToString("\n")

    // ---- asked ----------------------------------------------------------------------------------------------------

    private fun norm(text: String) = Spaced.joined(text)

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |okay jarvis |boss |so |and |please |ok |okay |hi |hello |tell me |bata |batao )*"
    private const val TAIL = "( boss| jarvis| please| then| now| yaar| na| bhai)* $"
    private const val ASK = "(what is |whats |what s |tell me |give me |show me |say |read me |lets have |lets see |can i have |can you give me |could you give me |what about )?"
    private const val LEVELS = "( (vs|versus|against|compared to|relative to) (the )?(liquidity )?(levels|level))?"
    private val ASKED = Regex(
        // "how did the market open", "how did we open today", "where did we open vs the levels", "how has the market opened"
        "^ $LEAD(how|where) (did|has|have) (the )?(market|markets|indices|we|things) (open|opened)( up)?( today| this morning)?$LEVELS$TAIL|" +
        // "how was the open", "how was today's opening", "how did the open go"
        "^ $LEAD(how was|hows|how s|how is) (the |todays |today s )?(open|opening)( today| this morning)?$TAIL|" +
        "^ $LEAD(how did) (the |todays |today s )?(open|opening) go( today| this morning)?$TAIL|" +
        // "opening read", "give me the opening read", "what's the opening read", "today's opening summary"
        "^ $LEAD$ASK(the |my |todays |today s |an )?(opening|open) (read|report|summary|recap|snapshot|note)( today| please)?$TAIL|" +
        // "market kaisa khula", "aaj market kaisa khula", "market kaise khula aaj", "market kahan khula"
        "^ $LEAD(aaj )?(market|bazaar|bazar|index|indices)( aaj)? (kaisa|kaise|kaisi|kahan|kaha|kidhar) (khula|khuli|khule|open hua|open hui|open kiya)( aaj)?( hai| tha| kya)?$TAIL|" +
        // "open kaisa tha", "aaj ki opening kaisi rahi"
        "^ $LEAD(aaj ka |aaj ki )?(open|opening) (kaisa|kaisi) (tha|thi|raha|rahi|hua|hui)( aaj)?$TAIL"
    )
    /** Never this: another day, a record or a usual, an index named (its own read), a forecast, an act, the session's hours. */
    private val NOT = Regex(" (yesterday|kal|tomorrow|last|week|month|record|history|usually|usual|often|always|typically|nifty|banknifty|finnifty|" +
        "sensex|bank|fin|bnf|vix|gold|will|would|predict|forecast|expect|buy|sell|trade|close|exit|stop|start|arm|remind|alert|when|time|timing|hours) ")

    /** "How did the market open", "opening read", "market kaisa khula", "where did we open vs the levels". */
    fun asked(text: String): Boolean {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return false
        return ASKED.containsMatchIn(t)
    }
}
