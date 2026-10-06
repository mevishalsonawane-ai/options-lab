package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * "Is my stop too tight?" (reasoning, round 33, 2026-10-06): each open bought option's stop set against how far its index
 * usually swings over the next quarter and half hour. The other reads say where the stop is ([AtStops]: what it costs if
 * hit; [Exposure]: whether a move asked would reach it; [NeedsTrue]: what the trade needs), never whether a stop of that
 * distance sits inside the index's ordinary noise. This does, from facts only:
 *
 *  - the stop's distance from the option's price now, in premium and as a share of it;
 *  - roughly in index points: the index move against the option (a fall for a call, a rise for a put) that takes the
 *    option's estimated price ([Exposure.price], repriced at its own IV where known, else delta and gamma; IV and time
 *    held still) down to the stop;
 *  - how often the index moved at least that far against it within 15 and within 30 minutes of a quarter-hour start in
 *    the whole past sessions of 1-minute candles on the phone ([MoveTime.sessions]), read as a share of the start price so
 *    an older, lower index counts at today's size, and the median such move in 30 minutes beside the stop's distance.
 *
 * A record of the index's past moves set against the stop through today's delta - never a forecast, never a judgement that
 * the stop is right or wrong and never advice to change it: where a stop sits is Boss's call. Only bought options (a
 * short's stop is said as not read here); a stop already crossed is said so. His positions, so the hub asks for this only
 * on an unlocked phone ([LOCKED]); gold is never read here. Nothing places, changes or cancels an order. Pure.
 */
object StopNoise {
    /** The windows read, minutes after a start. */
    val WINDOWS = listOf(15, 30)
    /** Fewer whole sessions than this: too few to say anything ([MoveTime.MIN_SESSIONS]). */
    const val MIN_SESSIONS = MoveTime.MIN_SESSIONS
    /** Fewer whole sessions than this: said as a small record. */
    const val FEW_SESSIONS = MoveTime.FEW_SESSIONS
    /** At most this many positions are read out; the rest are counted. */
    const val MAX_LEGS = 5
    /** A delta smaller than this (in size) is too small to turn a premium distance into index points. */
    const val MIN_DELTA = 0.02
    /** The furthest index move tried when looking for the stop, a share of the index (15%). */
    private const val FURTHEST = 0.15
    /** The starts: each quarter hour from 9:30 to 15:00, as [MoveTime] takes them. */
    private val STARTS: List<LocalTime> = generateSequence(MoveTime.FIRST_START) { it.plusMinutes(15) }.takeWhile { !it.isAfter(MoveTime.LAST_START) }.toList()

    const val LOCKED = "Your positions stay out of it on a locked phone, Boss - unlock it for that."
    const val CLOSING = "A record of the index's past moves set against each stop through today's delta (IV and time held still), not a " +
        "forecast - and a stop is a trigger, not a price: a gap or a fast market fills past it. Where your stop sits is your call, Boss."

    // ---- the question -----------------------------------------------------------------------------------------------

    private const val STOP = "(stop|stops|stop loss|stop losses|stoploss|stoplosses|stop-loss|sl|sls)"
    private const val TIGHT = "(tight|close|near|narrow|small|short|closely set|close in)"
    private const val SET = "((set|placed|kept|sitting) )?"
    private const val VERY = "(too |very |so |a bit |a little |bit |quite |really |way too |rather |overly )"

    private val ASK = rx(
        // "Is my stop too tight?", "are my stops too close?", "isn't my SL a bit tight?"
        " (is|are|isnt|arent) (my|our) (\\w+ ){0,3}?$STOP $SET$VERY?$TIGHT " +
        // "My stop is too tight?", "my stops are too close, are they?"
        "| (my|our) (\\w+ ){0,3}?$STOP (is|are|seems|seem|looks|look|feels|feel) $VERY$TIGHT " +
        // "Is my stop inside the noise?", "are my stops within normal swings?"
        "| (is|are) (my|our) (\\w+ ){0,3}?$STOP (inside|within|in) (the )?(normal |usual |ordinary |everyday |daily |intraday )?(noise|swings|wiggle|wiggles|chop|range) " +
        // "Will normal noise hit my stop?", "can an ordinary swing take out my SL?"
        "| (will|would|can|could|does|do) (the )?(normal |usual |ordinary |everyday |regular |typical )(noise|swing|swings|moves|wiggle|wiggles|chop) (hit|take out|trigger|knock out|reach|tag) (my|our) $STOP " +
        // "How much room does my stop have?", "how much room do my stops give?"
        "| how much (room|breathing room|space|buffer|cushion) (does|do|is there in|is in) (my|our) (\\w+ ){0,3}?$STOP (have|give|leave|got)? ?" +
        // Named: "my stop noise check", "check my stops against the noise" (a bare "stop ..." stays the command it reads as)
        "| (my|our) $STOP (noise|tightness|room) (check|record|read) | (check|compare) (my|our) $STOP (against|with|to) (the )?(normal |usual )?(noise|swings) " +
        // Hinglish: "mera stop bahut tight hai kya", "kya meri SL zyada paas hai", "mera sl tight to nahi"
        "| (mera|meri|mere|apna|apni|apne) $STOP (bahut |bohot |bahot |zyada |jyada |kaafi |kafi |thoda |thodi |jada )?(tight|close|paas|pass|nazdeek|najdeek|kareeb|karib|chhota|chota)( (hai|he|h|hain|to nahi|toh nahi|nahi))? ")

    /** Something else is meant: advice or an order, a bot's stop, gold, a definition, a past stop hit, or the worst case. */
    private val NOT = rx(" (should|shall|recommend|suggest|advise|advice|ideal|best|where to|where should|how far should|kahan rakhu|kaha rakhu|kahan lagau|" +
        "move|moving|setting|change|changing|modify|widen|widening|loosen|loosening|tighten|tightening|trail|trailing|place|cancel|remove|raise|lower|shift|" +
        "close to being|close to getting|close to hit|close to hitting|close to triggering|" +
        "karo|kar do|kardo|badha do|ghata do|hata do|laga do|rakh do|bot|bots|strategy|strategies|orb|arm|arms|algo|gold|" +
        "what is|whats a|meaning|define|explain|did|was|were|hit today|got hit|worst case|kitna loss|how much do i lose) ")

    private fun norm(text: String) = Spaced.joined(text)

    /** Does [text] ask whether his own stops sit inside the index's ordinary swings? Never an order, advice or a bot's stop. */
    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return false
        if (Market.GOLD in Market.mentioned(text)) return false
        return ASK.containsMatchIn(t)
    }

    // ---- the record -------------------------------------------------------------------------------------------------

    /**
     * The index's moves after each quarter-hour start, as shares of the start price (%): [falls] and [rises] by window
     * (the furthest the 1-minute lows and highs went from the start's close within it), over [days] whole sessions from
     * [first] to [last]; [lastClose] the newest session's close.
     */
    data class Record(val falls: Map<Int, List<Double>>, val rises: Map<Int, List<Double>>, val days: Int,
                      val first: LocalDate?, val last: LocalDate?, val lastClose: Double?)

    /** The record from [bars] (an index's 1-minute candles), the whole sessions before [today] ([MoveTime.sessions]). */
    fun record(bars: List<Candle>, today: LocalDate): Record {
        val ss = MoveTime.sessions(bars, today)
        val falls = WINDOWS.associateWith { ArrayList<Double>() }
        val rises = WINDOWS.associateWith { ArrayList<Double>() }
        for (s in ss) {
            val bs = s.bars
            val end = bs.last().t.toLocalTime().toSecondOfDay()
            for (st in STARTS) {
                val i = bs.indexOfFirst { it.t.toLocalTime() == st }
                if (i < 0) continue
                val from = bs[i].c
                if (!(from > 0)) continue
                for (w in WINDOWS) {
                    if (end - st.toSecondOfDay() < w * 60) continue
                    val until = st.toSecondOfDay() + w * 60
                    var lo = from; var hi = from
                    var j = i + 1
                    while (j < bs.size && bs[j].t.toLocalTime().toSecondOfDay() <= until) {
                        lo = minOf(lo, bs[j].l); hi = maxOf(hi, bs[j].h); j++
                    }
                    falls.getValue(w) += (from - lo) / from * 100
                    rises.getValue(w) += (hi - from) / from * 100
                }
            }
        }
        return Record(falls, rises, ss.size, ss.firstOrNull()?.day, ss.lastOrNull()?.day, ss.lastOrNull()?.bars?.lastOrNull()?.c)
    }

    /** The indices of [legs]' bought options with a stop, whose records are asked for. */
    fun markets(legs: List<Exposure.Leg>): List<Market> = legs.filter { read(it) }.mapNotNull { index(it) }.distinct()

    private fun index(l: Exposure.Leg): Market? = MoveTime.INDICES.firstOrNull { it.name.equals(l.underlying, true) }

    private fun stopOf(l: Exposure.Leg): Double? = l.stop?.takeIf { it.isFinite() && it > 0 }

    /** A bought option with a stop of its own. */
    private fun read(l: Exposure.Leg) = l.qty > 0 && Exposure.option(l) && stopOf(l) != null

    /**
     * The index move against [l] (points, > 0) that takes its estimated price ([Exposure.price]) down to [stop], or null
     * when none up to [FURTHEST] of [spot] does. [against]: -1 a fall (a call's), +1 a rise (a put's).
     */
    fun pointsTo(l: Exposure.Leg, stop: Double, against: Int, spot: Double): Double? {
        val far = spot * FURTHEST
        if (Exposure.price(l, against * far) > stop) return null
        var lo = 0.0; var hi = far
        repeat(60) {
            val mid = (lo + hi) / 2
            if (Exposure.price(l, against * mid) <= stop) hi = mid else lo = mid
        }
        return hi
    }

    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun median(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    /** One leg's line, against its index's [rec] at [spot] (the index now). */
    fun line(l: Exposure.Leg, m: Market, rec: Record?, spot: Double?): String {
        val stop = stopOf(l)!!
        val head = "${l.where} ${l.symbol} (${l.qty}, now ${AppFacts.px(l.ltp)})"
        if (l.ltp <= stop) return "$head is already at or below its ${AppFacts.px(stop)} stop."
        val gap = l.ltp - stop
        val pct = gap / l.ltp * 100
        val dist = "its ${AppFacts.px(stop)} stop is ${AppFacts.px(gap)} below the price (%.0f%% of the premium)".format(Locale.ENGLISH, pct)
        val d = l.delta
        val spotNow = l.model?.forward ?: spot ?: rec?.lastClose
        if (d == null || abs(d) < MIN_DELTA || spotNow == null || !(spotNow > 0))
            return "$head: $dist; its delta or ${m.label}'s price is not known just now, so I can't put that in ${m.label} points."
        val against = if (d > 0) -1 else 1
        val way = if (against < 0) "fall" else "rise"
        val x = pointsTo(l, stop, against, spotNow)
            ?: return "$head: $dist; by this estimate a ${m.label} $way alone of up to ${pts(spotNow * FURTHEST)} points would not take it " +
                "there (time and IV would)."
        val px = x / spotNow * 100
        val core = "$head: $dist - about ${pts(x)} ${m.label} points of $way by this estimate (delta ${"%.2f".format(Locale.ENGLISH, d)})"
        if (rec == null || rec.days < MIN_SESSIONS)
            return "$core. I have only ${rec?.days ?: 0} whole ${m.label} session${if (rec?.days == 1) "" else "s"} on the phone - " +
                "too few to say how often a move that size came (I need $MIN_SESSIONS)."
        val moves = if (against < 0) rec.falls else rec.rises
        val by = WINDOWS.mapNotNull { w ->
            val xs = moves[w].orEmpty()
            if (xs.isEmpty()) null else "within $w minutes on ${share(xs.count { it >= px }, xs.size)} of ${"%,d".format(Locale.ENGLISH, xs.size)}"
        }
        if (by.isEmpty()) return "$core. None of the ${m.label} sessions on the phone had a start to read, Boss."
        val med = median(moves[WINDOWS.last()].orEmpty())?.let { it * spotNow / 100 }
        val medSaid = med?.let {
            val times = if (it > 0) x / it else null
            "; its median $way within ${WINDOWS.last()} minutes was about ${pts(it)} points" +
                (times?.let { r -> ", the stop's distance %.1f times that".format(Locale.ENGLISH, r) } ?: "")
        } ?: ""
        return "$core. A ${m.label} $way that far from a quarter-hour start came ${by.joinToString(" and ")} starts$medSaid."
    }

    /**
     * The answer: each bought option with a stop against its index's record ([records], by index; [spots], the indices
     * now), worst-placed first; the bought options with no stop and the shorts said as not read.
     */
    fun answer(legs: List<Exposure.Leg>, records: Map<Market, Record>, spots: Map<Market, Double?>): List<String> {
        val options = legs.filter { Exposure.option(it) && it.qty != 0 }
        if (options.isEmpty()) return listOf("You have no open options, Boss, so there is no stop to set against the index's swings.")
        val withStop = options.filter { read(it) && index(it) != null }
        val out = ArrayList<String>()
        if (withStop.isEmpty()) {
            out += "None of your open bought options on Nifty, BankNifty, FinNifty or Sensex has a stop the app keeps, Boss, so there is nothing to set against the swings."
        } else {
            // The closest stops (as a share of the premium) first.
            val sorted = withStop.sortedBy { (it.ltp - stopOf(it)!!) / it.ltp }
            for (l in sorted.take(MAX_LEGS)) {
                val m = index(l)!!
                out += line(l, m, records[m], spots[m])
            }
            if (sorted.size > MAX_LEGS) out += "And ${sorted.size - MAX_LEGS} more with a stop."
            val rs = withStop.mapNotNull { index(it) }.distinct().mapNotNull { m -> records[m]?.takeIf { it.days >= MIN_SESSIONS }?.let { m to it } }
            rs.firstOrNull()?.let { (m, r) ->
                out += "Starts each quarter hour from 9:30 to 15:00 over ${r.days} whole ${m.label} sessions on this phone" +
                    (if (r.first != null && r.last != null) " (${date(r.first)} to ${date(r.last)})" else "") +
                    "; the starts in one session overlap, so they are not separate chances." +
                    (if (r.days < FEW_SESSIONS) " ${r.days} sessions is a small record." else "")
            }
        }
        val noStop = options.filter { it.qty > 0 && stopOf(it) == null }
        if (noStop.isNotEmpty()) out += "No stop kept on: " + noStop.take(4).joinToString(", ") { "${it.where} ${it.symbol}" } +
            (if (noStop.size > 4) " and ${noStop.size - 4} more" else "") + "."
        val shorts = options.filter { it.qty < 0 }
        if (shorts.isNotEmpty()) out += "Not read here: your short option${if (shorts.size > 1) "s" else ""} (" +
            shorts.take(4).joinToString(", ") { "${it.where} ${it.symbol}" } + (if (shorts.size > 4) " and ${shorts.size - 4} more" else "") + ") - this reads bought options only."
        val elsewhere = options.filter { read(it) && index(it) == null }
        if (elsewhere.isNotEmpty()) out += "Not read here: " + elsewhere.take(4).joinToString(", ") { "${it.where} ${it.symbol}" } +
            " (not on an index whose record the phone keeps)."
        if (withStop.isNotEmpty()) out += CLOSING
        return out
    }
}
