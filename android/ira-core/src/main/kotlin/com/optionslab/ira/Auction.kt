package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The auction around the order flow (Boss, 9 Oct 2026): LIVE READINGS and SHADOW-LOG FIELDS only - never a trading rule
 * (research tests each one separately; any use in a decision comes later, if ever). From the near-month index FUTURE's
 * traded volume, each trade signed by the tick rule ([OrderFlow]):
 *
 *  - the volume profile ([levels]): today's developing one and the prior day's (from its 1-minute candles, [fromCandles]),
 *    in bins of about 0.05% of the price ([binSize]): the point of control (POC, the busiest bin), the value area
 *    (70% of the volume around the POC: VAH / VAL) and the high- and low-volume nodes (HVN / LVN);
 *  - the auction regime against the PRIOR day's value area ([regime]): inside value, or outside above / below - "accepted"
 *    after two consecutive 30-minute closes out there, "testing" until then;
 *  - delta: each minute's and the session's cumulative signed volume, and a delta divergence ([divergence]): a higher high
 *    over the last 15 minutes while the window's delta fell (bearish), or a lower low while it rose (bullish);
 *  - a footprint: the last 15 minutes' buy and sell volume at each traded price ([Day.footprint]);
 *  - VWAP: the session's, with ±1 / ±2 standard-deviation bands, and VWAPs anchored at the day's high and low ([Vwap]);
 *  - the market profile (TPO, 30-minute periods) through [MarketProfile].
 *
 * Pure: no clock (the caller passes the time), no Android. [Day] is fed from the flow's own thread, under the board's lock.
 */
object Auction {
    /** A profile bin is about this share of the price (0.05%: ~27 points on BankNifty, ~12 on Nifty). */
    const val BIN_PCT = 0.0005

    /** The value area's share of the volume. */
    const val VALUE_AREA = 0.70

    /** HVNs and LVNs named, each. */
    const val NODES = 3

    /** The footprint's window (minutes) and the prices it shows. */
    const val FOOT_MIN = 15
    const val FOOT_TOP = 10

    /** The divergence's window (minutes) and its recent part (the new high or low must be in it). */
    const val DIV_WINDOW = 15
    const val DIV_RECENT = 5

    /** The TPO / acceptance period (minutes). */
    const val PERIOD_MIN = 30

    /** India's offset from UTC (seconds): NSE's and MCX's sessions are IST days. */
    const val IST_OFFSET_SEC = 19_800L

    /** The bin for [price]: [BIN_PCT] of it, a whole number of [tick]s, at least one. */
    fun binSize(price: Double, tick: Double = 0.05): Double {
        if (!(price > 0) || !(tick > 0)) return tick.coerceAtLeast(0.05)
        val n = Math.round(price * BIN_PCT / tick).coerceAtLeast(1L)
        return n * tick
    }

    fun binOf(price: Double, bin: Double): Long = floor(price / bin + 1e-9).toLong()

    /** A bin's middle price, to the paisa. */
    fun center(i: Long, bin: Double): Double = Math.round((i + 0.5) * bin * 100) / 100.0

    /** The IST day of [epochSec] (days since the epoch). */
    fun dayOf(epochSec: Long): Long = Math.floorDiv(epochSec + IST_OFFSET_SEC, 86_400L)

    /** The IST minute of the day of [epochSec] (0-1439). */
    fun minuteOfDay(epochSec: Long): Int = (Math.floorMod(epochSec + IST_OFFSET_SEC, 86_400L) / 60).toInt()

    // ---- the profile -----------------------------------------------------------------------------------------------------

    /** A profile's levels: its bin, POC, value area high / low, its HVNs (busiest first) and LVNs (quietest first), its range. */
    data class Levels(
        val bin: Double, val poc: Double, val vah: Double, val vaLow: Double,
        val hvns: List<Double>, val lvns: List<Double>, val volume: Double, val high: Double, val low: Double,
    )

    /**
     * The levels of the volume [vol] (bin index to volume) in bins of [bin]: the POC is the busiest bin (a tie: the one
     * nearest the profile's middle); the value area grows from it one bin at a time to the busier neighbour (a tie: up)
     * until it holds [VALUE_AREA] of the volume; an HVN is a local peak other than the POC, an LVN a local trough inside
     * the profile. Prices are the bins' middles. Null with no volume.
     */
    fun levels(vol: Map<Long, Double>, bin: Double, nodes: Int = NODES): Levels? {
        val keys = vol.filter { it.value > 0 }.keys
        if (keys.isEmpty() || !(bin > 0)) return null
        val lo = keys.min(); val hi = keys.max()
        val n = (hi - lo + 1).toInt()
        val a = DoubleArray(n) { (vol[lo + it] ?: 0.0).coerceAtLeast(0.0) }
        val total = a.sum()
        val mid = (n - 1) / 2.0
        var poc = 0
        for (i in 1 until n) if (a[i] > a[poc] + 1e-9 || (abs(a[i] - a[poc]) <= 1e-9 && abs(i - mid) < abs(poc - mid))) poc = i
        var up = poc; var dn = poc; var acc = a[poc]
        while (acc < VALUE_AREA * total - 1e-9 && (up < n - 1 || dn > 0)) {
            val above = if (up < n - 1) a[up + 1] else -1.0
            val below = if (dn > 0) a[dn - 1] else -1.0
            if (above >= below) { up++; acc += above } else { dn--; acc += below }
        }
        val peaks = (0 until n).filter { i ->
            i != poc && a[i] > 0 && (i == 0 || a[i] > a[i - 1]) && (i == n - 1 || a[i] >= a[i + 1])
        }.sortedByDescending { a[it] }.take(nodes)
        val troughs = (1 until n - 1).filter { i -> a[i] < a[i - 1] && a[i] <= a[i + 1] }.sortedBy { a[it] }.take(nodes)
        return Levels(bin, center(lo + poc, bin), center(lo + up, bin), center(lo + dn, bin),
            peaks.map { center(lo + it, bin) }, troughs.map { center(lo + it, bin) }, total, center(hi, bin), center(lo, bin))
    }

    /** One 1-minute candle of the future (the prior day's, or today's before the stream began). */
    data class Candle(val startSec: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long)

    /**
     * The profile of [candles]: each candle's volume spread evenly over the bins from its low to its high ([bin]: by
     * default [binSize] of the last close). Null with no traded candle.
     */
    fun fromCandles(candles: List<Candle>, bin: Double? = null): Levels? {
        val ref = candles.lastOrNull { it.close > 0 }?.close ?: return null
        val b = bin ?: binSize(ref)
        val vol = HashMap<Long, Double>()
        spread(candles, b) { i, v -> vol[i] = (vol[i] ?: 0.0) + v }
        return levels(vol, b)
    }

    private inline fun spread(candles: List<Candle>, bin: Double, add: (Long, Double) -> Unit) {
        for (c in candles) {
            if (c.volume <= 0 || !(c.high > 0) || !(c.low > 0)) continue
            val lo = binOf(minOf(c.low, c.high), bin); val hi = binOf(maxOf(c.low, c.high), bin)
            val share = c.volume.toDouble() / (hi - lo + 1)
            for (i in lo..hi) add(i, share)
        }
    }

    // ---- the regime ------------------------------------------------------------------------------------------------------

    /** Where the price is against the prior day's value area; [chip] one word for the screens. */
    enum class Regime(val chip: String, val words: String) {
        INSIDE("inside", "inside value"),
        ABOVE_ACCEPTED("accepted↑", "outside above (accepted)"),
        ABOVE_TESTING("testing↑", "outside above (testing)"),
        BELOW_ACCEPTED("accepted↓", "outside below (accepted)"),
        BELOW_TESTING("testing↓", "outside below (testing)"),
    }

    /**
     * [price] against [prior]'s value area: inside (VAL..VAH), else outside above / below - accepted when the last two
     * completed 30-minute closes ([closes30], oldest first) were both out on that side, testing otherwise. Null with no
     * prior profile or price.
     */
    fun regime(prior: Levels?, price: Double, closes30: List<Double>): Regime? {
        if (prior == null || !(price > 0)) return null
        if (price >= prior.vaLow && price <= prior.vah) return Regime.INSIDE
        val above = price > prior.vah
        val last2 = closes30.takeLast(2)
        val accepted = last2.size == 2 && last2.all { if (above) it > prior.vah else it < prior.vaLow }
        return when {
            above && accepted -> Regime.ABOVE_ACCEPTED
            above -> Regime.ABOVE_TESTING
            accepted -> Regime.BELOW_ACCEPTED
            else -> Regime.BELOW_TESTING
        }
    }

    // ---- minutes and delta -----------------------------------------------------------------------------------------------

    /**
     * One minute of the future: OHLC, the tick-rule buy and sell volume and all the volume, Σ price·volume and
     * Σ price²·volume (VWAP and its bands). [signed] false: from a candle (before the stream began), no delta known.
     */
    data class Minute(
        val startSec: Long, val open: Double, val high: Double, val low: Double, val close: Double,
        val buy: Long, val sell: Long, val vol: Long, val pv: Double, val p2v: Double, val signed: Boolean = true,
    ) {
        val delta: Long get() = buy - sell
    }

    /**
     * The closes of the 30-minute periods completed by [nowSec], oldest first; periods count from [openMin] (minute of
     * the IST day: 9:15 for NSE). A period's close is its last minute's.
     */
    fun closes30(minutes: List<Minute>, nowSec: Long, openMin: Int): List<Double> {
        val nowP = periodOf(nowSec, openMin)
        val out = LinkedHashMap<Int, Double>()
        for (m in minutes.sortedBy { it.startSec }) {
            val p = periodOf(m.startSec, openMin)
            if (p in 0 until nowP) out[p] = m.close
        }
        return out.values.toList()
    }

    /** The 30-minute period of [epochSec] from [openMin] (negative before the open). */
    fun periodOf(epochSec: Long, openMin: Int): Int = Math.floorDiv(minuteOfDay(epochSec) - openMin, PERIOD_MIN)

    enum class Divergence(val words: String) {
        BEARISH("bearish delta divergence: a higher high on falling delta"),
        BULLISH("bullish delta divergence: a lower low on rising delta"),
    }

    /**
     * Over the [window] minutes to [nowSec]: a higher high (the last [recent] minutes' high above the earlier minutes')
     * while the window's delta is negative - BEARISH; a lower low while it is positive - BULLISH. Null without two thirds
     * of the window streamed (signed) or with neither.
     */
    fun divergence(minutes: List<Minute>, nowSec: Long, window: Int = DIV_WINDOW, recent: Int = DIV_RECENT): Divergence? {
        val now = Math.floorDiv(nowSec, 60L)
        val w = minutes.filter { val m = Math.floorDiv(it.startSec, 60L); m in (now - window + 1)..now && it.signed }
        if (w.size < (window * 2 + 2) / 3) return null
        val cut = now - recent + 1
        val early = w.filter { Math.floorDiv(it.startSec, 60L) < cut }
        val late = w.filter { Math.floorDiv(it.startSec, 60L) >= cut }
        if (early.isEmpty() || late.isEmpty()) return null
        val d = w.sumOf { it.delta }
        if (late.maxOf { it.high } > early.maxOf { it.high } && d < 0) return Divergence.BEARISH
        if (late.minOf { it.low } < early.minOf { it.low } && d > 0) return Divergence.BULLISH
        return null
    }

    // ---- VWAP --------------------------------------------------------------------------------------------------------------

    /** The session VWAP with its standard deviation, and VWAPs anchored at the day's high and low minutes (null: none). */
    data class Vwap(val vwap: Double, val sd: Double, val atHigh: Double?, val atLow: Double?) {
        /** [price]'s distance from the VWAP in standard deviations (null with no spread). */
        fun sdUnits(price: Double): Double? = if (sd > 1e-9 && price > 0) (price - vwap) / sd else null
        fun band(k: Int): Pair<Double, Double> = (vwap - k * sd) to (vwap + k * sd)
    }

    /** The VWAP of [minutes] (Σpv / Σv), its volume-weighted sd, and the VWAPs from the high's and the low's minute on. */
    fun vwap(minutes: List<Minute>): Vwap? {
        val ms = minutes.filter { it.vol > 0 }.sortedBy { it.startSec }
        val v = ms.sumOf { it.vol }
        if (v <= 0) return null
        val pv = ms.sumOf { it.pv }; val p2v = ms.sumOf { it.p2v }
        val mean = pv / v
        val sd = sqrt((p2v / v - mean * mean).coerceAtLeast(0.0))
        fun from(i: Int): Double? {
            if (i < 0) return null
            val rest = ms.subList(i, ms.size)
            val rv = rest.sumOf { it.vol }
            return if (rv > 0) rest.sumOf { it.pv } / rv else null
        }
        val hi = ms.indices.maxByOrNull { ms[it].high } ?: -1
        val lo = ms.indices.minByOrNull { ms[it].low } ?: -1
        return Vwap(mean, sd, from(hi), from(lo))
    }

    // ---- the day -----------------------------------------------------------------------------------------------------------

    /** A price's buy and sell volume over the footprint's window. */
    data class Foot(val price: Double, val buy: Long, val sell: Long) {
        val total: Long get() = buy + sell
    }

    /** What the screens and the log read of a future's auction at one moment. */
    data class Snapshot(
        val name: String, val atSec: Long, val last: Double, val bin: Double,
        val today: Levels?, val prior: Levels?, val regime: Regime?,
        /** The session's cumulative delta, and the last 15 minutes' (streamed minutes only). */
        val sessionDelta: Long, val delta15: Long,
        val divergence: Divergence?,
        /** The last 30 minutes' per-minute delta (minute start to delta), oldest first. */
        val deltas: List<Pair<Long, Long>>,
        /** The last 15 minutes' busiest prices with their buy and sell volume, by price (highest first). */
        val footprint: List<Foot>,
        val vwap: Vwap?,
        val tpo: MarketProfile.Read?,
        /** When the streamed minutes began (epoch s; null: none), and whether earlier minutes came from candles. */
        val streamedFrom: Long?, val seeded: Boolean,
    )

    /**
     * One future's session: its profile (buy, sell and unsigned volume per bin), its minutes and the footprint's last 15
     * minutes, reset at each new IST day. Fed in arrival order ([trade]); candles fill the minutes before the stream ([seed]).
     * Not thread-safe by itself (the board's lock guards it).
     */
    class Day(var openMin: Int = 9 * 60 + 15) {
        private var day = Long.MIN_VALUE
        var bin = 0.0; private set
        private val bins = HashMap<Long, LongArray>()
        private val minutes = java.util.TreeMap<Long, LongArray>()      // minute -> buy, sell, vol
        private val prices = java.util.TreeMap<Long, DoubleArray>()     // minute -> open, high, low, close, pv, p2v
        private val unsigned = HashSet<Long>()
        private val foot = java.util.TreeMap<Long, HashMap<Long, LongArray>>()   // minute -> paise -> buy, sell
        private var seededTo = Long.MIN_VALUE
        private var firstStreamed: Long? = null
        var last = 0.0; private set

        private fun roll(sec: Long, price: Double) {
            val d = dayOf(sec)
            if (d == day) return
            day = d; bin = binSize(price); bins.clear(); minutes.clear(); prices.clear(); unsigned.clear(); foot.clear()
            seededTo = Long.MIN_VALUE; firstStreamed = null
        }

        /** A trade of [qty] at [price] at [ms] signed [side] (+1 buy, −1 sell, 0 unknown). */
        fun trade(ms: Long, price: Double, qty: Long, side: Int) {
            if (!(price > 0) || qty <= 0) return
            val sec = Math.floorDiv(ms, 1000L)
            roll(sec, price)
            last = price
            val m = Math.floorDiv(sec, 60L)
            val f = foot.getOrPut(m) { HashMap() }.getOrPut(Math.round(price * 100)) { LongArray(2) }
            if (side > 0) f[0] += qty else if (side < 0) f[1] += qty
            while (foot.isNotEmpty() && foot.firstKey() <= m - FOOT_MIN) foot.pollFirstEntry()
            // A minute already filled from a candle keeps the candle's volume (it is not counted twice).
            if (m <= seededTo) return
            if (firstStreamed == null) firstStreamed = m * 60
            val b = bins.getOrPut(binOf(price, bin)) { LongArray(3) }
            b[if (side > 0) 0 else if (side < 0) 1 else 2] += qty
            val mv = minutes.getOrPut(m) { LongArray(3) }
            if (side > 0) mv[0] += qty else if (side < 0) mv[1] += qty
            mv[2] += qty
            val p = prices[m]
            if (p == null) prices[m] = doubleArrayOf(price, price, price, price, price * qty, price * price * qty)
            else { p[1] = maxOf(p[1], price); p[2] = minOf(p[2], price); p[3] = price; p[4] += price * qty; p[5] += price * price * qty }
        }

        /**
         * Today's 1-minute [candles] before the stream: each one's volume spread over its bins (unsigned) and its minute
         * kept (no delta), up to the first streamed minute, or up to the last one completed at [nowSec] when none streamed.
         */
        fun seed(candles: List<Candle>, nowSec: Long) {
            val today = candles.filter { it.close > 0 && dayOf(it.startSec) == dayOf(nowSec) }.sortedBy { it.startSec }
            if (today.isEmpty()) return
            roll(today.first().startSec, today.first().close)
            if (dayOf(nowSec) != day) return
            val stop = firstStreamed?.let { Math.floorDiv(it, 60L) } ?: Math.floorDiv(nowSec, 60L)
            val use = today.filter { Math.floorDiv(it.startSec, 60L) < stop && Math.floorDiv(it.startSec, 60L) !in minutes }
            if (use.isEmpty()) return
            spread(use, bin) { i, v -> val b = bins.getOrPut(i) { LongArray(3) }; b[2] += Math.round(v) }
            for (c in use) {
                val m = Math.floorDiv(c.startSec, 60L)
                val tp = (c.high + c.low + c.close) / 3
                minutes[m] = longArrayOf(0, 0, c.volume.coerceAtLeast(0))
                prices[m] = doubleArrayOf(c.open, c.high, c.low, c.close, tp * c.volume, tp * tp * c.volume)
                unsigned += m
                seededTo = maxOf(seededTo, m)
            }
            if (last <= 0) last = use.last().close
        }

        /** Every minute of the day so far, oldest first. */
        fun minutes(): List<Minute> = minutes.map { (m, v) ->
            val p = prices.getValue(m)
            Minute(m * 60, p[0], p[1], p[2], p[3], v[0], v[1], v[2], p[4], p[5], m !in unsigned)
        }

        /** Today's profile levels (null: nothing traded). */
        fun levels(): Levels? = levels(bins.mapValues { (it.value[0] + it.value[1] + it.value[2]).toDouble() }, bin)

        /** The footprint's last [FOOT_MIN] minutes to [nowSec]: the [top] busiest prices, highest price first. */
        fun footprint(nowSec: Long, top: Int = FOOT_TOP): List<Foot> {
            val m = Math.floorDiv(nowSec, 60L)
            val sum = HashMap<Long, LongArray>()
            for ((k, prices) in foot) if (k > m - FOOT_MIN && k <= m) for ((paise, v) in prices) {
                val s = sum.getOrPut(paise) { LongArray(2) }; s[0] += v[0]; s[1] += v[1]
            }
            return sum.entries.filter { it.value[0] + it.value[1] > 0 }.sortedByDescending { it.value[0] + it.value[1] }.take(top)
                .map { Foot(it.key / 100.0, it.value[0], it.value[1]) }.sortedByDescending { it.price }
        }

        /**
         * Each minute's footprint (every traded price with its buy and sell volume, highest price first) for the minutes after
         * [afterMin] and before [beforeMin] (epoch minutes) still kept ([FOOT_MIN]), oldest first: the per-minute footprint file.
         */
        fun footMinutes(afterMin: Long, beforeMin: Long): List<Pair<Long, List<Foot>>> =
            foot.subMap(afterMin, false, beforeMin, false).map { (m, prices) ->
                m to prices.entries.filter { it.value[0] + it.value[1] > 0 }.map { Foot(it.key / 100.0, it.value[0], it.value[1]) }.sortedByDescending { it.price }
            }

        /** The day as of [nowSec] against [prior] (null: no snapshot - nothing today). */
        fun snapshot(name: String, nowSec: Long, prior: Levels?): Snapshot? {
            if (day == Long.MIN_VALUE || dayOf(nowSec) != day || minutes.isEmpty()) return null
            val ms = minutes()
            val signed = ms.filter { it.signed }
            val now = Math.floorDiv(nowSec, 60L)
            val d15 = signed.filter { Math.floorDiv(it.startSec, 60L) > now - DIV_WINDOW }.sumOf { it.delta }
            val deltas = signed.filter { Math.floorDiv(it.startSec, 60L) > now - 30 }.map { it.startSec to it.delta }
            return Snapshot(name, nowSec, last, bin, levels(), prior, regime(prior, last, closes30(ms, nowSec, openMin)),
                signed.sumOf { it.delta }, d15, divergence(ms, nowSec), deltas, footprint(nowSec), vwap(ms),
                MarketProfile.read(ms, nowSec, openMin, bin), firstStreamed, unsigned.isNotEmpty())
        }
    }

    // ---- the chart's lines -------------------------------------------------------------------------------------------------

    /** A thin line the Chart can draw (optional, off by default): its label, price and which overlay it belongs to. */
    data class ChartLevel(val label: String, val price: Double, val kind: Kind) {
        enum class Kind { PROFILE, VWAP }
    }

    /**
     * [s]'s lines in the INDEX's prices (the future's levels less [basis] = future − index): the prior day's POC / VAH / VAL
     * ("pPOC"...), today's ("POC"...), and the VWAP with its ±1σ / ±2σ bands.
     */
    fun chartLevels(s: Snapshot, basis: Double): List<ChartLevel> {
        val out = ArrayList<ChartLevel>()
        fun add(label: String, px: Double?, k: ChartLevel.Kind) { if (px != null && px > 0) out += ChartLevel(label, Math.round((px - basis) * 100) / 100.0, k) }
        s.prior?.let { add("pPOC", it.poc, ChartLevel.Kind.PROFILE); add("pVAH", it.vah, ChartLevel.Kind.PROFILE); add("pVAL", it.vaLow, ChartLevel.Kind.PROFILE) }
        s.today?.let { add("POC", it.poc, ChartLevel.Kind.PROFILE); add("VAH", it.vah, ChartLevel.Kind.PROFILE); add("VAL", it.vaLow, ChartLevel.Kind.PROFILE) }
        s.vwap?.let { v ->
            add("VWAP", v.vwap, ChartLevel.Kind.VWAP)
            if (v.sd > 0) for (k in 1..2) { add("+${k}σ", v.vwap + k * v.sd, ChartLevel.Kind.VWAP); add("−${k}σ", v.vwap - k * v.sd, ChartLevel.Kind.VWAP) }
        }
        return out
    }

    // ---- words -------------------------------------------------------------------------------------------------------------

    private fun px(x: Double) = "%,.2f".format(Locale.ENGLISH, x).removeSuffix(".00")
    private fun sg(x: Long) = (if (x >= 0) "+" else "−") + "%,d".format(Locale.ENGLISH, abs(x))
    private fun sgd(x: Double, d: Int = 1) = (if (x >= 0) "+" else "−") + "%,.${d}f".format(Locale.ENGLISH, abs(x))

    /** "POC 52,310 · VA 52,180-52,420 · HVN 52,310, 52,505 · LVN 52,240". */
    fun levelsLine(l: Levels?): String = if (l == null) "not known yet" else
        "POC ${px(l.poc)} · VA ${px(l.vaLow)}–${px(l.vah)}" +
            (if (l.hvns.isNotEmpty()) " · HVN ${l.hvns.joinToString(", ") { px(it) }}" else "") +
            (if (l.lvns.isNotEmpty()) " · LVN ${l.lvns.joinToString(", ") { px(it) }}" else "")

    /** The detail sheet's auction lines: label to value. */
    fun lines(s: Snapshot): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += "Auction (vs the prior day's value)" to (s.regime?.words ?: "no prior day's profile yet")
        out += "Prior day's profile" to levelsLine(s.prior)
        out += "Today's profile (developing)" to levelsLine(s.today) + if (s.seeded) " (early minutes from candles)" else ""
        out += "Delta: session / last 15 min" to "${sg(s.sessionDelta)} / ${sg(s.delta15)}" + (s.divergence?.let { " · ${it.words}" } ?: "")
        s.vwap?.let { v ->
            val (l1, h1) = v.band(1); val (l2, h2) = v.band(2)
            out += "VWAP (future)" to "${px(v.vwap)} · ±1σ ${px(l1)}–${px(h1)} · ±2σ ${px(l2)}–${px(h2)}" +
                (v.sdUnits(s.last)?.let { " · price ${sgd(it)}σ" } ?: "")
            out += "Anchored VWAP (from the high / low)" to "${v.atHigh?.let { px(it) } ?: "—"} / ${v.atLow?.let { px(it) } ?: "—"}"
        }
        s.tpo?.let { out += MarketProfile.lines(it) }
        return out
    }

    /** Home's word for [s]: "BN: inside value" or "BN: no profile yet". */
    fun homeWord(name: String, s: Snapshot?): String = "${OrderFlow.short(name)}: " + (s?.regime?.chip ?: "—")

    // ---- Jarvis ----------------------------------------------------------------------------------------------------------

    enum class Topic { PROFILE, GAMMA }

    private val PROFILE = Regex(" (volume profile|value area|point of control|poc|vah|val|market profile|auction regime) ")
    private val GAMMA = Regex(" (gamma regime|gamma exposure|gex|zero gamma|gamma flip|dealer gamma) ")

    /** Which of the auction's readings [text] asks for (OrderFlow's question carries it), or null. */
    fun topic(text: String): Topic? {
        val t = " " + text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        return when { GAMMA.containsMatchIn(t) -> Topic.GAMMA; PROFILE.containsMatchIn(t) -> Topic.PROFILE; else -> null }
    }

    /** Jarvis's volume-profile answer for [name] from [s] (null: no live profile). Facts only. */
    fun answer(name: String, s: Snapshot?, nowSec: Long): String {
        val who = OrderFlow.label(name)
        if (s == null || nowSec - s.atSec > 120) return "I have no live volume profile for $who right now, Boss: it is built from its near " +
            "future's trades on the Zerodha price stream in market hours."
        val parts = ArrayList<String>()
        parts += "today's developing profile: ${levelsLine(s.today)}"
        parts += if (s.prior != null) "the prior day's: ${levelsLine(s.prior)}" else "no prior day's profile yet"
        s.regime?.let { parts += "price is ${it.words} against the prior day's value area" }
        parts += "session delta ${sg(s.sessionDelta)}, last 15 minutes ${sg(s.delta15)}"
        s.divergence?.let { parts += it.words }
        s.tpo?.let { t -> t.openType?.let { parts += "the open was ${it.words}" }; t.dayType?.let { parts += "the day looks ${it.words} so far" } }
        return "$who's future, Boss: " + parts.joinToString("; ") + ". A live reading for research, not a signal; no strategy uses it."
    }
}
