package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The Candle agent's memory (20 Oct 2026): what happened after each candle it read, its own running statistics per shape x
 * context, the research it starts from, and what it learned against that research.
 *
 *  - [outcome]: after a read, the next 1-50 candles of the SAME session (R87's horizons): the move in the candle's ATR,
 *    the best and worst over 10 (MFE / MAE), and whether the far end of its longer wick held for 10 candles. A gap in the
 *    candles (a missed stretch, a halt) ends the count there; a horizon past the session's end stays missing, never filled.
 *    A read is settled once 10 candles followed, or the session is over.
 *  - [Stats]: one [Cell] per instrument x timeframe x shape x context word ("NIFTY|5|big_lower_wick|uptrend"), plus a
 *    same-colour baseline per context ("*G" / "*R", R85's lesson: compare with candles of the same colour). Counts only, a
 *    few dozen bytes a cell, at most [MAX_CELLS] (the thinnest dropped first), encoded as text ([encode]) for the app to keep.
 *  - [Seed]: the research (R87 index candles, R87B, R85 MCX) from the app's asset knowledge/candle_seed.json.
 *  - [compare] / [results]: its own count against the research, cell by cell, as Jarvis's lessons ([AgentLessons.CandleResult]).
 *    At most "building" (AgentLessons.ceiling): a candle lesson is never strong, so never a proposal that changes anything.
 *    [notes] are its proposals: notes for Boss only.
 *
 * Nothing here trades, sizes, stops or orders. Pure: no clock, no file.
 */
object CandleBook {
    val HORIZONS = intArrayOf(1, 3, 5, 10, 20, 50)
    const val SETTLE = 10
    /** About 21 series x (43 shapes + 3 colours) x the context words they meet: a few hundred KB of text at most. */
    const val MAX_CELLS = 6_000
    /** Own reads needed before the agent says its own number at all. */
    const val SAY_N = 10
    /** Own reads needed before it compares itself with the research. */
    const val COMPARE_N = 30
    /** |z| of the up-rate against its same-colour baseline before the agent calls its own count an edge. */
    const val EDGE_Z = 2.0
    /**
     * 20 Oct, review: the false-discovery rate across every compared cell (Benjamini-Hochberg, CLAUDE.md's rule for many ideas).
     * Thousands of cells tested at |z| >= 2 alone gave dozens of "differs from the research" notes by chance.
     */
    const val FDR_Q = 0.10

    // ---- outcomes ---------------------------------------------------------------------------------------------------------

    /**
     * What followed the read: [moves] per horizon (ATR, close to close; missing past a gap or the session's end), [mfe10] /
     * [mae10] (ATR, over the 10 candles or fewer), the wick tested ([wickSide] +1 its low, -1 its high) and whether it [wickHeld].
     */
    data class Outcome(val moves: Map<Int, Double>, val mfe10: Double?, val mae10: Double?, val wickSide: Int, val wickHeld: Boolean?, val seen: Int) {
        val up5: Boolean? get() = moves[5]?.let { it > 0 }
    }

    /**
     * The outcome of [read] from [bars] (the same list it was read from: [CandleRead.Read.index] points into it), or null while
     * it is not settled (fewer than [SETTLE] candles after it and the session not over). [sessionOver]: the read's session has
     * ended (the market closed, or a later day's candle exists). No ATR: nothing to measure in, null.
     */
    fun outcome(read: CandleRead.Read, bars: List<CandleRead.Bar>, sessionOver: Boolean): Outcome? {
        val atr = read.anatomy.atr ?: return null
        val i = read.index
        if (i !in bars.indices || bars[i].t != read.bar.t) return null
        val day = read.bar.t.toLocalDate()
        val after = ArrayList<CandleRead.Bar>()
        var j = i + 1
        var prevT = read.bar.t
        var gap = false
        while (j < bars.size && after.size < HORIZONS.last()) {
            val b = bars[j]
            if (b.t.toLocalDate() != day) break
            // A stretch longer than three candles with nothing: a gap (a missed feed, a halt). The count ends at it.
            if (java.time.Duration.between(prevT, b.t).toMinutes() > 3L * read.tf) { gap = true; break }
            after += b; prevT = b.t; j++
        }
        val dayEnded = j < bars.size && bars[j].t.toLocalDate() != day
        if (after.size < SETTLE && !sessionOver && !dayEnded && !gap) return null
        val c0 = read.bar.c
        val moves = HashMap<Int, Double>()
        for (h in HORIZONS) if (after.size >= h) moves[h] = (after[h - 1].c - c0) / atr
        val ten = after.take(SETTLE)
        val mfe = if (ten.isEmpty()) null else maxOf(0.0, ten.maxOf { it.h } - c0) / atr
        val mae = if (ten.isEmpty()) null else maxOf(0.0, c0 - ten.minOf { it.l }) / atr
        val a = read.anatomy
        val side = if (a.lowerPct >= a.upperPct) 1 else -1
        val held = if (ten.isEmpty()) null else if (side > 0) ten.all { it.l >= read.bar.l } else ten.all { it.h <= read.bar.h }
        // A wick only counts as held after the full 10 candles; broken counts as soon as it breaks.
        val wick = when { held == false -> false; ten.size >= SETTLE -> held; else -> null }
        return Outcome(moves, mfe, mae, side, wick, after.size)
    }

    // ---- the stats ---------------------------------------------------------------------------------------------------------

    /** One cell's counts: reads with a 5-candle outcome ([n5]), how many went up, the sum and squares of the move, MFE / MAE. */
    data class Cell(
        val n5: Int = 0, val up5: Int = 0, val sum5: Double = 0.0, val sq5: Double = 0.0, val n10: Int = 0, val mfe: Double = 0.0, val mae: Double = 0.0,
        val wickN: Int = 0, val wickHeld: Int = 0, val last: LocalDate? = null,
    ) {
        val upRate: Double? get() = if (n5 == 0) null else up5.toDouble() / n5
        val mean5: Double? get() = if (n5 == 0) null else sum5 / n5
        val heldRate: Double? get() = if (wickN == 0) null else wickHeld.toDouble() / wickN
        fun add(o: Outcome, day: LocalDate): Cell {
            val m5 = o.moves[5]
            val ten = o.seen >= SETTLE
            return Cell(
                n5 + (if (m5 != null) 1 else 0), up5 + (if (m5 != null && m5 > 0) 1 else 0), sum5 + (m5 ?: 0.0), sq5 + (m5 ?: 0.0) * (m5 ?: 0.0),
                n10 + (if (ten && o.mfe10 != null) 1 else 0), mfe + (if (ten) o.mfe10 ?: 0.0 else 0.0), mae + (if (ten) o.mae10 ?: 0.0 else 0.0),
                wickN + (if (o.wickHeld != null) 1 else 0), wickHeld + (if (o.wickHeld == true) 1 else 0), if (last == null || day > last) day else last,
            )
        }
    }

    /** The running statistics: key "INST|tf|shape|context" to its cell. Not thread-safe by itself: the agent guards it. */
    class Stats(val cells: MutableMap<String, Cell> = HashMap(),
                /** Per "INST|tf": the last candle whose outcome was counted (kept with the counts, so a restart never counts it twice). */
                val marks: MutableMap<String, java.time.LocalDateTime> = HashMap(),
                /** Per "INST|tf": the first candle counted. The 24x7 study of older history counts only before it (never twice). */
                val firsts: MutableMap<String, java.time.LocalDateTime> = HashMap()) {
        /** Not yet counted for [series]: after its last counted candle, or before its first. */
        fun countable(series: String, t: java.time.LocalDateTime): Boolean = countable(marks[series], firsts[series], t)
        fun counted(series: String, t: java.time.LocalDateTime) {
            if (marks[series]?.let { t.isAfter(it) } != false) marks[series] = t
            if (firsts[series]?.let { t.isBefore(it) } != false) firsts[series] = t
        }
        val size: Int get() = cells.size
        operator fun get(key: String): Cell? = cells[key]
    }

    /**
     * Not yet counted, given the counted span [first]..[mark] (null: nothing counted). A batch judges each candle against the span
     * as it was when the batch began: counting the oldest candle of a study first must not hide the ones after it.
     */
    fun countable(mark: java.time.LocalDateTime?, first: java.time.LocalDateTime?, t: java.time.LocalDateTime): Boolean {
        val m = mark ?: return true
        val f = first ?: return t.isAfter(m)
        return t.isAfter(m) || t.isBefore(f)
    }

    fun key(inst: String, tf: Int, shape: String, ctx: String) = "${inst.uppercase(Locale.ENGLISH)}|$tf|$shape|$ctx"
    fun baseKey(inst: String, tf: Int, colour: Int, ctx: String) = key(inst, tf, if (colour > 0) "*G" else if (colour < 0) "*R" else "*F", ctx)

    /**
     * [read]'s settled [o] counted into [s]: its primary shape (and every other shape it is in, under "all") and its context
     * words, and the same-colour baseline the same way. Bounded: past [MAX_CELLS] the thinnest, oldest cells go first.
     */
    fun count(s: Stats, read: CandleRead.Read, o: Outcome) {
        val day = read.bar.t.toLocalDate()
        val ctxs = listOf("all") + read.context.tags
        fun bump(k: String) { s.cells[k] = (s.cells[k] ?: Cell()).add(o, day) }
        for (c in ctxs) { bump(key(read.inst, read.tf, read.primary, c)); bump(baseKey(read.inst, read.tf, read.anatomy.colour, c)) }
        for (sh in read.shapes) if (sh != read.primary && sh != "green" && sh != "red" && sh != "flat") bump(key(read.inst, read.tf, sh, "all"))
        // Bounded by the caller once a batch ([trim]): sorting the cells at every count would cost a sort per candle.
    }

    fun trim(s: Stats, max: Int = MAX_CELLS) {
        if (s.cells.size <= max) return
        // Kept longest: the same-colour baselines over all candles, then each shape's "all" cell, then the context cells - the
        // thinnest and the oldest of those go first.
        fun rank(k: String) = if (k.endsWith("|all")) (if (k.contains("|*")) 2 else 1) else 0
        val drop = s.cells.entries.sortedWith(compareBy<Map.Entry<String, Cell>>({ rank(it.key) }, { it.value.n5 }, { it.value.last ?: LocalDate.MIN }))
            .take(s.cells.size - max).map { it.key }
        for (k in drop) s.cells.remove(k)
    }

    /** Exact: a sum read back must be the sum written (a rounded one would drift a little every save). */
    private fun num(x: Double) = if (x == Math.rint(x) && abs(x) < 1e12) x.toLong().toString() else x.toString()

    /** One line a cell: "C~key~n5~up5~sum5~sq5~n10~mfe~mae~wickN~wickHeld~last"; one a mark: "M~INST|tf~time". */
    fun encode(s: Stats): String = (s.marks.toSortedMap().entries.map { (k, t) -> "M~${k.replace('\n', ' ')}~$t" } +
        s.firsts.toSortedMap().entries.map { (k, t) -> "F~${k.replace('\n', ' ')}~$t" } +
        s.cells.toSortedMap().entries.map { (k, c) ->
            listOf("C", k.replace('\n', ' '), c.n5, c.up5, num(c.sum5), num(c.sq5), c.n10, num(c.mfe), num(c.mae), c.wickN, c.wickHeld, c.last?.toString() ?: "")
                .joinToString("~")
        }).joinToString("\n")

    /** [encode] read back; broken lines skipped (a damaged file loses only them). */
    fun decode(text: String?): Stats {
        val s = Stats()
        if (text.isNullOrBlank()) return s
        for (line in text.lineSequence()) {
            val p = line.split('~')
            if (p.size == 3 && p[0] == "M") { runCatching { java.time.LocalDateTime.parse(p[2]) }.getOrNull()?.let { s.marks[p[1]] = it }; continue }
            if (p.size == 3 && p[0] == "F") { runCatching { java.time.LocalDateTime.parse(p[2]) }.getOrNull()?.let { s.firsts[p[1]] = it }; continue }
            if (p.size != 12 || p[0] != "C") continue
            val c = runCatching {
                Cell(p[2].toInt(), p[3].toInt(), p[4].toDouble(), p[5].toDouble(), p[6].toInt(), p[7].toDouble(), p[8].toDouble(), p[9].toInt(), p[10].toInt(),
                    p[11].takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) })
            }.getOrNull() ?: continue
            if (c.n5 < 0 || c.up5 !in 0..c.n5 || c.wickHeld !in 0..c.wickN) continue
            // 20 Oct, second review: cells of the chain and gamma context read at the batch (look-ahead, no longer counted:
            // [CandleRead.countable]) are dropped from a file saved before, so they never reach the comparisons or the notes.
            if (p[1].substringAfterLast('|') in CandleRead.NOW_TAGS) continue
            s.cells[p[1]] = c
        }
        // A file from before the first-counted span (20 Oct's first build): everything up to its mark counts as counted.
        for (k in s.marks.keys) if (k !in s.firsts) s.firsts[k] = java.time.LocalDateTime.MIN
        trim(s)
        return s
    }

    // ---- the research seed ------------------------------------------------------------------------------------------------

    /** One research row: R87's numbers for one family on one index and timeframe (design 2021-2025). */
    data class Row(
        val und: String, val tf: Int, val n: Int, val up: Double?, val base: Double?, val effAtr: Double?, val pts: Double?, val q: Double?,
        val lowBroken: Double?, val lowBase: Double?, val highBroken: Double?, val highBase: Double?,
        /** Held out of sample: true, false, or null (not a design finding, so not tested). */
        val held: Boolean?,
    ) {
        /**
         * A finding that is up (+1), down (-1), or none (0): BH q under 0.10 in design AND not failed out of sample (a design finding
         * that did not hold is no edge: R87's holdout). Its way is the up-rate against the matched candles, the agent's own measure.
         */
        val edge: Int get() {
            if (q == null || q >= 0.10 || held == false) return 0
            val u = up; val b = base
            return if (u != null && b != null && u != b) (if (u > b) 1 else -1) else if (effAtr != null && effAtr != 0.0) (if (effAtr > 0) 1 else -1) else 0
        }
    }

    data class Family(val key: String, val name: String, val def: String, val why: String, val verdict: String, val rows: List<Row>)

    data class Seed(val date: String, val doc: String, val design: String, val holdout: String, val held: Int, val findings: Int, val tested: Int,
                    val r87b: String, val mcx: String, val families: Map<String, Family>) {
        fun row(shape: String, und: String, tf: Int): Row? = families[shape]?.rows?.firstOrNull { it.und.equals(und, true) && it.tf == tf }
        val empty: Boolean get() = families.isEmpty()
    }

    val EMPTY_SEED = Seed("", "", "", "", 0, 0, 0, "", "", emptyMap())

    /** The research indices R87 measured. Anything else (MCX) is R85's. */
    val R87_INDICES = setOf("NIFTY", "BANKNIFTY", "SENSEX")

    private fun d(x: Any?): Double? = (x as? Number)?.toDouble()

    /** The asset's text parsed; a broken or missing asset gives [EMPTY_SEED] (the agent then says "no research loaded"). */
    fun seed(json: String?): Seed = runCatching {
        val m = Json.parse(json ?: return EMPTY_SEED) as? Map<*, *> ?: return EMPTY_SEED
        fun s(k: String) = m[k] as? String ?: ""
        val fams = (m["families"] as? List<*>).orEmpty().mapNotNull { x ->
            val f = x as? Map<*, *> ?: return@mapNotNull null
            val k = f["k"] as? String ?: return@mapNotNull null
            val rows = (f["s"] as? List<*>).orEmpty().mapNotNull { r ->
                val a = r as? List<*> ?: return@mapNotNull null
                if (a.size < 13) return@mapNotNull null
                Row(a[0] as? String ?: return@mapNotNull null, d(a[1])?.toInt() ?: return@mapNotNull null, d(a[2])?.toInt() ?: 0, d(a[3]), d(a[4]), d(a[5]), d(a[6]),
                    d(a[7]), d(a[8]), d(a[9]), d(a[10]), d(a[11]), when (d(a[12])?.toInt()) { 1 -> true; 0 -> false; else -> null })
            }
            k to Family(k, f["name"] as? String ?: k, f["def"] as? String ?: "", f["why"] as? String ?: "", f["verdict"] as? String ?: "", rows)
        }.toMap()
        Seed(s("date"), s("doc"), s("design"), s("holdout"), d(m["held"])?.toInt() ?: 0, d(m["findings"])?.toInt() ?: 0, d(m["tested"])?.toInt() ?: 0,
            s("r87b"), s("mcx"), fams)
    }.getOrDefault(EMPTY_SEED)

    /** The research timeframe nearest to [tf] (R87 measured 1, 5 and 15 minutes). */
    fun researchTf(tf: Int): Int = when { tf <= 2 -> 1; tf <= 9 -> 5; else -> 15 }

    /**
     * What the research says about [shape] on [inst] at [tf], in one plain sentence with its sample size, or null when it has
     * nothing on that shape. MCX (and any instrument R87 did not measure) gets R85's verdict.
     */
    fun researchLine(seed: Seed, shape: String, inst: String, tf: Int): String? {
        val u = inst.uppercase(Locale.ENGLISH)
        if (u !in R87_INDICES) return seed.mcx.takeIf { it.isNotEmpty() }
        val fam = seed.families[shape] ?: return null
        val r = seed.row(shape, u, researchTf(tf)) ?: return null
        val sb = StringBuilder("History (R87, $u ${r.tf}m, ${seed.design}, n ${"%,d".format(Locale.ENGLISH, r.n)}): ")
        if (r.up != null && r.base != null) sb.append("up over the next 5 candles ${pc(r.up)} vs ${pc(r.base)} for matched candles")
        r.pts?.let { sb.append(String.format(Locale.ENGLISH, " (%+.1f points)", it)) }
        sb.append(when {
            r.edge != 0 && r.held == true -> "; a design finding that held out of sample, but below an option's cost"
            r.held == false -> "; a design finding that did NOT hold out of sample, so no edge"
            r.edge != 0 -> "; a design finding (q ${f2(r.q!!)})"
            else -> "; no edge (q ${r.q?.let { f2(it) } ?: "n/a"})"
        })
        val far = if (shape.contains("upper") || shape in setOf("shooting_star", "inverted_hammer", "gravestone_doji", "fp_stop_run_up", "tweezer_top"))
            r.highBroken?.let { b -> r.highBase?.let { "its high was broken within 10 candles ${pc(b)} vs ${pc(it)} for any candle with a high that far" } }
        else r.lowBroken?.let { b -> r.lowBase?.let { "its low was broken within 10 candles ${pc(b)} vs ${pc(it)} for any candle with a low that far" } }
        far?.let { sb.append("; ").append(it) }
        sb.append(". ").append(fam.verdict.replaceFirstChar { it.uppercase() }).append('.')
        return sb.toString()
    }

    private fun pc(x: Double) = "${Math.round(x * 100)}%"
    private fun f2(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)

    // ---- learning: own count against the research -------------------------------------------------------------------------

    enum class Says(val words: String) { UP("more up than its baseline"), DOWN("more down than its baseline"), NONE("no edge"), FEW("too few to say") }

    /** What the agent's own count says for one cell against its same-colour baseline: a z on the up-rate, needs [COMPARE_N]. */
    /**
     * 20 Oct, second review: [s] said for ONE candle's own count (the explanation). An edge there is one cell's |z| >= 2 before
     * the correction for the many cells tested ([compare]'s Benjamini-Hochberg), so it is said as that, never as a finding.
     */
    fun oneCellWords(s: Says): String = if (s == Says.UP || s == Says.DOWN)
        "${s.words} on this one count alone, before correcting for the many shapes counted (the shapes table says whether it holds)" else s.words

    fun says(cell: Cell?, base: Cell?): Says {
        val z = z(cell, base) ?: return Says.FEW
        return when { z >= EDGE_Z -> Says.UP; z <= -EDGE_Z -> Says.DOWN; else -> Says.NONE }
    }

    /** The z of [cell]'s up-rate against its baseline (null: under [COMPARE_N] settled reads, or no outcome). */
    fun z(cell: Cell?, base: Cell?): Double? {
        val n = cell?.n5 ?: 0
        val r = cell?.upRate ?: return null
        if (n < COMPARE_N) return null
        val p = base?.upRate ?: 0.5
        val se = sqrt(p * (1 - p) / n).coerceAtLeast(1e-9)
        return (r - p) / se
    }

    /** One cell compared: its own count, the baseline, the research's row and whether they agree. */
    data class Compare(val inst: String, val tf: Int, val shape: String, val ctx: String, val cell: Cell, val base: Cell?, val own: Says,
                       val research: Row?, val researchSays: Says, val agrees: Boolean?) {
        fun words(): String {
            val head = "${inst} ${tf}m ${CandleRead.name(shape)}${if (ctx == "all") "" else " (${ctxWords(ctx)})"}"
            val mine = cell.upRate?.let { "up after 5 candles ${pc(it)} of ${cell.n5}" + (base?.upRate?.let { b -> " vs ${pc(b)} same-colour" } ?: "") } ?: "no outcome yet"
            val res = research?.let { r -> r.up?.let { "research ${pc(it)} vs ${pc(r.base ?: 0.5)} (n ${r.n})" } } ?: "no research row"
            val verdict = when (agrees) { true -> "agrees with the research"; false -> "differs from the research"; null -> "too few to compare" }
            return "$head: $mine; $res; $verdict"
        }
    }

    /** A context word as said: "hist_support_held" -> "at a support from history", "bp_sweep" -> "big players: sweep". */
    fun ctxWords(ctx: String): String = when {
        ctx == "hist_support_held" -> "at a support from history"
        ctx == "hist_resistance_hit" -> "at a resistance from history"
        ctx.startsWith("bp_") -> "big players: " + ctx.removePrefix("bp_").replace('_', ' ')
        else -> ctx.replace('_', ' ')
    }

    /**
     * Every "INST|tf|shape|ctx" cell of [s] (baselines aside), compared with the research.
     *
     * 20 Oct, review: its own count is an edge only when it survives Benjamini-Hochberg at [FDR_Q] across every cell compared
     * (two-sided p from its z), not |z| >= 2 on each of thousands of cells. And a research edge its own count does not show
     * (yet) is "too few to compare", not "differs": at n 30 an own count has little power to see a small edge.
     */
    fun compare(s: Stats, seed: Seed): List<Compare> {
        data class Raw(val inst: String, val tf: Int, val shape: String, val ctx: String, val c: Cell, val base: Cell?, val z: Double?)
        val raw = s.cells.entries.mapNotNull { (k, c) ->
            val p = k.split('|')
            if (p.size != 4 || p[2].startsWith("*")) return@mapNotNull null
            val inst = p[0]; val tf = p[1].toIntOrNull() ?: return@mapNotNull null; val shape = p[2]; val ctx = p[3]
            val colour = when { shape.contains("green") || shape in BULL -> 1; shape.contains("red") || shape in BEAR -> -1; else -> 0 }
            // A shape without one colour is compared with every candle of the context (green and red baselines together).
            val base = if (colour != 0) s[baseKey(inst, tf, colour, ctx)] else merge(s[baseKey(inst, tf, 1, ctx)], s[baseKey(inst, tf, -1, ctx)])
            Raw(inst, tf, shape, ctx, c, base, z(c, base))
        }
        val tested = raw.filter { it.z != null }
        val q = LearnStats.bh(DoubleArray(tested.size) { LearnStats.pOfZ(tested[it].z!!) })
        val qOf = HashMap<Raw, Double>(tested.size * 2).apply { tested.forEachIndexed { i, r -> put(r, q[i]) } }
        return raw.map { r ->
            val own = when {
                r.z == null -> Says.FEW
                (qOf[r] ?: 1.0) > FDR_Q -> Says.NONE
                r.z > 0 -> Says.UP
                else -> Says.DOWN
            }
            val row = if (r.inst in R87_INDICES && r.ctx == "all") seed.row(r.shape, r.inst, researchTf(r.tf)) else null
            // MCX and every context cell: R85 / R87 found no edge in any (0 of 4,049 shape x context cells passed).
            val rs = when { row != null -> when (row.edge) { 1 -> Says.UP; -1 -> Says.DOWN; else -> Says.NONE }; seed.empty -> Says.FEW; else -> Says.NONE }
            val agrees = when {
                own == Says.FEW || rs == Says.FEW -> null
                rs != Says.NONE && own == Says.NONE -> null           // a research edge not (yet) seen in its own count: too few to say
                else -> own == rs
            }
            Compare(r.inst, r.tf, r.shape, r.ctx, r.c, r.base, own, row, rs, agrees)
        }.sortedWith(compareBy({ it.inst }, { it.tf }, { it.shape }, { it.ctx }))
    }

    private val BULL = setOf("three_white", "bull_engulfing", "bull_harami", "morning_star", "tweezer_bottom")
    private val BEAR = setOf("three_black", "bear_engulfing", "bear_harami", "evening_star", "tweezer_top")

    private fun merge(a: Cell?, b: Cell?): Cell? = when {
        a == null -> b; b == null -> a
        else -> Cell(a.n5 + b.n5, a.up5 + b.up5, a.sum5 + b.sum5, a.sq5 + b.sq5, a.n10 + b.n10, a.mfe + b.mfe, a.mae + b.mae, a.wickN + b.wickN, a.wickHeld + b.wickHeld)
    }

    /**
     * Tonight's lessons for Jarvis ([AgentLessons.CandleResult]): every "all" cell with an own verdict compared, held = agrees.
     * At most [max], the biggest samples first.
     */
    fun results(s: Stats, seed: Seed, day: LocalDate, max: Int = 40): List<AgentLessons.CandleResult> = compare(s, seed)
        .filter { it.ctx == "all" && it.agrees != null }
        .sortedByDescending { it.cell.n5 }.take(max)
        .map { c ->
            AgentLessons.CandleResult(day, "${c.inst.lowercase(Locale.ENGLISH)}:${c.tf}:${c.shape}", "${CandleRead.name(c.shape).replaceFirstChar { it.uppercase() }} on ${c.inst} ${c.tf}m",
                c.cell.n5, c.agrees == true, listOf("candles", c.inst.lowercase(Locale.ENGLISH), c.shape))
        }

    /**
     * The agent's proposals: notes for Boss only, never an action. A cell whose own count differs from the research with
     * [COMPARE_N]+ reads becomes "worth a research re-check"; nothing in any arm, size, stop or order changes.
     */
    fun notes(s: Stats, seed: Seed, max: Int = 5): List<String> = compare(s, seed).filter { it.agrees == false }
        .sortedByDescending { it.cell.n5 }.take(max)
        .map { "${it.words()}. A note for Boss: worth a research re-check; nothing in any arm, size, stop or order changes." }

    /**
     * The growth score, 0-100: the share of the shapes it has seen that it has counted [COMPARE_N]+ times, and how many of
     * those it could compare with the research. Said with its parts ([growthWords]).
     */
    fun growth(s: Stats): Int {
        val all = s.cells.filter { it.key.endsWith("|all") && !it.key.split('|')[2].startsWith("*") }
        if (all.isEmpty()) return 0
        return (100.0 * all.count { it.value.n5 >= COMPARE_N } / all.size).toInt().coerceIn(0, 100)
    }

    fun growthWords(s: Stats, seed: Seed): String {
        val all = s.cells.filter { it.key.endsWith("|all") && !it.key.split('|')[2].startsWith("*") }
        val mature = all.count { it.value.n5 >= COMPARE_N }
        val cmp = compare(s, seed).filter { it.ctx == "all" && it.agrees != null }
        return "Growth ${growth(s)}/100: $mature of ${all.size} shapes counted $COMPARE_N+ times; ${cmp.count { it.agrees == true }} agree with the research, " +
            "${cmp.count { it.agrees == false }} differ."
    }
}
