package com.optionslab.ira

import com.optionslab.engine.orb.SoloMidday
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * Solo (midday)'s day in words (Boss, 06 Oct 2026): "what did solo do today", "why didn't solo trade", "solo ne aaj kya
 * kiya", "how did solo decide". Answered from Solo's own records of today: its switch; before 12:00 what it will look at
 * (NIFTY, BANKNIFTY and FINNIFTY - SENSEX is not traded) and whether its early reads are in (each index's daily ATR, the
 * contracts); at its 12:00 decision each index's read against the others ([SoloMidday.signal]: the move from the open in
 * daily ATRs - the strength the indices are ranked by - and where it closed in the morning's range), what set an index
 * aside after it signalled (its expiry day, another automatic position on it, a thin strike moved or passed over, no
 * contract or price, the paper order unfilled, the 12:03 LATE rule), the pick with its entry, stop, breakeven lock, 2R mark
 * and 14:30 exit - and once closed its exit and net - or why there was no trade; then the forward test (n of 60, the net,
 * the drawdown against the -Rs 25,000 bar from its baseline) and the research line ([ForwardCheck.SOLO]).
 *
 * Facts from Solo's own records only - nothing here switches, places, closes or changes anything. Pure: no clock, no
 * storage, no network. The day's record ([Record]) is kept by the app in memory, updated through the pure steps below.
 */
object SoloDay {
    // ---- the question ---------------------------------------------------------------------------------------------

    /** What was asked: [why] "why didn't it trade" (else "what did it do / how did it decide"), [market] an index named. */
    data class Q(val why: Boolean = false, val market: String? = null)

    const val LOCKED = "Unlock the phone for that, Boss."

    private fun norm(text: String) = Spaced.joined(text)

    private val SOLO = Regex(" (solo|solos|solo midday|soloo) ")
    private val WHY = Regex(" (why|kyu|kyun|kyon|kyo|kiyu|how come|kis wajah|kya wajah|what stopped|what kept) ")
    private val NEG = Regex(" (no|not|didnt|dont|doesnt|hasnt|havent|isnt|wasnt|wont|nahi|nahin|nhi|nai|na|never|zero) ")
    private val DO = Regex(" (trade|trades|traded|trading|enter|entered|entry|take|took|taken|buy|bought|liya|li|kiya|ki|chala|chali|" +
        "hua|hui|position|signal|signals) ")
    private val DID_WHAT = Regex(" (what|wat|whats) (did|has|have|was) (solo|solos) (do|done|buy|bought|trade|traded|see|saw|look at|read|pick|picked|" +
        "choose|chose|decide|decided|go for|end up) ")
    private val DID = Regex(" (did|has) solo (trade|traded|take a trade|buy|buy anything|enter|do anything|decide) ")
    private val HINDI = Regex(" solo ne (aaj )?(kya|kaise|kaisa|kaun sa|kaunsa|konsa|kis) |" +
        " solo (aaj )?(kya|kab) (karega|kar raha|dekh|dekhega|dekh raha|decide|trade) ")
    private val DECIDE = Regex(" (decide|decided|deciding|decision|decisions|choose|chose|chosen|choice|pick|picked|picks|select|selected|" +
        "faisla|skip|skipped|skips|pass over|passed over|candidate|candidates|compare|compared|strongest) ")
    private val WAIT = Regex(" (waiting for|waiting on|wait for|looking for|look at|looking at|watching|watch for|intezaar|intezar|wait kar) ")
    private val ASK = Regex(" (what|whats|wat|which|when|kya|kis|kab) ")
    private val WHEN = Regex(" when (will|does|is) solo (trade|decide|buy|look|enter|going to) ")
    /** "That trade" (one decision of the Thinking trail, as before), never the day's story. */
    private val SPECIFIC = Regex(" (that|this|those|these|the|wo|woh|vo|voh|ye|yeh|us|is) (trade|trades|position|setup|signal|one|idea|call|put|order) ")
    /**
     * Not this: another day, its exits and results, its switch, size or settings, its record or forward test alone, how it
     * is doing (Solo's status), a definition, another arm.
     */
    private val NOT = Regex(" (yesterday|kal|parso|last|week|weeks|month|months|tomorrow|this year|monday|tuesday|wednesday|thursday|friday|" +
        "exit|exited|exits|exiting|get out|got out|lose|lost|loss|losses|losing|win|won|winning|profit|pnl|p l|" +
        "switch|switched|turn on|turn off|on karo|off karo|band karo|chalu karo|band kar|chalu kar|enable|disable|setting|settings|" +
        "lot|lots|size|sizing|backtest|back test|record|forward test|on track|health|healthy|doing|kaisa chal|kaise chal|haal|" +
        "mean|means|meaning|define|" +
        "liquidity|orb|hero|sweep|fade|gold|news) ")

    /** Is Solo (midday)'s day asked - what it did, why it did not trade, how it decided, what it looks at? Null when not. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (!SOLO.containsMatchIn(t) || NOT.containsMatchIn(t) || SPECIFIC.containsMatchIn(t)) return null
        val why = WHY.containsMatchIn(t) && NEG.containsMatchIn(t) && DO.containsMatchIn(t)
        val what = DID_WHAT.containsMatchIn(t) || DID.containsMatchIn(t) || HINDI.containsMatchIn(t) || DECIDE.containsMatchIn(t) ||
            ASK.containsMatchIn(t) && WAIT.containsMatchIn(t) || WHEN.containsMatchIn(t)
        if (!why && !what) return null
        return Q(why, Market.mentioned(text).firstOrNull()?.name?.takeIf { it in SoloMidday.UNDERLYINGS })
    }

    // ---- the day's record (kept by the app, in memory; pure steps) -------------------------------------------------

    /** A guard that held the 12:00 decision back at [at]: [KILL_SWITCH], [LOSS_BREAKER], [CHECK_STOP] or [CHECK_UNREAD]. */
    data class Held(val at: LocalDateTime, val why: String)

    const val KILL_SWITCH = "kill_switch"
    const val LOSS_BREAKER = "loss_breaker"
    const val CHECK_STOP = "trade_check_stop"
    const val CHECK_UNREAD = "trade_check_unread"

    /** An index that signalled and was set aside at [at]: [why] a gate's code ("expiry_today", "expiry_unknown"), or the words. */
    data class Pass(val at: LocalDateTime, val underlying: String, val why: String)

    /** A thin strike Solo did not buy at [at]: [symbol] and [problem] ([StrikeLiquidity.problem]'s words). */
    data class Thin(val at: LocalDateTime, val underlying: String, val symbol: String, val problem: String)

    /** The paper buy at [at]: [symbol] at [strike] ([itm] steps in the money) filled at [price]; [moved] a thin strike moved it. */
    data class Bought(val at: LocalDateTime, val underlying: String, val symbol: String, val strike: Int, val itm: Int, val price: Double,
                      val moved: Boolean = false)

    /**
     * Solo's [day] as it decided it: a guard that [held] the decision back, the indices it [waited] for (their 11:59 minute
     * or ATR) and when, the minute it [decidedAt] and each index's [decisions], the indices passed over ([passes]), the
     * [thin] strikes, the entry window closing first ([late]), and what it [bought].
     */
    data class Record(
        val day: LocalDate, val held: Held? = null, val waited: Pair<LocalDateTime, List<String>>? = null,
        val decidedAt: LocalDateTime? = null, val decisions: List<SoloMidday.Decision> = emptyList(), val passes: List<Pass> = emptyList(),
        val thin: List<Thin> = emptyList(), val late: LocalDateTime? = null, val bought: Bought? = null,
    )

    /** At most this many thin strikes are kept a day (three indices, three strikes each). */
    const val MAX_THIN = 9

    /** [r] when it is [day]'s, else a fresh record for [day] (a new day's first note drops the earlier day's). */
    fun of(r: Record?, day: LocalDate): Record = if (r?.day == day) r else Record(day)

    fun held(r: Record?, at: LocalDateTime, why: String): Record = of(r, at.toLocalDate()).copy(held = Held(at, why))
    fun waited(r: Record?, at: LocalDateTime, underlyings: List<String>): Record = of(r, at.toLocalDate()).copy(waited = at to underlyings)
    fun decided(r: Record?, at: LocalDateTime, decisions: List<SoloMidday.Decision>): Record =
        of(r, at.toLocalDate()).copy(decidedAt = at, decisions = decisions)
    /** An index set aside (one reason an index: the latest). */
    fun passed(r: Record?, at: LocalDateTime, underlying: String, why: String): Record = of(r, at.toLocalDate()).let { x ->
        x.copy(passes = x.passes.filter { it.underlying != underlying } + Pass(at, underlying, why))
    }
    fun thin(r: Record?, at: LocalDateTime, underlying: String, symbol: String, problem: String): Record = of(r, at.toLocalDate()).let { x ->
        if (x.thin.size >= MAX_THIN) x else x.copy(thin = x.thin + Thin(at, underlying, symbol, problem))
    }
    fun late(r: Record?, at: LocalDateTime): Record = of(r, at.toLocalDate()).copy(late = at)
    fun bought(r: Record?, b: Bought): Record = of(r, b.at.toLocalDate()).copy(bought = b)

    // ---- the facts --------------------------------------------------------------------------------------------------

    /** Solo's early reads today: [started] (from 09:15), each index's daily [atrs] read so far, the [contracts] read. */
    data class Prefetch(val started: Boolean, val atrs: Map<String, Double> = emptyMap(), val contracts: Boolean = false)

    /**
     * One Solo (midday) trade: [underlying], [symbol], a call or put, [qty], [entry] premium, [entryMinute] minutes after
     * 09:15, the 12:00 index level [index], its index [stop] and 2R [target], the [atr] they came from, [why] it bought,
     * and once [closed] its [exitPrice], [net] after charges and [exit] words.
     */
    data class Trade(
        val day: LocalDate, val underlying: String, val symbol: String, val call: Boolean, val qty: Int, val entry: Double, val entryMinute: Int,
        val index: Double, val stop: Double, val target: Double, val atr: Double?, val why: String,
        val closed: Boolean = false, val exitPrice: Double? = null, val net: Double? = null, val exit: String? = null,
    )

    /**
     * What the answer is made from at [now]: [tradingDay] today a session, [on] Solo's switch (null: not read), [paused] why
     * it switched itself off, [record] today's record (kept in memory [since] the app last started), [prefetch] its early
     * reads, [trades] today's Solo (midday) trades, [earlier] a trade from an earlier day it is still seeing through,
     * [check] the forward test ([SoloMidday.judge] from its baseline), [thinCounts] how often a thin strike moved the strike
     * and passed an index over.
     */
    data class Facts(
        val now: LocalDateTime, val tradingDay: Boolean = true, val on: Boolean? = null, val paused: String? = null,
        val record: Record? = null, val since: LocalDateTime? = null, val prefetch: Prefetch? = null,
        val trades: List<Trade> = emptyList(), val earlier: Trade? = null, val check: SoloMidday.Check? = null,
        val thinCounts: Pair<Int, Int>? = null,
    )

    // ---- the words ------------------------------------------------------------------------------------------------

    private fun n(x: Double) = String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun lvl(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x).removeSuffix(".00")
    private fun px(x: Double) = String.format(Locale.ENGLISH, "₹%,.2f", x)
    private fun k2(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)
    private fun pct(x: Double) = String.format(Locale.ENGLISH, "%.0f", x * 100)
    private fun hhmm(t: LocalDateTime) = String.format(Locale.ENGLISH, "%02d:%02d", t.hour, t.minute)
    private fun list(xs: List<String>): String = when (xs.size) {
        0 -> ""; 1 -> xs[0]; 2 -> "${xs[0]} and ${xs[1]}"
        else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    }
    private fun s(n: Int) = if (n == 1) "" else "s"
    private val INDICES = list(SoloMidday.UNDERLYINGS)

    /** The rule it decides by, in one sentence. */
    val RULE: String = "At 12:00 (orders only until 12:03) it reads $INDICES - SENSEX is not traded - and buys the index that has " +
        "moved ${SoloMidday.MOVE_ATR} of its daily ATR or more from the open and closed in the outer quarter of the morning's range, the " +
        "strongest move if several do, ${SoloMidday.ITM_STEPS} strikes in the money, one lot, one trade a day, never on that index's expiry day."

    /** The research line ([ForwardCheck.SOLO]): the unseen period on its three indices. */
    fun research(): String = ForwardCheck.SOLO.let { e ->
        "Research, unseen period (Jul 2024 - Oct 2026) on $INDICES: ${e.trades} trades, net ${SoloMidday.rs(e.net)}" +
            (e.profitFactor?.let { ", profit factor ${String.format(Locale.ENGLISH, "%.2f", it)}" } ?: "") +
            " - about break-even, not proven."
    }

    /** The forward test: n of 60, net, the worst drawdown from its baseline against the bar. */
    fun forwardLine(c: SoloMidday.Check?): String {
        if (c == null) return "Its forward test could not be read just now."
        val r = c.record
        val head = "Forward test: ${minOf(r.trades, SoloMidday.FORWARD_TRADES)} of ${SoloMidday.FORWARD_TRADES} closed paper trades" +
            (if (r.trades > SoloMidday.FORWARD_TRADES) " (${r.trades} in all)" else "") + ", net ${SoloMidday.rs(r.net)}" +
            (r.perTrade?.let { " (${SoloMidday.rs(it)} a trade)" } ?: "")
        val dd = "worst drawdown ${SoloMidday.rs(c.drawdown)} " + (if (c.base.from > 0) "since you switched it back on" else "from its start") +
            " against the bar of ${SoloMidday.rs(-SoloMidday.FORWARD_MAX_DRAWDOWN)} (beyond it Solo switches itself off)"
        val v = when (c.verdict) {
            SoloMidday.Verdict.FAILED_DRAWDOWN -> " - that bar has failed."
            SoloMidday.Verdict.FAILED_NET -> " - and after ${SoloMidday.FORWARD_TRADES} trades it made ₹0 or less a trade: the net bar failed."
            SoloMidday.Verdict.PASSED -> " - it passed the bar set in advance (still paper only, not proven)."
            SoloMidday.Verdict.RUNNING -> "."
        }
        return "$head; $dd$v"
    }

    /** A guard's words. */
    fun heldWords(why: String): String = when (why) {
        KILL_SWITCH -> "the kill switch is on"
        LOSS_BREAKER -> "the day's loss breaker has tripped"
        CHECK_STOP -> "the trade check says STOP"
        CHECK_UNREAD -> "the trade check could not be read (a check that cannot be read is a no)"
        else -> why
    }

    /** One index's read at 12:00 in words; [rank] its place among those that signalled (1: the strongest; null: none). */
    fun indexWords(d: SoloMidday.Decision, rank: Int? = null, signalled: Int = 0): String {
        val s = d.signal ?: return SoloMidday.skip(d)
        val place = when {
            signalled <= 1 -> "the only one that signalled"
            rank == 1 -> "the strongest of the $signalled that signalled"
            else -> "number $rank of the $signalled that signalled"
        }
        return "${s.underlying} ${if (s.side > 0) "up" else "down"} ${n(s.move)} points from the open, ${k2(s.strength)} x its daily ATR " +
            "(${n(s.atr)}), closing in the ${if (s.side > 0) "top" else "bottom"} ${pct(s.position)}% of the morning's range - a signal, $place"
    }

    /** Why an index that signalled was set aside, in words ([p]'s reason; [thin] the day's thin strikes on it). */
    fun passWords(p: Pass, thin: List<Thin> = emptyList()): String {
        val first = thin.firstOrNull { it.underlying == p.underlying }
        return when {
            p.why == "expiry_today" -> "${p.underlying} expires today (Solo never trades an index on its expiry day)"
            p.why == "expiry_unknown" -> "${p.underlying}'s expiry could not be read from the contracts, so it was skipped"
            first != null && thin.filter { it.underlying == p.underlying }.any { it.problem == p.why } ->
                "${p.underlying}'s strikes near the one it wanted were all thin (${first.symbol}: ${first.problem}) - passed over"
            p.why.startsWith("no ") && p.why.contains("expiry") -> "${p.underlying}: no expiry is listed for it (no contract to buy)"
            p.why.startsWith("no ") && p.why.contains("strike") -> "${p.underlying}: ${p.why} (no contract)"
            p.why.startsWith("no price") -> "${p.underlying}: ${p.why} (data missing)"
            else -> "${p.underlying}: ${p.why}"
        }
    }

    /** The plan of a trade: entry, stop, breakeven lock, the 2R mark, 14:30. */
    fun planWords(t: Trade): String {
        val side = if (t.call) 1 else -1
        val lock = t.atr?.let { SoloMidday.lockTrigger(side, t.index, it) }
        return "Entry: ${t.underlying} at ${lvl(t.index)} (the 12:00 price). Stop: out on a 1-minute close ${if (t.call) "at or below" else "at or above"} " +
            "${lvl(t.stop)} (${SoloMidday.STOP_ATR} ATR)" + (lock?.let { "; the stop moves to breakeven (${lvl(t.index)}) once ${t.underlying} reaches ${lvl(it)}" } ?: "") +
            "; the 2R mark is ${lvl(t.target)} (no fixed target); out at 14:30 at the latest."
    }

    /** A trade's state: still open, or its exit and net. */
    fun tradeState(t: Trade): String = when {
        !t.closed -> "It is still open: watched every minute against those exits."
        else -> "Closed" + (t.exitPrice?.let { " at ${px(it)}" } ?: "") + ": " + (t.exit?.trim()?.trimEnd('.') ?: "exit not recorded") + ". " +
            (t.net?.let { "Net ${SoloMidday.rs(it)} after charges." } ?: "Its result could not be read.")
    }

    private fun hasStory(r: Record?): Boolean = r != null && (r.held != null || r.waited != null || r.decidedAt != null || r.late != null ||
        r.bought != null || r.passes.isNotEmpty() || r.thin.isNotEmpty())

    /** The answer at [Facts.now], from Solo's own records only. */
    fun answer(q: Q, f: Facts): String {
        val day = f.now.toLocalDate()
        val t = f.now.toLocalTime()
        val tail = listOfNotNull(forwardLine(f.check), thinLine(f.thinCounts), research())
        if (!f.tradingDay) return (listOf("No session today, Boss - Solo (midday) decides only on a trading day, once, at 12:00.") + tail).joinToString(" ")
        val r = f.record?.takeIf { it.day == day }
        val trade = f.trades.lastOrNull { it.day == day }
        val parts = ArrayList<String>()
        parts += when (f.on) {
            true -> "Solo (midday) is on today, Boss - paper only, not proven."
            false -> if (f.paused != null) "Solo (midday) is off, Boss: it switched itself off on its forward test's bar (switching it back on is your choice, in Jarvis settings)."
                else "Solo (midday) is off, Boss (it is switched on in Jarvis settings; paper only, not proven)."
            null -> "I could not read Solo's switch just now, Boss."
        }
        val beforeNoon = t.isBefore(SoloMidday.DECIDE_AT)
        when {
            hasStory(r) -> parts += story(r!!, trade, f)
            trade != null -> {
                parts += "It bought ${trade.symbol} at ${minute(trade)}: ${trade.why.trim()}"
                parts += planWords(trade)
                parts += tradeState(trade)
                parts += "(The read of each index at 12:00 is not in memory: the app restarted since.)"
            }
            f.earlier != null && !f.earlier.closed ->
                parts += "It is still seeing through its ${f.earlier.symbol} trade from ${f.earlier.day} (one Solo position at a time), so it makes no new decision until that is out."
            f.on == false -> parts += if (beforeNoon) "Switched off, it will not look at anything at 12:00 today." else "No 12:00 decision is on record today."
            beforeNoon -> parts += before(f)
            SoloMidday.inEntryWindow(t) -> parts += "It is deciding now (12:00-12:03). $RULE" + prefetchWords(f.prefetch, f.now)?.let { " $it" }.orEmpty()
            else -> parts += missed(f)
        }
        if (q.why && trade != null) parts.add(1, "It did trade today.")
        focus(q.market, r)?.let { parts += it }
        parts += tail
        return parts.joinToString(" ")
    }

    /** The index Boss named, on its own: what Solo read on it and what became of it (null: none named, or no decision). */
    fun focus(market: String?, r: Record?): String? {
        if (market == null || r?.decidedAt == null) return null
        val d = r.decisions.firstOrNull { it.underlying == market } ?: return "On $market: it was not read at 12:00."
        val signals = SoloMidday.rank(r.decisions.mapNotNull { it.signal })
        val what = when {
            r.bought?.underlying == market -> "it was the one bought"
            d.signal == null -> "no signal"
            else -> r.passes.firstOrNull { it.underlying == market }?.let { "set aside - " + passWords(it, r.thin) }
                ?: if (r.bought != null) "not taken - ${r.bought.underlying} was bought first" else if (r.late != null) "the window closed before the order" else "not taken"
        }
        return "On $market: " + indexWords(d, d.signal?.let { s -> signals.indexOf(s) + 1 }, signals.size) + " - $what."
    }

    private fun minute(t: Trade): String = String.format(Locale.ENGLISH, "%02d:%02d", (9 * 60 + 15 + t.entryMinute) / 60, (9 * 60 + 15 + t.entryMinute) % 60)

    private fun thinLine(c: Pair<Int, Int>?): String? = c?.takeIf { it.first + it.second > 0 }?.let { (moved, passed) ->
        "Thin strikes so far: the strike moved $moved time${s(moved)}, an index passed over $passed time${s(passed)}."
    }

    /** Before 12:00: what it will look at, the half-ATR each index needs, and whether the early reads are in. */
    private fun before(f: Facts): String {
        val w = StringBuilder("It has not decided yet: it decides once, between 12:00 and 12:03. ").append(RULE)
        prefetchWords(f.prefetch, f.now)?.let { w.append(' ').append(it) }
        return w.toString()
    }

    /** The early reads in words (null: nothing to say). */
    fun prefetchWords(p: Prefetch?, now: LocalDateTime): String? {
        if (now.toLocalTime().isBefore(java.time.LocalTime.of(9, 15))) return "Its early reads (each index's daily ATR and the contracts) start from 09:15."
        if (p == null) return null
        if (!p.started && p.atrs.isEmpty()) return "Its early reads (each index's daily ATR and the contracts) have not run yet; whatever is missing is read at 12:00."
        val have = SoloMidday.UNDERLYINGS.filter { it in p.atrs }
        val missing = SoloMidday.UNDERLYINGS.filter { it !in p.atrs }
        val atrs = if (have.isEmpty()) "no daily ATR read yet" else "daily ATR " + have.joinToString(", ") { u ->
            val a = p.atrs.getValue(u)
            "$u ${n(a)} (it needs ${n(SoloMidday.MOVE_ATR * a)} points from the open)"
        }
        return "Early reads: $atrs" + (if (missing.isNotEmpty() && have.isNotEmpty()) "; ${list(missing)} not read yet" else "") +
            "; the contracts ${if (p.contracts) "are read" else "are not read yet"}." +
            (if (missing.isNotEmpty() || !p.contracts) " Anything missing is read again at 12:00." else "")
    }

    /** After 12:03 with nothing on record: why there may be no decision. */
    private fun missed(f: Facts): String {
        val since = f.since?.takeIf { it.toLocalDate() == f.now.toLocalDate() }
        val end = f.now.toLocalDate().atTime(SoloMidday.LAST_ENTRY.plusMinutes(1))
        return when {
            since != null && !since.isBefore(end) ->
                "No 12:00 decision today: the app started at ${hhmm(since)}, after the 12:00-12:03 window, so Solo did not decide (no order after 12:03)."
            since != null && since.isAfter(f.now.toLocalDate().atTime(SoloMidday.DECIDE_AT)) ->
                "The app restarted at ${hhmm(since)}, during the window: today's decision is not in memory, and there is no Solo trade today."
            else -> "No 12:00 decision is on record today and no Solo trade: it decides only while the app's market watch runs between 12:00 " +
                "and 12:03 with the minute data in (no order after 12:03)."
        }
    }

    /** The day's decision from the record. */
    private fun story(r: Record, trade: Trade?, f: Facts): List<String> {
        val out = ArrayList<String>()
        if (r.decidedAt == null) {
            r.held?.let { out += "At ${hhmm(it.at)} its decision was held back: ${heldWords(it.why)}." }
            r.waited?.let { (at, us) -> out += "At ${hhmm(at)} it was waiting for the 11:59 minute or the daily ATR of ${list(us)} (it waits until 12:01, then decides with what it has)." }
        }
        if (r.decidedAt != null) {
            val signals = SoloMidday.rank(r.decisions.mapNotNull { it.signal })
            val reads = signals.mapIndexed { i, sg -> indexWords(r.decisions.first { it.signal === sg }, i + 1, signals.size) } +
                r.decisions.filter { it.signal == null }.sortedByDescending { it.strength ?: -1.0 }.map { indexWords(it) }
            out += "At ${hhmm(r.decidedAt)} it read ${if (r.decisions.size == 3) "all three" else "${r.decisions.size} indices"}: " + reads.joinToString("; ") + "."
            if (signals.isEmpty()) out += "None met both rules (${SoloMidday.MOVE_ATR} ATR from the open and a close in the outer quarter), so no trade today."
            val bought = r.bought
            val aside = r.passes.filter { it.underlying != bought?.underlying }
            if (aside.isNotEmpty()) out += "Set aside: " + aside.joinToString("; ") { passWords(it, r.thin) } + "."
            if (bought != null) {
                out += "It bought ${bought.symbol} (${bought.itm} in the money) on paper at ${px(bought.price)} at ${hhmm(bought.at)}" +
                    (signals.firstOrNull()?.takeIf { it.underlying != bought.underlying }?.let { " - ${it.underlying} was stronger but was set aside" } ?: "") + "."
                if (bought.moved) r.thin.firstOrNull { it.underlying == bought.underlying }?.let {
                    out += "The strike ${SoloMidday.ITM_STEPS} in the money was thin (${it.symbol}: ${it.problem}): it bought the next one, as the research's fallback."
                }
                val weaker = signals.dropWhile { it.underlying != bought.underlying }.drop(1).filter { s -> aside.none { it.underlying == s.underlying } }
                if (weaker.isNotEmpty()) out += "Not taken as weaker (one Solo position at a time): " +
                    weaker.joinToString(", ") { "${it.underlying} (${k2(it.strength)} ATR)" } + "."
            } else if (r.late != null) {
                out += "The 12:00-12:03 window closed (${hhmm(r.late)}) before the order could go in - no order after 12:03, so no trade today."
            } else if (signals.isNotEmpty()) {
                out += "Every index that signalled was set aside, so no trade today."
            }
        } else if (r.late != null) {
            out += "The 12:00-12:03 window closed (${hhmm(r.late)}) before the order could go in - no order after 12:03, so no trade today."
        }
        if (trade != null) {
            if (r.bought == null) out += "It bought ${trade.symbol} at ${minute(trade)}: ${trade.why.trim()}"
            out += planWords(trade)
            out += tradeState(trade)
        } else if (r.bought != null) {
            out += "Its trade record could not be read just now."
        }
        if (r.decidedAt == null && r.bought == null && r.late == null) {
            val t = f.now.toLocalTime()
            out += when {
                SoloMidday.inEntryWindow(t) -> "It is still within its 12:00-12:03 window."
                t.isBefore(SoloMidday.DECIDE_AT) -> RULE
                else -> "The window closed with no trade today (no order after 12:03)."
            }
        }
        return out
    }
}
