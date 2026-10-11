package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Session
import com.optionslab.engine.sandbox.PaperSpread
import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The strategy lab's virtual trader (11 Oct): one set of option-buying rules ([Rules]) followed on one stored or recorded day
 * ([Day]) - the index's 1-minute bars decide, the option's 1-minute WICKS settle, the way the trade manager's counterfactual
 * settles ([TradeManager.walk]): a minute touching both the stop and the target counts as the stop, a minute opening through
 * a level fills at its open. Costs are the app's paper costs ([SandboxCosts.charge] on each leg) with the paper half-spread
 * ([PaperSpread.DEFAULTS]) paid TWICE on each fill (the paper's own plus one more half-spread: stricter than paper).
 *
 * Always 1 lot, always BUYING a call or a put near the money - never selling, never adding. A day without the option's
 * minutes is followed on the index alone ([Trade.approx]: a premium modelled as half the index move less a day's decay),
 * labelled "index-only approximation" and judged on a stricter bar by its callers.
 *
 * Nothing here places, changes or reads an order; no clock, no I/O. The forge's rule grammar ([StrategyForge]); the running
 * arms are NOT modelled here: the strategy lab replays them with their own rule code ([ArmLab], S3), on this file's [Day].
 */
object ShadowSim {
    /** 09:15, the first minute (minutes of the day, IST). */
    const val OPEN = 9 * 60 + 15
    /** The F&O hours rules the lab keeps: entries decided no earlier than 09:16 and no later than 14:30, flat by 15:10. */
    const val FIRST_ENTRY = 9 * 60 + 16
    const val LAST_ENTRY = 14 * 60 + 30
    const val SQUARE_OFF = 15 * 60 + 10
    /** The index-only premium: this share of the index, half its move, less this much of the premium over a whole day. */
    const val APPROX_PREMIUM = 0.0045
    const val APPROX_DELTA = 0.5
    const val APPROX_DECAY = 0.3

    // ---- the data --------------------------------------------------------------------------------------------------------

    /** One instrument's minutes of a day: minute of the day (ascending) and prices. */
    class Series(val minutes: IntArray, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray, val oi: LongArray? = null) {
        val size: Int get() = minutes.size
        /** The index of the first minute at or after [m] (size when none). */
        fun atOrAfter(m: Int): Int {
            var lo = 0; var hi = minutes.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (minutes[mid] < m) lo = mid + 1 else hi = mid }
            return lo
        }
        /** The index of the last minute at or before [m] (-1 when none). */
        fun atOrBefore(m: Int): Int = atOrAfter(m + 1) - 1
    }

    /** One option of the day's nearest series: its strike, +1 call / -1 put, and its minutes. */
    class Leg(val strike: Double, val right: Int, val s: Series)

    /** Bars of [minutes] each, folded from the index's 1-minute bars, labelled by their START minute. */
    class Folded(val start: IntArray, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray) {
        val size: Int get() = start.size
    }

    /**
     * One day: the [index]'s minutes, the nearest series' options ([legs], empty when not stored), the previous session's
     * high, low and close, India VIX's minutes when kept, and whether it is an expiry day of the index. [busy]: the market
     * brain's busy / quiet read for the day (null: not known).
     */
    class Day(
        val date: LocalDate, val underlying: String, val lot: Int, val expiryDay: Boolean, val index: Series,
        val legs: List<Leg> = emptyList(), val prevHigh: Double? = null, val prevLow: Double? = null, val prevClose: Double? = null,
        val vix: Series? = null, val busy: Boolean? = null, val strikeStep: Int = stepOf(underlying),
    ) {
        val optionBars: Boolean get() = legs.isNotEmpty()
        /** What the real-arm replays ([ArmLab]) work out once per day and share across a night's variants (memory only). */
        val memo = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private val folds = arrayOfNulls<Folded>(31)
        /** The index folded to [minutes]-minute bars (kept: a day is shared by every candidate of a night). */
        fun fold(minutes: Int): Folded {
            val k = minutes.coerceIn(1, 30)
            folds[k]?.let { return it }
            return synchronized(folds) { folds[k] ?: foldOf(index, k).also { folds[k] = it } }
        }
        /** The day's open (the first minute's open). */
        val open: Double get() = if (index.size > 0) index.o[0] else Double.NaN
        private val legByKey: Map<Long, Leg> by lazy { legs.associateBy { key(it.strike, it.right) } }
        fun leg(strike: Double, right: Int): Leg? = legByKey[key(strike, right)]
        private fun key(strike: Double, right: Int) = (strike * 100).roundToInt().toLong() * 4 + (right + 1)
        /** The running time-weighted average of the typical price (the index has no volume): the VWAP the lab can know. */
        val twap: DoubleArray by lazy {
            val out = DoubleArray(index.size); var s = 0.0
            for (i in 0 until index.size) { s += (index.h[i] + index.l[i] + index.c[i]) / 3; out[i] = s / (i + 1) }
            out
        }
    }

    fun stepOf(underlying: String): Int = when (underlying.uppercase(Locale.ENGLISH)) {
        "BANKNIFTY", "SENSEX" -> 100; "MIDCPNIFTY" -> 25; else -> 50
    }

    private fun foldOf(s: Series, k: Int): Folded {
        if (k == 1) return Folded(s.minutes, s.o, s.h, s.l, s.c)
        val st = ArrayList<Int>(); val o = ArrayList<Double>(); val h = ArrayList<Double>(); val l = ArrayList<Double>(); val c = ArrayList<Double>()
        for (i in 0 until s.size) {
            val b = OPEN + Math.floorDiv(s.minutes[i] - OPEN, k) * k
            if (st.isEmpty() || st.last() != b) { st += b; o += s.o[i]; h += s.h[i]; l += s.l[i]; c += s.c[i] }
            else { val j = st.size - 1; h[j] = maxOf(h[j], s.h[i]); l[j] = minOf(l[j], s.l[i]); c[j] = s.c[i] }
        }
        return Folded(st.toIntArray(), o.toDoubleArray(), h.toDoubleArray(), l.toDoubleArray(), c.toDoubleArray())
    }

    private fun seriesOf(minutes: IntArray, close: DoubleArray, open: DoubleArray?, high: DoubleArray?, low: DoubleArray?, oi: LongArray?): Series =
        Series(minutes, open ?: close, high ?: close, low ?: close, close, oi)

    /**
     * A stored session ([Session]: the index and its options' minutes, as the app keeps them) as a [Day]: the options of the
     * nearest series on or after the day (an expiry day's own). [prev]: the session before it (its high, low and close).
     */
    fun fromSession(s: Session, underlying: String, prev: Session? = null, vix: Series? = null, busy: Boolean? = null, strikes: Int = 4): Day? {
        val ix = s.index ?: return null
        if (ix.size < 30) return null
        val index = seriesOf(ix.minutes, ix.close, ix.open, ix.high, ix.low, null)
        val expiry = s.options.mapNotNull { it.expiry }.filter { !it.isBefore(s.day) }.minOrNull()
        // Only the strikes near the day's range are kept (the lab buys at or one strike in the money): a phone's memory.
        val step = stepOf(underlying).toDouble()
        val lo = (ix.low ?: ix.close).minOrNull()?.minus(strikes * step) ?: Double.NEGATIVE_INFINITY
        val hi = (ix.high ?: ix.close).maxOrNull()?.plus(strikes * step) ?: Double.POSITIVE_INFINITY
        val legs = s.options.filter { it.expiry == expiry && it.size > 0 && it.strike in lo..hi }.map {
            Leg(it.strike, if (it.right == Right.CE) 1 else -1, seriesOf(it.minutes, it.close, it.open, it.high, it.low, it.oi))
        }
        val lot = s.lotHint ?: s.options.firstOrNull()?.lot ?: lotOf(underlying)
        val p = prev?.index
        return Day(s.day, underlying, lot, expiry == s.day, index, legs,
            p?.let { (it.high ?: it.close).maxOrNull() }, p?.let { (it.low ?: it.close).minOrNull() }, p?.close?.lastOrNull(), vix, busy)
    }

    /** [d] with the market brain's busy / quiet read set. */
    fun withBusy(d: Day, busy: Boolean?): Day = Day(d.date, d.underlying, d.lot, d.expiryDay, d.index, d.legs, d.prevHigh, d.prevLow, d.prevClose,
        d.vix, busy, d.strikeStep)

    /**
     * The busy / quiet read for each of [days] (oldest first), as the market brain judges a session: its first 30 minutes'
     * range against the median of the 20 sessions before (busy above it); null for the first 20.
     */
    fun markBusy(days: List<Day>): List<Day> {
        val ranges = days.map { d ->
            val k = d.index.atOrBefore(OPEN + 29)
            if (k < 0) Double.NaN else { var h = Double.NEGATIVE_INFINITY; var l = Double.POSITIVE_INFINITY
                for (i in 0..k) { h = maxOf(h, d.index.h[i]); l = minOf(l, d.index.l[i]) }; (h - l) / d.index.o[0] }
        }
        return days.mapIndexed { i, d ->
            val prev = ranges.subList(maxOf(0, i - 20), i).filter { !it.isNaN() }.sorted()
            withBusy(d, if (prev.size < 20 || ranges[i].isNaN()) null else ranges[i] > prev[prev.size / 2])
        }
    }

    /** A lot when the data does not say (the current NSE lots). */
    fun lotOf(underlying: String): Int = when (underlying.uppercase(Locale.ENGLISH)) {
        "BANKNIFTY" -> 30; "NIFTY" -> 65; "FINNIFTY" -> 60; "MIDCPNIFTY" -> 120; "SENSEX" -> 20; else -> 1
    }

    // ---- the rules ---------------------------------------------------------------------------------------------------------

    /** The entry building blocks (the forge's grammar; every one is a rule the engine's arms already trade or research ran). */
    enum class Entry(val words: String) {
        ORB_BREAK("ORB break"), ORB_SWEEP("opening-range sweep"), RANGE_FADE("range-edge fade"), VWAP_RECLAIM("VWAP reclaim"),
        LEVEL_SWEEP("liquidity level sweep"), OI_BUILDUP("OI build-up"), GAP_FADE("gap fade"), TIME_OF_DAY("time-of-day momentum"),
        VIX_DIVERGENCE("VIX divergence"), PREV_DAY_BREAK("previous day high/low break"), SQUEEZE_BREAK("range compression then expansion"),
    }

    /** The filters (the market brain's busy / quiet read, expiry day, VIX band); the time window is in [Rules] itself. */
    enum class Filter(val words: String) {
        EXPIRY_ONLY("expiry days only"), SKIP_EXPIRY("never on expiry day"), VIX_LOW("VIX under 14"), VIX_HIGH("VIX 14 or more"),
        BUSY_ONLY("busy markets only"), QUIET_ONLY("quiet markets only"),
    }

    /**
     * One strategy's rules. Entries: [entry] on [barMin]-minute bars of the index decided at a bar's close, filled at the next
     * minute's open, between [fromMin] and [untilMin]; [orMin] the opening range's minutes, [buffer] a share of the index a
     * break must clear, [param] the entry's own threshold (see [signal]), at most [maxTrades] a day, [itm] strikes in the money.
     * Exits (the first of): the premium [stopPct] / [stopPts] (one is required), the premium [targetPct] / [targetPts], R12's
     * no-progress exit (not up [noProgressGain] after [noProgressMin] minutes), a [timeStopMin] time stop, an index level
     * [indexStopPct] back against the entry, and [squareOff].
     */
    data class Rules(
        val entry: Entry, val barMin: Int = 5, val orMin: Int = 45, val buffer: Double = 0.0, val param: Double = 0.0,
        val fresh: Boolean = false, val fromMin: Int = 10 * 60, val untilMin: Int = 14 * 60, val filters: Set<Filter> = emptySet(),
        val stopPct: Double? = null, val stopPts: Double? = null, val targetPct: Double? = null, val targetPts: Double? = null,
        val noProgressMin: Int? = null, val noProgressGain: Double = 0.05, val timeStopMin: Int? = null, val indexStopPct: Double? = null,
        val maxTrades: Int = 1, val itm: Int = 0, val squareOff: Int = SQUARE_OFF,
    ) {
        /** Option buying, 1 lot, a stop, inside the F&O hours rules: what every rule set must be. Null when valid, else why not. */
        fun invalid(): String? = when {
            stopPct == null && stopPts == null -> "no stop"
            stopPct != null && (stopPct <= 0 || stopPct > 0.5) -> "stop outside 0-50% of the premium"
            stopPts != null && stopPts <= 0 -> "stop at or below zero"
            targetPct != null && targetPct <= 0 -> "target at or below zero"
            targetPts != null && targetPts <= 0 -> "target at or below zero"
            fromMin < FIRST_ENTRY || untilMin > LAST_ENTRY || fromMin >= untilMin -> "entries outside 09:16-14:30"
            squareOff > SQUARE_OFF || squareOff <= untilMin -> "square-off after 15:10 or before the last entry"
            barMin !in setOf(1, 3, 5, 15) -> "bar size"
            maxTrades !in 1..3 -> "trades a day"
            itm !in 0..1 -> "strike not near the money"
            orMin !in 15..90 -> "opening range minutes"
            else -> null
        }
        /** The rules in plain English (the forge's sheet and Jarvis's "what is candidate X"). */
        fun words(underlying: String): String {
            val p = { x: Double -> "%.0f%%".format(Locale.ENGLISH, x * 100) }
            val entryWords = when (entry) {
                Entry.ORB_BREAK -> "a ${barMin}-minute close beyond the first $orMin minutes' range" + (if (buffer > 0) " by ${"%.2f".format(Locale.ENGLISH, buffer * 100)}%" else "") + (if (fresh) " (fresh breaks only)" else "")
                Entry.ORB_SWEEP -> "a ${barMin}-minute bar that pokes beyond the first $orMin minutes' range and closes back inside (fade it)"
                Entry.RANGE_FADE -> "a ${barMin}-minute bar at the outer ${p(param)} of the first $orMin minutes' range closing back inside (fade it)"
                Entry.VWAP_RECLAIM -> "a ${barMin}-minute close crossing back over the day's average price"
                Entry.LEVEL_SWEEP -> "a ${barMin}-minute bar that takes yesterday's high or low and closes back (fade the sweep)"
                Entry.OI_BUILDUP -> "the near-money puts' OI building ${p(param)} faster than the calls' (bullish), or the reverse"
                Entry.GAP_FADE -> "a gap of at least ${p(param)} from yesterday's close, faded back toward it"
                Entry.TIME_OF_DAY -> "at ${hhmm(fromMin)}, the day's move so far of at least ${p(param)}, followed"
                Entry.VIX_DIVERGENCE -> "the index up ${p(param)} with India VIX up 2% (buy the put), or both down (buy the call)"
                Entry.PREV_DAY_BREAK -> "a ${barMin}-minute close beyond yesterday's high or low"
                Entry.SQUEEZE_BREAK -> "a ${barMin}-minute close out of a tight ${param.toInt()}-bar box"
            }
            val exits = listOfNotNull(
                stopPct?.let { "stop ${p(it)} under the price paid" }, stopPts?.let { "stop ${it.toInt()} premium points" },
                targetPct?.let { "target +${p(it)}" }, targetPts?.let { "target +${it.toInt()} points" },
                noProgressMin?.let { "out if not up ${p(noProgressGain)} after $it minutes" }, timeStopMin?.let { "out after $it minutes" },
                indexStopPct?.let { "out if the index goes ${"%.2f".format(Locale.ENGLISH, it * 100)}% back" }, "flat by ${hhmm(squareOff)}")
            return "$underlying, 1 lot, buys the ${if (itm > 0) "one-in-the-money" else "at-the-money"} call or put on $entryWords, " +
                "between ${hhmm(fromMin)} and ${hhmm(untilMin)}" + (if (maxTrades > 1) ", up to $maxTrades a day" else ", once a day") +
                (if (filters.isNotEmpty()) "; " + filters.joinToString(", ") { it.words } else "") + ". Exits: " + exits.joinToString(", ") + "."
        }
    }

    /** [r] as one line ("e=ORB_BREAK,b=5,..."): kept in the lab's own files. */
    fun encode(r: Rules): String = listOfNotNull(
        "e=${r.entry.name}", "b=${r.barMin}", "or=${r.orMin}", "buf=${r.buffer}", "p=${r.param}", "fr=${if (r.fresh) 1 else 0}",
        "from=${r.fromMin}", "until=${r.untilMin}", "f=${r.filters.joinToString("+") { it.name }}",
        r.stopPct?.let { "sp=$it" }, r.stopPts?.let { "spt=$it" }, r.targetPct?.let { "tp=$it" }, r.targetPts?.let { "tpt=$it" },
        r.noProgressMin?.let { "np=$it" }, "npg=${r.noProgressGain}", r.timeStopMin?.let { "ts=$it" }, r.indexStopPct?.let { "is=$it" },
        "mt=${r.maxTrades}", "itm=${r.itm}", "sq=${r.squareOff}").joinToString(",")

    /** [encode]'s line back; null when it does not read (or the rules it reads are not valid). */
    fun decode(s: String): Rules? = runCatching {
        val m = s.split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        Rules(Entry.valueOf(m.getValue("e")), m.getValue("b").toInt(), m.getValue("or").toInt(), m.getValue("buf").toDouble(),
            m.getValue("p").toDouble(), m["fr"] == "1", m.getValue("from").toInt(), m.getValue("until").toInt(),
            m["f"].orEmpty().split('+').filter { it.isNotBlank() }.map { Filter.valueOf(it) }.toSet(),
            m["sp"]?.toDouble(), m["spt"]?.toDouble(), m["tp"]?.toDouble(), m["tpt"]?.toDouble(), m["np"]?.toInt(), m["npg"]?.toDouble() ?: 0.05,
            m["ts"]?.toInt(), m["is"]?.toDouble(), m.getValue("mt").toInt(), m.getValue("itm").toInt(), m.getValue("sq").toInt())
    }.getOrNull()?.takeIf { it.invalid() == null }

    fun hhmm(m: Int) = "%02d:%02d".format(Locale.ENGLISH, m / 60, m % 60)

    // ---- the trade --------------------------------------------------------------------------------------------------------

    /** One virtual trade: the day, the minutes in and out, +1 call / -1 put, the premiums, the rupees after costs, why out. */
    data class Trade(val day: LocalDate, val entryMin: Int, val exitMin: Int, val dir: Int, val entry: Double, val exit: Double,
                     val net: Double, val why: String, val approx: Boolean)

    /** The paper half-spread for [underlying], paid twice on each fill (the paper's own plus one more: stricter than paper). */
    fun halfSpread(underlying: String): Double = 2 * (PaperSpread.DEFAULTS[underlying.uppercase(Locale.ENGLISH)] ?: PaperSpread.UNKNOWN)

    /** The rupees of one round trip of [lot] units bought at [entry] and sold at [exit], spreads and the paper charges paid. */
    fun net(underlying: String, lot: Int, entry: Double, exit: Double): Double {
        val hs = halfSpread(underlying)
        val buy = entry * (1 + hs); val sell = (exit * (1 - hs)).coerceAtLeast(0.0)
        val ch = SandboxCosts.charge("BUY", BigDecimal(buy), lot).toDouble() + SandboxCosts.charge("SELL", BigDecimal(sell), lot).toDouble()
        return (sell - buy) * lot - ch
    }

    // ---- the day's run --------------------------------------------------------------------------------------------------

    /** Does [r] trade at all on [d] (its filters)? */
    fun allowed(r: Rules, d: Day): Boolean {
        for (f in r.filters) when (f) {
            Filter.EXPIRY_ONLY -> if (!d.expiryDay) return false
            Filter.SKIP_EXPIRY -> if (d.expiryDay) return false
            Filter.VIX_LOW -> { val v = vixOpen(d) ?: return false; if (v >= 14.0) return false }
            Filter.VIX_HIGH -> { val v = vixOpen(d) ?: return false; if (v < 14.0) return false }
            Filter.BUSY_ONLY -> if (d.busy != true) return false
            Filter.QUIET_ONLY -> if (d.busy != false) return false
        }
        return true
    }

    private fun vixOpen(d: Day): Double? = d.vix?.takeIf { it.size > 0 }?.c?.get(0)

    /** The busy / quiet read is known from this minute on (its first 30 minutes' range, [markBusy]). */
    const val BUSY_KNOWN = OPEN + 30

    /**
     * The first decision minute [r] may use: a busy / quiet filter reads the day's first 30 minutes, so no entry may be
     * decided before 09:45 under it (R3 finding 4: deciding at 09:20 on a read made at 09:45 was look-ahead).
     */
    fun firstDecision(r: Rules): Int =
        if (Filter.BUSY_ONLY in r.filters || Filter.QUIET_ONLY in r.filters) maxOf(r.fromMin, BUSY_KNOWN) else r.fromMin

    /** [r] followed on [d]: its virtual trades. Never throws on thin data (a day it cannot read is no trade). */
    fun run(r: Rules, d: Day): List<Trade> {
        if (r.invalid() != null || d.index.size < 30 || !allowed(r, d)) return emptyList()
        val f = d.fold(r.barMin)
        val out = ArrayList<Trade>(2)
        var busyUntil = -1
        var fired = false
        val ctx = Ctx(r, d, f)
        val first = firstDecision(r)
        for (k in 0 until f.size) {
            val closeMin = f.start[k] + r.barMin
            if (closeMin < first) continue
            if (closeMin >= r.untilMin) break
            if (out.size >= r.maxTrades) break
            if (closeMin <= busyUntil) continue
            val dir = ctx.signal(k, fired)
            if (dir == 0) continue
            fired = true
            val t = enter(r, d, dir, closeMin, f.c[k]) ?: continue
            out += t
            busyUntil = t.exitMin
        }
        return out
    }

    /** [r] on every day of [days], in order. */
    fun runAll(r: Rules, days: List<Day>): List<Trade> { val out = ArrayList<Trade>(); for (d in days) out += run(r, d); return out }

    /**
     * A trade entered at minute [atMin] (the decision bar's close; filled at that minute's open) leaning [dir], the index at
     * [index] then: the strike, its option's minutes, and the walk to the exit. Null when nothing can be priced.
     */
    fun enter(r: Rules, d: Day, dir: Int, atMin: Int, index: Double): Trade? {
        val step = d.strikeStep.toDouble()
        val atm = floor(index / step + 0.5) * step
        val strike = atm - dir * r.itm * step
        val leg = if (d.optionBars) d.leg(strike, dir) ?: d.leg(atm, dir) else null
        if (d.optionBars && leg == null) return null
        return if (leg != null) walkLeg(r, d, dir, atMin, leg) else walkApprox(r, d, dir, atMin)
    }

    private fun levels(r: Rules, entry: Double): Pair<Double, Double> {
        val stop = maxOf(r.stopPct?.let { entry * (1 - it) } ?: Double.NEGATIVE_INFINITY, r.stopPts?.let { entry - it } ?: Double.NEGATIVE_INFINITY)
        val target = minOf(r.targetPct?.let { entry * (1 + it) } ?: Double.POSITIVE_INFINITY, r.targetPts?.let { entry + it } ?: Double.POSITIVE_INFINITY)
        return stop to target
    }

    private fun walkLeg(r: Rules, d: Day, dir: Int, atMin: Int, leg: Leg): Trade? {
        val s = leg.s
        val j = s.atOrAfter(atMin)
        if (j >= s.size || s.minutes[j] > atMin + 3) return null
        val entry = s.o[j].takeIf { it > 0 } ?: s.c[j].takeIf { it > 0 } ?: return null
        val (stop, target) = levels(r, entry)
        val ix0 = d.index.c[maxOf(0, d.index.atOrBefore(atMin - 1))]
        var peak = entry
        for (i in j until s.size) {
            val m = s.minutes[i]
            if (m >= r.squareOff) return done(d, atMin, m, dir, entry, s.o[i], "TIME", false)
            if (stop > 0 && s.l[i] <= stop) return done(d, atMin, m, dir, entry, minOf(stop, s.o[i]), "STOP", false)
            if (s.h[i] >= target) return done(d, atMin, m, dir, entry, maxOf(target, s.o[i]), "TARGET", false)
            peak = maxOf(peak, s.h[i])
            val held = m - s.minutes[j] + 1
            r.noProgressMin?.let { t -> if (held == t && peak < entry * (1 + r.noProgressGain)) return done(d, atMin, m, dir, entry, s.c[i], "NO_PROGRESS", false) }
            r.timeStopMin?.let { t -> if (held >= t) return done(d, atMin, m, dir, entry, s.c[i], "TIME_STOP", false) }
            r.indexStopPct?.let { p ->
                val ii = d.index.atOrBefore(m)
                if (ii >= 0 && dir * (d.index.c[ii] - ix0) <= -p * ix0) return done(d, atMin, m, dir, entry, s.c[i], "INDEX_STOP", false)
            }
        }
        return done(d, atMin, s.minutes.last(), dir, entry, s.c.last(), "LAST", false)
    }

    /** The index-only approximation: the premium as [APPROX_PREMIUM] of the index, moved by half the index, less a day's decay. */
    private fun walkApprox(r: Rules, d: Day, dir: Int, atMin: Int): Trade? {
        val s = d.index
        val j = s.atOrAfter(atMin)
        if (j >= s.size || s.minutes[j] > atMin + 3) return null
        val ix0 = s.o[j]
        val p0 = ix0 * APPROX_PREMIUM
        fun prem(ix: Double, held: Int) = (p0 + APPROX_DELTA * dir * (ix - ix0) - p0 * APPROX_DECAY * held / 375.0).coerceAtLeast(0.05)
        val (stop, target) = levels(r, p0)
        var peak = p0
        for (i in j until s.size) {
            val m = s.minutes[i]
            val held = m - s.minutes[j] + 1
            val o = prem(s.o[i], held)
            val lo = prem(if (dir > 0) s.l[i] else s.h[i], held)
            val hi = prem(if (dir > 0) s.h[i] else s.l[i], held)
            val c = prem(s.c[i], held)
            if (m >= r.squareOff) return done(d, atMin, m, dir, p0, o, "TIME", true)
            if (stop > 0 && lo <= stop) return done(d, atMin, m, dir, p0, minOf(stop, o), "STOP", true)
            if (hi >= target) return done(d, atMin, m, dir, p0, maxOf(target, o), "TARGET", true)
            peak = maxOf(peak, hi)
            r.noProgressMin?.let { t -> if (held == t && peak < p0 * (1 + r.noProgressGain)) return done(d, atMin, m, dir, p0, c, "NO_PROGRESS", true) }
            r.timeStopMin?.let { t -> if (held >= t) return done(d, atMin, m, dir, p0, c, "TIME_STOP", true) }
            r.indexStopPct?.let { p -> if (dir * (s.c[i] - ix0) <= -p * ix0) return done(d, atMin, m, dir, p0, c, "INDEX_STOP", true) }
        }
        return done(d, atMin, s.minutes.last(), dir, p0, prem(s.c.last(), s.minutes.last() - s.minutes[j] + 1), "LAST", true)
    }

    private fun done(d: Day, atMin: Int, exitMin: Int, dir: Int, entry: Double, exit: Double, why: String, approx: Boolean) =
        Trade(d.date, atMin, exitMin, dir, entry, exit, net(d.underlying, d.lot, entry, exit), why, approx)

    // ---- the signals ------------------------------------------------------------------------------------------------------

    /** One day's signal reader for one rule set (the opening range and the levels worked out once). */
    private class Ctx(val r: Rules, val d: Day, val f: Folded) {
        val orh: Double; val orl: Double; val orReady: Int
        init {
            var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY; var last = -1
            val end = OPEN + r.orMin
            for (i in 0 until d.index.size) {
                val m = d.index.minutes[i]
                if (m >= end) break
                hi = maxOf(hi, d.index.h[i]); lo = minOf(lo, d.index.l[i]); last = m
            }
            orh = hi; orl = lo; orReady = if (last >= end - 2) end else Int.MAX_VALUE
        }

        /** +1 call, -1 put, 0 nothing on bar [k] (closed); [fired]: a signal already came today. */
        fun signal(k: Int, fired: Boolean): Int {
            val start = f.start[k]; val h = f.h[k]; val l = f.l[k]; val c = f.c[k]
            val prevC = if (k > 0) f.c[k - 1] else Double.NaN
            return when (r.entry) {
                Entry.ORB_BREAK -> {
                    if (start < orReady) return 0
                    val up = orh * (1 + r.buffer); val dn = orl * (1 - r.buffer)
                    val dir = if (c > up) 1 else if (c < dn) -1 else 0
                    if (dir != 0 && r.fresh && k > 0 && f.start[k - 1] >= orReady && (if (dir > 0) prevC > up else prevC < dn)) 0 else dir
                }
                Entry.ORB_SWEEP -> if (start < orReady) 0 else if (h > orh && c < orh) -1 else if (l < orl && c > orl) 1 else 0
                Entry.RANGE_FADE -> {
                    if (start < orReady) return 0
                    val w = orh - orl; if (w <= 0) return 0
                    val e = if (r.param > 0) r.param else 0.1
                    if (h >= orh - e * w && c < orh) -1 else if (l <= orl + e * w && c > orl) 1 else 0
                }
                Entry.VWAP_RECLAIM -> {
                    if (k == 0) return 0
                    val i = d.index.atOrBefore(start + r.barMin - 1); val ip = d.index.atOrBefore(start - 1)
                    if (i < 0 || ip < 0) return 0
                    val v = d.twap[i]; val vp = d.twap[ip]
                    if (prevC < vp && c > v * (1 + r.buffer)) 1 else if (prevC > vp && c < v * (1 - r.buffer)) -1 else 0
                }
                Entry.LEVEL_SWEEP -> {
                    val ph = d.prevHigh ?: return 0; val pl = d.prevLow ?: return 0
                    if (h > ph && c < ph) -1 else if (l < pl && c > pl) 1 else 0
                }
                Entry.PREV_DAY_BREAK -> {
                    val ph = d.prevHigh ?: return 0; val pl = d.prevLow ?: return 0
                    if (c > ph * (1 + r.buffer) && !(prevC > ph * (1 + r.buffer))) 1 else if (c < pl * (1 - r.buffer) && !(prevC < pl * (1 - r.buffer))) -1 else 0
                }
                Entry.GAP_FADE -> {
                    if (fired) return 0
                    val pc = d.prevClose ?: return 0
                    val gap = (d.open - pc) / pc
                    val th = if (r.param > 0) r.param else 0.003
                    if (abs(gap) < th) 0 else if (gap > 0 && c > pc) -1 else if (gap < 0 && c < pc) 1 else 0
                }
                Entry.TIME_OF_DAY -> {
                    if (fired) return 0
                    val move = (c - d.open) / d.open
                    val th = if (r.param > 0) r.param else 0.002
                    if (move >= th) 1 else if (move <= -th) -1 else 0
                }
                Entry.VIX_DIVERGENCE -> {
                    if (fired) return 0
                    val vx = d.vix ?: return 0
                    if (vx.size == 0) return 0
                    val vi = vx.atOrBefore(start + r.barMin - 1); if (vi < 0) return 0
                    val ri = (c - d.open) / d.open; val rv = (vx.c[vi] - vx.c[0]) / vx.c[0]
                    val a = if (r.param > 0) r.param else 0.002
                    if (ri > a && rv > 0.02) -1 else if (ri < -a && rv < -0.02) 1 else 0
                }
                Entry.OI_BUILDUP -> oiSignal(start + r.barMin - 1, c)
                Entry.SQUEEZE_BREAK -> {
                    val n = (if (r.param >= 2) r.param.toInt() else 6)
                    if (k < n + 2) return 0
                    var bh = Double.NEGATIVE_INFINITY; var bl = Double.POSITIVE_INFINITY
                    for (i in k - n until k) { bh = maxOf(bh, f.h[i]); bl = minOf(bl, f.l[i]) }
                    var avg = 0.0; for (i in 0 until k) avg += f.h[i] - f.l[i]; avg /= k
                    if (bh - bl > 2.0 * avg) 0 else if (c > bh && prevC <= bh) 1 else if (c < bl && prevC >= bl) -1 else 0
                }
            }
        }

        /** The near-money options' OI build-up at minute [m]: puts' OI change since the open less the calls', over their OI. */
        private fun oiSignal(m: Int, index: Double): Int {
            if (!d.optionBars) return 0
            val step = d.strikeStep.toDouble()
            val atm = floor(index / step + 0.5) * step
            var ce = 0.0; var pe = 0.0; var base = 0.0
            for (leg in d.legs) {
                if (abs(leg.strike - atm) > 2 * step + 1e-6) continue
                val oi = leg.s.oi ?: continue
                if (leg.s.size == 0) continue
                val i = leg.s.atOrBefore(m); if (i < 0) continue
                val ch = (oi[i] - oi[0]).toDouble()
                base += oi[0].toDouble()
                if (leg.right > 0) ce += ch else pe += ch
            }
            if (base <= 0) return 0
            val x = (pe - ce) / base
            val th = if (r.param > 0) r.param else 0.05
            return if (x > th) 1 else if (x < -th) -1 else 0
        }
    }

    // ---- the random twin ---------------------------------------------------------------------------------------------------

    /**
     * The random twin of [trades] (the candidate's own): on each of the same days the same number of entries at random decision
     * minutes inside the rules' window, a random side, the same exits. Seeded by [rng].
     */
    fun twin(r: Rules, days: Map<LocalDate, Day>, trades: List<Trade>, rng: DiscoveryStats.Rng): List<Trade> {
        val out = ArrayList<Trade>(trades.size)
        val perDay = trades.groupingBy { it.day }.eachCount()
        for ((date, n) in perDay) {
            val d = days[date] ?: continue
            val from = firstDecision(r)
            val span = (r.untilMin - from).coerceAtLeast(1)
            repeat(n) {
                val at = from + rng.nextInt(span)
                val dir = if (rng.nextInt(2) == 0) 1 else -1
                val ii = d.index.atOrBefore(at - 1)
                if (ii >= 0) enter(r, d, dir, at, d.index.c[ii])?.let { out += it }
            }
        }
        return out
    }
}
