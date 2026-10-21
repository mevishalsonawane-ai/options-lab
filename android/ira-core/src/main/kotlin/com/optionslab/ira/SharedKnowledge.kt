package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale

/**
 * One shared knowledge for every agent (Boss, 13 Oct: "I want all the agents which we have already built to learn from
 * whatever findings we have, and vice versa: Jarvis's knowledge should be shared with them, and the findings from the
 * research should be shared with Jarvis"). One query layer over three things that used to live apart:
 *
 *  (a) the research - the knowledge pack's "shared" section ([research], built by research/knowledge/build_pack.py): the
 *      findings and strategy cards that carry a verdict, every experiment-log entry (failures included), the open questions,
 *      the phone's own lessons imported back into research, and the execution baselines (the real spread, h24);
 *  (b) Jarvis's lessons ([AgentLessons.Book]: what the agents' records taught him, with their evidence);
 *  (c) the recent findings (the findings bus's logs, counted by kind).
 *
 * Each [Item] is tagged the same way - topics, strategy (the trade manager's family key, "orb", "liquidity"), instrument,
 * time of day, regime, execution, where it is from, its date and sample size - with a strength: n, a unitless effect and its
 * [Gate] (pass / fail / open). A simple API: [about], [verdict], [executionBaseline], and one reader per agent
 * ([specialistNote], [flawCite], [flowVerdict], [studyPlan], [forgeAvoid], [forgePrior], [variantRejected], [answer]).
 *
 * The conflict rule: when an app lesson (Jarvis's, or one the phone sent back to research) says the opposite of a research
 * verdict about the same strategy, BOTH are kept and both are marked [Item.conflict] with both sample sizes ([Conflict]) - never
 * one silently picked. A few weeks of paper fills are said beside years of tests, never instead of them.
 *
 * What reading it may do (the hard rule): explain, rank, or LOWER risk - and a new skip or smaller size starts only as a
 * SHADOW record ([shadowSkips]: counted at night, never acting), behind a switch that is off by default. It never adds a
 * trade, never raises a size, never touches a live order, never Liquidity BANKNIFTY's behaviour (frozen to 26 Oct: read and
 * explained only, [LIQUIDITY_FROZEN]). Pure and memory-cheap: a few hundred items, built once in the background
 * ([build]) and rebuilt nightly; every read is a lookup in the built [Snapshot] (no I/O, no clock), safe on any thread.
 */
object SharedKnowledge {
    enum class Source(val key: String, val word: String) {
        RESEARCH("research", "the research"), EXPERIMENT("experiment", "the experiment log"), OPEN("open", "the open questions"),
        APP("app", "the phone's lessons sent back to research"), JARVIS("jarvis", "Jarvis's lessons"), FINDINGS("findings", "the findings bus");
        val app: Boolean get() = this == APP || this == JARVIS
        companion object { fun of(k: String?): Source = entries.firstOrNull { it.key == k } ?: RESEARCH }
    }

    enum class Verdict(val key: String, val word: String) {
        WORKS("positive", "held up"), WEAK("weak", "a weak edge at best"), FAILS("negative", "did not pay"),
        OPEN("open", "still open"), CONTEXT("context", "context only");
        companion object { fun of(k: String?): Verdict = entries.firstOrNull { it.key == k } ?: CONTEXT }
    }

    /** The strength's gate: passed the tests (or the lesson's bar), failed them, not settled yet, or not a test. */
    enum class Gate(val key: String) {
        PASS("pass"), FAIL("fail"), OPEN("open"), NONE("none");
        companion object { fun of(k: String?): Gate = entries.firstOrNull { it.key == k } ?: NONE }
    }

    data class Item(
        val id: String, val topics: List<String>, val arm: String?, val inst: String?, val tod: String?, val regime: String?,
        val execution: Boolean, val source: Source, val date: String, val n: Int?, val effect: Double?, val gate: Gate,
        val verdict: Verdict, val text: String, val src: String,
        /** Set on both sides of a disagreement between an app lesson and a research verdict (both kept). */
        val conflict: Conflict? = null,
    ) {
        val topic: String get() = topics.firstOrNull() ?: ""
        /** The source file's name ("FINDINGS.md", "HUNT_R14_REENTRY_CHURN.md"). */
        val doc: String get() = src.substringAfterLast('/')
        /** "HUNT_R14_REENTRY_CHURN.md, 2026-10-12, n 1924". */
        fun cite(): String = doc + (if (date.isNotEmpty()) ", $date" else "") + (n?.let { ", n $it" } ?: "")
        /** The item in one line with its source: "<text> (doc, date, n; did not pay)". */
        fun line(max: Int = 220): String {
            val t = text.trim().trimEnd('.').let { if (it.length > max) it.take(max - 3).trimEnd() + "..." else it }
            return "$t (${cite()}; ${verdict.word})" + (conflict?.let { " - ${it.words()}" } ?: "")
        }
    }

    /** An app lesson and a research verdict that disagree about one strategy: both kept, both sample sizes said. */
    data class Conflict(val arm: String, val researchId: String, val researchVerdict: Verdict, val researchN: Int?, val researchSrc: String,
                        val appId: String, val appVerdict: Verdict, val appN: Int?, val appSource: Source) {
        fun words(): String = "conflict on ${armName(arm)}: the research says it ${researchVerdict.word} (n ${researchN ?: "not stated"}, $researchSrc), " +
            "${appSource.word} say it ${appVerdict.word} (n ${appN ?: "?"}); both kept, neither picked"
    }

    /** The real half-spread of one index's near-the-money options (h24) and where it is from. */
    data class Spread(val inst: String, val halfSpreadPct: Double, val n: Int, val src: String, val sec: String, val text: String, val date: String)

    /** What the research found about execution: the spreads, the signal-time multiplier, the round trip's cost. */
    data class Baselines(val spreads: List<Spread> = emptyList(), val signalX: Double = 1.0, val signalText: String = "", val signalSrc: String = "",
                         val roundTrips: Map<String, Double> = emptyMap())

    /** The pack's shared section, typed ([research]). */
    class Research(val items: List<Item>, val baselines: Baselines)

    /**
     * Everything one build put together, read only: the items, indexed by strategy and topic, the baselines, the pack's
     * version and the day it was built for. Safe to share between threads.
     */
    class Snapshot(val items: List<Item>, val baselines: Baselines, val version: String, val day: LocalDate?) {
        internal val byArm: Map<String, List<Item>> = items.filter { it.arm != null }.groupBy { it.arm!! }
        internal val byTopic: Map<String, List<Item>> = HashMap<String, ArrayList<Item>>().also { m ->
            for (i in items) for (t in i.topics) m.getOrPut(t) { ArrayList() } += i
        }
        val conflicts: List<Item> get() = items.filter { it.conflict != null && it.source.app }
        val size: Int get() = items.size
        fun count(s: Source): Int = items.count { it.source == s }
        /** "188 research items, 64 experiments, 12 of Jarvis's lessons, 3 findings counts (pack 5c0dc75)". */
        fun summary(): String = "${count(Source.RESEARCH)} research items, ${count(Source.EXPERIMENT)} experiments, ${count(Source.OPEN)} open questions, " +
            "${count(Source.APP)} phone lessons in research, ${count(Source.JARVIS)} of Jarvis's lessons, ${count(Source.FINDINGS)} findings counts" +
            (if (version.isNotEmpty()) " (pack ${version.take(7)})" else "")
    }

    val EMPTY = Snapshot(emptyList(), Baselines(), "", null)

    /** Liquidity BANKNIFTY's behaviour is frozen for its paper test to 26 Oct: it is read and explained, never gated. */
    const val LIQUIDITY_FROZEN = "liquidity"

    // ---- the research (the pack's shared section) --------------------------------------------------------------------------

    private fun str(m: Map<*, *>, k: String): String = m[k] as? String ?: ""
    private fun strs(m: Map<*, *>, k: String): List<String> = (m[k] as? List<*>).orEmpty().filterIsInstance<String>()

    /** The pack's "shared" section typed (unknown or broken items skipped). */
    fun research(m: Map<*, *>): Research {
        val items = (m["items"] as? List<*>).orEmpty().mapNotNull { x ->
            val o = x as? Map<*, *> ?: return@mapNotNull null
            val id = o["id"] as? String ?: return@mapNotNull null
            val text = o["x"] as? String ?: return@mapNotNull null
            Item(id, strs(o, "t"), (o["a"] as? String)?.takeIf { it.isNotEmpty() }, o["i"] as? String, o["tod"] as? String, o["r"] as? String,
                o["e"] == true, Source.of(o["k"] as? String), str(o, "d"), (o["n"] as? Number)?.toInt(), (o["f"] as? Number)?.toDouble(),
                Gate.of(o["g"] as? String), Verdict.of(o["v"] as? String), text, str(o, "s"))
        }
        val b = m["baselines"] as? Map<*, *>
        val baselines = if (b == null) Baselines() else Baselines(
            (b["spreads"] as? List<*>).orEmpty().mapNotNull { x ->
                val o = x as? Map<*, *> ?: return@mapNotNull null
                val hs = (o["hs"] as? Number)?.toDouble() ?: return@mapNotNull null
                Spread(str(o, "i").uppercase(Locale.ENGLISH), hs, (o["n"] as? Number)?.toInt() ?: 1, str(o, "s"), str(o, "sec"), str(o, "x"), str(o, "d"))
            },
            (b["signal_x"] as? Number)?.toDouble()?.coerceIn(1.0, 5.0) ?: 1.0, str(b, "signal_text"), str(b, "signal_src"),
            (b["round_trips"] as? List<*>).orEmpty().mapNotNull { x ->
                val o = x as? Map<*, *> ?: return@mapNotNull null
                val rs = (o["rs"] as? Number)?.toDouble() ?: return@mapNotNull null
                str(o, "i").uppercase(Locale.ENGLISH) to rs
            }.toMap())
        return Research(items, baselines)
    }

    // ---- the build -----------------------------------------------------------------------------------------------------------

    /** The trade manager's families as words ("orb_fresh" -> "ORB Fresh"); others as they are. */
    fun armName(arm: String): String = TradeManager.familyOf(arm)?.name ?: when (arm) { "mcx" -> "MCX bots"; else -> arm }

    private val ARM_RX: List<Pair<String, Regex>> = listOf(
        "orb_fresh" to Regex("\\borb[ _-]?fresh\\b"), "orb_sweep" to Regex("\\borb[ _-]?sweep\\b"), "range_fade" to Regex("\\brange[ _-]?fade\\b"),
        "liquidity" to Regex("\\bliquidity\\b"), "orb" to Regex("\\borb\\b|opening range break"), "hero" to Regex("expiry hero|zero to hero|\\bhero\\b"),
        "night" to Regex("\\bnight( \\(?r3\\)?)?\\b"), "vixdiv" to Regex("vix ?div(ergence)?"), "solo" to Regex("\\bsolo\\b|big-candle pullback"),
        "pine" to Regex("\\bpine\\b|supertrend"), "mcx" to Regex("\\bmcx\\b"),
    )

    /** The strategy [text] names (a bot's name, a lesson's words, a question): the trade manager's family key, or null. */
    fun armOf(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val t = text.lowercase(Locale.ENGLISH).replace('_', ' ')
        TradeManager.FAMILIES.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }?.let { return it.key }
        return ARM_RX.firstOrNull { it.second.containsMatchIn(t) }?.first
    }

    /** A lesson's topics: its topic's word and its tags, and the specialist's topic for a specialist lesson. */
    private fun lessonTopics(l: AgentLessons.Lesson): List<String> {
        val p = l.id.substringBefore(':')
        val base = when (l.topic) {
            AgentLessons.Topic.STRATEGY -> "strategy"; AgentLessons.Topic.MANAGER -> "trade-manager"; AgentLessons.Topic.SPECIALIST -> "trade-manager"
            AgentLessons.Topic.ORDER_FLOW -> "order-flow"; AgentLessons.Topic.REGIME -> "regime"; AgentLessons.Topic.TIME_OF_DAY -> "time-of-day"
            AgentLessons.Topic.DATA_HEALTH -> "data-health"; AgentLessons.Topic.LAB -> "lab"; AgentLessons.Topic.ORDERS -> "orders"
            AgentLessons.Topic.EXECUTION -> "costs"; AgentLessons.Topic.STUDY -> "study"
            AgentLessons.Topic.CANDLES -> "candles"
        }
        val extra = when (p) {
            "trap" -> listOf("traps"); "spec" -> listOfNotNull(SPEC_TOPICS[l.id.substringAfterLast(':')]); "order" -> listOf("orders")
            "strat" -> listOf("profit"); "exec" -> listOf("costs", "execution"); else -> emptyList()
        }
        return (listOf(base) + extra + l.tags).distinct().take(10)
    }

    /** One of Jarvis's lessons as an item: its numbers as n and a unitless effect (never its rupees), strong as a gate. */
    fun item(l: AgentLessons.Lesson): Item {
        val e = AgentLessons.evidence(l)
        val p = l.id.substringBefore(':')
        val arm = when (p) {
            "mgr" -> l.id.substringAfter(':'); "spec" -> l.id.split(':').getOrNull(1)
            else -> armOf(l.name) ?: armOf(l.id.substringAfter(':'))
        }
        val verdict = when { l.sign > 0 -> Verdict.WORKS; l.sign < 0 -> Verdict.FAILS; else -> Verdict.CONTEXT }
        // A market-study lesson is never a passed or failed test (13 Oct review): its nightly counts re-read the same two years,
        // so they are not independent agreement ([AgentLessons.ceiling] keeps it at building; this holds for an older book too).
        val gate = if (l.confidence == AgentLessons.Confidence.STRONG && l.topic != AgentLessons.Topic.STUDY && l.topic != AgentLessons.Topic.CANDLES) (if (l.sign > 0) Gate.PASS else Gate.FAIL) else Gate.NONE
        val effect = e.hitRate?.let { it - 0.5 }
        return Item("jarvis:${l.id}", lessonTopics(l), arm, null, l.cond?.takeIf { it.startsWith("tod:") }?.substringAfter(':'),
            l.cond?.takeIf { it.startsWith("regime:") }?.substringAfter(':'), p == "exec" || p == "order" || l.topic == AgentLessons.Topic.EXECUTION,
            Source.JARVIS, l.lastSeen.toString(), e.n, effect, gate, verdict,
            "${AgentLessons.statement(l)} (${e.n} ${l.topic.unit}, ${l.confidence.word})", "jarvis/lessons.txt")
    }

    private val FINDING_TOPICS = mapOf(
        Findings.Kind.SWEEP to "liquidity", Findings.Kind.BREAK to "orb", Findings.Kind.ABSORPTION to "order-flow", Findings.Kind.PULL to "order-flow",
        Findings.Kind.STOP_HUNT to "traps", Findings.Kind.BIG_MOVE to "moves", Findings.Kind.STRONG_CLOSE to "candles", Findings.Kind.VIX_SPIKE to "vix",
        Findings.Kind.NEWS_WINDOW to "news", Findings.Kind.ENTRY to "strategy", Findings.Kind.STOP_HIT to "stop", Findings.Kind.TARGET_HIT to "target",
        Findings.Kind.DRIFT to "drift", Findings.Kind.DATA_UNRELIABLE to "data-health", Findings.Kind.DATA_HEALTH to "data-health", Findings.Kind.OTHER to "note",
        Findings.Kind.CANDLE to "candles",
    )

    /** The recent findings counted by kind (how many posts on how many days): one context item a kind. */
    fun findingItems(findings: List<Findings.Finding>, day: LocalDate?): List<Item> = findingItems(FindingsTally.of(findings), day)

    /**
     * [findingItems] from the counts alone (memory audit, 14 Oct: the logs are counted as they are read, never kept as
     * objects). The same items as from the list.
     */
    fun findingItems(tally: FindingsTally, day: LocalDate?): List<Item> {
        if (tally.isEmpty()) return emptyList()
        return tally.byKind().map { kc ->
            val k = kc.kind; val n = kc.count; val days = kc.days
            val who = kc.who.sortedByDescending { it.second }.take(3).joinToString(", ") { it.first }
            Item("findings:${k.name.lowercase(Locale.ENGLISH)}", listOf(FINDING_TOPICS[k] ?: "note"), null, null, null, null,
                k == Findings.Kind.DATA_UNRELIABLE, Source.FINDINGS, day?.toString() ?: "", n, null, Gate.NONE, Verdict.CONTEXT,
                "The findings bus: $n ${k.words} post${if (n == 1) "" else "s"} on $days day${if (days == 1) "" else "s"} (most from $who)", "findings/")
        }.sortedBy { it.id }
    }

    /** Is this research item a verdict on the strategy as a whole (its card, or an experiment / finding naming it)? */
    private fun strategyVerdict(i: Item): Boolean = i.arm != null && (i.verdict == Verdict.WORKS || i.verdict == Verdict.FAILS) &&
        !i.source.app && i.source != Source.FINDINGS && i.source != Source.OPEN

    /** The research's verdict on [arm]: its strategy card first, then the best-tested other item. */
    private fun researchVerdictOf(items: List<Item>, arm: String): Item? {
        val pool = items.filter { it.arm == arm && !it.source.app && it.source != Source.FINDINGS }
        return pool.firstOrNull { it.id.startsWith("strategy.") && it.id.endsWith(".what") }
            ?: pool.filter { strategyVerdict(it) }.maxByOrNull { (it.n ?: 0) + if (it.gate == Gate.PASS || it.gate == Gate.FAIL) 1_000_000 else 0 }
    }

    /** An app lesson that makes a claim about the strategy's money (Jarvis's "strat:" lessons, the phone's in research). */
    private fun profitClaim(i: Item): Boolean = i.source.app && i.arm != null && (i.verdict == Verdict.WORKS || i.verdict == Verdict.FAILS) &&
        ("profit" in i.topics || i.id.startsWith("jarvis:strat:") || i.id.startsWith("app:strat:"))

    /**
     * The conflict rule over [items]: each app lesson about a strategy's money that says the opposite of the research's
     * verdict on that strategy gets a [Conflict], and so does that research item (both kept, both sample sizes said).
     */
    fun marked(items: List<Item>): List<Item> {
        val research = HashMap<String, Item?>()
        val conflictsOn = HashMap<String, Conflict>()
        val out = items.map { i ->
            if (!profitClaim(i)) return@map i
            val r = research.getOrPut(i.arm!!) { researchVerdictOf(items, i.arm) } ?: return@map i
            val opposite = (r.verdict == Verdict.WORKS && i.verdict == Verdict.FAILS) || (r.verdict == Verdict.FAILS && i.verdict == Verdict.WORKS)
            if (!opposite) return@map i
            // A strategy card states no sample size of its own: the largest one the research gives on that strategy and verdict.
            val rn = r.n ?: items.filter { it.arm == i.arm && !it.source.app && it.source != Source.FINDINGS && it.verdict == r.verdict }.mapNotNull { it.n }.maxOrNull()
            val c = Conflict(i.arm, r.id, r.verdict, rn, r.doc, i.id, i.verdict, i.n, i.source)
            conflictsOn.putIfAbsent(r.id, c)
            i.copy(conflict = c)
        }
        return if (conflictsOn.isEmpty()) out else out.map { i -> conflictsOn[i.id]?.let { i.copy(conflict = it) } ?: i }
    }

    /** The snapshot from the pack's research [r], Jarvis's [book] and the recent [findings], for [day]. Pure; a worker thread. */
    fun build(r: Research?, book: AgentLessons.Book?, findings: List<Findings.Finding> = emptyList(), day: LocalDate? = null, version: String = ""): Snapshot =
        build(r, book, FindingsTally.of(findings), day, version)

    /** [build] from the findings' counts (the phone's way since 14 Oct: no finding is kept as an object). */
    fun build(r: Research?, book: AgentLessons.Book?, findings: FindingsTally, day: LocalDate?, version: String): Snapshot {
        // Strong and building lessons - and a lesson the pass marked "contradicts research" (kept weak there): exactly the one the
        // conflict rule must say beside the research, never drop.
        val lessons = book?.let { AgentLessons.active(it) }.orEmpty()
            .filter { it.confidence != AgentLessons.Confidence.WEAK || AgentLessons.contradiction(it) != null }.map { item(it) }
        val items = marked(r?.items.orEmpty() + lessons + findingItems(findings, day))
        return Snapshot(items, r?.baselines ?: Baselines(), version, day)
    }

    /** [build] from a pack (its shared section and version). */
    fun build(pack: Knowledge.Pack?, book: AgentLessons.Book?, findings: List<Findings.Finding> = emptyList(), day: LocalDate? = null): Snapshot =
        build(pack?.shared, book, findings, day, pack?.version.orEmpty())

    /** [build] from a pack and the findings' counts. */
    fun build(pack: Knowledge.Pack?, book: AgentLessons.Book?, findings: FindingsTally, day: LocalDate?): Snapshot =
        build(pack?.shared, book, findings, day, pack?.version.orEmpty())

    // ---- the simple API ------------------------------------------------------------------------------------------------------

    private fun rank(i: Item): Int = (when (i.source) { Source.RESEARCH, Source.EXPERIMENT -> 40; Source.OPEN -> 30; Source.APP -> 25; Source.JARVIS -> 20; Source.FINDINGS -> 0 }) +
        (when (i.gate) { Gate.PASS, Gate.FAIL -> 30; Gate.OPEN -> 20; Gate.NONE -> 0 }) + (if (i.conflict != null) 50 else 0) +
        (if (i.id.endsWith(".what")) 15 else 0)

    /** Best first: a conflict, then research with a decisive gate, then open questions, then the lessons; then the sample size. */
    fun ranked(items: List<Item>): List<Item> = items.sortedWith(compareByDescending<Item> { rank(it) }.thenByDescending { it.n ?: 0 }.thenBy { it.id })

    /**
     * What is known about [arm] and / or [topic] (and [inst], [execution] when given), best first, at most [max]: every source,
     * conflicts marked.
     */
    fun about(s: Snapshot, arm: String? = null, topic: String? = null, inst: String? = null, execution: Boolean? = null, max: Int = 8): List<Item> {
        val pool = when {
            arm != null -> s.byArm[arm].orEmpty() + (if (topic != null) s.byTopic[topic].orEmpty().filter { it.arm == null } else emptyList())
            topic != null -> s.byTopic[topic].orEmpty()
            else -> s.items
        }
        return ranked(pool.distinctBy { it.id }.filter { i ->
            (topic == null || topic in i.topics || i.arm == arm) && (inst == null || i.inst == null || i.inst.equals(inst, true)) &&
                (execution == null || i.execution == execution)
        }).take(max)
    }

    /** What is known about [arm], said in one line ([words]): the research's verdict, the lessons beside it, a conflict plainly. */
    data class ArmVerdict(val arm: String, val verdict: Verdict, val research: Item?, val app: List<Item>, val conflict: Conflict?) {
        fun words(): String {
            val head = research?.let { "${armName(arm)}: the research says it ${it.verdict.word} (${it.cite()})" } ?: "${armName(arm)}: no research verdict yet"
            val lessons = if (app.isEmpty()) "" else "; ${app.first().source.word}: ${app.first().text.trimEnd('.')}"
            return head + lessons + (conflict?.let { ". ${it.words().replaceFirstChar { c -> c.uppercase() }}" } ?: "") + "."
        }
    }

    /** The verdict on [arm]: the research's (its card first), the app lessons about its money, and a conflict when they disagree. */
    fun verdict(s: Snapshot, arm: String): ArmVerdict {
        val items = s.byArm[arm].orEmpty()
        val r = researchVerdictOf(items, arm)
        val app = ranked(items.filter { profitClaim(it) })
        return ArmVerdict(arm, r?.verdict ?: if (items.any { it.source == Source.OPEN }) Verdict.OPEN else Verdict.CONTEXT, r, app,
            app.firstNotNullOfOrNull { it.conflict } ?: r?.conflict)
    }

    // ---- execution ------------------------------------------------------------------------------------------------------------

    /** Slippage this many times the expected half-spread or more: "off the baseline" (flagged, never acted on). */
    const val FLAG_X = 2.0

    /** The index an option's trading symbol is on ("BANKNIFTY26OCT52000CE" -> BANKNIFTY), or null. */
    fun indexOf(symbol: String): String? {
        val s = symbol.uppercase(Locale.ENGLISH).substringAfter(':')
        return listOf("BANKNIFTY", "MIDCPNIFTY", "FINNIFTY", "NIFTY").firstOrNull { s.startsWith(it) }
    }

    /**
     * The expected cost of a fill for [symbol] at [minute] (IST minutes of the day): the research's real half-spread for its index
     * (h24), times the signal multiplier in the first 15 minutes and the last 15 (h24: "may be up to about 2x the calm level" -
     * an assumption, said as one) and away from the measured strikes ([strikesAway] over 2). [flagPct] is where a fill counts as
     * off the baseline ([FLAG_X]). Null for an index the research did not measure.
     */
    data class ExecBaseline(val inst: String, val minute: Int, val halfSpreadPct: Double, val multiplier: Double, val expectedPct: Double,
                            val flagPct: Double, val src: String, val assumed: Boolean, val roundTripRs: Double?) {
        fun words(): String = "expected about ${pct(expectedPct)} a fill for $inst at ${hhmm(minute)} (real half-spread ${pct(halfSpreadPct)}" +
            (if (multiplier > 1.0) " x${fmt(multiplier)} near the open, the close or away from the money - an assumption" else "") +
            "; ${src.substringAfterLast('/')})" + (roundTripRs?.let { "; an ATM round trip costs about Rs ${"%,.0f".format(Locale.ENGLISH, it)}" } ?: "")
    }

    fun executionBaseline(s: Snapshot, symbol: String, minute: Int, strikesAway: Int = 0): ExecBaseline? {
        val inst = indexOf(symbol) ?: symbol.uppercase(Locale.ENGLISH).takeIf { x -> s.baselines.spreads.any { it.inst == x } } ?: return null
        val sp = s.baselines.spreads.firstOrNull { it.inst == inst } ?: return null
        val edge = minute < 9 * 60 + 30 || minute >= 15 * 60 + 15
        val far = kotlin.math.abs(strikesAway) > 2
        val mult = if (edge || far) s.baselines.signalX else 1.0
        val exp = sp.halfSpreadPct * mult
        return ExecBaseline(inst, minute, sp.halfSpreadPct, mult, exp, exp * FLAG_X, sp.src, mult > 1.0, s.baselines.roundTrips[inst])
    }

    /** Is a fill's slippage [slipPct] (% of its intended price, a cost positive) off the baseline [b]? */
    fun offBaseline(b: ExecBaseline, slipPct: Double): Boolean = slipPct.isFinite() && slipPct > b.flagPct

    private fun pct(x: Double) = String.format(Locale.ENGLISH, "%.2f%%", x)
    private fun fmt(x: Double) = if (x == Math.floor(x)) x.toInt().toString() else String.format(Locale.ENGLISH, "%.1f", x)
    private fun hhmm(m: Int) = String.format(Locale.ENGLISH, "%02d:%02d", m / 60, m % 60)

    /**
     * One day's fills judged against the baselines, as Jarvis's lesson evidence ([AgentLessons.ExecResult]): per strategy and index,
     * how many fills, how many off the baseline. [fills]: (day, strategy, symbol, minute, slippage % of the intended price).
     */
    data class FillCheck(val day: LocalDate, val strategy: String, val symbol: String, val minute: Int, val slipPct: Double)

    fun execResults(s: Snapshot, fills: List<FillCheck>, kind: String): List<AgentLessons.ExecResult> {
        val out = HashMap<Triple<LocalDate, String, String>, IntArray>()
        for (f in fills) {
            val b = executionBaseline(s, f.symbol, f.minute) ?: continue
            val k = Triple(f.day, f.strategy, b.inst)
            val a = out.getOrPut(k) { IntArray(2) }
            a[0]++; if (offBaseline(b, f.slipPct)) a[1]++
        }
        return out.map { (k, a) ->
            AgentLessons.ExecResult(k.first, "$kind:${k.second}:${k.third}".lowercase(Locale.ENGLISH),
                "${k.second}'s ${if (kind == "spread") "signal-time spread" else "fills"} on ${k.third}", a[0], a[1],
                listOf(kind, k.third.lowercase(Locale.ENGLISH), k.second.lowercase(Locale.ENGLISH)))
        }.sortedWith(compareBy({ it.day }, { it.key }))
    }

    // ---- the readers, one per agent ----------------------------------------------------------------------------------------

    /** Each trade-manager specialist's subject in the research. */
    val SPEC_TOPICS: Map<String, String> = mapOf(
        "flow" to "order-flow", "delta" to "order-flow", "absorption" to "order-flow", "traps" to "traps", "vwap" to "vwap",
        "volume" to "volume", "profile" to "order-flow", "oi" to "oi", "vix" to "vix", "momentum" to "momentum", "price" to "trade-manager",
        "time" to "theta", "gamma" to "gex", "news" to "news", "bots" to "strategy", "data" to "data-health",
    )

    /**
     * The research prior a specialist's vote is cited with ("research on traps: still open - ..."), or null when the research has
     * nothing on its subject. The open question first (an unproven reader is said as unproven), else the best decisive item.
     */
    fun specialistNote(s: Snapshot, specialist: String, arm: String? = null): String? {
        val topic = SPEC_TOPICS[specialist] ?: return null
        val pool = s.byTopic[topic].orEmpty().filter { !it.source.app && it.source != Source.FINDINGS }
        val open = pool.firstOrNull { it.gate == Gate.OPEN }
        val best = open ?: ranked(pool.filter { arm == null || it.arm == null || it.arm == arm }.filter { it.gate != Gate.NONE }).firstOrNull() ?: return null
        val short = best.text.trim().trimEnd('.').let { if (it.length > 110) it.take(107).trimEnd() + "..." else it }
        return "research on ${topic.replace('-', ' ')}: ${best.verdict.word} - $short (${best.doc})"
    }

    /** Each order-review flaw's subject in the research ([OrderReview.Category.key]). */
    private val FLAW_TOPICS: Map<String, List<String>> = mapOf(
        "chg" to listOf("reentry", "costs"), "reent" to listOf("reentry"), "slp" to listOf("costs"), "theta" to listOf("theta"),
        "gb" to listOf("profit-lock"), "stt" to listOf("target", "stop"), "early" to listOf("profit-lock", "target"), "risk" to listOf("stop"),
        "late" to listOf("stop"),
    )

    /** The finding an order-review flaw cites ("R14: re-entries made about Rs 0 before charges ..."), or null. Read only. */
    fun flawCite(s: Snapshot, category: String, strategy: String? = null): String? {
        if (category == "slp") return s.baselines.spreads.firstOrNull()?.let { "research: real option half-spread ${s.baselines.spreads.joinToString(", ") { sp -> "${sp.inst} ${pct(sp.halfSpreadPct)}" }}, " +
            "up to x${fmt(s.baselines.signalX)} at a signal (${it.src.substringAfterLast('/')})" }
        val topics = FLAW_TOPICS[category] ?: return null
        val arm = armOf(strategy)
        val pool = topics.flatMap { s.byTopic[it].orEmpty() }.filter { (it.source == Source.RESEARCH || it.source == Source.EXPERIMENT) && it.gate != Gate.NONE }
            .distinctBy { it.id }
        val best = ranked(pool.filter { arm != null && it.arm == arm }).firstOrNull() ?: ranked(pool).firstOrNull() ?: return null
        val short = best.text.trim().trimEnd('.').let { if (it.length > 150) it.take(147).trimEnd() + "..." else it }
        return "research: $short (${best.doc})"
    }

    /**
     * The research's verdict on order flow, said wherever the flow is logged or asked about: still unproven while the S7 re-test
     * is open, with the tested failures beside it.
     */
    fun flowVerdict(s: Snapshot): String? {
        val pool = (s.byTopic["order-flow"].orEmpty() + s.byTopic["traps"].orEmpty()).filter { !it.source.app && it.source != Source.FINDINGS }.distinctBy { it.id }
        if (pool.isEmpty()) return null
        val open = pool.firstOrNull { it.gate == Gate.OPEN }
        val failed = pool.count { it.gate == Gate.FAIL }
        return "Research verdict on order flow: " + (if (open != null) "still unproven (${open.doc} ${open.id.substringAfter(':').substringBefore('-').uppercase(Locale.ENGLISH)}: " +
            open.text.substringBefore(':').trimEnd('.').lowercase(Locale.ENGLISH) + ")" else "not proven") +
            (if (failed > 0) "; $failed tested order-flow rule${if (failed == 1) "" else "s"} did not pay" else "") + ". Shadow log only."
    }

    /** The study's setups ([Study.KEYS]) and the subject each asks about in the research. */
    private val STUDY_TOPICS: Map<String, String> = mapOf(
        "f15up" to "direction", "f15down" to "direction", "bigup" to "momentum", "bigdown" to "momentum", "gapup" to "gap", "gapdown" to "gap",
        "narrowor" to "orb", "wideor" to "orb", "closehigh" to "overnight", "closelow" to "overnight",
    ) + listOf("monday", "tuesday", "wednesday", "thursday", "friday").associate { "dow-$it" to "day-of-week" }

    /**
     * The study's plan: [order] the setups to count (the ones touching an open question first), [skipped] the ones the research has
     * already settled with why (a decisive test on the same subject - its topic and its words - and no open question on it), and
     * [open] the open questions to say. Only what the study counts changes; it never trades.
     */
    data class StudyPlan(val order: List<String>, val skipped: Map<String, String>, val open: List<String>)

    /** The research items that settle a study subject: a held or failed test on its topic whose words name it too. */
    private val SETTLES: Map<String, Regex> = mapOf(
        "direction" to Regex("(?i)direction (15|30|60)|not predictable|trend vs range day from the first"),
        "day-of-week" to Regex("(?i)(day of (the )?week|weekday)s? (effect|filter|does|did|made)"),
    )

    fun studyPlan(s: Snapshot, keys: List<String>): StudyPlan {
        val openItems = s.items.filter { it.gate == Gate.OPEN && !it.source.app }
        val openTopics = openItems.flatMap { it.topics }.toSet()
        val skipped = LinkedHashMap<String, String>()
        for (k in keys) {
            val topic = STUDY_TOPICS[k] ?: continue
            if (topic in openTopics) continue
            val rx = SETTLES[topic] ?: continue
            val settledBy = ranked(s.byTopic[topic].orEmpty().filter {
                (it.source == Source.RESEARCH || it.source == Source.EXPERIMENT) && (it.gate == Gate.PASS || it.gate == Gate.FAIL) && rx.containsMatchIn(it.text)
            }).firstOrNull() ?: continue
            skipped[k] = "settled by the research: ${settledBy.text.trim().trimEnd('.').take(120)} (${settledBy.doc})"
        }
        val left = keys.filter { it !in skipped }
        val order = left.sortedBy { if (STUDY_TOPICS[it] in openTopics) 0 else 1 }
        // The curated open questions first, then the experiment log's still-open entries.
        val open = openItems.filter { it.source == Source.OPEN } + ranked(openItems.filter { it.source != Source.OPEN })
        return StudyPlan(order, skipped, open.map { it.text.trim() }.take(5))
    }

    /** The forge's entry blocks ([ShadowSim.Entry]) and the strategy or subject each re-tests. */
    private val FORGE_SUBJECT: Map<String, Pair<String?, String?>> = mapOf(
        "ORB_BREAK" to ("orb" to null), "ORB_SWEEP" to ("orb_sweep" to null), "RANGE_FADE" to ("range_fade" to null),
        "VIX_DIVERGENCE" to ("vixdiv" to null), "OI_BUILDUP" to (null to "oi"), "VWAP_RECLAIM" to (null to "vwap"),
        "GAP_FADE" to (null to "gap"), "TIME_OF_DAY" to (null to "direction"), "PREV_DAY_BREAK" to (null to "pattern"),
        "SQUEEZE_BREAK" to (null to "pattern"), "LEVEL_SWEEP" to (null to null),
    )

    private fun forgeVerdict(s: Snapshot, entry: String): Item? {
        val (arm, topic) = FORGE_SUBJECT[entry] ?: return null
        if (arm == LIQUIDITY_FROZEN) return null
        if (arm != null) return researchVerdictOf(s.byArm[arm].orEmpty(), arm)
        if (topic == null) return null
        val pool = s.byTopic[topic].orEmpty().filter { it.source == Source.RESEARCH || it.source == Source.EXPERIMENT || it.source == Source.OPEN }
        if (pool.any { it.gate == Gate.OPEN }) return pool.first { it.gate == Gate.OPEN }
        val fails = pool.count { it.gate == Gate.FAIL }; val passes = pool.count { it.gate == Gate.PASS }
        return when {
            fails >= 3 && passes == 0 -> ranked(pool.filter { it.gate == Gate.FAIL }).first()
            passes > 0 && passes >= fails -> ranked(pool.filter { it.gate == Gate.PASS }).first()
            else -> null
        }
    }

    /**
     * Why the forge should not test a candidate again ("the research already rejected plain ORB break: ..."), or null. Only the
     * PLAIN form of a rejected idea (the entry with no filter) is skipped: a filter makes it a new idea, still tested.
     */
    fun forgeAvoid(s: Snapshot, entry: String, filters: Int): String? {
        if (filters > 0) return null
        val v = forgeVerdict(s, entry) ?: return null
        if (v.verdict != Verdict.FAILS) return null
        return "the research already rejected it: ${v.text.trim().trimEnd('.').take(110)} (${v.doc})"
    }

    /**
     * The forge's research prior per block key ("entry:ORB_BREAK" -> (successes, failures) added to its own record): a failed idea
     * one failure, an open question or a held one a success - a seed, not a gate (the exploration floor stays; the gates never read it).
     */
    fun forgePrior(s: Snapshot): Map<String, Pair<Double, Double>> = FORGE_SUBJECT.keys.mapNotNull { e ->
        val v = forgeVerdict(s, e) ?: return@mapNotNull null
        "entry:$e" to when (v.gate) { Gate.FAIL -> 0.0 to 1.0; Gate.PASS, Gate.OPEN -> 1.0 to 0.0; Gate.NONE -> return@mapNotNull null }
    }.toMap()

    /**
     * Why the strategy lab should not test this directed variant ([VariantLab.Seed]), or null. Only what the research tested and
     * rejected: more ORB re-entries a side (R14 adopted the cap of 1) and ORB's "let it run" target (R14: no effect). Never for
     * Liquidity (frozen: read and explained only).
     */
    fun variantRejected(s: Snapshot, strategy: String, param: String, dir: Int): String? {
        val st = strategy.lowercase(Locale.ENGLISH)
        if (st.startsWith(LIQUIDITY_FROZEN)) return null
        val r14 = s.items.firstOrNull { it.id == "exp:r14" } ?: return null
        val orb = st == "orb" || st == "orb_fresh"
        return when {
            orb && param == ArmParams.SIDE_CAP && dir > 0 -> "the research rejected more ORB re-entries: capping them at 1 a side was adopted (${r14.doc})"
            orb && param == ArmParams.TARGET_PTS && dir > 0 -> "the research tested letting ORB run (no +40 target): no effect (${r14.doc})"
            else -> null
        }
    }

    /**
     * Jarvis's merged answer for [question] (its strategy, else its topic): the research's lines and the app's lessons, each with
     * its source, and a conflict said plainly. [locked]: the lessons' words are left out (they carry the bots' results) - and so
     * is a research item's conflict note (13 Oct review: it quotes the lesson's verdict and n), so a locked phone says the research only.
     */
    fun answer(s: Snapshot, question: String, locked: Boolean, max: Int = 4): List<String> {
        val arm = armOf(question)
        val topic = TOPIC_WORDS.entries.firstOrNull { (w, _) -> Regex("\\b$w\\b").containsMatchIn(question.lowercase(Locale.ENGLISH)) }?.value
        if (arm == null && topic == null) return emptyList()
        val items = about(s, arm = arm, topic = if (arm == null) topic else null, max = 20).filter { !locked || !it.source.app }
        val out = ArrayList<String>()
        if (arm != null) verdict(s, arm).takeIf { it.research != null || it.app.isNotEmpty() }?.let { v ->
            out += if (locked) v.research?.let { "${armName(arm)}: the research says it ${it.verdict.word} (${it.cite()})." } ?: return@let else v.words()
        }
        for (i in items) {
            if (out.size >= max) break
            if (arm != null && (i.id == verdict(s, arm).research?.id)) continue
            if (i.source == Source.FINDINGS) continue
            out += "${i.source.word.replaceFirstChar { it.uppercase() }}: ${(if (locked) i.copy(conflict = null) else i).line(160)}"
        }
        return out
    }

    private val TOPIC_WORDS = linkedMapOf("order flow" to "order-flow", "trap" to "traps", "slippage" to "costs", "spread" to "costs",
        "charges" to "costs", "re-?entr" to "reentry", "vix" to "vix", "expiry" to "expiry", "gap" to "gap", "vwap" to "vwap", "oi" to "oi",
        "trade manager" to "trade-manager", "direction" to "direction", "candles?" to "candles")

    // ---- the shadow record (never acts) ----------------------------------------------------------------------------------------

    /**
     * One shadow rule's night: the trades it WOULD have skipped. Never applied - counted after the close from the day's closed trips,
     * so nothing on the order path is involved. [rs]: their rupees (Boss's money: kept only in the encrypted part of the bundle).
     */
    data class ShadowSkip(val rule: String, val arm: String, val trades: Int, val wouldSkip: Int, val rs: Double, val why: String)

    /**
     * The shadow rule "the research says this strategy does not pay": [day]'s closed trips of each strategy whose research verdict
     * is FAILS, counted as would-be skips. Liquidity is never part of it (frozen). Pure.
     */
    fun shadowSkips(s: Snapshot, trips: List<Insights.Trip>, day: LocalDate): List<ShadowSkip> {
        val today = trips.filter { it.closedAt.toLocalDate() == day }
        val byArm = today.groupBy { armOf(ArmOwners.arm(it.owner)) }
        return byArm.mapNotNull { (arm, ts) ->
            if (arm == null || arm == LIQUIDITY_FROZEN) return@mapNotNull null
            val v = verdict(s, arm)
            if (v.verdict != Verdict.FAILS || v.research == null) return@mapNotNull null
            ShadowSkip("research verdict: does not pay", arm, ts.size, ts.size, ts.sumOf { it.net }, "${armName(arm)}: the research says it ${v.verdict.word} (${v.research.doc})")
        }.sortedBy { it.arm }
    }
}
