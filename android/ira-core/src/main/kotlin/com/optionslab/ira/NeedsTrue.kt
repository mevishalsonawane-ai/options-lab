package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * "What would have to be true" for an open position of Boss's (reasoning, round 9, 2026-10-05): "for my 24500 put to
 * work, what needs to happen?", "what has to happen for my call to pay off?", "where is my breakeven?", "meri put ke
 * liye kya hona chahiye". Worked step by step from facts the app already has, for each open position (or the one named):
 *
 *  1. the condition at expiry - the breakeven from his own average price (the strike plus or minus the premium), the
 *     side it must be on, and for a seller where all of the premium is kept;
 *  2. the distance - from spot now to that breakeven, in points and in percent, the way it has to go;
 *  3. the time - the sessions left to expiry (today's rest counted before the close; holidays as the app knows them);
 *  4. the typical move - the average day's range, and the median move over that many sessions on the phone's own
 *     1-minute candles;
 *  5. how often a move like the one needed (or, where the condition holds now, the one that would undo it) happened in
 *     the past stretches of the same length on the phone - past stretches, never odds or a forecast;
 *  6. time decay - what each day costs a buyer or pays a seller.
 *
 * At-expiry arithmetic only, charges left out, said so. Facts, never advice: it ends "your call, Boss". Nothing here
 * places, changes or closes anything; it reads the account, so the app answers it on an unlocked phone only, like every
 * account section ([Section.NEED]). Pure.
 */
object NeedsTrue {
    /** Fewer past stretches than this and they are not counted, only said to be too few. */
    const val MIN_STRETCHES = 8
    /** A session on the phone counts when it holds this many 1-minute candles (a full day is 375). */
    private const val FULL = 300
    private val CLOSE: LocalTime = PositionHealth.CLOSE
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    private fun norm(text: String) = " " + text.lowercase().replace("p&l", "p l").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private val OWNER = Regex(" (my|mine|our|meri|mere|mera|hamari|hamare|apni|apne|apna) ")
    private val HOLDING = "(put|puts|call|calls|ce|pe|position|positions|trade|trades|option|options|short|long|straddle|strangle|spread|futures?|holding|holdings|strike|condor|iron condor|butterfly)"
    private val ASK = Regex(
        // "What needs to happen for my put to work", "what would have to be true for my 24500 put"
        " (what|wat|wht) (needs|need|has|have|would have|will have|must|should have|would need|will need|is needed|is required) (to )?(happen|be true|go right|go my way|occur)|" +
        // "For my 24500 put to work", "for my call to pay off / make money / be profitable / come good"
        " for (my|mine|our|the|this|that) ([a-z0-9 ]{0,30})?$HOLDING to (work|pay off|pay|make money|make a profit|profit|be profitable|come good|come right|win|be in profit|turn profitable|break even)|" +
        // "What does my put need", "what do my positions need"
        " what (does|do) (my|our|the|this|that) ([a-z0-9 ]{0,30})?$HOLDING need |" +
        // "Where is my breakeven", "how far is my breakeven", "breakeven of my put", "my breakevens"
        " (break ?even|break evens|breakevens|breakeven point|breakeven level)s? |" +
        // Hinglish: "meri put ke liye kya hona chahiye", "mere call ko profit ke liye kya chahiye"
        " (ke liye|ko|me|mein) kya (hona|hone|chahiye|hona chahiye|hone chahiye|zaroori|zaruri)|" +
        // Round 10: "does my put need Nifty to fall", "how much does Nifty need to fall for my put", "at what level does my
        // call make money", "where do I start losing on my put", "what Nifty level do I need for profit", "make the case for
        // holding my put", "meri put kaam karegi kya" - each what has to happen, worked from the facts (never a forecast).
        " (does|do) (my|our) ([a-z0-9 ]{0,30})?$HOLDING need |" +
        " how (much|far) (does|do|would|will) [a-z ]{0,20}(need|have) to (fall|drop|rise|go up|go down|go|move|climb|come down) (for|so) |" +
        " (at )?(what|which) ([a-z]+ )?(level|price|strike) (does|do|will|would) (my|our|i) ([a-z0-9 ]{0,30})?(make money|make a profit|profit|break even|breakeven|start losing|start making money|turn profitable|pay off|work) |" +
        " where (do|does|will|would) (i|my|our) ([a-z0-9 ]{0,30})?(start losing|start making money|break even|turn profitable) |" +
        " (what|which) ([a-z]+ )?(level|price) do (i|we) need (for|to) (profit|make money|break even|be in profit) |" +
        " (make the case|the case|pros and cons|arguments?) (for|of|on) (holding|keeping|staying in) |" +
        " $HOLDING (kaam|profit) (karegi|karega|karenge|degi|dega|denge) kya | $HOLDING (kab|kaise) (profit|paisa) (dega|degi|denge|banayega|banayegi) "
    )
    /** "At what level do I break even", "what level do I need for profit": Boss's own book named by "I" (round 10). */
    private val I_HOLD = Regex(" (do|will|would|does) i (need|break even|make money|start losing|start making money|turn profitable) | where do i (start losing|break even) |" +
        // "Breakeven kitna door hai" (routing round 11): Hinglish asks his own breakeven's distance without "my".
        "^ (jarvis |boss )?(mera |meri |mere )?(break ?even|breakevens?)( level| point)? ((kitna|kitni|kitne) (door|dur|duur|paas|pass|bacha|baaki|baki)|kahan|kaha)( hai| he| h)?( kya)?( boss| jarvis)? $")
    /** Not this question: a what-if, an order or a change, the P&L now, a ranking, a plain list or a word explained. */
    private val NOT = Regex(" (if|suppose|agar|close|exit|square|sell|buy|add|cancel|set|place|move it|modify|worst|best|rank|what is a|what is the meaning|meaning of|define|explain) ")

    /** "For my 24500 put to work, what needs to happen?", "where is my breakeven?", "meri put ke liye kya hona chahiye". */
    fun asked(text: String): Boolean = runCatching {
        listOf(text, Ask.reading(text)).any { s ->
            val t = norm(s)
            ASK.containsMatchIn(t) && (OWNER.containsMatchIn(t) || I_HOLD.containsMatchIn(t)) && !NOT.containsMatchIn(t) &&
                // The breakeven or a holding must be named: "what needs to happen for my day" is not this.
                (Regex(" $HOLDING ").containsMatchIn(t) || Regex(" (break ?even|breakevens?) ").containsMatchIn(t) || Regex(" \\d{3,6} ").containsMatchIn(t) || I_HOLD.containsMatchIn(t))
        }
    }.getOrDefault(false)

    /** Which of Boss's positions the question names: a strike, put or call, and an index; any null means any. */
    data class Pick(val strike: Double? = null, val right: String? = null, val underlying: String? = null)

    fun pick(text: String): Pick {
        val t = norm(text.replace(Regex("(\\d),(\\d)"), "$1$2"))
        val strike = Regex(" (\\d{3,6})(?: ?(ce|pe|call|calls|put|puts|strike))? ").findAll(t).map { it.groupValues[1].toDouble() }
            .firstOrNull { it >= 100 }
        val right = when {
            Regex(" (put|puts|pe) |\\d(pe) ").containsMatchIn(t) -> "PE"
            Regex(" (call|calls|ce) |\\d(ce) ").containsMatchIn(t) -> "CE"
            else -> null
        }
        val u = when {
            Regex(" (banknifty|bank nifty|bnf|nifty bank) ").containsMatchIn(t) -> "BANKNIFTY"
            Regex(" (finnifty|fin nifty|nifty fin) ").containsMatchIn(t) -> "FINNIFTY"
            Regex(" (midcpnifty|midcap nifty|midcp nifty) ").containsMatchIn(t) -> "MIDCPNIFTY"
            Regex(" (sensex) ").containsMatchIn(t) -> "SENSEX"
            Regex(" (nifty|nifty 50|nifty50) ").containsMatchIn(t) -> "NIFTY"
            else -> null
        }
        return Pick(strike, right, u)
    }

    private fun matches(p: PositionHealth.Pos, k: Pick): Boolean =
        (k.strike == null || p.strike != null && abs(p.strike - k.strike) < 0.01) &&
            (k.right == null || p.right == k.right) &&
            (k.underlying == null || p.underlying?.uppercase() == k.underlying)

    /** The sessions from [now] to [expiry]'s close: today's counted when it trades and the close has not passed. 0: expired. */
    fun sessionsLeft(now: LocalDateTime, expiry: LocalDate, isTradingDay: (LocalDate) -> Boolean): Int {
        var d = now.toLocalDate()
        if (!now.toLocalTime().isBefore(CLOSE)) d = d.plusDays(1)
        var n = 0; var guard = 0
        while (!d.isAfter(expiry) && guard++ < 800) { if (isTradingDay(d)) n++; d = d.plusDays(1) }
        return n
    }

    /**
     * The past stretches of [sessions] sessions on the phone, each as a fraction moved (0.01 = 1%): from the price at
     * [from] on a session (the last candle at or before it) to the close [sessions] - 1 sessions later; with [from] null
     * (asked after today's close), from the session's previous close to the close [sessions] later. Full sessions before
     * [today] only, overlapping, oldest first.
     */
    fun stretches(bars: List<Candle>, today: LocalDate, sessions: Int, from: LocalTime?): List<Double> {
        if (sessions < 1) return emptyList()
        val days = bars.filter { it.t.toLocalDate() < today }.groupBy { it.t.toLocalDate() }
            .filterValues { it.size >= FULL }.toSortedMap().values.map { s -> s.sortedBy { it.t } }
        val out = ArrayList<Double>()
        for (i in days.indices) {
            val start = if (from == null) days.getOrNull(i - 1)?.last()?.c else days[i].lastOrNull { !it.t.toLocalTime().isAfter(from) }?.c
            val endDay = if (from == null) i - 1 + sessions else i + sessions - 1
            val end = days.getOrNull(endDay)?.last()?.c
            if (start == null || end == null || start <= 0) continue
            out += end / start - 1
        }
        return out
    }

    private fun n0(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pc(x: Double) = "%.2f%%".format(Locale.ENGLISH, x * 100)
    private fun label(u: String?) = u?.let { name -> Market.entries.firstOrNull { it.name == name.uppercase() }?.label ?: name } ?: "the underlying"
    private fun sess(k: Int) = if (k == 1) "1 session" else "$k sessions"

    /** One position's chain of facts, or null when it cannot be worked out (said by the caller). */
    private fun one(p: PositionHealth.Pos, bars: List<Candle>, now: LocalDateTime, isTradingDay: (LocalDate) -> Boolean): List<String> {
        val side = if (p.qty > 0) "long" else "short"
        val head = "${p.where} ${p.symbol}, ${abs(p.qty)} $side at ${p2(p.avg)}"
        if (!p.option) {
            // Futures or shares: in profit while the price is on the right side of his average (charges left out).
            val need = if (p.qty > 0) "above" else "below"
            val gap = p.ltp - p.avg
            val now2 = if (gap == 0.0) "exactly there now" else "${p2(abs(gap))} ${if (gap > 0) "above" else "below"} it now, at ${p2(p.ltp)}"
            return listOf("$head: it is in profit while its price is $need ${p2(p.avg)}, your average - $now2 (charges left out).")
        }
        val k = p.strike!!
        val name = label(p.underlying)
        val call = p.right == "CE"
        val long = p.qty > 0
        val be = if (call) k + p.avg else k - p.avg
        // A long call and a short put need the underlying above the breakeven; a long put and a short call, below it.
        val above = call == long
        val out = ArrayList<String>()
        val paid = if (long) "paid" else "received"
        val when_ = p.expiry?.let { " at expiry (${it.format(DAY)})" } ?: " at expiry"
        val cond = "$head: for it to be in profit$when_, $name has to be ${if (above) "above" else "below"} ${n0(be)} - the ${n0(k)} strike " +
            "${if (call) "plus" else "less"} the ${p2(p.avg)} $paid" +
            (if (!long) "; all of the premium is kept ${if (call) "at or below" else "at or above"} ${n0(k)}" else "") + "."
        out += cond
        val spot = p.spot
        if (spot == null || spot <= 0) {
            out += "$name's price is not known just now, so not how far that is."
            return out
        }
        val gap = be - spot                       // + : the breakeven is above spot
        val needFrac = be / spot - 1
        val holds = if (above) spot > be else spot < be
        val sessions = p.expiry?.let { sessionsLeft(now, it, isTradingDay) }
        if (sessions == 0) {
            out += "It has expired (or the session of its expiry is over), so nothing more has to happen."
            return out
        }
        val span = sessions?.let { " in the ${sess(it)} left" + if (now.toLocalTime().isBefore(CLOSE) && isTradingDay(now.toLocalDate())) " (today's rest counted)" else "" } ?: ""
        val dir = if (gap > 0) "rise" else "fall"
        out += if (holds) "$name is at ${p2(spot)} now: ${n0(abs(gap))} points (${pc(abs(needFrac))}) on the right side already, so it needs $name not to " +
            "${if (above) "fall" else "rise"} more than that$span."
            else "$name is at ${p2(spot)} now: that is a $dir of ${n0(abs(gap))} points (${pc(abs(needFrac))}) needed$span."
        // The typical move: the average day's range, and the median move over that many sessions on the phone.
        val typical = ArrayList<String>()
        p.avgRange?.takeIf { it > 0 }?.let { r ->
            typical += "its average day's range is ${n0(r)} points (last ${p.rangeDays} sessions), so the distance is %.1f of a day's range".format(Locale.ENGLISH, abs(gap) / r)
        }
        val from = if (now.toLocalTime().isBefore(CLOSE) && isTradingDay(now.toLocalDate())) now.toLocalTime() else null
        val st = if (sessions != null) stretches(bars, now.toLocalDate(), sessions, from) else emptyList()
        if (st.size >= MIN_STRETCHES) {
            val med = st.map { abs(it) }.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
            typical += "its median move over ${sess(sessions!!)}${if (from != null) " from this time of day" else ""} on the phone is ${n0(med * spot)} points (${pc(med)}) either way"
        }
        if (typical.isNotEmpty()) out += "Typical: " + typical.joinToString("; ") + "."
        if (sessions != null) {
            if (st.size >= MIN_STRETCHES) {
                // A move like the one needed (or the one that would undo it) in the past stretches of the same length.
                val hit = st.count { if (above) it > needFrac else it < needFrac }
                out += if (holds) {
                    val undo = st.size - hit
                    "A ${if (above) "fall" else "rise"} of more than ${pc(abs(needFrac))} within ${sess(sessions)}, which would undo it: " +
                        "$undo of the last ${st.size} overlapping stretches on the phone (${pct(undo, st.size)})."
                } else "A $dir of ${pc(abs(needFrac))} or more within ${sess(sessions)}: $hit of the last ${st.size} overlapping stretches on the phone (${pct(hit, st.size)})."
            } else out += "Too few past stretches of ${sess(sessions)} on the phone (${st.size}) to say how often a move that size happened."
        }
        p.theta?.let { th ->
            val day = th * p.qty
            out += "Time: decay about ${AppFacts.rs(day)} a day on the position (${p2(abs(th))} a unit) - " +
                (if (long) "each day without the move costs a buyer about that." else "each quiet day pays a seller about that.")
        }
        return out
    }

    private fun pct(k: Int, of: Int) = if (of > 0) "%.0f%%".format(Locale.ENGLISH, k * 100.0 / of) else "-"

    /**
     * The answer for [text]: the named position (or each open one), each worked through. [bars] the 1-minute candles on
     * the phone by underlying (upper case); [now] IST; [isTradingDay] the app's calendar.
     */
    fun lines(text: String, ps: List<PositionHealth.Pos>, bars: Map<String, List<Candle>>, now: LocalDateTime,
              isTradingDay: (LocalDate) -> Boolean = { it.dayOfWeek.value <= 5 }): List<String> {
        if (ps.isEmpty()) return listOf("You have no open positions, Boss, so there is nothing that has to happen for one.")
        val k = pick(text)
        val chosen = ps.filter { matches(it, k) }
        if (chosen.isEmpty()) {
            val what = listOfNotNull(k.underlying?.let { label(it) }, k.strike?.let { n0(it) }, k.right?.let { if (it == "PE") "put" else "call" }).joinToString(" ")
            return listOf("None of your open positions is ${if (what.isBlank()) "that one" else "the $what"}, Boss. Open now: " +
                ps.joinToString(", ") { "${it.where} ${it.symbol}" } + ".")
        }
        val out = ArrayList<String>()
        out += if (chosen.size == 1) "What has to be true for your position, Boss, worked from the facts:"
            else "What has to be true for each of your ${chosen.size} open positions, Boss, worked from the facts:"
        chosen.sortedWith(compareBy<PositionHealth.Pos> { it.expiry ?: LocalDate.MAX }.thenBy { it.symbol })
            .forEach { out += one(it, bars[it.underlying?.uppercase()].orEmpty(), now, isTradingDay) }
        if (chosen.any { it.option }) out += "That is at expiry, from your average price, charges left out; before expiry an option's price also moves " +
            "with time and volatility, so it can be in profit or loss sooner. The stretches are past ones on the phone, not odds."
        out += "Facts and arithmetic, not a forecast or advice - your call, Boss."
        return out
    }
}
