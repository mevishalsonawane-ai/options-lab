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
 * The straddle decay record (market intelligence, round 40): "how much does the ATM straddle usually lose between 9:30
 * and 2:30?", "how much does Nifty's straddle decay on a quiet day vs a trending day?", "straddle decay record for
 * BankNifty", "how often does the ATM straddle gain by 2:30?", "on expiry day how much does the straddle usually lose",
 * "nifty ka straddle din mein kitna girta hai". The question an option buyer asks before holding through the middle of
 * the day: what the hours from 9:30 to 14:30 usually cost the at-the-money premium, and how far the index had to go for it
 * to hold. From the option prices the phone keeps (the bundled and harvested 1-minute option candles, and the chain read
 * today): each past session read on its own - the nearest expiry's strike closest to the index at 9:30, its call and put
 * at 9:30 and the same strike at 14:30 - and the index's net move between the two. Expiry days (the contract expiring that
 * day) are counted apart from the rest, since their premium runs out by the close. The sessions are split into thirds by
 * that net move: the quietest third against the third that moved most. Beside today's straddle so far. A record of past
 * sessions on this phone, said with its counts and plainly when there are too few; never a forecast or advice; nothing
 * acts. The expected move from the straddle now stays [ChainIntel]'s, today's expiry companion [ExpiryDay]'s, Boss's own
 * book's decay [BookDecay]'s, the index's own reads (MoveTime, GiveBack, OpenReach...) theirs. Pure.
 */
object StraddleDecay {
    /** What was asked: [expiry] when expiry days themselves are asked; [lead] the kind of day named first, if any. */
    data class Q(val expiry: Boolean = false, val lead: Kind? = null)

    enum class Kind { QUIET, TREND }

    /**
     * One past session: its [day], whether the straddle's contract expired that day ([expiry]), the [strike], the index at
     * 9:30 and 14:30 ([spotA], [spotB]), and the straddle (call + put) at each ([a], [b]).
     */
    data class Day(val day: LocalDate, val expiry: Boolean, val strike: Double, val spotA: Double, val spotB: Double, val a: Double, val b: Double) {
        /** The straddle's change from 9:30 to 14:30, as a share of its 9:30 price (negative: it lost). */
        val change: Double get() = (b - a) / a
        /** The index's net move from 9:30 to 14:30, % of its 9:30 price, either way. */
        val move: Double get() = abs(spotB - spotA) / spotA * 100
    }

    /** One group's figures: [n] sessions, the median change, how many gained, the median net move. */
    data class Group(val n: Int, val medianChange: Double?, val gained: Int, val medianMove: Double?)

    /** The read starts here (minute of the IST day): 9:30, after the opening swings. */
    const val FROM = 9 * 60 + 30
    /** And ends here: 14:30, before the last hour. */
    const val TO = 14 * 60 + 30
    /** A price older than this many minutes at either time is no price for it (a contract that did not trade). */
    const val STALE = 10
    /** Fewer sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 10
    /** Fewer sessions than this: said as a small record. */
    const val FEW_SESSIONS = 30
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250

    const val NOTE = "A record of past sessions on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the straddle decay record for the index options only, Boss - Nifty, BankNifty, FinNifty and Sensex, as far as the phone keeps their option prices: gold's options are not kept here, and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun pc(x: Double) = "%d%%".format(Locale.ENGLISH, Math.round(abs(x) * 100))
    private fun px(x: Double) = "%,.1f".format(Locale.ENGLISH, x)
    private fun k(x: Double) = if (x % 1.0 == 0.0) "%,.0f".format(Locale.ENGLISH, x) else "%,.1f".format(Locale.ENGLISH, x)
    private fun count(x: Int) = "%,d".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun hm(minute: Int) = "%02d:%02d".format(Locale.ENGLISH, minute / 60, minute % 60)
    /** "lost 18%" / "gained 4%" / "held its value". */
    private fun did(x: Double) = when { Math.round(abs(x) * 100) == 0L -> "held its value"; x < 0 -> "lost ${pc(x)}"; else -> "gained ${pc(x)}" }

    // ---- the questions --------------------------------------------------------------------------------------------

    /** The at-the-money straddle, or the at-the-money premiums. */
    private val WHAT = Regex(" (atm straddle|atm straddles|at the money straddle|at the money straddles|straddle|straddles|straddle premium|straddle premiums|" +
        "atm premium|atm premiums|at the money premium|at the money premiums|atm options|at the money options|atm option premiums|option premiums) ")
    /** Losing value through the day, or holding it. */
    private val LOSE = Regex(" (lose|loses|lost|losing|decay|decays|decayed|decaying|bleed|bleeds|melt|melts|melting|erode|erodes|shrink|shrinks|" +
        "gain|gains|gained|hold its value|hold value|holds its value|holds value|hold up|holds up|time decay|theta|girta|girti|ghat|ghatta|ghatti|" +
        "kam hota|kam hoti|kam ho jata|kam ho jati|decay hota|decay hoti|kitna girta|kitna ghatta) ")
    /** Asked of the record, or of the stretch of the day it covers. */
    private val HOW = Regex(" (how much|how often|how many times|what share|what percent|what percentage|usually|normally|typically|generally|" +
        "tend to|tends to|on average|average|median|historically|kitna|kitni|kitni baar|aksar|mostly|often|" +
        "quiet day|quiet days|flat day|flat days|sideways day|sideways days|range day|range days|trending day|trending days|trend day|trend days|" +
        "intraday|in a day|through the day|during the day|din mein|din me|between 9 30 and 2 30|between 9 30 and 14 30|by 2 30|by 14 30|till 2 30|till 14 30) ")
    /** Named as a record. */
    private val NAME = Regex(" (straddle decay|straddle|premium decay|atm decay|intraday decay) (record|records|stats|statistics|history|data) ")
    private val QUIET = Regex(" (quiet|flat|sideways|range|rangebound|range bound|dull|slow) (day|days|session|sessions) ")
    private val TREND = Regex(" (trending|trend|moving|big move|big|directional|volatile) (day|days|session|sessions) ")
    private val EXPIRY = Regex(" (expiry day|expiry days|on expiry|expiry ke din|expiry wale din) ")
    // A forecast or advice, Boss's own book, a what-if, alerts, the app's bots, a definition, a reason, today, now or one
    // past day, the expected move or IV (ChainIntel's), max pain and OI, other spreads, gold or VIX, Jarvis or the app.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|tomorrows|kal|next|predict|prediction|forecast|outlook|expect|expected|should|shall|" +
        "buy|sell|enter|exit|book|booking|short|write|writing|i|me|my|mine|we|our|if|what if|suppose|imagine|scenario|agar|remind|reminder|alert|alarm|" +
        "notify|bot|bots|algo|strategy|backtest|mean|means|meaning|define|explain|what is|why|kyun|kyon|kyu|reason|" +
        "today|todays|aaj|aj|now|right now|abhi|so far|this|that|last|past|previous|prev|recent|recently|yesterday|yesterdays|did|was|were|" +
        "imply|implies|implied|expected move|iv|implied volatility|max pain|oi|open interest|pcr|strangle|strangles|spread|spreads|condor|condors|butterfly|" +
        "gold|vix|fear|position|positions|portfolio|stop|stops|sl|target|targets|news|order|orders|you|your|jarvis|app|weekend|holiday|holidays) ")

    /** What was asked, or null: the record of what 9:30 to 14:30 did to the at-the-money straddle, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD || it == Market.VIX }) return null
        val ok = NAME.containsMatchIn(t) || (WHAT.containsMatchIn(t) && LOSE.containsMatchIn(t) && HOW.containsMatchIn(t))
        if (!ok) return null
        val q = QUIET.find(t)?.range?.first
        val tr = TREND.find(t)?.range?.first
        val lead = when {
            q != null && (tr == null || q < tr) -> Kind.QUIET
            tr != null -> Kind.TREND
            else -> null
        }
        return Q(EXPIRY.containsMatchIn(t), lead)
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

    /**
     * The nearest expiry's at-the-money call and put of [s] (the strike closest to the index at [minute] with both legs),
     * or null without the prices.
     */
    internal fun legs(s: Session, minute: Int): Pair<Series, Series>? {
        val ix = s.index ?: return null
        val spot = at(ix, minute) ?: return null
        val opts = s.options
        val expiry = opts.mapNotNull { it.expiry }.filter { !it.isBefore(s.day) }.minOrNull() ?: return null
        val near = opts.filter { it.expiry == expiry }
        val calls = near.filter { it.right == Right.CE && at(it, minute) != null }.associateBy { it.strike }
        val puts = near.filter { it.right == Right.PE && at(it, minute) != null }.associateBy { it.strike }
        val strike = calls.keys.filter { it in puts }.minByOrNull { abs(it - spot) } ?: return null
        return calls.getValue(strike) to puts.getValue(strike)
    }

    /** [s]'s straddle from [FROM] to [TO], or null without prices at both times. */
    fun day(s: Session): Day? {
        val ix = s.index ?: return null
        val (ce, pe) = legs(s, FROM) ?: return null
        val sa = at(ix, FROM) ?: return null
        val sb = at(ix, TO) ?: return null
        val a = (at(ce, FROM) ?: return null) + (at(pe, FROM) ?: return null)
        val b = (at(ce, TO) ?: return null) + (at(pe, TO) ?: return null)
        if (a <= 0) return null
        return Day(s.day, ce.expiry == s.day, ce.strike, sa, sb, a, b)
    }

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

    /** [ds]'s figures as one group. */
    fun group(ds: List<Day>): Group = Group(ds.size, median(ds.map { it.change }), ds.count { it.b > it.a }, median(ds.map { it.move }))

    /** [ds] split by net move: the quietest third and the third that moved most (the middle third left out), and the cut values. */
    internal fun thirds(ds: List<Day>): Triple<List<Day>, List<Day>, Pair<Double, Double>>? {
        if (ds.size < 3) return null
        val by = ds.sortedBy { it.move }
        val n = ds.size / 3
        val quiet = by.take(n); val trend = by.takeLast(n)
        return Triple(quiet, trend, quiet.last().move to trend.first().move)
    }

    /**
     * [m]'s straddle decay record from [past] (its [days]) for [q], beside [todays] session (the chain kept today, or null)
     * at [now] on [today].
     */
    fun answer(q: Q, m: Market, past: List<Day>, todays: Session?, today: LocalDate, now: LocalDateTime): String {
        val ds = past.filter { it.expiry == q.expiry && it.day.isBefore(today) }
        val kind = if (q.expiry) "expiry-day " else ""
        if (ds.size < MIN_SESSIONS) {
            val other = past.count { it.expiry != q.expiry }
            return "I have only ${ds.size} ${kind}session${if (ds.size == 1) "" else "s"} of ${m.label}'s option prices on the phone, Boss - " +
                "too few to say what 9:30 to 14:30 usually does to its at-the-money straddle (I need $MIN_SESSIONS)." +
                (if (other > 0 && q.expiry) " I have ${count(other)} other sessions; ask without expiry for those." else "")
        }
        val all = group(ds)
        val span = "${count(ds.size)} ${kind}sessions on this phone (${date(ds.first().day)} to ${date(ds.last().day)})"
        val lines = ArrayList<String>()
        // The lead carries the key figure (ShortAnswer's line).
        val apart = if (q.expiry) "" else ", expiry days apart"
        lines += "From 9:30 to 14:30, ${m.label}'s at-the-money straddle (the nearest expiry, the strike closest to ${m.label} at 9:30, held) " +
            "${did(all.medianChange ?: 0.0)} at the median over the last $span$apart."
        val t = thirds(ds)
        if (t != null) {
            val (quiet, trend, cut) = t
            val gq = group(quiet); val gt = group(trend)
            val quietLine = "On the quietest third (${count(gq.n)} sessions, ${m.label} within ${p2(cut.first)} of its 9:30 price at 14:30) it " +
                "${did(gq.medianChange ?: 0.0)} at the median and gained on ${gq.gained} (${share(gq.gained, gq.n)})"
            val trendLine = "on the third that moved most (${count(gt.n)} sessions, ${p2(cut.second)} or more) it ${did(gt.medianChange ?: 0.0)} " +
                "at the median and gained on ${gt.gained} (${share(gt.gained, gt.n)})"
            lines += if (q.lead == Kind.TREND) "${trendLine.replaceFirstChar { it.uppercase() }}; ${quietLine.replaceFirstChar { it.lowercase() }}."
                else "$quietLine; $trendLine."
        }
        val gainers = ds.filter { it.b > it.a }
        lines += if (gainers.isEmpty()) "It was never worth more at 14:30 than at 9:30 in these ${count(ds.size)} sessions."
        else "Over all, it was worth more at 14:30 than at 9:30 on ${count(gainers.size)} of the ${count(ds.size)} (${share(gainers.size, ds.size)}); " +
            "on those, ${m.label} had moved a median ${p2(median(gainers.map { it.move }) ?: 0.0)} from its 9:30 price, against " +
            "${p2(all.medianMove ?: 0.0)} on all of them."
        if (!q.expiry) {
            val ex = past.filter { it.expiry && it.day.isBefore(today) }
            if (ex.size >= 3) lines += "On the ${count(ex.size)} expiry days, it ${did(group(ex).medianChange ?: 0.0)} at the median."
        }
        if (ds.size < FEW_SESSIONS)
            lines += "${count(ds.size)} sessions is a small record, so a few more would move these figures."
        todayLine(m, todays, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today's straddle against the same measure ("so far" while it trades; where the kept chain stops when it stops early). */
    private fun todayLine(m: Market, s: Session?, today: LocalDate, now: LocalDateTime): String? {
        if (s == null || s.day != today) return null
        val ix = s.index
        val nowMin = if (now.toLocalDate() == today) now.hour * 60 + now.minute else 24 * 60
        if (nowMin < FROM) return null
        val (ce, pe) = legs(s, FROM) ?: return "Today, the chain kept on the phone has no at-the-money call and put at 9:30 to set beside it."
        val a = (at(ce, FROM) ?: return null) + (at(pe, FROM) ?: return null)
        // The newest minute both legs have, up to 14:30.
        val lastMin = minOf(TO, nowMin, ce.minutes.last(), pe.minutes.last())
        if (lastMin <= FROM) return null
        val b = (at(ce, lastMin) ?: return null) + (at(pe, lastMin) ?: return null)
        val change = (b - a) / a
        val live = lastMin < TO && nowMin < 15 * 60 + 30
        val lead = when {
            lastMin >= TO -> "Today"
            live && nowMin - lastMin <= STALE -> "Today so far"
            else -> "Today, the kept chain stops at ${hm(lastMin)}; up to there"
        }
        val spotNote = ix?.let { x -> val sa = at(x, FROM); val sb = at(x, lastMin)
            if (sa != null && sb != null) ", with ${m.label} ${p2(abs(sb - sa) / sa * 100)} from its 9:30 price" else null } ?: ""
        return "$lead, the ${k(ce.strike)} straddle was ${px(a)} at 9:30 and ${px(b)} at ${hm(lastMin)} - it ${did(change)}$spotNote."
    }
}
