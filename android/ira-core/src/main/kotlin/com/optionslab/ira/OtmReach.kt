package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The out-of-the-money option's record (market intelligence, round 43): "how often does an OTM option 100 points away end the
 * day in the money?", "how often does a call two strikes out of the money finish in the money?", "how often does Nifty's OTM
 * put double?", "OTM option record for BankNifty", "100 point door ka otm call kitni baar itm hota hai". What an option buyer
 * asks before paying the cheaper premium further out: from the option prices the phone keeps (the bundled and harvested
 * 1-minute option candles, and the chain read today), each past session read on its own - the index at 9:30, the nearest
 * expiry's out-of-the-money calls and puts priced then (their 9:30 price, the best after - the candle's high where kept - and
 * the end of the day), the index's high and low after 9:30 and its end of the day. For the distance asked (points from the
 * index at 9:30, or strikes from the at-the-money strike; two strikes when neither is said): how often the strike ended the
 * day in the money, how often the index reached it at some point, how often the option doubled from 9:30 and ended worth
 * more, and the median end-of-day change. Expiry days (the contract expiring that day) counted apart. Beside today's legs
 * so far. A record of past sessions on this phone, said with its counts and plainly when there are too few; never a forecast
 * or advice; nothing acts. The at-the-money option stays [AtmBuy]'s, how far the index goes from the open [OpenReach]'s.
 * Pure.
 */
object OtmReach {
    /**
     * What was asked: [right] the call or the put alone (null: both), [points] from the index at 9:30 or [strikes] from the
     * at-the-money strike (neither: [DEFAULT_STRIKES] strikes), [expiry] expiry days themselves, [lead] what leads.
     */
    data class Q(val right: Right? = null, val points: Double? = null, val strikes: Int? = null, val expiry: Boolean = false, val lead: Aim = Aim.ITM)

    /** The figure asked first: ending the day in the money, or doubling. */
    enum class Aim { ITM, DOUBLE }

    /** One option held from 9:30: its [strike], its price then ([entry]), the best after ([best]) and at the end ([end]). */
    data class Leg(val strike: Double, val entry: Double, val best: Double, val end: Double) {
        val up: Boolean get() = end > entry
        val doubled: Boolean get() = best >= 2 * entry
        /** The end-of-day change, as a share of the 9:30 price. */
        val change: Double get() = (end - entry) / entry
    }

    /**
     * One past session: its [day], whether the contract expired that day ([expiry]), the index at 9:30 ([spot]), its high
     * and low after ([high], [low]) and at the end of the day ([close]), the strike [step], the at-the-money [atm] strike,
     * and the out-of-the-money [calls] and [puts] by strike.
     */
    data class Day(val day: LocalDate, val expiry: Boolean, val spot: Double, val high: Double, val low: Double, val close: Double,
                   val step: Double, val atm: Double, val calls: Map<Double, Leg>, val puts: Map<Double, Leg>) {
        /** The [r] leg [q] asks about this day, or null when the phone keeps no price for it. */
        fun leg(r: Right, q: Q): Leg? {
            val legs = if (r == Right.PE) puts else calls
            val p = q.points
            return if (p != null) {
                if (r == Right.PE) legs.keys.filter { it <= spot - p }.maxOrNull()?.let { legs[it] }
                else legs.keys.filter { it >= spot + p }.minOrNull()?.let { legs[it] }
            } else {
                val n = q.strikes ?: DEFAULT_STRIKES
                val k = if (r == Right.PE) atm - n * step else atm + n * step
                legs.entries.firstOrNull { abs(it.key - k) < 1e-6 }?.value
            }
        }
        /** [l] (an [r] leg) ended the day in the money. */
        fun itm(r: Right, l: Leg): Boolean = if (r == Right.PE) close < l.strike else close > l.strike
        /** The index reached [l]'s strike at some point after 9:30. */
        fun reached(r: Right, l: Leg): Boolean = if (r == Right.PE) low <= l.strike else high >= l.strike
    }

    /** The read starts here (minute of the IST day): 9:30, after the opening swings. */
    const val FROM = 9 * 60 + 30
    /** The end of the day: the last minute of the session. */
    const val END = 15 * 60 + 29
    /** A price older than this many minutes is no price for that time. */
    const val STALE = 10
    /** Strikes out of the money when no distance is said. */
    const val DEFAULT_STRIKES = 2
    /** At most this many strikes out of the money are asked or kept. */
    const val MAX_STRIKES = 12
    /** Fewer sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 10
    /** Fewer sessions than this: said as a small record. */
    const val FEW_SESSIONS = 30
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250

    const val NOTE = "A record of past sessions on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the out-of-the-money option record for the index options only, Boss - Nifty, BankNifty, FinNifty and Sensex, as far as the phone keeps their option prices: gold's options are not kept here, and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun pc(x: Double) = "%d%%".format(Locale.ENGLISH, Math.round(abs(x) * 100))
    private fun px(x: Double) = "%,.1f".format(Locale.ENGLISH, x)
    private fun k(x: Double) = if (x % 1.0 == 0.0) "%,.0f".format(Locale.ENGLISH, x) else "%,.1f".format(Locale.ENGLISH, x)
    private fun count(x: Int) = "%,d".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun hm(minute: Int) = "%02d:%02d".format(Locale.ENGLISH, minute / 60, minute % 60)
    /** "down 34%" / "up 12%" / "flat". */
    private fun moved(x: Double) = when { Math.round(abs(x) * 100) == 0L -> "flat"; x < 0 -> "down ${pc(x)}"; else -> "up ${pc(x)}" }
    private fun side(r: Right) = if (r == Right.PE) "put" else "call"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** Out of the money, said. */
    private val OTM = Regex(" (otm|out of the money|out of money|out the money) ")
    /** An option. */
    private val OPTION = Regex(" (option|options|call|calls|put|puts|ce|pe|strike|strikes|contract|contracts|premium|premiums) ")
    private val CALL = Regex(" (call|calls|ce) ")
    private val PUT = Regex(" (put|puts|pe) ")
    /** A distance: "100 points away", "two strikes out", "3 strikes otm", "100 point door". */
    private const val NUM = "(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|ek|do|teen|char|paanch|panch)"
    private val POINTS = Regex(" $NUM (point|points|pts|pt) (away|out|otm|out of the money|door|dur|above|below|from the money|from atm|from the atm|from spot) | " +
        "(otm|out of the money) by $NUM (point|points|pts) | $NUM (point|points|pts) (otm|out of the money) ")
    private val STRIKES = Regex(" $NUM (strike|strikes) (away|out|otm|out of the money|door|dur|above|below|from the money|from atm|from the atm) | " +
        "(otm|out of the money) by $NUM (strike|strikes) ")
    /** Ending the day in the money. */
    private val ITM = Regex(" (in the money|itm|in money|expire in the money|expires in the money|finish in the money|finishes in the money|" +
        "end up in the money|ends up in the money|come into the money|comes into the money|get into the money|gets into the money) ")
    private val DOUBLE = Regex(" (double|doubles|doubled|doubling|2x|two times|twice its|twice the|do guna|dugna|double hota|double hoti) ")
    /** Asked of the record. */
    private val HOW = Regex(" (how often|how many times|how many days|how many sessions|what share|what percent|what percentage|usually|normally|typically|" +
        "generally|tend to|tends to|historically|hit rate|success rate|win rate|kitni baar|kitne din|aksar|often) ")
    /** Named as a record. */
    private val NAME = Regex(" (otm|out of the money) (option |call |put )?(record|records|stats|statistics|history|hit rate|success rate) ")
    private val EXPIRY = Regex(" (expiry day|expiry days|on expiry|expiry ke din|expiry wale din) ")
    // A forecast or advice, a seller's or writer's question, Boss's own book, a what-if, alerts, the app's bots, a
    // definition, a reason, today, now or one past day, the at-the-money option (AtmBuy's), spreads, the expected move,
    // max pain and OI, gold or VIX, Jarvis or the app.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|tomorrows|kal|next|predict|prediction|forecast|outlook|expect|expected|should|shall|" +
        "sell|selling|sold|seller|sellers|short|shorting|write|writing|writer|writers|i|me|my|mine|we|our|if|what if|suppose|imagine|scenario|agar|jab|when|" +
        "remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|backtest|mean|means|meaning|define|explain|what is|what are|why|kyun|kyon|kyu|reason|" +
        "today|todays|aaj|aj|now|right now|abhi|so far|this|that|last|past|previous|prev|recent|recently|yesterday|yesterdays|did|was|were|" +
        "atm|at the money|straddle|straddles|strangle|strangles|spread|spreads|condor|condors|butterfly|imply|implies|implied|expected move|iv|implied volatility|" +
        "max pain|oi|open interest|pcr|gold|vix|fear|position|positions|portfolio|stop|stops|sl|target|targets|news|order|orders|you|your|jarvis|app|" +
        "weekend|holiday|holidays|price now|quote|ltp|delta|theta|gamma|vega|greeks) ")

    private fun number(s: String): Int? = s.toIntOrNull() ?: when (s) {
        "one", "ek" -> 1; "two", "do" -> 2; "three", "teen" -> 3; "four", "char" -> 4; "five", "paanch", "panch" -> 5
        "six" -> 6; "seven" -> 7; "eight" -> 8; "nine" -> 9; "ten" -> 10; else -> null
    }

    /** What was asked, or null: the record of an out-of-the-money option held from 9:30, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD || it == Market.VIX }) return null
        val pm = POINTS.find(t)
        val sm = STRIKES.find(t)
        val far = OTM.containsMatchIn(t) || ((pm != null || sm != null) && OPTION.containsMatchIn(t))
        val d = DOUBLE.find(t)?.range?.first
        val i = ITM.find(t)?.range?.first
        val ok = NAME.containsMatchIn(t) || (far && OPTION.containsMatchIn(t) && (d != null || i != null) && HOW.containsMatchIn(t))
        if (!ok) return null
        val points = pm?.groupValues?.drop(1)?.firstNotNullOfOrNull { number(it) }?.toDouble()?.takeIf { it > 0 }
        val strikes = if (points != null) null else sm?.groupValues?.drop(1)?.firstNotNullOfOrNull { number(it) }?.takeIf { it in 1..MAX_STRIKES }
        // A number counts only beside "points" or "strikes" ("do" is Hindi "two" there, never "does").
        val c = CALL.containsMatchIn(t); val p = PUT.containsMatchIn(t)
        val right = when { c && !p -> Right.CE; p && !c -> Right.PE; else -> null }
        val lead = if (d != null && (i == null || d < i)) Aim.DOUBLE else Aim.ITM
        return Q(right, points, strikes, EXPIRY.containsMatchIn(t), lead)
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        if (markets.isEmpty()) return Market.NIFTY
        return markets.firstOrNull { it != Market.GOLD && it != Market.VIX }
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /** [x]'s price at [minute], or null when it has none there no older than [STALE] minutes. */
    private fun at(x: Series, minute: Int): Double? {
        val i = x.lastAtOrBefore(minute)
        if (i < 0 || x.minutes[i] < minute - STALE) return null
        return x.close[i].takeIf { it > 0 }
    }

    /** The best price [x] reached after [FROM] up to [until] (the high where kept, else the close), or null without a bar. */
    private fun best(x: Series, until: Int): Double? {
        var b: Double? = null
        for (i in 0 until x.size) {
            val m = x.minutes[i]
            if (m <= FROM || m > until) continue
            val h = x.high?.get(i)?.takeIf { it > 0 } ?: x.close[i]
            if (b == null || h > b) b = h
        }
        return b
    }

    /** The index's high and low after [FROM] up to [until] (the candle's high and low where kept), or null without a bar. */
    private fun range(x: Series, until: Int): Pair<Double, Double>? {
        var hi: Double? = null; var lo: Double? = null
        for (i in 0 until x.size) {
            val m = x.minutes[i]
            if (m <= FROM || m > until) continue
            val h = x.high?.get(i)?.takeIf { it > 0 } ?: x.close[i]
            val l = x.low?.get(i)?.takeIf { it > 0 } ?: x.close[i]
            if (hi == null || h > hi) hi = h
            if (lo == null || l < lo) lo = l
        }
        return if (hi == null || lo == null) null else hi to lo
    }

    /** [x] held from [FROM] to [until], or null without its prices. */
    private fun leg(x: Series, until: Int): Leg? {
        val entry = at(x, FROM) ?: return null
        val end = at(x, until) ?: return null
        val top = maxOf(best(x, until) ?: end, end)
        return Leg(x.strike, entry, top, end)
    }

    /** The usual gap between neighbouring strikes of [ks] (sorted), or null with fewer than two. */
    private fun step(ks: List<Double>): Double? = ks.zipWithNext { a, b -> b - a }.filter { it > 0 }
        .groupingBy { Math.round(it * 100) }.eachCount().maxWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { -it.key })?.key?.div(100.0)

    /** [s]'s out-of-the-money options from 9:30 to [until], or null without the index's or the chain's prices. */
    private fun read(s: Session, until: Int): Day? {
        val ix = s.index ?: return null
        val spot = at(ix, FROM) ?: return null
        val close = at(ix, until) ?: return null
        val (hi, lo) = range(ix, until) ?: return null
        val opts = s.options
        val expiry = opts.mapNotNull { it.expiry }.filter { !it.isBefore(s.day) }.minOrNull() ?: return null
        val near = opts.filter { it.expiry == expiry }
        val ks = near.map { it.strike }.distinct().sorted()
        val step = step(ks) ?: return null
        val atm = ks.minByOrNull { abs(it - spot) } ?: return null
        val calls = near.filter { it.right == Right.CE && it.strike > atm && it.strike <= atm + MAX_STRIKES * step + 1e-6 }
            .mapNotNull { x -> leg(x, until) }.associateBy { it.strike }
        val puts = near.filter { it.right == Right.PE && it.strike < atm && it.strike >= atm - MAX_STRIKES * step - 1e-6 }
            .mapNotNull { x -> leg(x, until) }.associateBy { it.strike }
        if (calls.isEmpty() && puts.isEmpty()) return null
        return Day(s.day, expiry == s.day, spot, maxOf(hi, close), minOf(lo, close), close, step, atm, calls, puts)
    }

    /** [s]'s out-of-the-money calls and puts from 9:30 to the end of the day, or null without the prices. */
    fun day(s: Session): Day? = read(s, END)

    /**
     * Each session before [today] in [sessions] read down to its [Day] (streamed: each session is let go once read), the
     * newest [MAX_SESSIONS], oldest first.
     */
    fun days(sessions: Sequence<Session>, today: LocalDate): List<Day> {
        val out = java.util.TreeMap<LocalDate, Day>()
        for (s in sessions) {
            if (!s.day.isBefore(today)) continue
            day(s)?.let { out[s.day] = it }
            if (out.size > MAX_SESSIONS) out.remove(out.firstKey())
        }
        return out.values.toList()
    }

    private fun median(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** How far out [q] asks, in words. */
    private fun distance(q: Q, r: Right): String {
        val p = q.points
        if (p != null) return "the first strike at least ${k(p)} points ${if (r == Right.PE) "below" else "above"} the index at 9:30"
        val n = q.strikes ?: DEFAULT_STRIKES
        return "$n strike${if (n == 1) "" else "s"} ${if (r == Right.PE) "below" else "above"} the at-the-money strike at 9:30"
    }

    /**
     * [m]'s out-of-the-money option record from [past] (its [days]) for [q], beside [todays] session (the chain kept today,
     * or null) at [now] on [today].
     */
    fun answer(q: Q, m: Market, past: List<Day>, todays: Session?, today: LocalDate, now: LocalDateTime): String {
        val ds = past.filter { it.expiry == q.expiry && it.day.isBefore(today) }
        val kind = if (q.expiry) "expiry-day " else ""
        val rights = q.right?.let { listOf(it) } ?: listOf(Right.CE, Right.PE)
        val held = rights.associateWith { r -> ds.mapNotNull { d -> d.leg(r, q)?.let { d to it } } }
        val n = held.values.maxOf { it.size }
        if (n < MIN_SESSIONS) {
            val other = past.count { it.expiry != q.expiry && it.day.isBefore(today) }
            val that = if (ds.size >= MIN_SESSIONS) " with a price kept for a strike that far out" else ""
            return "I have only $n ${kind}session${if (n == 1) "" else "s"} of ${m.label}'s option prices on the phone$that, Boss - " +
                "too few to say how often its out-of-the-money option ended the day in the money (I need $MIN_SESSIONS)." +
                (if (other > 0 && q.expiry) " I have ${count(other)} other sessions; ask without expiry for those." else "") +
                (if (ds.size >= MIN_SESSIONS) " Ask for a nearer strike." else "")
        }
        val all = held.values.flatten().map { it.first.day }.distinct().sorted()
        val span = "${count(n)} ${kind}sessions on this phone (${date(all.first())} to ${date(all.last())})"
        val apart = if (q.expiry) "" else ", expiry days apart"
        val lines = ArrayList<String>()
        val legs = rights.mapNotNull { r ->
            val h = held.getValue(r); if (h.isEmpty()) return@mapNotNull null
            val c = h.size
            val itm = h.count { (d, l) -> d.itm(r, l) }; val dbl = h.count { (_, l) -> l.doubled }
            val itmPart = "ended the day in the money on ${count(itm)} of ${count(c)} (${share(itm, c)})"
            val dblPart = "doubled from its 9:30 price at some point before the end of the day on ${count(dbl)} (${share(dbl, c)})"
            "the ${side(r)} (${distance(q, r)}) " + if (q.lead == Aim.DOUBLE) "$dblPart and $itmPart" else "$itmPart and $dblPart"
        }
        // The lead carries the key figure (ShortAnswer's line).
        lines += "Held from 9:30, ${m.label}'s out-of-the-money ${if (rights.size == 1) side(rights[0]) else "call and put"} " +
            "(the nearest expiry) over the last $span$apart: " + legs.joinToString("; ") + "."
        val parts = rights.mapNotNull { r ->
            val h = held.getValue(r); if (h.isEmpty()) return@mapNotNull null
            val c = h.size
            val reach = h.count { (d, l) -> d.reached(r, l) }
            val up = h.count { (_, l) -> l.up }
            val gap = median(h.map { (d, l) -> abs(l.strike - d.spot) }) ?: 0.0
            "the ${side(r)}'s strike was a median ${k(Math.round(gap).toDouble())} points from ${m.label} at 9:30, ${m.label} reached it at some " +
                "point on ${count(reach)} (${share(reach, c)}), the ${side(r)} ended worth more than at 9:30 on ${count(up)} (${share(up, c)}) and its " +
                "median end of the day was ${moved(median(h.map { it.second.change }) ?: 0.0)}"
        }
        lines += parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
        if (!q.expiry) {
            val ex = past.filter { it.expiry && it.day.isBefore(today) }
            val said = rights.mapNotNull { r ->
                val h = ex.mapNotNull { d -> d.leg(r, q)?.let { d to it } }
                if (h.size < 3) null else "the ${side(r)} ended in the money on ${count(h.count { (d, l) -> d.itm(r, l) })} of ${count(h.size)}"
            }
            if (said.isNotEmpty()) lines += "On the expiry days, " + said.joinToString(" and ") + "."
        }
        if (n < FEW_SESSIONS)
            lines += "${count(n)} sessions is a small record, so a few more would move these figures."
        todayLine(q, rights, todays, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today's legs from 9:30 ("so far" while it trades; where the kept chain stops when it stops early). */
    private fun todayLine(q: Q, rights: List<Right>, s: Session?, today: LocalDate, now: LocalDateTime): String? {
        if (s == null || s.day != today) return null
        val nowMin = if (now.toLocalDate() == today) now.hour * 60 + now.minute else 24 * 60
        if (nowMin <= FROM) return null
        val lastOpt = s.options.maxOfOrNull { if (it.size == 0) 0 else it.minutes.last() } ?: return null
        val lastIx = s.index?.let { if (it.size == 0) 0 else it.minutes.last() } ?: return null
        val lastMin = minOf(END, nowMin, lastOpt, lastIx)
        if (lastMin <= FROM) return null
        val d = read(s, lastMin) ?: return "Today, the chain kept on the phone has no out-of-the-money prices at 9:30 to set beside it."
        val read = rights.mapNotNull { r -> d.leg(r, q)?.let { r to it } }
        if (read.isEmpty()) return null
        val live = lastMin < END && nowMin < 15 * 60 + 30
        val lead = when {
            lastMin >= END -> "Today"
            live && nowMin - lastMin <= STALE -> "Today so far"
            else -> "Today, the kept chain stops at ${hm(lastMin)}; up to there"
        }
        val said = read.joinToString("; ") { (r, l) ->
            val inside = d.itm(r, l)
            val gap = abs(d.close - l.strike)
            "the ${k(l.strike)} ${side(r)} was ${px(l.entry)} at 9:30 and ${px(l.end)} at ${hm(lastMin)} (${moved(l.change)}), " +
                (if (inside) "in the money by ${k(Math.round(gap).toDouble())} points" else "${k(Math.round(gap).toDouble())} points out of the money") +
                (if (l.doubled) " - doubled by then" else "")
        }
        return "$lead, $said."
    }
}
