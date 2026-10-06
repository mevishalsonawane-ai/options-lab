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
 * The expiry-day last-hour premium record (market intelligence, round 42): "how does the ATM option's premium behave in the
 * last hour on expiry day?", "how much does the ATM call lose in the last hour of expiry?", "how often does the ATM put
 * double in the final hour on expiry?", "expiry last hour premium record for BankNifty", "expiry ke aakhri ghante mein atm
 * premium kitna girta hai". What an option buyer asks before holding the expiring at-the-money option into its last hour:
 * from the option prices the phone keeps (the bundled and harvested 1-minute option candles, and the chain read today), each
 * past expiry day read on its own - the expiring contract's strike closest to the index at 14:30 (as [StraddleDecay] picks
 * it), its call and its put at 14:30 - and, for each leg, the best price it reached after 14:30 (the candle's high where
 * kept) and its price at the end of the day. The median change to the end of the day, how often each ended worth more, lost
 * half or more, and touched twice its 14:30 price; the two together; the index's own move in that hour; the same hour on
 * the other days beside it for scale. Beside today's legs so far when today is an expiry. A record of past expiries on this
 * phone, said with its counts and plainly when there are too few; never a forecast or advice; nothing acts. The day from
 * 9:30 stays [AtmBuy]'s, the straddle to 14:30 [StraddleDecay]'s, the index's last hour [LastHour]'s, today's expiry as it
 * goes [ExpiryDay]'s, the pin [ExpiryPin]'s. Pure.
 */
object ExpiryHour {
    /** What was asked: [right] the call or the put alone (null: both), [lead] what leads. */
    data class Q(val right: Right? = null, val lead: Aim = Aim.CHANGE)

    /** The figure asked first: the change to the end of the day, or doubling. */
    enum class Aim { CHANGE, DOUBLE }

    /** One leg held from 14:30: its price then ([entry]), the best after ([best]) and at the end of the day ([end]). */
    data class Leg(val entry: Double, val best: Double, val end: Double) {
        val up: Boolean get() = end > entry
        val doubled: Boolean get() = best >= 2 * entry
        val halved: Boolean get() = end <= 0.5 * entry
        /** The change to the end of the day, as a share of the 14:30 price. */
        val change: Double get() = (end - entry) / entry
    }

    /**
     * One past session: its [day], whether the contract expired that day ([expiry]), the [strike], its two legs, and the
     * index at 14:30 ([ixAt]) and at the end ([ixEnd]).
     */
    data class Day(val day: LocalDate, val expiry: Boolean, val strike: Double, val ce: Leg, val pe: Leg, val ixAt: Double, val ixEnd: Double) {
        fun leg(r: Right): Leg = if (r == Right.PE) pe else ce
        /** The two legs together, 14:30 to the end, as a share of their 14:30 price. */
        val both: Double get() = (ce.end + pe.end - ce.entry - pe.entry) / (ce.entry + pe.entry)
    }

    /** The last hour starts here (minute of the IST day): 14:30. */
    const val FROM = 14 * 60 + 30
    /** The end of the day: the last minute of the session. */
    const val END = 15 * 60 + 29
    /** A price older than this many minutes is no price for that time. */
    const val STALE = 10
    /** Fewer expiry days than this: too few to say anything. */
    const val MIN_DAYS = 8
    /** Fewer expiry days than this: said as a small record. */
    const val FEW_DAYS = 20
    /** Fewer other days than this: no line for them. */
    const val MIN_OTHER = 10
    /** At most this many of the newest expiry days (and as many other days) are read. */
    const val MAX_DAYS = 250

    const val NOTE = "A record of past expiries on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the expiry-day last-hour record for the index options only, Boss - Nifty, BankNifty, FinNifty and Sensex, as far as the phone keeps their option prices: gold's options are not kept here, and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun pc(x: Double) = "%d%%".format(Locale.ENGLISH, Math.round(abs(x) * 100))
    private fun px(x: Double) = "%,.1f".format(Locale.ENGLISH, x)
    private fun k(x: Double) = if (x % 1.0 == 0.0) "%,.0f".format(Locale.ENGLISH, x) else "%,.1f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun count(x: Int) = "%,d".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun hm(minute: Int) = "%02d:%02d".format(Locale.ENGLISH, minute / 60, minute % 60)
    /** "down 34%" / "up 12%" / "flat". */
    private fun moved(x: Double) = when { Math.round(abs(x) * 100) == 0L -> "flat"; x < 0 -> "down ${pc(x)}"; else -> "up ${pc(x)}" }
    private fun side(r: Right) = if (r == Right.PE) "put" else "call"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** The last hour of the day. */
    private val LAST = Regex(" (last hour|final hour|closing hour|last 60 minutes|last one hour|after 2 30|after 14 30|from 2 30|from 14 30|" +
        "2 30 to|14 30 to|aakhri ghanta|aakhri ghante|akhri ghanta|akhri ghante|aakhri ghante mein|last ghanta|last ghante) ")
    /** An expiry day. */
    private val EXPIRY = Regex(" (expiry|expiries|expiring|expiry day|expiry days|expiry ke din|expiry wale din) ")
    /** An option's price. */
    private val OPT = Regex(" (atm|at the money|option|options|premium|premiums|call|calls|put|puts|ce) ")
    private val CALL = Regex(" (call|calls|ce) ")
    private val PUT = Regex(" (put|puts) ")
    private val DOUBLE = Regex(" (double|doubles|doubled|doubling|2x|two times|twice its|twice the|do guna|dugna|double hota|double hoti) ")
    /** The words of the topic alone: the last hour, an expiry day, the at-the-money option, an index, and the joins. */
    private val TOPIC = Regex("(?<= )(last hour|final hour|closing hour|last one hour|aakhri ghanta|aakhri ghante|akhri ghanta|akhri ghante|last ghanta|last ghante|" +
        "expiry day|expiry days|expiry|expiries|expiring|atm|at the money|option|options|premium|premiums|call|calls|put|puts|ce|pe|" +
        "nifty|bank nifty|banknifty|finnifty|fin nifty|sensex|index|of|on|the|in|an|a|for|at|during|day|din|ke|ka|ki|ko|mein|me|main|wale|wala|par|pe|" +
        "jarvis|boss|please|record|stats)(?= )")
    /** Asked of the record. */
    private val HOW = Regex(" (how often|how many times|how many|how much|how does|how do|what happens|what usually happens|" +
        "usually|normally|typically|generally|tend to|tends to|historically|on average|median|behave|behaves|behaviour|behavior|" +
        "lose|loses|decay|decays|melt|melts|kitna|kitni|kitni baar|kya hota|kaise|aksar|often|record|records|stats|statistics|history) ")
    // A forecast or advice, Boss's own book, a what-if, alerts, the app's bots, a definition, a reason, today, now or one
    // past expiry, the straddle as a spread (StraddleDecay's word), other spreads, the expected move or IV, max pain and OI
    // (ExpiryPin's), gold or VIX, the quote, Jarvis or the app.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|tomorrows|kal|next|predict|prediction|forecast|outlook|expect|expected|should|shall|" +
        "i|me|my|mine|we|our|if|what if|suppose|imagine|scenario|agar|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|backtest|" +
        "mean|means|meaning|define|explain|what is|why|kyun|kyon|kyu|reason|today|todays|aaj|aj|now|right now|abhi|so far|this|that|" +
        "last expiry|last week|last month|last thursday|last tuesday|last session|last day|previous|prev|recent|recently|yesterday|yesterdays|did|was|were|" +
        "straddle|straddles|strangle|strangles|spread|spreads|condor|condors|butterfly|imply|implies|implied|expected move|iv|implied volatility|" +
        "max pain|pin|pinned|oi|open interest|pcr|gold|vix|fear|position|positions|portfolio|stop|stops|sl|target|targets|news|order|orders|" +
        "you|your|jarvis|app|weekend|holiday|holidays|price now|quote|ltp) ")

    /** What was asked, or null: the record of the expiring at-the-money option's last hour, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (!LAST.containsMatchIn(t) || !EXPIRY.containsMatchIn(t) || !OPT.containsMatchIn(t)) return null
        // The topic said alone ("expiry ke last hour mein premium", "ATM premium last hour expiry", understanding round 30)
        // asks the record too; with any other words, only a question of it ([HOW]).
        if (!HOW.containsMatchIn(t) && TOPIC.replace(t, " ").isNotBlank()) return null
        if (Market.mentioned(text).any { it == Market.GOLD || it == Market.VIX }) return null
        val c = CALL.containsMatchIn(t); val p = PUT.containsMatchIn(t)
        val right = when { c && !p -> Right.CE; p && !c -> Right.PE; else -> null }
        return Q(right, if (DOUBLE.containsMatchIn(t)) Aim.DOUBLE else Aim.CHANGE)
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

    /** [x] held from [FROM] to [until], or null without its prices. */
    private fun leg(x: Series, until: Int): Leg? {
        val entry = at(x, FROM) ?: return null
        val end = at(x, until) ?: return null
        val top = maxOf(best(x, until) ?: end, end)
        return Leg(entry, top, end)
    }

    /** [s]'s at-the-money call and put from 14:30 to the end of the day, or null without prices at both ends. */
    fun day(s: Session): Day? {
        val ix = s.index ?: return null
        val (ce, pe) = StraddleDecay.legs(s, FROM) ?: return null
        val c = leg(ce, END) ?: return null
        val p = leg(pe, END) ?: return null
        val a = at(ix, FROM) ?: return null
        val b = at(ix, END) ?: return null
        return Day(s.day, ce.expiry == s.day, ce.strike, c, p, a, b)
    }

    /**
     * Each session before [today] in [sessions] read down to its [Day] (streamed: each session is let go once read), the
     * newest [MAX_DAYS] expiry days and as many other days, oldest first.
     */
    fun days(sessions: Sequence<Session>, today: LocalDate): List<Day> {
        val ex = java.util.TreeMap<LocalDate, Day>()
        val other = java.util.TreeMap<LocalDate, Day>()
        for (s in sessions) {
            if (!s.day.isBefore(today)) continue
            val d = day(s) ?: continue
            val into = if (d.expiry) ex else other
            into[s.day] = d
            if (into.size > MAX_DAYS) into.remove(into.firstKey())
        }
        return (ex.values + other.values).sortedBy { it.day }
    }

    private fun median(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** One leg's line over [ds], [lead] first. */
    private fun legLine(r: Right, ds: List<Day>, lead: Aim): String {
        val legs = ds.map { it.leg(r) }
        val n = ds.size
        val up = legs.count { it.up }; val half = legs.count { it.halved }; val dbl = legs.count { it.doubled }
        val change = "ended the hour a median ${moved(median(legs.map { it.change }) ?: 0.0)}, lost half or more on ${count(half)} (${share(half, n)}) " +
            "and ended worth more on ${count(up)} (${share(up, n)})"
        val double = "touched twice its 14:30 price at some point in the hour on ${count(dbl)} (${share(dbl, n)})"
        return "the ${side(r)} " + if (lead == Aim.DOUBLE) "$double; it $change" else "$change; it $double"
    }

    /**
     * [m]'s expiry-day last-hour record from [past] (its [days]) for [q], beside [todays] session (the chain kept today, or
     * null) at [now] on [today].
     */
    fun answer(q: Q, m: Market, past: List<Day>, todays: Session?, today: LocalDate, now: LocalDateTime): String {
        val ds = past.filter { it.expiry && it.day.isBefore(today) }
        if (ds.size < MIN_DAYS) {
            val other = past.count { !it.expiry && it.day.isBefore(today) }
            return "I have only ${ds.size} expiry day${if (ds.size == 1) "" else "s"} of ${m.label}'s option prices on the phone, Boss - " +
                "too few to say how its at-the-money option behaves in the last hour of expiry (I need $MIN_DAYS)." +
                (if (other > 0) " I have ${count(other)} other sessions; the at-the-money record from 9:30 reads those." else "")
        }
        val rights = q.right?.let { listOf(it) } ?: listOf(Right.CE, Right.PE)
        val n = ds.size
        val lines = ArrayList<String>()
        // The lead carries the key figure (ShortAnswer's line).
        lines += "On the last ${count(n)} expiry days of ${m.label} on this phone (${date(ds.first().day)} to ${date(ds.last().day)}), " +
            "the expiring at-the-money ${if (rights.size == 1) side(rights[0]) else "call and put"} (the strike closest to ${m.label} at 14:30) " +
            "from 14:30 to the end of the day: " + rights.joinToString("; ") { legLine(it, ds, q.lead) } + "."
        if (rights.size == 2) {
            val either = ds.count { it.ce.doubled || it.pe.doubled }
            val bothUp = ds.count { it.both > 0 }
            lines += "The two together ended the hour a median ${moved(median(ds.map { it.both }) ?: 0.0)} and worth more on ${count(bothUp)} " +
                "(${share(bothUp, n)}); at least one of the two doubled on ${count(either)} (${share(either, n)})."
        }
        val ixMove = median(ds.map { abs(it.ixEnd - it.ixAt) }) ?: 0.0
        val big = ds.maxByOrNull { abs(it.ixEnd - it.ixAt) }
        lines += "${m.label} itself moved a median ${pts(ixMove)} points in that hour" +
            (big?.let { ", the most ${pts(it.ixEnd - it.ixAt)} points on ${date(it.day)}" } ?: "") + "."
        val others = past.filter { !it.expiry && it.day.isBefore(today) }
        if (others.size >= MIN_OTHER) lines += "On the ${count(others.size)} other days, the same hour left " + rights.joinToString(" and ") { r ->
            "the ${side(r)} a median ${moved(median(others.map { it.leg(r).change }) ?: 0.0)}" } + "."
        if (n < FEW_DAYS) lines += "${count(n)} expiry days is a small record, so a few more would move these figures."
        todayLine(rights, todays, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today's legs from 14:30 when today is an expiry ("so far" while it trades; where the kept chain stops when it stops early). */
    private fun todayLine(rights: List<Right>, s: Session?, today: LocalDate, now: LocalDateTime): String? {
        if (s == null || s.day != today) return null
        val nowMin = if (now.toLocalDate() == today) now.hour * 60 + now.minute else 24 * 60
        val expiring = s.options.mapNotNull { it.expiry }.filter { !it.isBefore(today) }.minOrNull() == today
        if (!expiring) return null
        if (nowMin <= FROM) return "Today is an expiry; its last hour starts at 14:30."
        val (ce, pe) = StraddleDecay.legs(s, FROM) ?: return "Today is an expiry, but the chain kept on the phone has no at-the-money call and put at 14:30 to set beside it."
        val lastMin = minOf(END, nowMin, ce.minutes.last(), pe.minutes.last())
        if (lastMin <= FROM) return null
        val read = rights.map { r -> val x = if (r == Right.PE) pe else ce; r to (leg(x, lastMin) ?: return null) }
        val live = lastMin < END && nowMin < 15 * 60 + 30
        val lead = when {
            lastMin >= END -> "Today's expiry"
            live && nowMin - lastMin <= STALE -> "Today's expiry so far"
            else -> "Today's expiry, the kept chain stops at ${hm(lastMin)}; up to there"
        }
        val said = read.joinToString("; ") { (r, l) ->
            "the ${k(ce.strike)} ${side(r)} was ${px(l.entry)} at 14:30 and ${px(l.end)} at ${hm(lastMin)} (${moved(l.change)})" +
                (if (l.doubled) " - it doubled in between" else "")
        }
        return "$lead, $said."
    }
}
