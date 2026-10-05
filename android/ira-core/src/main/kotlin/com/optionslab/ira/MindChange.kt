package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * "What would change your mind?" (reasoning, round 11, 2026-10-05): for the read of an index's structure Jarvis last gave
 * ([Structure]), the specific levels on the phone that would make each part of it no longer true, and - since he said it -
 * which of those have already happened, at what minute. "What would change your mind?", "what would make you wrong?",
 * "what would invalidate that read?", "when would that stop being true?", "aapka view kab badlega", "kya hoga to ye galat
 * hoga".
 *
 * Each part of the read is tested by the same measure [Structure] uses to say it:
 *  - the swing shape (higher highs and higher lows, or lower highs and lower lows) on the candle size he named: a trade
 *    beyond the last swing low (high) makes a lower low (higher high); a next swing high (low) short of the last one would
 *    make a lower high (higher low) - that half has no level until it forms, and is checked against the shape now;
 *  - trend-like or range-like: the price at which the net move's share of the day's range, with the price's place in it,
 *    would read otherwise (with today's high and low as they stood; a new extreme moves it - the price beyond is worked out);
 *  - the side of the opening range and of the prior close: a 1-minute close back across it.
 *
 * Without a read of his today (or for an index other than the one he read), today's read as it stands is tested, and he
 * says so. Facts from the candles only - never a forecast that any of it will happen, never advice; nothing acts. Market
 * data, so fine on a locked phone. Pure.
 */
object MindChange {
    /** The read Jarvis gave: the index and the newest 1-minute candle it was read from. */
    data class Said(val market: Market, val lastBar: LocalDateTime)

    /** One part of the read, what would make it no longer true, and when that happened after the read (null: not so far). */
    data class Test(val claim: String, val condition: String, val brokenAt: LocalDateTime?, val since: String?)

    const val NOTE = "These are the lines on the phone's own candles, Boss - not a forecast that any of them will be crossed, and not advice."
    const val NOT_PRICE = "A headline, a view or a feeling alone wouldn't change this read: it is read from the candles, so only the price can."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace(rx("[^a-z0-9' ]"), " ")
        .replace("'", "").replace(rx("\\s+"), " ").trim() + " "
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    // Boss's own (his position, his mind - "what would change my mind" is his to say), a what-if, a forecast's words.
    private val NOT = rx(" (i|im|me|my|mine|we|our|position|positions|put|puts|trade|trades|strategy|bot|bots|order|buy|sell|tomorrow|predict|forecast|should) ")
    private val ASKED = rx(
        " (what|wat|whats|which|kya) (would|could|will|might|can|is going to|does it take to) (change|alter|flip|shift) (your|ur|the|that|this) (mind|view|read|call|reading|structure read|take) |" +
            " what (would|could|will|might) (make|prove|show) (you|u|that|this|it|your read|your view|the read|that read|this read) (to be )?(wrong|untrue|false|invalid|no longer true) |" +
            " what (would|could|will|might) (invalidate|negate|break|undo) (that|this|it|your read|your view|the read|that read|this read|your call) |" +
            " what (level|levels|price|prices) (would|could|will|might)? ?(invalidate|invalidates|negate|negates|break|breaks|change|changes) (that|this|it|your|the) (read|view|call|structure|mind) |" +
            // Routing round 11: "what would make you change your view", "what would make you turn bearish", "is your read still valid".
            " what (would|could|will|might) make (you|u) (change|rethink|reconsider|flip|drop|abandon) (your|ur|that|this|the) (mind|view|read|call|take) |" +
            " what (would|could|will|might) make (you|u) (turn |go |become |get )?(bearish|bullish) |" +
            " what (invalidates|negates|breaks|would invalidate|would break) (your|ur|that|this|the) (read|view|call|structure read) |" +
            " (is|does|do) (your|ur|that|this|the) (read|view|call|structure read) still (valid|hold|holds|holding|true|stand|stands|standing|good|intact) |" +
            " (when|where|at what (level|price)) (would|will|does|do) (that|this|it|your read|your view|the read|that read|this read) (stop being|no longer be|cease to be|stop) (true|holding|hold) |" +
            " (where|when|how) (would|will) (you|u) (be|know you are|know youre|know ur) wrong |" +
            " what would it take (for you )?to change (your|ur) (mind|view|read) |" +
            " (aapka|apka|tumhara|tera|aap ka|tumhra) (view|read|mind|vichar|soch) (kab|kaise|kis level par|kis level pe) (badlega|badalega|badle|badlegi|galat hoga) |" +
            " (kya|kab) (hoga|ho) (to|toh|tab) (ye|yeh|ya|aapka|apka|tumhara) (view |read )?(galat|badal) |" +
            " kab galat (hoga|sabit hoga|ho jayega) "
    )

    /** "What would change your mind?" and its kin - about Jarvis's own market read, never Boss's position or a forecast. */
    fun asked(text: String): Boolean {
        val t = norm(text)
        return ASKED.containsMatchIn(t) && !NOT.containsMatchIn(t)
    }

    /** What the hub keeps when Jarvis gives a structure read of [m] from [bars] on [today]; null when there was no read. */
    fun said(m: Market, bars: List<Candle>, today: LocalDate): Said? = Structure.read(m, bars, today)?.let { Said(m, it.at) }

    // ---- the measure of trend-like and range-like (Structure.Read.dayKind), on a price p with the day's open, high, low ----

    private fun kind(open: Double, hi: Double, lo: Double, p: Double): Int? {
        val h = maxOf(hi, p); val l = minOf(lo, p); val range = h - l
        if (range <= 0) return 0
        val share = kotlin.math.abs(p - open) / range; val place = (p - l) / range; val net = p - open
        return when {
            share >= Structure.TREND_SHARE && net > 0 && place >= 0.75 -> 1
            share >= Structure.TREND_SHARE && net < 0 && place <= 0.25 -> -1
            share <= Structure.RANGE_SHARE -> 0
            else -> null
        }
    }

    /** The lowest price that reads trend-like up with this open, high and low (a new high when the range is not enough). */
    internal fun trendUpAt(open: Double, hi: Double, lo: Double): Double {
        val r = hi - lo; val ts = Structure.TREND_SHARE
        val a = maxOf(open + ts * r, lo + 0.75 * r)
        return if (a <= hi) a else maxOf(hi, (open - ts * lo) / (1 - ts))
    }

    /** The highest price that reads trend-like down. */
    internal fun trendDownAt(open: Double, hi: Double, lo: Double): Double {
        val r = hi - lo; val ts = Structure.TREND_SHARE
        val b = minOf(open - ts * r, hi - 0.75 * r)
        return if (b >= lo) b else minOf(lo, (open - ts * hi) / (1 - ts))
    }

    /** The prices beyond which the day no longer reads range-like: (below, above). */
    internal fun rangeEdges(open: Double, hi: Double, lo: Double): Pair<Double, Double> {
        val r = hi - lo; val rs = Structure.RANGE_SHARE
        val up = if (open + rs * r <= hi) open + rs * r else (open - rs * lo) / (1 - rs)
        val down = if (open - rs * r >= lo) open - rs * r else (open - rs * hi) / (1 - rs)
        return down to up
    }

    private fun kindWord(k: Int?) = when (k) {
        1 -> "trend-like up"; -1 -> "trend-like down"; 0 -> "range-like"; else -> "in between (neither a clear trend nor a clear range)"
    }

    /** The parts of [then] (the read as given, from [day]'s 1-minute candles up to it) and what has happened since, from [day]. */
    fun tests(then: Structure.Read, day: List<Candle>, now: Structure.Read?): List<Test> {
        val after = day.filter { it.t.isAfter(then.at) }.sortedBy { it.t }
        val out = ArrayList<Test>()
        // (a) the swing shape, on the candle size the read named first (15-minute, else 5-minute).
        val shape = then.fifteen.takeIf { it.label != null } ?: then.five.takeIf { it.label != null }
        if (shape != null) {
            val h = shape.highWay; val l = shape.lowWay
            val lastHi = shape.highs.last(); val lastLo = shape.lows.last()
            val nowShape = now?.let { if (shape.minutes == 15) it.fifteen else it.five }
            val nowSaid = nowShape?.label?.takeIf { it != shape.label }?.let { " The ${shape.minutes}-minute shape now reads: $it." } ?: ""
            if (h != null && l != null && h > 0 && l > 0) {
                val hit = after.firstOrNull { it.l < lastLo.price }
                out += Test("${shape.label} on ${shape.minutes}-minute candles",
                    "a trade below the last swing low, ${n(lastLo.price)} (${hm(lastLo.at)}), would make a lower low; and the next swing high " +
                        "topping out under ${n(lastHi.price)} (${hm(lastHi.at)}) would make a lower high (that half has no level until it forms)",
                    hit?.t, hit?.let { "it traded at ${n(it.l)} at ${hm(it.t)}, below ${n(lastLo.price)}.$nowSaid" }
                        ?: ("not so far: the lowest since is " + (after.minOfOrNull { it.l }?.let { n(it) } ?: "no new candle yet") + ".$nowSaid"))
            } else if (h != null && l != null && h < 0 && l < 0) {
                val hit = after.firstOrNull { it.h > lastHi.price }
                out += Test("${shape.label} on ${shape.minutes}-minute candles",
                    "a trade above the last swing high, ${n(lastHi.price)} (${hm(lastHi.at)}), would make a higher high; and the next swing low " +
                        "holding above ${n(lastLo.price)} (${hm(lastLo.at)}) would make a higher low (that half has no level until it forms)",
                    hit?.t, hit?.let { "it traded at ${n(it.h)} at ${hm(it.t)}, above ${n(lastHi.price)}.$nowSaid" }
                        ?: ("not so far: the highest since is " + (after.maxOfOrNull { it.h }?.let { n(it) } ?: "no new candle yet") + ".$nowSaid"))
            } else {
                out += Test("${shape.label} on ${shape.minutes}-minute candles",
                    "the next swing high or low against ${n(lastHi.price)} (${hm(lastHi.at)}) and ${n(lastLo.price)} (${hm(lastLo.at)}) - a mixed shape has no single line",
                    null, if (nowSaid.isNotEmpty()) nowSaid.trim() else "no change in the shape so far.")
            }
        }
        // (b) trend-like or range-like, by the read's own measure, minute by minute since.
        val k = then.dayKind
        val hi = then.high.price; val lo = then.low.price; val o = then.open
        val condition = when (k) {
            1 -> "a price under ${n(trendUpAt(o, hi, lo))} would no longer read trend-like up (with the day's high ${n(hi)} and low ${n(lo)} as they stood; a new high raises it)"
            -1 -> "a price over ${n(trendDownAt(o, hi, lo))} would no longer read trend-like down (with the day's high ${n(hi)} and low ${n(lo)} as they stood; a new low lowers it)"
            0 -> rangeEdges(o, hi, lo).let { (d, u) -> "a price above ${n(u)} or below ${n(d)} would no longer read range-like (the net move from the open of ${n(o)} past ${(Structure.RANGE_SHARE * 100).toInt()}% of the day's range)" }
            else -> "it would read trend-like up at ${n(trendUpAt(o, hi, lo))} or higher, trend-like down at ${n(trendDownAt(o, hi, lo))} or lower" +
                rangeEdges(o, hi, lo).let { (d, u) -> ", and range-like between ${n(d)} and ${n(u)}" }
        }
        var rh = hi; var rl = lo
        var flip: Pair<Candle, Int?>? = null
        for (c in after) {
            rh = maxOf(rh, c.h); rl = minOf(rl, c.l)
            val kc = kind(o, rh, rl, c.c)
            if (kc != k) { flip = c to kc; break }
        }
        val nowKind = now?.dayKind
        out += Test("the day reading ${kindWord(k)} so far", condition, flip?.first?.t,
            flip?.let { (c, kc) -> "at ${hm(c.t)} (close ${n(c.c)}) it read ${kindWord(kc)}" +
                (if (now != null && nowKind != kc) "; it reads ${kindWord(nowKind)} now." else ".") }
                ?: "not so far.")
        // (c) the side of the opening range and of the prior close, by 1-minute closes.
        val orHi = then.orHigh; val orLo = then.orLow; val os = then.orSide
        if (orHi != null && orLo != null && os != null) {
            val (claim, cond, hit) = when (os.side) {
                1 -> Triple("above the opening range (${n(orLo)} to ${n(orHi)})", "a 1-minute close back at or under ${n(orHi)} would put it back inside",
                    after.firstOrNull { it.c <= orHi })
                -1 -> Triple("below the opening range (${n(orLo)} to ${n(orHi)})", "a 1-minute close back at or over ${n(orLo)} would put it back inside",
                    after.firstOrNull { it.c >= orLo })
                else -> Triple("inside the opening range (${n(orLo)} to ${n(orHi)})", "a 1-minute close above ${n(orHi)} or below ${n(orLo)} would take it out",
                    after.firstOrNull { it.c > orHi || it.c < orLo })
            }
            out += Test(claim, cond, hit?.t, hit?.let { "a 1-minute close of ${n(it.c)} at ${hm(it.t)}." } ?: "not so far.")
        }
        val pc = then.prevClose; val ps = then.prevSide
        if (pc != null && ps != null && ps.side != 0) {
            val hit = if (ps.side > 0) after.firstOrNull { it.c < pc } else after.firstOrNull { it.c > pc }
            out += Test("${if (ps.side > 0) "above" else "below"} the prior close of ${n(pc)}",
                "a 1-minute close ${if (ps.side > 0) "under" else "over"} ${n(pc)} would put it ${if (ps.side > 0) "below" else "above"}",
                hit?.t, hit?.let { "a 1-minute close of ${n(it.c)} at ${hm(it.t)}." } ?: "not so far.")
        }
        return out
    }

    /**
     * The answer for [asked] (the index named, or null) from [bars] on [today], with [said] the read Jarvis last gave (null
     * when none is kept, or one from another day: then today's read as it stands is tested, and he says so).
     */
    fun answer(asked: Market?, said: Said?, bars: Map<Market, List<Candle>>, today: LocalDate): String {
        val ours = said?.takeIf { it.lastBar.toLocalDate() == today && (asked == null || asked == it.market) }
        val m = ours?.market ?: asked ?: Market.NIFTY
        if (m.open == null || m == Market.VIX) return Structure.NOT_HERE
        val all = bars[m].orEmpty()
        val day = all.filter { it.t.toLocalDate() == today }.sortedBy { it.t }
        val now = Structure.read(m, all, today)
        val then = if (ours != null) Structure.read(m, all.filter { !it.t.isAfter(ours.lastBar) }, today) else now
        if (then == null) return "I have no read of ${m.label}'s structure today to test, Boss: " +
            (if (day.isEmpty()) "there are no ${m.label} candles for today on the phone yet." else "only ${day.size} minutes of candles so far, too few (I need ${Structure.MIN_BARS}).")
        val tests = tests(then, day, now)
        val asOf = hm(then.at.plusMinutes(1))
        val head = if (ours != null) "What would change my read of ${m.label}'s structure as of $asOf, Boss - each part, the line that would make it no longer true, and whether that has happened since."
            else "I haven't given you a read of ${m.label}'s structure today, Boss, so here is today's as it stands at $asOf - each part and the line that would make it no longer true."
        val broken = tests.filter { it.brokenAt != null }
        val summary = when {
            ours == null -> null
            broken.isEmpty() -> "Since I said it, none of these lines has been crossed."
            else -> "Since I said it, ${broken.size} of ${tests.size} ${if (broken.size == 1) "has" else "have"} already changed: " +
                broken.sortedBy { it.brokenAt }.joinToString("; ") { "${it.claim}, at ${hm(it.brokenAt!!)}" } +
                " - so ${if (broken.size == 1) "that part" else "those parts"} of what I said no longer ${if (broken.size == 1) "holds" else "hold"}."
        }
        val lines = tests.joinToString(" ") { t ->
            "${t.claim.replaceFirstChar { it.uppercase() }}: ${t.condition}." + (if (ours != null) " Since then: ${t.since}" else "")
        }
        return listOfNotNull(head, summary, lines, NOT_PRICE, NOTE).joinToString(" ")
    }
}
