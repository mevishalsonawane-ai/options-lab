package com.optionslab.ira

import com.optionslab.engine.options.OptionMath
import com.optionslab.engine.options.OptionType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
 *  6. time decay - what each day costs a buyer or pays a seller;
 *  7. (reasoning, round 25) today's move in the underlying beside its own intraday comeback record and its average
 *     day's range, said for each option on it - the way it needs or against it, and how often such a move held or came back;
 *  8. (reasoning, round 30) for a bought option in a live session, the index move that just covers the rest of today's
 *     decay at its delta now, and how often and how soon the index went that far from this time of day on the phone;
 *  9. (reasoning, round 31) for a bought option, the round trip's charges on Zerodha's F&O schedule ([PnlCharges.perFill]):
 *     the price it has to be sold at just to cover them, the premium points and (at its delta now) the index points that
 *     is, where it is against that now, and the at-expiry breakeven with them added.
 *
 * At-expiry arithmetic, charges left out of it and said so, apart from the charges line (an estimate). Facts, never advice: it ends "your call, Boss". Nothing here
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

    private fun norm(text: String) = " " + spacedWords(text.lowercase().replace("p&l", "p l")) + " "

    private val OWNER = Regex(" (my|mine|our|meri|mere|mera|hamari|hamare|apni|apne|apna) ")
    private val HOLDING = "(put|puts|call|calls|ce|pe|position|positions|trade|trades|option|options|short|long|straddle|strangle|spread|futures?|holding|holdings|strike|condor|iron condor|butterfly)"
    /** A holding named ([HOLDING] as a word), compiled once. */
    private val HOLDING_RX = Regex(" $HOLDING ")
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
        " $HOLDING (kaam|profit) (karegi|karega|karenge|degi|dega|denge) kya | $HOLDING (kab|kaise) (profit|paisa) (dega|degi|denge|banayega|banayegi) |" +
        // Round 22: "when does my put start making money", "at what Nifty level am I in profit", "how much does Nifty have
        // to move for me to break even" (and [NEED_FOR]).
        " when (does|do|will|would) (my|our) ([a-z0-9 ]{0,30})?$HOLDING (start making money|start losing|make money|turn profitable|break even|pay off|work|come good) |" +
        " (at )?(what|which) ([a-z]+ )?(level|price) (am i|are we|will i be|would i be|do i get) (in profit|profitable|in the green|at breakeven|at break even|making money) |" +
        " for (me|us) to (break even|get to breakeven|be at breakeven) "
    )
    /**
     * Round 22: "what does Nifty need to do for my put", "where does Nifty need to be for my put" - only with a real holding
     * named (a holding, a strike or the breakeven): "what do I need to do for my strategy to go live" and "where do I need to
     * be for my meeting" are not this (round 22 review).
     */
    private val NEED_FOR = Regex(" (what|where) (does|do|would|will) [a-z0-9 ]{0,20}(need|have) to (do|be|go|reach|get to|close|close at|hit) (for|so) (my|our) ")
    /** "At what level do I break even", "what level do I need for profit": Boss's own book named by "I" (round 10). */
    private val I_HOLD = Regex(" (do|will|would|does) i (need|break even|make money|start losing|start making money|turn profitable) | where do i (start losing|break even) |" +
        // Round 22: "at what Nifty level am I in profit", "for me to break even".
        " (level|price) (am i|will i be|would i be) (in profit|profitable|in the green|at breakeven|at break even|making money) | for (me|us) to (break even|get to breakeven|be at breakeven) |" +
        // "Breakeven kitna door hai" (routing round 11): Hinglish asks his own breakeven's distance without "my".
        "^ (jarvis |boss )?(mera |meri |mere )?(break ?even|breakevens?)( level| point)? ((kitna|kitni|kitne) (door|dur|duur|paas|pass|bacha|baaki|baki)|kahan|kaha)( hai| he| h)?( kya)?( boss| jarvis)? $|" +
        // Understanding round 28: the breakeven with the round trip's charges in it, said without "my" ("breakeven after
        // charges", "charges ke baad breakeven kya hai", "real breakeven") - only his own positions have one to work out.
        "$AFTER_CHARGES")
    /**
     * The breakeven after charges (round 28): "breakeven after / including / with charges", "charges ke baad (ka) breakeven",
     * "breakeven charges ke baad", "real / net breakeven", "at what price do I cover my charges".
     */
    private const val AFTER_CHARGES = " (break ?even|breakevens?)( point| level| price)? (after|including|incl|with|plus|net of|inclusive of|covering|counting) (the |all |my |all the )?(charges|brokerage|costs|fees|taxes) |" +
        " (charges|brokerage|costs) (ke baad|ke bad|ke saath|ke sath|milake|mila ke|jod ke|jodke|joda ke|include karke|lagake|laga ke) (ka |ki |wala |wali |mera |meri )?(break ?even|breakevens?) |" +
        " (break ?even|breakevens?) (charges|brokerage) (ke baad|ke bad|ke saath|ke sath|milake|mila ke|jod ke|jodke|include karke|lagake|laga ke) |" +
        "^ (jarvis |boss )?(whats |what is |what s )?(the |my )?(real|true|net|actual|asli|sahi) (break ?even|breakevens?)( point| level| price)?( kya| kya hai| hai| please| boss| jarvis)* $|" +
        " (what|which|at what) (price|level) (do|does|will|would|should) (i|my [a-z0-9 ]{1,30}) (need to |have to |go to |reach to )?(cover|recover|pay for|pay off) (my |the |its |all the |all my )?(charges|brokerage|costs) "
    private val AFTER_RX = Regex(AFTER_CHARGES)
    /** The breakeven after charges explained, not worked out (round 28). */
    private val DEFINE = Regex(" (mean|means|meaning|matlab|definition|defined|concept|formula|how is|how do you calculate|how to calculate|kaise nikale|kaise nikalte|kya hota hai) ")
    /** Not this question: a what-if, an order or a change, the P&L now, a ranking, a plain list or a word explained. */
    private val NOT = Regex(" (if|suppose|agar|close|exit|square|sell|buy|add|cancel|set|place|move it|modify|worst|best|rank|what is a|what is the meaning|meaning of|define|explain) ")

    /** "For my 24500 put to work, what needs to happen?", "where is my breakeven?", "meri put ke liye kya hona chahiye". */
    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean = runCatching {
        listOf(text, Ask.reading(text)).any { s ->
            val t = norm(s)
            // (The owner first: the cheap check before the long one - the same answer, speed round 10.)
            if (!(OWNER.containsMatchIn(t) || I_HOLD.containsMatchIn(t)) || NOT.containsMatchIn(t)) return@any false
            // The breakeven after charges (round 28): his own positions' charges line, never the word explained.
            if (AFTER_RX.containsMatchIn(t)) return@any !DEFINE.containsMatchIn(t)
            // The breakeven or a holding must be named: "what needs to happen for my day" is not this.
            val held = HOLDING_RX.containsMatchIn(t) || rx(" (break ?even|breakevens?) ").containsMatchIn(t) || rx(" \\d{3,6} ").containsMatchIn(t)
            // "I" alone stands for his book only with the asks made for it; "what do I need to do for my ..." needs the holding itself.
            ASK.containsMatchIn(t) && (held || I_HOLD.containsMatchIn(t)) || NEED_FOR.containsMatchIn(t) && held
        }
    }.getOrDefault(false)

    /** Which of Boss's positions the question names: a strike, put or call, and an index; any null means any. */
    data class Pick(val strike: Double? = null, val right: String? = null, val underlying: String? = null)

    fun pick(text: String): Pick {
        val t = norm(text.replace(rx("(\\d),(\\d)"), "$1$2"))
        val strike = rx(" (\\d{3,6})(?: ?(ce|pe|call|calls|put|puts|strike))? ").findAll(t).map { it.groupValues[1].toDouble() }
            .firstOrNull { it >= 100 }
        val right = when {
            rx(" (put|puts|pe) |\\d(pe) ").containsMatchIn(t) -> "PE"
            rx(" (call|calls|ce) |\\d(ce) ").containsMatchIn(t) -> "CE"
            else -> null
        }
        val u = when {
            rx(" (banknifty|bank nifty|bnf|nifty bank) ").containsMatchIn(t) -> "BANKNIFTY"
            rx(" (finnifty|fin nifty|nifty fin) ").containsMatchIn(t) -> "FINNIFTY"
            rx(" (midcpnifty|midcap nifty|midcp nifty) ").containsMatchIn(t) -> "MIDCPNIFTY"
            rx(" (sensex) ").containsMatchIn(t) -> "SENSEX"
            rx(" (nifty|nifty 50|nifty50) ").containsMatchIn(t) -> "NIFTY"
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
            chargesCover(p, now)?.let { out += it }
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
        // Reasoning, round 30: for a bought option in a live session, the move that covers the rest of today's decay, and how
        // often and how soon the index went that far from this time of day on the phone.
        thetaCover(p, bars, now, isTradingDay)?.let { out += it }
        // Reasoning, round 31: the premium (and index) move that just covers the round trip's charges.
        chargesCover(p, now)?.let { out += it }
        return out
    }

    /** The charges of buying [qty] units of option [symbol] at [buy] and selling them at [sell], one order each way. */
    fun roundTrip(symbol: String, exchange: String, qty: Int, buy: Double, sell: Double): Double =
        PnlCharges.perFill(listOf(PnlCharges.Fill("BUY", buy, qty, "buy", symbol, exchange), PnlCharges.Fill("SELL", sell, qty, "sell", symbol, exchange)))
            .sumOf { it.values.sum() }

    /**
     * The price [qty] units bought at [avg] have to be sold at for the sale to just cover the round trip's charges
     * ([roundTrip]: the sale's own charges grow with its price, so it is worked to a fixed point), and those charges; null for
     * no quantity or price. Pure.
     */
    fun exitToCover(symbol: String, exchange: String, qty: Int, avg: Double): Pair<Double, Double>? {
        if (qty <= 0 || !(avg > 0)) return null
        var sell = avg
        var c = 0.0
        repeat(12) { c = roundTrip(symbol, exchange, qty, avg, sell); sell = avg + c / qty }
        return (sell to c).takeIf { sell.isFinite() && c.isFinite() }
    }

    /**
     * Reasoning, round 31 (2026-10-05): for a bought option [p], the round trip's charges (Zerodha's F&O schedule, the one the
     * paper account pays too; one buy order and one sell order of the whole quantity - an estimate) and the price it has to be
     * sold at just to cover them ([exitToCover]): the premium points above his average, at the option's delta now
     * ([deltaNow], held still) the index points that is, where its price is against it now, and the at-expiry breakeven with
     * the charges added (the exercise's own charges aside). Arithmetic on his own record, never a forecast or advice; nothing
     * acts. Null when it does not apply (sold, not an option, no quantity or price). Pure.
     */
    fun chargesCover(p: PositionHealth.Pos, now: LocalDateTime): String? {
        if (!p.option || p.qty <= 0) return null
        val u = p.underlying?.uppercase()
        val exchange = if (u == "SENSEX" || u == "BANKEX") "BFO" else "NFO"
        val (sell, c) = exitToCover(p.symbol, exchange, p.qty, p.avg) ?: return null
        if (!(c >= 0.005)) return null
        val points = sell - p.avg
        val name = label(p.underlying)
        val call = p.right == "CE"
        val index = deltaNow(p, now)?.takeIf { abs(it) >= MIN_DELTA }?.let { d ->
            "; at its delta of ${"%.2f".format(Locale.ENGLISH, abs(d))} now, that is about ${p2(points / abs(d))} $name points ${if (call) "up" else "down"}"
        } ?: ""
        val gap = p.ltp - sell
        val against = if (p.ltp > 0) " It is at ${p2(p.ltp)} now, " + when {
            abs(gap) < 0.005 -> "right on that."
            gap > 0 -> "${p2(gap)} above that."
            else -> "${p2(-gap)} below that."
        } else ""
        val k = p.strike!!
        val be = if (call) k + p.avg else k - p.avg
        val beC = if (call) k + sell else k - sell
        return "Charges: a round trip of ${p.qty} at your ${p2(p.avg)} comes to about ${rs0(c)} on Zerodha's F&O schedule (one order each way, " +
            "an estimate), so it has to sell at about ${p2(sell)} just to cover them - ${p2(points)} above your average$index.$against " +
            "Held to expiry, the breakeven with them is about ${p2(beC)} rather than ${p2(be)} (the exercise's own charges aside)."
    }

    /** The session's open; its length in minutes (09:15 to 15:30). */
    private val OPEN: LocalTime = LocalTime.of(9, 15)
    const val SESSION_MINUTES = 375
    /** Below this delta (either sign) the index's move hardly reaches the option's price: no cover is worked out. */
    const val MIN_DELTA = 0.02
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /**
     * The delta of option [p] now, worked from its price, spot and expiry ([OptionMath.legGreeks], spot as the forward, as
     * the app works its greeks), or null when it cannot be (no spot or expiry, or no time value to read an IV from). Pure.
     */
    fun deltaNow(p: PositionHealth.Pos, now: LocalDateTime): Double? {
        if (!p.option) return null
        val s = p.spot?.takeIf { it > 0 } ?: return null
        val e = p.expiry ?: return null
        val t = OptionMath.timeToExpiryYears(now.atZone(IST), e)
        val g = runCatching { OptionMath.legGreeks(if (p.right == "CE") OptionType.CE else OptionType.PE, s, p.strike!!, t, p.ltp) }.getOrNull() ?: return null
        return g.greeks.delta.takeIf { !g.theoretical && it.isFinite() }
    }

    /**
     * The decay per unit still to come in today's session for a bought option: on its expiry day, the [timeValue] left in it
     * (all gone by 15:30); otherwise one day's [theta] (per calendar day, as the app works it) spread evenly over the
     * session's [SESSION_MINUTES] minutes, times the [minutesLeft]. Null when there is nothing to cover. Pure.
     */
    fun decayLeftToday(theta: Double?, minutesLeft: Int, expiresToday: Boolean, timeValue: Double?): Double? {
        if (minutesLeft <= 0) return null
        val d = if (expiresToday) timeValue else theta?.let { abs(it) * minOf(minutesLeft, SESSION_MINUTES) / SESSION_MINUTES }
        return d?.takeIf { it.isFinite() && it > 0.005 }
    }

    /**
     * The phone's record of a move of a fraction (0.004 = 0.4%) from one time of day to the close: over [days] whole
     * sessions, how many [reached] that far the way asked at some minute before the close (by the candles' highs or lows),
     * the median minutes it took on those days ([medianMinutes]), and how many [held] that far at the close.
     */
    data class Cover(val days: Int, val reached: Int, val held: Int, val medianMinutes: Int?)

    /**
     * [Cover] from 1-minute [bars]: on each whole session before [today], from the price at [from] (the last candle at or
     * before it; before the session's first candle, its opening price) to the close, a move of [frac] up ([up]) or down. Pure.
     */
    fun coverRecord(bars: List<Candle>, today: LocalDate, from: LocalTime, frac: Double, up: Boolean): Cover {
        val days = bars.filter { it.t.toLocalDate() < today }.groupBy { it.t.toLocalDate() }
            .filterValues { it.size >= FULL }.values.map { s -> s.sortedBy { it.t } }
        var n = 0; var reached = 0; var held = 0
        val minutes = ArrayList<Int>()
        for (d in days) {
            val atOrBefore = d.lastOrNull { !it.t.toLocalTime().isAfter(from) }
            val start = atOrBefore?.c ?: d.first().o
            if (!(start > 0)) continue
            // From the open, the first candle counts (its high or low is a move from the opening price).
            val rest = if (atOrBefore == null) d else d.filter { it.t.toLocalTime().isAfter(from) }
            if (rest.isEmpty()) continue
            n++
            val goal = if (up) start * (1 + frac) else start * (1 - frac)
            val hit = rest.firstOrNull { if (up) it.h >= goal else it.l <= goal }
            if (hit != null) {
                reached++
                val began = atOrBefore?.t ?: d.first().t.minusMinutes(1)
                minutes += maxOf(1, ChronoUnit.MINUTES.between(began, hit.t).toInt())
            }
            if (if (up) rest.last().c >= goal else rest.last().c <= goal) held++
        }
        val med = minutes.sorted().takeIf { it.isNotEmpty() }?.let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2] + 1) / 2 }
        return Cover(n, reached, held, med)
    }

    private fun mins(m: Int) = if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60} min"
    private fun rs0(x: Double) = "Rs " + n0(abs(x))

    /**
     * Reasoning, round 30 (2026-10-05): for a bought option [p] in a live session on a trading day, the decay still to come
     * today ([decayLeftToday]), the index move that just makes it back at the option's delta now ([deltaNow]) - points,
     * percent and which way - and the phone's record ([coverRecord]) of how often, and how soon, the index went that far from
     * this time of day before the close, and how often it was still that far at the close. Delta held still (said so). Past
     * sessions, never odds, a forecast or advice; nothing acts. Null when it does not apply (sold, not an option, no spot,
     * theta or delta, the session over, not a trading day). Pure.
     */
    fun thetaCover(p: PositionHealth.Pos, bars: List<Candle>, now: LocalDateTime, isTradingDay: (LocalDate) -> Boolean): String? {
        if (!p.option || p.qty <= 0) return null
        val today = now.toLocalDate()
        val e = p.expiry ?: return null
        if (e.isBefore(today) || !isTradingDay(today) || !now.toLocalTime().isBefore(CLOSE)) return null
        val spot = p.spot?.takeIf { it > 0 } ?: return null
        val preOpen = now.toLocalTime().isBefore(OPEN)
        val from = if (preOpen) OPEN else now.toLocalTime()
        val left = ChronoUnit.MINUTES.between(from, CLOSE).toInt()
        val itm = p.itmBy ?: return null
        val expiresToday = e == today
        val unit = decayLeftToday(p.theta, left, expiresToday, maxOf(0.0, p.ltp - maxOf(0.0, itm))) ?: return null
        val delta = deltaNow(p, now)?.takeIf { abs(it) >= MIN_DELTA } ?: return null
        val name = label(p.underlying)
        val call = p.right == "CE"
        val points = unit / abs(delta)
        val frac = points / spot
        val way = if (call) "up" else "down"
        val onPos = unit * p.qty
        val whatLeft = if (expiresToday) "the ${rs0(onPos)} of time value left on the position is gone by 15:30"
            else "about ${rs0(onPos)} of decay is still to come on the position today (one day's theta spread over the session, ${mins(left)} left)"
        val head = "Covering today's decay: $whatLeft. At its delta of ${"%.2f".format(Locale.ENGLISH, abs(delta))} now, $name has to move about " +
            "${n0(points)} points (${pc(frac)}) $way, your way, just to make that back."
        val r = coverRecord(bars, today, if (preOpen) LocalTime.of(9, 14) else from, frac, call)
        if (r.days < MIN_STRETCHES) return "$head Too few whole sessions of $name on the phone (${r.days}) to say how often it moved that far from this time of day."
        val since = if (preOpen) "from the open" else "from %02d:%02d".format(from.hour, from.minute)
        val soon = r.medianMinutes?.let { ", at a median of ${mins(it)} in" } ?: ""
        return "$head On the record $since, it went that far $way before the close on ${r.reached} of the last ${r.days} whole sessions on the phone " +
            "(${pct(r.reached, r.days)})$soon, and was still that far $way at the close on ${r.held} (${pct(r.held, r.days)}). " +
            "Delta held still - gamma, IV and the clock move it."
    }

    /** The smallest move from the previous close set beside the comeback record (in %). */
    const val MIN_TODAY_PCT = 0.5
    /** The largest size the record is counted at (in %): a bigger move is counted at this. */
    const val MAX_TODAY_PCT = 3.0

    /**
     * Reasoning, round 25 (2026-10-05): today's move in [u] (an index, upper case) so far, joined with two facts the app
     * already has - its own intraday comeback record on the phone ([Comebacks.past] and [Comebacks.side], counted at the
     * move now rounded down to the half per cent, [MIN_TODAY_PCT] to [MAX_TODAY_PCT]) and today's range against its
     * average day's range - then said for each of Boss's options on it ([held]): whether today's move is the way it needs
     * or against it, and how often such a move held or came back on the record. Past days, never odds, a forecast or
     * advice; nothing acts. Empty for an underlying other than Nifty, BankNifty, FinNifty or Sensex, or when the phone has no
     * session of today with a whole one before it at most [Comebacks.MAX_DAYS_APART] days earlier and the trading day just
     * before today by [isTradingDay] (a missing session is never bridged). Pure.
     */
    fun todayBeside(u: String, bars: List<Candle>, now: LocalDateTime, held: List<PositionHealth.Pos>,
                    isTradingDay: (LocalDate) -> Boolean = Comebacks.WEEKDAYS): List<String> {
        // Only the indices the comeback record is kept for (never gold, which has no previous close to count from).
        if (Comebacks.INDICES.none { it.name == u }) return emptyList()
        val today = now.toLocalDate()
        val ss = MarketStory.sessions(bars)
        val t = ss.lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() } ?: return emptyList()
        // The previous close counts as Comebacks counts it: a whole session, at most MAX_DAYS_APART days before today.
        val before = ss.lastOrNull { it.day.isBefore(today) }?.takeIf {
            Comebacks.whole(it) && it.close > 0 && ChronoUnit.DAYS.between(it.day, today) <= Comebacks.MAX_DAYS_APART &&
                Comebacks.follows(it.day, today, isTradingDay)
        } ?: return emptyList()
        val name = label(u)
        val pc = before.close
        val live = now.toLocalTime().isBefore(CLOSE)
        val nowPct = (t.close - pc) / pc * 100
        val lowPct = (t.bars.minOf { it.l } - pc) / pc * 100
        val highPct = (t.bars.maxOf { it.h } - pc) / pc * 100
        val out = ArrayList<String>()
        val rangeNote = held.firstNotNullOfOrNull { p -> p.avgRange?.takeIf { it > 0 }?.let { it to p.rangeDays } }?.let { (r, n) ->
            "; its range ${if (live) "so far" else "today"} is ${n0(t.range)} points, %.1f of its average day's range (${n0(r)}, last $n sessions)"
                .format(Locale.ENGLISH, t.range / r)
        } ?: ""
        out += "Beside today: $name ${if (live) "is" else "ended"} at ${p2(t.close)}, ${sp(nowPct)} on the previous close of ${p2(pc)} " +
            "(today's low ${sp(lowPct)}, high ${sp(highPct)})$rangeNote."
        val size = minOf(MAX_TODAY_PCT, Math.floor(abs(nowPct) * 2) / 2)
        if (size < MIN_TODAY_PCT) {
            out += "That is under ${p1(MIN_TODAY_PCT)} from the previous close, too small a move to set beside its comeback record."
            return out
        }
        val fall = nowPct < 0
        val days = Comebacks.past(bars, today, size, isTradingDay)
        if (days.size < Comebacks.MIN_SESSIONS) {
            out += "Too few whole sessions of $name on the phone (${days.size}) to set today's move beside its comeback record (I need ${Comebacks.MIN_SESSIONS})."
            return out
        }
        val s = Comebacks.side(days, if (fall) -1 else 1, size)
        val moved = if (fall) "fell ${p1(size)} or more below" else "rose ${p1(size)} or more above"
        if (s.touched < Comebacks.MIN_TOUCHES) {
            out += "Over the last ${days.size} whole sessions on the phone, $name $moved the previous close in the day on only ${s.touched} - " +
                "too few to say how such days ended (I need ${Comebacks.MIN_TOUCHES})."
            return out
        }
        out += "Its comeback record: over the last ${days.size} whole sessions on the phone, $name $moved the previous close in the day on ${s.touched}; " +
            "${s.back} of them ended back ${if (fall) "above" else "below"} it (${pct(s.back, s.touched)}), " +
            "${s.half} ${if (fall) "won back at least half of the day's fall" else "gave back at least half of the day's rise"} (${pct(s.half, s.touched)}) " +
            "and ${s.held} ended still ${p1(size)} or more ${if (fall) "down" else "up"} (${pct(s.held, s.touched)})." +
            (if (s.touched < Comebacks.FEW_TOUCHES) " Only ${s.touched} days, so a few days move these figures a lot." else "")
        for (p in held.sortedBy { it.symbol }) {
            val k = p.strike ?: continue
            val call = p.right == "CE"
            val above = call == (p.qty > 0)
            val be = if (call) k + p.avg else k - p.avg
            // Today's move is the position's way when it goes towards the side its breakeven needs.
            val itsWay = fall != above
            val move = if (fall) "fall" else "rise"
            val needs = "Your ${p.symbol} needs $name ${if (above) "above" else "below"} ${n0(be)} at expiry"
            out += if (itsWay) "$needs: today's $move is its way, and on the record such a move held to the close on ${s.held} of ${s.touched} days " +
                "(${pct(s.held, s.touched)}) and came back across the previous close on ${s.back} (${pct(s.back, s.touched)})."
            else "$needs: today's $move is against it; on the record such a move came back across the previous close on ${s.back} of ${s.touched} days " +
                "(${pct(s.back, s.touched)}) and held to the close on ${s.held} (${pct(s.held, s.touched)})."
        }
        return out
    }

    private fun sp(x: Double) = (if (x > 0) "+" else "") + "%.2f%%".format(Locale.ENGLISH, x)
    private fun p1(x: Double) = "%.1f%%".format(Locale.ENGLISH, x).replace(".0%", "%")

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
        // Reasoning, round 25: today's move in each underlying beside its own comeback record and its usual day's range.
        chosen.filter { it.option && it.underlying != null }.groupBy { it.underlying!!.uppercase() }.toSortedMap()
            .forEach { (u, held) -> out += todayBeside(u, bars[u].orEmpty(), now, held, isTradingDay) }
        if (chosen.any { it.option }) out += "That is at expiry, from your average price, charges left out${if (chosen.any { it.option && it.qty > 0 }) " but for the charges line" else ""}; before expiry an option's price also moves " +
            "with time and volatility, so it can be in profit or loss sooner. The stretches are past ones on the phone, not odds."
        out += "Facts and arithmetic, not a forecast or advice - your call, Boss."
        return out
    }
}
