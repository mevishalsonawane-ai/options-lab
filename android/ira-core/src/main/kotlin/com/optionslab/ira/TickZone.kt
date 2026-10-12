package com.optionslab.ira

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The tick zone (Data Brain L1b; ARCHITECTURE.md "Changes the data dictionary forces (12 Oct)", items 1, 2 and 4): a
 * per-second order-flow recording of a fixed watch-list, kept apart from the per-minute grid. Pure logic: the watch-list,
 * the row shapes, the per-minute order-flow formulas, the gap runs, the bounded hand-off queue, the raw files' rotation and
 * the card's lines. The app's [com.optionslab.app.data.TickZoneRecorder] copies what the price stream already holds in
 * memory once a second and writes these rows in the background.
 *
 * The watch-list ([watchList]): BANKNIFTY and NIFTY near-month futures, the ATM +/-[EACH_SIDE] calls and puts of each
 * index's current (nearest) expiry, and India VIX - about 31 instruments, all in Kite's full mode.
 *
 * Two files a day under noBackupFilesDir/recordings/YYYY-MM-DD/, both plain gzip written a member at a time (as
 * [LiveRecord]'s market file):
 *  - [TICKS_FILE]: a header ("t":"H"), the watch-list whenever it changes ("t":"I"), one row a second per instrument
 *    ("t":"T": LTP, cumulative volume, average traded price, OI, total buy and sell quantity, five bid and five ask levels
 *    as [price, qty, orders]) and every second not recorded as a gap run ("t":"G": from, to, why). Kept [KEEP_TICK_DAYS] days.
 *  - [FLOW_FILE]: one row a minute per instrument ("t":"A"): OHLC, volume, VWAP, the tick-rule delta and CVD, order-book
 *    imbalance (average and at the close), average spread, large prints and the absorption flag. Kept as the recorder's
 *    days ([LiveRecord.keepDays]).
 *
 * Every T and A row carries "precision":"exact": these values come from the exchange's own ticks, unlike the 1-minute
 * estimates before 12 Oct (item 4). The formulas carry [FORMULA] ("v").
 */
object TickZone {
    const val SCHEMA = 1
    /** The order-flow formulas' version (written on every minute row). */
    const val FORMULA = 1
    /** Strikes either side of the money followed. */
    const val EACH_SIDE = 3
    val INDICES = listOf("BANKNIFTY", "NIFTY")
    /** The watch-list at most (2 futures + 2 x 7 x 2 options + VIX = 31; far inside Kite's 3000 a connection). */
    const val MAX_INSTRUMENTS = 40

    const val TICKS_FILE = "ticks.jsonl.gz"
    const val FLOW_FILE = "orderflow_1m.jsonl.gz"
    const val EXACT = "exact"

    /** Raw per-second files are kept this many days (today counts as one), then deleted. */
    const val KEEP_TICK_DAYS = 10
    /** A day's tick file stops growing past this (the minute aggregates go on). About 30-60 MB is normal. */
    const val TICK_DAY_BUDGET_BYTES = 150_000_000L
    /** All the kept tick files at most (the oldest go first, never today's). */
    const val TICK_TOTAL_CAP_BYTES = 1_000_000_000L
    /** Seconds waiting for the writer at most (ten minutes; past it the oldest second is dropped and recorded as a gap). */
    const val QUEUE_CAP = 600
    /** A tick older than this is not a snapshot of now: that second is a gap ("stale") for the instrument. */
    const val STALE_MS = 60_000L

    /** Large print: a second's traded volume above this quantile of the day's earlier seconds. */
    const val LARGE_Q = 0.99
    /** ... once the day has this many sized seconds for the instrument (before that no threshold, no large prints). */
    const val LARGE_MIN_SAMPLES = 200
    /** Absorption: at least this share of the minute's volume traded at one price, ... */
    const val ABSORB_SHARE = 0.6
    /** ... the minute's volume at least this many times the day's average minute so far, ... */
    const val ABSORB_VOL_MULT = 2.0
    /** ... with this many earlier minutes to average, ... */
    const val ABSORB_MIN_MINUTES = 5
    /** ... and the minute's range at most the larger of this (rupees, two option ticks) and [ABSORB_RANGE_FRAC] of the price. */
    const val ABSORB_RANGE_MIN = 0.10
    const val ABSORB_RANGE_FRAC = 0.0002

    enum class Kind { FUT, CE, PE, VIX }

    /** One instrument followed. [strike] only for an option. */
    data class Inst(val token: Long, val symbol: String, val und: String, val kind: Kind, val strike: Double? = null, val expiry: LocalDate? = null)

    data class Level(val price: Double, val qty: Long, val orders: Int)

    /**
     * One instrument's state at one second, as copied from the stream's memory. [ageMs]: how old the tick was. Volume,
     * average price, quantities and depth are null where the packet has none (VIX: an index packet).
     */
    data class Snap(
        val token: Long, val ltp: Double, val volume: Long?, val atp: Double?, val oi: Long?, val buyQty: Long?, val sellQty: Long?,
        val lastQty: Long?, val lastTradeSec: Long?, val exchSec: Long?, val bids: List<Level>, val asks: List<Level>, val ageMs: Long,
    )

    /**
     * One second as the sampler saw it: [list] the watch-list then, [snaps] the instruments with a fresh tick and [missing]
     * the others with why ("no tick", "stale", "stream down"). [prevSec]: the second the sampler sampled before this one
     * (null when it has just started or resumed), so a late sampler's skipped seconds are known.
     */
    class Second(val sec: Long, val prevSec: Long?, val list: List<Inst>, val snaps: List<Snap>, val missing: List<Pair<Long, String>>)

    // ---- the watch-list -----------------------------------------------------------------------------------------------

    /**
     * The instruments to follow: each index's near future ([futures]), the [each]-either-side strikes around its spot
     * ([spots]; else its future's price) of its nearest expiry from [options] (both rights), and [vix]. At most [MAX_INSTRUMENTS].
     */
    fun watchList(
        futures: Map<String, Inst>, options: Map<String, List<Inst>>, spots: Map<String, Double?>, vix: Inst?, each: Int = EACH_SIDE,
    ): List<Inst> {
        val out = ArrayList<Inst>()
        for (ix in INDICES) {
            futures[ix]?.let { out += it }
            val opts = options[ix].orEmpty().filter { it.kind == Kind.CE || it.kind == Kind.PE }
            val near = opts.mapNotNull { it.expiry }.minOrNull()
            val cur = opts.filter { it.expiry == near && it.strike != null }
            val spot = spots[ix]?.takeIf { it > 0 } ?: continue
            val keep = MarketRecord.around(cur.mapNotNull { it.strike }, spot, each).toSet()
            out += cur.filter { it.strike in keep }.sortedWith(compareBy({ it.strike }, { it.kind }))
        }
        vix?.let { out += it }
        return out.distinctBy { it.token }.take(MAX_INSTRUMENTS)
    }

    // ---- the per-minute order flow --------------------------------------------------------------------------------------

    /** One instrument's minute ("t":"A"). Volumes are contracts' quantity; [delta] = [buy] - [sell]. */
    data class Agg(
        val token: Long, val minuteSec: Long, val open: Double, val high: Double, val low: Double, val close: Double,
        val volume: Long, val buy: Long, val sell: Long, val unsigned: Long, val delta: Long, val cvd: Long, val cvdFromSec: Long?,
        val vwap: Double?, val oi: Long?, val obiAvg: Double?, val obiClose: Double?, val depthObiAvg: Double?, val spreadAvg: Double?,
        val largeCount: Int, val largeVolume: Long, val largeThreshold: Long?, val absorb: Boolean, val absorbPrice: Double?,
        val absorbSide: String?, val seconds: Int, val missing: Int,
    )

    /** Order-book imbalance from the whole book's totals: (buy - sell) / (buy + sell), in -1..1; null without both. */
    fun imbalance(buyQty: Long?, sellQty: Long?): Double? {
        if (buyQty == null || sellQty == null || buyQty < 0 || sellQty < 0) return null
        val sum = buyQty + sellQty
        return if (sum <= 0) null else (buyQty - sellQty).toDouble() / sum
    }

    /** Best ask minus best bid; null without both or when crossed. */
    fun spread(bids: List<Level>, asks: List<Level>): Double? {
        val b = bids.firstOrNull()?.price?.takeIf { it > 0 } ?: return null
        val a = asks.firstOrNull()?.price?.takeIf { it > 0 } ?: return null
        return if (a >= b) a - b else null
    }

    /** The tick rule: price up = buy (+1), down = sell (-1), unchanged = the previous side ([prevSide]; 0 = not known yet). */
    fun side(ltp: Double, prevLtp: Double?, prevSide: Int): Int = when {
        prevLtp == null -> prevSide
        ltp > prevLtp -> 1
        ltp < prevLtp -> -1
        else -> prevSide
    }

    /** The nearest-rank [q] quantile of a size histogram (value -> count, ascending); null when empty. */
    fun quantile(counts: java.util.SortedMap<Long, Int>, total: Long, q: Double): Long? {
        if (total <= 0) return null
        val rank = kotlin.math.ceil(q * total).toLong().coerceIn(1, total)
        var seen = 0L
        for ((v, n) in counts) { seen += n; if (seen >= rank) return v }
        return counts.lastKey()
    }

    /**
     * Absorption: big volume at one price with the price not moving. True when [vol] is at least [ABSORB_VOL_MULT] times
     * [avgPrior] (the day's average minute so far, over [priorMinutes] >= [ABSORB_MIN_MINUTES]), at least [ABSORB_SHARE] of it
     * traded at one price ([topVol]), and the minute's range [high] - [low] is at most max([ABSORB_RANGE_MIN],
     * [ABSORB_RANGE_FRAC] x [close]).
     */
    fun absorbed(vol: Long, topVol: Long, avgPrior: Double?, priorMinutes: Int, high: Double, low: Double, close: Double): Boolean {
        if (vol <= 0 || avgPrior == null || avgPrior <= 0 || priorMinutes < ABSORB_MIN_MINUTES) return false
        if (vol < ABSORB_VOL_MULT * avgPrior) return false
        if (topVol.toDouble() / vol < ABSORB_SHARE) return false
        return high - low <= maxOf(ABSORB_RANGE_MIN, ABSORB_RANGE_FRAC * close) + 1e-9
    }

    /**
     * One instrument's order flow through one day, fed a second at a time in order ([add] for a recorded second, [miss] for
     * one not recorded). A second's traded volume is the rise in cumulative volume since the last recorded second, signed
     * by the tick rule. After a gap the rise spans several seconds: it still counts in the delta but is not a "size" (no
     * large print, not in the size histogram). CVD runs from the first second recorded today ([cvdFromSec]): a restart in
     * the day restarts it. Large prints use the day's sizes from EARLIER minutes only (point-in-time). Not thread-safe:
     * the writer's thread only.
     */
    class Flow(val token: Long) {
        private var prevLtp: Double? = null
        private var prevVol: Long? = null
        private var prevSec: Long? = null
        private var prevSide = 0
        var cvd = 0L
            private set
        var cvdFromSec: Long? = null
            private set
        private val sizes = java.util.TreeMap<Long, Int>()
        private var sizeCount = 0L
        private var priorVol = 0L
        private var priorMinutes = 0

        // The minute being built.
        private var minute: Long? = null
        private var o = 0.0; private var h = 0.0; private var l = 0.0; private var c = 0.0
        private var priced = false
        private var vol = 0L; private var buy = 0L; private var sell = 0L; private var unsigned = 0L
        private var obiSum = 0.0; private var obiN = 0; private var obiClose: Double? = null
        private var dObiSum = 0.0; private var dObiN = 0
        private var spreadSum = 0.0; private var spreadN = 0
        private var vwap: Double? = null; private var oi: Long? = null
        private val minuteSizes = ArrayList<Long>()
        private val atPrice = HashMap<Long, LongArray>()      // price in paise -> [volume, signed volume]
        private var secs = 0; private var missing = 0

        /** The minute holding [sec] ends the one before: returned when it had any second. */
        fun roll(sec: Long): Agg? {
            val m = sec - Math.floorMod(sec, 60L)
            val cur = minute
            if (cur == m) return null
            val out = if (cur != null && cur < m) close() else null
            if (cur == null || cur < m) startMinute(m)
            return out
        }

        /** A recorded second. */
        fun add(sec: Long, s: Snap): Agg? {
            val out = roll(sec)
            secs++
            if (cvdFromSec == null) cvdFromSec = sec
            val ltp = s.ltp
            if (ltp > 0) {
                if (!priced) { o = ltp; h = ltp; l = ltp; priced = true }
                h = maxOf(h, ltp); l = minOf(l, ltp); c = ltp
            }
            val v = s.volume
            var dv = 0L
            if (v != null) {
                val pv = prevVol
                dv = if (pv == null || v < pv) 0L else v - pv
                prevVol = v
            }
            val sd = if (ltp > 0) side(ltp, prevLtp, prevSide) else prevSide
            if (dv > 0) {
                vol += dv
                when (sd) { 1 -> buy += dv; -1 -> sell += dv; else -> unsigned += dv }
                if (prevSec == sec - 1) minuteSizes += dv
                if (ltp > 0) atPrice.getOrPut(Math.round(ltp * 100)) { LongArray(2) }.let { it[0] += dv; it[1] += sd * dv }
            }
            if (ltp > 0) { prevLtp = ltp; prevSide = sd }
            prevSec = sec
            imbalance(s.buyQty, s.sellQty)?.let { obiSum += it; obiN++; obiClose = it }
            if (s.bids.isNotEmpty() || s.asks.isNotEmpty()) imbalance(s.bids.sumOf { it.qty }, s.asks.sumOf { it.qty })?.let { dObiSum += it; dObiN++ }
            spread(s.bids, s.asks)?.let { spreadSum += it; spreadN++ }
            s.atp?.takeIf { it > 0 }?.let { vwap = it }
            s.oi?.takeIf { it > 0 }?.let { oi = it }
            return out
        }

        /** A second not recorded for this instrument. */
        fun miss(sec: Long): Agg? {
            val out = roll(sec)
            missing++
            return out
        }

        private fun startMinute(m: Long) {
            minute = m; priced = false; o = 0.0; h = 0.0; l = 0.0; c = 0.0
            vol = 0; buy = 0; sell = 0; unsigned = 0
            obiSum = 0.0; obiN = 0; obiClose = null; dObiSum = 0.0; dObiN = 0; spreadSum = 0.0; spreadN = 0
            vwap = null; oi = null; minuteSizes.clear(); atPrice.clear(); secs = 0; missing = 0
        }

        /** The minute being built, closed (null when it had no second at all); the day's histories then take it in. */
        fun close(): Agg? {
            val m = minute ?: return null
            minute = null
            if (secs == 0 && missing == 0) return null
            val delta = buy - sell
            cvd += delta
            val threshold = if (sizeCount >= LARGE_MIN_SAMPLES) quantile(sizes, sizeCount, LARGE_Q) else null
            val large = threshold?.let { t -> minuteSizes.filter { it > t } }.orEmpty()
            val top = atPrice.maxByOrNull { it.value[0] }
            val avgPrior = if (priorMinutes > 0) priorVol.toDouble() / priorMinutes else null
            val absorb = priced && top != null && absorbed(vol, top.value[0], avgPrior, priorMinutes, h, l, c)
            val agg = Agg(
                token, m, o, h, l, c, vol, buy, sell, unsigned, delta, cvd, cvdFromSec, vwap, oi,
                if (obiN > 0) obiSum / obiN else null, obiClose, if (dObiN > 0) dObiSum / dObiN else null,
                if (spreadN > 0) spreadSum / spreadN else null, large.size, large.sum(), threshold,
                absorb, if (absorb) top!!.key / 100.0 else null,
                if (absorb) top!!.value[1].let { sv -> if (sv > 0) "ask" else if (sv < 0) "bid" else null } else null,
                secs, missing,
            )
            for (x in minuteSizes) { sizes.merge(x, 1, Int::plus); sizeCount++ }
            if (secs > 0) { priorVol += vol; priorMinutes++ }
            return agg
        }
    }

    // ---- gap runs ------------------------------------------------------------------------------------------------------

    /** A run of seconds not recorded: [key] the instrument's token (0: every instrument), [from]..[to] inclusive. */
    data class Run(val key: Long, val from: Long, val to: Long, val why: String) { val seconds: Long get() = to - from + 1 }

    /** Missing seconds joined into runs (one line per run, not per second). Not thread-safe: the writer's thread only. */
    class Gaps {
        private val open = HashMap<Long, Run>()

        /** [key] missed [sec] for [why]: extends its run, or closes it (returned) and opens a new one. */
        fun miss(key: Long, sec: Long, why: String): Run? {
            val r = open[key]
            if (r != null && r.why == why && sec == r.to + 1) { open[key] = r.copy(to = sec); return null }
            if (r != null && sec <= r.to) return null
            open[key] = Run(key, sec, sec, why)
            return r
        }

        /** [key] recorded [sec]: its open run, if any, closed and returned. */
        fun ok(key: Long): Run? = open.remove(key)

        /** Every open run closed (the day's end, the market's close, a pause). */
        fun closeAll(): List<Run> = open.values.sortedWith(compareBy({ it.from }, { it.key })).also { open.clear() }
    }

    /**
     * The sampler's hand-off to the writer: [offer] is a short synchronized add (never a wait, never I/O); past [cap] the
     * oldest second is dropped, counted and remembered as a run, so the writer records it as a gap ("queue full").
     */
    class SecondQueue(private val cap: Int = QUEUE_CAP) {
        private val items = ArrayDeque<Second>()
        private val droppedRuns = ArrayList<LongArray>()
        @Volatile var dropped = 0L
            private set

        fun offer(x: Second) {
            synchronized(items) {
                items.addLast(x)
                while (items.size > cap) {
                    val d = items.removeFirst().sec
                    dropped++
                    val last = droppedRuns.lastOrNull()
                    if (last != null && last[1] == d - 1) last[1] = d else droppedRuns += longArrayOf(d, d)
                }
            }
        }

        /** Everything waiting, oldest first, and the runs of seconds dropped since the last drain. */
        fun drain(): Pair<List<Second>, List<LongRange>> = synchronized(items) {
            val out = ArrayList(items) to droppedRuns.map { it[0]..it[1] }
            items.clear(); droppedRuns.clear()
            out
        }

        fun size(): Int = synchronized(items) { items.size }
    }

    // ---- the rows -------------------------------------------------------------------------------------------------------

    private val HMS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private fun clock(sec: Long, f: DateTimeFormatter): String = Instant.ofEpochSecond(sec).atZone(MarketRecord.IST).toLocalTime().format(f)

    /** IST day of an epoch second. */
    fun dayOf(sec: Long): LocalDate = Instant.ofEpochSecond(sec).atZone(MarketRecord.IST).toLocalDate()

    /** IST time of an epoch second. */
    fun timeOf(sec: Long): LocalTime = Instant.ofEpochSecond(sec).atZone(MarketRecord.IST).toLocalTime()

    fun header(file: String, day: LocalDate, app: String = ""): String = LiveRecord.obj(
        "t" to "H", "schema" to SCHEMA, "v" to FORMULA, "file" to file, "day" to day.toString(), "tz" to "Asia/Kolkata", "app" to app,
        "s" to "epoch seconds", "eachSide" to EACH_SIDE, "precision" to "exact: from Zerodha's full-mode ticks",
    )

    /** The watch-list as of [sec]: [token, symbol, underlying, kind, strike, expiry] each. */
    fun instruments(sec: Long, list: List<Inst>): String = LiveRecord.obj(
        "t" to "I", "s" to sec, "list" to LiveRecord.Json(list.joinToString(",", "[", "]") { i ->
            "[${i.token},${LiveRecord.str(i.symbol)},${LiveRecord.str(i.und)},${LiveRecord.str(i.kind.name)}," +
                "${i.strike?.let { LiveRecord.num(it) } ?: "null"},${i.expiry?.let { LiveRecord.str(it.toString()) } ?: "null"}]"
        }),
    )

    /** One instrument's second. */
    fun tick(sec: Long, s: Snap): String = LiveRecord.obj(
        "t" to "T", "s" to sec, "k" to s.token, "ltp" to s.ltp, "v" to s.volume, "atp" to s.atp, "oi" to s.oi,
        "tbq" to s.buyQty, "tsq" to s.sellQty, "ltq" to s.lastQty, "ltt" to s.lastTradeSec, "x" to s.exchSec, "age" to s.ageMs,
        "b" to s.bids.takeIf { it.isNotEmpty() }?.let { LiveRecord.Json(levels(it)) },
        "a" to s.asks.takeIf { it.isNotEmpty() }?.let { LiveRecord.Json(levels(it)) },
        "precision" to EXACT,
    )

    private fun levels(l: List<Level>) = l.take(5).joinToString(",", "[", "]") { "[${LiveRecord.num(it.price)},${it.qty},${it.orders}]" }

    /** A run of seconds not recorded ("k" absent: every instrument). */
    fun gap(r: Run): String = LiveRecord.obj(
        "t" to "G", "from" to r.from, "to" to r.to, "n" to r.seconds, "k" to r.key.takeIf { it != 0L }, "why" to r.why,
        "at" to clock(r.from, HMS),
    )

    /** One instrument's minute of order flow. */
    fun agg(a: Agg, inst: Inst?): String = LiveRecord.obj(
        "t" to "A", "m" to clock(a.minuteSec, HM), "s" to a.minuteSec, "k" to a.token, "sym" to inst?.symbol, "und" to inst?.und,
        "kind" to inst?.kind?.name,
        "o" to a.open.takeIf { a.close > 0 }, "h" to a.high.takeIf { a.close > 0 }, "l" to a.low.takeIf { a.close > 0 }, "c" to a.close.takeIf { it > 0 },
        "vol" to a.volume, "buy" to a.buy, "sell" to a.sell, "unsigned" to a.unsigned, "delta" to a.delta, "cvd" to a.cvd,
        "cvdFrom" to a.cvdFromSec?.let { clock(it, HMS) }, "vwap" to a.vwap, "oi" to a.oi,
        "obi" to a.obiAvg?.let { LiveRecord.Json(LiveRecord.num(it, 4)) }, "obiClose" to a.obiClose?.let { LiveRecord.Json(LiveRecord.num(it, 4)) },
        "obi5" to a.depthObiAvg?.let { LiveRecord.Json(LiveRecord.num(it, 4)) }, "spread" to a.spreadAvg?.let { LiveRecord.Json(LiveRecord.num(it, 4)) },
        "large" to a.largeCount, "largeVol" to a.largeVolume, "p99" to a.largeThreshold,
        "absorb" to a.absorb, "absorbPx" to a.absorbPrice, "absorbSide" to a.absorbSide,
        "secs" to a.seconds, "missing" to a.missing, "precision" to EXACT, "v" to FORMULA,
    )

    /** A line for the minute file: the tick file stopped for the day ([why]: words only). */
    fun note(sec: Long, what: String, why: String): String = LiveRecord.obj("t" to "G", "s" to sec, "at" to clock(sec, HMS), "what" to what, "why" to why)

    // ---- the folder -----------------------------------------------------------------------------------------------------

    /** Today's tick file is full: no more per-second rows today (the minute rows go on). */
    fun overTickBudget(bytes: Long): Boolean = bytes >= TICK_DAY_BUDGET_BYTES

    /**
     * The days whose raw tick file goes: older than [KEEP_TICK_DAYS] days before [today], then, with all the tick files over
     * [cap], the oldest until under it. Today's never goes. [days]: each day's tick file size.
     */
    fun pruneTicks(days: List<LiveRecord.DayDir>, today: LocalDate, keep: Int = KEEP_TICK_DAYS, cap: Long = TICK_TOTAL_CAP_BYTES): List<LocalDate> =
        LiveRecord.rotate(days, today, keep, cap).delete

    // ---- the card -------------------------------------------------------------------------------------------------------

    data class Status(val following: Int, val todayBytes: Long, val tickDays: Int, val dropped: Long, val lastSec: Long?, val full: Boolean = false)

    fun card(s: Status, nowMs: Long): List<Pair<String, String>> {
        val fresh = s.lastSec?.let { nowMs / 1000 - it <= 120 } == true
        return listOf(
            "Tick zone" to when {
                fresh -> "${s.following} instruments, every second"
                s.following > 0 -> "${s.following} instruments (records while the market is open)"
                else -> "waiting for the Zerodha price stream"
            },
            "Ticks today" to (MarketRecord.size(s.todayBytes) + if (s.full) " (full for today)" else ""),
            "Tick days kept" to "${s.tickDays} (raw seconds kept $KEEP_TICK_DAYS days)",
        ) + if (s.dropped > 0) listOf("Seconds dropped (writer behind)" to "${s.dropped}") else emptyList()
    }

    /** The export's README part for the tick zone. */
    val README: String = """
        ticks.jsonl.gz        plain gzip, JSON lines, one row a second per instrument (raw, kept $KEEP_TICK_DAYS days):
                              H header; I watch-list [token,symbol,und,kind,strike,expiry]; T second: s (epoch s), k (token),
                              ltp, v (cumulative volume), atp (average traded price = the day's exact VWAP), oi, tbq/tsq (total
                              buy/sell quantity), ltq/ltt (last trade qty/time), x (exchange time), age (ms), b/a (5 bid/ask
                              levels [price,qty,orders]); G gap runs: from..to (epoch s), k (absent: all), why.
        orderflow_1m.jsonl.gz one row a minute per instrument (A): OHLC, vol, buy/sell/unsigned by the tick rule (up = buy,
                              down = sell, unchanged = previous side), delta, cvd (from cvdFrom), vwap, oi, obi (average of
                              (tbq-tsq)/(tbq+tsq)), obiClose, obi5 (top-5 depth), spread (average ask-bid), large/largeVol
                              (seconds traded above p99, the 99th percentile of the day's earlier seconds), absorb (>= ${(ABSORB_SHARE * 100).toInt()}% of
                              a >= ${ABSORB_VOL_MULT}x-average minute at one price, range <= max($ABSORB_RANGE_MIN, ${ABSORB_RANGE_FRAC} x price)),
                              secs, missing. "precision":"exact" (from ticks); "v": formula version $FORMULA.
    """.trimIndent()
}
