package com.optionslab.ira

import com.optionslab.engine.KiteTicks
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Live order flow (Boss, 9 Oct 2026): who is pushing - buyers or sellers - read from Zerodha's full-mode ticks (the five
 * best bids and offers, the book's total buy and sell quantity, the volume and the OI), per instrument at a 1-second
 * resolution with rolling 10 s, 60 s and 5 minute windows:
 *
 *  - OFI: Cont, Kukanov and Stoikov's order-flow imbalance from the best bid and offer's price and size changes ([ofi]);
 *  - the 5-level depth imbalance (Σbid − Σask) / (Σbid + Σask) and the top-of-book queue imbalance ([imbalance]);
 *  - Kite's total buy / sell quantity ratio ([ratio]);
 *  - signed volume and the cumulative volume delta: each tick's volume change signed by the TICK RULE (a trade above the
 *    last different trade price a buy, below a sell, a zero tick keeps the last sign) - the research's live sample (HUNT R7)
 *    found the tick rule tracking the move where the quote rule against the previous book did not; the quote rule (at or
 *    above the offer a buy, at or below the bid a sell, inside by the midpoint, [sign]) is kept as a small second part;
 *  - feed hygiene: an exact repeat of the last packet is dropped (most packets), and so is one that re-sends an OLDER state
 *    (its volume below the running maximum: about 1 in 150);
 *  - option-side flow: the ATM ±2 calls' signed volume against the puts' (call buying against put buying);
 *  - the future's OI change with its price change, per minute ([buildUp]: long build-up, short build-up, covering, unwinding).
 *
 * They combine into one read ([Read]): BUYERS, SELLERS or BALANCED with a 0-100 strength, each part z-scored against the
 * instrument's own last 30 minutes ([z], [buyers]). Shown live, logged beside every strategy signal ([FlowShadow]) and, only
 * where Boss turns CONFIRM on, able to SKIP an entry the flow does not agree with ([agreement], [skips]) - never to place,
 * enlarge, reverse or exit anything. Pure: no clock, no socket, no Android.
 */
object OrderFlow {
    enum class Side { BUYERS, SELLERS, BALANCED }

    enum class BuildUp(val words: String) {
        LONG_BUILDUP("long build-up"), SHORT_BUILDUP("short build-up"), SHORT_COVERING("short covering"),
        LONG_UNWINDING("long unwinding"), NEUTRAL("no clear build-up"),
    }

    /**
     * What an instrument is to its underlying's read: its future, an ATM ±2 call or put, or the option the Chart shows
     * (WATCH: its tape and heatmap only, never part of the read).
     */
    enum class Role {
        FUTURE, CE, PE, WATCH,
        /**
         * The big-move recorder's ATM ±2 calls and puts of the index NOT in focus ([MoveEvents]): recorded only (the 1-second
         * file and the event files), never part of any read, so no strategy's flow changes because they are followed.
         */
        REC_CE, REC_PE,
    }

    /** A strategy's use of the flow: none, logged beside each signal, or an entry skipped unless the flow agrees. */
    enum class Mode { OFF, SHADOW, CONFIRM }

    enum class Agreement { AGREES, DISAGREES, UNKNOWN }

    /** CONFIRM's default: a call needs buyers at 55 or more, a put sellers at 55 or more. Adjustable 50-80. */
    const val THRESHOLD = 55
    const val MIN_THRESHOLD = 50
    const val MAX_THRESHOLD = 80

    /** The 30 minutes each part is z-scored against. */
    const val HISTORY_SEC = 1800

    /** Two minutes of seconds before a read is trusted (a strength is given). */
    const val WARM_SEC = 120

    /** A read older than this at a decision is no read. */
    const val STALE_SEC = 5L

    /** The full-mode set kept small (Kite allows 3000 instruments a connection; full packets are the heaviest). */
    const val FULL_MODE_CAP = 22

    /** The underlyings the app reads flow for (their near futures), and how they are shortened on a line. */
    val INDICES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")

    fun short(name: String): String = when (name) {
        "NIFTY" -> "NF"; "BANKNIFTY" -> "BN"; "FINNIFTY" -> "FN"; "MIDCPNIFTY" -> "MN"; else -> name
    }

    fun label(name: String): String = when (name) {
        "NIFTY" -> "Nifty"; "BANKNIFTY" -> "BankNifty"; "FINNIFTY" -> "FinNifty"; "MIDCPNIFTY" -> "Midcap Nifty"
        else -> name.lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase() }
    }

    // ---- the formulas ---------------------------------------------------------------------------------------------------

    /**
     * Cont-Kukanov-Stoikov's OFI of one book update: e = 1{b ≥ b'}·q_b − 1{b ≤ b'}·q_b' − 1{a ≤ a'}·q_a + 1{a ≥ a'}·q_a',
     * the primed values the previous best bid / offer. Positive: buying pressure (bids added or lifted, offers taken).
     */
    fun ofi(prevBid: Double, prevBidQty: Long, prevAsk: Double, prevAskQty: Long, bid: Double, bidQty: Long, ask: Double, askQty: Long): Double {
        var e = 0.0
        if (bid >= prevBid) e += bidQty
        if (bid <= prevBid) e -= prevBidQty
        if (ask <= prevAsk) e -= askQty
        if (ask >= prevAsk) e += prevAskQty
        return e
    }

    /** (bid − ask) / (bid + ask) in [-1, 1]; null when the book is empty. The depth imbalance and the queue imbalance alike. */
    fun imbalance(bid: Long, ask: Long): Double? = if (bid < 0 || ask < 0 || bid + ask <= 0) null else (bid - ask).toDouble() / (bid + ask)

    /** Kite's total buy over total sell quantity; null when either is not known. */
    fun ratio(buy: Long?, sell: Long?): Double? = if (buy == null || sell == null || buy <= 0 || sell <= 0) null else buy.toDouble() / sell

    /**
     * A trade at [price] against the book that stood before it ([bid], [ask]): +1 a buy, −1 a sell. The quote rule (at or
     * above the offer a buy, at or below the bid a sell, inside by the midpoint), then the tick rule against [prevPrice] on
     * the mid or with no book; a zero tick keeps [lastSign].
     */
    fun sign(price: Double, bid: Double?, ask: Double?, prevPrice: Double?, lastSign: Int): Int {
        if (ask != null && ask > 0 && price >= ask) return 1
        if (bid != null && bid > 0 && price <= bid) return -1
        if (bid != null && ask != null && bid > 0 && ask > 0) {
            val mid = (bid + ask) / 2
            if (price > mid + 1e-9) return 1
            if (price < mid - 1e-9) return -1
        }
        if (prevPrice != null && prevPrice > 0) {
            if (price > prevPrice + 1e-9) return 1
            if (price < prevPrice - 1e-9) return -1
        }
        return lastSign
    }

    /** The minute's price change with its OI change: who is adding or leaving. Either unchanged: no clear build-up. */
    fun buildUp(priceChange: Double, oiChange: Long): BuildUp = when {
        abs(priceChange) < 1e-9 || oiChange == 0L -> BuildUp.NEUTRAL
        priceChange > 0 && oiChange > 0 -> BuildUp.LONG_BUILDUP
        priceChange < 0 && oiChange > 0 -> BuildUp.SHORT_BUILDUP
        priceChange > 0 -> BuildUp.SHORT_COVERING
        else -> BuildUp.LONG_UNWINDING
    }

    /** +1 a build-up that leans to buyers (long build-up, short covering), −1 to sellers, 0 none. */
    fun lean(b: BuildUp?): Int = when (b) {
        BuildUp.LONG_BUILDUP, BuildUp.SHORT_COVERING -> 1
        BuildUp.SHORT_BUILDUP, BuildUp.LONG_UNWINDING -> -1
        else -> 0
    }

    /**
     * [x] against [history]'s first [n] values: (x − mean) / sd, clipped to ±4. Null with under 30 values; 0 when the history
     * never moved and [x] sits on it.
     */
    fun z(x: Double, history: DoubleArray, n: Int = history.size): Double? {
        if (n < 30 || x.isNaN()) return null
        var sum = 0.0; var k = 0
        for (i in 0 until n) { val v = history[i]; if (!v.isNaN()) { sum += v; k++ } }
        if (k < 30) return null
        val mean = sum / k
        var sq = 0.0
        for (i in 0 until n) { val v = history[i]; if (!v.isNaN()) sq += (v - mean) * (v - mean) }
        val sd = sqrt(sq / k)
        if (sd < 1e-9) return if (abs(x - mean) < 1e-9) 0.0 else if (x > mean) 4.0 else -4.0
        return ((x - mean) / sd).coerceIn(-4.0, 4.0)
    }

    /** The standard normal's CDF (Abramowitz-Stegun 7.1.26, error under 1.5e-7). */
    fun cdf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x) / sqrt(2.0))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x / 2)
        return if (x >= 0) 0.5 * (1 + y) else 0.5 * (1 - y)
    }

    /** The combined score (a weighted mean of z-scores) as the buyers' share, 0-100: 50 balanced, 72 at a score of +0.58. */
    fun buyers(score: Double): Int = (100 * cdf(score)).roundToInt().coerceIn(0, 100)

    /** BUYERS at [THRESHOLD] or more, SELLERS at 100 − [THRESHOLD] or less, else BALANCED. */
    fun sideOf(buyers: Int): Side = when {
        buyers >= THRESHOLD -> Side.BUYERS
        buyers <= 100 - THRESHOLD -> Side.SELLERS
        else -> Side.BALANCED
    }

    /** The side's strength: the buyers' share for BUYERS, the sellers' for SELLERS, the larger for BALANCED. */
    fun strength(buyers: Int): Int = when (sideOf(buyers)) {
        Side.BUYERS -> buyers
        Side.SELLERS -> 100 - buyers
        Side.BALANCED -> maxOf(buyers, 100 - buyers)
    }

    /** Each part's weight in the combined score (the parts that are known are averaged by these). */
    val WEIGHTS = mapOf(
        // CONFIRM's core, on the FUTURE over 60 s and 300 s: the tick-rule signed volume and the 5-level imbalance (HUNT R7).
        "cvd60" to 0.22, "cvd300" to 0.14, "depth60" to 0.22, "depth300" to 0.10,
        // Smaller parts: the best-level OFI, the quote-rule signed volume, the options, the OI. Kite's total buy / sell
        // quantities are shown only: they are easy to spoof (the trap guard), so they never move the score.
        "ofi" to 0.14, "quote" to 0.05, "options" to 0.08, "oi" to 0.05,
    )

    /** The weighted mean of the known [zs] (name to z); 0 when none is known. */
    fun score(zs: Map<String, Double?>): Double {
        var s = 0.0; var w = 0.0
        for ((k, v) in zs) { val wt = WEIGHTS[k] ?: continue; if (v != null && !v.isNaN()) { s += wt * v; w += wt } }
        return if (w <= 0) 0.0 else s / w
    }

    /**
     * Does the flow agree with a trade of direction [side] (+1 bullish - a call bought, a future bought; −1 bearish)?
     * UNKNOWN - no opinion, the trade follows the strategy - for no read, a read older than [STALE_SEC] or not warm, an
     * unreliable feed and a time trap. A stop hunt or failed break in the trade's direction in the last 2 minutes: DISAGREES.
     * Otherwise it AGREES only when the executed
     * flow (tick-rule signed volume over 60 s) is on the trade's side AND the side's share is at least [threshold]: resting
     * depth can add confidence, never decide alone.
     */
    fun agreement(read: Read?, side: Int, threshold: Int, nowSec: Long): Agreement {
        if (read == null || !read.warm || nowSec - read.atSec > STALE_SEC || side == 0) return Agreement.UNKNOWN
        if (TrapGuard.Trap.UNRELIABLE in read.flags || TrapGuard.Trap.TIME_WINDOW in read.flags) return Agreement.UNKNOWN
        // A stop hunt or failed break in the last 2 minutes: no entry in the burst's direction (the lockout).
        if ((TrapGuard.Trap.STOP_HUNT in read.flags || TrapGuard.Trap.FAILED_BREAK in read.flags) && side == read.huntDir) return Agreement.DISAGREES
        if (side * read.cvd60 <= 0) return Agreement.DISAGREES
        val share = if (side > 0) read.buyers else 100 - read.buyers
        return if (share >= threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)) Agreement.AGREES else Agreement.DISAGREES
    }

    /**
     * Whether [mode] skips an entry the flow judged [a]: only CONFIRM, and only when the flow disagrees. No opinion (no read,
     * an unreliable feed, a time trap) never skips: the trade follows the strategy, logged so. The flow can only ever skip.
     */
    fun skips(mode: Mode, a: Agreement): Boolean = mode == Mode.CONFIRM && a == Agreement.DISAGREES

    /** CONFIRM switched on for a strategy that can trade at Zerodha while the app is in Live: Boss's PIN or fingerprint first. */
    fun needsPin(to: Mode, live: Boolean, liveCapable: Boolean): Boolean = to == Mode.CONFIRM && live && liveCapable

    /** The ATM strike of [strikes] nearest [spot] and [n] on each side (fewer at the chain's edge), ascending. */
    fun atmStrikes(strikes: Collection<Double>, spot: Double, n: Int = 2): List<Double> {
        val s = strikes.distinct().sorted()
        if (s.isEmpty() || spot <= 0) return emptyList()
        val i = s.indices.minBy { abs(s[it] - spot) }
        return s.subList((i - n).coerceAtLeast(0), (i + n + 1).coerceAtMost(s.size))
    }

    // ---- one instrument -------------------------------------------------------------------------------------------------

    /** One second of one instrument: its mid and last price, OFI, signed and total volume, the book's balance, its OI. */
    data class Bar(
        val sec: Long, val mid: Double, val last: Double, val ofi: Double, val buyVol: Long, val sellVol: Long, val vol: Long,
        /** NaN when not known. */
        val depth: Double, val queue: Double, val ratio: Double, val oi: Long,
        /** The same second's volume signed by the quote rule (the tick rule's is [delta]). */
        val qDelta: Long = 0,
        /** The trap guard's resting-depth imbalance: rested 3 s, walls capped, deep walls out (NaN: not known). */
        val pdepth: Double = Double.NaN,
        /** Size pulled (cancelled without a trade) from the bids and the offers this second. */
        val pullBid: Long = 0, val pullAsk: Long = 0,
        /** Packets taken this second, and older states re-sent or crossed (dropped). */
        val updates: Int = 0, val stale: Int = 0,
        /** The median size of a level this second over all 10 shown (the walls' and the cap's base). */
        val medLevel: Double = Double.NaN,
        /** The trades' arrival delay this second (ms after the exchange's trade time; NaN: no trade). */
        val ageMs: Double = Double.NaN,
        // ---- recorded only (the 1-second file and the big-move recorder, [MoveEvents]); no read uses them ----
        /** The highest and lowest trade price this second (NaN: no trade). */
        val high: Double = Double.NaN, val low: Double = Double.NaN,
        /** The best bid and offer, their sizes, and the 5 levels' total sizes (the last packet of the second). */
        val bid: Double = Double.NaN, val ask: Double = Double.NaN, val bidQty: Long = 0, val askQty: Long = 0,
        val bid5: Long = 0, val ask5: Long = 0,
        /** The quote rule's buy and sell volume (their difference is [qDelta]). */
        val qBuy: Long = 0, val qSell: Long = 0,
        /** Packets with a volume change (a trade-count proxy) and the largest volume step (the largest print). */
        val prints: Int = 0, val maxPrint: Long = 0,
        /** The 5-level order-flow imbalance (the best level's is [ofi]). */
        val mofi: Double = 0.0,
        /** Kite's total buy and sell quantity (−1: not known). Recorded, never trusted for direction (HUNT R8). */
        val tbq: Long = -1, val tsq: Long = -1,
        /** The 5-level book of the second's last packet; kept only for the last [BOOK_SEC] bars (memory stays bounded). */
        val book: Book? = null,
    ) {
        val delta: Long get() = buyVol - sellVol
    }

    /** Bars a tracker keeps the 5-level book for (the big-move recorder's 5 minutes before a candle, with slack). */
    const val BOOK_SEC = 420

    /**
     * A 5-level book packed small (about 180 bytes): per level the price in paise, and the size with the order count in
     * one long. Levels missing from the packet are 0.
     */
    class Book(private val v: LongArray) {
        fun bidPx(i: Int): Double = v[i] / 100.0
        fun bidQty(i: Int): Long = v[5 + i] ushr 32
        fun bidOrders(i: Int): Int = (v[5 + i] and 0xffffffffL).toInt()
        fun askPx(i: Int): Double = v[10 + i] / 100.0
        fun askQty(i: Int): Long = v[15 + i] ushr 32
        fun askOrders(i: Int): Int = (v[15 + i] and 0xffffffffL).toInt()
        override fun equals(other: Any?): Boolean = other is Book && other.v.contentEquals(v)
        override fun hashCode(): Int = v.contentHashCode()

        companion object {
            /** The first five [bids] and [asks] (best first). */
            fun of(bids: List<KiteTicks.Level>, asks: List<KiteTicks.Level>): Book {
                val v = LongArray(20)
                fun put(l: List<KiteTicks.Level>, at: Int) {
                    for (i in 0 until minOf(5, l.size)) {
                        v[at + i] = Math.round(l[i].price * 100)
                        v[at + 5 + i] = (l[i].qty.coerceIn(0L, 0xffffffffL) shl 32) or (l[i].orders.toLong() and 0xffffffffL)
                    }
                }
                put(bids, 0); put(asks, 10)
                return Book(v)
            }
        }
    }

    /** Multi-level OFI: the best-level formula level by level over the 5 levels, summed (a level missing on either side: skipped). */
    fun mofi(prevBids: List<KiteTicks.Level>, prevAsks: List<KiteTicks.Level>, bids: List<KiteTicks.Level>, asks: List<KiteTicks.Level>): Double {
        var e = 0.0
        for (i in 0 until 5) {
            val pb = prevBids.getOrNull(i) ?: continue
            val pa = prevAsks.getOrNull(i) ?: continue
            val b = bids.getOrNull(i) ?: continue
            val a = asks.getOrNull(i) ?: continue
            e += ofi(pb.price, pb.qty, pa.price, pa.qty, b.price, b.qty, a.price, a.qty)
        }
        return e
    }

    /** A tick reduced to what the flow reads (null without a book: not a full-mode packet). */
    data class Quote(
        val ms: Long, val last: Double, val volume: Long, val oi: Long,
        val bid: Double, val bidQty: Long, val ask: Double, val askQty: Long,
        val bidDepth: Long, val askDepth: Long, val totalBuy: Long?, val totalSell: Long?,
        val bids: List<KiteTicks.Level> = emptyList(), val asks: List<KiteTicks.Level> = emptyList(),
        /** The exchange's last trade time (epoch seconds), when the packet carries it. */
        val tradeSec: Long? = null,
    )

    fun quoteOf(t: KiteTicks.Tick, ms: Long): Quote? {
        val d = t.depth ?: return null
        val b = d.bids.firstOrNull() ?: return null
        val a = d.asks.firstOrNull() ?: return null
        return Quote(ms, t.last, t.volume, t.oi, b.price, b.qty, a.price, a.qty, d.bidQty, d.askQty, t.buyQty, t.sellQty, d.bids, d.asks, t.lastTradeTime)
    }

    /** One instrument's flow: fed its quotes in arrival order, it keeps the last 30 minutes of 1-second bars. */
    class Tracker(val token: Long, val name: String, val role: Role, val strike: Double = 0.0) {
        private val ring = arrayOfNulls<Bar>(HISTORY_SEC)
        private var head = 0
        private var count = 0
        private var prev: Quote? = null
        private var lastSign = 0
        // The second being built.
        private var sec = -1L
        private var ofiAcc = 0.0; private var buyAcc = 0L; private var sellAcc = 0L; private var volAcc = 0L; private var qAcc = 0L
        private var pullBidAcc = 0L; private var pullAskAcc = 0L
        private var updAcc = 0; private var staleAcc = 0
        // Recorded only: the second's trade range, the quote rule's two sides, the prints, the 5-level OFI.
        private var hiAcc = Double.NaN; private var loAcc = Double.NaN; private var qBuyAcc = 0L; private var qSellAcc = 0L
        private var printsAcc = 0; private var maxPrintAcc = 0L; private var mofiAcc = 0.0
        private val ages = ArrayList<Long>()
        /** Recent trade sizes (the illiquid-print guard's median). */
        private val sizes = ArrayDeque<Long>()
        /** The defended levels: the offers against buying, the bids against selling (absorption, exhaustion). */
        val askDefence = TrapGuard.Defence(ask = true)
        val bidDefence = TrapGuard.Defence(ask = false)
        private var last: Quote? = null
        /** The highest volume seen: a packet below it re-sends an older state and is dropped. */
        private var maxVolume = -1L
        /** Each shown price, since when it has rested there (the trap guard's 3 s). */
        private val bidSince = HashMap<Double, TrapGuard.Seen>()
        private val askSince = HashMap<Double, TrapGuard.Seen>()
        /** Quotes taken, all told; when the last one came (ms). */
        var quotes = 0L; private set
        var lastMs = 0L; private set
        /** Packets dropped: exact repeats of the last one, and older states re-sent. */
        var repeats = 0L; private set
        var stale = 0L; private set
        /** The future's session: profile, minutes, footprint ([Auction.Day]; display and the log only). */
        val day: Auction.Day? = if (role == Role.FUTURE) Auction.Day() else null
        /** Time & sales ([TapeHeat.Tape]). */
        val tape = TapeHeat.Tape()
        /** The liquidity heatmap, kept only for the instruments the board shows one for ([Board.layout]). */
        var heat: TapeHeat.Heatmap? = null

        /** Takes [q] (true), or drops it as a repeat or an older state (false). */
        fun offer(q: Quote): Boolean {
            // A volume below the running maximum is an older state re-sent - unless the feed was quiet half an hour (a new session).
            if (q.volume < maxVolume && q.ms - lastMs < 30 * 60_000L) { stale++; staleAcc++; return false }
            // A crossed or locked book is a glitch.
            if (TrapGuard.crossed(q.bid, q.ask)) { stale++; staleAcc++; return false }
            val p0 = prev
            if (p0 != null && p0.copy(ms = q.ms) == q) { repeats++; return false }
            maxVolume = q.volume
            val s = q.ms / 1000
            if (sec >= 0 && s > sec) close()
            if (sec < 0 || s > sec) sec = s
            val p = prev
            if (p != null) {
                ofiAcc += ofi(p.bid, p.bidQty, p.ask, p.askQty, q.bid, q.bidQty, q.ask, q.askQty)
                val dv = (q.volume - p.volume).coerceAtLeast(0)
                var sg = 0
                // One print far over the usual size that did not move the price: an illiquid print, not flow.
                val med = TrapGuard.median(sizes.map { it.toDouble() })
                val illiquid = dv > 0 && TrapGuard.illiquidPrint(dv, med, priceMoved = abs(q.last - p.last) > 1e-9)
                if (dv > 0) { sizes.addLast(dv); while (sizes.size > 200) sizes.removeFirst() }
                val bigPrint = dv > 0 && TapeHeat.big(dv, med)
                q.tradeSec?.takeIf { dv > 0 && it > 0 }?.let { ages += q.ms - it * 1000 }
                if (dv > 0 && !illiquid) {
                    // The tick rule (primary): against the previous trade price; a zero tick keeps the last sign.
                    sg = sign(q.last, null, null, p.last, lastSign)
                    if (sg > 0) buyAcc += dv else if (sg < 0) sellAcc += dv
                    volAcc += dv
                    if (sg != 0) lastSign = sg
                    // The quote rule (secondary): against the book that stood before the trade.
                    val qs = sign(q.last, p.bid, p.ask, null, 0)
                    qAcc += qs * dv
                    if (qs > 0) qBuyAcc += dv else if (qs < 0) qSellAcc += dv
                }
                // Recorded only: the print, its size and the second's trade range; the 5-level OFI.
                if (dv > 0) {
                    printsAcc++
                    if (dv > maxPrintAcc) maxPrintAcc = dv
                    if (hiAcc.isNaN() || q.last > hiAcc) hiAcc = q.last
                    if (loAcc.isNaN() || q.last < loAcc) loAcc = q.last
                }
                mofiAcc += mofi(p.bids, p.asks, q.bids, q.asks)
                // Display and the log only: the tape, the heatmap's big prints, the future's session (an illiquid print unsigned).
                if (dv > 0) {
                    tape.add(TapeHeat.Print(q.tradeSec?.takeIf { it > 0 }?.times(1000) ?: q.ms, q.last, dv, sg, bigPrint))
                    if (bigPrint) heat?.dot(TapeHeat.Dot(q.ms / 1000, q.last, dv, if (sg >= 0) TapeHeat.DotKind.BIG_BUY else TapeHeat.DotKind.BIG_SELL))
                    day?.trade(q.ms, q.last, dv, sg)
                }
                // The trap guard: size that left the book without trading; the defended levels.
                pullBidAcc += TrapGuard.pulled(p.bids, q.bids, bids = true, traded = dv)
                pullAskAcc += TrapGuard.pulled(p.asks, q.asks, bids = false, traded = dv)
                val pm = (p.bid + p.ask) / 2; val qm = (q.bid + q.ask) / 2
                askDefence.update(p.ask, p.askQty, pm, q.asks, q.bid, q.ask, q.last, dv, qm, q.ms)
                bidDefence.update(p.bid, p.bidQty, pm, q.bids, q.bid, q.ask, q.last, dv, qm, q.ms)
            }
            TrapGuard.keepSince(bidSince, q.bids, q.ms)
            TrapGuard.keepSince(askSince, q.asks, q.ms)
            prev = q; last = q
            updAcc++
            quotes++; lastMs = q.ms
            return true
        }

        /** The median level size over the last 5 minutes (the walls' and the cap's base, at least 1), null with none. */
        private fun median5m(): Double? {
            val out = ArrayList<Double>()
            for (i in 0 until minOf(count, 300)) ring[(head - 1 - i + HISTORY_SEC) % HISTORY_SEC]?.medLevel?.let { if (!it.isNaN()) out += it }
            return TrapGuard.median(out)?.coerceAtLeast(1.0)
        }

        /** The second being built as a bar (null when nothing came yet). */
        private fun open(): Bar? {
            val q = last ?: return null
            if (sec < 0) return null
            val levels = (q.bids + q.asks).filter { it.qty > 0 }.map { it.qty.toDouble() }
            val med = median5m()
            val pb = TrapGuard.persistentDepth(q.bids, bidSince, q.ms, med)
            val pa = TrapGuard.persistentDepth(q.asks, askSince, q.ms, med)
            return Bar(sec, (q.bid + q.ask) / 2, q.last, ofiAcc, buyAcc, sellAcc, volAcc,
                imbalance(q.bidDepth, q.askDepth) ?: Double.NaN, imbalance(q.bidQty, q.askQty) ?: Double.NaN,
                ratio(q.totalBuy, q.totalSell) ?: Double.NaN, q.oi, qAcc,
                if (pb + pa > 0) (pb - pa) / (pb + pa) else Double.NaN, pullBidAcc, pullAskAcc, updAcc, staleAcc,
                TrapGuard.median(levels) ?: Double.NaN, TrapGuard.median(ages.map { it.toDouble() }) ?: Double.NaN,
                high = hiAcc, low = loAcc, bid = q.bid, ask = q.ask, bidQty = q.bidQty, askQty = q.askQty,
                bid5 = q.bidDepth, ask5 = q.askDepth, qBuy = qBuyAcc, qSell = qSellAcc, prints = printsAcc, maxPrint = maxPrintAcc,
                mofi = mofiAcc, tbq = q.totalBuy ?: -1, tsq = q.totalSell ?: -1, book = Book.of(q.bids, q.asks))
        }

        private fun close() {
            open()?.let { b ->
                ring[head] = b; head = (head + 1) % HISTORY_SEC; if (count < HISTORY_SEC) count++
                // The book is kept for the last [BOOK_SEC] bars only (each bar is at least a second apart): bounded memory.
                if (count > BOOK_SEC) {
                    val old = (head - 1 - BOOK_SEC + HISTORY_SEC) % HISTORY_SEC
                    ring[old]?.takeIf { it.book != null }?.let { ring[old] = it.copy(book = null) }
                }
            }
            // The heatmap: the book once a second, and the pulls the trap guard counts as big.
            val h = heat; val q = last
            if (h != null && q != null && sec >= 0) {
                h.snap(sec, q.bids, q.asks)
                val big = maxOf(1.0, TrapGuard.PULL_BIG * (median5m() ?: 0.0))
                if (pullBidAcc >= big) h.dot(TapeHeat.Dot(sec, q.bid, pullBidAcc, TapeHeat.DotKind.PULL_BID))
                if (pullAskAcc >= big) h.dot(TapeHeat.Dot(sec, q.ask, pullAskAcc, TapeHeat.DotKind.PULL_ASK))
            }
            ofiAcc = 0.0; buyAcc = 0; sellAcc = 0; volAcc = 0; qAcc = 0
            pullBidAcc = 0; pullAskAcc = 0; updAcc = 0; staleAcc = 0; ages.clear()
            hiAcc = Double.NaN; loAcc = Double.NaN; qBuyAcc = 0; qSellAcc = 0; printsAcc = 0; maxPrintAcc = 0; mofiAcc = 0.0
        }

        /** The closed seconds, oldest first, then the one being built. */
        fun bars(): List<Bar> {
            val out = ArrayList<Bar>(count + 1)
            for (i in 0 until count) ring[(head - count + i + HISTORY_SEC) % HISTORY_SEC]?.let { out += it }
            open()?.let { out += it }
            return out
        }

        /** The closed seconds after [afterSec] (the recorder's next rows), oldest first. */
        fun closedAfter(afterSec: Long): List<Bar> {
            val out = ArrayList<Bar>()
            for (i in 0 until count) ring[(head - count + i + HISTORY_SEC) % HISTORY_SEC]?.takeIf { it.sec > afterSec }?.let { out += it }
            return out
        }
    }

    /** Σ of [f] over the bars in the last [n] seconds up to [nowSec]. */
    fun window(bars: List<Bar>, nowSec: Long, n: Int, f: (Bar) -> Double): Double {
        var s = 0.0
        for (i in bars.indices.reversed()) { val b = bars[i]; if (b.sec <= nowSec - n) break; if (b.sec <= nowSec) s += f(b) }
        return s
    }

    /** At each bar, the Σ of [f] over the [n] seconds ending there (a rolling sum by the bars' own seconds). */
    fun rolling(bars: List<Bar>, n: Int, f: (Bar) -> Double): DoubleArray {
        val out = DoubleArray(bars.size)
        var lo = 0; var s = 0.0
        for (i in bars.indices) {
            s += f(bars[i])
            while (bars[lo].sec <= bars[i].sec - n) { s -= f(bars[lo]); lo++ }
            out[i] = s
        }
        return out
    }

    /** At each bar, the mean of [f] (NaN left out) over the [n] seconds ending there; NaN when none is known. */
    fun rollingMean(bars: List<Bar>, n: Int, f: (Bar) -> Double): DoubleArray {
        val out = DoubleArray(bars.size)
        var lo = 0; var s = 0.0; var k = 0
        for (i in bars.indices) {
            val v = f(bars[i]); if (!v.isNaN()) { s += v; k++ }
            while (bars[lo].sec <= bars[i].sec - n) { val u = f(bars[lo]); if (!u.isNaN()) { s -= u; k-- }; lo++ }
            out[i] = if (k > 0) s / k else Double.NaN
        }
        return out
    }

    /** The mid's move over the last [n] seconds to the last bar (0 with too little history). */
    fun move(bars: List<Bar>, n: Int): Double {
        val last = bars.lastOrNull() ?: return 0.0
        val then = bars.lastOrNull { it.sec <= last.sec - n } ?: bars.first()
        return last.mid - then.mid
    }

    /** The last completed minute's price change and OI change (against the minute before), from 1-second bars; null too few. */
    fun minuteChange(bars: List<Bar>, nowSec: Long): Pair<Double, Long>? {
        val cur = nowSec / 60
        val done = bars.lastOrNull { it.sec / 60 < cur } ?: return null
        val before = bars.lastOrNull { it.sec / 60 < done.sec / 60 } ?: return null
        if (done.oi <= 0 || before.oi <= 0) return null
        return (done.last - before.last) to (done.oi - before.oi)
    }

    // ---- the combined read ----------------------------------------------------------------------------------------------

    data class Read(
        val name: String,
        val atSec: Long,
        val side: Side,
        /** The side's share, 0-100. */
        val strength: Int,
        /** The buyers' share, 0-100 (50 balanced), after the trap guard. */
        val buyers: Int,
        /** Enough history (two minutes) for the z-scores: a read that is not warm decides nothing. */
        val warm: Boolean,
        val mid: Double,
        val ofi10: Double, val ofi60: Double, val ofi300: Double,
        val cvd10: Double, val cvd60: Double, val cvd300: Double,
        /** The 5-level depth imbalance and the top-of-book queue imbalance now, −1..1 (NaN: not known). */
        val depth: Double, val queue: Double,
        /** Kite's total buy / sell quantity now (NaN: not known). Shown only. */
        val ratio: Double,
        /** The ATM ±2 calls' and puts' signed volume over 60 s; their net (−1 puts bought .. +1 calls bought), NaN with no options. */
        val ce60: Double, val pe60: Double, val optionNet: Double,
        val buildUp: BuildUp?, val oiChange: Long?,
        /** Each part's z-score against its own last 30 minutes (null: not known). */
        val z: Map<String, Double?>,
        /** The trap guard's resting-depth imbalance averaged over 10 s, 60 s and 5 minutes (NaN: not known). */
        val depth10: Double = Double.NaN, val depth60: Double = Double.NaN, val depth300: Double = Double.NaN,
        /** The quote rule's signed volume over 60 s (the secondary signing). */
        val qcvd60: Double = Double.NaN,
        /** The traps the guard found in this read (each one shown and logged). */
        val flags: Set<TrapGuard.Trap> = emptySet(),
        /** The buyers' share before the trap guard (for the log). */
        val rawBuyers: Int = buyers,
        /** Size pulled from the bids and the offers over the last 60 s, and the cancel-to-trade ratio (pulled / traded). */
        val pulledBid60: Long = 0, val pulledAsk60: Long = 0, val cancelToTrade: Double = Double.NaN,
        /** A time trap's reason ("the first 3 minutes after the open"), when [TrapGuard.Trap.TIME_WINDOW] is flagged. */
        val timeWhy: String? = null,
        /** A stop hunt's or failed break's direction (+1 up, −1 down; 0: none): CONFIRM's 2-minute lockout that way. */
        val huntDir: Int = 0,
        /** How late trades arrive, above the steady clock offset (ms, median over 30 s; NaN: not known). */
        val lateMs: Double = Double.NaN,
        /**
         * The latest absorption in the last minute (a defended level that held): its price (NaN: none) and who absorbed
         * (+1 buyers - a bid soaked up selling; −1 sellers - an offer soaked up buying). Display and the log only.
         */
        val absorbAt: Double = Double.NaN, val absorbBy: Int = 0,
    )

    /** The flows of every followed instrument, by underlying. Thread-safe: every call holds its lock. */
    class Board {
        data class Entry(val token: Long, val name: String, val role: Role, val strike: Double = 0.0)

        private val trackers = LinkedHashMap<Long, Tracker>()
        private val optHist = HashMap<String, Pair<LongArray, DoubleArray>>()
        private val history = HashMap<String, ArrayDeque<Pair<Long, Int>>>()
        private var quotesSeen = 0L
        private val rate = ArrayDeque<Pair<Long, Long>>()
        private val sessions = HashMap<String, TrapGuard.Session>()
        /** When each underlying's feed last looked stuffed (epoch s): its book parts stay off 10 s. */
        private val stuffedAt = HashMap<String, Long>()

        /** Follow exactly [entries] (an instrument already followed keeps its history). */
        @Synchronized fun layout(entries: List<Entry>) {
            val want = entries.associateBy { it.token }
            trackers.keys.retainAll(want.keys)
            for (e in entries) {
                val t = trackers[e.token]
                if (t == null || t.role != e.role || t.name != e.name) trackers[e.token] = Tracker(e.token, e.name, e.role, e.strike)
            }
            heats()
        }

        /** The charted option's token (it may already be followed as an ATM call or put): its tape and heatmap are shown. */
        private var watchToken: Long? = null

        @Synchronized fun watch(token: Long?) { watchToken = token; heats() }

        /** A heatmap for the index futures and the charted option only (memory is fixed: about 150 KB each). */
        private fun heats() {
            for (t in trackers.values) {
                val keep = t.role == Role.WATCH || t.token == watchToken || (t.role == Role.FUTURE && t.name in INDICES)
                if (keep && t.heat == null) t.heat = TapeHeat.Heatmap() else if (!keep) t.heat = null
            }
        }

        /** [name]'s session for the time traps (NSE's by default; MCX's hours, an expiry day, the day's event minutes). */
        @Synchronized fun session(name: String, s: TrapGuard.Session) { sessions[name] = s }

        @Synchronized fun tokens(): Set<Long> = trackers.keys.toSet()

        @Synchronized fun entries(): List<Entry> = trackers.values.map { Entry(it.token, it.name, it.role, it.strike) }

        /** A tick for a followed instrument: true when it was used (a full-mode packet with a book, not a repeat or older). */
        @Synchronized fun offer(t: KiteTicks.Tick, ms: Long): Boolean {
            val tr = trackers[t.token] ?: return false
            val q = quoteOf(t, ms) ?: return false
            if (!tr.offer(q)) return false
            quotesSeen++
            return true
        }

        /** Instruments followed, those with a quote in the last 10 s, and quotes a second over the last 10 s. */
        data class Coverage(val followed: Int, val live: Int, val perSec: Double)

        @Synchronized fun coverage(nowMs: Long): Coverage {
            rate.addLast(nowMs to quotesSeen)
            while (rate.size > 2 && nowMs - rate.first().first > 10_000) rate.removeFirst()
            val (t0, q0) = rate.first()
            val per = if (nowMs - t0 > 0) (quotesSeen - q0) * 1000.0 / (nowMs - t0) else 0.0
            return Coverage(trackers.size, trackers.values.count { nowMs - it.lastMs < 10_000 }, per)
        }

        /** Every followed instrument's closed seconds after [afterSec]: the recorder's rows. */
        @Synchronized fun closedAfter(afterSec: Long): List<Pair<Entry, Bar>> =
            trackers.values.flatMap { t -> t.closedAfter(afterSec).map { Entry(t.token, t.name, t.role, t.strike) to it } }

        /**
         * Every followed instrument's closed seconds after its own cursor ([after] of its token): the recorders' rows. An
         * instrument whose second closes late (its next packet came late) is not skipped, as one cursor for all would.
         */
        @Synchronized fun closedAfter(after: (Long) -> Long): List<Pair<Entry, Bar>> =
            trackers.values.flatMap { t -> t.closedAfter(after(t.token)).map { Entry(t.token, t.name, t.role, t.strike) to it } }

        /** [name]'s future's footprint of each completed minute after [afterMin] (epoch minute to its prices), oldest first. */
        @Synchronized fun footMinutes(name: String, afterMin: Long, nowSec: Long): List<Pair<Long, List<Auction.Foot>>> =
            future(name)?.day?.footMinutes(afterMin, Math.floorDiv(nowSec, 60L)).orEmpty()

        /** The closed seconds from [fromSec] on of the followed instruments [pick] takes (the big-move recorder's window before). */
        @Synchronized fun window(fromSec: Long, pick: (Entry) -> Boolean): List<Pair<Entry, Bar>> = trackers.values.flatMap { t ->
            val e = Entry(t.token, t.name, t.role, t.strike)
            if (pick(e)) t.closedAfter(fromSec - 1).map { e to it } else emptyList()
        }

        /** [name]'s read at [nowMs] from its future (null: no future followed or no quote yet), its options and its OI, trap-guarded. */
        @Synchronized fun read(name: String, nowMs: Long): Read? {
            val fut = trackers.values.firstOrNull { it.name == name && it.role == Role.FUTURE } ?: return null
            val bars = fut.bars()
            if (bars.isEmpty()) return null
            val nowSec = nowMs / 1000
            val n = bars.size
            val lastBar = bars.last()
            val ofiRoll = rolling(bars, 60) { it.ofi }
            val cvdRoll = rolling(bars, 60) { it.delta.toDouble() }
            val cvd300Roll = rolling(bars, 300) { it.delta.toDouble() }
            val quoteRoll = rolling(bars, 60) { it.qDelta.toDouble() }
            val session = sessions[name] ?: TrapGuard.Session.NSE
            // Resting depth as the trap guard counts it (rested 3 s and 3 snapshots, walls capped).
            val rest: (Bar) -> Double = { b -> b.pdepth }
            val depth10 = rollingMean(bars, 10, rest)
            val depth60 = rollingMean(bars, 60, rest)
            val depth300 = rollingMean(bars, 300, rest)
            val warm = bars.size >= 30 && lastBar.sec - bars.first().sec >= WARM_SEC
            // Option side: the calls' 60 s signed volume against the puts'.
            // (An option with no distinct update for 15 s is left out; NSE's options count from 9:20.)
            val opts = if (!TrapGuard.optionsReady(nowSec, session)) emptyList()
                else trackers.values.filter { it.name == name && (it.role == Role.CE || it.role == Role.PE) && nowMs - it.lastMs <= TrapGuard.BOOK_STALE_OPTION_MS }
            var ce = 0.0; var pe = 0.0
            for (o in opts) {
                val d = window(o.bars(), nowSec, 60) { it.delta.toDouble() }
                if (o.role == Role.CE) ce += d else pe += d
            }
            val optNet = if (opts.isEmpty() || abs(ce) + abs(pe) <= 0) Double.NaN else (ce - pe) / (abs(ce) + abs(pe))
            val zOpt = if (optNet.isNaN()) null else sampleOptions(name, nowSec, optNet)
            val mc = minuteChange(bars, nowSec)
            val bu = mc?.let { buildUp(it.first, it.second) }
            val flags = LinkedHashSet<TrapGuard.Trap>()
            // (10) The feed: the book's parts off when the future has gone quiet 5 s, trades arrive late, or updates burst
            // with no volume (10 s); trades 10 s late or older states re-sent: unreliable.
            val recent = bars.filter { it.sec > nowSec - 30 }
            // Lateness above the steadiest arrival seen (the phone's own clock offset taken out).
            val base = bars.mapNotNull { b -> b.ageMs.takeIf { !it.isNaN() } }.minOrNull()
            val late = if (base == null) Double.NaN else (TrapGuard.median(recent.map { it.ageMs })?.minus(base) ?: Double.NaN)
            val perSec = bars.filter { it.sec > nowSec - 1800 }.map { it.updates }
            val lastTwo = bars.filter { it.sec > nowSec - 2 }
            if (TrapGuard.stuffing(perSec, lastTwo.sumOf { it.updates }, lastTwo.sumOf { it.vol })) stuffedAt[name] = nowSec
            val bookOff = nowMs - fut.lastMs > TrapGuard.BOOK_STALE_FUTURE_MS || (!late.isNaN() && late > TrapGuard.LATE_MS) ||
                (stuffedAt[name]?.let { nowSec - it <= TrapGuard.STUFF_HOLD_SEC } == true)
            if (bookOff) flags += TrapGuard.Trap.BOOK_OFF
            if ((!late.isNaN() && late > TrapGuard.LATE_UNRELIABLE_MS) ||
                window(bars, nowSec, 60) { it.stale.toDouble() }.toInt() >= TrapGuard.STALE_LIMIT) flags += TrapGuard.Trap.UNRELIABLE
            // (3) A big pull on a side in the last 10 s: that side's resting depth gets no credit in this read.
            val med = TrapGuard.median(bars.takeLast(300).map { it.medLevel }) ?: 0.0
            val big = maxOf(1.0, TrapGuard.PULL_BIG * med)
            if (window(bars, nowSec, TrapGuard.PULL_WINDOW_SEC) { it.pullBid.toDouble() } >= big) flags += TrapGuard.Trap.PULL_BID
            if (window(bars, nowSec, TrapGuard.PULL_WINDOW_SEC) { it.pullAsk.toDouble() } >= big) flags += TrapGuard.Trap.PULL_ASK
            var zDepth60 = if (bookOff) null else z(depth60[n - 1], depth60)
            var zDepth300 = if (bookOff) null else z(depth300[n - 1], depth300)
            if (TrapGuard.Trap.PULL_BID in flags) { zDepth60 = zDepth60?.coerceAtMost(0.0); zDepth300 = zDepth300?.coerceAtMost(0.0) }
            if (TrapGuard.Trap.PULL_ASK in flags) { zDepth60 = zDepth60?.coerceAtLeast(0.0); zDepth300 = zDepth300?.coerceAtLeast(0.0) }
            val zCvd60 = z(cvdRoll[n - 1], cvdRoll)
            val zs = mapOf(
                "cvd60" to zCvd60,
                "cvd300" to z(cvd300Roll[n - 1], cvd300Roll),
                "depth60" to zDepth60,
                "depth300" to zDepth300,
                "ofi" to z(ofiRoll[n - 1], ofiRoll),
                "quote" to z(quoteRoll[n - 1], quoteRoll),
                "options" to zOpt,
                // The minute's build-up as a fixed lean (no history to score it against in 30 one-minute values).
                "oi" to lean(bu).takeIf { bu != null }?.toDouble(),
            )
            val raw = buyers(score(zs))
            var by = raw
            val lean = if (raw > 50) 1 else if (raw < 50) -1 else 0
            val cvd60 = window(bars, nowSec, 60) { it.delta.toDouble() }
            val move60 = move(bars, 60)
            // (6) Absorption by a defended level in the last minute (exhausted: said, not held against the flow).
            val asks = fut.askDefence.events.filter { nowSec - it.sec <= 60 }
            val bidsD = fut.bidDefence.events.filter { nowSec - it.sec <= 60 }
            if (asks.any { it.exhausted }) flags += TrapGuard.Trap.EXHAUSTION_BUY
            if (bidsD.any { it.exhausted }) flags += TrapGuard.Trap.EXHAUSTION_SELL
            if (asks.any { !it.exhausted } && by > 50) { by = 50; flags += TrapGuard.Trap.ABSORPTION_BUY }
            if (bidsD.any { !it.exhausted } && by < 50) { by = 50; flags += TrapGuard.Trap.ABSORPTION_SELL }
            // (6) Price confirmation over the minute.
            if (TrapGuard.against(lean, move60) && by != 50) { by = 50; flags += TrapGuard.Trap.PRICE_AGAINST }
            // (10) The future against the ATM options.
            if (TrapGuard.contradicts(by, optNet)) { by = 50; flags += TrapGuard.Trap.CONTRADICTION }
            // (4) The lean with no executed flow behind it (depth alone): said, and CONFIRM will not agree.
            if (by != 50 && (if (by > 50) 1 else -1) * cvd60 <= 0) flags += TrapGuard.Trap.NO_EXECUTED
            // (8) A stop hunt (back within 60 s) or failed break (120 s) in the last 2 minutes.
            val hunt = TrapGuard.stopHunt(bars, nowSec)
            if (hunt != null) flags += if (hunt.failedBreak) TrapGuard.Trap.FAILED_BREAK else TrapGuard.Trap.STOP_HUNT
            // (9) Time traps.
            val why = TrapGuard.timeWindow(nowSec, session)
            if (why != null) flags += TrapGuard.Trap.TIME_WINDOW
            // The absorption note: the latest defended level that held in the last minute (display and the log only).
            val held = (asks.filter { !it.exhausted }.map { it to -1 } + bidsD.filter { !it.exhausted }.map { it to 1 }).maxByOrNull { it.first.sec }
            val pulledBid = window(bars, nowSec, 60) { it.pullBid.toDouble() }.toLong()
            val pulledAsk = window(bars, nowSec, 60) { it.pullAsk.toDouble() }.toLong()
            val traded = window(bars, nowSec, 60) { it.vol.toDouble() }
            val read = Read(name, nowSec, sideOf(by), strength(by), by, warm, lastBar.mid,
                window(bars, nowSec, 10) { it.ofi }, window(bars, nowSec, 60) { it.ofi }, window(bars, nowSec, 300) { it.ofi },
                window(bars, nowSec, 10) { it.delta.toDouble() }, cvd60, window(bars, nowSec, 300) { it.delta.toDouble() },
                lastBar.depth, lastBar.queue, lastBar.ratio, ce, pe, optNet, bu, mc?.second, zs,
                depth10[n - 1], depth60[n - 1], depth300[n - 1], window(bars, nowSec, 60) { it.qDelta.toDouble() },
                flags, raw, pulledBid, pulledAsk, if (traded > 0) (pulledBid + pulledAsk) / traded else Double.NaN, why,
                hunt?.dir ?: 0, late, held?.first?.price ?: Double.NaN, held?.second ?: 0)
            if (warm) keepHistory(name, nowSec, by)
            return read
        }

        /** The option net's z against its own per-second history (sampled at each read, once a second). */
        private fun sampleOptions(name: String, sec: Long, v: Double): Double? {
            val (secs, vals) = optHist.getOrPut(name) { LongArray(HISTORY_SEC) { -1 } to DoubleArray(HISTORY_SEC) { Double.NaN } }
            val i = (sec % HISTORY_SEC).toInt()
            if (secs[i] != sec) { secs[i] = sec; vals[i] = v }
            val live = DoubleArray(HISTORY_SEC) { k -> if (secs[k] >= 0 && sec - secs[k] < HISTORY_SEC) vals[k] else Double.NaN }
            return z(v, live)
        }

        /** The buyers' share every 10 seconds for the last 30 minutes (the detail's mini history). */
        private fun keepHistory(name: String, sec: Long, buyers: Int) {
            val h = history.getOrPut(name) { ArrayDeque() }
            val slot = sec / 10 * 10
            if (h.lastOrNull()?.first == slot) h.removeLast()
            h.addLast(slot to buyers)
            while (h.isNotEmpty() && slot - h.first().first >= HISTORY_SEC) h.removeFirst()
        }

        private val priors = HashMap<String, Auction.Levels>()

        /** [name]'s prior-day profile (from its future's 1-minute candles; null forgets it). */
        @Synchronized fun prior(name: String, l: Auction.Levels?) { if (l == null) priors.remove(name) else priors[name] = l }

        @Synchronized fun prior(name: String): Auction.Levels? = priors[name]

        private fun future(name: String) = trackers.values.firstOrNull { it.name == name && it.role == Role.FUTURE }

        /** Today's 1-minute [candles] of [name]'s future before the stream ([Auction.Day.seed]). False: no future followed. */
        @Synchronized fun seed(name: String, candles: List<Auction.Candle>, nowSec: Long): Boolean {
            val d = future(name)?.day ?: return false
            d.seed(candles, nowSec)
            return true
        }

        /** [name]'s auction at [nowMs] (its future's profile, delta, footprint, VWAP, TPO; null: none today). */
        @Synchronized fun auction(name: String, nowMs: Long): Auction.Snapshot? {
            val d = future(name)?.day ?: return null
            d.openMin = (sessions[name] ?: TrapGuard.Session.NSE).openMin
            return d.snapshot(name, nowMs / 1000, priors[name])
        }

        /** The latest [n] prints of [name]'s future ([watch]: of the charted option instead), newest first. */
        @Synchronized fun tape(name: String, watch: Boolean, n: Int = 200): List<TapeHeat.Print> =
            pick(name, watch)?.tape?.latest(n).orEmpty()

        /** The heatmap of [name]'s future ([watch]: of the charted option) at [nowMs] (null: none kept). */
        @Synchronized fun heat(name: String, watch: Boolean, nowMs: Long, maxCols: Int = 300): TapeHeat.Frame? =
            pick(name, watch)?.heat?.frame(nowMs / 1000, maxCols)

        /** The charted option's tracker (WATCH) or [name]'s future. */
        private fun pick(name: String, watch: Boolean): Tracker? =
            if (watch) watchToken?.let { trackers[it] } ?: trackers.values.firstOrNull { it.role == Role.WATCH } else future(name)

        /** [name]'s buyers' share every 10 s over the last 30 minutes, oldest first (epoch second to share). */
        @Synchronized fun history(name: String): List<Pair<Long, Int>> = history[name]?.toList().orEmpty()
    }

    // ---- words ----------------------------------------------------------------------------------------------------------

    /** "buyers 72", "sellers 64", "balanced 52", "warming up" (under two minutes of history). */
    fun word(r: Read?): String = when {
        r == null -> "no flow"
        !r.warm -> "warming up"
        else -> "${r.side.name.lowercase(Locale.ENGLISH)} ${r.strength}"
    }

    /** Home's line: "Flow BN: buyers 72". */
    fun line(name: String, r: Read?): String = "Flow ${short(name)}: ${word(r)}" + if (r != null && r.flags.isNotEmpty()) " ⚠" else ""

    private fun signed(x: Double) = if (x.isNaN()) "—" else (if (x >= 0) "+" else "−") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun pct(x: Double) = if (x.isNaN()) "—" else (if (x >= 0) "+" else "−") + "%.0f%%".format(Locale.ENGLISH, abs(x) * 100)
    private fun zw(v: Double?) = v?.let { " (z " + (if (it >= 0) "+" else "−") + "%.1f".format(Locale.ENGLISH, abs(it)) + ")" } ?: ""

    /** The detail's components, each with its z: label to value. */
    fun components(r: Read): List<Pair<String, String>> = listOf(
        "Depth imbalance (5 levels) 10s / 60s / 5m" to "${pct(r.depth10)} / ${pct(r.depth60)} / ${pct(r.depth300)}${zw(r.z["depth60"])}",
        "Volume delta, tick rule 10s / 60s / 5m" to "${signed(r.cvd10)} / ${signed(r.cvd60)} / ${signed(r.cvd300)}${zw(r.z["cvd60"])}",
        "Order-flow imbalance 10s / 60s / 5m" to "${signed(r.ofi10)} / ${signed(r.ofi60)} / ${signed(r.ofi300)}${zw(r.z["ofi"])}",
        "Volume delta, quote rule 60s" to "${signed(r.qcvd60)}${zw(r.z["quote"])}",
        "Top-of-book queue" to pct(r.queue),
        "Total buy / sell quantity (shown only)" to (if (r.ratio.isNaN()) "—" else "%.2f".format(Locale.ENGLISH, r.ratio)),
        "Calls vs puts bought (ATM ±2, 60s)" to (if (r.optionNet.isNaN()) "no options followed"
            else "${signed(r.ce60)} calls / ${signed(r.pe60)} puts, net ${pct(r.optionNet)}${zw(r.z["options"])}"),
        "Futures OI last minute" to (r.buildUp?.let { b -> b.words + (r.oiChange?.let { " (OI ${if (it >= 0) "+" else "−"}${"%,d".format(Locale.ENGLISH, abs(it))})" } ?: "") }
            ?: "not known yet"),
        "Pulled without trading (60s)" to "bids ${"%,d".format(Locale.ENGLISH, r.pulledBid60)} / offers ${"%,d".format(Locale.ENGLISH, r.pulledAsk60)}" +
            (if (r.cancelToTrade.isNaN()) "" else ", ${"%.1f".format(Locale.ENGLISH, r.cancelToTrade)} cancelled per traded"),
        "Absorption (last minute)" to (absorption(r) ?: "none"),
        "Trap guard" to trapWords(r),
    )

    /** "absorption at 52,310 (sellers)" when a defended level held in the last minute, else null. */
    fun absorption(r: Read?): String? = r?.takeIf { !it.absorbAt.isNaN() && it.absorbBy != 0 }?.let {
        "absorption at ${"%,.2f".format(Locale.ENGLISH, it.absorbAt).removeSuffix(".00")} (${if (it.absorbBy > 0) "buyers" else "sellers"})"
    }

    /** The traps found, in plain words ("pull detected (offers), possible stop hunt"), or "none". */
    fun trapWords(r: Read): String = if (r.flags.isEmpty()) "none" else r.flags.joinToString(", ") { t ->
        if (t == TrapGuard.Trap.TIME_WINDOW && r.timeWhy != null) "time window: ${r.timeWhy}" else t.words
    } + if (r.rawBuyers != r.buyers) " (buyers ${r.rawBuyers} before the guard)" else ""

    /** A book in a few characters for the shadow log: "b 101.5x300/4 101.45x75/2;a 101.6x120/3" (no separator, no line break). */
    fun bookText(d: KiteTicks.Depth): String {
        fun side(l: List<KiteTicks.Level>) = l.joinToString(" ") { "${it.price}x${it.qty}/${it.orders}" }
        return "b " + side(d.bids) + ";a " + side(d.asks)
    }

    // ---- Jarvis ---------------------------------------------------------------------------------------------------------

    private val FLOW = Regex(" (order ?flow|orderflow|flow of orders|buying pressure|selling pressure|buyers or sellers|sellers or buyers|" +
        "who is buying|who s buying|whos buying|kaun kharid raha|kaun bech raha|kharidar ya bechne wale|tape|" +
        // The auction's readings ([Auction.topic]): answered from the same live flow.
        "volume profile|value area|point of control|market profile|auction regime|gamma regime|gamma exposure|gex|zero gamma|gamma flip|dealer gamma) ")
    /** How the flow has done for the strategies: [FlowShadow]'s question, not this one. */
    private val HELP = Regex(" (helping|helped|help|working|worked|record|results?|paying|useful|worth) ")
    private val NOT = Regex(" (turn|switch|set|enable|disable|confirm mode|shadow mode|export|delete|explain|what is an?|meaning|definition) ")

    /** Which underlying [text] asks the order flow of ("" when none is named: the charted or BankNifty), or null. */
    fun asked(text: String): String? {
        val t = " " + text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (!FLOW.containsMatchIn(t) || HELP.containsMatchIn(t) || NOT.containsMatchIn(t)) return null
        return when {
            Regex(" (midcap ?nifty|midcpnifty|mid cap nifty|midcp) ").containsMatchIn(t) -> "MIDCPNIFTY"
            Regex(" (fin ?nifty|finnifty|nifty fin|fin) ").containsMatchIn(t) -> "FINNIFTY"
            Regex(" (bank ?nifty|banknifty|nifty bank|bn|bank) ").containsMatchIn(t) -> "BANKNIFTY"
            Regex(" (nifty|nifty 50|nf) ").containsMatchIn(t) -> "NIFTY"
            else -> ""
        }
    }

    /** Jarvis's answer for [name] from [r] (null: no live flow) at [nowSec]. Facts only; never a trade suggestion. */
    fun answer(name: String, r: Read?, nowSec: Long): String {
        val who = label(name)
        if (r == null || nowSec - r.atSec > 30) return "I have no live order flow for $who right now, Boss: it needs the Zerodha price stream " +
            "(logged in, market hours) and its near future followed in full mode."
        if (!r.warm) return "$who's order flow is still warming up, Boss: I need two minutes of its future's ticks before I call a side."
        val side = when (r.side) {
            Side.BUYERS -> "buyers are in control, strength ${r.strength}"
            Side.SELLERS -> "sellers are in control, strength ${r.strength}"
            Side.BALANCED -> "it is balanced (${r.buyers} on the buyers' side)"
        }
        val parts = ArrayList<String>()
        parts += "order-flow imbalance over a minute ${signed(r.ofi60)}"
        parts += "volume delta ${signed(r.cvd60)}"
        if (!r.depth.isNaN()) parts += "the 5-level book ${pct(r.depth)} to the bids"
        if (!r.optionNet.isNaN()) parts += if (r.optionNet >= 0) "calls bought more than puts (net ${pct(r.optionNet)})" else "puts bought more than calls (net ${pct(-r.optionNet)})"
        r.buildUp?.takeIf { it != BuildUp.NEUTRAL }?.let { parts += "the future shows ${it.words}" }
        absorption(r)?.let { parts += it }
        val traps = if (r.flags.isEmpty()) "" else " Trap guard: ${trapWords(r)}."
        return "$who's order flow, from its future: $side. " + parts.joinToString(", ").replaceFirstChar { it.uppercase() } +
            ".$traps It is a live read, not a forecast; strategies only log it unless you set one to confirm."
    }
}
