package com.optionslab.ira

import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.SoloMidday
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * "Tomorrow's plan" (Boss, 06 Oct 2026): once the session is over (from [POST_AT], once a trading day, in the words lane)
 * Jarvis puts one note in the chat that prepares Boss for the next session, and says the same when asked ("what's the
 * plan for tomorrow", "tomorrow ka plan", "prepare me for tomorrow"). Facts only, short, each line only when its data
 * is there:
 *
 *  - the next session's date (weekends and the exchange's holidays skipped, those named) and the index expiries that day;
 *  - Liquidity 15+5: today's paper result in one line, the levels it carries into tomorrow for BankNifty, FinNifty and Midcap Nifty (the
 *    nearest untaken level above and below the close, and how far, from [LiquidityMap]'s read), its switch and its lots;
 *    today's trades in research terms ([TradeLesson.day]'s line);
 *  - Solo (midday): on or off and its forward test's count of [SoloMidday.FORWARD_TRADES]; Hero: whether the next session
 *    is a Nifty expiry, the only kind of day it trades;
 *  - the events on the next session ([Events]), the FIIs' latest positioning (NSE's participant OI, [MorningCues]) and
 *    the FII/DII cash figures the market recorder kept ([Flows]), today's big-move read at the close ([BigMoveRisk]);
 *  - that GIFT Nifty is read in the morning ([MorningCues], the 09:00 check).
 *
 * Words only: nothing here places, closes, arms or changes anything, and it never suggests a trade. Pure.
 */
object TomorrowPlan {
    /** The plan is put in the chat from this time on a trading day (after the close and the 15:35 wrap-up). */
    val POST_AT: LocalTime = LocalTime.of(15, 45)
    /** ... and not from this time (quiet hours start then; the next morning's check takes over). */
    val POST_UNTIL: LocalTime = LocalTime.of(22, 0)

    const val LOCKED = "Unlock the phone for tomorrow's plan, Boss - it has your paper trades."
    const val NOTHING = "Facts only - nothing was armed, placed or changed."
    const val GIFT = "GIFT Nifty is read tomorrow morning from 06:30 - the 09:00 check gives the gap (or ask \"what's GIFT Nifty saying\")."

    /** One untaken liquidity level: its [edge], [distance] in points from the close (never negative), its [name] and book. */
    data class Level(val edge: Double, val distance: Double, val name: String, val minutes: Int)

    /** An index's levels carried into tomorrow: the [close] read and the nearest untaken level [above] and [below] it. */
    data class Levels(val underlying: String, val close: Double, val above: Level?, val below: Level?)

    /** Liquidity 15+5's closed paper trades today: [trades], [wins], [net] rupees after charges. */
    data class Day(val trades: Int, val wins: Int, val net: Double)

    /**
     * What the app read. [next]: the next session (null: not known). [holidays]: the weekday holidays between today and
     * [next], named. [expiries]: the indices expiring on [next]; [expiriesKnown] false when no contract list is on the
     * phone (then neither the expiries nor Hero's day is said). [liquidity]: today's closed paper trades (null: not read);
     * [levels]: the levels read; [armed]/[lots]: the arm's switch and size (null: not read). [soloOn]/[soloTrades]:
     * Solo's switch and its forward-test count. [heroArmed]: the Hero arm's switch (null: not read). [events]: the
     * events on the days up to [next]; [fii]: the FIIs' positioning line ([MorningCues.fiiBrief]); [flows]: the FII/DII
     * cash figures the recorder kept; [bigMove]: today's big-move read at the close; [lesson]: [TradeLesson.day]'s line.
     */
    data class Facts(
        val today: LocalDate, val next: LocalDate?,
        val holidays: List<Pair<LocalDate, String>> = emptyList(),
        val expiries: List<Market> = emptyList(), val expiriesKnown: Boolean = false,
        val liquidity: Day? = null, val levels: List<Levels> = emptyList(), val armed: Boolean? = null, val lots: Int? = null,
        val soloOn: Boolean? = null, val soloTrades: Int? = null,
        val heroArmed: Boolean? = null,
        val events: List<Events.Event> = emptyList(),
        val fii: String? = null, val flows: List<Flows.Flow> = emptyList(),
        val bigMove: BigMoveRisk.Read? = null,
        val lesson: String? = null,
    )

    // ---- when -----------------------------------------------------------------------------------------------------

    /** The note is due at [now]: a trading day, from [POST_AT] until [POST_UNTIL], and not yet put today ([doneOn]). */
    fun due(now: LocalDateTime, tradingToday: Boolean, doneOn: LocalDate?): Boolean {
        if (!tradingToday || doneOn == now.toLocalDate()) return false
        val t = now.toLocalTime()
        return !t.isBefore(POST_AT) && t.isBefore(POST_UNTIL)
    }

    /** Liquidity 15+5's paper trades closed on [today] from its book's rows ([LiquidityRecord.rows]: closed paper, net after charges). */
    fun dayOf(rows: List<LiquidityRecord.Row>, today: LocalDate): Day {
        val mine = rows.filter { it.trade.exitTime?.toLocalDate() == today }
        return Day(mine.size, mine.count { it.win }, mine.sumOf { it.net })
    }

    // ---- the levels -----------------------------------------------------------------------------------------------

    /**
     * Each index's levels from Liquidity 15+5's books ([LiquidityMap.read]: BankNifty 15 and 5, FinNifty 30 and 5, Midcap Nifty 15 and 5): the
     * close is the newest price the books read, and on each side the nearest untaken level of any book (a level the price
     * already sits past is not one). Indices in the arm's order; one with no book read is left out.
     */
    fun levels(reads: List<LiquidityMap.Read>): List<Levels> {
        val ok = reads.filter { it.state == LiquidityMap.State.OK && it.price != null }
        val order = LiquidityRules.UNDERLYINGS
        return ok.groupBy { it.underlying }.entries.sortedBy { order.indexOf(it.key).let { i -> if (i < 0) order.size else i } }.map { (u, rs) ->
            val close = rs.maxBy { it.priceAt ?: LocalDateTime.MIN }.price!!
            fun side(sign: Int): Level? = rs.mapNotNull { r ->
                val l = (if (sign > 0) r.above else r.below)?.nearest ?: return@mapNotNull null
                val d = sign * (l.edge - close)
                if (d < 0) null else Level(l.edge, d, l.name, r.minutes)
            }.minWithOrNull(compareBy<Level> { it.distance }.thenBy { it.minutes })
            Levels(u, close, side(1), side(-1))
        }
    }

    // ---- the words ------------------------------------------------------------------------------------------------

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun n(x: Double) = String.format(Locale.ENGLISH, "%,.0f", x)
    private fun pts(x: Double) = String.format(Locale.ENGLISH, "%.0f", abs(x))
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "Rs ") + String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun list(xs: List<String>) = if (xs.size <= 1) xs.joinToString() else xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    private fun s(k: Int) = if (k == 1) "" else "s"

    /** "tomorrow, Wed 7 Oct" / "Mon 12 Oct (after the weekend)" / "Fri 9 Oct (Thu 8 Oct is a holiday: Dussehra)". */
    fun nextWords(today: LocalDate, next: LocalDate, holidays: List<Pair<LocalDate, String>>): String {
        val day = next.format(DAY)
        if (next == today.plusDays(1)) return "tomorrow, $day"
        val skipped = holidays.filter { it.first.isAfter(today) && it.first.isBefore(next) }.sortedBy { it.first }
        val weekend = generateSequence(today.plusDays(1)) { it.plusDays(1) }.takeWhile { it.isBefore(next) }
            .any { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
        val why = listOfNotNull(
            if (weekend) "after the weekend" else null,
            if (skipped.isEmpty()) null else list(skipped.map { (d, name) -> "${d.format(DAY)} is a holiday: $name" }),
        )
        return day + if (why.isEmpty()) "" else " (${why.joinToString("; ")})"
    }

    private fun expiryLine(f: Facts): String? {
        if (!f.expiriesKnown) return null
        return if (f.expiries.isEmpty()) "No index expiry that day."
        else "Expiry that day: ${list(f.expiries.map { it.label })}."
    }

    private fun resultLine(d: Day): String =
        if (d.trades == 0) "Liquidity 15+5 today: no paper trade."
        else "Liquidity 15+5 today on paper: ${d.trades} trade${s(d.trades)}, ${d.wins} won, net ${rs(d.net)} after charges."

    private fun levelWords(l: Level?, word: String): String =
        if (l == null) "nothing untaken $word read" else "$word ${n(l.edge)} (${l.name}, ${l.minutes}-min) ${pts(l.distance)} pts away"

    /** "BankNifty closed 54,120 - above 54,180 (pool on a swing high, 15-min) 60 pts away; below 53,900 (swing low, 5-min) 220 pts away." */
    fun levelLine(l: Levels): String =
        "${LiquidityMap.indexName(l.underlying)} at the close ${n(l.close)} - ${levelWords(l.above, "above")}; ${levelWords(l.below, "below")}."

    private fun switchLine(armed: Boolean?, lots: Int?): String? {
        val size = lots?.let { "$it lot${s(it)} a trade" }
        return when (armed) {
            true -> "Liquidity 15+5's switch is on" + (size?.let { ", $it" } ?: "") + ": it watches its levels from ${LiquidityRules.FIRST_ENTRY} tomorrow."
            false -> "Liquidity 15+5's switch is off" + (size?.let { " ($it when on)" } ?: "") + ": it won't trade tomorrow unless you arm it."
            null -> size?.let { "Liquidity 15+5 trades $it." }
        }
    }

    private fun soloLine(on: Boolean?, trades: Int?): String? {
        if (on == null) return null
        val count = trades?.let { " - ${minOf(it, SoloMidday.FORWARD_TRADES)} of ${SoloMidday.FORWARD_TRADES} forward-test trades" +
            (if (it > SoloMidday.FORWARD_TRADES) " ($it in all)" else "") } ?: ""
        return "Solo (midday, paper): ${if (on) "on" else "off"}$count."
    }

    private fun heroLine(f: Facts): String? {
        if (!f.expiriesKnown) return null
        val nifty = f.expiries.any { it.name == HeroRules.UNDERLYING }
        val sw = when (f.heroArmed) { true -> " and it is armed"; false -> ", but it is switched off"; null -> "" }
        return if (nifty) "Hero (expiry, paper): the next session is a Nifty expiry - its kind of day$sw."
        else "Hero (expiry, paper): not a Nifty expiry, so it sits the next session out."
    }

    private fun eventsLine(f: Facts, next: LocalDate): String? {
        // The expiries are said on their own line: only the other events, on the days up to the next session.
        val es = f.events.filter { it.day.isAfter(f.today) && !it.day.isAfter(next) && !it.name.endsWith(" expiry") }
            .distinctBy { it.day to it.name }.sortedWith(compareBy({ it.day }, { it.name }))
        if (es.isEmpty()) return null
        return "Events: " + es.joinToString("; ") { e ->
            (if (e.day == next) "" else "${e.day.format(DAY)}: ") + e.name + (if (e.owner) " (added by you)" else "")
        } + "."
    }

    private fun flowsLine(flows: List<Flows.Flow>): String? {
        if (flows.none { it.who == "FII" || it.who == "DII" }) return null
        return Flows.lines(flows).first()
    }

    private fun bigMoveLine(r: BigMoveRisk.Read?): String? {
        val lvl = r?.level ?: return null
        val x = r.times ?: return null
        return "Big-move risk at today's close (${r.market.label}): ${lvl.word}, about ${String.format(Locale.ENGLISH, "%.1f", x)}x the usual - " +
            "${BigMoveRisk.CAVEAT}; tomorrow's 09:15 candle is the day's riskiest."
    }

    /** Every line, in the order said ([brief]: the date, expiries, Liquidity's result and switch, and the events). */
    fun lines(f: Facts, brief: Boolean = false): List<String> {
        val next = f.next ?: return listOf("I couldn't tell the next trading day just now, Boss, so there's no plan for it yet.")
        val out = ArrayList<String>()
        out += "Tomorrow's plan, Boss - next session ${nextWords(f.today, next, f.holidays)}."
        expiryLine(f)?.let { out += it }
        f.liquidity?.let { out += resultLine(it) }
        if (!brief) f.lesson?.let { out += it }
        if (!brief) f.levels.forEach { out += levelLine(it) }
        switchLine(f.armed, f.lots)?.let { out += it }
        if (!brief) soloLine(f.soloOn, f.soloTrades)?.let { out += it }
        if (!brief) heroLine(f)?.let { out += it }
        eventsLine(f, next)?.let { out += it }
        if (!brief) f.fii?.let { out += if (it.endsWith(".")) it else "$it." }
        if (!brief) flowsLine(f.flows)?.let { out += it }
        if (!brief) bigMoveLine(f.bigMove)?.let { out += it }
        out += GIFT
        out += NOTHING
        return out
    }

    /** The plan in words, one line each. */
    fun say(f: Facts, brief: Boolean = false): String = lines(f, brief).joinToString("\n")

    // ---- the recorder's FII/DII rows ------------------------------------------------------------------------------

    /**
     * The FII/DII cash figures in the market recorder's records ([lines]: "X,date,who,buy,sell,net", [MarketRecord.flow]),
     * the newest date's (the last of each kind), FII first. A row that does not read is skipped, never guessed.
     */
    fun flowsOf(lines: List<String>): List<Flows.Flow> {
        val rows = lines.filter { it.startsWith("X,") }.mapNotNull { l ->
            val f = runCatching { MarketRecord.fields(l) }.getOrNull() ?: return@mapNotNull null
            if (f.size < 6) return@mapNotNull null
            val net = f[5].toDoubleOrNull() ?: return@mapNotNull null
            Flows.Flow(f[2], f[1], f[3].toDoubleOrNull() ?: 0.0, f[4].toDoubleOrNull() ?: 0.0, net)
        }
        if (rows.isEmpty()) return emptyList()
        val newest = rows.last().date
        return rows.filter { it.date == newest }.associateBy { it.who }.values.sortedBy { if (it.who == "FII") 0 else 1 }
    }

    // ---- asked ----------------------------------------------------------------------------------------------------

    private fun norm(text: String) = Spaced.joined(text)

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |okay jarvis |boss |so |and |please |ok |okay |hi |hello )*"
    private const val TAIL = "( boss| jarvis| please| then| now| yaar| na)* $"
    private const val ASK = "(what is |whats |what s |what are |tell me |give me |show me |say |read me |go through |lets go through |lets have |lets see |can i have |can you give me |could you give me |what about )?"
    private const val NEXT = "(tomorrow|tomorrows|tmrw|tmrws|tomorow|tommorow|tommorrow|the next session|next session|the next trading day|next trading day|tomorrows session|tomorrows open)"
    private const val PLAN = "(plan|game plan|prep|preparation|prep sheet|briefing|brief|plan of action)"
    private val ASKED = Regex(
        // "what's the plan for tomorrow", "plan for tomorrow", "give me my plan for the next session", "what's the game plan for tomorrow"
        "^ $LEAD$ASK(the |my |our |a )?$PLAN (for|of) $NEXT( then| boss| jarvis)?( kya hai)?$TAIL|" +
        // "tomorrow's plan", "what's tomorrow's plan", "tomorrow's game plan", "tomorrow plan"
        "^ $LEAD$ASK(my |our |the )?(tomorrows|tmrws|tomorrow|tmrw) $PLAN$TAIL|" +
        // "tomorrow ka plan", "kal ka plan", "kal ka plan kya hai", "kal ki taiyari", "kal ke liye plan", "kal ka plan batao"
        "^ $LEAD(mera |hamara |apna )?(tomorrow|tomorrows|kal|kal subah|kal ke din) (ka|ki|ke liye|ke liye ka|ke liye kya) (plan|game plan|taiyari|taiyaari|tayari|tayyari|preparation)" +
            "( kya hai| kya h| kya he| batao| bata do| bolo| sunao| do| de do| dikhao)?( kya)?$TAIL|" +
        "^ $LEAD(plan|taiyari|tayari) (kal|tomorrow) (ka|ki|ke liye)( kya hai| batao| bolo)?$TAIL|" +
        // "prepare me for tomorrow", "get me ready for tomorrow", "brief me for tomorrow", "prep me for the next session"
        "^ $LEAD(can you |could you |will you |would you )?(prepare|prep|ready|brief|get) me( ready| set| prepared)? (for|ahead of) $NEXT$TAIL|" +
        // "how should I prepare for tomorrow", "help me prepare for tomorrow", "how do I get ready for tomorrow"
        "^ $LEAD(how (should|do|can|must) i |help me |lets )(prepare|prep|get ready|get prepared|plan) (for|ahead of) $NEXT$TAIL"
    )
    /** Never this: an index named (its own outlook), Jarvis's own plan, an act, a reminder, a forecast, the day after, a checklist. */
    private val NOT = Regex(" (nifty|banknifty|finnifty|midcpnifty|midcap|sensex|bank|fin|bnf|vix|gold|your|yours|buy|sell|trade|trades|order|orders|close|exit|square|" +
        "stop|start|arm|disarm|switch|remind|reminder|alert|alarm|outlook|forecast|expect|predict|day after|checklist|check list|to do|todo|book|" +
        "trip|holiday|leave|vacation|meeting|lunch|dinner|gym) ")

    /** "What's the plan for tomorrow", "tomorrow ka plan", "prepare me for tomorrow". */
    fun asked(text: String): Boolean {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return false
        return ASKED.containsMatchIn(t)
    }
}
