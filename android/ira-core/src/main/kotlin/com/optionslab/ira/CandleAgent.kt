package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The Candle agent (Boss, 20 Oct 2026: "add a new agent in the application who will only study the candle patterns ...
 * then use all the OI, gamma and everything we can. I want to understand each and everything and make the agent learn from
 * it, and afterwards we can upgrade Jarvis" and "it should have the same characteristics as the other agents: sharing the
 * knowledge, communicating with each other").
 *
 * A member of the agent family, built on what the family already shares:
 *  - It reads candles in BATCHES ([study], every few minutes from the app's supervised worker, never per tick): each closed
 *    candle of the indices and MCX at 1, 5 and 15 minutes ([CandleRead]), then what the next 1-50 candles did ([CandleBook]).
 *  - It SHARES on the findings bus: a notable candle's read ([posts], kind [Findings.Kind.CANDLE]) with its shape, context,
 *    nearest support / resistance and the research's odds with the sample size. Always with NO direction (its lean only in
 *    the evidence), so it never counts in any strategy's consensus or reaction: it can never skip, size or exit anything.
 *  - It RECEIVES from the bus what the other agents post ([heard]): the market brain's regime, OI walls and shifts, gamma,
 *    news windows, big players' sweeps and absorption, liquidity, stop and target hits; said beside its reads.
 *  - It ANSWERS: other agents through [ask] (typed, memory only, cheap) and Jarvis through [answer] ("why was that candle
 *    green", "what does this candle mean", "where is support for this candle"). On a locked phone nothing the bots did is said
 *    (their stop and target hits are the account's), only the market's candles and the research.
 *  - It LEARNS: its own counts per shape x context against the research ([CandleBook.compare]) become Jarvis's lessons
 *    ([lessons], topic "Candle agent", never above building) and notes for Boss ([notes]: never an action).
 *  - Lifecycle and health like the others: [status], [growth], [exchanges] (what it sent and received) for its page.
 *
 * It never trades: nothing here, or anything it learns, touches an arm, a size, a stop or an order. Thread-safe (one lock;
 * every call is short and in memory). The app keeps its counts in a file off the main thread and off the order path.
 */
class CandleAgent(val seed: CandleBook.Seed, stats: CandleBook.Stats = CandleBook.Stats()) {
    /** One instrument's candles of one timeframe for a batch (closed candles only, oldest first, a few sessions at most). */
    data class Series(
        val inst: String, val tf: Int, val bars: List<CandleRead.Bar>, val sessionOpenMin: Int = 9 * 60 + 15,
        /** The newest session is over (the market closed): its last reads settle with what they have. */
        val sessionOver: Boolean = false,
        /** The chain, gamma and expiry known around a candle's close (the app gives today's only to a candle that just closed). */
        val around: (LocalDateTime) -> CandleRead.Around = { CandleRead.Around() },
        /**
         * The 24x7 study of older history (Boss, 20 Oct: "agent should be running 24x7 to learn new things from candles"): every
         * candle read and its outcome counted (only those never counted before), nothing explained or posted.
         */
        val deep: Boolean = false,
        /** Its own market is open now (an index in MCX's evening is not: never called stale then). */
        val open: Boolean = true,
    )

    /** One notable candle explained, for the page and for Jarvis. [atMs]: when the agent read it. */
    data class Explained(val read: CandleRead.Read, val lines: List<String>, val atMs: Long) {
        val inst: String get() = read.inst
        val title: String get() = "${read.inst} ${read.tf}m ${CandleRead.timeOf(read.bar.t, read.tf)}: ${CandleRead.name(read.primary)}"
    }

    enum class Way(val words: String) { SENT("shared"), HEARD("heard"), ASKED("asked by"), ANSWERED("answered") }

    /** One line of the agent's talk with the others. */
    data class Exchange(val atMs: Long, val way: Way, val who: String, val words: String)

    enum class Health(val words: String) {
        WAITING("waiting for its first batch"), OK("reading candles"), NO_DATA("no candles to read (market closed, a holiday, or no data yet)"),
        STALE("the newest candles are stale: reads held back"), RELEASED("released to free memory (open positions come first)"),
    }

    data class Status(val health: Health, val lastBatchMs: Long?, val batches: Int, val reads: Long, val counted: Long, val cells: Int, val growth: Int,
                      val lastError: String? = null) {
        fun words(): String = "Candle agent: ${health.words}; $batches batches, $reads candles read, $counted outcomes counted, $cells cells, growth $growth/100" +
            (lastError?.let { "; last error $it" } ?: "")
    }

    /** What one batch did: the findings to post (the app posts them) and whether the counts changed (the app saves them). */
    data class Step(val posts: List<Findings.Finding>, val explained: Int, val counted: Int, val changed: Boolean)

    private val lock = Any()
    private val stats = stats
    private val latest = ArrayDeque<Explained>()
    private val log = ArrayDeque<Exchange>()
    /** Per "INST|tf": the last candle read (the last one counted is in [CandleBook.Stats.marks]). */
    private val readTo = HashMap<String, LocalDateTime>()
    /** Reads waiting for their outcome, per series (bounded: a session of 1-minute candles). */
    private val pending = HashMap<String, ArrayDeque<CandleRead.Read>>()
    /** The other agents' standing words, per instrument (and "*" for all): "who|kind" to the finding. */
    private val heardOn = HashMap<String, LinkedHashMap<String, Findings.Finding>>()
    private val seen = HashSet<Long>()
    /** Every bus number at or under this was heard already ([seen] keeps the ones after it). */
    private var heardTo = -1L
    private var health = Health.WAITING
    private var lastBatchMs: Long? = null
    private var batches = 0
    private var reads = 0L
    private var counted = 0L
    private var lastError: String? = null

    companion object {
        const val WHO = "Candle agent"
        const val KEEP_EXPLAINED = 40
        const val KEEP_LOG = 80
        const val KEEP_PENDING = 400
        /** A notable candle is posted only when it is this fresh (in candles): history is read, not re-announced. */
        const val POST_WITHIN_CANDLES = 2
        /**
         * 1-minute candles are read and counted, but only 5-minute and longer ones are posted; only 5- and 15-minute counts
         * become lessons and notes ([CandleBook.countsAsLesson], 20 Oct, enhancement round 2).
         */
        const val POST_MIN_TF = 5
        /** Said under a 1-minute candle's own count (20 Oct, enhancement round 2). */
        const val ONE_MINUTE_NOTE = "A 1-minute candle is explained only, never counted as a lesson: lessons come from 5- and 15-minute candles."
        /** The other agents' words stand this long for the agent's reads. */
        const val HEARD_MS = 30 * 60_000L
        /** No candle newer than this many candle lengths while the market is open: the data is stale. */
        const val STALE_CANDLES = 4
        /**
         * 21 Oct (Boss's 11:09 diagnostics, stream LIVE): never less than this many minutes, whatever the candle. Four 1-minute
         * candles were 5 minutes: the batch runs every 5 minutes on MCX data kept 10, and the newest running minute is never
         * trusted, so a healthy live 1-minute series read as stale every other batch. 5- and 15-minute candles are unchanged
         * (20 and 60 minutes are already above it). Only this alarm changed: unfinished candles are still never read.
         */
        const val STALE_MIN_MINUTES = 15

        /**
         * Whether a series whose newest closed candle started at [newest] ([tf] minutes) is stale at [now]: only while [open],
         * when that candle ended more than max(4 candles, [STALE_MIN_MINUTES]) minutes ago.
         */
        fun stale(newest: LocalDateTime, tf: Int, now: LocalDateTime, open: Boolean): Boolean =
            open && newest.plusMinutes(tf.toLong() + maxOf(STALE_CANDLES * tf, STALE_MIN_MINUTES)).isBefore(now)

        /** The kinds of the other agents' posts it reads (its own aside). */
        val HEARS: Set<Findings.Kind> = Findings.Kind.entries.toSet() - Findings.Kind.CANDLE

        private val WHY = Regex(" why (is|was|did|does)? ?(that|this|the|the last|last|latest|the latest)? ?([0-9]+ ?(minute|min|m) )?candle (green|red|turn green|turn red|close green|close red) | candle (green|red) (kyun|kyu|kyon) | (green|red) kyun (hai|tha|hua) .*candle ")
        private val MEAN = Regex(" what does (this|that|the|the last|last|the latest) ?([0-9]+ ?(minute|min|m) )?candle (mean|say|tell) | explain (this|that|the last|the latest|last) ([0-9]+ ?(minute|min|m) )?candle | (read|explain) the (last|latest) candle | candle agent | (is|ka) candle (ka )?matlab ")
        private val SUPPORT = Regex(" where (is|was|did) .*(support|resistance).* candle | where did (this|that|the last) candle (get|find) | candle s? (support|resistance) | (support|resistance) (for|of|under|over|above|below) (this|that|the last|the latest) candle ")
        /** A candle record (BigCandles), a named pattern's meaning (the pattern expert), a backtest: not the Candle agent's. */
        private val NOT = Regex(" (record|how often|stats|statistics|backtest|hammer|doji|engulfing|harami|marubozu|shooting star|big candle|large candle|badi candle|bada candle) ")

        /** The Candle agent's question in [text], or null (Jarvis's routing: "why was that candle green", "what does this candle mean"). */
        fun asked(text: String): Question? {
            val t = " " + text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
            if (!t.contains(" candle") || NOT.containsMatchIn(t)) return null
            return when {
                WHY.containsMatchIn(t) -> Question.WHY_COLOUR
                SUPPORT.containsMatchIn(t) -> Question.SUPPORT
                MEAN.containsMatchIn(t) -> Question.MEANING
                else -> null
            }
        }

        /** The instrument [text] names (an index, or an MCX future), or null (the newest read of any). */
        fun instrument(text: String): String? {
            val t = " " + text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ") + " "
            Regex(" (crude ?oil|crude|natural ?gas|natgas|gold ?m|goldm|gold|silver ?m|silverm|silver) ").find(t)?.let { m ->
                val w = m.value.trim().replace(" ", "")
                return when { w.startsWith("crude") -> "CRUDEOIL"; w.startsWith("nat") -> "NATURALGAS"; w.startsWith("gold") -> "GOLDM"; else -> "SILVERM" }
            }
            return Market.mentioned(text).firstOrNull { it == Market.NIFTY || it == Market.BANKNIFTY || it == Market.SENSEX }?.name
        }

        /** A trade's own result: the account's, never said on a locked phone. */
        val ACCOUNT_KINDS = setOf(Findings.Kind.ENTRY, Findings.Kind.STOP_HIT, Findings.Kind.TARGET_HIT, Findings.Kind.DRIFT)
    }

    // ---- the batch -------------------------------------------------------------------------------------------------------------

    /**
     * One batch at [nowMs] (the market's clock [now]): the other agents' new [findings] heard, then every series read from where
     * the last batch stopped, the settled outcomes counted, the fresh notable candles explained and returned as posts.
     * [marketOpen]: a series whose newest candle is older than [STALE_CANDLES] candle lengths while open is stale (read, never posted).
     */
    fun study(series: List<Series>, findings: List<Findings.Finding>, nowMs: Long, now: LocalDateTime, marketOpen: Boolean): Step = synchronized(lock) {
        hear(findings, nowMs)
        val posts = ArrayList<Findings.Finding>()
        var explainedN = 0; var countedN = 0
        var anyBars = false; var anyStale = false
        for (s in series) {
            val bars = s.bars.filter { it.ok }.sortedBy { it.t }
            if (bars.isEmpty()) continue
            anyBars = true
            val sk = "${s.inst.uppercase(Locale.ENGLISH)}|${s.tf}"
            val qk = if (s.deep) "$sk|deep" else sk
            val newest = bars.last().t
            val stale = stale(newest, s.tf, now, marketOpen && s.open)
            if (stale) anyStale = true
            // The big players' posts during each candle (a sweep, absorption, pulled orders, a stop hunt) become context words too.
            val fresh = CandleRead.readAll(s.inst.uppercase(Locale.ENGLISH), s.tf, bars, if (s.deep) null else readTo[sk], s.sessionOpenMin, s.around).map { r ->
                val bp = players(r).map { "bp_" + it.kind.name.lowercase(Locale.ENGLISH) }.distinct()
                if (bp.isEmpty()) r else r.copy(context = r.context.copy(tags = r.context.tags + bp))
            }
            if (fresh.isNotEmpty() && !s.deep) readTo[sk] = fresh.last().bar.t
            reads += fresh.size
            val q = pending.getOrPut(qk) { ArrayDeque() }
            val mark0 = stats.marks[sk]; val first0 = stats.firsts[sk]
            q.addAll(fresh)
            // Indices move as the history window slides: each pending read is found again by its time.
            val at = HashMap<LocalDateTime, Int>(bars.size * 2)
            for ((i, b) in bars.withIndex()) at[b.t] = i
            while (q.isNotEmpty()) {
                val r0 = q.first()
                val idx = at[r0.bar.t]
                if (idx == null) { q.removeFirst(); continue }          // its candle left the window: dropped, never guessed
                // A candle read before ATR existed (the first 14) has nothing to be measured in: it never settles, so it leaves.
                // One already counted (before a restart) leaves too, without being measured again.
                if (r0.anatomy.atr == null || !CandleBook.countable(mark0, first0, r0.bar.t)) { q.removeFirst(); continue }
                val r = if (idx == r0.index) r0 else r0.copy(index = idx)
                val sessionOver = s.sessionOver && r.bar.t.toLocalDate() == newest.toLocalDate()
                val o = CandleBook.outcome(r, bars, sessionOver) ?: break // in order: the next ones are not settled either
                q.removeFirst()
                // Counted without the chain, gamma and outside VWAP read at the batch (after the candle: look-ahead; 20 Oct review).
                CandleBook.count(stats, CandleRead.countable(r), o)
                stats.counted(sk, r.bar.t)
                countedN++
            }
            // Bounded after settling (a first batch reads days of history and settles nearly all of it at once).
            while (q.size > KEEP_PENDING) q.removeFirst()
            CandleBook.trim(stats)
            if (s.deep) { q.clear(); continue }                              // a study pass: counted, never announced
            var toPost: CandleRead.Read? = null
            for (r in fresh) {
                if (!r.notable) continue
                val ex = Explained(r, explain(r, locked = false, nowMs = nowMs), nowMs)
                latest.addFirst(ex)
                explainedN++
                val age = java.time.Duration.between(r.bar.t.plusMinutes(r.tf.toLong()), now).toMinutes()
                if (!stale && r.tf >= POST_MIN_TF && age <= POST_WITHIN_CANDLES.toLong() * r.tf) toPost = r
            }
            // One post a series a batch at most (its newest fresh notable candle): the bus is shared, its ring is not the agent's.
            toPost?.let { posts += post(it, nowMs) }
        }
        // 20 Oct, review: newest candle first (by when it closed), whatever series it came from, and trimmed only after that.
        // Added series by series and cut to 40 at each add, a first batch's days of MCX history pushed NIFTY's newest reads out,
        // and Jarvis's "why was that candle green" answered about an older 15-minute SILVERM candle.
        if (explainedN > 0) {
            val sorted = latest.sortedWith(compareByDescending<Explained> { it.read.bar.t.plusMinutes(it.read.tf.toLong()) }.thenByDescending { it.atMs })
            latest.clear()
            for (e in sorted) { if (latest.size >= KEEP_EXPLAINED) break; latest.addLast(e) }
        }
        counted += countedN
        batches++
        lastBatchMs = nowMs
        health = when { !anyBars -> Health.NO_DATA; anyStale -> Health.STALE; else -> Health.OK }
        for (p in posts) note(Exchange(nowMs, Way.SENT, "the findings bus", p.words))
        Step(posts, explainedN, countedN, countedN > 0)
    }

    /** The finding a notable read posts: NO direction (its colour only in the evidence), so no consensus or reaction counts it. */
    fun post(r: CandleRead.Read, nowMs: Long): Findings.Finding {
        val c = r.context
        val ev = LinkedHashMap<String, Double>()
        ev["lean"] = r.anatomy.colour.toDouble()
        r.anatomy.size?.let { ev["sizeAtr"] = it }
        ev["closeAt"] = r.anatomy.closeAt
        c.support?.distAtr?.let { ev["supportAtr"] = it }
        c.resistance?.distAtr?.let { ev["resistanceAtr"] = it }
        val row = seed.row(r.primary, r.inst, CandleBook.researchTf(r.tf))
        row?.up?.let { ev["researchUp"] = it }
        row?.base?.let { ev["researchBase"] = it }
        row?.let { ev["researchN"] = it.n.toDouble() }
        val words = "${r.tf}m ${CandleRead.name(r.primary)} at ${CandleRead.px(r.bar.c)}" +
            (c.support?.let { "; support ${CandleRead.px(it.price)} (${it.kind.words})" } ?: "") +
            (c.resistance?.let { "; resistance ${CandleRead.px(it.price)} (${it.kind.words})" } ?: "") +
            (row?.let { r2 -> r2.up?.let { "; research up ${pc(it)} vs ${pc(r2.base ?: 0.5)} n ${r2.n}" } } ?: "")
        return Findings.Finding(WHO, Findings.Kind.CANDLE, r.inst, direction = 0, level = c.support?.price, strength = 40, atMs = nowMs,
            ttlMs = (3L * r.tf * 60_000L).coerceAtLeast(5 * 60_000L), evidence = ev, words = words)
    }

    /** The other agents' posts heard (its own and repeats aside), kept per instrument for [HEARD_MS]. */
    fun hear(findings: List<Findings.Finding>, nowMs: Long) = synchronized(lock) {
        for (f in findings) {
            if (f.who == WHO || f.kind == Findings.Kind.CANDLE || f.kind !in HEARS) continue
            if (f.seq >= 0 && (f.seq <= heardTo || !seen.add(f.seq))) continue
            heardOn.getOrPut(f.instrument.uppercase(Locale.ENGLISH)) { LinkedHashMap() }["${f.who}|${f.kind}"] = f
            note(Exchange(f.atMs, Way.HEARD, f.who, f.words.ifEmpty { f.kind.words }))
        }
        // 20 Oct, review: the set is bounded by forgetting its oldest numbers, never by clearing it (a clear heard every finding
        // still alive on the bus again and logged each one twice). The bus numbers its posts in order.
        if (seen.size > 4_000) {
            val keep = seen.sortedDescending().take(2_000)
            heardTo = maxOf(heardTo, keep.last() - 1)
            seen.clear(); seen.addAll(keep)
        }
        for (m in heardOn.values) m.values.removeAll { nowMs - it.atMs > HEARD_MS }
    }

    /** The other agents' standing words on [inst] (and market-wide), newest first; trade results left out when [locked]. */
    fun heard(inst: String, nowMs: Long, locked: Boolean): List<Findings.Finding> = synchronized(lock) {
        (heardOn[inst.uppercase(Locale.ENGLISH)].orEmpty().values + heardOn[Findings.ALL].orEmpty().values)
            .filter { nowMs - it.atMs in -1_000L..HEARD_MS }
            .filter { !locked || (it.kind !in ACCOUNT_KINDS) }
            .sortedByDescending { it.atMs }
    }

    private fun note(e: Exchange) { log.addFirst(e); while (log.size > KEEP_LOG) log.removeLast() }

    // ---- the explanation ---------------------------------------------------------------------------------------------------

    /**
     * [r] in plain English: what it is, why it is green or red (who won the candle, where it closed), where it formed, the nearest
     * support and resistance and where each came from, what that kind of candle did historically (the research, with n), what
     * its own count says, what the other agents said around it, and the honest end: a read, not a signal.
     */
    fun explain(r: CandleRead.Read, locked: Boolean, nowMs: Long): List<String> = synchronized(lock) {
        val a = r.anatomy; val c = r.context; val b = r.bar
        val out = ArrayList<String>()
        val colour = when (a.colour) { 1 -> "green"; -1 -> "red"; else -> "flat" }
        out += "${r.inst} ${r.tf}-minute candle ${CandleRead.timeOf(b.t, r.tf)}: a ${CandleRead.name(r.primary)}, $colour" +
            (a.size?.let { ", ${CandleRead.f1(it)}x its ATR" } ?: "") + ", closed ${pc(a.closeAt)} of the way up its range."
        out += whyColour(r)
        out += context(r)
        val lv = listOfNotNull(c.support?.let { "nearest support ${it.words()}" }, c.resistance?.let { "nearest resistance ${it.words()}" })
        out += if (lv.isEmpty()) "No support or resistance level known yet (too few candles)." else lv.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
        if (r.primary in WICKY) out += "A long wick's end is not a stronger level than any other at the same distance: R87 found wick lows and highs " +
            "broken about as often as any level that far away."
        out += CandleBook.researchLine(seed, r.primary, r.inst, r.tf) ?: if (seed.empty) "History: the research pack is not loaded." else
            "History: the research has no row for this shape on ${r.inst}."
        out += own(r)
        historyLines(r).forEach { out += it }
        playersLine(r, locked)?.let { out += it }
        if (!locked) heardLine(r.inst, nowMs)?.let { out += it }
        out += "A read, not a signal: in the research no candle shape predicted the direction by itself, and nothing here trades."
        out
    }

    private val WICKY = setOf("big_lower_wick", "big_upper_wick", "both_big_wicks", "hammer", "hanging_man", "inverted_hammer", "shooting_star",
        "hammer_shape_flat", "inverted_shape_flat", "dragonfly_doji", "gravestone_doji", "long_legged_doji", "fp_stop_run_up", "fp_stop_run_down")

    /** Why it is green or red: who won the candle, how far each side pushed, where it closed. */
    fun whyColour(r: CandleRead.Read): String {
        val a = r.anatomy; val b = r.bar
        val head = when (a.colour) {
            1 -> "Green because it closed at ${CandleRead.px(b.c)}, above its open ${CandleRead.px(b.o)}: buyers won this candle by ${CandleRead.px(a.body)}."
            -1 -> "Red because it closed at ${CandleRead.px(b.c)}, below its open ${CandleRead.px(b.o)}: sellers won this candle by ${CandleRead.px(a.body)}."
            else -> "Flat: it closed where it opened (${CandleRead.px(b.o)}), so neither side won."
        }
        val parts = ArrayList<String>()
        if (a.lowerPct >= 0.3) parts += "sellers pushed it ${CandleRead.px(a.lower)} under the body and buyers took that back (a lower wick of ${pc(a.lowerPct)})"
        if (a.upperPct >= 0.3) parts += "buyers pushed it ${CandleRead.px(a.upper)} over the body and sellers took that back (an upper wick of ${pc(a.upperPct)})"
        if (a.bodyPct >= 0.85) parts += "one side was in control from open to close (the body is ${pc(a.bodyPct)} of the range)"
        val vol = when {
            a.volRatio != null -> "volume ${CandleRead.f1(a.volRatio)}x the day's usual candle"
            r.inst in CandleBook.R87_INDICES -> "an index has no volume of its own"
            else -> null
        }
        return head + (if (parts.isEmpty()) "" else " Inside it, " + parts.joinToString("; ") + ".") + (vol?.let { " ${it.replaceFirstChar { c -> c.uppercase() }}." } ?: "")
    }

    /** Where it formed, in words. */
    fun context(r: CandleRead.Read): String {
        val c = r.context; val b = r.bar
        val bits = ArrayList<String>()
        bits += when (c.trend) { 1 -> "in an up trend"; -1 -> "in a down trend"; else -> "with no clear trend" }
        if ("new_day_high" in c.tags) bits += "making a new day high"
        if ("new_day_low" in c.tags) bits += "making a new day low"
        if ("fp_stop_run_up" in r.shapes) bits += "poking above the day's high and closing back under it (a stop run; R87: on 5m and 15m these did not reverse)"
        if ("fp_stop_run_down" in r.shapes) bits += "poking under the day's low and closing back over it (a stop run)"
        if ("sweep_pdh" in c.tags) bits += "sweeping the previous day's high"
        if ("sweep_pdl" in c.tags) bits += "sweeping the previous day's low"
        if (c.orHigh != null && c.orLow != null) bits += when {
            b.c > c.orHigh -> "above the opening range"; b.c < c.orLow -> "below the opening range"; else -> "inside the opening range"
        }
        val atr = r.anatomy.atr
        bits += when {
            c.vwap != null && atr != null -> "${CandleRead.f1(kotlin.math.abs(b.c - c.vwap) / atr)} ATR ${if (b.c >= c.vwap) "above" else "below"} VWAP (${c.vwapFrom})"
            r.inst in CandleBook.R87_INDICES -> "VWAP not known (an index has no volume; the future's was not recorded)"
            else -> "VWAP not known"
        }
        bits += "in the ${c.timeOfDay}" + (if (c.expiry) " of an expiry day" else "")
        c.pcr?.let { bits += "PCR ${String.format(Locale.ENGLISH, "%.2f", it)}" }
        when (c.oiShift) { 1 -> bits += "puts added and calls cut (a bullish OI shift)"; -1 -> bits += "calls added and puts cut (a bearish OI shift)" }
        c.gamma?.let { bits += "dealers ${it} gamma" }
        c.volRegime?.let { bits += "$it volatility" }
        return "It formed " + bits.joinToString(", ") + "."
    }

    /** Its own count for this shape on this instrument and timeframe (a 1-minute one says it is never a lesson). */
    private fun own(r: CandleRead.Read): String = ownCount(r) + if (CandleBook.countsAsLesson(r.tf)) "" else " $ONE_MINUTE_NOTE"

    private fun ownCount(r: CandleRead.Read): String {
        val cell = stats[CandleBook.key(r.inst, r.tf, r.primary, "all")]
        val n = cell?.n5 ?: 0
        if (n < CandleBook.SAY_N) return "My own count: $n settled so far for this shape on ${r.inst} ${r.tf}m, too few to say anything (I need ${CandleBook.SAY_N})."
        val base = stats[CandleBook.baseKey(r.inst, r.tf, r.anatomy.colour, "all")]
        val says = CandleBook.says(cell, base)
        return "My own count (n $n): up after 5 candles ${pc(cell!!.upRate!!)}" + (base?.upRate?.let { " vs ${pc(it)} for any ${if (r.anatomy.colour > 0) "green" else "red"} candle" } ?: "") +
            (cell.heldRate?.let { ", its wick end held ${pc(it)} of ${cell.wickN}" } ?: "") + "; " + CandleBook.oneCellWords(says) + "."
    }

    /**
     * The history behind it (Boss, 20 Oct: "it always takes a support from somewhere back, or if there is a green rally, there
     * is also some history or resistance for that"): the earlier level it took support from or met as resistance, when that
     * level was made and how often price came back to it, and the rally or fall it is part of.
     */
    fun historyLines(r: CandleRead.Read): List<String> {
        val h = r.context.history ?: return emptyList()
        val out = ArrayList<String>()
        val move = h.move5?.let { m ->
            when { m >= 2.0 -> "part of a rally of ${CandleRead.f1(m)} ATR over the last 5 candles"; m <= -2.0 -> "part of a fall of ${CandleRead.f1(-m)} ATR over the last 5 candles"; else -> null }
        }
        val sup = h.support?.let { s ->
            if (h.held) "it took support at ${s.words()}: its low came within a quarter ATR of that level and it closed above it"
            else "the nearest earlier level under it is ${s.words()}"
        }
        val res = h.resistance?.let { s ->
            if (h.hit) "it ran into resistance at ${s.words()}: its high reached that level and it closed back under it"
            else "the nearest earlier level over it is ${s.words()}"
        }
        val parts = listOfNotNull(move, sup, res)
        if (parts.isEmpty()) return emptyList()
        out += "History behind it: " + parts.joinToString("; ") + "."
        if (h.held || h.hit) out += "An earlier level is where price paused before, not a wall: our research has not shown such levels hold more often than " +
            "any level as far away, so the agent counts how often they held (its own count, below in its table)."
        return out
    }

    /** The big players' kinds of post the agent reads into a candle. */
    private val PLAYERS = setOf(Findings.Kind.SWEEP, Findings.Kind.ABSORPTION, Findings.Kind.PULL, Findings.Kind.STOP_HUNT, Findings.Kind.BIG_MOVE)

    /** The epoch ms of a market time (IST). */
    private fun ms(t: LocalDateTime): Long = t.toInstant(java.time.ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()

    /** What the big-player agents (liquidity, order flow, trap guard, move recorder) posted on [r]'s instrument during its candle. */
    fun players(r: CandleRead.Read): List<Findings.Finding> = synchronized(lock) {
        val from = ms(r.bar.t); val to = ms(r.bar.t.plusMinutes(r.tf.toLong()))
        heardOn[r.inst.uppercase(Locale.ENGLISH)].orEmpty().values.filter { it.kind in PLAYERS && it.atMs in from..to }.sortedBy { it.atMs }
    }

    /**
     * The big players' hand in the candle (Boss, 20 Oct: "check how the big players have a hand in it"): their footprints in the
     * candle itself (a stop run, heavy volume with a small or a big range), the option writers' OI added since the last chain
     * read, and what the big-player agents posted during it (left out when [locked]). With the research's honest verdict.
     */
    fun playersLine(r: CandleRead.Read, locked: Boolean = false): String? {
        val bits = ArrayList<String>()
        if ("fp_stop_run_up" in r.shapes) bits += "stops above the day's high were taken and price closed back under it"
        if ("fp_stop_run_down" in r.shapes) bits += "stops under the day's low were taken and price closed back over it"
        if ("fp_absorption" in r.shapes) bits += "heavy volume (${CandleRead.f1(r.anatomy.volRatio ?: 0.0)}x usual) with a small range: someone absorbed the orders"
        if ("fp_climax" in r.shapes) bits += "heavy volume (${CandleRead.f1(r.anatomy.volRatio ?: 0.0)}x usual) with a big range: a climax"
        r.context.callBuild?.let { bits += "call writers added ${"%,d".format(Locale.ENGLISH, it.ceOiChg)} OI at ${CandleRead.px(it.strike)} (building resistance there)" }
        r.context.putBuild?.let { bits += "put writers added ${"%,d".format(Locale.ENGLISH, it.peOiChg)} OI at ${CandleRead.px(it.strike)} (building support there)" }
        // The bots' own posts are never said on a locked phone (Findings.LOCKED); the candle's own footprints and the chain are.
        if (!locked) for (f in players(r).take(3)) bits += "${f.who}: ${f.words.ifEmpty { f.kind.words }.take(80)}"
        if (bits.isEmpty()) return null
        return "Big players: " + bits.joinToString("; ") + ". In R86 and R87 these footprints did not predict the next move by themselves " +
            "(stop runs did not reverse), so they are context, not a signal."
    }

    private fun heardLine(inst: String, nowMs: Long): String? {
        val h = heard(inst, nowMs, locked = false).take(4)
        if (h.isEmpty()) return null
        return "Around it the other agents said: " + h.joinToString("; ") { "${it.who}: ${it.words.ifEmpty { it.kind.words }.take(90)}" } + "."
    }

    // ---- asking it -----------------------------------------------------------------------------------------------------------

    /** What another agent can ask. */
    sealed class Ask {
        /** The latest notable candle read on [inst] (any timeframe when [tf] is null). */
        data class Latest(val inst: String, val tf: Int? = null) : Ask()
        /** The nearest support and resistance under and over the latest candle on [inst], with their sources. */
        data class Levels(val inst: String) : Ask()
        /** What [shape] did on [inst] at [tf]: the research and its own count. */
        data class Odds(val inst: String, val tf: Int, val shape: String) : Ask()
    }

    /** Its answer: [words] in plain English, plus the typed parts an agent can use. Never an instruction. */
    data class Reply(val words: String, val read: CandleRead.Read? = null, val support: CandleRead.Level? = null, val resistance: CandleRead.Level? = null,
                     val research: CandleBook.Row? = null, val own: CandleBook.Cell? = null)

    /** Another agent's question, answered from memory (no I/O, short) and logged as asked and answered. */
    fun ask(from: String, q: Ask, nowMs: Long): Reply = synchronized(lock) {
        note(Exchange(nowMs, Way.ASKED, from, q.toString()))
        val reply = when (q) {
            is Ask.Latest -> latest.firstOrNull { it.inst.equals(q.inst, true) && (q.tf == null || it.read.tf == q.tf) }
                ?.let { Reply(it.lines.take(3).joinToString(" "), it.read, it.read.context.support, it.read.context.resistance) }
                ?: Reply("No notable candle read on ${q.inst.uppercase(Locale.ENGLISH)} yet.")
            is Ask.Levels -> latestRead(q.inst)?.let { r ->
                Reply(levelWords(r), r, r.context.support, r.context.resistance)
            } ?: Reply("No candle read on ${q.inst.uppercase(Locale.ENGLISH)} yet, so no levels.")
            is Ask.Odds -> {
                val row = seed.row(q.shape, q.inst.uppercase(Locale.ENGLISH), CandleBook.researchTf(q.tf))
                val cell = stats[CandleBook.key(q.inst, q.tf, q.shape, "all")]
                Reply((CandleBook.researchLine(seed, q.shape, q.inst, q.tf) ?: "No research row for ${CandleRead.name(q.shape)}.") +
                    " My own count: ${cell?.n5 ?: 0} settled.", research = row, own = cell)
            }
        }
        note(Exchange(nowMs, Way.ANSWERED, from, reply.words.take(140)))
        reply
    }

    /** The latest read on [inst], notable or not (the newest explained one). */
    private fun latestRead(inst: String): CandleRead.Read? = latest.firstOrNull { it.inst.equals(inst, true) }?.read

    private fun levelWords(r: CandleRead.Read): String {
        val c = r.context
        val s = c.support?.let { "support ${it.words()}" } ?: "no support level known"
        val res = c.resistance?.let { "resistance ${it.words()}" } ?: "no resistance level known"
        return "On ${r.inst} at ${CandleRead.px(r.bar.c)} (${r.tf}m candle ${CandleRead.timeOf(r.bar.t, r.tf)}): $s; $res. " +
            "A level is where price may pause, not a wall: R87 found wick ends broken as often as any level as far away."
    }

    // ---- Jarvis -----------------------------------------------------------------------------------------------------------------

    enum class Question { WHY_COLOUR, MEANING, SUPPORT }

    /** Jarvis's answer to [q] on [inst] (the newest notable read when null). [locked]: the other agents' trade results are left out. */
    fun answer(q: Question, inst: String?, locked: Boolean, nowMs: Long): String = synchronized(lock) {
        val ex = latest.firstOrNull { inst == null || it.inst.equals(inst, true) }
            ?: return@synchronized if (latest.isEmpty()) "The Candle agent has not read a notable candle yet, Boss (it reads in batches while the market is open)."
                else "The Candle agent has no notable candle on ${inst!!.uppercase(Locale.ENGLISH)} yet, Boss."
        val r = ex.read
        when (q) {
            Question.WHY_COLOUR -> "${ex.title}. " + whyColour(r) + " " + (CandleBook.researchLine(seed, r.primary, r.inst, r.tf) ?: "") +
                " Who won one candle did not tell the next candles' direction in the research."
            Question.MEANING -> explain(r, locked, nowMs).joinToString(" ")
            Question.SUPPORT -> levelWords(r)
        }.trim()
    }

    // ---- learning ---------------------------------------------------------------------------------------------------------------

    /** Tonight's lessons for Jarvis's pass. */
    fun lessons(day: LocalDate): List<AgentLessons.CandleResult> = synchronized(lock) { CandleBook.results(stats, seed, day) }

    /** Its proposals: notes for Boss, never an action. */
    fun notes(): List<String> = synchronized(lock) { CandleBook.notes(stats, seed) }

    /** The cells behind [notes], with their instruments (the page filters by them). */
    fun noteRows(): List<CandleBook.Compare> = synchronized(lock) { CandleBook.noteRows(stats, seed) }

    fun table(): List<CandleBook.Compare> = synchronized(lock) { CandleBook.compare(stats, seed) }

    fun growth(): Int = synchronized(lock) { CandleBook.growth(stats) }

    fun growthWords(): String = synchronized(lock) { CandleBook.growthWords(stats, seed) }

    // ---- state -----------------------------------------------------------------------------------------------------------------

    fun latest(): List<Explained> = synchronized(lock) { latest.toList() }

    fun exchanges(): List<Exchange> = synchronized(lock) { log.toList() }

    fun encodeStats(): String = synchronized(lock) { CandleBook.encode(stats) }

    /** The counted span of [series] ("NIFTY|5"): its first and last counted candle (nulls: none yet). */
    fun counted(series: String): Pair<LocalDateTime?, LocalDateTime?> = synchronized(lock) { stats.firsts[series] to stats.marks[series] }

    fun failed(e: Throwable) = synchronized(lock) { lastError = e.javaClass.simpleName }

    fun status(): Status = synchronized(lock) {
        Status(health, lastBatchMs, batches, reads, counted, stats.size, CandleBook.growth(stats), lastError)
    }

    private fun pc(x: Double) = "${Math.round(x * 100)}%"
}
