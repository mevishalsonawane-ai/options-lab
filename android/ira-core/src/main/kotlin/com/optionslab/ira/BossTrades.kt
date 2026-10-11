package com.optionslab.ira

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Learning from Boss's own trades (S10): when he places or closes a trade by hand (an order with no bot's source tag), the
 * app asks - optionally, one tap, dismissible, never holding the order - why: breakout, level, news, gut, following
 * Jarvis or other, with a note if he likes; and on the exit: target, stop, fear, news, time or other. Each trade is kept
 * with its context: the market's regime then, the time, the trade manager's specialists' votes on that index then, and
 * its risk to reward when a stop and a target were set.
 *
 * Over time Jarvis forms "your rules" from them ([rules]): each group - by reason, by reason and part of the day, by
 * regime - with its trades, wins and rupees, its RUPEES tested against zero (a sign-flip test on its per-day sums,
 * [LearnStats.signFlipAlwaysValidP]: a group that wins often but loses big is no good rule) and corrected for testing many groups at
 * once (Benjamini-Hochberg q). Exit reasons are not groups (R3 finding 6): trades closed "on target" win and those closed
 * "on stop" lose by definition, so testing them only padded the family with sure discoveries; [sayHabits] still counts them. A rule HOLDS at q ≤ [Q_HOLD] over at least [MIN_N]
 * trades; a soft warning before an order ([warn]) needs a losing group at q ≤ [Q_WARN]. Words only: nothing is ever
 * blocked, sized or changed. Pure.
 */
object BossTrades {
    const val MIN_N = 8
    const val Q_HOLD = 0.10
    const val Q_WARN = 0.20
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    enum class Why(val words: String) { BREAKOUT("breakout"), LEVEL("level"), NEWS("news"), GUT("gut"), JARVIS("following Jarvis"), OTHER("other") }
    enum class ExitWhy(val words: String) { TARGET("target"), STOP("stop"), FEAR("fear"), NEWS("news"), TIME("time"), OTHER("other") }

    /** The part of the day a trade opened in. */
    enum class Part(val words: String, val from: Int, val until: Int) {
        OPEN("before 10:00", 0, 10 * 60), MORNING("10:00 to 12:00", 10 * 60, 12 * 60), MIDDAY("12:00 to 14:00", 12 * 60, 14 * 60),
        LATE("after 14:00", 14 * 60, 24 * 60);
        companion object { fun of(minute: Int): Part = entries.first { minute >= it.from && minute < it.until } }
    }

    /** One hand trade: [id] its opening order ("paper:123"), [live] the account, reasons as Boss gave them (null: not given). */
    data class Trade(
        val id: String, val symbol: String, val live: Boolean, val openedMs: Long,
        val why: Why? = null, val note: String = "", val exitWhy: ExitWhy? = null, val closedMs: Long? = null,
        val net: Double? = null, val regime: String? = null, val votes: List<String> = emptyList(), val rr: Double? = null,
        /** The closing order's id ("kite:123") while its net waits for the trade book ([settle]). */
        val closeId: String? = null,
    ) {
        val closed: Boolean get() = net != null
        /** Closed at [closedMs], its net not in the trade book yet (G6-4: a live exit's round trip comes on the next account load). */
        val pending: Boolean get() = net == null && closedMs != null
        /** Still held: neither closed nor waiting for its close's net. */
        val open: Boolean get() = net == null && closedMs == null
        val minute: Int get() = Instant.ofEpochMilli(openedMs).atZone(IST).let { it.hour * 60 + it.minute }
        val day: LocalDate get() = Instant.ofEpochMilli(openedMs).atZone(IST).toLocalDate()
    }

    /** The risk to reward of a bracket: (target - entry) / (entry - stop) for a buy (mirrored for a sell); null when unknown. */
    fun rr(entry: Double?, stop: Double?, target: Double?, buy: Boolean): Double? {
        if (entry == null || stop == null || target == null) return null
        val risk = if (buy) entry - stop else stop - entry
        val reward = if (buy) target - entry else entry - target
        return if (risk <= 0 || reward <= 0) null else reward / risk
    }

    // ---- the rules ---------------------------------------------------------------------------------------------------------

    /** One group of his trades and what it says: [label] "your gut trades after 14:00". */
    data class Rule(val key: String, val label: String, val n: Int, val wins: Int, val net: Double, val p: Double, val q: Double,
                    val why: Why? = null, val part: Part? = null) {
        val rate: Double get() = if (n == 0) 0.0 else wins.toDouble() / n
        /** Which way its rupees point (the test is on rupees, so the sign is too). */
        val sign: Int get() = if (net > 1e-9) 1 else if (net < -1e-9) -1 else 0
        val holds: Boolean get() = n >= MIN_N && q <= Q_HOLD
        fun words(): String = "$label ${if (net >= 0) "made" else "lost"} ${rs(kotlin.math.abs(net))} over $n trades, won ${pct(rate)} (${wins} of $n)"
    }

    private fun rs(x: Double): String = "₹" + String.format(Locale.ENGLISH, "%,.0f", x)
    private fun pct(x: Double): String = String.format(Locale.ENGLISH, "%.0f%%", x * 100)

    /** Every group with at least [MIN_N] closed trades, its rupees' p against zero (per-day sums) and BH q across all of them. */
    fun rules(trades: List<Trade>): List<Rule> {
        val closed = trades.filter { it.closed }
        val groups = LinkedHashMap<String, Triple<String, List<Trade>, Pair<Why?, Part?>>>()
        fun add(key: String, label: String, xs: List<Trade>, why: Why? = null, part: Part? = null) {
            if (xs.size >= MIN_N) groups[key] = Triple(label, xs, why to part)
        }
        for (w in Why.entries) {
            val xs = closed.filter { it.why == w }
            add("why:${w.name}", "your ${w.words} trades", xs, w)
            for (p in Part.entries) add("why:${w.name}:${p.name}", "your ${w.words} trades ${p.words}", xs.filter { Part.of(it.minute) == p }, w, p)
        }
        for (p in Part.entries) add("part:${p.name}", "your trades ${p.words}", closed.filter { Part.of(it.minute) == p }, part = p)
        for (r in closed.mapNotNull { it.regime }.distinct()) add("regime:$r", "your trades on $r days", closed.filter { it.regime == r })
        val out = groups.map { (k, v) ->
            val xs = v.second
            val wins = xs.count { (it.net ?: 0.0) > 0 }
            // Oldest day first, tested always-valid (G6-10): the rules are looked at on every order review and every ask as
            // trades come in, and a fixed-sample p looked at that often crosses the bar by luck (R3 finding 8's fix, here too).
            // Tested the way its rupees point: "made" or "lost" is what a rule says.
            val perDay = xs.groupBy { it.day }.toSortedMap().values.map { d -> d.sumOf { it.net ?: 0.0 } }.toDoubleArray()
            val net = xs.sumOf { it.net ?: 0.0 }
            val side = if (net > 1e-9) 1 else if (net < -1e-9) -1 else 0
            val p = if (side == 0) 1.0 else LearnStats.signFlipAlwaysValidP(perDay, side = side)
            Rule(k, v.first, xs.size, wins, net, p, 1.0, v.third.first, v.third.second)
        }
        if (out.isEmpty()) return out
        val q = LearnStats.bh(out.map { it.p }.toDoubleArray())
        return out.mapIndexed { i, r -> r.copy(q = q[i]) }
    }

    /**
     * A soft word before an order at [minute] (IST) with reason [why] when known: the strongest LOSING group that matches
     * (q ≤ [Q_WARN]), as "Heads up: your gut trades after 14:00 lost 9 of 11 (₹4,200)." Null when his record says nothing.
     */
    fun warn(rules: List<Rule>, minute: Int, why: Why? = null): String? {
        val part = Part.of(minute)
        val r = rules.filter { it.sign < 0 && it.n >= MIN_N && it.q <= Q_WARN && it.net < 0 }
            .filter { (it.part == null || it.part == part) && (it.why == null || why == null || it.why == why) && (it.part != null || it.why != null) }
            .minByOrNull { it.q } ?: return null
        val asks = if (r.why != null && why == null) " Is this one a ${r.why.words} trade?" else ""
        return "Heads up, Boss: ${r.label} lost ${r.n - r.wins} of ${r.n} (${rs(-r.net)} in all).$asks Your call - nothing is blocked."
    }

    // ---- words -------------------------------------------------------------------------------------------------------------

    /** "What are my trading habits?" */
    fun sayHabits(trades: List<Trade>): String {
        if (trades.isEmpty()) return "I have none of your own trades with a reason yet, Boss. After a hand trade, tap a reason chip and I'll start learning your habits."
        val closed = trades.filter { it.closed }
        val byWhy = trades.mapNotNull { it.why }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val byPart = trades.groupingBy { Part.of(it.minute) }.eachCount().entries.maxByOrNull { it.value }
        val exits = closed.mapNotNull { it.exitWhy }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val rrs = trades.mapNotNull { it.rr }
        val parts = ArrayList<String>()
        parts += "From ${trades.size} of your own trades" + (if (closed.size < trades.size) " (${closed.size} closed)" else "") + ":"
        if (byWhy.isNotEmpty()) parts += "you trade most on " + byWhy.take(3).joinToString(", ") { "${it.key.words} (${it.value})" } + "."
        byPart?.let { parts += "Most open ${it.key.words} (${it.value} of ${trades.size})." }
        if (exits.isNotEmpty()) parts += "You close most on " + exits.take(3).joinToString(", ") { "${it.key.words} (${it.value})" } + "."
        if (rrs.isNotEmpty()) parts += "Your usual risk to reward is 1:${String.format(Locale.ENGLISH, "%.1f", rrs.sorted()[rrs.size / 2])}."
        if (closed.isNotEmpty()) parts += "Closed: won ${closed.count { (it.net ?: 0.0) > 0 }} of ${closed.size}, ${if (closed.sumOf { it.net ?: 0.0 } >= 0) "+" else "−"}${rs(kotlin.math.abs(closed.sumOf { it.net ?: 0.0 }))} in all."
        val untagged = trades.count { it.why == null }
        if (untagged > 0) parts += "$untagged had no reason given."
        return parts.joinToString(" ")
    }

    /** "Which of my rules work?" */
    fun sayRules(rules: List<Rule>, trades: Int): String {
        if (rules.isEmpty()) return "Not enough of your own trades yet, Boss: a rule needs at least $MIN_N closed trades in a group (I have $trades). Keep tagging the reason."
        val holds = rules.filter { it.holds }.sortedBy { it.q }
        val work = holds.filter { it.sign > 0 }; val lose = holds.filter { it.sign < 0 }
        val building = rules.filter { !it.holds }.sortedBy { it.q }.take(2)
        val parts = ArrayList<String>()
        if (work.isNotEmpty()) parts += "What works: " + work.take(3).joinToString("; ") { it.words() } + "."
        if (lose.isNotEmpty()) parts += "What loses: " + lose.take(3).joinToString("; ") { it.words() } + "."
        if (holds.isEmpty()) parts += "None of your groups is clear of chance yet (each group's rupees tested against luck and corrected for testing ${rules.size} at once)."
        if (building.isNotEmpty()) parts += "Still building: " + building.joinToString("; ") { "${it.words()}, ${AgentLessons.qWords(it.q)}" } + "."
        return parts.joinToString(" ")
    }

    /** A habit insight for today's lesson: the clearest rule that today's trades touched (null: none). */
    fun todayInsight(rules: List<Rule>, today: List<Trade>): String? {
        if (today.isEmpty()) return null
        val r = rules.filter { it.q <= Q_WARN && it.n >= MIN_N }.filter { r ->
            today.any { t -> (r.why == null || t.why == r.why) && (r.part == null || Part.of(t.minute) == r.part) && (r.why != null || r.part != null) }
        }.minByOrNull { it.q } ?: return null
        return "Today you made ${if (r.sign < 0) "a trade your record says loses" else "a trade your record says works"}: ${r.words()}."
    }

    // ---- the file ----------------------------------------------------------------------------------------------------------

    private fun clean(s: String) = s.replace(Regex("[|\\n\\r]"), " ").trim().take(140)

    fun encode(ts: List<Trade>): String = ts.joinToString("\n") { t ->
        listOf("T1", clean(t.id), clean(t.symbol), t.live, t.openedMs, t.why?.name ?: "", clean(t.note), t.exitWhy?.name ?: "", t.closedMs ?: "",
            t.net ?: "", clean(t.regime ?: ""), t.votes.joinToString(";") { clean(it).replace(";", ",") }, t.rr ?: "").joinToString("|") +
            (t.closeId?.let { "|" + clean(it) } ?: "")
    }

    fun decode(text: String?): List<Trade> = text.orEmpty().lineSequence().mapNotNull { l ->
        val p = l.split('|')
        if (p.size < 13 || p[0] != "T1") return@mapNotNull null
        runCatching {
            Trade(p[1], p[2], p[3].toBoolean(), p[4].toLong(), p[5].takeIf { it.isNotEmpty() }?.let { Why.valueOf(it) }, p[6],
                p[7].takeIf { it.isNotEmpty() }?.let { ExitWhy.valueOf(it) }, p[8].toLongOrNull(), p[9].toDoubleOrNull(), p[10].ifEmpty { null },
                p[11].split(';').filter { it.isNotBlank() }, p[12].toDoubleOrNull(), p.getOrNull(13)?.ifEmpty { null })
        }.getOrNull()
    }.toList()

    /**
     * [trades] with each [Trade.pending] close given its net from the trade book (G6-4): [nets] is the rupees per closing
     * order id ("kite:123", the round trips it closed summed). A close the book does not hold yet stays pending - its net is
     * never guessed (not 0, not an earlier trip's on the same symbol).
     */
    fun settle(trades: List<Trade>, nets: Map<String, Double>): List<Trade> =
        if (trades.none { it.pending }) trades else trades.map { t ->
            val n = if (t.pending) t.closeId?.let { nets[it] } else null
            if (n == null || !n.isFinite()) t else t.copy(net = n)
        }

    /** At most this many trades kept (the oldest go first). */
    const val KEEP = 2_000
}
