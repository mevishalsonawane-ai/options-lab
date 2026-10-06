package com.optionslab.ira.neuro

import com.optionslab.ira.dhan.DhanUniverse
import com.optionslab.ira.dhan.Files
import com.optionslab.ira.dhan.Plan
import java.time.LocalDate
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the [NeuroGraph]'s memory ([NeuroState]) from the Dhan store ([Files]) - streamed, one file at a time, never the
 * whole store in memory:
 *
 *  1. each index's sessions (daily candles; its expired options window by window, [OptionDays]) become [DayRow]s: the
 *     events of the day ([Ev]), the regime read from the sessions before it, the time of the first zero-to-hero move;
 *  2. each company (and each index against Nifty) is paired with its index on daily returns: correlation sums, and how
 *     it moved on the index's big days - per calendar quarter;
 *  3. their one-minute returns are cross-correlated day by day at lags of up to [MAX_LAG] minutes - per quarter.
 *
 * INCREMENTAL: every source keeps a watermark (the last day read into the state), and a run reads only the days after it
 * (a day only once all of an index's parts for it are stored, and never today). If older data arrives later (a backfilled
 * window, options added after their index's days were read, data deleted), [needsFull] says so and a full rebuild starts
 * from an empty state. DETERMINISTIC: sources in sorted order, sums only, no clock beyond [update]'s `today`. A run that
 * is stopped ([update]'s `active`) leaves a consistent state: a watermark moves only after its source's days are in.
 * Market data only: nothing here can place, arm or change anything.
 */
object NeuroBuilder {
    /** Index sessions kept (rolling). */
    const val DAY_LOOKBACK_YEARS = 10L
    /** Pair statistics kept (rolling, by quarter). */
    const val PAIR_LOOKBACK_YEARS = 5L
    /** Leads and lags checked, in minutes either way. */
    const val MAX_LAG = 3
    /** A day's lagged correlation counts only with this many minute pairs. */
    const val MIN_MINUTES = 120
    /** Daily pair sums: n, Σx, Σy, Σx², Σy², Σxy, big days, same-way big days, Σ big-day beta, same-way days. */
    const val D_LEN = 10
    /** Lead sums: days, then Σr and Σr² per lag from -MAX_LAG to MAX_LAG. */
    const val L_LEN = 1 + 2 * (2 * MAX_LAG + 1)
    const val VIX = "INDIAVIX"

    private const val SLOTS = 375
    private const val OPEN = 555

    fun quarter(day: Int): Int = LocalDate.ofEpochDay(day.toLong()).let { it.year * 4 + (it.monthValue - 1) / 3 }

    private fun epochDay(t: Long) = OptionDays.epochDay(t)

    // ---- what the store holds ----------------------------------------------------------------------------------------

    /** The indices with daily candles stored, in [DhanUniverse.INDICES]' order. */
    fun indices(files: Files): List<String> = DhanUniverse.INDICES.map { it.name }.filter { files.file("idx/${Plan.safe(it)}/day").isDirectory }

    /** The options flag read for [u]: its own (WEEK/MONTH) when stored, else the first stored; null when none. */
    fun optionFlag(files: Files, u: String): String? {
        val ix = DhanUniverse.index(u) ?: return null
        if (!ix.options) return null
        val kept = files.file("opt/${Plan.safe(u)}").list()?.sorted() ?: return null
        return if (ix.flag in kept) ix.flag else kept.firstOrNull()
    }

    /** Every dated folder the graph reads, with its windows (epoch days of each file's first day). */
    fun windows(files: Files): Map<String, IntArray> {
        val out = TreeMap<String, IntArray>()
        fun add(dir: String) {
            val w = files.dated(dir)
            if (w.isNotEmpty()) out[dir] = IntArray(w.size) { w[it].toEpochDay().toInt() }
        }
        for (g in listOf(Plan.Group.IDX, Plan.Group.EQ)) for (s in files.symbols(g)) {
            add("${g.dir}/$s/day"); add("${g.dir}/$s/min")
        }
        for (u in files.file("opt").list()?.sorted() ?: emptyList()) for (f in files.file("opt/$u").list()?.sorted() ?: emptyList()) add("opt/$u/$f")
        return out
    }

    /**
     * Why the next build must start from scratch (null: an incremental update is enough): no state yet, data deleted, or
     * older data arrived after the days around it were read (a window older than the newest seen, options for days
     * already read).
     */
    fun needsFull(files: Files, state: NeuroState?): String? {
        if (state == null) return "no graph yet"
        val now = windows(files)
        for ((dir, prior) in state.seen) {
            val cur = now[dir] ?: return "data was deleted ($dir)"
            if (prior.any { it !in cur }) return "data was deleted ($dir)"
        }
        for ((dir, cur) in now) {
            val prior = state.seen[dir]
            if (prior != null && prior.isNotEmpty()) {
                val newest = prior.max()
                if (cur.any { it < newest && it !in prior }) return "older data arrived ($dir)"
            } else if (dir.startsWith("opt/")) {
                val u = dir.split('/')[1]
                val mark = state.marks["idx|$u"]
                if (mark != null && cur.isNotEmpty() && cur.first() <= mark) return "options arrived for days already read ($u)"
            }
        }
        return null
    }

    // ---- the run ---------------------------------------------------------------------------------------------------------

    /**
     * Read what is new in [files] into [state] (a fresh state when null or [full]) and return it. [today]'s session is
     * never read (it is not over). [progress] gets a stage and a fraction; [active] false stops at the next file, leaving a
     * consistent state; [checkpoint] is handed the state after each finished part (to save it, so a stopped run resumes).
     */
    fun update(files: Files, state: NeuroState?, today: LocalDate, full: Boolean = false,
               progress: (String, Float) -> Unit = { _, _ -> }, active: () -> Boolean = { true },
               checkpoint: (NeuroState) -> Unit = {}): NeuroState {
        val s = if (state == null || full) NeuroState() else state
        val t = today.toEpochDay().toInt()
        val dayStart = today.minusYears(DAY_LOOKBACK_YEARS).toEpochDay().toInt()
        val pairStart = today.minusYears(PAIR_LOOKBACK_YEARS).toEpochDay().toInt()
        val ixs = indices(files)
        if (ixs.isEmpty()) { s.seen.clear(); return s }
        // A fresh state notes what it starts from, so a stopped full rebuild resumes incrementally instead of starting over.
        if (s.seen.isEmpty()) s.seen.putAll(windows(files))

        // 1. the index sessions
        val minIdxMark = ixs.minOf { s.marks["idx|$it"] ?: (dayStart - 1) }
        val vix = TreeMap<Int, Double>()
        if (VIX in ixs) for (b in dailyBars(files, Plan.Group.IDX, VIX, minIdxMark - 90)) vix[b.day] = b.c
        for ((i, u) in ixs.withIndex()) {
            if (!active()) return s
            progress("$u sessions and options", 0.3f * i / ixs.size)
            ingestIndex(files, s, u, t, dayStart, vix, active)
            checkpoint(s)
        }

        // 2. daily pairs
        if (!active()) return s
        dailyPairs(files, s, ixs, t, pairStart, progress, active, checkpoint)

        // 3. minute leads
        if (!active()) return s
        leads(files, s, ixs, t, pairStart, progress, active, checkpoint)
        if (!active()) return s

        // rolling: older than the lookbacks goes
        for (m in s.rows.values) m.headMap(dayStart).clear()
        val q0 = quarter(pairStart)
        for (m in s.pairs.values) m.headMap(q0).clear()
        s.pairs.entries.removeIf { it.value.isEmpty() }
        for (e in s.expiries.values) e.headSet(dayStart).clear()
        s.seen.clear(); s.seen.putAll(windows(files))
        s.builds++
        progress("Learned", 1f)
        return s
    }

    // ---- 1. index sessions ---------------------------------------------------------------------------------------------

    private class Bar(val day: Int, val o: Double, val h: Double, val l: Double, val c: Double)

    /** [sym]'s daily candles from [fromDay] on, oldest first, each day once - only the files that can hold those days are read. */
    private fun dailyBars(files: Files, group: Plan.Group, sym: String, fromDay: Int): List<Bar> {
        val dir = "${group.dir}/${Plan.safe(sym)}/day"
        val ws = files.dated(dir)
        val out = TreeMap<Int, Bar>()
        for ((i, w) in ws.withIndex()) {
            val next = ws.getOrNull(i + 1)
            if (next != null && next.toEpochDay() <= fromDay) continue
            for (l in files.lines("$dir/$w.csv.gz")) {
                val c = files.parseCandle(l) ?: continue
                val d = epochDay(c.t)
                if (d >= fromDay && c.close > 0) out[d] = Bar(d, c.open, c.high, c.low, c.close)
            }
        }
        return out.values.toList()
    }

    /** The last day in [u]'s newest options window. */
    private fun lastOptionDay(files: Files, u: String, flag: String, w: LocalDate): Int? {
        val dir = files.file("opt/${Plan.safe(u)}/$flag/$w")
        val f = dir.listFiles()?.sortedBy { it.name }?.let { l -> l.firstOrNull { it.name == "CE+0.csv.gz" } ?: l.firstOrNull() } ?: return null
        var last: Int? = null
        for (l in files.lines(f)) files.parseOption(l)?.let { last = max(last ?: Int.MIN_VALUE, epochDay(it.t)) }
        return last
    }

    private fun ingestIndex(files: Files, s: NeuroState, u: String, today: Int, dayStart: Int, vix: TreeMap<Int, Double>, active: () -> Boolean) {
        val key = "idx|$u"
        val wm = s.marks[key] ?: (dayStart - 1)
        val bars = dailyBars(files, Plan.Group.IDX, u, wm - 60)
        if (bars.size < 2) return
        val isVix = u == VIX
        var end = min(today - 1, bars.last().day)
        val opt = TreeMap<Int, OptDay>()
        val flag = optionFlag(files, u)
        if (flag != null && !isVix) {
            val ws = files.dated("opt/${Plan.safe(u)}/$flag")
            if (ws.isNotEmpty()) {
                val optLast = lastOptionDay(files, u, flag, ws.last())
                // Wait for the options of the newest days (both come with each download) - unless they stopped long ago.
                if (optLast != null && optLast >= end - 10) end = min(end, optLast)
                for (w in ws) {
                    val w0 = w.toEpochDay().toInt()
                    if (w0 + Plan.OPT_WINDOW_DAYS <= wm || w0 > end) continue
                    if (!active()) return
                    opt.putAll(OptionDays.read(files, u, flag, w))
                }
            }
        }
        if (end <= wm) return
        val rows = s.rows.getOrPut(u) { TreeMap() }
        val closes = bars.map { it.c }
        for (i in 1 until bars.size) {
            val b = bars[i]
            if (b.day <= wm || b.day > end) continue
            val prevBar = bars[i - 1]
            val prev = prevBar.c
            val avgRange = if (i >= 21) (i - 20 until i).map { j -> (bars[j].h - bars[j].l) / bars[j - 1].c * 100 }.average() else Double.NaN
            var mask = Rules.daily(prev, b.o, b.h, b.l, b.c, avgRange, isVix)
            var cov = DayRow.COV_DAILY
            val prior = closes.subList(max(0, i - 25), i)
            val regime = if (isVix) -1 else Rules.trend(prior)?.ordinal ?: -1
            val vixBefore = vix.lowerEntry(b.day)?.takeIf { b.day - it.key <= 7 }?.value
            val hv = !isVix && Rules.highVol(vixBefore, prior)
            var zth = -1
            var iv = Float.NaN
            opt[b.day]?.let { o ->
                cov = cov or DayRow.COV_OPT
                if (o.zthMinute >= 0) { mask = mask or Ev.ZERO_TO_HERO.bit; zth = o.zthMinute }
                if (o.htz) mask = mask or Ev.HERO_TO_ZERO.bit
                if (o.oiStart > 0) {
                    val ch = (o.oiEnd / o.oiStart - 1) * 100
                    if (ch >= Rules.OI_PCT) mask = mask or Ev.OI_BUILDUP.bit
                    if (ch <= -Rules.OI_PCT) mask = mask or Ev.OI_UNWIND.bit
                }
                iv = o.ivClose.toFloat()
                val before = rows.lowerEntry(b.day)?.takeIf { it.key == prevBar.day }?.value?.iv
                if (!iv.isNaN() && before != null && !before.isNaN() && before > 0 && (iv / before - 1) * 100 >= Rules.IV_SPIKE_PCT) mask = mask or Ev.IV_SPIKE.bit
                if (o.lateExpansion) mask = mask or Ev.STRADDLE_EXPANSION.bit
            }
            rows[b.day] = DayRow(b.day, mask, cov, regime, hv, zth, iv, (b.c / prev - 1).toFloat())
        }
        if (!isVix && DhanUniverse.index(u)?.options == true) {
            val ex = s.expiries.getOrPut(u) { java.util.TreeSet() }
            for (d in files.listedExpiries(u)) ex += d.toEpochDay().toInt()
            for ((d, o) in opt) if (o.expiryLike) ex += d
        }
        s.marks[key] = end
    }

    // ---- 2. daily pairs ------------------------------------------------------------------------------------------------

    /** (partner group, partner symbol, index): each company with each index it is in, and each other index with Nifty. */
    fun dailyPartners(files: Files, ixs: List<String>): List<Triple<Plan.Group, String, String>> {
        val out = ArrayList<Triple<Plan.Group, String, String>>()
        for (u in ixs) for (sym in DhanUniverse.CONSTITUENTS[u] ?: emptyList())
            if (files.file("eq/${Plan.safe(sym)}/day").isDirectory) out += Triple(Plan.Group.EQ, sym, u)
        if ("NIFTY" in ixs) for (x in ixs) if (x != "NIFTY") out += Triple(Plan.Group.IDX, x, "NIFTY")
        return out
    }

    fun partnerKey(group: Plan.Group, sym: String) = "${group.dir}:$sym"

    private fun dailyPairs(files: Files, s: NeuroState, ixs: List<String>, today: Int, pairStart: Int, progress: (String, Float) -> Unit,
                           active: () -> Boolean, checkpoint: (NeuroState) -> Unit) {
        val partners = dailyPartners(files, ixs)
        if (partners.isEmpty()) return
        fun mark(p: Triple<Plan.Group, String, String>) = s.marks["D|${partnerKey(p.first, p.second)}|${p.third}"] ?: (pairStart - 1)
        // Each index's daily returns from the earliest watermark of its pairs: (previous day, return) by day.
        val idxRet = HashMap<String, TreeMap<Int, Pair<Int, Double>>>()
        for (u in partners.map { it.third }.distinct()) {
            val from = partners.filter { it.third == u }.minOf { mark(it) } - 10
            val bars = dailyBars(files, Plan.Group.IDX, u, from)
            val m = TreeMap<Int, Pair<Int, Double>>()
            for (i in 1 until bars.size) m[bars[i].day] = bars[i - 1].day to (bars[i].c / bars[i - 1].c - 1)
            idxRet[u] = m
        }
        val byPartner = partners.groupBy { it.first to it.second }.toSortedMap(compareBy({ it.first }, { it.second }))
        var done = 0
        for ((pk, ps) in byPartner) {
            if (!active()) return
            progress("${pk.second} against its index", 0.3f + 0.2f * done / byPartner.size)
            val from = ps.minOf { mark(it) } - 10
            val bars = dailyBars(files, pk.first, pk.second, from)
            for (p in ps) {
                val key = "D|${partnerKey(p.first, p.second)}|${p.third}"
                val wm = mark(p)
                val ir = idxRet[p.third] ?: continue
                if (bars.size < 2 || ir.isEmpty()) continue
                val end = min(today - 1, min(bars.last().day, ir.lastKey()))
                if (end <= wm) continue
                val acc = s.pairs.getOrPut(key) { TreeMap() }
                for (i in 1 until bars.size) {
                    val d = bars[i].day
                    if (d <= wm || d > end) continue
                    val (prevDay, y) = ir[d] ?: continue
                    if (prevDay != bars[i - 1].day) continue
                    val x = bars[i].c / bars[i - 1].c - 1
                    val a = acc.getOrPut(quarter(d)) { DoubleArray(D_LEN) }
                    a[0] += 1.0; a[1] += x; a[2] += y; a[3] += x * x; a[4] += y * y; a[5] += x * y
                    val same = (x > 0 && y > 0) || (x < 0 && y < 0)
                    if (kotlin.math.abs(y) * 100 >= Rules.BIG_MOVE_PCT) {
                        a[6] += 1.0
                        if (same) a[7] += 1.0
                        a[8] += (x / y).coerceIn(-5.0, 5.0)
                    }
                    if (same) a[9] += 1.0
                }
                s.marks[key] = end
            }
            done++
            if (done % 20 == 0) checkpoint(s)
        }
        checkpoint(s)
    }

    // ---- 3. minute leads -----------------------------------------------------------------------------------------------

    /** (partner group, partner symbol) of each index with minute candles: its companies, and for Nifty the other indices. */
    fun minutePartners(files: Files, u: String, ixs: List<String>): List<Pair<Plan.Group, String>> {
        val out = ArrayList<Pair<Plan.Group, String>>()
        for (sym in DhanUniverse.CONSTITUENTS[u] ?: emptyList()) if (files.file("eq/${Plan.safe(sym)}/min").isDirectory) out += Plan.Group.EQ to sym
        if (u == "NIFTY") for (x in ixs) if (x != "NIFTY" && x != VIX && files.file("idx/${Plan.safe(x)}/min").isDirectory) out += Plan.Group.IDX to x
        return out
    }

    /** A minute file's days after [after]: each a row of closes by minute slot (9:15 = 0), NaN where none. */
    private fun minuteDays(files: Files, path: String, after: Int): TreeMap<Int, FloatArray> {
        val out = TreeMap<Int, FloatArray>()
        for (l in files.lines(path)) {
            val c = files.parseCandle(l) ?: continue
            val d = epochDay(c.t)
            if (d <= after) continue
            val slot = OptionDays.minuteOfDay(c.t) - OPEN
            if (slot < 0 || slot >= SLOTS || c.close <= 0) continue
            out.getOrPut(d) { FloatArray(SLOTS) { Float.NaN } }[slot] = c.close.toFloat()
        }
        return out
    }

    /**
     * The day's correlations of [p]'s one-minute returns with [u]'s [L] minutes later, for L = -MAX_LAG..MAX_LAG
     * (positive L: [p] moved first); null when any lag has fewer than [MIN_MINUTES] minute pairs.
     */
    fun lagCorr(p: FloatArray, u: FloatArray, maxLag: Int = MAX_LAG, minMinutes: Int = MIN_MINUTES): DoubleArray? {
        val n = min(p.size, u.size)
        fun ret(a: FloatArray, i: Int): Double = if (i < 1 || i >= n) Double.NaN else {
            val x = a[i]; val y = a[i - 1]
            if (x.isNaN() || y.isNaN() || y <= 0f) Double.NaN else x.toDouble() / y - 1
        }
        val rp = DoubleArray(n) { ret(p, it) }
        val ru = DoubleArray(n) { ret(u, it) }
        val out = DoubleArray(2 * maxLag + 1)
        for (lag in -maxLag..maxLag) {
            var k = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            for (i in 1 until n) {
                val j = i + lag
                if (j < 1 || j >= n) continue
                val x = rp[i]; val y = ru[j]
                if (x.isNaN() || y.isNaN()) continue
                k++; sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
            }
            if (k < minMinutes) return null
            val r = Stats.pearson(k, sx, sy, sxx, syy, sxy)
            if (r.isNaN()) return null
            out[lag + maxLag] = r
        }
        return out
    }

    private fun leads(files: Files, s: NeuroState, ixs: List<String>, today: Int, pairStart: Int, progress: (String, Float) -> Unit,
                      active: () -> Boolean, checkpoint: (NeuroState) -> Unit) {
        val bases = ixs.filter { it != VIX && files.file("idx/${Plan.safe(it)}/min").isDirectory }
        for ((bi, u) in bases.withIndex()) {
            if (!active()) return
            progress("$u minute by minute", 0.5f + 0.45f * bi / bases.size)
            val partners = minutePartners(files, u, ixs)
            if (partners.isEmpty()) continue
            val uLast = files.lastDay(Plan.Group.IDX, u, "min")?.toEpochDay()?.toInt() ?: continue
            val keys = partners.associateWith { "L|${partnerKey(it.first, it.second)}|$u" }
            val wms = partners.associateWith { s.marks[keys.getValue(it)] ?: (pairStart - 1) }
            val ends = partners.associateWith { p ->
                val pl = files.lastDay(p.first, p.second, "min")?.toEpochDay()?.toInt() ?: Int.MIN_VALUE
                min(today - 1, min(uLast, pl))
            }
            val todo = partners.filter { ends.getValue(it) > wms.getValue(it) }
            if (todo.isEmpty()) continue
            val minWm = todo.minOf { wms.getValue(it) }
            val maxEnd = todo.maxOf { ends.getValue(it) }
            val dir = "idx/${Plan.safe(u)}/min"
            val ws = files.dated(dir)
            // Summed apart and merged only when every window is read: a stopped run adds nothing twice.
            val fresh = HashMap<String, TreeMap<Int, DoubleArray>>()
            for ((i, w) in ws.withIndex()) {
                val next = ws.getOrNull(i + 1)
                if (next != null && next.toEpochDay() <= minWm + 1) continue
                if (w.toEpochDay() > maxEnd) continue
                if (!active()) return
                val uDays = minuteDays(files, "$dir/$w.csv.gz", minWm)
                if (uDays.isEmpty()) continue
                for (p in todo) {
                    if (!active()) return
                    val wm = wms.getValue(p); val end = ends.getValue(p)
                    val path = "${p.first.dir}/${Plan.safe(p.second)}/min/$w.csv.gz"
                    if (!files.has(path)) continue
                    val pDays = minuteDays(files, path, wm)
                    val acc = fresh.getOrPut(keys.getValue(p)) { TreeMap() }
                    for ((d, pc) in pDays) {
                        if (d > end) break
                        val uc = uDays[d] ?: continue
                        val r = lagCorr(pc, uc) ?: continue
                        val a = acc.getOrPut(quarter(d)) { DoubleArray(L_LEN) }
                        a[0] += 1.0
                        for (k in r.indices) { a[1 + 2 * k] += r[k]; a[2 + 2 * k] += r[k] * r[k] }
                    }
                }
            }
            // Every window up to each pair's end was read: its sums go in and its watermark moves there.
            for ((k, m) in fresh) {
                val acc = s.pairs.getOrPut(k) { TreeMap() }
                for ((q, a) in m) { val b = acc.getOrPut(q) { DoubleArray(L_LEN) }; for (j in a.indices) b[j] += a[j] }
            }
            for (p in todo) s.marks[keys.getValue(p)] = ends.getValue(p)
            s.pairs.entries.removeIf { it.value.isEmpty() }
            checkpoint(s)
        }
    }
}
