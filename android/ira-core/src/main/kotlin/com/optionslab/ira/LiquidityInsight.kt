package com.optionslab.ira

import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.LiquidityShadow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * "What's working" (Boss, 07 Oct 2026): "what's working for liquidity", "where does liquidity lose", "liquidity patterns",
 * "liquidity kahan loss karta hai" - Liquidity 15+5's own closed paper trades cut six ways, and once a week (Saturday
 * morning, when at least [MIN_NEW] trades closed since the last look) the same as one note.
 *
 * The cuts ([Dim]): the entry's time of day (09:20-10:30, 10:30-12:00, 12:00-13:30, 13:30-14:00, and after 14:00 for
 * trades from before the 14:00 last entry), the index and book (BankNifty 5/15-min, FinNifty 5/30-min), the side (CE/PE),
 * the exit reason, the room from the level broken to the next liquidity level at the entry (in index-stop units, when the
 * levels were recorded, on trades closed since the room filter went on - [ROOM_SINCE]) and the day of the week. A group is
 * shown only with at least [MIN_CUT] trades: its trades, win rate and net a lot a trade after charges.
 *
 * The significance guard: a group "stands out" only when its mean net a lot a trade differs from the mean of the rest of
 * its cut (the other trades with that cut recorded, at least [MIN_CUT] of them) beyond a two-sided 95% Welch t-test: the
 * difference over its standard error (SE = sqrt(s1^2/n1 + s2^2/n2), each s the sample standard deviation) past the Student
 * t critical value ([tCritical]) at the Welch-Satterthwaite degrees of freedom ([welchDf]). Otherwise: no cut stands out
 * yet. Exit reasons are outcomes, not something known at the entry, so they are never "standing out": their mix is set
 * against the research's ([TradeLesson]'s shares) with a binomial standard error instead ([Z] of them).
 *
 * Research, where the repo pins any: the backtest's net a trade and its band ([ForwardCheck.LIQUIDITY]), the five-year
 * study's exit mix ([TradeLesson]), the last-entry study (entries after 14:00 lost in both BankNifty years:
 * [LATE_TRADES], [LATE_NET]; research/ENTRY_CUTOFF.md) and the room filter's finding (breaks with under one index stop of
 * room did worse: [LiquidityRules.MIN_ROOM_STOPS]). It says plainly when the paper record agrees, disagrees, or cannot tell.
 *
 * Words only - never advice: nothing here changes a rule, a size or a switch, and it never says to trade more or bigger. At
 * most "worth watching"; a rule change stays Boss's call after enough trades, through the research first ([END]). Pure: no
 * clock, no storage.
 */
object LiquidityInsight {
    /** What was asked first: what works, where it loses, or every pattern. */
    enum class Focus { WORKING, LOSING, PATTERNS }

    /** The six cuts, in the order told. [atEntry]: known when the trade was entered (exits are outcomes). */
    enum class Dim(val words: String, val atEntry: Boolean = true) {
        TIME("entry time"), BOOK("index and book"), SIDE("side"), EXIT("exit", atEntry = false),
        ROOM("room from the level broken to the next level at entry"), WEEKDAY("day of the week")
    }

    /** A group is shown (and a rest set against it) only with at least this many trades. */
    const val MIN_CUT = 8
    /** The weekly note needs at least this many trades closed since the last one. */
    const val MIN_NEW = 10
    /** An exit's share is beyond noise past this many binomial standard errors from the research's. */
    const val Z = 2.0

    const val LOCKED = LiquidityRecord.LOCKED
    const val NOTE = "From the arm's own paper book - facts, not advice."
    /** Every answer and note ends here: nothing changes from it; a rule change is Boss's own, after enough trades. */
    const val END = "Nothing changes from this: the arm's rules, lots and switch stay as they are. A rule change stays your call " +
        "after enough trades, through the research first."
    const val NONE = "No cut stands out yet - too few trades, or the differences are within noise (a cut stands out only when its " +
        "average a trade differs from the rest's beyond a 95% t-test that allows for small groups)."

    /** Where the app keeps the Saturday the week's look was made, and the newest exit it counted. */
    const val KEY_DONE = "jarvis.liqinsight.done"
    const val KEY_MARK = "jarvis.liqinsight.mark"
    /** Both keys' prefix: this phone's alone (they follow the arms' book, which never leaves it - [Upkeep.PRIVATE]). */
    const val KEY_PREFIX = "jarvis.liqinsight."
    /** The weekly note's Saturday-morning window. */
    val FROM: LocalTime = LocalTime.of(8, 30)
    val UNTIL: LocalTime = LocalTime.of(12, 0)

    // ---- the research, pinned -----------------------------------------------------------------------------------------
    /**
     * research/ENTRY_CUTOFF.md, Liquidity 15+5 (BankNifty, two years, 1 lot, after costs): a 14:30 last entry against 14:00
     * added 39 + 35 trades and Rs -7,341 and Rs -3,311 (both years worse) - why the arm stops entering at 14:00.
     */
    const val LATE_TRADES = 74
    const val LATE_NET = -10_652.0

    /**
     * The room filter ([LiquidityRules.hasRoom], research liq2) went on with the new rules on 06 Oct 2026
     * ([LiquidityShadow.SINCE]): trades closed before it were not filtered, so the room cut leaves them out.
     */
    val ROOM_SINCE: LocalDate = LiquidityShadow.SINCE

    // ---- the question --------------------------------------------------------------------------------------------------

    private fun norm(text: String) = Spaced.joined(text)

    private val LIQ = Regex(" (liquidity|liquiditys|liqudity|liquidty) ")
    private val WORKING = Regex(" ((whats|what is|what s|what are|whats been|what has been) (working|going well|going right|clicking)|what works|" +
        "where (does|do|is) (liquidity|it) (win|winning|do well|doing well|make money|making money)|when (does|do) (liquidity|it) (win|do well|make money)|" +
        "(liquidity|it) (wins|does well|makes money) (where|when)|what (is |s )?(liquidity|it) good at|" +
        "kya (kaam kar raha|chal raha|kaam karta|kaam aa raha)|kahan (jeet|jeetta|jeette|profit|kamata|kamaata|faayda|fayda)|kaha (jeet|jeetta|profit|kamata)) ")
    private val LOSING = Regex(" (where (does|do|is) (liquidity|it) (lose|losing|lose money|losing money|go wrong|struggle|struggling|bleed|bleeding)|" +
        "when (does|do) (liquidity|it) (lose|lose money|go wrong|struggle)|(liquidity|it) (loses|loses money|goes wrong) (where|when)|" +
        "(whats|what is|what s) not working|what isnt working|what doesnt work|where (are|do|does) (its|the|liquiditys)? ?(losses|losers) (come from|coming from)|" +
        "weak (spot|spots|point|points)|kahan (loss|haar|haarta|haarti|nuksan|nuksaan|ghata)|kaha (loss|haar|haarta|nuksan)|loss kahan|loss kaha|" +
        "(kab|kis time|kis waqt) (loss|haar|haarta|nuksan)) ")
    private val PATTERNS = Regex(" (pattern|patterns|insight|insights|cuts|breakdown of what works) ")
    /**
     * Not this: a change or a switch (never from here), a backtest or the shadows, today alone (the day's own answers), its
     * levels or lots, why one trade went as it did, a definition, the record by index (LiquidityRecord's "which index works
     * best") or another arm.
     */
    private val NOT = Regex(" (should|shall|set|change|changes|changing|stop it|switch|turn on|turn off|disable|enable|karo|kar do|kardo|band karo|" +
        "backtest|back test|backtested|shadow|shadows|candidate|candidates|today|todays|aaj|tomorrow|kal|level|levels|lots|lot|size|sizing|" +
        "why|kyun|kyon|kyu|what is a|define|meaning|mean by|which index|which book|hero|solo|orb|gold|pine|nifty pattern|chart pattern|candle pattern|candlestick) ")

    /** A second question said after it ("... and what is my pnl"): left to the splitter, each answered on its own. */
    private val AND = Regex(" (and|aur|also|then|phir) (what|whats|how|hows|is|are|when|why|tell|show|give|check|kya|kitna|nifty|banknifty|my|mera|meri|mere) ")
    private val BOTH_SIDES = Regex(" (what works and what doesnt|whats working and whats not|what is working and what is not|whats working and what isnt) ")

    /** Is "what's working" for Liquidity 15+5 asked? Null when not. */
    fun asked(text: String): Focus? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Focus?>(64)

    private fun askedFresh(text: String): Focus? {
        val t = norm(text)
        if (!LIQ.containsMatchIn(t) || NOT.containsMatchIn(t)) return null
        // "What works and what doesn't" is one question (every pattern), never two.
        val one = BOTH_SIDES.replace(t, " every pattern ")
        if (AND.containsMatchIn(one)) return null
        return when {
            PATTERNS.containsMatchIn(one) -> Focus.PATTERNS
            LOSING.containsMatchIn(one) -> Focus.LOSING
            WORKING.containsMatchIn(one) -> Focus.WORKING
            else -> null
        }
    }

    // ---- the cuts ------------------------------------------------------------------------------------------------------

    /**
     * The entry-time bucket of [t] (an entry before 09:20 counts in the first; 13:30 to the 14:00 last entry in
     * "13:30-14:00"; one after 14:00 - from before the last entry moved to 14:00 on 30 Sep - in its own "after 14:00").
     */
    fun timeBucket(t: LocalTime): String = when {
        t.isBefore(LocalTime.of(10, 30)) -> "09:20-10:30"
        t.isBefore(LocalTime.of(12, 0)) -> "10:30-12:00"
        t.isBefore(LocalTime.of(13, 30)) -> "12:00-13:30"
        !t.isAfter(LiquidityRules.LAST_ENTRY) -> "13:30-14:00"
        else -> AFTER_LAST
    }
    private const val AFTER_LAST = "after 14:00"
    private val TIMES = listOf("09:20-10:30", "10:30-12:00", "12:00-13:30", "13:30-14:00", AFTER_LAST)

    /**
     * The room from the level the entry broke to the next level ahead, in index-stop units (BankNifty 30 points, FinNifty 15),
     * at the entry; +infinity with no level ahead; null when the levels were not recorded. (The room filter measures from
     * the deciding bar's close, which the book does not keep: this is the room from the level broken.)
     */
    fun roomStops(r: LiquidityRecord.Row): Double? {
        val level = r.trade.level ?: return null
        val target = r.trade.target ?: return Double.POSITIVE_INFINITY
        val side = if (r.trade.right.equals("PE", true)) -1 else 1
        return side * (target - level) / LiquidityRules.indexStopPoints(r.index)
    }

    /** A room in stop units, as a bucket. */
    fun roomBucket(stops: Double?): String? = when {
        stops == null -> null
        stops.isInfinite() -> "no level ahead"
        stops < 2 -> "under 2 index stops"
        stops < 4 -> "2-4 index stops"
        else -> "4+ index stops"
    }
    private val ROOMS = listOf("under 2 index stops", "2-4 index stops", "4+ index stops", "no level ahead")
    private val BOOKS = listOf("BankNifty 5-min", "BankNifty 15-min", "FinNifty 5-min", "FinNifty 30-min")
    private val DAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).map { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH) }

    private fun side(r: LiquidityRecord.Row) = if (r.trade.right.equals("PE", true)) "PE" else "CE"

    /** [r]'s group in [d], or null when that cut is not recorded for it. */
    fun keyOf(d: Dim, r: LiquidityRecord.Row): String? = when (d) {
        Dim.TIME -> timeBucket(r.trade.entryTime.toLocalTime())
        Dim.BOOK -> r.book
        Dim.SIDE -> side(r)
        Dim.EXIT -> r.why
        Dim.ROOM -> if (beforeRoomFilter(r)) null else roomBucket(roomStops(r))
        Dim.WEEKDAY -> r.day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    }

    /** Did [r] close before the room filter went on ([ROOM_SINCE])? Such a trade is left out of the room cut. */
    fun beforeRoomFilter(r: LiquidityRecord.Row): Boolean = (r.trade.exitTime?.toLocalDate() ?: r.day).isBefore(ROOM_SINCE)

    /** Mean and sample standard deviation (null under 2 values). */
    data class Stats(val n: Int, val mean: Double, val sd: Double?)

    fun stats(xs: List<Double>): Stats {
        val n = xs.size
        if (n == 0) return Stats(0, 0.0, null)
        val mean = xs.sum() / n
        val sd = if (n < 2) null else sqrt(xs.sumOf { (it - mean) * (it - mean) } / (n - 1))
        return Stats(n, mean, sd)
    }

    /**
     * The standard error of the difference between two means (Welch): sqrt(s1^2/n1 + s2^2/n2); null when either side has
     * fewer than 2 values.
     */
    fun standardError(a: Stats, b: Stats): Double? {
        val sa = a.sd ?: return null
        val sb = b.sd ?: return null
        return sqrt(sa * sa / a.n + sb * sb / b.n)
    }

    /**
     * The Welch-Satterthwaite degrees of freedom of the difference between two means:
     * (s1^2/n1 + s2^2/n2)^2 / ((s1^2/n1)^2/(n1-1) + (s2^2/n2)^2/(n2-1)); null when either side has fewer than 2 values or
     * there is no spread at all.
     */
    fun welchDf(a: Stats, b: Stats): Double? {
        val sa = a.sd ?: return null
        val sb = b.sd ?: return null
        val va = sa * sa / a.n
        val vb = sb * sb / b.n
        val den = va * va / (a.n - 1) + vb * vb / (b.n - 1)
        if (den <= 0.0) return null
        return (va + vb) * (va + vb) / den
    }

    /** Two-sided 95% Student t critical values (the 97.5th percentile) for 1..30 degrees of freedom. */
    private val T975 = doubleArrayOf(12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160,
        2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042)

    /**
     * The two-sided 95% Student t critical value at [df] degrees of freedom: the table for 1..30 (a fractional df rounds
     * down - the stricter value), beyond 30 the Cornish-Fisher expansion about the normal's 1.96 (t(60) = 2.000, t(120) =
     * 1.980), tending to 1.96.
     */
    fun tCritical(df: Double): Double {
        val d = floor(df).coerceAtLeast(1.0)
        if (d <= 30.0) return T975[d.toInt() - 1]
        val z = 1.959964
        val z3 = z * z * z
        val z5 = z3 * z * z
        return z + (z3 + z) / (4 * d) + (5 * z5 + 16 * z3 + 3 * z) / (96 * d * d)
    }

    /**
     * One group of a cut: [n] trades, [wins], [mean] net a lot a trade after charges; set against the [restN] other trades
     * of its cut ([restMean]) when there are at least [MIN_CUT] of them, with [se] the standard error of the difference and
     * [df] its Welch-Satterthwaite degrees of freedom.
     */
    data class Cut(val dim: Dim, val key: String, val n: Int, val wins: Int, val mean: Double, val restN: Int, val restMean: Double?, val se: Double?,
                   val df: Double? = null) {
        val winRate: Double get() = wins.toDouble() / n
        val diff: Double? get() = restMean?.let { mean - it }
        /** How many standard errors from the rest (Welch's t); null when it could not be set against one. */
        val z: Double? get() = if (se == null || se <= 0.0 || diff == null || df == null) null else diff!! / se
        val tested: Boolean get() = dim.atEntry && z != null
        /** Beyond the two-sided 95% t critical value at [df]. */
        val standsOut: Boolean get() = tested && abs(z!!) > tCritical(df!!)
    }

    /** Every group of [d] among [rows] with at least [MIN_CUT] trades, in the cut's own order. */
    fun cuts(d: Dim, rows: List<LiquidityRecord.Row>): List<Cut> {
        val keyed = rows.mapNotNull { r -> keyOf(d, r)?.let { it to r } }
        val groups = keyed.groupBy({ it.first }, { it.second })
        return order(d, groups.keys).mapNotNull { k ->
            val mine = groups.getValue(k)
            if (mine.size < MIN_CUT) return@mapNotNull null
            val rest = keyed.filter { it.first != k }.map { it.second.perLot }
            val a = stats(mine.map { it.perLot })
            val b = stats(rest)
            val tested = rest.size >= MIN_CUT
            Cut(d, k, mine.size, mine.count { it.win }, a.mean, rest.size, if (tested) b.mean else null, if (tested) standardError(a, b) else null,
                if (tested) welchDf(a, b) else null)
        }
    }

    /** The groups of [d] that have fewer than [MIN_CUT] trades (left out). */
    fun smallGroups(d: Dim, rows: List<LiquidityRecord.Row>): Int =
        rows.mapNotNull { keyOf(d, it) }.groupingBy { it }.eachCount().count { it.value < MIN_CUT }

    private fun order(d: Dim, keys: Set<String>): List<String> {
        val fixed = when (d) {
            Dim.TIME -> TIMES; Dim.BOOK -> BOOKS; Dim.SIDE -> listOf("CE", "PE"); Dim.ROOM -> ROOMS; Dim.WEEKDAY -> DAYS; Dim.EXIT -> emptyList()
        }
        return fixed.filter { it in keys } + keys.filter { it !in fixed }.sorted()
    }

    /** The whole read: every cut's groups, the ones that stand out (one of a complementary pair), and how many were tested. */
    data class Read(val rows: List<LiquidityRecord.Row>, val cuts: Map<Dim, List<Cut>>) {
        val tested: Int get() = cuts.values.flatten().count { it.tested }
        val standing: List<Cut> get() = cuts.values.flatMap { cs ->
            // A cut of exactly two groups (CE / PE): each is the other's rest - one finding, told once.
            val out = cs.filter { it.standsOut }
            if (out.size == 2 && out[0].n == out[1].restN && out[1].n == out[0].restN) listOf(out.maxByOrNull { it.diff!! }!!) else out
        }
    }

    fun read(rows: List<LiquidityRecord.Row>): Read = Read(rows, Dim.entries.associateWith { cuts(it, rows) })

    // ---- the research's exit mix ---------------------------------------------------------------------------------------

    /** An exit reason's share (0-1) of all the study's trades, or null when the study has no such exit. */
    fun researchExitShare(why: String): Double? {
        val w = TradeLesson.WIN_SHARE[why]; val l = TradeLesson.LOSS_SHARE[why]
        if (w == null && l == null) return null
        return ((w ?: 0.0) * TradeLesson.WINNERS + (l ?: 0.0) * TradeLesson.LOSERS) / (100.0 * (TradeLesson.WINNERS + TradeLesson.LOSERS))
    }

    /** -1 / 0 / +1: [k] of [n] trades is less than, within, or more than 2 binomial standard errors from the research's [p0]. */
    fun shareVs(k: Int, n: Int, p0: Double): Int {
        if (n <= 0) return 0
        val se = sqrt(p0 * (1 - p0) / n)
        val d = k.toDouble() / n - p0
        return if (se <= 0.0 || abs(d) <= Z * se) 0 else if (d > 0) 1 else -1
    }

    // ---- words ---------------------------------------------------------------------------------------------------------

    private fun rs(x: Double) = LiquidityRecord.rs(x)
    private fun pct(x: Double) = "${(100 * x).roundToInt()}%"
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun s(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun z1(z: Double) = String.format(Locale.ENGLISH, "%.1f", abs(z))

    /** A group named as said: "entries 10:30-12:00", "BankNifty 5-min", "CE trades", "room 2-4 index stops", "Tuesdays". */
    fun name(c: Cut): String = when (c.dim) {
        Dim.TIME -> "entries ${c.key}"
        Dim.BOOK -> c.key
        Dim.SIDE -> "${c.key} trades"
        Dim.EXIT -> LiquidityRecord.reason(c.key)
        Dim.ROOM -> if (c.key == "no level ahead") "entries with no level ahead" else "room ${c.key} from the level broken"
        Dim.WEEKDAY -> c.key + "s"
    }

    private fun stand(c: Cut): String =
        "${name(c)} - ${s(c.n, "trade")}, ${pct(c.winRate)} won, ${rs(c.mean)} a lot a trade vs ${rs(c.restMean!!)} for the other ${c.restN} " +
            "(${z1(c.z!!)} standard errors ${if (c.diff!! > 0) "better" else "worse"})"

    private fun group(c: Cut): String = "${c.key} ${s(c.n, "trade")}, ${pct(c.winRate)} won, ${rs(c.mean)}"

    private fun dimLine(d: Dim, read: Read): String? {
        val cs = read.cuts.getValue(d)
        if (cs.isEmpty()) return null
        val small = smallGroups(d, read.rows)
        val more = if (small > 0) " (${s(small, "smaller group")} under $MIN_CUT trades left out)" else ""
        return if (d == Dim.EXIT) "By exit: " + cs.sortedByDescending { it.n }.joinToString("; ") { c ->
            val share = researchExitShare(c.key)?.let { p0 ->
                val v = shareVs(c.n, read.rows.size, p0)
                " - ${pct(c.n.toDouble() / read.rows.size)} of trades vs ${pct(p0)} in the research" +
                    when (v) { 0 -> ", within noise"; 1 -> ", more often than noise explains"; else -> ", less often than noise explains" }
            } ?: " - not an exit in the research's mix"
            "${LiquidityRecord.reason(c.key)} ${s(c.n, "trade")} (${c.wins} won), ${rs(c.mean)} a lot a trade$share"
        } + "$more."
        else "By ${d.words}: " + cs.joinToString("; ") { group(it) } + " (a lot a trade" + (if (small > 0) "; ${s(small, "smaller group")} under $MIN_CUT trades left out" else "") + ")."
    }

    /** The backtest against the whole record. */
    private fun backtestLine(read: Read): String {
        val e = ForwardCheck.LIQUIDITY
        val st = stats(read.rows.map { it.perLot })
        val head = "Against the backtest (${rs(e.mean)} a lot a trade, ${pct(e.winRate)} won): "
        if (st.n < ForwardCheck.MIN_TRADES) return head + "${s(st.n, "trade")} ${if (st.n == 1) "is" else "are"} too few to judge (it takes ${ForwardCheck.MIN_TRADES})."
        val b = ForwardCheck.band(e, st.n)!!
        return head + when {
            st.mean < b.low -> "the paper record's ${rs(st.mean)} is below what it allows for ${st.n} trades (${rs(b.low)} at least) - it disagrees."
            st.mean > b.high -> "the paper record's ${rs(st.mean)} is above what it allows for ${st.n} trades (${rs(b.high)} at most) - better than researched."
            else -> "the paper record's ${rs(st.mean)} is within what it allows for ${st.n} trades (${rs(b.low)} to ${rs(b.high)}) - it agrees."
        }
    }

    /** The last-entry study against the paper's latest entries. */
    fun timeResearchLine(read: Read): String {
        val head = "The research: entries after 14:00 lost in both BankNifty years ($LATE_TRADES trades, ${rs(LATE_NET)} a lot in all - " +
            "research/ENTRY_CUTOFF.md), which is why the arm stops entering at 14:00; it pins nothing for the hours before. "
        val late = read.cuts.getValue(Dim.TIME).firstOrNull { it.key == "13:30-14:00" }
        val k = read.rows.count { timeBucket(it.trade.entryTime.toLocalTime()) == "13:30-14:00" }
        return head + when {
            late == null -> "The paper record has too few 13:30-14:00 entries (${k}) to tell whether that weakness reaches back before 14:00."
            !late.tested -> "Its 13:30-14:00 entries have too few other trades to be set against yet."
            late.standsOut && late.diff!! < 0 -> "On paper, the 13:30-14:00 entries do worse than the rest beyond noise - the same direction as that finding."
            late.standsOut -> "On paper, the 13:30-14:00 entries do better than the rest beyond noise - against the direction of that finding."
            else -> "On paper, the 13:30-14:00 entries are within noise of the rest - no sign either way yet."
        }
    }

    /**
     * The room filter's finding against the paper's tightest room. The paper measures the room from the level broken (the
     * filter's deciding close is not kept on the trade), and only on trades closed since the filter went on ([ROOM_SINCE]).
     */
    fun roomResearchLine(read: Read): String {
        val before = read.rows.count { beforeRoomFilter(it) }
        val recorded = read.rows.count { !beforeRoomFilter(it) && roomStops(it) != null }
        val head = "The research found breaks with under ${LiquidityRules.MIN_ROOM_STOPS.roundToInt()} index stop of room to the next level did worse, so the arm skips them. " +
            "The paper's room is measured from the level broken, not the deciding close the filter uses" +
            (if (before > 0) "; the ${s(before, "trade")} closed before ${date(ROOM_SINCE)} (before the filter went on) ${if (before == 1) "is" else "are"} left out of the room cut. " else ". ")
        if (recorded == 0) return head + if (before > 0) "No trade closed since has its room recorded, so the paper record cannot speak to it yet."
            else "The room is not recorded on these trades, so the paper record cannot speak to it."
        val tight = read.cuts.getValue(Dim.ROOM).firstOrNull { it.key == "under 2 index stops" }
        return head + when {
            tight == null -> "Too few paper trades had under 2 stops of room to tell whether less room still does worse."
            !tight.tested -> "Its tightest-room trades have too few others to be set against yet."
            tight.standsOut && tight.diff!! < 0 -> "On paper, room under 2 stops does worse than the rest beyond noise - the same direction as the research."
            tight.standsOut -> "On paper, room under 2 stops does better than the rest beyond noise - against the research's direction."
            else -> "On paper, room under 2 stops is within noise of the rest - no sign either way yet."
        }
    }

    private fun lossesLine(rows: List<LiquidityRecord.Row>): String? {
        val losses = rows.filter { !it.win }
        if (losses.isEmpty()) return null
        val parts = losses.groupBy { it.why }.entries.sortedByDescending { it.value.size }.joinToString(", ") { (why, rs) ->
            val r = TradeLesson.LOSS_SHARE[why]?.let { " (${it.roundToInt()}% in the research)" }.orEmpty()
            "${rs.size} ${LiquidityRecord.reason(why)}$r"
        }
        return "Its ${s(losses.size, "loss", "losses")} ended: $parts."
    }

    /** The standing-out lines for [focus]. */
    private fun standLines(read: Read, focus: Focus): List<String> {
        val st = read.standing
        if (st.isEmpty()) return listOf(NONE)
        val good = st.filter { it.diff!! > 0 }.sortedByDescending { it.z!! }
        val bad = st.filter { it.diff!! < 0 }.sortedBy { it.z!! }
        val out = ArrayList<String>()
        fun goodLine() = "Stands out on the good side: " + good.joinToString("; ") { stand(it) } + "."
        fun badLine() = "Stands out on the weak side: " + bad.joinToString("; ") { stand(it) } + "."
        when (focus) {
            Focus.WORKING -> {
                if (good.isNotEmpty()) out += goodLine() else out += "Nothing stands out on the good side yet."
                if (bad.isNotEmpty()) out += badLine()
            }
            Focus.LOSING -> {
                if (bad.isNotEmpty()) out += badLine() else out += "Nothing stands out on the weak side yet."
                if (good.isNotEmpty()) out += goodLine()
            }
            Focus.PATTERNS -> { if (good.isNotEmpty()) out += goodLine(); if (bad.isNotEmpty()) out += badLine() }
        }
        out += "Worth watching, not proof: with ${s(read.tested, "group")} checked, about ${byChance(read.tested)} could stand out by chance even with no " +
            "real difference, and the record is young."
        return out
    }

    /** How many of [n] groups a 5% test lets through by chance alone: n/20, to one decimal ("0.6", "1", "1.5"). */
    fun byChance(n: Int): String = String.format(Locale.ENGLISH, "%.1f", n / 20.0).removeSuffix(".0")

    private fun head(focus: Focus, rows: List<LiquidityRecord.Row>): String {
        val st = stats(rows.map { it.perLot })
        val wins = rows.count { it.win }
        val span = "${date(rows.first().day)} - ${date(rows.last().day)}"
        val what = when (focus) {
            Focus.WORKING -> "what's working for Liquidity 15+5"
            Focus.LOSING -> "where Liquidity 15+5 loses"
            Focus.PATTERNS -> "Liquidity 15+5's patterns"
        }
        return "Boss, $what - its ${s(rows.size, "closed paper trade")} ($span): $wins won (${pct(wins.toDouble() / rows.size)}), " +
            "${rs(st.mean)} a lot a trade after charges."
    }

    /** Too few trades to cut (none, or under two cuts' worth): said plainly, or null when there are enough. */
    private fun tooFew(rows: List<LiquidityRecord.Row>): String? = when {
        rows.isEmpty() -> "Liquidity 15+5 has no closed paper trade in its book yet, Boss - nothing to look for patterns in. $NOTE"
        rows.size < 2 * MIN_CUT -> "Liquidity 15+5 has only ${s(rows.size, "closed paper trade")} so far, Boss - too few to cut into patterns " +
            "(each group needs $MIN_CUT trades and $MIN_CUT more to set it against; ask again after ${2 * MIN_CUT}). " +
            "No cut stands out yet - too few trades. $NOTE"
        else -> null
    }

    /**
     * The answer to [focus] from the arm's closed paper trades [all] ([LiquidityRecord.rows]' order: oldest exit first):
     * the head, what stands out (or that nothing does), each cut's groups, the research beside them, and [END].
     */
    fun answer(focus: Focus, all: List<LiquidityRecord.Row>): String {
        tooFew(all)?.let { return "$it\n$END" }
        val read = read(all)
        val out = ArrayList<String>()
        out += head(focus, all)
        out += standLines(read, focus)
        if (focus == Focus.LOSING) lossesLine(all)?.let { out += it }
        val missing = ArrayList<String>()
        for (d in Dim.entries) dimLine(d, read)?.let { out += it } ?: run { missing += d.words }
        if (missing.isNotEmpty()) out += "Too few trades yet to cut by ${missing.joinToString(", ")} (each group needs $MIN_CUT)."
        out += timeResearchLine(read)
        out += roomResearchLine(read)
        out += "The research pins no split by book, side or day of the week - those cuts are the paper record alone."
        out += backtestLine(read)
        out += NOTE
        out += END
        return out.joinToString("\n")
    }

    // ---- the weekly note -----------------------------------------------------------------------------------------------

    /** Is the week's look due at [now]: a Saturday morning ([FROM]-[UNTIL]) not yet looked at ([doneOn])? */
    fun due(now: LocalDateTime, doneOn: LocalDate?): Boolean =
        now.dayOfWeek == DayOfWeek.SATURDAY && !now.toLocalTime().isBefore(FROM) && now.toLocalTime().isBefore(UNTIL) && doneOn != now.toLocalDate()

    /** Trades closed after [mark] (the newest exit the last note counted; every trade when none). */
    fun newSince(rows: List<LiquidityRecord.Row>, mark: LocalDateTime?): Int =
        if (mark == null) rows.size else rows.count { it.trade.exitTime?.isAfter(mark) == true }

    /** The newest exit among [rows] (what the next note counts from), or null. */
    fun markOf(rows: List<LiquidityRecord.Row>): LocalDateTime? = rows.mapNotNull { it.trade.exitTime }.maxOrNull()

    /**
     * The week's note from [all] when at least [MIN_NEW] trades closed after [mark], else null (nothing said): the head,
     * what stands out or that nothing does, the research beside the paper record, and [END] - the cuts in full on asking.
     */
    fun weekly(all: List<LiquidityRecord.Row>, mark: LocalDateTime?): String? {
        val fresh = newSince(all, mark)
        if (fresh < MIN_NEW || all.size < 2 * MIN_CUT) return null
        val read = read(all)
        val out = ArrayList<String>()
        out += "What's working - Liquidity 15+5's week in patterns, Boss: ${s(all.size, "closed paper trade")}, $fresh new since the last look; " +
            "${all.count { it.win }} won, ${rs(stats(all.map { it.perLot }).mean)} a lot a trade after charges."
        out += standLines(read, Focus.PATTERNS)
        out += timeResearchLine(read)
        out += roomResearchLine(read)
        out += backtestLine(read)
        out += "Ask \"liquidity patterns\" for every cut."
        out += END
        return out.joinToString("\n")
    }
}
