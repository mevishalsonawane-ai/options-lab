package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The relative move record (market intelligence, round 31): "how often does BankNifty move more than Nifty in % on a day?",
 * "is Sensex more volatile than Nifty?", "relative volatility of BankNifty", "banknifty nifty se zyada kitni baar chalta
 * hai". From the whole sessions of 1-minute candles both indices have on the phone (the newest [MAX_DAYS] before today):
 * each day's range as % of its own open and each day's close-to-close change, set side by side - on how many days the one
 * asked of had the wider range and the larger change in size, the median ratio of the two ranges with its middle half, and
 * how often the two closed the same way. Beside today so far, when both have it. A record of past days on this phone, said
 * with its counts and plainly when there are too few; never a forecast or advice; nothing acts. Which index is stronger
 * today stays [Compare]'s, whether they move together today [Together]'s. Pure.
 */
object RelativeMove {
    /** What was asked: [subject] moving more (or less) than [base], as said; both null when no index was named. */
    data class Q(val subject: Market?, val base: Market?)

    /** One day both indices have whole: each one's range as % of its open, and its change from the session before (null: none). */
    data class Day(val day: LocalDate, val rangeA: Double, val rangeB: Double, val changeA: Double?, val changeB: Double?) {
        val ratio: Double? get() = if (rangeB > 0) rangeA / rangeB else null
    }

    /** Fewer common days than this: too few to say a record. */
    const val MIN_DAYS = 20
    /** Fewer common days than this: said as a small record. */
    const val FEW_DAYS = 60
    /** At most this many of the newest common days are read. */
    const val MAX_DAYS = 250
    private val FIRST_BY = LocalTime.of(9, 20)
    private val LAST_FROM = LocalTime.of(15, 25)
    private val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the relative move record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9%. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun x2(x: Double) = "%.2f times".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** Moving more (or less) than another, in English and Hinglish. */
    private val MORE = Regex(" (move|moves|moved|swing|swings|range|ranges|travel|travels|run|runs|chalta|chalti|chale|hilta|hilti|bhagta|bhagti) " +
        "(a lot |much |far |way )?(more|bigger|wider|less|further|zyada|jyada|zada|kam) |" +
        " (more|less|zyada|jyada|zada|kam) (volatile|swingy|movement|range|move karta|move karti) |" +
        " (zyada|jyada|zada|kam) (chalta|chalti|hilta|hilti|bhagta|bhagti) |" +
        " relative (volatility|vol|move|moves|movement|range|ranges) |" +
        " (bigger|wider|larger) (moves|move|range|ranges|swings|days) | (moves|swings|ranges) (bigger|wider|larger) |" +
        " (times|guna) (more|bigger|zyada|jyada) |" +
        " (which|kaun|kaunsa|konsa|kon sa|kaun sa) (one |index |of them |of the two )?(moves|swings|chalta|hilta) (more|zyada|jyada|the most) ")
    /** "Nifty se zyada ... chalta": "se zyada" with a verb of moving anywhere. */
    private val SE_MORE = Regex(" se (zyada|jyada|zada|kam) ")
    private val MOVES_HI = Regex(" (chalta|chalti|chalte|hilta|hilti|hilte|bhagta|bhagti|move karta|move karti|volatile|swing karta) ")
    private val RELATIVE = Regex(" relative (volatility|vol) ")
    /** Asked of today, now: the index's own day ([Compare], [Together]), not the record. */
    private val NOW = Regex(" (today|todays|aaj|aj|now|right now|abhi|this morning|since|so far|moving|chal raha|chal rahi|hil raha|hil rahi) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, stock screens, a definition,
    // options, gold or VIX, a reason, a week's or a month's move, expiry days, correlation (Together's).
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|kal|next|predict|prediction|forecast|outlook|expect|should|shall|buy|sell|enter|exit|" +
        "trade|trading|i|me|my|mine|we|our|if|what if|suppose|agar|imagine|scenario|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|backtest|" +
        "stock|stocks|scan|scanner|screener|share|shares|mean|means|meaning|define|explain|beta|gold|vix|fear|chart|" +
        "call|calls|put|puts|premium|premiums|option|options|ce|pe|strike|why|kyun|kyon|kyu|reason|week|weekly|weeks|hafte|month|monthly|year|yearly|" +
        "expiry|expiries|hedge|correlation|correlated|together|sync|with) ")

    /** What was asked, or null: the record of one index's day moves against another's only, never a forecast or advice. */
    fun asked(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t) || NOW.containsMatchIn(t)) return null
        if (!MORE.containsMatchIn(t) && !(SE_MORE.containsMatchIn(t) && MOVES_HI.containsMatchIn(t))) return null
        val named = order(t)
        if (named.size < 2 && !(RELATIVE.containsMatchIn(t) && named.size == 1)) return null
        val (a, b) = sides(t, named)
        return Q(a, b)
    }

    /** The indices named in [t], in the order said ("bank nifty" is never read as "nifty"). */
    private fun order(t: String): List<Pair<Market, Int>> {
        var s = t
        val at = LinkedHashMap<Market, Int>()
        for ((alias, m) in INDICES.flatMap { m -> m.aliases.map { " $it " to m } }.sortedByDescending { it.first.length }) {
            var i = s.indexOf(alias)
            while (i >= 0) {
                if (at[m] == null || i < at.getValue(m)) at[m] = i
                s = s.substring(0, i) + " ".repeat(alias.length) + s.substring(i + alias.length)
                i = s.indexOf(alias)
            }
        }
        return at.entries.sortedBy { it.value }.map { it.key to it.value }
    }

    /** Which is asked of and which it is set against: the one after "than", or the one just before "se", is the base. */
    private fun sides(t: String, named: List<Pair<Market, Int>>): Pair<Market?, Market?> {
        if (named.isEmpty()) return null to null
        if (named.size == 1) return named[0].first to null
        val than = t.indexOf(" than ")
        val se = t.indexOf(" se ")
        val base = when {
            than >= 0 -> named.firstOrNull { it.second > than }?.first
            se >= 0 -> named.lastOrNull { it.second < se }?.first
            else -> null
        } ?: named[1].first
        val subject = named.first { it.first != base }.first
        return subject to base
    }

    /** The two indices: as asked; one named is set against Nifty (Nifty against BankNifty); none, BankNifty against Nifty. Null for gold or VIX. */
    fun pair(q: Q, markets: List<Market>): Pair<Market, Market>? {
        if (markets.any { it == Market.GOLD || it == Market.VIX } && markets.none { it in INDICES }) return null
        val a = q.subject ?: markets.firstOrNull { it in INDICES } ?: Market.BANKNIFTY
        val b = q.base?.takeIf { it != a } ?: if (a == Market.NIFTY) Market.BANKNIFTY else Market.NIFTY
        return a to b
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /** Whole sessions only (their start and end on the phone). */
    private fun whole(s: MarketStory.Session): Boolean =
        s.bars.isNotEmpty() && s.open > 0 && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) && !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    private fun rangePct(s: MarketStory.Session): Double = s.range / s.open * 100

    /** The days before [today] both [aBars] and [bBars] have whole, newest [MAX_DAYS], oldest first. */
    fun days(aBars: List<Candle>, bBars: List<Candle>, today: LocalDate): List<Day> {
        val sa = MarketStory.sessions(aBars).filter { it.day.isBefore(today) }
        val sb = MarketStory.sessions(bBars).filter { it.day.isBefore(today) }
        val byA = sa.withIndex().associate { it.value.day to it.index }
        val byB = sb.withIndex().associate { it.value.day to it.index }
        val out = ArrayList<Day>()
        for ((day, ia) in byA) {
            val ib = byB[day] ?: continue
            val a = sa[ia]; val b = sb[ib]
            if (!whole(a) || !whole(b)) continue
            // A change only when both indices' sessions before are the same day (no gap in one of them).
            val pa = sa.getOrNull(ia - 1); val pb = sb.getOrNull(ib - 1)
            val same = pa != null && pb != null && pa.day == pb.day && pa.close > 0 && pb.close > 0
            out += Day(day, rangePct(a), rangePct(b),
                if (same) (a.close - pa!!.close) / pa.close * 100 else null,
                if (same) (b.close - pb!!.close) / pb.close * 100 else null)
        }
        return out.sortedBy { it.day }.takeLast(MAX_DAYS)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun quartile(xs: List<Double>, q: Double): Double { val s = xs.sorted(); return s[((s.size - 1) * q).toInt()] }

    /** [a] against [b] from their 1-minute candles over many days, at [now] on [today]. */
    fun answer(a: Market, b: Market, aBars: List<Candle>, bBars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val ds = days(aBars, bBars, today)
        if (ds.size < MIN_DAYS)
            return "I have only ${ds.size} whole day${if (ds.size == 1) "" else "s"} that both ${a.label} and ${b.label} have on the phone, Boss - too few to say which usually moves more (I need $MIN_DAYS)."
        val n = ds.size
        val wider = ds.count { it.rangeA > it.rangeB }
        val ratios = ds.mapNotNull { it.ratio }
        val lines = ArrayList<String>()
        lines += "Over the last $n whole days both ${a.label} and ${b.label} have on this phone (${date(ds.first().day)} to ${date(ds.last().day)}),"
        lines += "${a.label}'s day range, as % of its own open, was wider than ${b.label}'s on $wider of them (${share(wider, n)}); " +
            "the median day ${a.label} ranged ${p2(median(ds.map { it.rangeA }))} and ${b.label} ${p2(median(ds.map { it.rangeB }))}."
        if (ratios.isNotEmpty())
            lines += "On the median day ${a.label}'s range was ${x2(median(ratios))} ${b.label}'s (the middle half from ${x2(quartile(ratios, 0.25))} to ${x2(quartile(ratios, 0.75))})."
        val moved = ds.filter { it.changeA != null && it.changeB != null }
        if (moved.isNotEmpty()) {
            val bigger = moved.count { abs(it.changeA!!) > abs(it.changeB!!) }
            val sameWay = moved.count { it.changeA!! * it.changeB!! > 0 }
            lines += "Close to close, ${a.label}'s change was the larger in size on $bigger of ${moved.size} days (${share(bigger, moved.size)}), " +
                "and the two closed the same way on $sameWay (${share(sameWay, moved.size)})."
        }
        if (n < FEW_DAYS) lines += "That is only $n days, so a few days move these figures a lot."
        todayLine(a, b, aBars, bBars, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today so far, when both have a session today: each one's range as % of its open. */
    private fun todayLine(a: Market, b: Market, aBars: List<Candle>, bBars: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val ta = MarketStory.sessions(aBars).lastOrNull { it.day == today }?.takeIf { it.open > 0 } ?: return null
        val tb = MarketStory.sessions(bBars).lastOrNull { it.day == today }?.takeIf { it.open > 0 } ?: return null
        val ra = rangePct(ta); val rb = rangePct(tb)
        if (rb <= 0) return null
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(LocalTime.of(15, 30))
        return "Today ${if (live) "so far" else "on the phone"}, ${a.label} has ranged ${p2(ra)} of its open and ${b.label} ${p2(rb)} - ${x2(ra / rb)}."
    }
}
