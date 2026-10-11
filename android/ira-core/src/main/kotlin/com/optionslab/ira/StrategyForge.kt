package com.optionslab.ira

import com.optionslab.ira.ShadowSim.Entry as E
import java.time.LocalDate
import java.util.Locale

/**
 * The forge (Boss, 11 Oct: "find new strategies"): a grammar of building blocks the engine already trades ([ShadowSim.Entry],
 * [ShadowSim.Filter], [ExitBlock]) composed into option-BUYING, 1-lot, near-the-money candidates, a few a night, each
 * backtested on the stored days with the paper costs plus half a spread more, exits on the option's 1-minute wicks where kept
 * (else the index-only approximation, on a stricter bar), and put through gates honest enough that almost everything fails -
 * as the research found (research/HUNT_FINAL.md: one real edge in dozens of ideas):
 *
 *  - at least [MIN_TRADES] trades ([APPROX_MIN_TRADES] on the approximation);
 *  - walk-forward: the days in three folds, positive after costs in at least two;
 *  - the random twin: the same number of random entries on the same days with the same exits, beaten with p < [TWIN_ALPHA]
 *    ([APPROX_TWIN_ALPHA] on the approximation);
 *  - Benjamini-Hochberg at [Q] across EVERY candidate ever generated (the global counter: [Book.tried], [Book.pvals]);
 *  - the deepest fall within [MAX_DD] (the Rs 1 lakh capital's rule).
 *
 * A survivor is not a strategy yet: it runs as a VIRTUAL shadow on the live days ([forwardDay]) for at least [FWD_MIN_DAYS]
 * trading days and [FWD_MIN_TRADES] trades, judged by the same sequential test ([DiscoveryStats.alwaysValidP]) with the
 * trial-count penalty; one that keeps passing becomes a PROPOSAL "start it on paper" (paper only: the forge never proposes
 * live). One that fails is retired with a lesson. [Book] keeps the graveyard's counts for Boss to see.
 *
 * It learns which blocks work ([weights]: a Beta per block from its own record - what Jarvis's lessons say, [LabLessons]),
 * and picks blocks by Thompson sampling with [EXPLORE] of picks kept uniformly random, so nothing is ever excluded. The gates
 * never read the weights. Caps: [PER_NIGHT] candidates, [BUDGET_MS] of work a night (cancellable), [MAX_FORWARD] in forward
 * test, [MAX_BYTES] kept. Pure: no clock (the app passes one), no I/O, no order.
 */
object StrategyForge {
    const val PER_NIGHT = 20
    const val BUDGET_MS = 60_000L
    const val MAX_FORWARD = 10
    const val MAX_BYTES = 5_000_000
    const val MIN_TRADES = 40
    const val APPROX_MIN_TRADES = 60
    const val TWIN_REPS = 199
    const val TWIN_ALPHA = 0.05
    const val APPROX_TWIN_ALPHA = 0.025
    const val Q = 0.10
    const val CAPITAL = 100_000.0
    /** The capital rule: a candidate's deepest fall stays within a fifth of the Rs 1 lakh. */
    const val MAX_DD = 20_000.0
    const val FWD_MIN_DAYS = 20
    const val FWD_MIN_TRADES = 30
    const val FWD_MAX_DAYS = 60
    const val FWD_MAX_TRADES = 90
    const val FWD_ALPHA = 0.05
    /** Never less than this share of block picks is uniformly random (the exploration floor). */
    const val EXPLORE = 0.10
    /** Rejected candidates kept in full (the rest are counted). */
    const val KEEP_REJECTED = 150


    /** The entry blocks the forge composes (the grammar's verbs). */
    val ENTRIES: List<ShadowSim.Entry> = listOf(E.ORB_BREAK, E.VWAP_RECLAIM, E.LEVEL_SWEEP, E.OI_BUILDUP, E.GAP_FADE, E.TIME_OF_DAY,
        E.VIX_DIVERGENCE, E.PREV_DAY_BREAK, E.SQUEEZE_BREAK)
    val FILTERS: List<ShadowSim.Filter> = ShadowSim.Filter.entries.toList()

    /** The exit blocks (each with a premium stop: a candidate never runs without one). */
    enum class ExitBlock(val words: String) {
        FIXED("fixed premium stop and target"), NO_PROGRESS("the no-progress exit"), TIME_STOP("a time stop"), INDEX_LEVEL("an index-level exit"),
    }

    fun key(e: ShadowSim.Entry) = "entry:${e.name}"
    fun key(f: ShadowSim.Filter) = "filter:${f.name}"
    fun key(x: ExitBlock) = "exit:${x.name}"

    /** Words for a block key. */
    fun words(k: String): String {
        val n = k.substringAfter(':')
        return when {
            k.startsWith("entry:") -> runCatching { ShadowSim.Entry.valueOf(n).words }.getOrDefault(n)
            k.startsWith("filter:") -> runCatching { "the " + ShadowSim.Filter.valueOf(n).words + " filter" }.getOrDefault(n)
            else -> runCatching { ExitBlock.valueOf(n).words }.getOrDefault(n)
        }
    }

    // ---- the candidate -----------------------------------------------------------------------------------------------------

    data class Candidate(val n: Int, val born: LocalDate, val underlying: String, val rules: ShadowSim.Rules, val exit: ExitBlock) {
        val id: String get() = "F$n"
        val name: String get() = "$id ${rules.entry.words} ${underlying.lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase() }} ${rules.barMin}m"
        val blocks: List<String> get() = listOf(key(rules.entry), key(exit)) + rules.filters.map { key(it) }
    }

    /** The gates' result on the stored days. [folds]: rupees per fold; [helped]: the filters that made it better than without. */
    data class Backtest(
        val trades: Int, val days: Int, val net: Double, val winRate: Double, val folds: List<Double>, val twinP: Double, val twinMean: Double,
        val maxDD: Double, val approx: Boolean, val gates: List<Pair<String, Boolean>>, val helped: Set<ShadowSim.Filter> = emptySet(),
        val from: LocalDate? = null, val to: LocalDate? = null,
    ) {
        val passed: Boolean get() = gates.isNotEmpty() && gates.all { it.second }
        val perTrade: Double get() = if (trades == 0) 0.0 else net / trades
        fun firstFailed(): String? = gates.firstOrNull { !it.second }?.first
    }

    enum class Stage(val words: String) {
        REJECTED("failed the backtest gates"), FORWARD("in forward test"), PROPOSED("proposed: start on paper"), PAPER("approved for paper"),
        RETIRED("retired after its forward test"), DECLINED("declined by you"),
    }

    /** One idea's record: its backtest, its forward days and trades' rupees, and why it stands where it does. */
    data class Record(val c: Candidate, val stage: Stage, val bt: Backtest?, val fwd: List<Double> = emptyList(), val fwdDays: Int = 0,
                      val fwdP: Double = 1.0, val why: String = "", val since: LocalDate? = null) {
        val fwdNet: Double get() = fwd.sum()
    }

    /** A block's record: tried, passed the backtest, passed / failed forward. The Beta weights and Jarvis's lessons read it. */
    data class BlockStat(val tried: Int = 0, val btPassed: Int = 0, val fwdPassed: Int = 0, val fwdFailed: Int = 0) {
        val successes: Double get() = btPassed + 2.0 * fwdPassed
        val failures: Double get() = (tried - btPassed).toDouble() + fwdFailed
    }

    /**
     * Everything kept: [tried] every candidate ever generated (the global counter), [pvals] every one's twin p (BH across them
     * all), the records, the counts of the graveyard, the blocks' records and each filter's (helped, survivors with it).
     */
    data class Book(
        val tried: Int = 0, val pvals: List<Double> = emptyList(), val records: List<Record> = emptyList(), val rejected: Int = 0,
        val retired: Int = 0, val proposed: Int = 0, val passed: Int = 0, val lastNight: LocalDate? = null, val lastForward: LocalDate? = null,
        val blocks: Map<String, BlockStat> = emptyMap(), val filterHelp: Map<String, Pair<Int, Int>> = emptyMap(),
    ) {
        val forward: List<Record> get() = records.filter { it.stage == Stage.FORWARD }
        val waiting: List<Record> get() = records.filter { it.stage == Stage.PROPOSED }
        fun find(id: String): Record? = records.firstOrNull { it.c.id.equals(id, ignoreCase = true) }
    }

    // ---- the weights (meta-learning) ---------------------------------------------------------------------------------------

    /** Each block's Beta(1 + successes, 1 + failures) from [book]'s record. */
    fun weights(book: Book): Map<String, Pair<Double, Double>> {
        val keys = ENTRIES.map { key(it) } + FILTERS.map { key(it) } + ExitBlock.entries.map { key(it) }
        return keys.associateWith { k -> book.blocks[k]?.let { 1.0 + it.successes to 1.0 + it.failures } ?: (1.0 to 1.0) }
    }

    /** The posterior mean of a block's success. */
    fun mean(w: Pair<Double, Double>) = w.first / (w.first + w.second)

    /** One of [keys]: with [EXPLORE] uniformly at random, else the best of a Thompson draw from [w]. */
    fun pick(keys: List<String>, w: Map<String, Pair<Double, Double>>, rng: DiscoveryStats.Rng): String {
        if (rng.nextDouble() < EXPLORE) return keys[rng.nextInt(keys.size)]
        var best = keys[0]; var top = -1.0
        for (k in keys) { val (a, b) = w[k] ?: (1.0 to 1.0); val x = rng.nextBeta(a, b); if (x > top) { top = x; best = k } }
        return best
    }

    // ---- the generator -----------------------------------------------------------------------------------------------------

    private fun <T> one(xs: List<T>, rng: DiscoveryStats.Rng): T = xs[rng.nextInt(xs.size)]

    /** One candidate numbered [n], born [day], on one of [underlyings]: blocks by [w], the parameters from small grids. */
    fun generate(n: Int, day: LocalDate, underlyings: List<String>, w: Map<String, Pair<Double, Double>>, rng: DiscoveryStats.Rng): Candidate {
        val entry = ShadowSim.Entry.valueOf(pick(ENTRIES.map { key(it) }, w, rng).substringAfter(':'))
        val exit = ExitBlock.valueOf(pick(ExitBlock.entries.map { key(it) }, w, rng).substringAfter(':'))
        val filters = if (rng.nextDouble() < 0.5) setOf(ShadowSim.Filter.valueOf(pick(FILTERS.map { key(it) }, w, rng).substringAfter(':')))
            else emptySet()
        val u = one(underlyings, rng)
        var r = ShadowSim.Rules(entry, barMin = one(listOf(1, 5, 15), rng), filters = filters, itm = rng.nextInt(2), maxTrades = 1 + rng.nextInt(2))
        r = when (entry) {
            E.ORB_BREAK -> r.copy(orMin = one(listOf(15, 30, 45, 60), rng), buffer = one(listOf(0.0, 0.0005, 0.001), rng), fresh = rng.nextInt(2) == 0)
            E.VWAP_RECLAIM, E.LEVEL_SWEEP, E.PREV_DAY_BREAK -> r.copy(buffer = one(listOf(0.0, 0.0005), rng))
            E.OI_BUILDUP -> r.copy(param = one(listOf(0.03, 0.05, 0.08), rng))
            E.GAP_FADE -> r.copy(param = one(listOf(0.002, 0.004, 0.006), rng), maxTrades = 1)
            E.TIME_OF_DAY -> r.copy(param = one(listOf(0.002, 0.004), rng), barMin = one(listOf(1, 5), rng), maxTrades = 1)
            E.VIX_DIVERGENCE -> r.copy(param = one(listOf(0.0015, 0.002, 0.003), rng), barMin = 1, maxTrades = 1)
            E.SQUEEZE_BREAK -> r.copy(param = one(listOf(4.0, 6.0, 8.0), rng))
            else -> r
        }
        val from = maxOf(one(listOf(9 * 60 + 20, 10 * 60, 11 * 60), rng), if (entry == E.ORB_BREAK) ShadowSim.OPEN + r.orMin + r.barMin else 0)
        val until = if (entry == E.TIME_OF_DAY) from + 30 else maxOf(from + 30, one(listOf(12 * 60, 13 * 60, 14 * 60), rng))
        r = r.copy(fromMin = from, untilMin = minOf(until, ShadowSim.LAST_ENTRY))
        val stop = one(listOf(0.15, 0.20, 0.25, 0.30), rng)
        r = when (exit) {
            ExitBlock.FIXED -> r.copy(stopPct = stop, targetPct = one(listOf(0.3, 0.5, 0.8, 1.0), rng))
            ExitBlock.NO_PROGRESS -> r.copy(stopPct = stop, noProgressMin = one(listOf(15, 20, 30), rng), noProgressGain = 0.05)
            ExitBlock.TIME_STOP -> r.copy(stopPct = stop, timeStopMin = one(listOf(30, 60, 90), rng))
            ExitBlock.INDEX_LEVEL -> r.copy(stopPct = stop, indexStopPct = one(listOf(0.0015, 0.0025), rng))
        }
        return Candidate(n, day, u, r, exit)
    }

    // ---- the gates --------------------------------------------------------------------------------------------------------

    /**
     * [c] backtested on [days] (its index's stored days, oldest first): every gate but the false-discovery one (that needs the
     * night's whole list: [night]). [stop] cuts the twin short (the budget): an unfinished twin is a failed gate.
     */
    fun backtest(c: Candidate, days: List<ShadowSim.Day>, stop: () -> Boolean = { false }): Backtest {
        val mine = days.filter { it.underlying == c.underlying }
        val trades = ShadowSim.runAll(c.rules, mine)
        val approx = trades.count { it.approx } * 2 > trades.size
        val nets = trades.map { it.net }
        val net = nets.sum()
        val thirds = if (mine.isEmpty()) emptyList() else (0 until 3).map { f ->
            val lo = mine.size * f / 3; val hi = mine.size * (f + 1) / 3
            val set = mine.subList(lo, hi).map { it.date }.toSet()
            trades.filter { it.day in set }.sumOf { it.net }
        }
        val minTrades = if (approx) APPROX_MIN_TRADES else MIN_TRADES
        val alpha = if (approx) APPROX_TWIN_ALPHA else TWIN_ALPHA
        val mean = if (nets.isEmpty()) 0.0 else nets.average()
        var twinMean = 0.0
        val p = if (trades.size < minTrades || mean <= 0) 1.0 else {
            val byDay = mine.associateBy { it.date }
            val rng = DiscoveryStats.Rng(c.n * 7_919L + c.born.toEpochDay())
            val means = ArrayList<Double>(TWIN_REPS)
            for (i in 0 until TWIN_REPS) {
                if (stop()) break
                val tw = ShadowSim.twin(c.rules, byDay, trades, rng)
                means += if (tw.isEmpty()) 0.0 else tw.sumOf { it.net } / tw.size
            }
            twinMean = if (means.isEmpty()) 0.0 else means.average()
            if (means.size < TWIN_REPS) 1.0 else DiscoveryStats.twinP(mean, means)
        }
        val dd = DiscoveryStats.maxDrawdown(nets)
        val gates = listOf(
            "at least $minTrades trades" + (if (approx) " (index-only approximation: stricter)" else "") to (trades.size >= minTrades),
            "net positive after costs" to (net > 0),
            "walk-forward: positive in 2 of 3 folds" to (thirds.count { it > 0 } >= 2),
            "beats its random twin (p < $alpha)" to (p < alpha),
            "deepest fall within ${DiscoveryStats.rs(MAX_DD).removePrefix("+")}" to (dd <= MAX_DD),
        )
        return Backtest(trades.size, mine.size, net, if (trades.isEmpty()) 0.0 else 100.0 * trades.count { it.net > 0 } / trades.size,
            thirds, p, twinMean, dd, approx, gates, from = mine.firstOrNull()?.date, to = mine.lastOrNull()?.date)
    }

    /** The filters of [c] that made its stored days better than the same rules without each (a survivor's lesson). */
    fun helped(c: Candidate, days: List<ShadowSim.Day>, withNet: Double): Set<ShadowSim.Filter> {
        val mine = days.filter { it.underlying == c.underlying }
        return c.rules.filters.filter { f -> withNet > ShadowSim.runAll(c.rules.copy(filters = c.rules.filters - f), mine).sumOf { it.net } }.toSet()
    }

    // ---- the night ---------------------------------------------------------------------------------------------------------

    data class Night(val book: Book, val made: List<Record>, val stoppedEarly: Boolean, val ms: Long)

    /**
     * One night: up to [n] candidates (deterministic for [day] and the book), each counted as tried and backtested on
     * [history] (per index, oldest first), the false-discovery check across every p ever, survivors into the forward test
     * while there is room. Stops when [nowMs] passes [budgetMs] or [cancelled] says so (what was not made is not counted).
     */
    fun night(book: Book, day: LocalDate, history: Map<String, List<ShadowSim.Day>>, nowMs: () -> Long, budgetMs: Long = BUDGET_MS,
              cancelled: () -> Boolean = { false }, n: Int = PER_NIGHT): Night {
        val t0 = nowMs()
        val unders = history.filterValues { it.size >= 30 }.keys.sorted()
        if (unders.isEmpty() || book.lastNight == day) return Night(book, emptyList(), false, 0)
        val rng = DiscoveryStats.Rng(DiscoveryStats.seedOf(day, 17))
        var b = book.copy(lastNight = day)
        val made = ArrayList<Record>()
        var early = false
        val over = { nowMs() - t0 > budgetMs || cancelled() }
        for (i in 0 until n) {
            if (over()) { early = true; break }
            val c = generate(b.tried + 1, day, unders, weights(b), rng)
            val bt0 = backtest(c, history[c.underlying].orEmpty(), over)
            // Cut short by the budget: its twin may be unfinished, so it is not judged (nor counted) - tried again another night.
            if (over()) { early = true; break }
            val (nb, rec) = judge(b, c, bt0, history[c.underlying].orEmpty(), day)
            b = nb; made += rec
        }
        return Night(trim(b), made, early, nowMs() - t0)
    }

    /**
     * [c]'s backtest [bt0] judged into [b]: counted as tried (the global counter), its p added to every p ever, the
     * false-discovery check across them all, the forward test while there is room, the blocks' records. The gates never
     * read the blocks' weights.
     */
    fun judge(b: Book, c: Candidate, bt0: Backtest, history: List<ShadowSim.Day>, day: LocalDate): Pair<Book, Record> {
        val pvals = b.pvals + bt0.twinP
        val tried = b.tried + 1
        val bh = DiscoveryStats.bhPass(bt0.twinP, pvals + List((tried - pvals.size).coerceAtLeast(0)) { 1.0 }, Q)
        var bt = bt0.copy(gates = bt0.gates + ("false-discovery check across all $tried ideas ever tried" to bh))
        val room = b.forward.size < MAX_FORWARD
        if (bt.passed) bt = bt.copy(helped = helped(c, history, bt.net))
        val stage = if (bt.passed && room) Stage.FORWARD else Stage.REJECTED
        val why = when {
            bt.passed && !room -> "passed every gate, but $MAX_FORWARD ideas are already in forward test"
            bt.passed -> "passed every gate on ${bt.days} stored days: now a virtual forward test of $FWD_MIN_DAYS+ days"
            else -> "failed: ${bt.firstFailed()}"
        }
        val rec = Record(c, stage, bt, why = why, since = day)
        val nb = b.copy(tried = tried, pvals = pvals, records = b.records + rec, rejected = b.rejected + if (stage == Stage.REJECTED) 1 else 0,
            passed = b.passed + if (bt.passed) 1 else 0,
            blocks = bump(b.blocks, c.blocks) { it.copy(tried = it.tried + 1, btPassed = it.btPassed + if (bt.passed) 1 else 0) },
            filterHelp = if (!bt.passed) b.filterHelp else c.rules.filters.fold(b.filterHelp) { m, f ->
                val (h, s) = m[key(f)] ?: (0 to 0); m + (key(f) to ((h + if (f in bt.helped) 1 else 0) to (s + 1)))
            })
        return nb to rec
    }

    private fun bump(m: Map<String, BlockStat>, keys: List<String>, f: (BlockStat) -> BlockStat): Map<String, BlockStat> =
        keys.distinct().fold(m) { acc, k -> acc + (k to f(acc[k] ?: BlockStat())) }

    private fun trim(b: Book): Book {
        val rej = b.records.filter { it.stage == Stage.REJECTED }
        val keep = if (rej.size > KEEP_REJECTED) b.copy(records = b.records - rej.take(rej.size - KEEP_REJECTED).toSet()) else b
        // The storage cap: older finished records' forward rupees dropped first (their counts stay), then the oldest records.
        var k = keep
        while (encode(k).length > MAX_BYTES && k.records.isNotEmpty()) {
            val old = k.records.firstOrNull { it.stage in setOf(Stage.REJECTED, Stage.RETIRED, Stage.DECLINED) } ?: break
            k = k.copy(records = k.records - old)
        }
        return k
    }

    // ---- the forward test ----------------------------------------------------------------------------------------------------

    sealed class Event {
        data class Proposed(val record: Record) : Event()
        data class Retired(val record: Record) : Event()
    }

    data class Forward(val book: Book, val events: List<Event>)

    /**
     * One live day ([days]: the day's data per index): every candidate in forward test (and every one approved for paper, as a
     * virtual paper record) follows it; then the forward judgement with the same sequential test and the trial penalty.
     */
    fun forwardDay(book: Book, date: LocalDate, days: Map<String, ShadowSim.Day>): Forward {
        if (book.lastForward != null && !date.isAfter(book.lastForward)) return Forward(book, emptyList())
        var b = book.copy(lastForward = date)
        val events = ArrayList<Event>()
        val penalty = DiscoveryStats.penalizedAlpha(FWD_ALPHA, b.tried)
        b = b.copy(records = b.records.map { r ->
            if (r.stage != Stage.FORWARD && r.stage != Stage.PAPER) return@map r
            val d = days[r.c.underlying] ?: return@map r
            val nets = ShadowSim.run(r.c.rules, d).map { it.net }
            val fwd = (r.fwd + nets).takeLast(400)
            val x = r.copy(fwd = fwd, fwdDays = r.fwdDays + 1)
            if (r.stage == Stage.PAPER) return@map x
            val pPass = DiscoveryStats.alwaysValidP(fwd, 1, 100.0)
            val pFail = DiscoveryStats.alwaysValidP(fwd, -1, 100.0)
            val dd = DiscoveryStats.maxDrawdown(fwd)
            val record = "${fwd.size} trades over ${x.fwdDays} days, ${DiscoveryStats.rs(fwd.sum())}"
            when {
                x.fwdDays >= FWD_MIN_DAYS && fwd.size >= FWD_MIN_TRADES && fwd.average() > 0 && pPass <= penalty -> {
                    val y = x.copy(stage = Stage.PROPOSED, fwdP = pPass, why = "kept passing its forward test: $record (sequential p ${VariantLab.fmtP(pPass)}, " +
                        "the bar after ${b.tried} ideas ${VariantLab.fmtP(penalty)})", since = date)
                    events += Event.Proposed(y); y
                }
                (fwd.size >= 15 && pFail <= FWD_ALPHA) || dd > MAX_DD || x.fwdDays >= FWD_MAX_DAYS || fwd.size >= FWD_MAX_TRADES -> {
                    val cause = when {
                        dd > MAX_DD -> "its deepest fall ${DiscoveryStats.rs(-dd)} broke the capital rule"
                        fwd.size >= 15 && pFail <= FWD_ALPHA -> "it lost on live days (p ${VariantLab.fmtP(pFail)})"
                        else -> "no proof after ${x.fwdDays} days"
                    }
                    val y = x.copy(stage = Stage.RETIRED, fwdP = pPass, why = "retired: $cause - $record, against ${DiscoveryStats.rs(r.bt?.net ?: 0.0)} in its backtest", since = date)
                    events += Event.Retired(y); y
                }
                else -> x.copy(fwdP = pPass)
            }
        })
        for (e in events) when (e) {
            is Event.Proposed -> b = b.copy(proposed = b.proposed + 1, blocks = bump(b.blocks, e.record.c.blocks) { it.copy(fwdPassed = it.fwdPassed + 1) })
            is Event.Retired -> b = b.copy(retired = b.retired + 1, blocks = bump(b.blocks, e.record.c.blocks) { it.copy(fwdFailed = it.fwdFailed + 1) })
        }
        return Forward(b, events)
    }

    /** Boss's yes to "start it on paper": the record becomes PAPER (its virtual paper record goes on). Live is never here. */
    fun approve(b: Book, id: String, day: LocalDate): Book = b.copy(records = b.records.map {
        if (it.c.id == id && it.stage == Stage.PROPOSED) it.copy(stage = Stage.PAPER, why = "you approved it for paper", since = day) else it
    })

    fun declineProposal(b: Book, id: String, day: LocalDate): Book = b.copy(records = b.records.map {
        if (it.c.id == id && it.stage == Stage.PROPOSED) it.copy(stage = Stage.DECLINED, why = "you declined it", since = day) else it
    })

    // ---- the graveyard -------------------------------------------------------------------------------------------------------

    /** Ideas tried, passed the backtest, in forward test, proposed, retired after forward, rejected by the backtest. */
    data class Counts(val tried: Int, val passed: Int, val forward: Int, val proposed: Int, val retired: Int, val rejected: Int, val paper: Int)

    fun counts(b: Book): Counts = Counts(b.tried, b.passed,
        b.forward.size, b.proposed, b.retired, b.rejected, b.records.count { it.stage == Stage.PAPER })

    // ---- kept on the phone -----------------------------------------------------------------------------------------------

    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ').replace('~', '-')

    fun encode(b: Book): String = buildString {
        append("T|${b.tried}|${b.rejected}|${b.retired}|${b.proposed}|${b.lastNight ?: ""}|${b.lastForward ?: ""}|${b.passed}\n")
        append("P|${b.pvals.joinToString(";") { "%.5g".format(Locale.ENGLISH, it) }}\n")
        for ((k, s) in b.blocks.toSortedMap()) append("B|$k|${s.tried}|${s.btPassed}|${s.fwdPassed}|${s.fwdFailed}\n")
        for ((k, s) in b.filterHelp.toSortedMap()) append("H|$k|${s.first}|${s.second}\n")
        for (r in b.records) {
            val c = r.c
            append("R|${c.n}|${c.born}|${c.underlying}|${c.exit.name}|${ShadowSim.encode(c.rules)}|${r.stage.name}|${r.fwdDays}|${r.fwdP}|${r.since ?: ""}|" +
                "${r.fwd.joinToString(";") { "%.1f".format(Locale.ENGLISH, it) }}|${clean(r.why)}|${r.bt?.let { bt(it) } ?: ""}\n")
        }
    }

    private fun bt(x: Backtest): String = listOf(x.trades, x.days, x.net, x.winRate, x.folds.joinToString(";"), x.twinP, x.twinMean, x.maxDD,
        if (x.approx) 1 else 0, x.gates.joinToString(";") { "${clean(it.first).replace(';', ',').replace('^', ' ')}^${if (it.second) 1 else 0}" },
        x.helped.joinToString(";") { it.name }, x.from ?: "", x.to ?: "").joinToString("~")

    private fun unbt(s: String): Backtest? = runCatching {
        val p = s.split('~')
        Backtest(p[0].toInt(), p[1].toInt(), p[2].toDouble(), p[3].toDouble(), p[4].split(';').filter { it.isNotBlank() }.map { it.toDouble() },
            p[5].toDouble(), p[6].toDouble(), p[7].toDouble(), p[8] == "1",
            p[9].split(';').filter { '^' in it }.map { it.substringBefore('^') to (it.substringAfter('^') == "1") },
            p[10].split(';').filter { it.isNotBlank() }.map { ShadowSim.Filter.valueOf(it) }.toSet(),
            p[11].takeIf { it.isNotEmpty() }?.let(LocalDate::parse), p[12].takeIf { it.isNotEmpty() }?.let(LocalDate::parse))
    }.getOrNull()

    fun decode(text: String?): Book {
        if (text.isNullOrBlank()) return Book()
        var b = Book()
        val recs = ArrayList<Record>(); val blocks = HashMap<String, BlockStat>(); val help = HashMap<String, Pair<Int, Int>>()
        for (l in text.lines()) runCatching {
            val p = l.split('|')
            when (p[0]) {
                "T" -> b = b.copy(tried = p[1].toInt(), rejected = p[2].toInt(), retired = p[3].toInt(), proposed = p[4].toInt(),
                    lastNight = p[5].takeIf { it.isNotEmpty() }?.let(LocalDate::parse), lastForward = p[6].takeIf { it.isNotEmpty() }?.let(LocalDate::parse), passed = p.getOrNull(7)?.toIntOrNull() ?: 0)
                "P" -> b = b.copy(pvals = p[1].split(';').filter { it.isNotBlank() }.map { it.toDouble() })
                "B" -> blocks[p[1]] = BlockStat(p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt())
                "H" -> help[p[1]] = p[2].toInt() to p[3].toInt()
                "R" -> {
                    val rules = ShadowSim.decode(p[5]) ?: return@runCatching
                    recs += Record(Candidate(p[1].toInt(), LocalDate.parse(p[2]), p[3], rules, ExitBlock.valueOf(p[4])), Stage.valueOf(p[6]),
                        unbt(p.getOrElse(12) { "" }), p[10].split(';').filter { it.isNotBlank() }.map { it.toDouble() }, p[7].toInt(), p[8].toDouble(),
                        p[11], p[9].takeIf { it.isNotEmpty() }?.let(LocalDate::parse))
                }
            }
        }
        return b.copy(records = recs, blocks = blocks, filterHelp = help)
    }
}
