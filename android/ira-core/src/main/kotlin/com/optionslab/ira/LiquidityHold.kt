package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * How long Liquidity 15+5 holds its trades (Boss, 07 Oct 2026): "how long does liquidity hold its trades", "liquidity hold
 * time", "how long do liquidity trades last this week", "do liquidity's losers last longer than its winners", "liquidity ke
 * trades kitni der chalte hain". From the arm's own book of closed paper trades ([LiquidityRecord.rows]) over the span asked
 * ([LiquidityRecord.spanOf]: this or last week, a month, a day, the last N trades, every one): the typical (median) time in a
 * trade, the shortest and the longest, winners against losers, the quick exits (under [QUICK_MIN] minutes) and what they
 * made, the median by exit reason, and one plain-language takeaway - facts, never advice.
 *
 * Read only: nothing is armed, stopped, placed, closed or changed from it; the arm's rules stay Boss's call. Pure: no clock,
 * no storage - the trades and today are handed in.
 */
object LiquidityHold {
    /** A trade closed within this many minutes of its entry is a quick exit. */
    const val QUICK_MIN = 10L
    /** Winners and losers are set side by side only with at least this many of each. */
    const val MIN_EACH = 3
    /** Losers held this many times as long as winners (or winners as long as losers), or more, is said as a difference. */
    const val RATIO = 1.5
    /** At most this many exit reasons are told. */
    const val MAX_REASONS = 4

    const val LOCKED = LiquidityRecord.LOCKED
    const val END = "From the arm's own paper book - facts, not advice; nothing about the arm changes from this."

    // ---- the question --------------------------------------------------------------------------------------------------

    private fun norm(text: String) = Spaced.joined(text)

    private val LIQ = Regex(" (liquidity|liquiditys|liqudity|liquidty) ")
    /** Time in a trade asked: how long it holds or how long its trades last, a hold time, a duration, in Hinglish "kitni der". */
    private val HOLD = Regex(" (hold time|hold times|holding time|holding times|holding period|holding periods|time in (a |the |its )?(trade|trades)|" +
        "trade duration|trade durations|duration|durations|" +
        "how long (does|do|did|is|are|was|were) (liquidity|liquiditys|it|its|the) ?(trades?|positions?)? ?(hold|held|holding|keep|kept|last|lasted|stay|stayed|run|ran|sit|open)|" +
        "how long( [a-z]+){1,5} (holds|keeps|lasts|stays|sits|usually (hold|last|stay|run)|typically (hold|last|stay|run)|normally (hold|last|stay|run))|" +
        "(losers|losing trades|losses|winners|winning trades|wins) (last|lasted|run|ran|held|stay|stayed) (longer|shorter)|" +
        "(hold|holds|held|keep|keeps|kept) (its |the )?(losers|losing trades|winners|winning trades) (longer|shorter|too long)|" +
        "kitni der|kitne der|kitne minute|kitna time|kitne time|kab tak (rakhta|rakhti|hold|chalta|chalti)) ")
    /**
     * Not this: a change or a switch (never from here), the open trade now (the arm's own answer), how long until a trade,
     * how long it has been armed or running, the backtest or the shadows, a definition, or another arm.
     */
    private val NOT = Regex(" (should|shall|set|change|changes|changing|switch|turn on|turn off|disable|enable|karo|kar do|kardo|band karo|" +
        "right now|now|abhi|currently|current|still|open trade|open position|this trade|this position|will|would|until|till|before|next|wait|waiting|been|armed|running since|" +
        "backtest|back test|backtested|shadow|shadows|candidate|candidates|today|todays|aaj|tomorrow|kal|why|kyun|kyon|kyu|" +
        "what is a|define|meaning|mean by|hero|solo|orb|gold|pine|my trades|i hold|do i|did i|main|mai) ")
    /** A second question said after it: left to the splitter, each answered on its own. */
    private val AND = Regex(" (and|aur|also|then|phir) (what|whats|how|hows|is|are|when|why|tell|show|give|check|kya|kitna|nifty|banknifty|my|mera|meri|mere) ")

    /** Is Liquidity 15+5's time in a trade asked? The span named (every closed trade when none), or null when not. */
    fun asked(text: String): LiquidityRecord.Q? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<LiquidityRecord.Q?>(64)

    private fun askedFresh(text: String): LiquidityRecord.Q? {
        val t = norm(text)
        if (!LIQ.containsMatchIn(t) || !HOLD.containsMatchIn(t) || NOT.containsMatchIn(t) || AND.containsMatchIn(t)) return null
        return LiquidityRecord.spanOf(text)
    }

    // ---- the figures ---------------------------------------------------------------------------------------------------

    /** Minutes from entry to exit (0 at least), or null when the exit time is not booked. */
    fun minutes(r: LiquidityRecord.Row): Long? = r.trade.exitTime?.let { Duration.between(r.trade.entryTime, it).toMinutes().coerceAtLeast(0) }

    /** The median of [xs] (the mean of the middle two for an even count), or null for none. */
    fun median(xs: List<Long>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m].toDouble() else (s[m - 1] + s[m]) / 2.0
    }

    /** One group's hold: [n] trades, the [median] minutes. */
    data class Group(val n: Int, val median: Double?)

    /** The read: every trade's [median], the [shortest] and [longest], [winners] and [losers], the [quick] exits, by reason. */
    data class Read(
        val n: Int, val median: Double?, val shortest: Pair<LiquidityRecord.Row, Long>?, val longest: Pair<LiquidityRecord.Row, Long>?,
        val winners: Group, val losers: Group, val quick: List<LiquidityRecord.Row>, val byReason: List<Pair<String, Group>>,
    )

    fun read(rows: List<LiquidityRecord.Row>): Read {
        val timed = rows.mapNotNull { r -> minutes(r)?.let { r to it } }
        fun group(xs: List<Pair<LiquidityRecord.Row, Long>>) = Group(xs.size, median(xs.map { it.second }))
        val byReason = timed.groupBy { it.first.why }.map { (why, xs) -> why to group(xs) }
            .sortedWith(compareByDescending<Pair<String, Group>> { it.second.n }.thenBy { it.first })
        return Read(
            n = timed.size, median = median(timed.map { it.second }),
            shortest = timed.minByOrNull { it.second }, longest = timed.maxByOrNull { it.second },
            winners = group(timed.filter { it.first.win }), losers = group(timed.filter { !it.first.win }),
            quick = timed.filter { it.second < QUICK_MIN }.map { it.first }, byReason = byReason,
        )
    }

    /** The plain-language takeaway: losers cut quicker, held longer, or about the same as winners - or too few to tell. */
    fun takeaway(r: Read): String {
        val w = r.winners.median; val l = r.losers.median
        if (r.winners.n < MIN_EACH || r.losers.n < MIN_EACH || w == null || l == null)
            return "Too few winners and losers yet to say whether it cuts losses quicker than it lets wins run."
        val lo = maxOf(l, 1.0); val wi = maxOf(w, 1.0)
        return when {
            lo >= RATIO * wi -> "Its losers stay open longer than its winners - worth watching, not proof; a rule change stays your call, through the research first."
            wi >= RATIO * lo -> "It cuts its losers quicker than it lets its winners run - what the stops are there for."
            else -> "Winners and losers stay open about as long - the exits, not the clock, are what separate them."
        }
    }

    // ---- words ---------------------------------------------------------------------------------------------------------

    private fun mins(x: Double): String {
        val m = Math.round(x)
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun s(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun one(p: Pair<LiquidityRecord.Row, Long>) =
        "${mins(p.second.toDouble())} (${date(p.first.day)}, ${p.first.book}, ${LiquidityRecord.reason(p.first.why)}, ${LiquidityRecord.rs(p.first.perLot)} a lot)"

    /** The answer for [q] at [today] from the arm's closed paper trades [all] (oldest exit first). */
    fun answer(q: LiquidityRecord.Q, all: List<LiquidityRecord.Row>, today: LocalDate): String {
        val rows = LiquidityRecord.pick(q, all, today)
        val span = LiquidityRecord.spanWords(q, today, rows)
        val r = read(rows)
        if (r.n == 0) return "Liquidity 15+5 has no closed paper trade $span to time, Boss.\n$END"
        val out = mutableListOf<String>()
        out += if (r.n == 1) "Liquidity 15+5's one closed paper trade $span was open ${one(r.longest!!)}."
        else "Liquidity 15+5 held its ${r.n} closed paper trades $span a typical ${mins(r.median!!)} (the median) - " +
            "shortest ${one(r.shortest!!)}, longest ${one(r.longest!!)}."
        if (r.n > 1) {
            val wl = listOfNotNull(
                r.winners.median?.let { "${s(r.winners.n, "winner")} a typical ${mins(it)}" },
                r.losers.median?.let { "${s(r.losers.n, "loser")} a typical ${mins(it)}" },
            )
            if (wl.isNotEmpty()) out += wl.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
            if (r.quick.isNotEmpty()) {
                val net = r.quick.sumOf { it.perLot }
                out += "${s(r.quick.size, "trade")} closed inside $QUICK_MIN minutes (${r.quick.count { it.win }} won), " +
                    "${LiquidityRecord.rs(net)} a lot between them."
            }
            if (r.byReason.size > 1) out += "By exit: " + r.byReason.take(MAX_REASONS).joinToString("; ") { (why, g) ->
                "${LiquidityRecord.reason(why)} ${s(g.n, "trade")}, ${mins(g.median ?: 0.0)}"
            } + "."
            out += takeaway(r)
        }
        out += END
        return out.joinToString("\n")
    }
}
