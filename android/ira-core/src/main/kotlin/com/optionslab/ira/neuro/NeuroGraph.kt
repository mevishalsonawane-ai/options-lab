package com.optionslab.ira.neuro

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.time.LocalDate

/** What a node stands for. */
enum class NodeType { INDEX, STOCK, BANK, SECTOR, EXPIRY, DAY, TIME, REGIME, EVENT }

/** How two nodes are related. */
enum class EdgeType {
    /** A company is in an index (the dated static list - structural, never learned). */
    CONSTITUENT_OF,
    /** A company is in a sector (static list - structural). */
    IN_SECTOR,
    /** Daily returns move together: weight = Pearson r. */
    CORRELATES,
    /** One-minute returns of `from` lead `to`'s by [Edge.lag] minutes: weight = the mean day's lagged correlation. */
    LEADS,
    /** Event `from` on a session, event `to` in the same session after the open ([Edge.lag] 0) or the next [Edge.lag] sessions: weight = P(to | from). */
    PRECEDES,
    /** An event in a context (weekday, expiry day, regime, time of day): weight = P(event | context), or the share in a time bucket. */
    OCCURS_IN,
    /** A company moved the same way as its index on the index's big days: weight = that rate, [Edge.extra] = its big-day beta. */
    CONTRIBUTES,
}

/** How far an edge can be trusted. */
enum class Status {
    /** A dated static fact (index membership, sector). */
    STRUCTURAL,
    /** Learned on the older 70% and held on the newest 30% (see [Stats]). */
    PROVEN,
    /** Seen in the data, not confirmed out of sample: never said as a fact. */
    HYPOTHESIS,
}

data class Node(val id: String, val type: NodeType, val label: String, val count: Int = 0, val rate: Double = Double.NaN)

/**
 * One relation. [weight] is the edge type's measure (see [EdgeType]); [base] the base rate it is compared with and [lift]
 * weight / base (NaN where none); [lo]..[hi] the 95% interval of the fitted value; [samples] the fit part's sample count,
 * [testSamples] / [testValue] / [testBase] the newest 30%'s; [lag] minutes (LEADS) or sessions (PRECEDES); [extra] a type's
 * second figure (CONTRIBUTES: big-day beta).
 */
data class Edge(
    val type: EdgeType, val from: String, val to: String, val status: Status,
    val weight: Double, val base: Double = Double.NaN, val lift: Double = Double.NaN,
    val lo: Double = Double.NaN, val hi: Double = Double.NaN,
    val samples: Int = 0, val testSamples: Int = 0, val testValue: Double = Double.NaN, val testBase: Double = Double.NaN,
    val lag: Int = 0, val extra: Double = Double.NaN,
) {
    val proven: Boolean get() = status == Status.PROVEN
    val learned: Boolean get() = status != Status.STRUCTURAL

    /** How strong the evidence is, for ranking and pruning: structural first, then proven, then the size of the effect times sqrt(n). */
    fun score(): Double {
        val effect = when (type) {
            EdgeType.CORRELATES, EdgeType.LEADS -> kotlin.math.abs(weight)
            else -> if (lift.isNaN()) weight else kotlin.math.abs(lift - 1.0)
        }
        val tier = when (status) { Status.STRUCTURAL -> 2e6; Status.PROVEN -> 1e6; Status.HYPOTHESIS -> 0.0 }
        return tier + effect * kotlin.math.sqrt(samples.coerceAtLeast(1).toDouble())
    }
}

/**
 * The NeuroGraph: what the phone has learned from the Dhan market data it keeps - indices, their companies and sectors,
 * expiries, weekdays, regimes, times of day and market events as nodes, their relations as edges with learned weights,
 * sample counts and a status (structural, proven out of sample, or only a hypothesis). Built by [NeuroBuilder] from the
 * store, kept in a small versioned file ([write] / [read]), asked through [NeuroQuery]. Market data only; it informs
 * Jarvis's answers and paper research and never arms, trades or changes any risk.
 */
class NeuroGraph(
    val builtAt: Long,
    /** The days the graph learned from (index sessions), oldest and newest, and how many. */
    val from: LocalDate?, val to: LocalDate?, val days: Int,
    val nodes: List<Node>, val edges: List<Edge>,
) {
    companion object {
        const val VERSION = 1
        private const val MAGIC = 0x4E475246 // "NGRF"
        /** At most this many edges are kept (the weakest learned ones are pruned first; structural ones always stay). */
        const val MAX_EDGES = 3000

        fun read(input: DataInputStream): NeuroGraph {
            if (input.readInt() != MAGIC) throw IOException("not a NeuroGraph file")
            val v = input.readInt()
            if (v != VERSION) throw IOException("NeuroGraph version $v (this app reads $VERSION)")
            val built = input.readLong()
            val from = input.readInt().let { if (it == Int.MIN_VALUE) null else LocalDate.ofEpochDay(it.toLong()) }
            val to = input.readInt().let { if (it == Int.MIN_VALUE) null else LocalDate.ofEpochDay(it.toLong()) }
            val days = input.readInt()
            val nNodes = input.readInt()
            if (nNodes < 0 || nNodes > 1_000_000) throw IOException("bad node count")
            val types = NodeType.values()
            val nodes = ArrayList<Node>(nNodes)
            repeat(nNodes) {
                nodes += Node(input.readUTF(), types[input.readByte().toInt()], input.readUTF(), input.readInt(), input.readDouble())
            }
            val nEdges = input.readInt()
            if (nEdges < 0 || nEdges > 10_000_000) throw IOException("bad edge count")
            val et = EdgeType.values(); val st = Status.values()
            val edges = ArrayList<Edge>(nEdges)
            repeat(nEdges) {
                val type = et[input.readByte().toInt()]
                val f = nodes[input.readInt()].id; val t = nodes[input.readInt()].id
                val status = st[input.readByte().toInt()]
                edges += Edge(type, f, t, status, input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble(),
                    input.readDouble(), input.readInt(), input.readInt(), input.readDouble(), input.readDouble(), input.readInt(), input.readDouble())
            }
            return NeuroGraph(built, from, to, days, nodes, edges)
        }
    }

    val byId: Map<String, Node> by lazy { nodes.associateBy { it.id } }
    val out: Map<String, List<Edge>> by lazy { edges.groupBy { it.from } }
    val into: Map<String, List<Edge>> by lazy { edges.groupBy { it.to } }

    val provenCount: Int get() = edges.count { it.status == Status.PROVEN }
    val hypothesisCount: Int get() = edges.count { it.status == Status.HYPOTHESIS }

    fun write(out: DataOutputStream) {
        out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(builtAt)
        out.writeInt(from?.toEpochDay()?.toInt() ?: Int.MIN_VALUE); out.writeInt(to?.toEpochDay()?.toInt() ?: Int.MIN_VALUE); out.writeInt(days)
        out.writeInt(nodes.size)
        val index = HashMap<String, Int>(nodes.size * 2)
        for ((i, n) in nodes.withIndex()) {
            index[n.id] = i
            out.writeUTF(n.id); out.writeByte(n.type.ordinal); out.writeUTF(n.label); out.writeInt(n.count); out.writeDouble(n.rate)
        }
        val kept = edges.filter { it.from in index && it.to in index }
        out.writeInt(kept.size)
        for (e in kept) {
            out.writeByte(e.type.ordinal); out.writeInt(index.getValue(e.from)); out.writeInt(index.getValue(e.to)); out.writeByte(e.status.ordinal)
            out.writeDouble(e.weight); out.writeDouble(e.base); out.writeDouble(e.lift); out.writeDouble(e.lo); out.writeDouble(e.hi)
            out.writeInt(e.samples); out.writeInt(e.testSamples); out.writeDouble(e.testValue); out.writeDouble(e.testBase)
            out.writeInt(e.lag); out.writeDouble(e.extra)
        }
        out.flush()
    }

    /** The graph with at most [max] edges: structural ones always, then the learned ones by [Edge.score] (proven first). */
    fun pruned(max: Int = MAX_EDGES): NeuroGraph {
        if (edges.size <= max) return this
        val (fixed, learned) = edges.partition { !it.learned }
        val room = (max - fixed.size).coerceAtLeast(0)
        val keep = learned.sortedWith(compareByDescending<Edge> { it.score() }.thenBy { it.type }.thenBy { it.from }.thenBy { it.to }.thenBy { it.lag })
            .take(room).toHashSet()
        val kept = edges.filter { !it.learned || it in keep }
        val used = kept.flatMap { listOf(it.from, it.to) }.toHashSet()
        return NeuroGraph(builtAt, from, to, days, nodes.filter { it.id in used || it.type == NodeType.INDEX }, kept)
    }

    /** Counts for the screen and Jarvis. */
    data class Summary(val nodes: Int, val edges: Int, val proven: Int, val hypotheses: Int, val structural: Int,
                       val byType: Map<EdgeType, Int>, val builtAt: Long, val from: LocalDate?, val to: LocalDate?, val days: Int)

    fun summary(): Summary = Summary(nodes.size, edges.size, provenCount, hypothesisCount, edges.count { it.status == Status.STRUCTURAL },
        EdgeType.values().associateWith { t -> edges.count { it.type == t } }.filterValues { it > 0 }, builtAt, from, to, days)
}
