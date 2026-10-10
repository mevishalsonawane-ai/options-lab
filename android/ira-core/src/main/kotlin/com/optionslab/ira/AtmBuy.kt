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
 * The at-the-money buyer's record (market intelligence, round 41): "how often does the ATM option double from its 9:30 price
 * before the end of the day?", "how often does a bought ATM call end the day worth more?", "how often does Nifty's ATM put
 * double on expiry day?", "ATM option buyer record for BankNifty", "atm call kitni baar double hota hai". The hit-rate an
 * option buyer asks about before paying for the at-the-money premium in the morning: from the option prices the phone keeps
 * (the bundled and harvested 1-minute option candles, and the chain read today), each past session read on its own - the
 * nearest expiry's strike closest to the index at 9:30 (as [StraddleDecay] picks it), its call and its put at 9:30 - and,
 * for each leg, the best price it reached after 9:30 (the candle's high where kept) and its price at the end of the day.
 * How often each ended the day worth more than at 9:30, how often each touched one and a half times and twice its 9:30
 * price before the end of the day, the medians, and how often at least one of the two doubled. Expiry days (the contract
 * expiring that day) are counted apart. Beside today's legs so far. A record of past sessions on this phone, said with its
 * counts and plainly when there are too few; never a forecast or advice; nothing acts. The straddle's decay to 14:30 stays
 * [StraddleDecay]'s, the expected move [ChainIntel]'s, Boss's own book [BookDecay]'s. Pure.
 */
object AtmBuy {
    /** What was asked: [right] the call or the put alone (null: both), [expiry] expiry days themselves, [lead] what leads. */
    data class Q(val right: Right? = null, val expiry: Boolean = false, val lead: Aim = Aim.UP)

    /** The figure asked first: ending the day worth more, or doubling. */
    enum class Aim { UP, DOUBLE }

    /** One leg held from 9:30: its price then ([entry]), the best after ([best]) and at the end of the day ([end]). */
    data class Leg(val entry: Double, val best: Double, val end: Double) {
        val up: Boolean get() = end > entry
        val doubled: Boolean get() = best >= 2 * entry
        val half: Boolean get() = best >= 1.5 * entry
        /** The end-of-day change, as a share of the 9:30 price. */
        val change: Double get() = (end - entry) / entry
        /** The best change after 9:30, as a share of the 9:30 price. */
        val peak: Double get() = (best - entry) / entry
    }

    /** One past session: its [day], whether the contract expired that day ([expiry]), the [strike], and its two legs. */
    data class Day(val day: LocalDate, val expiry: Boolean, val strike: Double, val ce: Leg, val pe: Leg) {
        fun leg(r: Right): Leg = if (r == Right.PE) pe else ce
    }

    /** The read starts here (minute of the IST day): 9:30, after the opening swings. */
    const val FROM = 9 * 60 + 30
    /** The end of the day: the last minute of the session. */
    const val END = 15 * 60 + 29
    /** A price older than this many minutes is no price for that time. */
    const val STALE = 10
    /** Fewer sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 10
    /** Fewer sessions than this: said as a small record. */
    const val FEW_SESSIONS = 30
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250

    const val NOTE = "A record of past sessions on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the at-the-money buyer's record for the index options only, Boss - Nifty, BankNifty, FinNifty and Sensex, as far as the phone keeps their option prices: gold's options are not kept here, and India VIX is not traded."

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

    /** The at-the-money option, call or put. */
    private val ATM = Regex(" (atm|at the money|at the money s) (option|options|call|calls|put|puts|ce|pe|premium|premiums|option premium|option premiums|contract|contracts) ")
    private val CALL = Regex(" (atm|at the money) (call|calls|ce) ")
    private val PUT = Regex(" (atm|at the money) (put|puts|pe) ")
    /** Doubling, or ending the day worth more. */
    private val DOUBLE = Regex(" (double|doubles|doubled|doubling|2x|two times|twice its|twice the|do guna|dugna|double hota|double hoti) ")
    private val UP = Regex(" (worth more|end the day worth more|ends the day worth more|end up worth more|ends up worth more|end higher|ends higher|" +
        "end the day higher|ends the day higher|end the day up|ends the day up|end up higher|ends up higher|end the day in profit|ends the day in profit|" +
        "end in profit|ends in profit|in profit|pay off|pays off|paid off|work out|works out|worked out|make money|makes money|made money|" +
        "upar band|zyada pe band|profit mein|profit me|munafa) ")
    /** Asked of the record. */
    private val HOW = Regex(" (how often|how many times|how many days|how many sessions|what share|what percent|what percentage|usually|normally|typically|" +
        "generally|tend to|tends to|historically|hit rate|success rate|win rate|kitni baar|kitne din|aksar|often) ")
    /** Named as a record. */
    private val NAME = Regex(" (atm|at the money) (option |call |put )?(buyer|buyers|buying|buy) (record|records|stats|statistics|history|hit rate|success rate) | " +
        "(atm|at the money) (option|call|put) (double|doubling) (record|records|stats|statistics|history) ")
    private val EXPIRY = Regex(" (expiry day|expiry days|on expiry|expiry ke din|expiry wale din) ")
    /**
     * Buying the at-the-money option asked whether it works (understanding round 29): "how often does buying ATM work",
     * "does buying ATM options pay off", "do ATM buyers make money", "atm buying kaam karta hai" - the same record.
     */
    private val WORKS = Regex(" (buying|buy) (an |the )?(atm|at the money)( option| options| call| calls| put| puts| ce| pe)? |" +
        " (atm|at the money) (option |options |call |calls |put |puts )?(buying|buyers?) ")
    private val PAYS = Regex(" (work|works|working|worth it|pay|pays|pay off|pays off|make money|makes money|profitable|succeed|succeeds|" +
        "kaam karta|kaam karti|kaam karte|kaam aata|chalta|chalti|kamate|kamata) ")
    private val ASKS = Regex("^ (does|do|is|are)( |$)| (kya|hai kya|karta hai|karti hai|karte hain) $")
    // A forecast or advice, a seller's or writer's question, Boss's own book, a what-if, alerts, the app's bots, a
    // definition, a reason, today, now or one past day, the straddle (StraddleDecay's) and other spreads, the expected
    // move or IV (ChainIntel's), max pain and OI, gold or VIX, Jarvis or the app.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|tomorrows|kal|next|predict|prediction|forecast|outlook|expect|expected|should|shall|" +
        "sell|selling|sold|seller|sellers|short|shorting|write|writing|writer|writers|i|me|my|mine|we|our|if|what if|suppose|imagine|scenario|agar|" +
        "remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|backtest|mean|means|meaning|define|explain|what is|why|kyun|kyon|kyu|reason|" +
        "today|todays|aaj|aj|now|right now|abhi|so far|this|that|last|past|previous|prev|recent|recently|yesterday|yesterdays|did|was|were|" +
        "straddle|straddles|strangle|strangles|spread|spreads|condor|condors|butterfly|imply|implies|implied|expected move|iv|implied volatility|" +
        "max pain|oi|open interest|pcr|gold|vix|fear|position|positions|portfolio|stop|stops|sl|target|targets|news|order|orders|you|your|jarvis|app|" +
        "weekend|holiday|holidays|price now|quote|ltp) ")

    /** What was asked, or null: the record of a bought at-the-money option from 9:30, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD || it == Market.VIX }) return null
        val d = DOUBLE.find(t)?.range?.first
        val u = UP.find(t)?.range?.first
        val ok = NAME.containsMatchIn(t) || (ATM.containsMatchIn(t) && (d != null || u != null) && HOW.containsMatchIn(t)) ||
            (WORKS.containsMatchIn(t) && PAYS.containsMatchIn(t) && (HOW.containsMatchIn(t) || ASKS.containsMatchIn(t)))
        if (!ok) return null
        val c = CALL.containsMatchIn(t); val p = PUT.containsMatchIn(t)
        val right = when { c && !p -> Right.CE; p && !c -> Right.PE; else -> null }
        val lead = if (d != null && (u == null || d < u)) Aim.DOUBLE else Aim.UP
        return Q(right, EXPIRY.containsMatchIn(t), lead)
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

    /** [s]'s at-the-money call and put from 9:30 to the end of the day, or null without prices at both ends. */
    fun day(s: Session): Day? {
        val (ce, pe) = StraddleDecay.legs(s, FROM) ?: return null
        val c = leg(ce, END) ?: return null
        val p = leg(pe, END) ?: return null
        return Day(s.day, ce.expiry == s.day, ce.strike, c, p)
    }

    /**
     * Each session before [today] in [sessions] read down to its [Day] (streamed: each session is let go once read), the
     * newest [MAX_SESSIONS], oldest first.
     */
    fun days(sessions: Sequence<Session>, today: LocalDate): List<Day> = Reader(today).apply { sessions.forEach(::add) }.days()

    /** [days] one session at a time, so one stream of the kept sessions can feed several reads ([PastReads]). */
    class Reader(private val today: LocalDate) : PastReads.Part<List<Day>> {
        private val out = java.util.TreeMap<LocalDate, Day>()
        override fun add(s: Session) {
            if (!s.day.isBefore(today)) return
            day(s)?.let { out[s.day] = it }
            if (out.size > MAX_SESSIONS) out.remove(out.firstKey())
        }
        override fun days(): List<Day> = out.values.toList()
    }

    private fun median(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** One leg's line over [ds]: how often it ended the day worth more and how often it doubled, [q]'s lead first. */
    private fun legLine(r: Right, ds: List<Day>, lead: Aim): String {
        val legs = ds.map { it.leg(r) }
        val n = ds.size
        val up = legs.count { it.up }; val dbl = legs.count { it.doubled }
        val upPart = "ended the day worth more than at 9:30 on ${count(up)} (${share(up, n)})"
        val dblPart = "doubled from its 9:30 price at some point before the end of the day on ${count(dbl)} (${share(dbl, n)})"
        return "the ${side(r)} " + if (lead == Aim.DOUBLE) "$dblPart and $upPart" else "$upPart and $dblPart"
    }

    /**
     * [m]'s at-the-money buyer's record from [past] (its [days]) for [q], beside [todays] session (the chain kept today, or
     * null) at [now] on [today].
     */
    fun answer(q: Q, m: Market, past: List<Day>, todays: Session?, today: LocalDate, now: LocalDateTime): String {
        val ds = past.filter { it.expiry == q.expiry && it.day.isBefore(today) }
        val kind = if (q.expiry) "expiry-day " else ""
        if (ds.size < MIN_SESSIONS) {
            val other = past.count { it.expiry != q.expiry && it.day.isBefore(today) }
            return "I have only ${ds.size} ${kind}session${if (ds.size == 1) "" else "s"} of ${m.label}'s option prices on the phone, Boss - " +
                "too few to say how often its at-the-money option bought at 9:30 ended the day worth more or doubled (I need $MIN_SESSIONS)." +
                (if (other > 0 && q.expiry) " I have ${count(other)} other sessions; ask without expiry for those." else "")
        }
        val rights = q.right?.let { listOf(it) } ?: listOf(Right.CE, Right.PE)
        val span = "${count(ds.size)} ${kind}sessions on this phone (${date(ds.first().day)} to ${date(ds.last().day)})"
        val apart = if (q.expiry) "" else ", expiry days apart"
        val lines = ArrayList<String>()
        // The lead carries the key figure (ShortAnswer's line).
        val legs = rights.map { r -> legLine(r, ds, q.lead) }
        lines += "Bought at 9:30 and held, ${m.label}'s at-the-money ${if (rights.size == 1) side(rights[0]) else "call and put"} " +
            "(the nearest expiry, the strike closest to ${m.label} at 9:30) over the last $span$apart: " + legs.joinToString("; ") + "."
        val parts = rights.map { r ->
            val l = ds.map { it.leg(r) }
            val half = l.count { it.half }
            "the ${side(r)} touched one and a half times its 9:30 price on ${count(half)} (${share(half, ds.size)}), its median best was " +
                "${moved(median(l.map { it.peak }) ?: 0.0)} and its median end of the day ${moved(median(l.map { it.change }) ?: 0.0)}"
        }
        lines += parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
        if (rights.size == 2) {
            val either = ds.count { it.ce.doubled || it.pe.doubled }
            val neither = ds.count { !it.ce.up && !it.pe.up }
            lines += "At least one of the two doubled on ${count(either)} of the ${count(ds.size)} (${share(either, ds.size)}); " +
                "both ended the day worth less on ${count(neither)} (${share(neither, ds.size)})."
        }
        if (!q.expiry) {
            val ex = past.filter { it.expiry && it.day.isBefore(today) }
            if (ex.size >= 3) lines += "On the ${count(ex.size)} expiry days, " + rights.joinToString(" and ") { r ->
                "the ${side(r)} doubled on ${count(ex.count { it.leg(r).doubled })}" } + "."
        }
        if (ds.size < FEW_SESSIONS)
            lines += "${count(ds.size)} sessions is a small record, so a few more would move these figures."
        todayLine(m, rights, todays, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today's legs from 9:30 ("so far" while it trades; where the kept chain stops when it stops early). */
    private fun todayLine(m: Market, rights: List<Right>, s: Session?, today: LocalDate, now: LocalDateTime): String? {
        if (s == null || s.day != today) return null
        val nowMin = if (now.toLocalDate() == today) now.hour * 60 + now.minute else 24 * 60
        if (nowMin <= FROM) return null
        val (ce, pe) = StraddleDecay.legs(s, FROM) ?: return "Today, the chain kept on the phone has no at-the-money call and put at 9:30 to set beside it."
        val lastMin = minOf(END, nowMin, ce.minutes.last(), pe.minutes.last())
        if (lastMin <= FROM) return null
        val read = rights.map { r -> val x = if (r == Right.PE) pe else ce; r to (leg(x, lastMin) ?: return null) }
        val live = lastMin < END && nowMin < 15 * 60 + 30
        val lead = when {
            lastMin >= END -> "Today"
            live && nowMin - lastMin <= STALE -> "Today so far"
            else -> "Today, the kept chain stops at ${hm(lastMin)}; up to there"
        }
        val said = read.joinToString("; ") { (r, l) ->
            "the ${k(ce.strike)} ${side(r)} was ${px(l.entry)} at 9:30, at best ${px(l.best)} (${moved(l.peak)}) and ${px(l.end)} at ${hm(lastMin)}" +
                (if (l.doubled) " - doubled by then" else "")
        }
        return "$lead, $said."
    }
}
