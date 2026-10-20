package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * Jarvis learns from the agents (Boss, 11 Oct: "I also want Jarvis to learn from all these agents we created, and help him
 * grow"). Once a day after the close the app hands this digest what every agent kept - the bots' closed trades, the market
 * brain's regime, the self-review's health per strategy, the trade manager's counterfactual records and its specialists'
 * parts in them, the order flow's shadow log (with the trap guard's flags, the findings' consensus and the brain's would-be
 * pauses beside each signal), the findings bus's data-health posts, self-healing's incidents and the move recorder's big
 * moves - and it turns them into [Lesson]s: a plain statement with the evidence behind it (how many, the rupee effect, the
 * hit rate, first and last seen, which agents said it) and an honest confidence.
 *
 *  - Evidence is kept per day ([Bucket]); a day seen again replaces that day (so a pass is idempotent), days a source no
 *    longer keeps stay. New evidence strengthens a lesson, evidence against it weakens it, and a weakened lesson contradicted
 *    again retires. One key, one lesson: duplicates merge ([merge]).
 *  - Confidence ([confidence]): strong needs [STRONG_N] or more and the same sign in at least [AGREE] of [SPLITS] time
 *    splits; building needs [BUILDING_N]; else weak. Nothing is said about rupees without the numbers beside it.
 *  - Honest statistics (11 Oct, "smarter learning"): every lesson gets a p-value ([pValue]: an always-valid test on its
 *    per-day rupees - it is looked at every night - an exact binomial test on a rate) and, each night, a Benjamini-Hochberg
 *    q-value across all active lessons ([LearnStats.bh], [BH_Q] = 0.10; the lab's own lessons in a family of their own). Strong also needs q <= [BH_Q]; the evidence line shows q. A lesson may be conditional
 *    ("on busy days only", [conditions]): its days split by the market brain's regime, the expiry-day flag or the part of
 *    the day, [COND_N]+ cases a side, the split's own BH family passing at [BH_Q], and the two sides pointing opposite ways.
 *  - The strategy lab (S, 11 Oct): its results ([LabResult]: one forge idea's test of a building block or a filter, one
 *    variant's parameter direction - passed or failed, its rupees) are evidence like any other agent's: per day, a rate
 *    against a half, the same confidence, a Benjamini-Hochberg family of their own ("lab:" lessons). [LabLessons.results] makes them.
 *  - Hard caps: [MAX_LESSONS] lessons and [MAX_BYTES] encoded, retired and then the oldest weak lessons dropped first.
 *  - A strong lesson that implies a change becomes a [Proposal] for Boss - only ever one that lowers risk or acts on paper
 *    ([ProposalKind]); never the PIN, the lock, Live, AI-live, a guard, the Google speech choice, the day lock or real
 *    orders ([safe]). Boss's yes or no is kept ([answered]): a yes raises the lesson's weight, a no lowers it, and
 *    [REJECTS] noes stop it being proposed for [COOLDOWN_DAYS] days.
 *  - The research's own findings travel with them as fixed notes ([RESEARCH]: Majority Rule 14, R13), said in the answers
 *    and the model's notes beside the lessons - never a lesson, never a proposal, never a trade or more risk.
 *  - 13 Oct (one shared knowledge, [SharedKnowledge]): the slippage record's live fills and the Liquidity paper test's signal
 *    spreads, judged against the research's real spread ([ExecResult], "exec:" lessons, [EXECUTION]), and the nightly market study's
 *    setups ([StudyResult], "study:" snapshot lessons, [STUDY]) are evidence like every other agent's - the same buckets, tests and
 *    confidence; they never propose anything.
 *
 * Nothing here acts. Pure: the app reads the agents' stores and keeps the [Book] (noBackupFilesDir).
 */
object AgentLessons {
    enum class Topic(val title: String, val unit: String) {
        STRATEGY("Strategies", "trades"),
        MANAGER("Trade manager", "managed trades"),
        SPECIALIST("The manager's specialists", "settled votes"),
        ORDER_FLOW("Order flow and traps", "shadow trades"),
        REGIME("Market regime", "trades"),
        TIME_OF_DAY("Time of day", "trades"),
        DATA_HEALTH("Data health", "days watched"),
        LAB("Strategy lab", "lab tests"),
        /** The order review's recurring flaws (12 Oct): a rate of the orders and trades reviewed. */
        ORDERS("Order review", "orders reviewed"),
        /** Fills and signal-time spreads against the research's baseline (13 Oct: the slippage record, the Liquidity paper test). */
        EXECUTION("Execution", "fills"),
        /** Jarvis's nightly market study (13 Oct): which setups held in both years of candles. */
        STUDY("Market study", "days counted"),
        /** The Candle agent's own counts per candle shape against the research (20 Oct): a nightly snapshot, like the study's. */
        CANDLES("Candle agent", "candles counted"),
    }

    enum class Confidence(val word: String) { WEAK("weak"), BUILDING("building"), STRONG("strong") }
    enum class Status(val word: String) { NEW("new"), CONFIRMED("confirmed"), WEAKENED("weakened"), RETIRED("retired") }

    /** How a lesson's evidence adds up: rupees per trade, a rate of days or events, or the latest of a daily review. */
    enum class Kind { RUPEES, RATE, SNAPSHOT }

    const val MAX_LESSONS = 300
    const val MAX_BYTES = 600_000
    const val MAX_BUCKETS = 60
    const val STRONG_N = 30
    const val BUILDING_N = 10
    const val SPLITS = 3
    const val AGREE = 2
    const val COOLDOWN_DAYS = 30L
    const val REJECTS = 2
    const val REPROPOSE_DAYS = 7L
    const val MAX_PROPOSALS = 2
    const val MODEL_CHARS = 600
    const val TIMELINE = 100
    const val REGIME_DAYS = 400
    /** The false-discovery rate the night's lessons are held to (Benjamini-Hochberg). */
    const val BH_Q = 0.10
    /** A conditional lesson needs this many cases on each side of its split. */
    const val COND_N = 20

    /** Data trouble on this share of the days watched or more: "trouble often". */
    const val HEALTH_RATE = 0.2
    /** This share of the big moves or more in one part of the day: "common". */
    const val MOVES_RATE = 0.4
    /** A recurring order flaw on this share of a strategy's reviewed orders or more: "keeps happening" (the order review). */
    const val ORDER_RATE = 0.2
    /** Fills off the research's baseline on this share or more: "often off the baseline" (13 Oct, [ExecResult]). */
    const val EXEC_RATE = 0.2

    /** One day's evidence: [n] trades (or days, or events), their rupee effect [rs], and [hits] (won, or the event seen). */
    data class Bucket(val day: LocalDate, val n: Int, val rs: Double, val hits: Int,
                      /** The first day it holds: older days folded into one ([MAX_BUCKETS]) run [from] to [day]. */
                      val from: LocalDate = day)

    data class Lesson(
        val id: String, val topic: Topic, val kind: Kind,
        /** The subject in words ("Liquidity 15+5 entered in the morning (before 10:30)"). */
        val name: String,
        /** Words it is found by ("liquidity", "oi", "morning"). */
        val tags: List<String>,
        val agents: Set<String>,
        val buckets: List<Bucket>,
        /** +1 / -1: which way the evidence points (0: even). */
        val sign: Int,
        val status: Status,
        val confidence: Confidence,
        val firstSeen: LocalDate, val lastSeen: LocalDate,
        /** The day its status last changed. */
        val statusOn: LocalDate,
        /** The evidence count when last judged (a pass with nothing new judges nothing). */
        val checkedN: Int = 0,
        val flips: Int = 0,
        /** Boss's answers move it: ranks the lesson in answers and in the model's notes. */
        val weight: Double = 1.0,
        val rejections: Int = 0,
        val cooldownUntil: LocalDate? = null,
        val proposedOn: LocalDate? = null,
        val accepted: Boolean = false,
        /** What Autopilot made of it ([AutoChange]): "applied 11 Oct", "kept 18 Oct", "reverted 18 Oct"; null: nothing. */
        val auto: String? = null,
        /** Its p-value ([pValue]) and its Benjamini-Hochberg q-value across tonight's active lessons ([pass]); null: not tested yet. */
        val p: Double? = null, val q: Double? = null,
        /**
         * The one condition its effect holds under, when a split passes ([conditions]): "regime:busy", "expiry:yes",
         * "expiry:no", "tod:morning"; null: it holds everywhere (or no split could say otherwise). [condQ]: the split's q.
         */
        val cond: String? = null, val condQ: Double? = null,
    ) {
        /** Its evidence, worked out once (a copy works it out afresh); not part of what it equals. */
        internal val ev: Evidence by lazy(LazyThreadSafetyMode.PUBLICATION) { evidence(kind, id, buckets) }
    }

    data class Milestone(val day: LocalDate, val key: String, val text: String)

    enum class ChangeKind(val word: String) { NEW("new"), STRONGER("stronger"), CONFIRMED("confirmed"), WEAKENED("weakened"), RETIRED("retired") }

    data class Change(val id: String, val kind: ChangeKind)

    /** Everything kept: the lessons, the regime per day, the growth timeline, and the last pass's day, changes and line. */
    data class Book(
        val lessons: List<Lesson> = emptyList(),
        val regimes: Map<LocalDate, String> = emptyMap(),
        val timeline: List<Milestone> = emptyList(),
        val lastPass: LocalDate? = null,
        val today: List<Change> = emptyList(),
        val line: String? = null,
    )

    /** What the agents kept, as the app reads it (each part may be empty). */
    data class Inputs(
        val day: LocalDate,
        /** The bots' closed trades (owner: the bot's name). */
        val trips: List<Insights.Trip> = emptyList(),
        /** The market brain's regime for Nifty today ("busy", "quiet", "normal"); null: not known. */
        val regimeToday: String? = null,
        val drift: List<DriftReview.Review> = emptyList(),
        val managed: List<TradeManager.Record> = emptyList(),
        val flow: List<FlowShadow.Signal> = emptyList(),
        val findings: List<Findings.Finding> = emptyList(),
        val incidents: List<SelfHeal.Incident> = emptyList(),
        val moves: List<MoveEvents.Named> = emptyList(),
        /** Specialists muted now, "family:id" to why (the specialists' own book). */
        val muted: Map<String, String> = emptyMap(),
        /** Self-review keys whose new paper entries are paused now. */
        val paused: Set<String> = emptySet(),
        /** The trade manager's mode on paper per family now. */
        val paperModes: Map<String, TradeManager.Mode> = emptyMap(),
        /** The strategy lab's results ([LabLessons.results]): forge ideas' blocks and filters, variants' parameter moves. */
        val lab: List<LabResult> = emptyList(),
        /** The order review's RECURRING flaws only ([OrderReview.lessonInputs]): a one-off flaw is never a lesson. */
        val orders: List<OrderFlawResult> = emptyList(),
        /** Fills and spreads judged against the research's baseline (13 Oct: [SharedKnowledge.execResults]). */
        val exec: List<ExecResult> = emptyList(),
        /** The nightly market study's setups (13 Oct: [Study.Finding]s as [StudyResult]s). */
        val study: List<StudyResult> = emptyList(),
        /** The Candle agent's shapes, its own count against the research (20 Oct: [CandleBook.results]). */
        val candles: List<CandleResult> = emptyList(),
    )

    /**
     * One day's fills of one strategy on one index judged against the research's execution baseline (13 Oct: the slippage record,
     * "fills:ORB:BANKNIFTY", and the Liquidity paper test's signal-time spreads, "spread:Liquidity:BANKNIFTY"): [n] fills, [hits] of
     * them off the baseline ([SharedKnowledge.offBaseline]). A rate, like the order review's flaws; [agent] says whose it is.
     */
    data class ExecResult(val day: LocalDate, val key: String, val name: String, val n: Int, val hits: Int, val tags: List<String> = emptyList(),
                          val agent: String = if (key.startsWith("spread")) "the Liquidity paper test" else "the slippage record")

    /**
     * One setup of the nightly market study (13 Oct): on [day] it counted [days] days of [key] ("nifty:gapup") and the outcome
     * [held] in both years or was a coin toss. The study counts the whole two years again each night, so this is the latest of a
     * daily review (a snapshot), never added up night after night.
     */
    data class StudyResult(val day: LocalDate, val key: String, val name: String, val days: Int, val held: Boolean, val tags: List<String> = emptyList())

    /**
     * One candle shape of the Candle agent (20 Oct): on [day] its own count of [key] ("nifty:5:big_lower_wick") had [n] settled
     * candles and [agrees] with the research (R87 / R85) or not. Its counts are cumulative and re-read each night, so this is the
     * latest of a daily review (a snapshot), and it stays at building at most ([ceiling]): never a proposal, never an action.
     */
    data class CandleResult(val day: LocalDate, val key: String, val name: String, val n: Int, val agrees: Boolean, val tags: List<String> = emptyList())

    /**
     * One recurring order flaw on one day (the order review, 12 Oct): [key] "orb_fresh:paper:gb", [n] the strategy's orders or
     * trades reviewed that day, [hits] how many had the flaw, [rs] its rupees (a loss: negative).
     */
    data class OrderFlawResult(val day: LocalDate, val key: String, val name: String, val n: Int, val hits: Int, val rs: Double = 0.0,
                               val tags: List<String> = emptyList())

    /**
     * One strategy-lab result (S, 11 Oct: lab lessons as Jarvis's own): on [day], [n] tests of [key] (a block "block:entry:X",
     * a filter "filter:X", a parameter direction "param:liquidity:stopPct:wider"), [passed] or not, with [rs] rupees (the
     * backtest's or the forward test's net; 0 when there are none). [name] says it ("Ideas using VWAP reclaim").
     */
    data class LabResult(val day: LocalDate, val key: String, val name: String, val passed: Boolean, val rs: Double = 0.0, val n: Int = 1,
                         val tags: List<String> = emptyList())

    enum class ProposalKind(val words: String) {
        MUTE_SPECIALIST("mute a specialist for one strategy"),
        PAUSE_PAPER_ENTRIES("pause one strategy's new paper entries"),
        MANAGER_ACT_PAPER("let the trade manager act on one strategy's paper trades"),
    }

    /** A change one strong lesson implies, put to Boss (never done by Jarvis). */
    data class Proposal(val lessonId: String, val kind: ProposalKind, val family: String, val specialist: String? = null,
                        val title: String, val text: String)

    data class Pass(val book: Book, val changes: List<Change>, val proposals: List<Proposal>, val line: String, val finding: String)

    // ---- time ---------------------------------------------------------------------------------------------------------

    private const val IST_MS = 19_800_000L
    private const val DAY_MS = 86_400_000L
    fun dayOf(ms: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(ms + IST_MS, DAY_MS))
    fun minuteOf(ms: Long): Int = (Math.floorMod(ms + IST_MS, DAY_MS) / 60_000L).toInt()

    /** The afternoon ends with F&O's close, 15:40 since Aug 2026 (G6-6): its last ten minutes are not MCX's evening. */
    private const val AFTERNOON_END = 15 * 60 + 40

    /** The part of the day an entry at [minute] (IST) falls in. */
    fun slot(minute: Int): String = when {
        minute < 10 * 60 + 30 -> "morning"
        minute < 13 * 60 + 30 -> "midday"
        minute < AFTERNOON_END -> "afternoon"
        else -> "evening"
    }

    private fun slotWords(s: String): String = when (s) {
        "morning" -> "in the morning (before 10:30)"
        "midday" -> "at midday (10:30-13:30)"
        "afternoon" -> "in the afternoon (13:30-15:40)"
        else -> "in the evening (after 15:40)"
    }

    // ---- the evidence, worked out from a lesson's days --------------------------------------------------------------------

    /** A lesson's evidence: [n], rupees [rs], [hits] of [of], and the sign of each time split (oldest first). */
    data class Evidence(val n: Int, val rs: Double, val hits: Int, val of: Int, val sign: Int, val splits: List<Int>) {
        val hitRate: Double? get() = if (of == 0) null else hits.toDouble() / of
        val recent: Int get() = splits.lastOrNull() ?: 0
        val agreeing: Int get() = splits.count { it == sign && it != 0 }
    }

    private fun prefix(id: String) = id.substringBefore(':')

    private fun rateSign(prefix: String, hits: Int, n: Int): Int {
        if (n <= 0) return 0
        val r = hits.toDouble() / n
        return when (prefix) {
            "health" -> if (r >= HEALTH_RATE) -1 else 1
            "moves" -> if (r >= MOVES_RATE) 1 else -1
            "order" -> if (r >= ORDER_RATE) -1 else 1
            "exec" -> if (r >= EXEC_RATE) -1 else 1
            else -> if (r >= 0.5) 1 else -1
        }
    }

    private fun sgn(x: Double): Int = if (x > 1e-9) 1 else if (x < -1e-9) -1 else 0

    fun evidence(kind: Kind, id: String, buckets: List<Bucket>): Evidence {
        if (buckets.isEmpty()) return Evidence(0, 0.0, 0, 0, 0, emptyList())
        if (kind == Kind.SNAPSHOT) {
            val last = buckets.last()
            val signs = buckets.takeLast(SPLITS).map { if (it.hits > 0) -1 else 1 }
            val sign = if (last.hits > 0) -1 else 1
            return Evidence(last.n, last.rs, buckets.count { it.hits > 0 }, buckets.size, sign, if (signs.size < SPLITS) emptyList() else signs)
        }
        var n = 0; var rs = 0.0; var hits = 0
        for (b in buckets) { n += b.n; rs += b.rs; hits += b.hits }
        val p = prefix(id)
        fun signOf(sn: Int, srs: Double, sh: Int) = if (kind == Kind.RUPEES) sgn(srs) else rateSign(p, sh, sn)
        // Three splits by cumulative count, oldest first (each day goes to the third its middle falls in); a split with
        // nothing in it - too few days - means no splits at all.
        var splits: List<Int> = emptyList()
        if (buckets.size >= SPLITS && n >= SPLITS) {
            val pn = IntArray(SPLITS); val prs = DoubleArray(SPLITS); val ph = IntArray(SPLITS)
            var before = 0L
            for (b in buckets) {
                val part = minOf(SPLITS - 1, ((before * 2 + b.n) * SPLITS / (2L * n)).toInt())
                pn[part] += b.n; prs[part] += b.rs; ph[part] += b.hits
                before += b.n
            }
            if (pn.all { it > 0 }) splits = (0 until SPLITS).map { signOf(pn[it], prs[it], ph[it]) }
        }
        return Evidence(n, rs, hits, n, signOf(n, rs, hits), splits)
    }

    /** Strong: [STRONG_N] or more and the same sign in at least [AGREE] of [SPLITS] splits; building: [BUILDING_N]; else weak. */
    fun confidence(e: Evidence): Confidence = when {
        e.n >= STRONG_N && e.sign != 0 && e.splits.size == SPLITS && e.agreeing >= AGREE -> Confidence.STRONG
        e.n >= BUILDING_N -> Confidence.BUILDING
        else -> Confidence.WEAK
    }

    fun evidence(l: Lesson): Evidence = l.ev

    /**
     * The most a lesson of [topic] may be (13 Oct review): a market-study lesson ([Topic.STUDY]) is at most building. Its nightly
     * counts re-read the same two years of candles, so night after night agreeing is not independent evidence - it never becomes
     * strong, so never a passed test in the shared knowledge, never a proposal, never sent to research as a confirmed finding.
     */
    fun ceiling(topic: Topic, c: Confidence): Confidence =
        if ((topic == Topic.STUDY || topic == Topic.CANDLES) && c == Confidence.STRONG) Confidence.BUILDING else c

    /** [confidence], with strong also needing the night's q-value [q] at or under [BH_Q] (null: not tested yet - not strong). */
    fun confidence(e: Evidence, q: Double?): Confidence {
        val c = confidence(e)
        return if (c == Confidence.STRONG && (q == null || q > BH_Q + 1e-12)) Confidence.BUILDING else c
    }

    // ---- honest statistics --------------------------------------------------------------------------------------------------

    /**
     * A lesson's p-value: rupees - an ALWAYS-VALID test of its per-day sums against zero ([LearnStats.signFlipAlwaysValidP]:
     * the lesson is re-tested every night as its days come in, and a fixed-sample p looked at nightly is "significant" on
     * some night far more often than it says - R3 finding 8); a rate - an exact binomial test of its hits against its
     * threshold ([HEALTH_RATE], [MOVES_RATE], else a half); a daily review - the chance of so many of the EARLIER reviews
     * agreeing with the latest by luck ([LearnStats.binomUpper] at a half; the latest itself is not evidence for itself -
     * R3 finding 9).
     */
    fun pValue(kind: Kind, id: String, buckets: List<Bucket>): Double {
        if (buckets.isEmpty()) return 1.0
        return when (kind) {
            Kind.RUPEES -> LearnStats.signFlipAlwaysValidP(DoubleArray(buckets.size) { buckets[it].rs })
            Kind.RATE -> {
                var n = 0; var h = 0
                for (b in buckets) { n += b.n; h += b.hits }
                val p0 = when (prefix(id)) { "health" -> HEALTH_RATE; "moves" -> MOVES_RATE; "order" -> ORDER_RATE; "exec" -> EXEC_RATE; else -> 0.5 }
                LearnStats.binomTwoSided(h, n, p0)
            }
            Kind.SNAPSHOT -> {
                val last = buckets.last().hits > 0
                LearnStats.binomUpper(buckets.count { (it.hits > 0) == last } - 1, buckets.size - 1, 0.5)
            }
        }
    }

    /** [l]'s p-value: kept from the night's pass, else worked out from its days. */
    fun pValue(l: Lesson): Double = l.p ?: pValue(l.kind, l.id, l.buckets)

    /** A candidate condition and its split's p (side [inside] against the rest, each side's mean per case). */
    private class Split(val cond: String, val p: Double, val inside: Double, val outside: Double)

    private fun side(bs: List<Bucket>): LearnStats.Side = LearnStats.Side(IntArray(bs.size) { bs[it].n }, DoubleArray(bs.size) { bs[it].rs })

    /** The weekly expiry flag of a day (Nifty's weekly options expire on Tuesdays; a holiday's shift is not known here). */
    fun expiryDay(d: LocalDate): Boolean = d.dayOfWeek == java.time.DayOfWeek.TUESDAY

    /**
     * The splits worth testing for a rupee lesson [l]: by the market brain's regime of each day ([regimes]), by the expiry-day
     * flag, and for a strategy by its parts of the day (its own `tod:` lessons in [byId], set against the rest of its days).
     * Only single days count (a folded run of old days has no one regime); each side needs [COND_N] cases.
     */
    private val SPLIT_PREFIXES = setOf("strat", "mgr", "spec", "flow", "trap", "bots")
    private val REGIMES = listOf("busy", "quiet", "normal")

    private fun splits(l: Lesson, regimes: Map<LocalDate, String>, byId: Map<String, Lesson>): List<Split> {
        if (l.kind != Kind.RUPEES || prefix(l.id) !in SPLIT_PREFIXES || evidence(l).n < 2 * COND_N) return emptyList()
        val days = l.buckets.filter { it.from == it.day }
        val m = days.size
        val out = ArrayList<Split>(6)
        fun add(cond: String, a: List<Bucket>, b: List<Bucket>) {
            val sa = side(a); val sb = side(b)
            if (sa.total < COND_N || sb.total < COND_N) return
            out += Split(cond, LearnStats.twoSampleP(sa, sb), sa.mean, sb.mean)
        }
        // Each day's tag read once; the sides filled straight from the days (a year of lessons a night).
        fun addBy(cond: String, inside: (Int) -> Boolean, known: (Int) -> Boolean) {
            var ni = 0; var no = 0
            for (i in 0 until m) if (known(i)) { if (inside(i)) ni += days[i].n else no += days[i].n }
            if (ni < COND_N || no < COND_N) return
            val a = ArrayList<Bucket>(); val b = ArrayList<Bucket>()
            for (i in 0 until m) if (known(i)) { if (inside(i)) a += days[i] else b += days[i] }
            add(cond, a, b)
        }
        if (regimes.isNotEmpty()) {
            val reg = arrayOfNulls<String>(m)
            for (i in 0 until m) reg[i] = regimes[days[i].day]
            for (r in REGIMES) addBy("regime:$r", { reg[it] == r }, { reg[it] != null })
        }
        val exp = BooleanArray(m) { expiryDay(days[it].day) }
        addBy("expiry:yes", { exp[it] }, { true })
        if (prefix(l.id) == "strat") for (s in SLOTS) {
            val child = byId[l.id.replaceFirst("strat:", "tod:") + ":" + s] ?: continue
            val inDay = child.buckets.filter { it.from == it.day }.associateBy { it.day }
            val rest = days.mapNotNull { b ->
                val c = inDay[b.day]
                if (c == null) b else (b.n - c.n).takeIf { it > 0 }?.let { b.copy(n = it, rs = b.rs - c.rs, hits = maxOf(0, b.hits - c.hits)) }
            }
            add("tod:$s", inDay.values.sortedBy { it.day }, rest)
        }
        return out
    }

    /**
     * Tonight's conditions: every candidate split of every active rupee lesson in one Benjamini-Hochberg family at [BH_Q];
     * a lesson takes the passing split with the smallest q whose inside points its way and whose outside does not (for the
     * expiry flag either side may be the inside: "on expiry days only" or "except on expiry days"). Id to (cond, q).
     */
    fun conditions(lessons: Collection<Lesson>, regimes: Map<LocalDate, String>): Map<String, Pair<String, Double>> {
        val byId = lessons.associateBy { it.id }
        val all = ArrayList<Pair<Lesson, Split>>()
        for (l in lessons) if (l.status != Status.RETIRED) for (sp in splits(l, regimes, byId)) all += l to sp
        if (all.isEmpty()) return emptyMap()
        val q = LearnStats.bh(DoubleArray(all.size) { all[it].second.p })
        val out = HashMap<String, Pair<String, Double>>()
        for ((i, x) in all.withIndex()) {
            val (l, sp) = x
            if (q[i] > BH_Q + 1e-12 || l.sign == 0) continue
            val si = sgn(sp.inside); val so = sgn(sp.outside)
            val cond = when {
                si == l.sign && so != l.sign -> sp.cond
                sp.cond == "expiry:yes" && so == l.sign && si != l.sign -> "expiry:no"
                else -> null
            } ?: continue
            val have = out[l.id]
            if (have == null || q[i] < have.second) out[l.id] = cond to q[i]
        }
        return out
    }

    /** "on busy days only", "on expiry days only", "except on expiry days", "when entered in the morning only". */
    fun condWords(cond: String?): String? {
        if (cond == null) return null
        val k = cond.substringBefore(':'); val v = cond.substringAfter(':', "")
        return when (k) {
            "regime" -> "on $v days only"
            "expiry" -> if (v == "yes") "on expiry days only" else "except on expiry days"
            "tod" -> "when entered ${slotWords(v)} only"
            else -> null
        }
    }

    /** "q 0.03" ("q 0.004" under 0.01; "q < 0.001" when tiny). */
    fun qWords(q: Double): String = if (q < 0.001) "q < 0.001" else "q " + fmtQ(q)

    /** A q or a bar in plain digits: "0.10", "0.004". */
    fun fmtQ(q: Double): String = String.format(Locale.ENGLISH, if (q < 0.01) "%.3f" else "%.2f", q)

    // ---- words --------------------------------------------------------------------------------------------------------

    fun rs(x: Double): String = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun pct(x: Double) = Math.round(x * 100).toString() + "%"
    fun day(d: LocalDate): String = "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)}"

    /** The lesson in one plain sentence (no numbers: [evidenceLine] carries them). */
    fun statement(l: Lesson): String {
        val pos = l.sign >= 0
        val verb = when (prefix(l.id)) {
            "strat", "tod", "regime" -> if (pos) "makes money" else "loses money"
            "flow", "trap", "bots", "pause" -> if (pos) "make money" else "lose money"
            "mgr" -> if (pos) "helps against the original rules" else "hurts against the original rules"
            "spec" -> if (pos) "helps its exits against the original rules" else "hurts its exits against the original rules"
            "drift" -> if (pos) "is on track with its backtest" else "is drifting from its backtest"
            "health" -> if (pos) "has been reliable" else "has had trouble often"
            "moves" -> if (pos) "are common" else "are rare"
            "exp" -> if (pos) "did better than the current setting" else "did worse than the current setting"
            "lab" -> if (pos) "mostly passed in the strategy lab" else "mostly failed in the strategy lab"
            "order" -> if (pos) "has become rare" else "keeps happening"
            "exec" -> if (pos) "stay near the research's baseline" else "are often off the research's baseline"
            "study" -> if (pos) "held in both years of my study" else "was a coin toss in my study"
            "candle" -> if (pos) "behaves as the research says in the Candle agent's own count" else "differs from the research in the Candle agent's own count"
            else -> if (pos) "goes well" else "goes badly"
        }
        return "${l.name} $verb" + (condWords(l.cond)?.let { " $it" } ?: "")
    }

    /** The numbers behind it: "64 trades, +Rs 12,400, 55% won, 2 Sep-10 Oct, from the bot trades and the market brain". */
    fun evidenceLine(l: Lesson): String {
        val e = evidence(l)
        val span = "${day(l.firstSeen)}-${day(l.lastSeen)}"
        val from = "from " + l.agents.sorted().joinToString(", ")
        val p = prefix(l.id)
        val body = when (l.kind) {
            Kind.RUPEES -> {
                val won = e.hitRate?.let { (if (p == "mgr" || p == "spec") ", helped in " else ", ") + pct(it) + (if (p == "mgr" || p == "spec") "" else " won") } ?: ""
                "${e.n} ${l.topic.unit}, ${rs(e.rs)}$won"
            }
            Kind.RATE -> when (p) {
                "moves" -> "${e.hits} of ${e.n} big moves"
                "lab" -> "passed ${e.hits} of ${e.n} ${l.topic.unit}, ${rs(e.rs)}"
                "order" -> "on ${e.hits} of ${e.n} ${l.topic.unit}, ${rs(e.rs)}"
                "exec" -> "${e.hits} of ${e.n} fills off the baseline"
                else -> "trouble on ${e.hits} of ${e.n} days watched"
            }
            Kind.SNAPSHOT -> if (p == "study") "${e.n} days counted, held on ${e.of - e.hits} of ${e.of} nightly counts"
                else if (p == "candle") "${e.n} candles counted, agreed with the research on ${e.of - e.hits} of ${e.of} nightly counts"
                else "${e.n} trades judged, drifting on ${e.hits} of ${e.of} daily reviews, latest ${rs(e.rs)} a trade"
        }
        return "$body, $span, $from" + (l.q?.let { ", " + qWords(it) } ?: "") +
            (if (l.cond != null) l.condQ?.let { " (the split: " + qWords(it) + ")" } ?: "" else "")
    }

    /** "Statement (evidence; strong)". */
    fun said(l: Lesson): String = "${statement(l)} (${evidenceLine(l)}; ${trustWord(l)})"

    /**
     * Whether a strategy lesson says the opposite of the research's tested verdict (12 Oct: "ORB makes money (36 trades,
     * +Rs 3,428)" the same day the trade check said "Tested: ORB lost in both years" and research R14 found every ORB trade has
     * about zero edge before charges). Only the optimistic way: a lesson that a strategy MAKES money while the research found
     * it lost in both tested years (a few weeks of paper fills cannot overturn years of real-cost tests). A lesson that a
     * researched winner loses is drift - the self-review's to judge - and stays as it is. The reason, or null.
     */
    fun contradiction(l: Lesson): String? {
        if (prefix(l.id) != "strat" || l.sign <= 0) return null
        val (y1, y2) = TradeCheck.RECORD[l.name.trim()] ?: return null
        if (y1 >= 0 || y2 >= 0) return null
        return "contradicts the research: ${l.name} lost in both tested years (${rs(y1)}, ${rs(y2)} a lot, real costs)"
    }

    /** The confidence as said: "contradicts research - not trusted" for a [contradiction], else its word. */
    fun trustWord(l: Lesson): String = if (contradiction(l) != null) "contradicts research - not trusted" else l.confidence.word

    // ---- what the agents kept, as samples --------------------------------------------------------------------------------

    /** How far back a pass reads a source's days (older days a lesson already holds stay in it). */
    private const val SPAN_DAYS = 760

    /**
     * One lesson's evidence as the agents' records give it, per day: plain arrays indexed back from [end] (the pass's day
     * and two after it, a clock running ahead), filled without a map or a boxed number per trade (a year of data in a pass).
     */
    private class Acc(val id: String, val topic: Topic, val kind: Kind, val name: String, val tags: List<String>, agent: String, private val end: Long) {
        val agents = HashSet<String>().apply { add(agent) }
        private var n: IntArray? = null
        private var rs: DoubleArray? = null
        private var hits: IntArray? = null
        fun add(epochDay: Long, n1: Int, rs1: Double, hit: Int) {
            val k = end - epochDay
            if (k < 0 || k >= SPAN_DAYS) return
            val a = n ?: IntArray(SPAN_DAYS).also { n = it; rs = DoubleArray(SPAN_DAYS); hits = IntArray(SPAN_DAYS) }
            val i = k.toInt()
            a[i] += n1; rs!![i] += rs1; hits!![i] += hit
        }
        fun buckets(): List<Bucket> {
            val a = n ?: return emptyList()
            val out = ArrayList<Bucket>()
            for (i in SPAN_DAYS - 1 downTo 0) if (a[i] > 0) out += Bucket(LocalDate.ofEpochDay(end - i), a[i], rs!![i], hits!![i])
            return out
        }
    }

    private const val A_TRADES = "the bot trades"
    private const val A_BRAIN = "the market brain"
    private const val A_REVIEW = "the self-review"
    private const val A_MANAGER = "the trade manager"
    private const val A_SPECS = "the specialists"
    private const val A_FLOW = "the order flow"
    private const val A_TRAPS = "the trap guard"
    private const val A_FINDINGS = "the findings bus"
    private const val A_HEAL = "self-healing"
    private const val A_MOVES = "the move recorder"
    private const val A_LAB = "the strategy lab"
    private const val A_ORDERS = "the order review"
    private const val A_STUDY = "the market study"
    private const val A_CANDLES = "the Candle agent"

    private val SKIP_OWNERS = setOf("", "manual", "protection")
    private val SLOTS = listOf("morning", "midday", "afternoon", "evening")

    private fun slotIndex(minute: Int): Int = when {
        minute < 10 * 60 + 30 -> 0
        minute < 13 * 60 + 30 -> 1
        minute < AFTERNOON_END -> 2
        else -> 3
    }

    private fun words(s: String): List<String> = spacedWords(s.lowercase(Locale.ENGLISH)).split(' ').filter { it.length > 1 }

    /** A stable id: lower case, spaces and odd characters folded (two spellings of one key are one lesson). */
    fun norm(id: String): String = SPACES.replace(id.lowercase(Locale.ENGLISH).trim(), "_").replace('|', '/').replace(',', ';')

    private val SPACES = Regex("\\s+")

    private fun epochDayOf(ms: Long): Long = Math.floorDiv(ms + IST_MS, DAY_MS)

    /** One bot's lessons, made once a pass and found again by its name. */
    private class OwnerAccs(val owner: String, val words: List<String>, val strat: Acc) {
        val tod = arrayOfNulls<Acc>(4)
        val regime = HashMap<String, Acc>()
    }

    private fun samples(i: Inputs, regimes: Map<LocalDate, String>): Collection<Acc> {
        val m = HashMap<String, Acc>()
        val end = i.day.toEpochDay() + 2
        fun acc(id: String, topic: Topic, kind: Kind, name: String, tags: List<String>, agent: String): Acc {
            val n = norm(id)
            return m.getOrPut(n) { Acc(n, topic, kind, name, tags, agent, end) }.also { it.agents += agent }
        }
        // The bots' closed trades: each bot, by part of the day, by the day's regime.
        val owners = HashMap<String, OwnerAccs?>()
        for (t in i.trips) {
            val oa = if (owners.containsKey(t.owner)) owners[t.owner] else {
                val owner = t.owner.trim()
                val made = if (owner.lowercase(Locale.ENGLISH) in SKIP_OWNERS) null else {
                    val ow = words(owner)
                    OwnerAccs(owner, ow, acc("strat:$owner", Topic.STRATEGY, Kind.RUPEES, owner, ow + "strategy", A_TRADES))
                }
                owners[t.owner] = made
                made
            } ?: continue
            val d = t.closedAt.toLocalDate()
            val ed = d.toEpochDay()
            // After ALL costs (12 Oct): what the book did not book (the paper spread before 08 Oct) is taken off too.
            val net = t.net - t.unbooked.coerceAtLeast(0.0)
            val win = if (net > 0) 1 else 0
            oa.strat.add(ed, 1, net, win)
            val si = slotIndex(t.openedAt.hour * 60 + t.openedAt.minute)
            val tod = oa.tod[si] ?: SLOTS[si].let { s ->
                acc("tod:${oa.owner}:$s", Topic.TIME_OF_DAY, Kind.RUPEES, "${oa.owner} entered ${slotWords(s)}", oa.words + s, A_TRADES).also { oa.tod[si] = it }
            }
            tod.add(ed, 1, net, win)
            val r = regimes[d]
            if (r != null) {
                val ra = oa.regime[r] ?: acc("regime:${oa.owner}:$r", Topic.REGIME, Kind.RUPEES, "${oa.owner} on $r days", oa.words + r + "regime", A_TRADES)
                    .also { it.agents += A_BRAIN; oa.regime[r] = it }
                ra.add(ed, 1, net, win)
            }
        }
        // The trade manager against the original rules, and each specialist's part in it.
        val mgr = HashMap<String, Acc>()
        for (r in i.managed) {
            val vs = r.vsOriginal ?: continue
            val at = r.actual?.atMs ?: continue
            if (abs(vs) < 1e-9) continue
            val f = r.trade.family
            val a = mgr[f] ?: acc("mgr:$f", Topic.MANAGER, Kind.RUPEES, "The trade manager on ${TradeManager.familyName(f)}",
                words(TradeManager.familyName(f)) + listOf(f, "manager"), A_MANAGER).also { mgr[f] = it }
            a.add(epochDayOf(at), 1, vs, if (vs > 0) 1 else 0)
        }
        val specs = HashMap<String, HashMap<String, Acc>>()
        for (o in SpecialistBook.outcomes(i.managed)) {
            val byId = specs.getOrPut(o.family) { HashMap() }
            val a = byId[o.specialist] ?: ManagerTeam.nameOf(o.specialist).let { name ->
                acc("spec:${o.family}:${o.specialist}", Topic.SPECIALIST, Kind.RUPEES, "The $name specialist on ${TradeManager.familyName(o.family)}",
                    words(TradeManager.familyName(o.family)) + listOf(o.family, o.specialist, "specialist") + words(name), A_SPECS).also { byId[o.specialist] = it }
            }
            a.add(epochDayOf(o.atMs), 1, o.rs, if (o.rs > 0) 1 else 0)
        }
        // The order flow's shadow log: trades with and against the flow, with a trap flag up, against the bots' consensus,
        // and those a brain pause would have skipped - each by its closed paper outcome.
        val flowAccs = HashMap<String, Array<Acc>>()
        val traps = HashMap<String, Acc?>()
        val pauses = HashMap<String, Acc?>()
        var botsWith: Acc? = null
        var botsAgainst: Acc? = null
        for (s in i.flow) {
            val net = s.net ?: continue
            if (s.live) continue
            val ed = Math.floorDiv(s.epochSec * 1000 + IST_MS, DAY_MS)
            val win = if (net > 0) 1 else 0
            if (s.agreement != OrderFlow.Agreement.UNKNOWN) {
                val pair = flowAccs[s.key] ?: run {
                    val label = FlowShadow.strategy(s.key)?.label ?: s.key
                    val kt = words(label) + s.key.substringBefore(':')
                    arrayOf(acc("flow:${s.key}:with", Topic.ORDER_FLOW, Kind.RUPEES, "$label trades taken with the order flow", kt + listOf("flow", "with"), A_FLOW),
                        acc("flow:${s.key}:against", Topic.ORDER_FLOW, Kind.RUPEES, "$label trades taken against the order flow", kt + listOf("flow", "against"), A_FLOW))
                        .also { flowAccs[s.key] = it }
                }
                pair[if (s.agreement == OrderFlow.Agreement.AGREES) 0 else 1].add(ed, 1, net, win)
            }
            for (fl in s.flags) {
                val a = if (traps.containsKey(fl)) traps[fl] else {
                    val w = TrapGuard.Trap.entries.firstOrNull { it.name == fl }?.words
                    val made = w?.let { acc("trap:$fl", Topic.ORDER_FLOW, Kind.RUPEES, "Trades taken with the trap guard's \"$it\" flag up",
                        listOf("trap", "traps", fl.lowercase(Locale.ENGLISH)), A_TRAPS) }
                    traps[fl] = made
                    made
                }
                a?.add(ed, 1, net, win)
            }
            val c = s.consensus
            if (c != null) {
                val (bull, bear) = c
                val against = (s.side > 0 && bear > bull) || (s.side < 0 && bull > bear)
                val with = (s.side > 0 && bull > bear) || (s.side < 0 && bear > bull)
                if (against) (botsAgainst ?: acc("bots:against", Topic.ORDER_FLOW, Kind.RUPEES, "Trades taken against the bots' consensus",
                    listOf("bots", "consensus", "findings"), A_FINDINGS).also { botsAgainst = it }).add(ed, 1, net, win)
                if (with) (botsWith ?: acc("bots:with", Topic.ORDER_FLOW, Kind.RUPEES, "Trades taken with the bots' consensus",
                    listOf("bots", "consensus", "findings"), A_FINDINGS).also { botsWith = it }).add(ed, 1, net, win)
            }
            for (cause in s.wouldPause) {
                val a = if (pauses.containsKey(cause)) pauses[cause] else {
                    val w = MarketBrain.Cause.entries.firstOrNull { it.key == cause }?.words
                    val made = w?.let { acc("pause:$cause", Topic.REGIME, Kind.RUPEES, "Entries during $it", listOf("pause", cause, "regime") + words(it), A_BRAIN) }
                    pauses[cause] = made
                    made
                }
                a?.add(ed, 1, net, win)
            }
        }
        // Data health: every day the findings bus kept (or today), and the days with data trouble on it or from self-healing.
        val watched = HashSet<Long>()
        val trouble = HashSet<Long>()
        var lastDay = Long.MIN_VALUE
        for (f in i.findings) {
            val d = epochDayOf(f.atMs)
            if (d != lastDay) { watched += d; lastDay = d }
            if (f.kind == Findings.Kind.DATA_UNRELIABLE || (f.kind == Findings.Kind.DATA_HEALTH && f.who == "self-healing")) trouble += d
        }
        val byDep = HashMap<SelfHeal.Dep, HashSet<Long>>()
        for (x in i.incidents) {
            val d = epochDayOf(x.startedAt)
            watched += d; trouble += d
            byDep.getOrPut(x.dep) { HashSet() } += d
        }
        if (watched.isNotEmpty()) {
            watched += i.day.toEpochDay()
            val a = acc("health:data", Topic.DATA_HEALTH, Kind.RATE, "The market data", listOf("data", "feed", "feeds", "health", "stream"),
                if (i.findings.isNotEmpty()) A_FINDINGS else A_HEAL)
            if (i.incidents.isNotEmpty()) a.agents += A_HEAL
            for (d in watched) a.add(d, 1, 0.0, if (d in trouble) 1 else 0)
            for ((dep, days) in byDep) {
                val b = acc("health:${dep.name}", Topic.DATA_HEALTH, Kind.RATE, dep.words.replaceFirstChar { it.uppercase() }, listOf("data", "health") + words(dep.words), A_HEAL)
                for (d in watched) b.add(d, 1, 0.0, if (d in days) 1 else 0)
            }
        }
        // The move recorder's big moves, by part of the day.
        val moves = i.moves.filter { it.kind != MoveEvents.Kind.CONTROL }
        if (moves.isNotEmpty()) {
            for ((si, s) in SLOTS.take(3).withIndex()) {
                val a = acc("moves:$s", Topic.TIME_OF_DAY, Kind.RATE, "Big moves ${slotWords(s)}", listOf("moves", "big", s), A_MOVES)
                for (mv in moves) {
                    val hm = mv.hhmm.toIntOrNull() ?: continue
                    a.add(mv.day.toEpochDay(), 1, 0.0, if (slotIndex(hm / 100 * 60 + hm % 100) == si) 1 else 0)
                }
            }
        }
        // The strategy lab: each block, filter or parameter direction as a rate of tests passed, per day.
        for (r in i.lab) {
            if (r.n <= 0 || r.key.isBlank()) continue
            acc("lab:${r.key}", Topic.LAB, Kind.RATE, r.name, (r.tags + listOf("lab", "forge")).distinct(), A_LAB)
                .add(r.day.toEpochDay(), r.n, r.rs, if (r.passed) r.n else 0)
        }
        // The order review: each RECURRING flaw as a rate of the strategy's reviewed orders, per day (one-offs never come here).
        for (r in i.orders) {
            if (r.n <= 0 || r.key.isBlank()) continue
            acc("order:${r.key}", Topic.ORDERS, Kind.RATE, r.name, (r.tags + listOf("orders", "flaws")).distinct(), A_ORDERS)
                .add(r.day.toEpochDay(), r.n, r.rs, r.hits.coerceIn(0, r.n))
        }
        // Execution against the research's baseline (13 Oct): each strategy's fills on an index as a rate off the baseline, per day.
        for (r in i.exec) {
            if (r.n <= 0 || r.key.isBlank()) continue
            acc("exec:${r.key}", Topic.EXECUTION, Kind.RATE, r.name, (r.tags + listOf("execution", "costs")).distinct(), r.agent)
                .add(r.day.toEpochDay(), r.n, 0.0, r.hits.coerceIn(0, r.n))
        }
        // The market study (13 Oct): each setup's latest count, held in both years or not (a daily review, like the self-review's).
        for (r in i.study) {
            if (r.days <= 0 || r.key.isBlank()) continue
            acc("study:${r.key}", Topic.STUDY, Kind.SNAPSHOT, r.name, (r.tags + listOf("study")).distinct(), A_STUDY)
                .add(r.day.toEpochDay(), r.days, 0.0, if (r.held) 0 else 1)
        }
        // The Candle agent (20 Oct): each shape's own count against the research, the latest of a daily review.
        for (r in i.candles) {
            if (r.n <= 0 || r.key.isBlank()) continue
            acc("candle:${r.key}", Topic.CANDLES, Kind.SNAPSHOT, r.name, (r.tags + listOf("candles", "candle")).distinct(), A_CANDLES)
                .add(r.day.toEpochDay(), r.n, 0.0, if (r.agrees) 0 else 1)
        }
        // The self-review: each strategy's health against its backtest, one reading a day.
        for (r in i.drift) {
            if (r.verdict == DriftReview.Verdict.TOO_FEW) continue
            val mean = r.windows.lastOrNull()?.mean ?: 0.0
            acc("drift:${r.key}:${r.venue}", Topic.STRATEGY, Kind.SNAPSHOT, "${r.name} (${r.venue})", words(r.name) + listOf(r.key, "health", "backtest"), A_REVIEW)
                .add(i.day.toEpochDay(), r.trades, mean, if (r.verdict == DriftReview.Verdict.DRIFTING) 1 else 0)
        }
        return m.values
    }

    // ---- the pass -----------------------------------------------------------------------------------------------------------

    /** [old]'s days with [fresh]'s (a day seen again is replaced), oldest first, at most [MAX_BUCKETS] (the oldest folded together). */
    fun mergeBuckets(old: List<Bucket>, fresh: List<Bucket>): List<Bucket> {
        if (old.isEmpty()) return cap(fresh)
        if (fresh.isEmpty()) return cap(old)
        // Kept as they were: the old days before the fresh ones, and a folded bucket reaching into them (it already holds
        // those days, so the fresh ones it covers are not added again). A folded bucket wholly inside the fresh days is read
        // again from them; single old days inside them stay unless read again.
        val first = fresh.first().day
        val out = ArrayList<Bucket>(old.size + fresh.size)
        val within = ArrayList<Bucket>()
        var cut: LocalDate? = null
        for (b in old) when {
            b.day < first -> out += b
            b.from < first -> { out += b; cut = b.day }
            b.from == b.day -> within += b
        }
        val c = cut
        val fr = if (c == null) fresh else fresh.filter { it.day > c }
        // Both oldest first: one walk through the two (a day read again replaces the old one).
        var i = 0; var j = 0
        while (i < within.size || j < fr.size) {
            val a = within.getOrNull(i); val b = fr.getOrNull(j)
            when {
                b == null -> { out += a!!; i++ }
                a == null -> { out += b; j++ }
                a.day < b.day -> { out += a; i++ }
                a.day > b.day -> { out += b; j++ }
                else -> { out += b; i++; j++ }
            }
        }
        return cap(out)
    }

    private fun cap(bs: List<Bucket>): List<Bucket> {
        if (bs.size <= MAX_BUCKETS) return bs
        val fold = bs.size - MAX_BUCKETS + 1
        val head = bs.take(fold)
        return listOf(Bucket(head.last().day, head.sumOf { it.n }, head.sumOf { it.rs }, head.sumOf { it.hits }, head.first().from)) + bs.drop(fold)
    }

    /** One lesson brought up to date with [fresh] days (null: none new); what changed in it. */
    internal fun update(old: Lesson?, id: String, topic: Topic, kind: Kind, name: String, tags: List<String>, agents: Set<String>,
                        fresh: List<Bucket>, day: LocalDate, p: Double? = null, q: Double? = null,
                        merged: List<Bucket>? = null): Pair<Lesson, List<ChangeKind>> {
        val buckets = merged ?: mergeBuckets(old?.buckets.orEmpty(), fresh)
        val e = evidence(kind, id, buckets)
        val conf = ceiling(topic, confidence(e, q))
        val first = buckets.first().from; val last = buckets.last().day
        if (old == null) {
            val st = if (conf == Confidence.STRONG) Status.CONFIRMED else Status.NEW
            return Lesson(id, topic, kind, name, tags.distinct(), agents, buckets, e.sign, st, conf, first, last, day, e.n, p = p, q = q) to listOf(ChangeKind.NEW)
        }
        val base = old.copy(topic = topic, kind = kind, name = name, tags = (old.tags + tags).distinct().take(16), agents = old.agents + agents,
            buckets = buckets, firstSeen = minOf(old.firstSeen, first), lastSeen = maxOf(old.lastSeen, last), p = p, q = q)
        // Nothing new since it was last judged (the same count: a day read again, or the file's rounding): kept as it was.
        if (e.n == old.checkedN) return base.copy(confidence = conf) to emptyList()
        // A sign flip counts against a lesson only once it had some weight (G6-2), as [against] does: a weak lesson's early
        // noise (a sum that crosses zero twice in its first days) never retires it.
        val flipped = old.sign != 0 && e.sign != 0 && e.sign != old.sign && old.confidence != Confidence.WEAK
        val against = old.sign != 0 && e.recent == -old.sign && old.confidence != Confidence.WEAK
        val changes = ArrayList<ChangeKind>()
        var status = old.status
        when {
            flipped || against -> {
                status = if (old.status == Status.WEAKENED || old.status == Status.RETIRED) Status.RETIRED else Status.WEAKENED
            }
            conf == Confidence.STRONG -> status = Status.CONFIRMED
        }
        if (status != old.status) changes += when (status) {
            Status.CONFIRMED -> ChangeKind.CONFIRMED; Status.WEAKENED -> ChangeKind.WEAKENED; Status.RETIRED -> ChangeKind.RETIRED; Status.NEW -> ChangeKind.NEW
        } else if (conf.ordinal > old.confidence.ordinal) changes += ChangeKind.STRONGER
        return base.copy(sign = if (e.sign != 0) e.sign else old.sign, status = status, confidence = conf, checkedN = e.n,
            statusOn = if (status != old.status) day else old.statusOn, flips = old.flips + if (flipped) 1 else 0) to changes
    }

    /** Lessons sharing one id (after [norm]) merged into one: their days, agents and tags together, Boss's answers kept. */
    fun merge(lessons: List<Lesson>): List<Lesson> {
        val out = LinkedHashMap<String, Lesson>()
        for (l0 in lessons) {
            val l = l0.copy(id = norm(l0.id))
            val o = out[l.id]
            if (o == null) { out[l.id] = l; continue }
            val m = java.util.TreeMap<LocalDate, Bucket>()
            for (b in o.buckets + l.buckets) { val x = m[b.day]; if (x == null || b.n > x.n) m[b.day] = b }
            val bs = cap(m.values.toList())
            val e = evidence(o.kind, o.id, bs)
            out[l.id] = o.copy(buckets = bs, agents = o.agents + l.agents, tags = (o.tags + l.tags).distinct().take(16),
                firstSeen = minOf(o.firstSeen, l.firstSeen), lastSeen = maxOf(o.lastSeen, l.lastSeen),
                confidence = ceiling(o.topic, confidence(e, o.q ?: l.q)), sign = if (e.sign != 0) e.sign else o.sign, weight = maxOf(o.weight, l.weight),
                rejections = o.rejections + l.rejections, cooldownUntil = listOfNotNull(o.cooldownUntil, l.cooldownUntil).maxOrNull(),
                proposedOn = listOfNotNull(o.proposedOn, l.proposedOn).maxOrNull(), accepted = o.accepted || l.accepted, auto = o.auto ?: l.auto,
                status = if (o.status.ordinal >= l.status.ordinal) o.status else l.status)
        }
        return out.values.toList()
    }

    /** The order lessons are dropped in at a cap: retired first, then weak, building, the rest; within each the oldest last-seen. */
    private val DROP_ORDER = compareBy<Lesson>({ if (it.status == Status.RETIRED) 0 else 1 + it.confidence.ordinal }, { it.lastSeen }, { it.id })

    /** At most [max] lessons and [bytes] encoded: the first in [DROP_ORDER] dropped first. */
    fun capped(book: Book, max: Int = MAX_LESSONS, bytes: Int = MAX_BYTES): Book {
        var ls = book.lessons
        if (ls.size > max) { val drop = ls.sortedWith(DROP_ORDER).take(ls.size - max).map { it.id }.toSet(); ls = ls.filter { it.id !in drop } }
        // The file's size, from an upper bound per lesson (no encoding at each pass): the first in the order dropped until it fits.
        val head = 64 + book.regimes.size * 24 + book.timeline.sumOf { 32 + it.key.length + it.text.length } + book.today.sumOf { 16 + it.id.length } +
            (book.line?.length ?: 0)
        var total = head + ls.sumOf { bound(it) }
        if (total > bytes) {
            val drop = HashSet<String>()
            for (l in ls.sortedWith(DROP_ORDER)) { if (total <= bytes) break; drop += l.id; total -= bound(l) }
            ls = ls.filter { it.id !in drop }
        }
        return book.copy(lessons = ls)
    }

    /** At most this many characters a lesson takes in the file (its line, and [BUCKET_CHARS] a day). */
    private fun bound(l: Lesson): Int = 160 + 2 * l.id.length + l.name.length + l.tags.sumOf { it.length + 1 } + l.agents.sumOf { it.length + 1 } +
        l.buckets.size * BUCKET_CHARS + (l.auto?.length ?: 0)
    private const val BUCKET_CHARS = 48

    /**
     * The day's learning: [inputs] digested into [book]'s lessons (strengthened, weakened, retired, new), the growth timeline
     * moved on, the proposals the strong lessons imply (at most [MAX_PROPOSALS], only [safe] ones), the evening's line and the
     * finding's words.
     */
    fun pass(book: Book, inputs: Inputs): Pass {
        val day = inputs.day
        val regimes = HashMap(book.regimes)
        inputs.regimeToday?.lowercase(Locale.ENGLISH)?.takeIf { it in setOf("busy", "quiet", "normal") }?.let { regimes[day] = it }
        val keptRegimes = if (regimes.size > REGIME_DAYS) regimes.entries.sortedByDescending { it.key }.take(REGIME_DAYS).associate { it.key to it.value } else regimes
        val byId = merge(book.lessons).associateBy { it.id }.toMutableMap()
        val changes = ArrayList<Change>()
        val accs = samples(inputs, keptRegimes).sortedBy { it.id }
        // Each lesson's days brought up to date first, so that tonight's p-values are adjusted together (Benjamini-Hochberg
        // across every lesson still active) before any lesson is judged strong.
        val merged = HashMap<String, List<Bucket>>(accs.size * 2)
        for (a in accs) merged[a.id] = mergeBuckets(byId[a.id]?.buckets.orEmpty(), a.buckets())
        val kinds = HashMap<String, Kind>()
        for (a in accs) kinds[a.id] = a.kind
        val family = (byId.keys + merged.keys).filter { byId[it]?.status != Status.RETIRED }.sorted()
        val ps = DoubleArray(family.size) { k ->
            val id = family[k]
            val bs = merged[id] ?: byId[id]?.buckets.orEmpty()
            pValue(kinds[id] ?: byId[id]?.kind ?: Kind.RUPEES, id, bs)
        }
        // Two Benjamini-Hochberg families (R3 finding 5): the strategy lab's "lab:" lessons in their own. Nearly every forge
        // idea fails, so against a half its blocks are all overwhelmingly "mostly failed" - in the agents' family those
        // hundreds of easy discoveries would raise everyone's BH cut and let marginal trading lessons through.
        val qs = DoubleArray(family.size)
        for (lab in listOf(true, false)) {
            val idx = family.indices.filter { (prefix(family[it]) == "lab") == lab }
            val q = LearnStats.bh(DoubleArray(idx.size) { ps[idx[it]] })
            for ((j, k) in idx.withIndex()) qs[k] = q[j]
        }
        val pOf = HashMap<String, Double>(family.size * 2); val qOf = HashMap<String, Double>(family.size * 2)
        for ((k, id) in family.withIndex()) { pOf[id] = ps[k]; qOf[id] = qs[k] }
        // A retired lesson new evidence reached (G6-2) stays out of the night's family (the BH cut is the active lessons'),
        // but gets a conservative q of its own - Bonferroni over its family and itself - so strong new evidence can confirm
        // it again. Without a q it could never be strong, so a retired lesson never came back.
        for ((id, bs) in merged) {
            val old = byId[id] ?: continue
            if (old.status != Status.RETIRED || bs.isEmpty()) continue
            val lab = prefix(id) == "lab"
            val m = family.count { (prefix(it) == "lab") == lab } + 1
            val p = pValue(kinds[id] ?: old.kind, id, bs)
            pOf[id] = p; qOf[id] = minOf(1.0, p * m)
        }
        for (a in accs) {
            if (merged[a.id].isNullOrEmpty()) continue
            val (l, ch) = update(byId[a.id], a.id, a.topic, a.kind, a.name, a.tags, a.agents, emptyList(), day, pOf[a.id], qOf[a.id], merged[a.id])
            byId[a.id] = l
            ch.forEach { changes += Change(a.id, it) }
        }
        // The lessons nothing new reached: their q moves with the night's family (strong only while it passes).
        for ((id, l) in byId.entries.toList()) {
            if (id in merged) continue
            if (l.status == Status.RETIRED) { if (l.q != null) byId[id] = l.copy(q = null); continue }
            val q = qOf[id]
            val conf = ceiling(l.topic, confidence(evidence(l), q))
            if (q != l.q || conf != l.confidence || pOf[id] != l.p) byId[id] = l.copy(p = pOf[id], q = q, confidence = conf)
        }
        // Regime-aware: a lesson whose effect holds only under one condition says so ([conditions]).
        val conds = conditions(byId.values, keptRegimes)
        for ((id, l) in byId.entries.toList()) {
            val c = conds[id]
            if (c?.first != l.cond || c?.second != l.condQ) byId[id] = l.copy(cond = c?.first, condQ = c?.second)
        }
        // 12 Oct: a lesson against the research's tested verdict is never more than weak (never proposed, never acted on).
        for ((id, l) in byId.entries.toList()) if (l.confidence != Confidence.WEAK && contradiction(l) != null) byId[id] = l.copy(confidence = Confidence.WEAK)
        var next = capped(Book(byId.values.sortedBy { it.id }, keptRegimes, book.timeline, day, changes, book.line))
        val kept = next.lessons.map { it.id }.toSet()
        val todays = changes.filter { it.id in kept }
        val proposals = proposals(next.lessons, inputs)
        val propIds = proposals.map { it.lessonId }.toSet()
        next = next.copy(lessons = next.lessons.map { if (it.id in propIds) it.copy(proposedOn = day) else it }, today = todays)
        next = next.copy(timeline = milestones(next, inputs))
        val line = line(next, todays)
        val finding = findingWords(next, todays)
        return Pass(next.copy(line = line), todays, proposals, line, finding)
    }

    /** "Learned" today: what became strong, confirmed, weakened or retired, and the new lessons with some weight behind them. */
    fun learnedCount(book: Book, changes: List<Change>): Int {
        val by = book.lessons.associateBy { it.id }
        return changes.count { c -> c.kind != ChangeKind.NEW || (by[c.id]?.confidence ?: Confidence.WEAK) != Confidence.WEAK }
    }

    private fun findingWords(book: Book, changes: List<Change>): String {
        val n = learnedCount(book, changes)
        return if (n == 0) "Jarvis learned nothing new today (${active(book).size} lessons hold)"
        else "Jarvis learned $n thing${if (n == 1) "" else "s"} today"
    }

    /** The evening's "What I learned today" line. */
    fun line(book: Book, changes: List<Change>): String {
        val by = book.lessons.associateBy { it.id }
        val counted = changes.filter { c -> c.kind != ChangeKind.NEW || (by[c.id]?.confidence ?: Confidence.WEAK) != Confidence.WEAK }
        val hunches = changes.count { c -> c.kind == ChangeKind.NEW && (by[c.id]?.confidence ?: Confidence.WEAK) == Confidence.WEAK }
        val held = active(book).size
        if (counted.isEmpty()) return "What I learned today: nothing new" + (if (hunches > 0) ", $hunches new hunch${if (hunches == 1) "" else "es"} to watch" else "") +
            " - $held lesson${if (held == 1) "" else "s"} hold."
        val parts = ChangeKind.entries.mapNotNull { k -> counted.count { it.kind == k }.takeIf { it > 0 }?.let { "$it ${k.word}" } }
        val top = counted.mapNotNull { by[it.id] }.sortedWith(compareByDescending<Lesson> { it.confidence.ordinal }.thenByDescending { evidence(it).n }).firstOrNull()
        return "What I learned today: " + parts.joinToString(", ") + (if (hunches > 0) "; $hunches new hunch${if (hunches == 1) "" else "es"} to watch" else "") +
            "." + (top?.let { " Top: ${said(it)}." } ?: "")
    }

    // ---- growth -------------------------------------------------------------------------------------------------------------

    fun active(book: Book): List<Lesson> = book.lessons.filter { it.status != Status.RETIRED }

    data class Skill(val topic: Topic, val level: Confidence?, val n: Int, val lessons: Int) {
        /** "Order flow and traps: building (42 shadow trades)". */
        fun words(): String = "${topic.title}: " + (level?.word ?: "not yet") + " ($n ${topic.unit}" + (if (lessons > 0) ", $lessons lesson${if (lessons == 1) "" else "s"}" else "") + ")"
    }

    /** The prefixes whose evidence counts toward each topic's skill without counting a trade twice. */
    private val SKILL_BASE = mapOf(Topic.STRATEGY to "strat", Topic.MANAGER to "mgr", Topic.SPECIALIST to "spec", Topic.ORDER_FLOW to "flow",
        Topic.REGIME to "regime", Topic.TIME_OF_DAY to "tod", Topic.DATA_HEALTH to "health", Topic.LAB to "lab", Topic.ORDERS to "order",
        Topic.EXECUTION to "exec", Topic.STUDY to "study", Topic.CANDLES to "candle")

    /** Each topic's level (its best active lesson's confidence) and its evidence count. */
    fun skills(book: Book): List<Skill> = Topic.entries.map { t ->
        val mine = active(book).filter { it.topic == t }
        val base = SKILL_BASE.getValue(t)
        val n = if (t == Topic.DATA_HEALTH || t == Topic.STUDY || t == Topic.CANDLES) book.lessons.filter { prefix(it.id) == base }.maxOfOrNull { evidence(it).n } ?: 0
            else book.lessons.filter { prefix(it.id) == base }.sumOf { evidence(it).n }
        Skill(t, mine.maxOfOrNull { it.confidence.ordinal }?.let { Confidence.entries[it] }, n, mine.size)
    }

    fun counts(book: Book): Map<Confidence, Int> = Confidence.entries.associateWith { c -> active(book).count { it.confidence == c } }

    private fun milestones(book: Book, i: Inputs): List<Milestone> {
        val have = book.timeline.map { it.key }.toSet()
        val out = ArrayList(book.timeline)
        fun add(key: String, text: String) { if (key !in have && out.none { it.key == key }) out += Milestone(i.day, key, text) }
        val act = active(book)
        if (book.lessons.isNotEmpty()) add("first-lesson", "First lessons from the agents: ${book.lessons.size} to start with")
        act.filter { it.confidence == Confidence.STRONG }.maxByOrNull { evidence(it).n }?.let { add("first-strong", "First strong lesson: ${said(it)}") }
        val strong = act.count { it.confidence == Confidence.STRONG }
        if (strong >= 10) add("ten-strong", "10 strong lessons")
        val managed = i.managed.count { it.vsOriginal != null }
        if (managed >= 100) add("managed-100", "100 managed trades reviewed against the original rules")
        val trades = book.lessons.filter { prefix(it.id) == "strat" }.sumOf { evidence(it).n }
        if (trades >= 1000) add("trades-1000", "1,000 bot trades studied")
        val muted = i.muted.filterValues { !it.startsWith("muted by") }
        if (muted.isNotEmpty()) add("first-muted-evidence", "First specialist muted on evidence: ${muted.keys.first().let { k ->
            ManagerTeam.nameOf(k.substringAfter(':')) + " on " + TradeManager.familyName(k.substringBefore(':')) }}")
        if (book.lessons.any { it.status == Status.RETIRED }) add("first-retired", "First lesson retired when the evidence turned against it")
        return out.takeLast(TIMELINE)
    }

    // ---- proposals ------------------------------------------------------------------------------------------------------------

    /** Never anything that could add real risk or touch a safety setting: only these kinds, and no such word in them. */
    private val FORBIDDEN = Regex("(?i)\\b(pin|lock|live|ai[- ]live|guard|google|speech|day lock|real order|real-money|kill switch|zerodha)\\b")

    fun safe(p: Proposal): Boolean = p.kind in ProposalKind.entries && !FORBIDDEN.containsMatchIn(p.kind.words) &&
        !FORBIDDEN.containsMatchIn(p.family) && (p.specialist == null || !FORBIDDEN.containsMatchIn(p.specialist))

    /** Is [l] in its rest after Boss said no twice? */
    fun coolingDown(l: Lesson, day: LocalDate): Boolean = l.cooldownUntil?.let { !day.isAfter(it) } == true

    /** The changes the strong, confirmed lessons imply: each put to Boss, never done; [MAX_PROPOSALS] at most a day. */
    fun proposals(lessons: List<Lesson>, i: Inputs): List<Proposal> {
        val day = i.day
        val out = ArrayList<Proposal>()
        val ranked = lessons.filter { it.status == Status.CONFIRMED && it.confidence == Confidence.STRONG && !it.accepted && !coolingDown(it, day) &&
            (it.proposedOn == null || !day.isBefore(it.proposedOn.plusDays(REPROPOSE_DAYS))) }
            .sortedWith(compareByDescending<Lesson> { it.weight }.thenByDescending { evidence(it).n })
        for (l in ranked) {
            val p = prefix(l.id); val parts = l.id.split(':')
            val e = evidence(l)
            val prop = when {
                p == "spec" && l.sign < 0 && parts.size == 3 -> {
                    val fam = parts[1]; val id = parts[2]
                    if (id !in ManagerTeam.IDS || i.muted.containsKey("$fam:$id")) null
                    else Proposal(l.id, ProposalKind.MUTE_SPECIALIST, fam, id, "mute the ${ManagerTeam.nameOf(id)} specialist for ${TradeManager.familyName(fam)}",
                        "Boss, I learned that ${statement(l).replaceFirstChar { it.lowercase() }} (${evidenceLine(l)}). Shall I mute the " +
                            "${ManagerTeam.nameOf(id)} specialist for ${TradeManager.familyName(fam)}? That only moves the manager back toward the original rules; its record goes on.")
                }
                p == "drift" && l.sign < 0 && parts.size == 3 && parts[2] == "paper" -> {
                    val key = parts[1]
                    if (key in i.paused) null
                    else Proposal(l.id, ProposalKind.PAUSE_PAPER_ENTRIES, key, null, "pause ${l.name.substringBefore(" (")}'s paper entries",
                        "Boss, ${statement(l)} (${evidenceLine(l)}). Consider pausing its new paper entries? Exits always go, and you switch it back on yourself.")
                }
                p == "mgr" && l.sign > 0 && parts.size == 2 -> {
                    val fam = parts[1]; val f = TradeManager.familyOf(fam)
                    if (f == null || !f.canAct || i.paperModes[fam] == TradeManager.Mode.ACT || e.rs <= 0) null
                    else Proposal(l.id, ProposalKind.MANAGER_ACT_PAPER, fam, null, "let the trade manager act on ${f.name}'s paper trades",
                        "Boss, ${statement(l)} (${evidenceLine(l)}). Turn the trade manager to act on ${f.name}'s paper trades? Paper only; it only exits early or locks profit.")
                }
                else -> null
            }
            if (prop != null && safe(prop)) out += prop
            if (out.size >= MAX_PROPOSALS) break
        }
        return out
    }

    /** Boss's yes or no to [lessonId]'s proposal on [day]: a yes raises its weight, a no lowers it; [REJECTS] noes rest it [COOLDOWN_DAYS] days. */
    fun answered(book: Book, lessonId: String, accepted: Boolean, day: LocalDate): Book {
        val id = norm(lessonId)
        var firstYes = false
        val ls = book.lessons.map { l ->
            if (l.id != id) l
            else if (accepted) { firstYes = true; l.copy(accepted = true, weight = minOf(2.0, l.weight + 0.25), rejections = 0) }
            else {
                val r = l.rejections + 1
                if (r >= REJECTS) l.copy(rejections = 0, cooldownUntil = day.plusDays(COOLDOWN_DAYS), weight = maxOf(0.1, l.weight * 0.6))
                else l.copy(rejections = r, weight = maxOf(0.1, l.weight * 0.6))
            }
        }
        val tl = if (firstYes && book.timeline.none { it.key == "first-accepted" })
            (book.timeline + Milestone(day, "first-accepted", "First proposal you accepted: ${book.lessons.firstOrNull { it.id == id }?.let { statement(it) } ?: id}")).takeLast(TIMELINE)
            else book.timeline
        return book.copy(lessons = ls, timeline = tl)
    }

    /**
     * What Autopilot made of [lessonId]'s change on [on] ([AutoChange]): "applied", "kept" or "reverted" - so Jarvis learns
     * from his own changes. A change kept raises the lesson's weight a little; one reverted by its own check lowers it as a
     * no would, and rests the lesson's proposals [COOLDOWN_DAYS] days.
     */
    fun autoStatus(book: Book, lessonId: String, status: String, on: LocalDate): Book {
        val id = norm(lessonId)
        if (book.lessons.none { it.id == id }) return book
        return book.copy(lessons = book.lessons.map { l ->
            if (l.id != id) l else when (status) {
                "kept" -> l.copy(auto = "kept ${day(on)}", weight = minOf(2.0, l.weight + 0.1))
                "reverted" -> l.copy(auto = "reverted ${day(on)}", weight = maxOf(0.1, l.weight * 0.6), cooldownUntil = on.plusDays(COOLDOWN_DAYS))
                else -> l.copy(auto = "$status ${day(on)}")
            }
        })
    }

    /**
     * A paper experiment's end ([Experiments]): its pairs (the day, and the variant's lead over the control in rupees) kept as
     * a lesson of its own - "The paper experiment on X did worse than the current setting" when it lost (a negative lesson),
     * "did better" when it won - and the lesson that started it moved as Autopilot's own changes move one: a win raises its
     * weight a little; a loss lowers it as a no would and rests its proposals [COOLDOWN_DAYS] days.
     */
    fun experimentResult(book: Book, lessonId: String?, target: String, what: String, won: Boolean, pairs: List<Pair<LocalDate, Double>>, on: LocalDate): Book {
        val ls = book.lessons.toMutableList()
        if (pairs.isNotEmpty()) {
            val id = norm("exp:$target")
            val days = java.util.TreeMap<LocalDate, Bucket>()
            for ((d, x) in pairs) {
                val b = days[d]
                days[d] = if (b == null) Bucket(d, 1, x, if (x > 0) 1 else 0) else b.copy(n = b.n + 1, rs = b.rs + x, hits = b.hits + if (x > 0) 1 else 0)
            }
            val bs = cap(days.values.toList())
            val e = evidence(Kind.RUPEES, id, bs)
            val sign = if (won) 1 else -1
            val old = ls.indexOfFirst { it.id == id }
            val l = Lesson(id, Topic.STRATEGY, Kind.RUPEES, "The paper experiment on $what", words(what) + listOf("experiment", "paper"),
                setOf("Autopilot's experiments"), bs, sign, Status.CONFIRMED, confidence(e, null), bs.first().from, bs.last().day, on, e.n,
                auto = (if (won) "experiment won " else "experiment lost ") + day(on))
            if (old >= 0) ls[old] = l else ls += l
        }
        val src = lessonId?.let { norm(it) }
        val out = ls.map { l ->
            if (l.id != src) l
            else if (won) l.copy(auto = "experiment won ${day(on)}", weight = minOf(2.0, l.weight + 0.1))
            else l.copy(auto = "experiment lost ${day(on)}", weight = maxOf(0.1, l.weight * 0.6), cooldownUntil = on.plusDays(COOLDOWN_DAYS))
        }
        return capped(book.copy(lessons = out))
    }

    // ---- questions ------------------------------------------------------------------------------------------------------------

    enum class AskKind { ALL, TODAY, GROWTH, ABOUT }

    /** What was asked; for [AskKind.ABOUT] the [topic] or the [tag] it is about, in [about] as said. */
    data class Ask(val kind: AskKind, val topic: Topic? = null, val tag: String? = null, val about: String? = null)

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(tell me |can you tell me )?"
    private const val TAIL = "( please| boss| jarvis)* $"
    private const val LEARNED = "(learned|learnt|learn)"
    private val AGENTS = "(the |your |my |these |all the |all these )?(agents|bots|strategies|workers|specialists|arms)"
    private val ALL = Regex(LEAD + "(what (have you|did you|you have|youve) $LEARNED( so far| till now| until now)?" +
        "|what (have you|did you|you have|youve) $LEARNED from $AGENTS( so far)?" +
        "|what (are|is) your lessons|show me your lessons|your lessons|show me what (you have|youve) $LEARNED|what (has|did) jarvis $LEARNED" +
        "|what have the agents taught you|what did the agents teach you)" + TAIL)
    private val TODAY = Regex(LEAD + "(what (have you|did you|you have|youve) $LEARNED (today|from $AGENTS today|this evening|tonight)" +
        "|what (have|did) you learn today from $AGENTS|todays lessons|today s lessons|(aaj|aaj tumne|tumne aaj|aapne aaj) kya (seekha|sikha)( hai)?)" + TAIL)
    private val GROWTH = Regex(LEAD + "(how are you growing|how have you grown|how much have you grown|are you growing|show me your growth|your growth|" +
        "how is your growth|hows your growth|how are you growing as an assistant|tum kaise (badh|grow) (rahe|kar rahe) ho|how are you growing jarvis)" + TAIL)
    private val ABOUT = Regex(LEAD + "(what do you know about|what (have you|did you|you have|youve) $LEARNED (about|on|from)|what are your lessons (on|about)|" +
        "what have the agents taught you about) ")
    private val ABOUT_TAIL = Regex(TAIL)

    /** "What have you learned?", "what did you learn today?", "how are you growing?", "what do you know about Liquidity?", else null. */
    fun asked(text: String): Ask? = askedKept.of(text) { askedFresh(text) }

    private val askedKept = Kept<Ask?>(64)

    private fun askedFresh(text: String): Ask? {
        val t = Spaced.joined(text)
        if (TODAY.containsMatchIn(t)) return Ask(AskKind.TODAY)
        if (ALL.containsMatchIn(t)) return Ask(AskKind.ALL)
        if (GROWTH.containsMatchIn(t)) return Ask(AskKind.GROWTH)
        val m = ABOUT.find(t) ?: return null
        val rest = ABOUT_TAIL.replace(" " + t.substring(m.range.last + 1), " ").trim()
        if (rest.isEmpty()) return null
        return subject(rest)?.let { (topic, tag) -> Ask(AskKind.ABOUT, topic, tag, rest) }
    }

    /** "Why do you think that?" / "what's the evidence?" (the app answers it only right after a lessons answer). */
    fun whyAsked(text: String): Boolean = WHY.containsMatchIn(Spaced.joined(text))

    private val WHY = Regex(LEAD + "(why do you think (that|so|this)|what (is|s) the evidence( for that| for this)?|whats the evidence( for that| for this)?|" +
        "show me the evidence|on what evidence|what evidence (do you have|is there))" + TAIL)

    private val FAMILY_TAGS = listOf("orb fresh" to "orb_fresh", "orb sweep" to "orb_sweep", "range fade" to "range_fade", "mcx evening" to "mcx-eve",
        "mcx morning" to "mcx-morning", "mcx trend" to "mcx-trend", "vix divergence" to "vixdiv", "us night silver" to "silver-night",
        "liquidity" to "liquidity", "solo" to "solo", "pine" to "pine", "hero" to "hero", "orb" to "orb", "night" to "night", "mcx" to "mcx")

    /** What [rest] names: a topic or a tag (a strategy, a specialist, a part of the day, a regime), or null. */
    fun subject(rest: String): Pair<Topic?, String?>? {
        val r = " " + rest.trim().removePrefix("the ").removePrefix("my ").removePrefix("your ").trim() + " "
        if (MajorityRule.asked(r)) return null to "majority_rule"
        if (Regex(" specialists? ").containsMatchIn(r)) {
            ManagerTeam.specialistIn(r)?.let { return null to it.first }
            return Topic.SPECIALIST to null
        }
        for ((w, key) in FAMILY_TAGS.take(8)) if (r.contains(" $w ")) return null to key
        if (Regex(" (mornings?|opening|the open|first hour) ").containsMatchIn(r)) return null to "morning"
        if (Regex(" (middays?|lunch|lunch time|noon) ").containsMatchIn(r)) return null to "midday"
        if (Regex(" (afternoons?|last hour|the close) ").containsMatchIn(r)) return null to "afternoon"
        if (Regex(" (evenings?) ").containsMatchIn(r)) return null to "evening"
        if (Regex(" (order flow|flow|traps?|trap guard) ").containsMatchIn(r)) return Topic.ORDER_FLOW to null
        if (Regex(" (trade manager|manager|managers) ").containsMatchIn(r)) return Topic.MANAGER to null
        if (Regex(" (data|data health|feeds?|data feeds?|the stream|stream) ").containsMatchIn(r)) return Topic.DATA_HEALTH to null
        if (Regex(" (busy days?|busy markets?) ").containsMatchIn(r)) return null to "busy"
        if (Regex(" (quiet days?|quiet markets?) ").containsMatchIn(r)) return null to "quiet"
        if (Regex(" (regimes?|market regimes?|the market s mood|market mood) ").containsMatchIn(r)) return Topic.REGIME to null
        if (Regex(" big moves? ").containsMatchIn(r)) return null to "moves"
        if (Regex(" (time of day|times of day|timing) ").containsMatchIn(r)) return Topic.TIME_OF_DAY to null
        for ((w, key) in FAMILY_TAGS.drop(8)) if (r.contains(" $w ")) return null to key
        if (Regex(" (strategy lab|the lab|lab|forge|the forge) ").containsMatchIn(r)) return Topic.LAB to null
        if (Regex("^ (order review|orders|order flaws|flaws|my fills|fills|execution|order execution) $").containsMatchIn(r)) return Topic.ORDERS to null
        if (Regex(" (slippage|spreads?|fill quality|the baseline) ").containsMatchIn(r)) return Topic.EXECUTION to null
        if (Regex(" (study|market study|the study|setups) ").containsMatchIn(r)) return Topic.STUDY to null
        if (Regex(" (candle agent|candle shapes?|the candles?) ").containsMatchIn(r)) return Topic.CANDLES to null
        if (Regex("^ (strategies|bots|agents|arms|my bots|the bots|the agents) $").containsMatchIn(r)) return Topic.STRATEGY to null
        return null
    }

    /** The lessons [a] is about ([a]'s topic, or its tag among a lesson's tags or its name's words), best first. */
    fun about(book: Book, a: Ask): List<Lesson> {
        val tag = a.tag?.lowercase(Locale.ENGLISH)
        return ranked(active(book).filter { l ->
            (a.topic != null && l.topic == a.topic) ||
                (tag != null && (tag in l.tags || l.id.split(':').any { it == tag || it.substringBefore(':') == tag } ||
                    " ${spacedWords(l.name.lowercase(Locale.ENGLISH))} ".contains(" ${tag.replace('_', ' ')} ")))
        })
    }

    /** Best first: strong before building before weak, then Boss's weight, then the evidence. */
    fun ranked(ls: List<Lesson>): List<Lesson> = ls.sortedWith(compareByDescending<Lesson> { it.confidence.ordinal }
        .thenByDescending { it.weight }.thenByDescending { evidence(it).n }.thenBy { it.id })

    const val NEVER_ACTS = "I only propose; nothing I learn changes a setting or a trade without your yes."

    // ---- the research's bundled notes ----------------------------------------------------------------------------------------

    /**
     * A finding of the app's own research, bundled with the app (not learned from the agents' records): fixed words with the
     * research's numbers, found by [tags]. It is never a [Lesson] - no evidence buckets, no confidence, never a [Proposal] -
     * so it can never change a setting, raise any risk or make a trade; the lessons' answers and the model's notes only say it.
     * It carries no word of Boss's account.
     */
    data class ResearchNote(val id: String, val name: String, val tags: List<String>, val text: String)

    /** The bundled notes. Majority Rule 14 (R13, Boss 11 Oct: "let all agents know your finding" - context only, no alert). */
    val RESEARCH: List<ResearchNote> = listOf(
        ResearchNote("research:majority_rule_14", "Majority Rule 14 (weekly NIFTY)",
            listOf("majority_rule", "majority", "mr14", "oversold"), MajorityRule.LESSON),
    )

    /** The bundled notes [question] names (one of a note's tags among its words, "majority rule", "MR 14", "sell-off zone"). */
    fun research(question: String): List<ResearchNote> {
        val t = Spaced.joined(question)
        val qw = t.trim().split(' ').toSet()
        val mr = MajorityRule.asked(question)
        return RESEARCH.filter { n -> (mr && n.id == "research:majority_rule_14") || n.tags.any { it in qw } }
    }

    /** "What have you learned?": the counts, the strongest few with their numbers. */
    fun sayAll(book: Book, max: Int = 4): String {
        val act = active(book)
        if (act.isEmpty()) return "I haven't learned anything from the agents yet, Boss: I study their records each evening after the close and say a lesson only with the numbers behind it."
        val c = counts(book)
        val shown = ranked(act).filter { it.confidence != Confidence.WEAK }.take(max)
        val head = "From the agents I've learned ${act.size} lesson${if (act.size == 1) "" else "s"}, Boss: ${c[Confidence.STRONG]} strong, ${c[Confidence.BUILDING]} building, ${c[Confidence.WEAK]} weak."
        val body = if (shown.isEmpty()) " None has enough evidence yet to be more than a hunch (I need $BUILDING_N to call one building, $STRONG_N and agreement over time to call it strong)."
            else " " + shown.joinToString(" ") { "• ${said(it)}." }
        return head + body + " Ask \"why do you think that?\" for the evidence. $NEVER_ACTS" + RESEARCH_LINE
    }

    /** Said after the lessons: the research notes carried beside them (context only). */
    private val RESEARCH_LINE: String get() = " From the research I also carry " + RESEARCH.joinToString("; ") { it.name } + " - context only, never a trade."

    /** "What did you learn today?". */
    fun sayToday(book: Book, today: LocalDate): String {
        if (book.lastPass != today) return "I haven't studied today's records yet, Boss: I do it after the close." + (book.line?.let { " Last time: $it" } ?: "")
        val by = book.lessons.associateBy { it.id }
        val items = book.today.mapNotNull { c -> by[c.id]?.let { c to it } }
            .filter { (c, l) -> c.kind != ChangeKind.NEW || l.confidence != Confidence.WEAK }
            .sortedWith(compareByDescending<Pair<Change, Lesson>> { it.second.confidence.ordinal }.thenByDescending { evidence(it.second).n })
        val line = book.line ?: line(book, book.today)
        if (items.isEmpty()) return "$line $NEVER_ACTS"
        return line.substringBefore(" Top:") + " " + items.take(4).joinToString(" ") { (c, l) -> "• ${c.kind.word.replaceFirstChar { it.uppercase() }}: ${said(l)}." } + " $NEVER_ACTS"
    }

    /** "How are you growing?": the counts, each topic's level and the latest milestones. */
    fun sayGrowth(book: Book): String {
        val c = counts(book)
        val skills = skills(book).filter { it.n > 0 }
        val head = "How I'm growing, Boss: ${active(book).size} lessons held (${c[Confidence.STRONG]} strong, ${c[Confidence.BUILDING]} building, ${c[Confidence.WEAK]} weak)" +
            (book.lessons.count { it.status == Status.RETIRED }.takeIf { it > 0 }?.let { ", $it retired when the evidence turned" } ?: "") + "."
        val sk = if (skills.isEmpty()) " No skill yet: the agents' records are still too thin." else " Skills: " + skills.joinToString("; ") { it.words() } + "."
        val tl = book.timeline.takeLast(3)
        val ms = if (tl.isEmpty()) "" else " Milestones: " + tl.joinToString("; ") { "${day(it.day)} - ${it.text}" } + "."
        return head + sk + ms
    }

    /** "What do you know about [a]?". */
    fun sayAbout(book: Book, a: Ask, max: Int = 4): String {
        val what = a.about ?: a.topic?.title ?: a.tag ?: "that"
        val notes = RESEARCH.filter { n -> a.tag != null && a.tag in n.tags }
        if (notes.isNotEmpty()) return "From the research, Boss: " + notes.joinToString(" ") { it.text }
        val ls = about(book, a)
        if (ls.isEmpty()) return "I haven't learned anything about $what from the agents yet, Boss. I say a lesson only once their records hold numbers behind it."
        return "What I've learned about $what, Boss: " + ls.take(max).joinToString(" ") { "• ${said(it)}." } +
            (if (ls.size > max) " And ${ls.size - max} more." else "") + " Ask \"why do you think that?\" for the evidence."
    }

    /**
     * Said for a lessons question on a locked phone (G6-1): the lessons carry the bots' rupees, live round trips among them,
     * so only the counts are said - as [DailyLesson.LOCKED] and the self answers do.
     */
    const val LOCKED = "Unlock the phone for my lessons, Boss: they carry the bots' rupees."

    /** The lessons answer on a locked phone: how many and how strong - no rupee, no lesson's words. */
    fun sayLocked(book: Book): String {
        val act = active(book)
        if (act.isEmpty()) return "I haven't learned anything from the agents yet, Boss."
        val c = counts(book)
        return "I hold ${act.size} lesson${if (act.size == 1) "" else "s"} from the agents, Boss: ${c[Confidence.STRONG]} strong, " +
            "${c[Confidence.BUILDING]} building, ${c[Confidence.WEAK]} weak. $LOCKED"
    }

    /** "Why do you think that?": the evidence behind [ls], and why each has its confidence ([locked]: only [LOCKED]). */
    fun sayWhy(ls: List<Lesson>, locked: Boolean = false): String {
        if (ls.isEmpty()) return "That answer had no lesson behind it, Boss."
        if (locked) return LOCKED
        return "The evidence, Boss: " + ls.take(4).joinToString(" ") { l ->
            val e = evidence(l)
            val why = when (l.confidence) {
                Confidence.STRONG -> "strong: $STRONG_N or more and ${e.agreeing} of $SPLITS periods point the same way" +
                    (l.q?.let { ", and ${qWords(it)} across all my lessons (the bar is ${fmtQ(BH_Q)})" } ?: "")
                Confidence.BUILDING -> "building: ${e.n} so far" + (if (e.splits.size == SPLITS) ", ${e.agreeing} of $SPLITS periods agree" else "") +
                    (if (l.topic == Topic.STUDY) "; a market-study lesson stays at building: each night counts the same two years again, so the nights are not independent"
                    else if (l.topic == Topic.CANDLES) "; a Candle agent lesson stays at building: it compares running counts with the research and never changes anything"
                    else if (confidence(e) == Confidence.STRONG) "; ${l.q?.let { qWords(it) } ?: "q not worked out yet"} is above the bar of ${fmtQ(BH_Q)}, so with this many lessons it could be luck"
                    else "; strong needs $STRONG_N, agreement over time and q at most ${fmtQ(BH_Q)}")
                Confidence.WEAK -> contradiction(l)?.let { "not trusted: it $it" } ?: "weak: only ${e.n} so far"
            }
            "• ${statement(l)}: ${evidenceLine(l)}. It is $why" + (if (l.status == Status.WEAKENED) "; recent evidence went against it, so it is weakened" else "") + "."
        }
    }

    /** One strong lesson to add to an answer about [tag] (a strategy family or "manager"), or null. */
    fun appendFor(book: Book, tag: String?, topic: Topic? = null): String? {
        val ls = about(book, Ask(AskKind.ABOUT, topic, tag)).filter { it.confidence == Confidence.STRONG && it.status == Status.CONFIRMED }
        return ls.firstOrNull()?.let { "What I've learned: ${said(it)}." }
    }

    /** The evidence of [ls] as plain facts (the chat's "how do you know that" reads them). */
    fun facts(ls: List<Lesson>): List<String> = ls.map { "${statement(it)}: ${evidenceLine(it)} (${trustWord(it)})" }

    /**
     * The model's notes: the lessons most relevant to [question] (its words among their tags or names; else the strongest),
     * strong and building only, short with their numbers, at most [cap] characters in all.
     */
    fun forModel(book: Book, question: String, cap: Int = MODEL_CHARS): List<String> {
        val qw = words(question).filter { it.length > 2 }.toSet()
        val pool = active(book).filter { it.confidence != Confidence.WEAK }
        val out = ArrayList<String>()
        var used = 0
        // The research's notes the question names (fixed, no account word): first, within the same cap.
        for (n in research(question)) if (used + n.text.length + 2 <= cap) { out += n.text; used += n.text.length + 2 }
        if (pool.isEmpty()) return out
        val rel = pool.filter { l -> l.tags.any { it in qw } || words(l.name).any { it in qw } }
        for (l in ranked(rel.ifEmpty { pool })) {
            val e = evidence(l)
            val s = "${statement(l)}: ${e.n} ${if (l.kind == Kind.RATE) "days/events" else l.topic.unit}" +
                (if (l.kind == Kind.RUPEES) ", ${rs(e.rs)}" else "") + " (${l.confidence.word})"
            if (used + s.length + 2 > cap) break
            out += s; used += s.length + 2
        }
        return out
    }

    // ---- the file ------------------------------------------------------------------------------------------------------------

    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').replace(',', ';').take(200)
    private fun cleanText(s: String) = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').take(400)
    /** Rupees to the paisa, written without a formatter (thousands of them a save). */
    private fun d(x: Double): String {
        if (!x.isFinite()) return "0"
        val c = Math.round(x * 100)
        val a = abs(c)
        val frac = a % 100
        return (if (c < 0) "-" else "") + (a / 100) + "." + (if (frac < 10) "0" else "") + frac
    }

    /** A probability in four significant digits ("0.03412", "1.200e-08"). */
    private fun sig(x: Double): String = if (!x.isFinite()) "1" else String.format(Locale.ENGLISH, "%.4g", x.coerceIn(0.0, 1.0))

    /** A probability read back (blank, garbage or out of 0..1: null). */
    private fun prob(s: String?): Double? = s?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }

    /** The book as lines (V, L, D, R, M, P, C); every free text cleaned of the separators. */
    fun encode(b: Book): String {
        val sb = StringBuilder()
        sb.append("V|1\n")
        b.lastPass?.let { sb.append("P|").append(it).append('|').append(cleanText(b.line.orEmpty())).append('\n') }
        for (c in b.today) sb.append("C|").append(clean(c.id)).append('|').append(c.kind.name).append('\n')
        for ((day, r) in b.regimes.entries.sortedBy { it.key }) sb.append("R|").append(day).append('|').append(clean(r)).append('\n')
        for (m in b.timeline) sb.append("M|").append(m.day).append('|').append(clean(m.key)).append('|').append(cleanText(m.text)).append('\n')
        for (l in b.lessons) {
            sb.append("L|").append(clean(l.id)).append('|').append(l.topic.name).append('|').append(l.kind.name).append('|').append(cleanText(l.name).replace(',', ';'))
                .append('|').append(l.tags.joinToString(",") { clean(it) }).append('|').append(l.agents.joinToString(",") { clean(it) })
                .append('|').append(l.sign).append('|').append(l.status.name).append('|').append(l.confidence.name)
                .append('|').append(l.firstSeen).append('|').append(l.lastSeen).append('|').append(l.statusOn)
                .append('|').append(l.checkedN).append('|').append(l.flips).append('|').append(d(l.weight)).append('|').append(l.rejections)
                .append('|').append(l.cooldownUntil ?: "").append('|').append(l.proposedOn ?: "").append('|').append(if (l.accepted) "1" else "0")
                .append('|').append(l.auto?.let { cleanText(it) } ?: "")
                .append('|').append(l.p?.let { sig(it) } ?: "").append('|').append(l.q?.let { sig(it) } ?: "")
                .append('|').append(l.cond?.let { clean(it) } ?: "").append('|').append(l.condQ?.let { sig(it) } ?: "").append('\n')
            sb.append("D|").append(clean(l.id)).append('|')
            for ((k, bk) in l.buckets.withIndex()) {
                if (k > 0) sb.append(';')
                sb.append(bk.day).append(',').append(bk.n).append(',').append(d(bk.rs)).append(',').append(bk.hits)
                if (bk.from != bk.day) sb.append(',').append(bk.from)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** The book from [text] (unreadable lines skipped; null or garbage: an empty book). Duplicates merge. */
    fun decode(text: String?): Book {
        if (text.isNullOrBlank()) return Book()
        val lessons = LinkedHashMap<String, Lesson>()
        val buckets = HashMap<String, ArrayList<Bucket>>()
        val regimes = HashMap<LocalDate, String>()
        val timeline = ArrayList<Milestone>()
        val today = ArrayList<Change>()
        var last: LocalDate? = null; var line: String? = null
        fun date(s: String?): LocalDate? = s?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        for (raw in text.lineSequence()) {
            val f = raw.split('|')
            runCatching {
                when (f[0]) {
                    "P" -> { last = date(f[1]); line = f.getOrNull(2)?.takeIf { it.isNotEmpty() } }
                    "C" -> today += Change(f[1], ChangeKind.valueOf(f[2]))
                    "R" -> regimes[LocalDate.parse(f[1])] = f[2]
                    "M" -> timeline += Milestone(LocalDate.parse(f[1]), f[2], f[3])
                    "L" -> if (f.size >= 20) {
                        val id = f[1]
                        lessons[id] = Lesson(id, Topic.valueOf(f[2]), Kind.valueOf(f[3]), f[4], f[5].split(',').filter { it.isNotEmpty() },
                            f[6].split(',').filter { it.isNotEmpty() }.toSet(), emptyList(), f[7].toInt(), Status.valueOf(f[8]), Confidence.valueOf(f[9]),
                            LocalDate.parse(f[10]), LocalDate.parse(f[11]), LocalDate.parse(f[12]), f[13].toInt(), f[14].toInt(), f[15].toDouble().coerceIn(0.1, 2.0),
                            f[16].toInt(), date(f[17]), date(f[18]), f[19] == "1", f.getOrNull(20)?.takeIf { it.isNotEmpty() },
                            p = prob(f.getOrNull(21)), q = prob(f.getOrNull(22)), cond = f.getOrNull(23)?.takeIf { it.isNotEmpty() },
                            condQ = prob(f.getOrNull(24)))
                    }
                    "D" -> for (part in f[2].split(';')) runCatching {
                        val x = part.split(',')
                        val day = LocalDate.parse(x[0])
                        buckets.getOrPut(f[1]) { ArrayList() } += Bucket(day, x[1].toInt(), x[2].toDouble(), x[3].toInt(),
                            x.getOrNull(4)?.let { LocalDate.parse(it) }?.takeIf { !it.isAfter(day) } ?: day)
                    }
                }
            }
        }
        val ls = lessons.values.mapNotNull { l -> buckets[l.id]?.sortedBy { it.day }?.let { bs -> l.copy(buckets = cap(bs)) } }
        return Book(merge(ls), regimes, timeline.takeLast(TIMELINE), last, today, line)
    }
}
