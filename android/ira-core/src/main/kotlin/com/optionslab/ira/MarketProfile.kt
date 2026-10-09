package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * The market profile (TPO) of the index future in 30-minute periods (Boss, 9 Oct 2026): a LIVE READING and shadow-log
 * fields only, never a rule. From the day's 1-minute bars ([Auction.Minute]):
 *
 *  - the initial balance: the first 60 minutes' high and low ([Read.ibHigh], [Read.ibLow]);
 *  - the opening type, once the first period is done ([openType], Dalton's four): open-drive (opened at one end of the first
 *    30 minutes and closed them at the other, the second period not back through the open), open-test-drive (tested one way
 *    first, then closed at the other end), open-rejection-reverse (tested one way, closed back across the open but not at the
 *    other end), open-auction (anything else: rotation around the open);
 *  - a guess at the day type once the initial balance is set ([dayType]): normal (range within 1.15× the IB), normal
 *    variation (one side extended, under 2×), trend (one side, 2× or more, price in the outer quarter), neutral (both sides);
 *  - single prints (bins one period alone traded, inside the day's range) and a poor high / low (the extreme bin traded in
 *    two or more periods: no excess).
 *
 * Pure.
 */
object MarketProfile {
    enum class OpenType(val code: String, val words: String) {
        OPEN_DRIVE("OD", "an open-drive"), OPEN_TEST_DRIVE("OTD", "an open-test-drive"),
        OPEN_REJECTION_REVERSE("ORR", "an open-rejection-reverse"), OPEN_AUCTION("OA", "an open-auction"),
    }

    enum class DayType(val words: String) {
        TREND("a trend day"), NORMAL("a normal day"), NORMAL_VARIATION("a normal-variation day"), NEUTRAL("a neutral day"),
    }

    data class Read(
        /** The initial balance (first two periods); null until the first period has a minute. */
        val ibHigh: Double?, val ibLow: Double?, val ibDone: Boolean,
        val openType: OpenType?, val openDir: Int,
        val dayType: DayType?,
        /** Single-print stretches (low, high of each, bins' middles), lowest first. */
        val singles: List<Pair<Double, Double>>,
        val poorHigh: Boolean, val poorLow: Boolean,
        /** Periods seen (A, B, C...). */
        val periods: Int,
    ) {
        val ibRange: Double? get() = if (ibHigh != null && ibLow != null) ibHigh - ibLow else null
    }

    /**
     * The opening type from the first period's minutes [a] (oldest first) and the second period's [b] (may be empty: the
     * drive is then judged on the first alone). Null with no minutes. [Pair.second]: the drive's direction (+1 up, −1 down, 0).
     */
    fun openType(a: List<Auction.Minute>, b: List<Auction.Minute>): Pair<OpenType, Int>? {
        if (a.isEmpty()) return null
        val open = a.first().open
        val hi = a.maxOf { it.high }; val lo = a.minOf { it.low }; val close = a.last().close
        val r = hi - lo
        if (r <= 1e-9) return OpenType.OPEN_AUCTION to 0
        val hiAt = a.indexOfFirst { it.high >= hi - 1e-9 }; val loAt = a.indexOfFirst { it.low <= lo + 1e-9 }
        val topQ = close >= hi - 0.25 * r; val botQ = close <= lo + 0.25 * r
        // Opened at one end and closed at the other, the next period not back through the open.
        if (open - lo <= 0.1 * r && topQ && (b.isEmpty() || b.minOf { it.low } >= open)) return OpenType.OPEN_DRIVE to 1
        if (hi - open <= 0.1 * r && botQ && (b.isEmpty() || b.maxOf { it.high } <= open)) return OpenType.OPEN_DRIVE to -1
        // Tested one way first (that extreme came first), then closed at the other end.
        if (loAt < hiAt && topQ) return OpenType.OPEN_TEST_DRIVE to 1
        if (hiAt < loAt && botQ) return OpenType.OPEN_TEST_DRIVE to -1
        // Tested one way, rejected back across the open (not at the other end).
        if (hiAt < loAt && close < open && hi - open >= 0.25 * r) return OpenType.OPEN_REJECTION_REVERSE to -1
        if (loAt < hiAt && close > open && open - lo >= 0.25 * r) return OpenType.OPEN_REJECTION_REVERSE to 1
        return OpenType.OPEN_AUCTION to 0
    }

    /** The day type from the IB ([ibHigh], [ibLow]), the day's range and the last [price]. Null with no IB range. */
    fun dayType(ibHigh: Double, ibLow: Double, high: Double, low: Double, price: Double): DayType? {
        val ib = ibHigh - ibLow
        if (ib <= 1e-9) return null
        val up = high > ibHigh + 1e-9; val down = low < ibLow - 1e-9
        val range = high - low
        return when {
            up && down -> DayType.NEUTRAL
            range <= 1.15 * ib + 1e-9 -> DayType.NORMAL
            range >= 2 * ib && ((up && price >= high - 0.25 * range) || (down && price <= low + 0.25 * range)) -> DayType.TREND
            else -> DayType.NORMAL_VARIATION
        }
    }

    /** The profile of [minutes] at [nowSec], periods from [openMin], prices in bins of [bin]. Null with no minute in a period. */
    fun read(minutes: List<Auction.Minute>, nowSec: Long, openMin: Int, bin: Double): Read? {
        if (!(bin > 0)) return null
        val by = minutes.filter { Auction.periodOf(it.startSec, openMin) >= 0 }.sortedBy { it.startSec }
            .groupBy { Auction.periodOf(it.startSec, openMin) }
        if (by.isEmpty()) return null
        val nowP = Auction.periodOf(nowSec, openMin)
        val ib = (by[0].orEmpty() + by[1].orEmpty())
        val ibDone = nowP >= 2
        val ibHigh = ib.maxOfOrNull { it.high }; val ibLow = ib.minOfOrNull { it.low }
        val ot = if (nowP >= 1) openType(by[0].orEmpty(), if (nowP >= 2) by[1].orEmpty() else emptyList()) else null
        val all = by.values.flatten()
        val high = all.maxOf { it.high }; val low = all.minOf { it.low }
        val price = all.last().close
        val dt = if (ibDone && ibHigh != null && ibLow != null) dayType(ibHigh, ibLow, high, low, price) else null
        // TPO counts per bin: how many periods traded there.
        val count = HashMap<Long, Int>()
        for ((_, ms) in by) {
            val h = ms.maxOf { it.high }; val l = ms.minOf { it.low }
            for (i in Auction.binOf(l, bin)..Auction.binOf(h, bin)) count[i] = (count[i] ?: 0) + 1
        }
        val top = Auction.binOf(high, bin); val bot = Auction.binOf(low, bin)
        val singles = ArrayList<Pair<Double, Double>>()
        var run: Long? = null
        for (i in (bot + 1)..(top - 1).coerceAtLeast(bot)) {
            val single = i in (bot + 1) until top && count[i] == 1
            if (single && run == null) run = i
            if (!single && run != null) { singles += Auction.center(run, bin) to Auction.center(i - 1, bin); run = null }
        }
        run?.let { singles += Auction.center(it, bin) to Auction.center(top - 1, bin) }
        return Read(ibHigh, ibLow, ibDone, ot?.first, ot?.second ?: 0, dt, singles,
            (count[top] ?: 0) >= 2 && by.size >= 2, (count[bot] ?: 0) >= 2 && by.size >= 2, by.size)
    }

    private fun px(x: Double) = "%,.2f".format(Locale.ENGLISH, x).removeSuffix(".00")

    /** The detail sheet's lines for [r]. */
    fun lines(r: Read): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += "Initial balance (first 60 min)" to (if (r.ibHigh == null || r.ibLow == null) "not yet" else
            "${px(r.ibLow)}–${px(r.ibHigh)} (${px(abs(r.ibHigh - r.ibLow))} pts)" + if (r.ibDone) "" else ", still forming")
        out += "Opening type" to (r.openType?.let { it.words.removePrefix("an ") + when (r.openDir) { 1 -> " (up)"; -1 -> " (down)"; else -> "" } }
            ?: "after the first 30 minutes")
        out += "Day type so far" to (r.dayType?.words?.removePrefix("a ")?.removeSuffix(" day") ?: "after the initial balance")
        out += "Single prints / poor high, low" to (if (r.singles.isEmpty()) "none" else r.singles.joinToString(", ") { (a, b) -> if (a == b) px(a) else "${px(a)}–${px(b)}" }) +
            " · " + listOfNotNull("poor high".takeIf { r.poorHigh }, "poor low".takeIf { r.poorLow }).ifEmpty { listOf("no poor extremes") }.joinToString(", ")
        return out
    }
}
