package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * India VIX's change against the index's next day (market intelligence, round 21): "when VIX jumps 5% how big is the next
 * day?", "after a VIX spike how much does Nifty move the next day?", "when India VIX falls 5% is the next session
 * quieter?", "VIX next day record for BankNifty", "jab vix 5% uchalta hai to agle din nifty kitna chalta hai". From the
 * whole sessions of 1-minute candles on the phone: each India VIX session's close against the one before it, paired with
 * the index's next whole session - its range (high to low, % of its open) and its close-to-close move. The days VIX rose
 * (or fell) by the size asked (5% when none is) set against every pair: the median next-day range and move, how many of
 * those next days were wider than the median of all (about half would be by chance alone), how they closed; the VIX days
 * themselves beside them, so a next day that is only as wide as the jump day is not passed off as more; and India VIX's
 * own latest change set beside. A record of past days on this phone, said with its counts; never a forecast or advice,
 * nothing acts. The last VIX spike one by one stays [MarketMemory]'s, where VIX stands in its own year [VixRank]'s, a
 * what-if on VIX [Scenarios]'. Pure.
 */
object VixNext {
    /** What was asked: VIX's change of at least [pct] (%), up ([dir] +1), down (-1) or either side (null). */
    data class Q(val dir: Int? = 1, val pct: Double = DEFAULT_PCT)

    /**
     * One India VIX session [day] with its close-to-close change [vixPct], paired with the index's next whole session
     * [next]: its range [rangePct] (% of its open) and its move [movePct] from the index's close on [day] (null when the
     * phone has no whole index session that day); [dayRangePct]: the index's own range on [day] (null likewise).
     */
    data class Pair(val day: LocalDate, val vixClose: Double, val vixPct: Double, val next: LocalDate, val rangePct: Double, val movePct: Double?, val dayRangePct: Double?)

    /** Fewer pairs than this: too few to say anything. */
    const val MIN_SESSIONS = 15
    /** Fewer VIX days of the size asked than this: too few to say what followed them. */
    const val MIN_DAYS = 5
    /** At most this many of the newest pairs are read. */
    const val MAX_SESSIONS = 250
    /** VIX's change asked of when no size is named (%). */
    const val DEFAULT_PCT = 5.0
    /** Sessions count as one after the other only when at most this many days apart (a weekend and a holiday). */
    const val MAX_DAYS_APART = 4L
    /** A move from the previous close under this (%) is flat. */
    const val FLAT_PCT = 0.05
    val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the VIX next-day record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold is not measured against India VIX."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun sp1(x: Double) = (if (x >= 0) "+" else "-") + "%.1f%%".format(Locale.ENGLISH, abs(x))
    private fun size(x: Double) = (if (x == Math.floor(x)) "%.0f%%" else "%.1f%%").format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun plural(k: Int, w: String) = if (k == 1) w else w + "s"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** India VIX named. */
    private const val VIX = " (vix|india vix|volatility index|fear gauge|fear index) "
    /** A change in VIX named. */
    private const val CHANGE = " (jump|jumps|jumped|jumping|spike|spikes|spiked|spiking|surge|surges|surged|rise|rises|rose|rising|climb|climbs|climbed|" +
        "shoots up|shot up|goes up|went up|is up|up|pops|popped|soars|soared|fall|falls|fell|falling|drop|drops|dropped|dropping|crash|crashes|crashed|" +
        "cool|cools|cooled|cools off|eases|eased|slides|slid|sinks|sank|goes down|went down|is down|down|change|changes|move|moves|" +
        "uchalta|uchle|uchla|badhta|badhe|badha|girta|gire|gira|tootta) "
    /** The next day named. */
    private const val NEXT = " (next day|next days|next session|next sessions|following day|following session|day after|the day after|day following|" +
        "next day s|agle din|agla din|agle session) "
    /** The size or behaviour of a day asked of. */
    private const val HOW = " (how big|how wide|how large|how much|how far|how often|how many times|range|ranges|move|moves|moved|bigger|wider|larger|" +
        "smaller|narrower|quieter|calmer|volatile|swing|swings|usually|normally|typically|generally|tend to|tends to|on average|record|history|" +
        "historically|stats|statistics|kitna|kitni|kitne|chalta|chalti|hilta|aksar|how does|how do|how did|what happens|what usually happens|" +
        "does nifty (fall|rise|drop|move|close)|does banknifty (fall|rise|drop|move|close)|does the market (fall|rise|drop|move|close)) "
    /** "VIX next day record", "VIX spike follow-through stats": the record named outright. */
    private const val NAMED = " (vix|india vix) (next day|next session|spike|jump|change) (record|stats|statistics|behaviour|behavior|follow through) "
    // Forecasts, advice, Boss's own book, alerts, a single day (and "when did VIX last jump", MarketMemory's), what-ifs,
    // where VIX stands (VixRank's), meanings, news.
    private const val NOT = " (will|would|going to|gonna|tomorrow|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|trade|trades|trading|" +
        "i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|today|aaj|now|right now|abhi|this|yesterday|kal|" +
        "last time|previous time|pichli baar|when did|when was|last|latest|" +
        "alert|alerts|notify|remind|reminder|warn|ping|watch|arm|arms|bot|bots|algo|algos|strategy|strategies|backtest|pine|news|" +
        "percentile|rank|high or low|cheap|expensive|premium|premiums|iv|" +
        "mean|means|meaning|define|explain|what is|what s|what are) "

    /** What was asked, or null. A record of past VIX days only: never a forecast, advice, an alert or today's own move. */
    fun asked(text: String): Q? {
        val t = norm(text)
        if (rx(NOT).containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        if (!rx(VIX).containsMatchIn(t)) return null
        val named = rx(NAMED).containsMatchIn(t)
        if (!named && !(rx(NEXT).containsMatchIn(t) && rx(CHANGE).containsMatchIn(t) && rx(HOW).containsMatchIn(t))) return null
        return Q(dir(t), pct(text))
    }

    /** The size named ("5%", "7 percent"), else [DEFAULT_PCT]; only 1% to 50% is read as one. */
    private fun pct(text: String): Double {
        val m = rx("(\\d+(?:\\.\\d+)?)\\s*(%|percent|per cent|pc)").find(text.lowercase(Locale.ENGLISH)) ?: return DEFAULT_PCT
        return m.groupValues[1].toDoubleOrNull()?.takeIf { it in 1.0..50.0 } ?: DEFAULT_PCT
    }

    /** VIX's side asked of: a rise +1, a fall -1, both or neither named null ("a change", "moves"): both sides said. A spike is a rise. */
    private fun dir(t: String): Int? {
        // The index's own side ("Nifty falls the next day") is not VIX's: only the words from VIX's name to the next day count.
        val from = rx(VIX).find(t)?.range?.first ?: 0
        val to = rx(NEXT).find(t, from)?.range?.first ?: t.length
        val s = t.substring(from, to) + " "
        val up = rx(" (jump|jumps|jumped|jumping|spike|spikes|spiked|spiking|surge|surges|surged|rise|rises|rose|rising|climb|climbs|climbed|shoots up|shot up|" +
            "goes up|went up|is up|up|pops|popped|soars|soared|uchalta|uchle|uchla|badhta|badhe|badha) ").find(s)?.range?.first
        val down = rx(" (fall|falls|fell|falling|drop|drops|dropped|dropping|crash|crashes|crashed|cool|cools|cooled|eases|eased|slides|slid|sinks|sank|" +
            "goes down|went down|is down|down|girta|gire|gira|tootta) ").find(s)?.range?.first
        // The first side named after VIX is VIX's ("VIX jumps... does Nifty fall the next day": a jump).
        return when { up == null && down == null -> null; down == null -> 1; up == null -> -1; up < down -> 1; else -> -1 }
    }

    /** The index asked about (Nifty when none is named; India VIX itself is the measure, not the index), or null for gold. */
    fun market(markets: List<Market>): Market? {
        if (markets.any { it == Market.GOLD } && markets.none { it != Market.GOLD && it != Market.VIX }) return null
        return markets.firstOrNull { it != Market.GOLD && it != Market.VIX } ?: Market.NIFTY
    }

    // ---- the reading ----------------------------------------------------------------------------------------------

    private fun whole(s: MarketStory.Session) = s.bars.isNotEmpty() && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) &&
        !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    private fun apart(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b)

    /**
     * Each whole India VIX session before [today] in [vix] with a whole one at most [MAX_DAYS_APART] days before it, paired
     * with [bars]' next whole session after it (within [MAX_DAYS_APART] days, no VIX session between, before [today]);
     * newest [MAX_SESSIONS], oldest first.
     */
    fun pairs(bars: List<Candle>, vix: List<Candle>, today: LocalDate): List<Pair> {
        val vs = MarketStory.sessions(vix).filter { it.day.isBefore(today) && whole(it) }
        val idx = MarketStory.sessions(bars).filter { it.day.isBefore(today) && whole(it) }
        val byDay = idx.associateBy { it.day }
        val vixDays = vs.map { it.day }.toSet()
        val out = ArrayList<Pair>()
        for (i in 1 until vs.size) {
            val v = vs[i]; val pv = vs[i - 1]
            if (apart(pv.day, v.day) > MAX_DAYS_APART || pv.close <= 0 || v.close <= 0) continue
            val next = idx.firstOrNull { it.day.isAfter(v.day) } ?: continue
            if (apart(v.day, next.day) > MAX_DAYS_APART) continue
            if (vixDays.any { it.isAfter(v.day) && it.isBefore(next.day) }) continue
            if (next.open <= 0) continue
            val same = byDay[v.day]
            out += Pair(v.day, v.close, (v.close - pv.close) / pv.close * 100, next.day, next.range / next.open * 100,
                same?.takeIf { it.close > 0 }?.let { (next.close - it.close) / it.close * 100 }, same?.takeIf { it.open > 0 }?.let { it.range / it.open * 100 })
        }
        return out.takeLast(MAX_SESSIONS)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /**
     * [q] answered for [m] from [bars] (its 1-minute candles over several days) and [vix] (India VIX's) at [now] on [today].
     */
    fun answer(q: Q, m: Market, bars: List<Candle>, vix: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val ps = pairs(bars, vix, today)
        if (ps.size < MIN_SESSIONS)
            return "I have only ${ps.size} India VIX ${plural(ps.size, "day")} on the phone with ${m.label}'s next whole session beside ${if (ps.size == 1) "it" else "them"}, Boss - " +
                "too few to say how the day after a VIX move went (I need $MIN_SESSIONS)."
        val lines = ArrayList<String>()
        val allRange = median(ps.map { it.rangePct })
        val moves = ps.mapNotNull { it.movePct }
        lines += "Over the last ${ps.size} India VIX sessions on this phone with ${m.label}'s next whole session beside them (${date(ps.first().day)} to ${date(ps.last().day)}), " +
            "the next day's range (high to low) had a median of ${p2(allRange)} of the open" +
            (if (moves.isNotEmpty()) " and its move from the close before a median of ${p2(median(moves.map { abs(it) }))} either way." else ".")
        val sides = when (q.dir) { null -> listOf(1, -1); else -> listOf(q.dir) }
        for (d in sides) lines += sideLine(d, q.pct, ps, allRange, m)
        val big = ps.maxBy { it.vixPct }; val small = ps.minBy { it.vixPct }
        lines += "The biggest VIX rise among them was ${sp1(big.vixPct)} on ${date(big.day)}, and ${m.label}'s next session (${date(big.next)}) ranged ${p2(big.rangePct)}; " +
            "the biggest fall ${sp1(small.vixPct)} on ${date(small.day)}, the next session ranging ${p2(small.rangePct)}."
        todayLine(q, vix, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    private fun sideLine(d: Int, pct: Double, ps: List<Pair>, allRange: Double, m: Market): String {
        val w = if (d == 1) "rose" else "fell"
        val part = ps.filter { if (d == 1) it.vixPct >= pct else it.vixPct <= -pct }
        if (part.size < MIN_DAYS)
            return "India VIX $w ${size(pct)} or more on only ${part.size} of them - too few to say how the next day went (I need $MIN_DAYS)."
        val r = median(part.map { it.rangePct })
        val wider = part.count { it.rangePct > allRange }
        val mv = part.mapNotNull { it.movePct }
        val up = mv.count { it >= FLAT_PCT }; val down = mv.count { it <= -FLAT_PCT }
        val own = part.mapNotNull { it.dayRangePct }
        return "On the ${part.size} days India VIX $w ${size(pct)} or more, ${m.label}'s next session ranged a median ${p2(r)} (against ${p2(allRange)} for all), " +
            "wider than that all-day median on $wider of ${part.size} (${share(wider, part.size)}; about half would be by chance alone)" +
            (if (mv.isNotEmpty()) ", moved a median ${p2(median(mv.map { abs(it) }))} from the close before, and closed up on $up and down on $down" else "") +
            (if (own.size >= MIN_DAYS) "; the VIX days themselves had ranged a median ${p2(median(own))}." else ".")
    }

    /** India VIX's latest change set beside the record, or null with too little of it. */
    private fun todayLine(q: Q, vix: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val ss = MarketStory.sessions(vix)
        val last = ss.lastOrNull { !it.day.isAfter(today) && it.bars.isNotEmpty() } ?: return null
        val prev = ss.lastOrNull { it.day.isBefore(last.day) && whole(it) }?.takeIf { apart(it.day, last.day) <= MAX_DAYS_APART && it.close > 0 } ?: return null
        val ch = (last.close - prev.close) / prev.close * 100
        val live = last.day == today && now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val sized = when (q.dir) { 1 -> ch >= q.pct; -1 -> ch <= -q.pct; else -> abs(ch) >= q.pct }
        val head = if (last.day == today) (if (live) "Today India VIX is ${sp1(ch)} so far at ${n(last.close)}" else "Today India VIX closed ${sp1(ch)} at ${n(last.close)}")
            else "India VIX's last session on the phone (${date(last.day)}) closed ${sp1(ch)} at ${n(last.close)}"
        return "$head - ${if (sized) "a move of the size asked about" else "under the ${size(q.pct)} asked about"}" +
            (if (live) ", with the session still on." else ".")
    }
}
