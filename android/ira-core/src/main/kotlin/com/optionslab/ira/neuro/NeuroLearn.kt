package com.optionslab.ira.neuro

import com.optionslab.ira.dhan.DhanUniverse
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.TreeMap
import kotlin.math.sqrt

/**
 * Turns the builder's sums ([NeuroState]) into the [NeuroGraph]: nodes, the structural edges from the dated static lists,
 * and every learned edge judged by [Stats] (fit on the older 70%, checked on the newest 30%; proven only when it held).
 * The split is made afresh here at every build, so a relation proven last month can fall back to a hypothesis when new
 * days stop supporting it. Deterministic: the same state gives the same graph. Pure.
 */
object NeuroLearn {
    /** PRECEDES looks this many sessions ahead (and, after a gap at the open, at the rest of the same session). */
    const val NEXT_SESSIONS = 2

    // ---- node ids and labels ---------------------------------------------------------------------------------------------

    fun idx(u: String) = "IDX:$u"
    fun stk(s: String) = "STK:$s"
    fun sec(name: String) = "SEC:$name"
    fun exp(u: String) = "EXP:$u"
    fun day(d: DayOfWeek) = "DAY:${d.name.take(3)}"
    fun tod(b: Int) = "TOD:$b"
    fun reg(u: String, r: Regime) = "REG:$u:${r.name}"
    fun ev(u: String, e: Ev) = "EV:$u:${e.name}"

    fun label(u: String) = when (u) {
        "NIFTY" -> "Nifty"; "BANKNIFTY" -> "BankNifty"; "FINNIFTY" -> "FinNifty"; "MIDCPNIFTY" -> "Midcap Nifty"
        "NIFTYNXT50" -> "Nifty Next 50"; "SENSEX" -> "Sensex"; "BANKEX" -> "Bankex"; "INDIAVIX" -> "India VIX"; else -> u
    }

    private fun dayLabel(d: DayOfWeek) = d.name.lowercase().replaceFirstChar { it.uppercase() }

    // ---- the build -----------------------------------------------------------------------------------------------------

    fun graph(state: NeuroState, builtAt: Long, maxEdges: Int = NeuroGraph.MAX_EDGES): NeuroGraph {
        val nodes = TreeMap<String, Node>()
        val edges = ArrayList<Edge>()
        fun node(id: String, type: NodeType, label: String, count: Int = 0, rate: Double = Double.NaN) {
            val cur = nodes[id]
            if (cur == null || (cur.count == 0 && count > 0)) nodes[id] = Node(id, type, label, count, rate)
        }
        for (ix in DhanUniverse.INDICES) node(idx(ix.name), NodeType.INDEX, label(ix.name))
        // Structural: index membership and sectors (dated static lists).
        for ((u, members) in DhanUniverse.CONSTITUENTS) for (sym in members) {
            node(stk(sym), if (Sectors.isBank(sym)) NodeType.BANK else NodeType.STOCK, sym)
            edges += Edge(EdgeType.CONSTITUENT_OF, stk(sym), idx(u), Status.STRUCTURAL, 1.0)
        }
        for (sym in DhanUniverse.allConstituents()) Sectors.of(sym)?.let { s ->
            node(sec(s), NodeType.SECTOR, s)
            edges += Edge(EdgeType.IN_SECTOR, stk(sym), sec(s), Status.STRUCTURAL, 1.0)
        }
        for (d in DayOfWeek.values().take(5)) node(day(d), NodeType.DAY, dayLabel(d))
        for (b in Rules.TOD.indices) node(tod(b), NodeType.TIME, Rules.todLabel(b))

        val vixRows = state.rows[NeuroBuilder.VIX] ?: TreeMap()
        for (e in Ev.values().filter { it.vix }) {
            val covered = vixRows.values.count { it.covers(e) }
            if (covered > 0) node(ev(NeuroBuilder.VIX, e), NodeType.EVENT, "India VIX: ${e.short}", vixRows.values.count { it.has(e) },
                vixRows.values.count { it.has(e) }.toDouble() / covered)
        }
        for ((u, m) in state.rows) {
            if (u == NeuroBuilder.VIX || m.isEmpty()) continue
            sessions(u, m.values.toList(), vixRows, state.expiries[u], ::node, edges)
        }
        pairs(state, ::node, edges)

        val days = state.rows.values.flatMap { it.keys }.toSortedSet()
        val g = NeuroGraph(builtAt, days.firstOrNull()?.let { LocalDate.ofEpochDay(it.toLong()) },
            days.lastOrNull()?.let { LocalDate.ofEpochDay(it.toLong()) }, days.size,
            nodes.values.toList(), edges.sortedWith(compareBy<Edge>({ it.type }, { it.from }, { it.to }, { it.lag })))
        return g.pruned(maxEdges)
    }

    private fun bothSplit(flags: List<BooleanArray>): Pair<List<BooleanArray>, List<BooleanArray>> {
        val s = Stats.split(flags.size)
        return flags.subList(0, s) to flags.subList(s, flags.size)
    }

    /** Hits of a condition: [0] the condition, [1] the outcome; returns (k, n, base) over [part]. */
    private fun tally(part: List<BooleanArray>): Triple<Int, Int, Double> {
        var n = 0; var k = 0; var b = 0
        for (f in part) { if (f[0]) { n++; if (f[1]) k++ }; if (f[1]) b++ }
        return Triple(k, n, if (part.isEmpty()) Double.NaN else b.toDouble() / part.size)
    }

    private fun probEdge(type: EdgeType, from: String, to: String, flags: List<BooleanArray>, lag: Int = 0): Edge? {
        val (fit, test) = bothSplit(flags)
        val (k, n, base) = tally(fit)
        val (tk, tn, tBase) = tally(test)
        val v = Stats.judge(k, n, base, tk, tn, tBase) ?: return null
        return Edge(type, from, to, if (v.proven) Status.PROVEN else Status.HYPOTHESIS, v.fitP, v.fitBase, v.lift, v.lo, v.hi, n, tn,
            v.testP, v.testBase, lag)
    }

    private fun sessions(u: String, rows: List<DayRow>, vixRows: TreeMap<Int, DayRow>, expiries: java.util.TreeSet<Int>?,
                         node: (String, NodeType, String, Int, Double) -> Unit, edges: MutableList<Edge>) {
        val own = Ev.values().filter { !it.vix }
        for (e in own) {
            val covered = rows.count { it.covers(e) }
            if (covered == 0) continue
            val k = rows.count { it.has(e) }
            node(ev(u, e), NodeType.EVENT, "${label(u)}: ${e.short}", k, k.toDouble() / covered)
        }
        // PRECEDES: A on a session -> B in the next sessions (or, after a gap at the open, later that session).
        data class Src(val id: String, val e: Ev, val vix: Boolean)
        val sources = own.map { Src(ev(u, it), it, false) } + Ev.values().filter { it.vix }.map { Src(ev(NeuroBuilder.VIX, it), it, true) }
        for (a in sources) for (b in own) for (lag in listOf(0, NEXT_SESSIONS)) {
            if (lag == 0 && (a.vix || !a.e.atOpen || b.atOpen || a.e == b)) continue
            val flags = ArrayList<BooleanArray>()
            for (i in rows.indices) {
                val r = rows[i]
                val aRow = if (a.vix) vixRows[r.day] else r
                if (aRow == null || !aRow.covers(a.e)) continue
                val window = if (lag == 0) listOf(r) else {
                    if (i + lag >= rows.size || rows[i + lag].day - r.day > 10) continue
                    rows.subList(i + 1, i + 1 + lag)
                }
                if (window.any { !it.covers(b) }) continue
                flags += booleanArrayOf(aRow.has(a.e), window.any { it.has(b) })
            }
            probEdge(EdgeType.PRECEDES, a.id, ev(u, b), flags, lag)?.let { edges += it }
        }
        // OCCURS_IN: an event in a context (weekday, expiry day, regime).
        val hasOptions = DhanUniverse.index(u)?.options == true
        val expFrom = expiries?.firstOrNull()
        val contexts = ArrayList<Pair<String, (DayRow) -> Boolean?>>()
        for (d in DayOfWeek.values().take(5)) contexts += day(d) to { r -> LocalDate.ofEpochDay(r.day.toLong()).dayOfWeek == d }
        if (hasOptions && expiries != null && expFrom != null) {
            node(exp(u), NodeType.EXPIRY, "${label(u)} expiry day", rows.count { it.day in expiries }, Double.NaN)
            contexts += exp(u) to { r -> if (r.day < expFrom) null else r.day in expiries }
        }
        for (rg in listOf(Regime.TREND_UP, Regime.TREND_DOWN, Regime.RANGE)) {
            val n = rows.count { it.regime == rg.ordinal }
            if (n > 0) node(reg(u, rg), NodeType.REGIME, "${label(u)} ${rg.label}", n, n.toDouble() / rows.count { it.regime >= 0 })
            contexts += reg(u, rg) to { r -> if (r.regime < 0) null else r.regime == rg.ordinal }
        }
        rows.count { it.highVol }.let { n -> if (n > 0) node(reg(u, Regime.HIGH_VOL), NodeType.REGIME, "${label(u)} ${Regime.HIGH_VOL.label}", n, n.toDouble() / rows.size) }
        contexts += reg(u, Regime.HIGH_VOL) to { r -> r.highVol }
        for (e in own) for ((ctx, inCtx) in contexts) {
            val flags = ArrayList<BooleanArray>()
            for (r in rows) {
                if (!r.covers(e)) continue
                val c = inCtx(r) ?: continue
                flags += booleanArrayOf(c, r.has(e))
            }
            probEdge(EdgeType.OCCURS_IN, ev(u, e), ctx, flags)?.let { edges += it }
        }
        // OCCURS_IN a time of day: when the zero-to-hero moves reached their multiple, against the bucket's share of the session.
        val zth = rows.filter { it.zthMinute >= 0 }
        if (zth.isNotEmpty()) {
            val s = Stats.split(zth.size)
            val fit = zth.subList(0, s); val test = zth.subList(s, zth.size)
            for ((b, span) in Rules.TOD.withIndex()) {
                val base = (span.second - span.first) / 376.0
                val k = fit.count { Rules.tod(it.zthMinute) == b }; val tk = test.count { Rules.tod(it.zthMinute) == b }
                val v = Stats.judge(k, fit.size, base, tk, test.size, base) ?: continue
                edges += Edge(EdgeType.OCCURS_IN, ev(u, Ev.ZERO_TO_HERO), tod(b), if (v.proven) Status.PROVEN else Status.HYPOTHESIS,
                    v.fitP, base, v.lift, v.lo, v.hi, fit.size, test.size, v.testP, base)
            }
        }
    }

    /** Quarters of [m], oldest first, split where the fit part holds closest to 70% of the samples (count at [at]). */
    private fun splitQuarters(m: TreeMap<Int, DoubleArray>, at: Int): Pair<DoubleArray, DoubleArray> {
        val len = m.values.first().size
        val total = m.values.sumOf { it[at] }
        var best = m.size; var bestGap = Double.MAX_VALUE; var cum = 0.0
        for ((i, a) in m.values.withIndex()) {
            cum += a[at]
            val gap = kotlin.math.abs(cum / total - Stats.FIT_SHARE)
            if (i < m.size - 1 && gap < bestGap) { bestGap = gap; best = i + 1 }
        }
        if (m.size == 1) best = 1
        val fit = DoubleArray(len); val test = DoubleArray(len)
        for ((i, a) in m.values.withIndex()) { val t = if (i < best) fit else test; for (j in 0 until len) t[j] += a[j] }
        return fit to test
    }

    private fun partnerNode(p: String): String? {
        val (g, sym) = p.split(':', limit = 2).let { if (it.size == 2) it[0] to it[1] else return null }
        return if (g == "idx") idx(sym) else stk(sym)
    }

    private fun pairs(state: NeuroState, node: (String, NodeType, String, Int, Double) -> Unit, edges: MutableList<Edge>) {
        for ((key, m) in state.pairs) {
            if (m.isEmpty()) continue
            val parts = key.split('|')
            if (parts.size != 3) continue
            val p = partnerNode(parts[1]) ?: continue
            val u = idx(parts[2])
            if (parts[1].startsWith("idx:")) node(p, NodeType.INDEX, label(parts[1].removePrefix("idx:")), 0, Double.NaN)
            when (parts[0]) {
                "D" -> if (m.values.first().size == NeuroBuilder.D_LEN) daily(p, u, parts[1].startsWith("eq:"), m, edges)
                "L" -> if (m.values.first().size == NeuroBuilder.L_LEN) lead(p, u, m, edges)
            }
        }
    }

    private fun daily(p: String, u: String, stock: Boolean, m: TreeMap<Int, DoubleArray>, edges: MutableList<Edge>) {
        val (f, t) = splitQuarters(m, 0)
        val r = Stats.pearson(f[0], f[1], f[2], f[3], f[4], f[5])
        val tr = Stats.pearson(t[0], t[1], t[2], t[3], t[4], t[5])
        Stats.judgeCorr(r, f[0].toInt(), tr, t[0].toInt())?.let { (proven, ci) ->
            edges += Edge(EdgeType.CORRELATES, p, u, if (proven) Status.PROVEN else Status.HYPOTHESIS, r, lo = ci.first, hi = ci.second,
                samples = f[0].toInt(), testSamples = t[0].toInt(), testValue = tr)
        }
        if (!stock) return
        val base = if (f[0] > 0) f[9] / f[0] else Double.NaN
        val tBase = if (t[0] > 0) t[9] / t[0] else Double.NaN
        val v = Stats.judge(f[7].toInt(), f[6].toInt(), base, t[7].toInt(), t[6].toInt(), tBase) ?: return
        val bigDays = f[6] + t[6]
        val beta = if (bigDays > 0) (f[8] + t[8]) / bigDays else Double.NaN
        edges += Edge(EdgeType.CONTRIBUTES, p, u, if (v.proven) Status.PROVEN else Status.HYPOTHESIS, v.fitP, v.fitBase, v.lift, v.lo, v.hi,
            f[6].toInt(), t[6].toInt(), v.testP, v.testBase, extra = beta)
    }

    /** A lead of at least this mean daily correlation, ahead of the mirror lag by [LEAD_EDGE], with a t of [LEAD_T]. */
    const val LEAD_MIN = 0.05
    const val LEAD_EDGE = 0.02
    const val LEAD_T = 2.0
    const val LEAD_TEST_MIN = 0.03
    const val LEAD_TEST_T = 1.64
    const val LEAD_TEST_DAYS = 10

    private fun lead(p: String, u: String, m: TreeMap<Int, DoubleArray>, edges: MutableList<Edge>) {
        val (f, t) = splitQuarters(m, 0)
        val maxLag = NeuroBuilder.MAX_LAG
        fun mean(a: DoubleArray, lag: Int) = if (a[0] > 0) a[1 + 2 * (lag + maxLag)] / a[0] else Double.NaN
        fun sd(a: DoubleArray, lag: Int): Double {
            val n = a[0]; if (n < 2) return Double.NaN
            val s = a[1 + 2 * (lag + maxLag)]; val ss = a[2 + 2 * (lag + maxLag)]
            return sqrt(((ss - s * s / n) / (n - 1)).coerceAtLeast(0.0))
        }
        fun tStat(a: DoubleArray, lag: Int) = mean(a, lag) / (sd(a, lag) / sqrt(a[0])).let { if (it <= 0 || it.isNaN()) Double.NaN else it }
        if (f[0] < Stats.MIN_FIT) return
        val best = (-maxLag..maxLag).filter { it != 0 }.maxByOrNull { mean(f, it) } ?: return
        val mBest = mean(f, best); val mMirror = mean(f, -best)
        val tFit = tStat(f, best)
        if (mBest.isNaN() || mBest < LEAD_MIN || mBest < mMirror + LEAD_EDGE || tFit.isNaN() || tFit < LEAD_T) return
        val tm = mean(t, best)
        val proven = t[0] >= LEAD_TEST_DAYS && !tm.isNaN() && tm >= LEAD_TEST_MIN && tm > mean(t, -best) && (tStat(t, best).let { !it.isNaN() && it >= LEAD_TEST_T })
        val se = sd(f, best) / sqrt(f[0])
        val (from, to) = if (best > 0) p to u else u to p
        edges += Edge(EdgeType.LEADS, from, to, if (proven) Status.PROVEN else Status.HYPOTHESIS, mBest, mMirror, Double.NaN,
            mBest - Stats.Z95 * se, mBest + Stats.Z95 * se, f[0].toInt(), t[0].toInt(), tm, mean(t, -best), kotlin.math.abs(best))
    }
}
