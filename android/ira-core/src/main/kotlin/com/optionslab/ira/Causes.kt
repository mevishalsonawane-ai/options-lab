package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Causal hygiene (reasoning, round 10): "why did Nifty fall?", "why is the market down today?", "what caused the rally
 * in BankNifty?", "nifty kyun gira" - the candidate explanations the phone holds, ranked by the evidence for each, with
 * the ones that are only coincidence in time said to be so.
 *
 * The move first, from the phone's own 1-minute candles: the day's change from the previous close, how much of it came
 * at the open (the gap: decided before 9:15, outside what the candles see) and the biggest stretch of it in the session
 * (its start and end), set against the index's usual day on the phone (a move no bigger than an ordinary day's may need
 * no special reason, and that is said first). Then each candidate, weighed:
 *  - a headline story ([NewsDesk.stories]) - its first time against the move: shortly before the biggest stretch began
 *    (or before the open, for a gap) the timing fits; after the stretch began it cannot have started it (it may report
 *    it); long before or after, nothing ties them. Weighed further by its sources, whether its words read the move's
 *    way, and - for a theme timed before ([NewsMoves]) - whether that theme's headlines were followed by a move more
 *    often than any hour on the phone. A headline that reports the move itself is never a reason for it;
 *  - the other indices (broad, or this index alone; heavier or lighter in banks - BankNifty is the only sector the
 *    phone has) and India VIX: they describe how it moved, they don't explain it;
 *  - FII figures: a day's total (out after the close), which can't say when they sold or that it moved the price;
 *  - today's scheduled events and expiry: the calendar holds their day, not their time.
 * What the phone has no data for (global markets, crude, stocks and sector indices, intraday flows) is named, never
 * weighed. Timing and size only: no cause is ever claimed, no forecast, no advice; nothing here acts. Pure.
 */
object Causes {
    /** What was asked: [market] (null: the market, read as Nifty) and the direction asked about (null: "why is it moving"). */
    data class Ask(val market: Market?, val down: Boolean?)

    /** The indices weighed. */
    val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    /** A day's change under this (%) is "barely moved": nothing to explain. */
    const val FLAT_PCT = 0.15
    /** A gap of at least this (%) and [GAP_SHARE] of the day's move is the overnight part. */
    const val GAP_PCT = 0.15
    const val GAP_SHARE = 0.4
    /** A story this many minutes before the biggest stretch began (to [LEAD_AFTER] after) fits in time. */
    const val LEAD_MINUTES = 30L
    const val LEAD_AFTER = 2L
    /** Earlier sessions needed before the usual day is said. */
    const val USUAL_MIN = 10
    const val USUAL_DAYS = 20
    /** Today's candles needed before anything is weighed. */
    const val MIN_BARS = 15
    /** At most this many stories in each group. */
    const val MAX_STORIES = 3
    /** Another index moving less than this (%) has barely moved. */
    const val QUIET_PCT = 0.1

    const val NOT_HERE = "I weigh the reasons for a move on Nifty, BankNifty, FinNifty and Sensex, Boss - the phone has the candles for those."
    const val HYGIENE = "Timing and size only, Boss: none of this proves what caused the move. Facts, not a forecast."
    const val NOT_ON_PHONE = "Not on the phone, so not weighed: global markets, crude, the rupee's moves, single stocks and sector indices, and intraday FII flows."

    // ---- what was asked ----------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace(Regex("'s\\b"), "s").replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim() + " "

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |tell me |please |bata |batao )*"
    private const val SUBJ = "(the )?(market|markets|stock market|share market|indices|index|nifty|nifty 50|nifty fifty|banknifty|bank nifty|nifty bank|finnifty|fin nifty|sensex)"
    private const val DOWN = "(fall|fell|fallen|falling|drop|dropped|dropping|down|crash|crashed|crashing|slide|slid|sliding|tank|tanked|tanking|" +
        "decline|declined|declining|dip|dipped|dipping|red|weak|lower|sell off|selloff|selling off|sold off|go down|gone down|going down|went down|slump|slumped|slumping|plunge|plunged|plunging|bleeding|bleed)"
    private const val UP = "(rise|rose|risen|rising|rally|rallied|rallying|up|jump|jumped|jumping|surge|surged|surging|green|strong|higher|climb|climbed|climbing|" +
        "gain|gained|gaining|go up|gone up|going up|went up|soar|soared|soaring|run up|bounce|bounced|bouncing|recover|recovered|recovering)"
    private const val MOVE = "(move|moved|moving|swing|swung|swinging)"
    private const val NOUN_DOWN = "(fall|drop|crash|slide|decline|dip|selloff|sell off|slump|plunge|weakness)"
    private const val NOUN_UP = "(rise|rally|jump|surge|gain|gains|climb|bounce|recovery|strength|upmove|up move)"
    private const val WHEN = "( today| aaj| this morning| this afternoon| now| right now| so far| since the open| since morning| in the morning)?"
    private const val MUCH = "( so much| this much| that much| so hard| so sharply| sharply| so badly| so fast| like this| like that)?"

    private val WHY = Regex("^ $LEAD(why|how come) (did |does |is |was |has |have |are |were |s )?$SUBJ( been| be)?( so| this| that)?( much)? ($DOWN|$UP|$MOVE)$MUCH$WHEN$MUCH( boss| jarvis)? $")
    private val CAUSED = Regex("^ $LEAD(what|whats|what is|what was) (caused|causing|is causing|drove|is driving|driving|was driving|is behind|was behind|behind|led to|triggered|sparked) " +
        "(the |this |that |today s |todays |the day s )?($NOUN_DOWN|$NOUN_UP|move|swing)( in| of| on)?( $SUBJ)?$WHEN( boss| jarvis)? $")
    private val CAUSED_TO = Regex("^ $LEAD(what|whats|what is|what was) (caused|made|is making|made the|pushed|dragged|sent) $SUBJ (to )?($DOWN|$UP|$MOVE)$WHEN( boss| jarvis)? $")
    /** "What drove the market today", "what explains today's move" (routing round 11: the plain why, or the market's update). */
    private val DROVE = Regex("^ $LEAD(what|whats|what is|what has) (drove|is driving|has driven|driving|moved|is moving|has moved) $SUBJ$WHEN( boss| jarvis)? $|" +
        "^ $LEAD(what|whats|what is) (explains|explaining|is explaining) (the |this |that |today s |todays |the day s )?($NOUN_DOWN|$NOUN_UP|move|swing)( in| of| on)?( $SUBJ)?$WHEN( boss| jarvis)? $")
    private val REASON = Regex("^ $LEAD(the )?(reason|reasons|wajah) (for|behind|of) (the |this |that |today s |todays )?($NOUN_DOWN|$NOUN_UP|move|swing)( in| of| on)?( $SUBJ)?$WHEN( boss| jarvis)? $")
    private const val HI_DOWN = "(gira|giri|gir raha hai|gir rahi hai|gir gaya|gir gayi|neeche hai|neeche aaya|neeche|down hai|toota|tuta|tut gaya)"
    private const val HI_UP = "(badha|badhi|chadha|chadhi|upar hai|upar gaya|upar|up hai|bhaga|uchla)"
    private val HINDI = Regex("^ $LEAD$SUBJ?( aaj)? (kyun|kyu|kyon|kiu|kis wajah se) (aaj )?($HI_DOWN|$HI_UP)( hai| aaj)?( boss| jarvis)? $")
    /** Not here: a sudden move is [SharpMove]'s, news is [NewsDesk]'s, his own book or Jarvis's own doings, a forecast or advice. */
    private val NOT = Regex(" (suddenly|just|news|headline|headlines|khabar|my|mine|me|i|you|your|we|our|will|would|tomorrow|kal|should|buy|sell|gold|vix|crude|dollar|rupee|trade|trades|position|positions|strategy|bot|orb|solo) ")

    fun asked(text: String): Ask? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (!(WHY.containsMatchIn(t) || CAUSED_TO.containsMatchIn(t) || HINDI.containsMatchIn(t) || CAUSED.containsMatchIn(t) || REASON.containsMatchIn(t) || DROVE.containsMatchIn(t))) return null
        val down = when {
            Regex(" ($DOWN|$NOUN_DOWN|$HI_DOWN) ").containsMatchIn(t) -> true
            Regex(" ($UP|$NOUN_UP|$HI_UP) ").containsMatchIn(t) -> false
            else -> null
        }
        val named = Market.mentioned(text).firstOrNull { it in INDICES }
        return Ask(named, down)
    }

    /** The index weighed for [a]: the one named, else the first index in [markets], else Nifty; null for any other market. */
    fun market(a: Ask, markets: List<Market>): Market? {
        a.market?.let { return it }
        if (markets.isEmpty()) return Market.NIFTY
        return markets.firstOrNull { it in INDICES } ?: if (markets.any { it == Market.GOLD || it == Market.VIX }) null else Market.NIFTY
    }

    // ---- the move ----------------------------------------------------------------------------------------------------

    /** The day's move of one index: from [base] (the previous close, else the open) to [last]; its biggest stretch [legFrom]-[legTo]. */
    data class Move(val market: Market, val day: LocalDate, val base: Double, val hasPrev: Boolean, val open: Double, val last: Double,
                    val openAt: LocalDateTime, val lastAt: LocalDateTime, val legFrom: LocalDateTime, val legTo: LocalDateTime, val legPoints: Double) {
        val net: Double get() = last - base
        val pct: Double get() = net / base * 100
        val down: Boolean get() = net < 0
        val gap: Double get() = if (hasPrev) open - base else 0.0
        val gapPct: Double get() = gap / base * 100
    }

    /** The day's move of [m] on its newest session in [bars] (1-minute); null with fewer than [MIN_BARS] candles that day. */
    fun move(m: Market, bars: List<Candle>, down: Boolean? = null): Move? {
        val b = bars.filter { it.c.isFinite() && it.c > 0 }.sortedBy { it.t }
        val last = b.lastOrNull() ?: return null
        val day = last.t.toLocalDate()
        val today = b.filter { it.t.toLocalDate() == day }
        if (today.size < MIN_BARS) return null
        val prev = b.lastOrNull { it.t.toLocalDate().isBefore(day) }?.c
        val open = today.first().o.takeIf { it.isFinite() && it > 0 } ?: today.first().c
        val base = prev ?: open
        val falling = down ?: (last.c < base)
        // The biggest stretch the day's way: the largest fall from an earlier close (or the open) to a later close (or rise).
        var bestFrom = 0; var bestTo = 0; var best = 0.0
        var ext = open; var extAt = 0
        for (j in today.indices) {
            val p = today[j].c
            val d = if (falling) ext - p else p - ext
            if (d > best) { best = d; bestFrom = extAt; bestTo = j }
            if (if (falling) p >= ext else p <= ext) { ext = p; extAt = j }
        }
        return Move(m, day, base, prev != null, open, last.c, today.first().t, last.t, today[bestFrom].t, today[bestTo].t, if (falling) -best else best)
    }

    /** The index's close-to-close moves (%, absolute) over its newest [USUAL_DAYS] sessions before [day]. */
    fun dailyMoves(bars: List<Candle>, day: LocalDate): List<Double> {
        val closes = bars.filter { it.t.toLocalDate().isBefore(day) && it.c.isFinite() && it.c > 0 }.sortedBy { it.t }
            .groupBy { it.t.toLocalDate() }.toSortedMap().values.map { it.last().c }
        return closes.zipWithNext { a, b -> abs(b - a) / a * 100 }.takeLast(USUAL_DAYS)
    }

    private fun median(x: List<Double>): Double? {
        if (x.isEmpty()) return null
        val s = x.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    // ---- the candidates ----------------------------------------------------------------------------------------------

    /** How a candidate stands: its timing fits (ranked by [score]), it only describes the move, or it is only coincidence. */
    enum class Weight { FITS, DESCRIBES, COINCIDENCE }

    /** One candidate explanation, its [weight], its [score] (FITS only: higher is better evidence) and the words said. */
    data class Candidate(val weight: Weight, val score: Int, val text: String)

    private fun n(x: Double) = "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun pts(x: Double) = (if (x < 0) "-" else "+") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun pctAbs(x: Double) = "%.2f%%".format(Locale.ENGLISH, abs(x))
    private fun share(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
    private fun mins(a: LocalDateTime, b: LocalDateTime) = ChronoUnit.MINUTES.between(a, b).let { "$it minute${if (it == 1L) "" else "s"}" }
    private fun quote(s: String) = "\"" + (if (s.length > 90) s.take(87).trimEnd() + "..." else s) + "\""

    /** A headline that reports a move of the market ("Nifty slips 1%", "Sensex ends lower") - never a reason for it. */
    private val REPORT = Regex(" (nifty|sensex|bank nifty|banknifty|nifty bank|market|markets|indices|stocks|equities|dalal street|d street|benchmarks?) " +
        "([a-z0-9 ]{0,30} )?(falls?|fell|slips?|slipped|drops?|dropped|tumbles?|tumbled|declines?|declined|sheds?|crash|crashes|crashed|plunges?|plunged|slumps?|slumped|sinks?|sank|" +
        "rises?|rose|gains?|gained|jumps?|jumped|surges?|surged|rall(y|ies|ied)|climbs?|climbed|soars?|soared|ends|ended|closes|closed|opens|opened|trades|trading|extends?|extended|snaps?|snapped|recovers?|recovered|" +
        "lower|higher|in red|in green|down|up) ")

    fun report(title: String): Boolean = REPORT.containsMatchIn(norm(title))

    /** The stories weighed against [mv]: theme-tagged or naming an index, first out from the previous close on. */
    private fun stories(mv: Move, news: List<Headline>, zone: ZoneId): List<Pair<NewsDesk.Story, LocalDateTime>> {
        val from = mv.day.minusDays(1).atTime(15, 30)
        return NewsDesk.stories(news.filter { it.markets.isEmpty() || it.markets.any { m -> m != Market.GOLD } })
            .mapNotNull { s -> s.first?.let { s to LocalDateTime.ofInstant(it, zone) } }
            .filter { (s, t) -> !t.isBefore(from) && !t.isAfter(mv.lastAt.plusMinutes(1)) && s.tags.any { it != NewsDesk.Tag.GOLD } }
    }

    /** Each story weighed against [mv] (see the object's comment). [usual]: how often the theme's index moves [NewsMoves.BIG_PCT] in any hour. */
    fun weighStories(mv: Move, news: List<Headline>, zone: ZoneId, log: List<NewsMoves.Note>, usual: Double?): List<Candidate> {
        val out = ArrayList<Candidate>()
        val gapCounts = mv.hasPrev && abs(mv.gapPct) >= GAP_PCT && (mv.gap < 0) == mv.down && abs(mv.gap / mv.net) >= GAP_SHARE
        val legBig = abs(mv.legPoints) / mv.base * 100 >= FLAT_PCT
        val way = if (mv.down) "fall" else "rise"
        for ((s, t) in stories(mv, news, zone)) {
            val title = quote(s.title)
            val src = if (s.sourceCount > 1) " (${s.sourceCount} sources)" else ""
            val at = if (t.toLocalDate() == mv.day) hm(t) else "${date(t.toLocalDate())} ${hm(t)}"
            if (report(s.title)) { out += Candidate(Weight.COINCIDENCE, 0, "$title$src at $at reports the move itself - a report of it, not a reason for it."); continue }
            val preOpen = t.isBefore(mv.openAt)
            val leadFrom = mv.legFrom.minusMinutes(LEAD_MINUTES)
            val why = ArrayList<String>()
            var score: Int
            when {
                preOpen && gapCounts -> { score = 3; why += "out before the open, so it could bear on the gap" }
                preOpen -> { out += Candidate(Weight.COINCIDENCE, 0, "$title$src came out before the open ($at), but ${mv.market.label} opened " +
                    (if (!mv.hasPrev || abs(mv.gapPct) < GAP_PCT) "close to the previous close" else "the other way") + " - nothing in the timing ties it to the $way."); continue }
                legBig && !t.isBefore(leadFrom) && !t.isAfter(mv.legFrom.plusMinutes(LEAD_AFTER)) -> {
                    score = 3; why += if (t.isAfter(mv.legFrom)) "out as the biggest stretch began (${hm(mv.legFrom)})"
                        else "out ${mins(t, mv.legFrom)} before the biggest stretch began (${hm(mv.legFrom)})"
                }
                legBig && t.isAfter(mv.legFrom) && !t.isAfter(mv.legTo) -> { out += Candidate(Weight.COINCIDENCE, 0,
                    "$title$src came out at $at, after the $way had begun (${hm(mv.legFrom)}) - it can't have started it; it may only report it."); continue }
                legBig && t.isAfter(mv.legTo) -> { out += Candidate(Weight.COINCIDENCE, 0,
                    "$title$src came out at $at, after the biggest stretch was over (${hm(mv.legTo)})."); continue }
                legBig -> { out += Candidate(Weight.COINCIDENCE, 0,
                    "$title$src came out at $at, ${mins(t, mv.legFrom)} before the biggest stretch began - nothing in the timing ties them."); continue }
                else -> { out += Candidate(Weight.COINCIDENCE, 0, "$title$src came out at $at, on a day with no clear stretch to tie it to."); continue }
            }
            // Its sources, its words, its theme's record here, and whether it touches the index asked about.
            if (s.sourceCount > 1) { score += minOf(s.sourceCount - 1, 2); why += "${s.sourceCount} sources" }
            val tone = s.headlines.map { it.tone }.average()
            if (tone.isFinite() && abs(tone) >= 0.25) {
                if ((tone < 0) == mv.down) { score += 1; why += "its words read ${if (tone < 0) "negative" else "positive"}, the move's way" }
                else { score -= 2; why += "though its words read ${if (tone < 0) "negative" else "positive"}, the other way" }
            }
            if (mv.market in listOf(Market.BANKNIFTY, Market.FINNIFTY) && NewsDesk.Tag.BANKS in s.tags) { score += 1; why += "about banks" }
            val rm = if (mv.market in NewsMoves.MARKETS) mv.market else Market.NIFTY
            val rec = s.tags.filter { it in NewsMoves.TAGS }.map { NewsMoves.record(log, it, rm, mv.day) }.filter { it.n >= NewsMoves.FEW }.maxByOrNull { it.n }
            if (rec != null && usual != null) {
                val what = NewsMoves.what(rec.tag)
                if (rec.rate >= usual + 0.1) { score += 2; why += "$what headlines here were followed by a ${"%.1f".format(Locale.ENGLISH, NewsMoves.BIG_PCT)}% move within an hour " +
                    "${rec.big} of ${rec.n} times, against about ${share(usual)} of any hour" }
                else if (rec.rate <= usual) { score -= 1; why += "but $what headlines here were followed by such a move no more often than any hour (${rec.big} of ${rec.n}, against about ${share(usual)})" }
            }
            out += Candidate(Weight.FITS, score, "$title, ${why.joinToString("; ")}.")
        }
        return out
    }

    /** The other indices over the same day: broad or alone, heavier or lighter in banks. Describes, doesn't explain. */
    fun breadth(mv: Move, bars: Map<Market, List<Candle>>): Candidate? {
        val others = INDICES.filter { it != mv.market }.mapNotNull { o -> bars[o]?.let { move(o, it) }?.takeIf { it.day == mv.day }?.let { o to it.pct } }
        if (others.isEmpty()) return null
        val list = others.joinToString(", ") { "${it.first.label} ${pct(it.second)}" }
        val same = others.count { (it.second < 0) == mv.down && abs(it.second) >= QUIET_PCT }
        val head = when (same) {
            others.size -> "Broad: $list - the whole market moved, not ${mv.market.label} alone"
            0 -> "${mv.market.label} alone: $list - so it was in ${mv.market.label}'s own stocks, which the phone doesn't hold"
            else -> "$same of ${others.size} other indices moved the same way ($list)"
        }
        val bank = when (mv.market) {
            Market.BANKNIFTY -> others.firstOrNull { it.first == Market.NIFTY }?.let { (_, p) ->
                if (abs(mv.pct) >= 1.5 * abs(p) && abs(mv.pct) >= QUIET_PCT) "; banks did more than Nifty (${pct(mv.pct)} against ${pct(p)})"
                else if (abs(mv.pct) <= 0.5 * abs(p)) "; banks did less than Nifty (${pct(mv.pct)} against ${pct(p)})" else null }
            else -> others.firstOrNull { it.first == Market.BANKNIFTY }?.let { (_, p) ->
                if ((p < 0) == mv.down && abs(p) >= 1.5 * abs(mv.pct)) "; heavier in banks (BankNifty ${pct(p)} against ${mv.market.label}'s ${pct(mv.pct)})"
                else if (abs(p) <= 0.5 * abs(mv.pct)) "; lighter in banks (BankNifty ${pct(p)}) - the rest did more" else null }
        } ?: ""
        return Candidate(Weight.DESCRIBES, 0, "$head$bank.")
    }

    /** India VIX over the day and the biggest stretch: the fear priced in as the market moved - a symptom, not a reason. */
    fun vix(mv: Move, vixBars: List<Candle>): Candidate? {
        val v = move(Market.VIX, vixBars, down = false)?.takeIf { it.day == mv.day } ?: return null
        val day = vixBars.filter { it.t.toLocalDate() == mv.day }.sortedBy { it.t }
        fun at(t: LocalDateTime) = day.lastOrNull { !it.t.isAfter(t) }?.c
        val legPart = at(mv.legFrom)?.let { a -> at(mv.legTo)?.let { b -> if (a > 0 && mv.legTo.isAfter(mv.legFrom)) " (${pct((b - a) / a * 100)} over ${hm(mv.legFrom)}-${hm(mv.legTo)})" else null } } ?: ""
        val rose = v.pct > 0
        val tail = if (mv.down == rose) " - VIX is worked out from Nifty's option prices and moves against the index: it shows the fear priced in, not a reason for the move."
            else " - the other way from usual: the options market was not paying up for protection as it moved."
        return Candidate(Weight.DESCRIBES, 0, "India VIX ${pct(v.pct)} on the day$legPart$tail")
    }

    /** FII figures: a day's total, out after the close - never tied to a time in the session. */
    fun fii(mv: Move, flows: List<Flows.Flow>): Candidate? {
        val f = flows.firstOrNull { it.who == "FII" } ?: return null
        val d = runCatching { LocalDate.parse(f.date.trim(), DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)) }.getOrNull()
        val what = "${if (f.net < 0) "net sellers" else "net buyers"} of Rs ${n(f.net)} crore"
        val dii = flows.firstOrNull { it.who == "DII" }?.let { "; DIIs were ${if (it.net < 0) "net sellers" else "net buyers"} of Rs ${n(it.net)} crore" } ?: ""
        return when {
            d == mv.day -> Candidate(Weight.COINCIDENCE, 0, "FIIs were $what on ${date(mv.day)} (NSE's day total$dii) - " +
                (if ((f.net < 0) == mv.down) "the same way as the move, " else "the other way from the move, ") +
                "but a day's total can't say when they traded or that it moved the price.")
            d != null && d.isBefore(mv.day) -> Candidate(Weight.COINCIDENCE, 0, "The latest FII figures I have are for ${date(d)}: $what - " +
                "from before this session, so they can't tell this session's flows; ${date(mv.day)}'s come out after its close.")
            else -> null
        }
    }

    // ---- the answer --------------------------------------------------------------------------------------------------

    /**
     * Why [m] moved, weighed: the move, then the candidates whose timing fits (best evidence first, with why), how it moved
     * (describes, doesn't explain), what is only coincidence in time, and what the phone has no data for. [bars]: 1-minute
     * candles of the indices and India VIX; [log]: the news-and-moves record; [flows]: NSE's latest FII/DII figures;
     * [events]: today's scheduled events (names, no expiries); [expiry]: [m]'s expiry day.
     */
    fun answer(a: Ask, m: Market, bars: Map<Market, List<Candle>>, news: List<Headline>, zone: ZoneId, now: LocalDateTime,
               log: List<NewsMoves.Note> = emptyList(), flows: List<Flows.Flow> = emptyList(), events: List<String> = emptyList(),
               expiry: Boolean = false): String {
        if (m !in INDICES) return NOT_HERE
        val first = move(m, bars[m].orEmpty()) ?: return "I don't have enough of ${m.label}'s candles from the session on the phone to weigh that, Boss."
        val mv = move(m, bars[m].orEmpty(), down = first.down) ?: first
        val dayWord = if (mv.day == now.toLocalDate()) "today" else "on ${date(mv.day)} (the last session on the phone)"
        val from = if (mv.hasPrev) "from the previous close" else "from the open (the previous close isn't on the phone)"
        if (abs(mv.pct) < FLAT_PCT)
            return "${m.label} has barely moved $dayWord (${pct(mv.pct)}, ${pts(mv.net)} points $from), Boss - there is no fall or rise to explain. $HYGIENE"
        val way = if (mv.down) "fall" else "rise"
        val parts = ArrayList<String>()
        if (a.down != null && a.down != mv.down)
            parts += "${m.label} isn't ${if (a.down) "down" else "up"} $dayWord, Boss - it's ${if (mv.down) "down" else "up"}. Weighing the $way instead."
        // The move: size, the gap's share, the biggest stretch, and the usual day here.
        val usualDays = dailyMoves(bars[m].orEmpty(), mv.day)
        val med = if (usualDays.size >= USUAL_MIN) median(usualDays) else null
        val head = StringBuilder("${m.label} is ${if (mv.down) "down" else "up"} ${pctAbs(mv.pct)} (${pts(mv.net)} points) $from $dayWord")
        med?.let { u -> head.append(if (abs(mv.pct) >= 1.5 * u) ", ${"%.1f".format(Locale.ENGLISH, abs(mv.pct) / u)} times its middle day's move on this phone (${pctAbs(u)} over ${usualDays.size} sessions)"
            else ", against a middle day's move of ${pctAbs(u)} on this phone over ${usualDays.size} sessions") }
        head.append(".")
        if (mv.hasPrev && abs(mv.gapPct) >= GAP_PCT) head.append(" It opened ${n(mv.gap)} points ${if (mv.gap < 0) "below" else "above"} the previous close" +
            (if ((mv.gap < 0) == mv.down) " (${share(minOf(1.0, abs(mv.gap / mv.net)))} of the day's move)" else ", the other way") + ".")
        if (abs(mv.legPoints) / mv.base * 100 >= FLAT_PCT)
            head.append(" The biggest stretch in the session: ${hm(mv.legFrom)} to ${hm(mv.legTo)}, ${pts(mv.legPoints)} points.")
        parts += head.toString()
        // Ranked: what fits in time, best evidence first.
        val fits = ArrayList<Candidate>()
        val gapShare = if (mv.hasPrev && (mv.gap < 0) == mv.down && abs(mv.gapPct) >= GAP_PCT) abs(mv.gap / mv.net) else 0.0
        if (med != null && abs(mv.pct) <= med) fits += Candidate(Weight.FITS, 9, "Nothing unusual to explain: a move this size is ordinary here - " +
            "${usualDays.count { it >= abs(mv.pct) }} of the last ${usualDays.size} sessions moved at least as much close to close; an ordinary day needs no special reason.")
        if (gapShare >= GAP_SHARE) fits += Candidate(Weight.FITS, if (gapShare >= 0.6) 6 else 4,
            "Most of it came before the session: the gap at the open was ${share(minOf(1.0, gapShare))} of the day's move, so whatever drove that happened before 9:15 - outside the candles on the phone.")
        val usual = runCatching { NewsMoves.usual(if (m in NewsMoves.MARKETS) m else Market.NIFTY, bars[if (m in NewsMoves.MARKETS) m else Market.NIFTY].orEmpty()) }.getOrNull()
        val weighed = weighStories(mv, news, zone, log, usual)
        fits += weighed.filter { it.weight == Weight.FITS }.sortedByDescending { it.score }.take(MAX_STORIES)
        if (fits.isEmpty()) parts += "Nothing on the phone lines up ahead of it: no headline came out shortly before the biggest stretch" +
            (if (mv.hasPrev) ", and the open was not where most of it happened." else ".")
        else {
            val ranked = fits.sortedByDescending { it.score }
            parts += "Weighed by the evidence, best first: " + ranked.mapIndexed { i, c -> "${i + 1}. ${c.text}" }.joinToString(" ")
        }
        // How it moved: describes, doesn't explain.
        val desc = listOfNotNull(breadth(mv, bars), bars[Market.VIX]?.let { vix(mv, it) })
        if (desc.isNotEmpty()) parts += "How it moved (that describes it, it doesn't explain it): " + desc.joinToString(" ") { it.text }
        // Only coincidence in time.
        val coin = ArrayList<String>()
        coin += weighed.filter { it.weight == Weight.COINCIDENCE }.take(MAX_STORIES).map { it.text }
        fii(mv, flows)?.let { coin += it.text }
        if (events.isNotEmpty()) coin += "Scheduled ${if (mv.day == now.toLocalDate()) "today" else "that day"}: ${events.joinToString(", ")} - the calendar holds the day, not the time, so I can't place it against the $way."
        if (expiry) coin += "It is ${m.label}'s expiry day - a fact of the calendar; nothing on the phone ties it to the $way."
        if (coin.isNotEmpty()) parts += "Only coincidence in time: " + coin.joinToString(" ")
        parts += NOT_ON_PHONE + (if (flows.none { it.who == "FII" }) " No FII figures were read either." else "")
        parts += HYGIENE
        return parts.joinToString(" ")
    }
}
