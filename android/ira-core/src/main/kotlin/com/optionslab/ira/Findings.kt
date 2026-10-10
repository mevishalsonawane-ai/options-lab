package com.optionslab.ira

import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs

/**
 * The findings bus (Boss, 10 Oct: "they should communicate with each other and share their findings"): a blackboard on top of
 * the market brain. Any worker or strategy posts a typed [Finding] - who, what, on which instrument, which way, at what level,
 * how strong (0-100), for how long it stands, the numbers behind it - and any other reads them ([Bus.recent]) or subscribes
 * by instrument and kind ([Bus.subscribe]). Liquidity "sweep taken at 55,100", ORB "range broken up", the order flow "sellers
 * absorbing at 55,080", the trap guard "pull detected", the move recorder "big move up started 10:31", the self-review "ORB
 * drifting", self-healing "stream stale", any arm "my stop hit" - all in one place.
 *
 *  - In memory only on the hot paths: a fixed ring ([Bus.capacity]) written with one atomic step per post and read without a
 *    lock; findings expire by their own time-to-live. Persisted for research lazily: [Bus.drainLines] hands the lines over
 *    for a coalesced write to a daily log (the app's, never per tick).
 *  - [consensus] per index: the findings agreeing and conflicting in the last minutes (3 bullish, 1 bearish), shown on Home
 *    and logged beside every strategy signal so the record can say later whether it helped.
 *  - [Reactions]: what one worker's findings may make another do - ONLY ever lowering risk (a skipped entry; exits never ask),
 *    SHADOW (logged, nothing done) by default and switched to ACT per strategy by Boss, except "the data is unreliable",
 *    which acts by default (data safety).
 *
 * Nothing here places, changes, enlarges or closes an order. Pure apart from the given clock values.
 */
object Findings {
    enum class Kind(val words: String) {
        SWEEP("liquidity sweep"), BREAK("range break"), ABSORPTION("absorption"), PULL("pulled orders"), STOP_HUNT("stop hunt"),
        BIG_MOVE("big move"), STRONG_CLOSE("strong close"), VIX_SPIKE("VIX spike"), NEWS_WINDOW("news window"),
        ENTRY("entry"), STOP_HIT("stop hit"), TARGET_HIT("target hit"), DRIFT("drifting from its backtest"),
        DATA_UNRELIABLE("data unreliable"), DATA_HEALTH("data health"), OTHER("note"),
    }

    /** Any instrument (a market-wide finding: the data's health, a news window for all). */
    const val ALL = "*"

    /** One finding. [direction]: +1 bullish, -1 bearish, 0 none. [strength] 0-100. [seq] set by the bus. */
    data class Finding(
        val who: String, val kind: Kind, val instrument: String, val direction: Int = 0, val level: Double? = null,
        val strength: Int = 50, val atMs: Long, val ttlMs: Long = DEFAULT_TTL_MS, val evidence: Map<String, Double> = emptyMap(),
        val words: String = "", val seq: Long = -1,
    ) {
        /** Standing at [nowMs]: posted no more than its TTL ago (a clock a second behind still counts it). */
        fun alive(nowMs: Long): Boolean = nowMs - atMs in -1_000L..ttlMs
        fun on(name: String): Boolean = instrument == ALL || instrument.equals(name, ignoreCase = true)
    }

    const val DEFAULT_TTL_MS = 15 * 60_000L

    /** A subscription: [instruments] (empty: all) and [kinds] (empty: all); [onFinding] runs on the poster's thread - keep it cheap. */
    class Sub(val instruments: Set<String>, val kinds: Set<Kind>, val onFinding: (Finding) -> Unit) {
        fun wants(f: Finding): Boolean = (kinds.isEmpty() || f.kind in kinds) &&
            (instruments.isEmpty() || f.instrument == ALL || instruments.any { it.equals(f.instrument, ignoreCase = true) })
    }

    class Bus(val capacity: Int = 512, private val pendingCap: Int = 4_000) {
        private val ring = AtomicReferenceArray<Finding?>(capacity)
        private val next = AtomicLong(0)
        private val subs = CopyOnWriteArrayList<Sub>()
        private val pending = ConcurrentLinkedQueue<String>()
        private val pendingSize = AtomicInteger(0)

        /** Findings posted so far (since the start). */
        val posted: Long get() = next.get()

        /**
         * [f] on the board (strength kept within 0-100, TTL at least a second): one atomic step, never blocks; the oldest
         * finding gives way when the ring is full. Subscribers are told on this thread (each in its own try). Returns it with
         * its sequence number.
         */
        fun post(f: Finding): Finding {
            val seq = next.getAndIncrement()
            val g = f.copy(seq = seq, strength = f.strength.coerceIn(0, 100), ttlMs = f.ttlMs.coerceAtLeast(1_000L))
            ring.set((seq % capacity).toInt(), g)
            if (pendingSize.incrementAndGet() > pendingCap) { if (pending.poll() != null) pendingSize.decrementAndGet() }
            pending.add(line(g))
            for (s in subs) if (s.wants(g)) runCatching { s.onFinding(g) }
            return g
        }

        fun subscribe(s: Sub): Sub { subs += s; return s }
        fun unsubscribe(s: Sub) { subs -= s }

        /**
         * The standing findings at [nowMs] (newest first), optionally on [instrument] (market-wide ones included), of [kinds],
         * posted within [withinMs]. Lock-free: each slot is read once; a slot overwritten meanwhile is read as its newer finding.
         */
        fun recent(nowMs: Long, instrument: String? = null, kinds: Set<Kind> = emptySet(), withinMs: Long = Long.MAX_VALUE): List<Finding> {
            val hi = next.get()
            val lo = maxOf(0L, hi - capacity)
            val out = ArrayList<Finding>()
            for (i in 0 until capacity) {
                val f = ring.get(i) ?: continue
                if (f.seq < lo || f.seq >= hi) continue
                if (!f.alive(nowMs) || nowMs - f.atMs > withinMs) continue
                if (instrument != null && !f.on(instrument)) continue
                if (kinds.isNotEmpty() && f.kind !in kinds) continue
                out += f
            }
            return out.sortedByDescending { it.seq }
        }

        /** The lines posted since the last call (for the daily log), oldest first. */
        fun drainLines(): List<String> {
            val out = ArrayList<String>()
            while (true) { val l = pending.poll() ?: break; pendingSize.decrementAndGet(); out += l }
            return out
        }

        /** TEST / a new day. */
        fun clear() {
            for (i in 0 until capacity) ring.set(i, null)
            pending.clear(); pendingSize.set(0)
        }
    }

    // ---- the log line --------------------------------------------------------------------------------------------------

    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').replace(',', ';').take(160)
    private fun num(x: Double) = if (x == Math.rint(x) && abs(x) < 1e12) x.toLong().toString() else String.format(Locale.ENGLISH, "%.4f", x)

    /** "F|seq|atMs|who|kind|instrument|dir|level|strength|ttlMs|k=v;k=v|words" (no account data, no token or URL). */
    fun line(f: Finding): String = listOf("F", f.seq.toString(), f.atMs.toString(), clean(f.who), f.kind.name, clean(f.instrument),
        f.direction.toString(), f.level?.let { num(it) } ?: "", f.strength.toString(), f.ttlMs.toString(),
        f.evidence.entries.joinToString(";") { "${clean(it.key).replace('=', ' ')}=${num(it.value)}" }, clean(f.words)).joinToString("|")

    fun parse(line: String): Finding? = runCatching {
        val p = line.split('|')
        if (p.size < 12 || p[0] != "F") return null
        val ev = p[10].split(';').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').toDouble() }
        Finding(p[3], Kind.valueOf(p[4]), p[5], p[6].toInt(), p[7].toDoubleOrNull(), p[8].toInt(), p[2].toLong(), p[9].toLong(), ev, p[11], p[1].toLong())
    }.getOrNull()

    // ---- consensus -----------------------------------------------------------------------------------------------------

    const val CONSENSUS_MS = 15 * 60_000L

    /** [index]'s findings with a direction in the window: how many each way, their strengths summed, and the findings. */
    data class Consensus(val index: String, val bullish: Int, val bearish: Int, val bullStrength: Int, val bearStrength: Int, val findings: List<Finding>) {
        val net: Int get() = bullish - bearish
        val lean: Int get() = when { bullStrength > bearStrength -> 1; bearStrength > bullStrength -> -1; else -> 0 }
        fun words(): String = if (bullish + bearish == 0) "no directional findings" else
            "$bullish bullish, $bearish bearish" + when (lean) { 1 -> " (leaning bullish)"; -1 -> " (leaning bearish)"; else -> " (split)" }
    }

    /** [index]'s consensus from [all] (standing ones) over the last [windowMs] at [nowMs]: directional findings on it only. */
    fun consensus(all: List<Finding>, index: String, nowMs: Long, windowMs: Long = CONSENSUS_MS): Consensus {
        val mine = all.filter { it.alive(nowMs) && nowMs - it.atMs <= windowMs && it.direction != 0 && it.instrument.equals(index, ignoreCase = true) }
        val up = mine.filter { it.direction > 0 }
        val dn = mine.filter { it.direction < 0 }
        return Consensus(index.uppercase(Locale.ENGLISH), up.size, dn.size, up.sumOf { it.strength }, dn.sumOf { it.strength }, mine)
    }

    // ---- reactions -----------------------------------------------------------------------------------------------------

    enum class Reaction(val key: String, val words: String, val default: Mode) {
        STOPS_CLUSTER("stops", "two strategies' stops hit on the index within 10 minutes: new entries there wait 15 minutes", Mode.SHADOW),
        OPPOSITE_CONSENSUS("consensus", "three or more strong findings against the entry's direction: the entry is skipped", Mode.SHADOW),
        DATA_UNRELIABLE("data", "self-healing says the data is unreliable: every new entry waits", Mode.ACT),
    }

    enum class Mode { OFF, SHADOW, ACT }

    const val CLUSTER_MS = 10 * 60_000L
    const val CLUSTER_PAUSE_MS = 15 * 60_000L
    const val OPPOSITE_MIN = 3
    const val OPPOSITE_STRENGTH = 60
    const val OPPOSITE_MS = 10 * 60_000L

    /** The modes: [global] (else each reaction's default), overridden per strategy key by [perStrategy]. */
    data class Policy(val global: Map<Reaction, Mode> = emptyMap(), val perStrategy: Map<String, Map<Reaction, Mode>> = emptyMap()) {
        fun mode(strategy: String, r: Reaction): Mode = perStrategy[strategy]?.get(r) ?: global[r] ?: r.default
    }

    /** "key:stops=ACT,consensus=SHADOW;*:data=ACT" ("*" for the global modes). Bad parts left out. */
    fun encode(p: Policy): String {
        val parts = ArrayList<String>()
        if (p.global.isNotEmpty()) parts += "*:" + p.global.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.key}=${it.value.name}" }
        for ((k, m) in p.perStrategy.toSortedMap()) if (m.isNotEmpty())
            parts += "${k.replace(';', ' ').replace(',', ' ')}:" + m.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.key}=${it.value.name}" }
        return parts.joinToString(";")
    }

    fun decode(s: String?): Policy {
        if (s.isNullOrBlank()) return Policy()
        var global: Map<Reaction, Mode> = emptyMap()
        val per = HashMap<String, Map<Reaction, Mode>>()
        for (part in s.split(';')) {
            val who = part.substringBeforeLast(':', "").trim()
            if (who.isEmpty()) continue
            val m = part.substringAfterLast(':').split(',').mapNotNull { kv ->
                val r = Reaction.entries.firstOrNull { it.key == kv.substringBefore('=').trim() } ?: return@mapNotNull null
                val mode = runCatching { Mode.valueOf(kv.substringAfter('=').trim()) }.getOrNull() ?: return@mapNotNull null
                r to mode
            }.toMap()
            if (m.isEmpty()) continue
            if (who == "*") global = m else per[who] = m
        }
        return Policy(global, per)
    }

    /** One reaction that applies: which, and its detail with the numbers. */
    data class Hit(val reaction: Reaction, val detail: String)

    /** The reactions' answer for one entry: [block] when one in ACT applies ([why]); [shadow] the ones in SHADOW. */
    data class Verdict(val block: Boolean, val why: String?, val acted: List<Hit> = emptyList(), val shadow: List<Hit> = emptyList())

    /** The reactions that apply to a new entry by [strategy] on [underlying] leaning [side] (+1 / -1; 0 unknown) at [nowMs]. */
    fun hits(all: List<Finding>, strategy: String, underlying: String?, side: Int, nowMs: Long): List<Hit> {
        val out = ArrayList<Hit>()
        val alive = all.filter { it.alive(nowMs) }
        alive.firstOrNull { it.kind == Kind.DATA_UNRELIABLE && (underlying == null || it.on(underlying)) }?.let {
            out += Hit(Reaction.DATA_UNRELIABLE, "data unreliable (${it.who}: ${it.words.ifEmpty { "no reliable prices" }})")
        }
        if (underlying != null) {
            val stops = alive.filter { it.kind == Kind.STOP_HIT && it.instrument.equals(underlying, ignoreCase = true) }.sortedBy { it.atMs }
            var until: Long? = null
            var pair: Pair<Finding, Finding>? = null
            for ((i, b) in stops.withIndex()) {
                val a = stops.subList(0, i).lastOrNull { it.who != b.who && b.atMs - it.atMs <= CLUSTER_MS } ?: continue
                until = b.atMs + CLUSTER_PAUSE_MS; pair = a to b
            }
            if (until != null && nowMs <= until && pair != null)
                out += Hit(Reaction.STOPS_CLUSTER, "stops hit on ${underlying.uppercase(Locale.ENGLISH)} by ${pair.first.who} and ${pair.second.who} " +
                    "${(pair.second.atMs - pair.first.atMs) / 60_000} min apart; new entries wait ${(until - nowMs + 59_999) / 60_000} min more")
            if (side != 0) {
                val against = alive.filter { it.instrument.equals(underlying, ignoreCase = true) && it.direction == -side &&
                    it.strength >= OPPOSITE_STRENGTH && nowMs - it.atMs <= OPPOSITE_MS && it.who != strategy }
                if (against.size >= OPPOSITE_MIN)
                    out += Hit(Reaction.OPPOSITE_CONSENSUS, "${against.size} strong ${if (side > 0) "bearish" else "bullish"} findings against it " +
                        "(" + against.take(3).joinToString(", ") { it.who } + ")")
            }
        }
        return out
    }

    /** [hits] under Boss's [policy] for [strategy]: ACT blocks, SHADOW is noted, OFF ignored. */
    fun react(all: List<Finding>, policy: Policy, strategy: String, underlying: String?, side: Int, nowMs: Long): Verdict {
        val h = hits(all, strategy, underlying, side, nowMs)
        val acted = h.filter { policy.mode(strategy, it.reaction) == Mode.ACT }
        val shadow = h.filter { policy.mode(strategy, it.reaction) == Mode.SHADOW }
        return Verdict(acted.isNotEmpty(), acted.firstOrNull()?.let { "paused: ${it.detail}" }, acted, shadow)
    }

    // ---- words ---------------------------------------------------------------------------------------------------------

    /** "10:31 Liquidity 15+5: sweep taken at 55,100 (bullish, 70)". */
    fun said(f: Finding, hhmm: (Long) -> String): String =
        "${hhmm(f.atMs)} ${f.who}: ${f.words.ifEmpty { f.kind.words }}" +
            (if (f.direction != 0 || f.strength != 50) " (" + listOfNotNull(
                when (f.direction) { 1 -> "bullish"; -1 -> "bearish"; else -> null }, "strength ${f.strength}").joinToString(", ") + ")" else "")

    private val ASK_BOTS = Regex(" (bots?|strategies|strategy|arms?|workers?|findings?) ")
    private val ASK_SAY = Regex(" (saying|say about|think about|thinking about|thinking on|seeing on|seeing in|finding on|findings|consensus|agree on|agree about) ")

    /** "what are the bots saying about banknifty?" - the index named, or "" when none is (all of them); null: not this question. */
    fun asked(text: String): String? {
        val t = " " + text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (!ASK_BOTS.containsMatchIn(t) || !ASK_SAY.containsMatchIn(t)) return null
        if (Regex(" (backtest|vs|versus|doing) ").containsMatchIn(t) && !t.contains(" saying ")) return null
        return when {
            Regex(" (bank ?nifty|banknifty|bnf) ").containsMatchIn(t) -> "BANKNIFTY"
            Regex(" (fin ?nifty|finnifty) ").containsMatchIn(t) -> "FINNIFTY"
            Regex(" (midcp ?nifty|midcap nifty|midcpnifty) ").containsMatchIn(t) -> "MIDCPNIFTY"
            Regex(" nifty ").containsMatchIn(t) -> "NIFTY"
            Regex(" (crude|crudeoil) ").containsMatchIn(t) -> "CRUDEOIL"
            Regex(" (natural gas|natgas|naturalgas) ").containsMatchIn(t) -> "NATURALGAS"
            Regex(" (silver|silverm) ").containsMatchIn(t) -> "SILVERM"
            else -> ""
        }
    }

    /** Jarvis's answer: the consensus, then the newest findings (at most [n]), in plain words. */
    fun answer(index: String, all: List<Finding>, nowMs: Long, hhmm: (Long) -> String, n: Int = 6): String {
        val name = index.ifEmpty { "the market" }
        val mine = if (index.isEmpty()) all.filter { it.alive(nowMs) } else all.filter { it.alive(nowMs) && it.on(index) }
        if (mine.isEmpty()) return "Nothing from the bots on $name in the last few minutes, Boss: no finding is standing."
        val head = if (index.isEmpty()) "${mine.size} finding${if (mine.size == 1) "" else "s"} standing."
            else "On $index the bots say: ${consensus(mine, index, nowMs).words()} in the last ${CONSENSUS_MS / 60_000} minutes."
        val list = mine.take(n).joinToString("\n") { "• " + said(it, hhmm) }
        return "$head\n$list\nFindings only: they skip entries only where you switched a reaction to act; exits never wait."
    }
}

/**
 * The one answer every new entry asks (part 3 and the findings' reactions together): the brain's global pause by Boss's
 * policy, then the findings' reactions by strategy. Lower risk only: it can say "skip this entry", never "enter", "add" or
 * "hold"; an exit never asks it. Pure.
 */
object EntryBrake {
    /** [shadowKeys]: the causes noted in SHADOW ([MarketBrain.Cause.key]s and [Findings.Reaction.key]s), for the record per cause. */
    data class Decision(val block: Boolean, val why: String?, val shadow: List<String>, val consensus: Findings.Consensus?,
                        val shadowKeys: List<String> = emptyList())

    fun decide(
        ctx: MarketBrain.Context, brain: MarketBrain.Policy, findings: List<Findings.Finding>, reactions: Findings.Policy,
        strategy: String, underlying: String?, side: Int, nowMs: Long,
    ): Decision {
        val b = MarketBrain.decide(ctx, brain, underlying, nowMs)
        val r = Findings.react(findings, reactions, strategy, underlying, side, nowMs)
        val shadow = b.shadow.map { "brain: $it" } + r.shadow.map { "${it.reaction.key}: ${it.detail}" }
        val why = b.why ?: r.why
        val cons = underlying?.let { Findings.consensus(findings, it, nowMs) }
        return Decision(b.block || r.block, why, shadow, cons, b.shadowCauses.map { it.key } + r.shadow.map { it.reaction.key })
    }
}
