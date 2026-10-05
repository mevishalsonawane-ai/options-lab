package com.optionslab.ira

import com.optionslab.engine.ExpiryPut
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The expiry-day pin record (market intelligence, round 22): "how often does Nifty close near max pain on expiry?", "does
 * Nifty pin to max pain on expiry day?", "expiry pin record", "how often does Nifty settle at the biggest OI strike on
 * expiry?", "expiry pe nifty max pain ke paas band hota hai kya". From the Nifty expiry-day chains on the phone (the ones
 * bundled with the app and the phone's own captures, each expiring contract's 1-minute closes and open interest): on each
 * past expiry, max pain and the strike with the most open interest (calls and puts together) as they stood at 09:30, and
 * where Nifty settled - the average over 15:00 to 15:29 of the price the options themselves imply (put-call parity), the
 * way NSE settles; the chains carry no index print. How often it settled within the points asked (50 when none are) of
 * each, the median distance, and - so a pin is not passed off as more than it is - how often it settled as near where it
 * already stood at 09:30, and the same read again at 14:30. The farthest miss and the newest expiry beside, and today's
 * newest chain read when there is one. A record of past expiries on this phone, said with its count; never a forecast or
 * advice; nothing acts. Today's expiry as it goes stays [ExpiryDay]'s, max pain through today [ChainDrift]'s, where it
 * stands now the chain's own read, expiry days' ranges [Weekdays]'. Pure.
 */
object ExpiryPin {
    /** What was asked: how often Nifty settled within [within] points. */
    data class Q(val within: Double = DEFAULT_PTS)

    /**
     * One past Nifty expiry [day]: at 09:30 the options' implied price [at930], [maxPain] and the strike with the most open
     * interest [oiStrike]; the same at 14:30 ([at1430], [lateMaxPain]; null when the chain cannot be read then); and the
     * settlement [settle] (the options' implied price averaged over 15:00 to 15:29).
     */
    data class Day(val day: LocalDate, val at930: Double, val maxPain: Double, val oiStrike: Double, val settle: Double,
                   val at1430: Double? = null, val lateMaxPain: Double? = null)

    /** Fewer expiries than this: too few to say anything. */
    const val MIN_DAYS = 10
    /** At most this many of the newest expiries are read. */
    const val MAX_DAYS = 150
    /** The distance asked of when none is named (points): one Nifty strike step. */
    const val DEFAULT_PTS = 50.0
    /** The morning read (IST minute of day): 09:30. */
    const val READ_AT = 9 * 60 + 30
    /** The late read: 14:30. */
    const val LATE_AT = 14 * 60 + 30
    /** An expiry is settled once the market has closed. */
    private val CLOSED: LocalTime = LocalTime.of(15, 30)

    const val NOTE = "A record of past expiries on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep past expiry-day chains for Nifty only, Boss, so the pin record is Nifty's."
    const val METHOD = "Settled here means the average over 15:00 to 15:29 of the price the options themselves imply, the way NSE settles - the chains I keep carry no index print. Max pain is worked from open interest over the strikes kept, and is a reading of open interest, never a pull on price."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "
    private fun k(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun share(n: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(n * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun dateY(d: LocalDate) = "${date(d)} ${d.year}"
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    // ---- the questions --------------------------------------------------------------------------------------------

    private const val MP = " (max pain|maxpain|max pain strike|biggest oi|highest oi|max oi|maximum oi|most oi|biggest open interest|highest open interest|most open interest|oi strike|oi wall) "
    private const val EXP = " (expiry|expiries|expiry day|expiry days|expiration|expirations|expiry session|expiry sessions|weekly expiry|weekly expiries) "
    private const val PIN = " (pin|pins|pinned|pinning|pin record|pin effect|gets pinned|get pinned) "
    private const val CLOSE = " (close|closes|closed|closing|settle|settles|settled|settling|settlement|end|ends|ended|finish|finishes|finished|expire|expires|expired|" +
        "land|lands|landed|near|nearer|within|around|close to|close at|stick|sticks|gravitate|gravitates|band|hota|hoti|aata|aati|paas) "
    private const val HOW = " (how often|how many times|how close|how far|how near|does|do|did|is|are|usually|normally|typically|generally|tend to|tends to|on average|" +
        "record|history|historically|stats|statistics|work|works|worked|accurate|accuracy|reliable|hit rate|kitni baar|aksar|kya|kitna) "
    /** "Does max pain work on expiry?": whether it has held, asked outright. */
    private const val WORK = " (work|works|worked|hold|holds|held|accurate|accuracy|reliable|hit rate|come true|comes true|sahi) "
    /** "Expiry pin record", "max pain hit rate": the record named outright. */
    private const val NAMED = " (expiry pin|max pain pin|pin) (record|stats|statistics|history|hit rate) | max pain (record|track record|hit rate|accuracy) on expir| max pain (on|across|over) (past|previous|old|all) expiries "
    // Forecasts, advice, Boss's own book, today's or the next expiry (today's read is the chain's own), a single past
    // expiry, meanings, alerts and bots.
    private const val NOT = " (will|would|going to|gonna|tomorrow|tomorrows|predict|prediction|forecast|outlook|expected|expect|target|should|shall|buy|sell|" +
        "i|me|my|mine|we|our|what if|suppose|agar|today|todays|aaj|now|right now|abhi|this expiry|next expiry|this week|kal|" +
        "last expiry|latest expiry|previous expiry|pichli expiry|when did|" +
        "alert|alerts|notify|remind|watch|arm|bot|bots|algo|strategy|strategies|backtest|news|" +
        "mean|means|meaning|define|explain|what is|what s|whats|what are|kya hota|kya hai matlab) "

    /** What was asked, or null: the record of past expiries only, never where things stand today or a forecast. */
    fun asked(text: String): Q? {
        val t = norm(text)
        if (rx(NOT).containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        val mp = rx(MP).containsMatchIn(t); val exp = rx(EXP).containsMatchIn(t)
        val ok = rx(NAMED).containsMatchIn(t) ||
            (rx(PIN).containsMatchIn(t) && (exp || mp)) ||
            (mp && exp && ((rx(CLOSE).containsMatchIn(t) && rx(HOW).containsMatchIn(t)) || rx(WORK).containsMatchIn(t)))
        return if (ok) Q(within(text)) else null
    }

    /** The distance named ("within 100 points", "30 pts"), else [DEFAULT_PTS]; only 5 to 1,000 points is read as one. */
    private fun within(text: String): Double {
        val m = rx("(\\d{1,4})\\s*(points|point|pts|pt|ank)\\b").find(text.lowercase(Locale.ENGLISH)) ?: return DEFAULT_PTS
        return m.groupValues[1].toDoubleOrNull()?.takeIf { it in 5.0..1000.0 } ?: DEFAULT_PTS
    }

    /** Nifty when nothing else is named; null when only another index is (the chains kept are Nifty's). */
    fun market(markets: List<Market>): Market? =
        if (markets.isEmpty() || Market.NIFTY in markets || markets.all { it == Market.VIX }) Market.NIFTY else null

    // ---- the reading ----------------------------------------------------------------------------------------------

    /** Max pain over [oi] (strike to call OI, put OI): the strike where the writers' total loss at expiry is least. */
    internal fun maxPain(oi: Map<Double, Pair<Long, Long>>): Double? {
        val s = oi.filter { it.key > 0 && (it.value.first > 0 || it.value.second > 0) }
        if (s.isEmpty()) return null
        return s.keys.sorted().minByOrNull { k ->
            s.entries.sumOf { (o, v) -> (if (k > o) (k - o) * v.first else 0.0) + (if (k < o) (o - k) * v.second else 0.0) }
        }
    }

    /** Each strike's call and put open interest at [minute] (the last bar at or before it). */
    private fun oiAt(opts: List<Series>, minute: Int): Map<Double, Pair<Long, Long>> {
        val out = HashMap<Double, Pair<Long, Long>>()
        for (s in opts) {
            val i = s.lastAtOrBefore(minute); if (i < 0) continue
            val v = s.oi[i]; val was = out[s.strike] ?: (0L to 0L)
            out[s.strike] = if (s.right == Right.CE) (v to was.second) else (was.first to v)
        }
        return out
    }

    private fun forwardAt(opts: List<Series>, minute: Int): Double? =
        runCatching { ExpiryPut.parityForward(ExpiryPut.snapshot(opts, minute)) }.getOrNull()?.takeIf { it.isFinite() && it > 0 }

    /** [s] read as one expiry, or null when it is not one the phone can read whole (no 09:30 read, no settlement window). */
    fun day(s: Session): Day? {
        val opts = s.options.filter { it.expiry == s.day && it.size > 0 && (it.right == Right.CE || it.right == Right.PE) }
        if (opts.none { it.minutes[0] <= READ_AT }) return null
        val f930 = forwardAt(opts, READ_AT) ?: return null
        val oi = oiAt(opts, READ_AT)
        val mp = maxPain(oi) ?: return null
        val top = oi.filter { it.value.first + it.value.second > 0 }.maxByOrNull { it.value.first + it.value.second }?.key ?: return null
        val window = opts.flatMap { o -> o.minutes.filter { it in ExpiryPut.SETTLE_FROM until ExpiryPut.SETTLE_TO }.toList() }.toSortedSet()
        val fs = window.mapNotNull { m -> runCatching { ExpiryPut.parityForward(ExpiryPut.minuteSlice(opts, m)) }.getOrNull()?.takeIf { it.isFinite() && it > 0 } }
        if (fs.isEmpty()) return null
        val f1430 = forwardAt(opts, LATE_AT)
        val late = if (f1430 != null) maxPain(oiAt(opts, LATE_AT)) else null
        return Day(s.day, f930, mp, top, fs.average(), f1430, late)
    }

    /**
     * The settled expiries among [sessions] (streamed one at a time; only each day's few numbers are kept), oldest first,
     * the newest [MAX_DAYS]: none after [today], and today's only once the market has closed at [now].
     */
    fun days(sessions: Sequence<Session>, today: LocalDate, now: LocalDateTime): List<Day> {
        val out = HashMap<LocalDate, Day>()
        for (s in sessions) {
            if (s.day.isAfter(today) || (s.day == today && now.toLocalTime().isBefore(CLOSED))) continue
            runCatching { day(s) }.getOrNull()?.let { out[it.day] = it }
        }
        return out.values.sortedBy { it.day }.takeLast(MAX_DAYS)
    }

    // ---- the answer -----------------------------------------------------------------------------------------------

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private fun versus(a: Int, b: Int, n: Int): String = when {
        abs(a - b) <= maxOf(1, n / 20) -> "about as often as"
        a > b -> "more often than"
        else -> "less often than"
    }

    /**
     * The answer to [q] from the past expiries [days] (oldest first); [latest] is today's newest Nifty chain read when there
     * is one (said as it stands, with its time).
     */
    fun answer(q: Q, days: List<Day>, latest: ChainIntel.Read?, today: LocalDate): String {
        val x = q.within
        val parts = ArrayList<String>()
        if (days.size < MIN_DAYS) {
            parts += if (days.isEmpty()) "I have no past Nifty expiry day with its option chain on this phone, Boss, so there is no pin record to tell."
            else "I have only ${days.size} past Nifty expiry ${if (days.size == 1) "day" else "days"} with the option chain on this phone, Boss - too few to call it a record (I need $MIN_DAYS)."
            days.lastOrNull()?.let { parts += newest(it) }
            now(latest, today)?.let { parts += it }
            return parts.joinToString(" ")
        }
        val n = days.size
        val nearMp = days.count { abs(it.settle - it.maxPain) <= x }
        val nearOi = days.count { abs(it.settle - it.oiStrike) <= x }
        val nearOpen = days.count { abs(it.settle - it.at930) <= x }
        val within = "within ${pts(x)} points"
        parts += "Over the last $n Nifty expiries on this phone (${dateY(days.first().day)} to ${dateY(days.last().day)}), Boss: Nifty settled $within of max pain as it stood at 09:30 " +
            "on $nearMp (${share(nearMp, n)}), and $within of the strike with the most open interest on $nearOi (${share(nearOi, n)})."
        parts += "For scale, it settled $within of where it already stood at 09:30 on $nearOpen (${share(nearOpen, n)}) - so max pain was hit ${versus(nearMp, nearOpen, n)} " +
            "simply where Nifty was that morning."
        parts += "Median distance at the settle: ${pts(median(days.map { abs(it.settle - it.maxPain) }))} points from max pain, " +
            "${pts(median(days.map { abs(it.settle - it.oiStrike) }))} from the biggest open-interest strike, ${pts(median(days.map { abs(it.settle - it.at930) }))} from the 09:30 level."
        val late = days.filter { it.at1430 != null && it.lateMaxPain != null }
        if (late.size >= MIN_DAYS) {
            val lm = late.count { abs(it.settle - it.lateMaxPain!!) <= x }; val ls = late.count { abs(it.settle - it.at1430!!) <= x }
            parts += "Read again at 14:30 (${late.size} expiries): within ${pts(x)} points of max pain then on $lm (${share(lm, late.size)}), of where Nifty stood then on $ls (${share(ls, late.size)})."
        }
        val far = days.maxByOrNull { abs(it.settle - it.maxPain) }!!
        parts += "The farthest: ${dateY(far.day)}, max pain ${k(far.maxPain)} at 09:30, settled near ${k(far.settle)} - ${pts(far.settle - far.maxPain)} points away."
        parts += newest(days.last())
        now(latest, today)?.let { parts += it }
        parts += METHOD
        parts += NOTE
        return parts.joinToString(" ")
    }

    private fun newest(d: Day): String =
        "The newest on the phone, ${dateY(d.day)}: max pain ${k(d.maxPain)} at 09:30, the most open interest at ${k(d.oiStrike)}, settled near ${k(d.settle)} " +
            "(${pts(d.settle - d.maxPain)} points from max pain)."

    /** Today's newest Nifty chain read, as it stands: its max pain and spot with its time (null when there is none today). */
    private fun now(r: ChainIntel.Read?, today: LocalDate): String? {
        if (r == null || r.at.toLocalDate() != today || r.underlying != "NIFTY") return null
        val mp = ChainDrift.maxPain(r) ?: return null
        val exp = if (r.expiry == today) "expiring today" else "expiry ${date(r.expiry)}"
        return "My newest read of today's chain ($exp, at ${hm(r.at)}): max pain ${k(mp)}, Nifty ${k(r.spot)} - where it stands, not where it will settle."
    }
}
