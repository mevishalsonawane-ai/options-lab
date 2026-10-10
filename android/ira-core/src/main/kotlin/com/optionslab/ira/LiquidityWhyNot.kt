package com.optionslab.ira

import com.optionslab.engine.orb.Arm
import com.optionslab.engine.orb.LiquidityRules
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * Why Liquidity 15+5 did or did not trade today (Boss, 06 Oct 2026): "why no liquidity trade today?", "why didn't
 * liquidity trade", "liquidity ne trade kyu nahi liya", "what is liquidity waiting for". Answered from the arm's own
 * records of the day, book by book (BANKNIFTY 15 and 5, FINNIFTY 30 and 5, MIDCPNIFTY 15 and 5 - [LiquidityRules.BOOKS]): its switch, the day's
 * stop ([DayStop]) and its entry hours; the bars it decided on and every break it saw with why it was not taken - the room
 * rule ([LiquidityRules.hasRoom]: the next level too close), Bot settings or the one-position-a-side rule refusing it, a
 * signal left unapproved, no option price, candles missing or still loading; the trades it did take (and where to ask
 * about them); and what would trigger its next entry - the nearest pool on a swing on each side and how far it is
 * ([LiquidityMap]'s read of the same levels the arm decides with).
 *
 * Facts from the arm's records only - nothing here arms, stops, places, closes or changes anything. Pure: no clock, no
 * storage, no network.
 */
object LiquidityWhyNot {
    // ---- the question ---------------------------------------------------------------------------------------------

    /** What was asked: [underlyings] the indices (all when none is named), [waiting] "what is it waiting for" (else "why no trade"). */
    data class Q(val underlyings: List<String> = LiquidityRules.UNDERLYINGS, val waiting: Boolean = false)

    const val LOCKED = "Unlock the phone for that, Boss."

    private fun norm(text: String) = Spaced.joined(text)

    private val LIQ = Regex(" (liquidity|liquiditys|liqudity|liquidty) ")
    private val WHY = Regex(" (why|kyu|kyun|kyon|kyo|kiyu|how come|kis wajah|kya wajah|what stopped|what kept) ")
    private val NEG = Regex(" (no|not|didnt|dont|doesnt|hasnt|havent|isnt|wasnt|wont|nahi|nahin|nhi|nai|na|never|zero) ")
    private val DO = Regex(" (trade|trades|traded|trading|enter|entered|entering|entry|entries|fire|fired|trigger|triggered|take|took|taken|" +
        "buy|bought|liya|li|liye|kiya|ki|kiye|chala|chali|chale|hua|hui|position|positions|signal|signals) ")
    private val SKIP = Regex(" (skip|skipped|skipping|skips|pass on|passed on|chhoda|choda|chhodi|chodi|chhod diya) ")
    private val ASK = Regex(" (what|whats|kya|kis|kisi|which|wat) ")
    private val WAITING = Regex(" (waiting for|waiting on|wait for|wait kar|wait ho|intezaar|intezar|intazar|looking for|watching for) ")
    private val NEXT = Regex(" (what (would|will|does it need to|needs to) (make|trigger|happen)|what does (the )?liquidity( bot| arm)? need|" +
        "when will (the )?liquidity( bot| arm)? (trade|enter|buy|fire)|liquidity( bot| arm)? kab (trade|entry|enter)) ")
    /** Its levels on their own (LiquidityMap: "which level is liquidity waiting for"), not the day's story. */
    private val LEVEL = Regex(" (level|levels|pool|pools|zone|zones|swing|swings|map) ")
    /**
     * Not this: an exit, a win or loss (BotTrades, ArmDay), its size, health, switch or a change, the backtest, another
     * day (its record: LiquidityRecord; tomorrow's plan), a definition, another arm named.
     */
    private val NOT = Regex(" (exit|exited|exits|exiting|get out|got out|square off|squared off|lose|lost|loss|losses|losing|win|won|winning|" +
        "profit|pnl|p l|lot|lots|size|sizing|health|healthy|switch on|switch off|turn on|turn off|disarm|arm it|band karo|chalu karo|set|change|" +
        "backtest|back test|tomorrow|yesterday|kal|week|weeks|month|months|this year|mean|means|meaning|define|what is a|kya hota|" +
        "jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|nov|november|dec|december|" +
        "orb|solo|hero|sweep|range fade|fade|nifty 50|sensex) ")
    private val BANK = Regex(" (bank ?nifty|banknifty|bnf|nifty bank|bank) ")
    private val FIN = Regex(" (fin ?nifty|finnifty|finnfty|nifty fin|nifty financial|fin) ")
    private val MID = Regex(" (midcap nifty|midcpnifty|midcp nifty|mid cap nifty|midcap|midcp|nifty mid select|nifty midcap select|midcap select) ")

    /** Is why Liquidity 15+5 did or did not trade today asked (or what it waits for)? Null when not. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (!LIQ.containsMatchIn(t) || NOT.containsMatchIn(t)) return null
        val why = WHY.containsMatchIn(t) && (NEG.containsMatchIn(t) && DO.containsMatchIn(t) || SKIP.containsMatchIn(t))
        val waiting = !why && !LEVEL.containsMatchIn(t) && (ASK.containsMatchIn(t) && WAITING.containsMatchIn(t) || NEXT.containsMatchIn(t))
        if (!why && !waiting) return null
        val bank = BANK.containsMatchIn(t)
        val fin = FIN.containsMatchIn(t)
        val mid = MID.containsMatchIn(t)
        val unds = if (bank || fin || mid) listOfNotNull("BANKNIFTY".takeIf { bank }, "FINNIFTY".takeIf { fin }, "MIDCPNIFTY".takeIf { mid })
            else LiquidityRules.UNDERLYINGS
        return Q(unds, waiting)
    }

    // ---- the records ----------------------------------------------------------------------------------------------

    /**
     * One decision of a book ([book] its source, "liquidity15"...) as the arm made it at [at]: [verdict] the status it gave
     * ("no_liquidity_break", "liquidity_no_room", "entered", "guard_refused: ..."), and for a bar it decided on, the bar's
     * start [bar], and for a break the [side] (+1 up, -1 down), the [level] broken, the next level [target] and the bar's [close].
     */
    data class Decision(val at: LocalDateTime, val book: String, val verdict: String, val bar: LocalDateTime? = null,
                        val side: Int? = null, val level: Double? = null, val target: Double? = null, val close: Double? = null)

    /** One book now: [armed] its switch, [status] its last verdict, [decided] the bars it decided on today. */
    data class BookState(val book: String, val armed: Boolean, val status: String = "", val decided: Int = 0)

    /**
     * What the answer is made from at [now]: [tradingDay] today a session, [armed] the arm's switch (null: not read),
     * [books] each book's state, [stopped] the day's stop (null: not stopped), [trades] today's Liquidity trades (any book),
     * [decisions] today's decisions (the in-memory record, kept [since] the app last started), [reads] each book's levels.
     */
    data class Facts(
        val now: LocalDateTime, val tradingDay: Boolean = true, val armed: Boolean? = null, val books: List<BookState> = emptyList(),
        val stopped: DayStop.Why? = null, val trades: List<BotTrades.Trade> = emptyList(), val decisions: List<Decision> = emptyList(),
        val since: LocalDateTime? = null, val reads: List<LiquidityMap.Read> = emptyList(),
    )

    /** What a verdict says, in kinds. */
    enum class Kind {
        ENTERED, AWAITING, LAPSED, SKIPPED_BY_YOU, NO_ROOM, NO_BREAK, OUTSIDE_HOURS, HOLDING, STOPPED, SQUARED_OFF, LOADING, NO_DATA,
        NO_CONTRACT, NO_QUOTE, GUARD, EXPOSURE, ORDER_REFUSED, REFUSED, ERROR, OTHER
    }

    /** The verdict a lapsed approval is recorded with (the arm drops the signal and says so). */
    const val LAPSED = "approval_lapsed"

    fun kind(verdict: String): Kind = when {
        verdict.startsWith("entered") -> Kind.ENTERED
        verdict == "awaiting_approval" -> Kind.AWAITING
        verdict == LAPSED -> Kind.LAPSED
        verdict == "skipped_by_you" -> Kind.SKIPPED_BY_YOU
        verdict == "liquidity_no_room" -> Kind.NO_ROOM
        verdict == "no_liquidity_break" -> Kind.NO_BREAK
        verdict == "liquidity_outside_entry_hours" -> Kind.OUTSIDE_HOURS
        verdict == "holding" -> Kind.HOLDING
        verdict == "stopped_for_today" -> Kind.STOPPED
        verdict == "flat_after_square_off" -> Kind.SQUARED_OFF
        verdict == "liquidity_history_loading" -> Kind.LOADING
        verdict == "no_index_data" -> Kind.NO_DATA
        verdict == "no_contract" -> Kind.NO_CONTRACT
        verdict == "refused: no quote" -> Kind.NO_QUOTE
        verdict.startsWith("guard_refused") -> Kind.GUARD
        AutoSide.refused(verdict) -> Kind.EXPOSURE
        verdict.startsWith("order_refused") -> Kind.ORDER_REFUSED
        verdict.startsWith("refused") -> Kind.REFUSED
        verdict.startsWith("error") -> Kind.ERROR
        else -> Kind.OTHER
    }

    /** A break it saw and did not take (each said with its reason). */
    private val NOT_TAKEN = setOf(Kind.NO_ROOM, Kind.LAPSED, Kind.SKIPPED_BY_YOU, Kind.NO_CONTRACT, Kind.NO_QUOTE, Kind.GUARD, Kind.EXPOSURE,
        Kind.ORDER_REFUSED, Kind.REFUSED, Kind.STOPPED, Kind.ERROR, Kind.OTHER)
    private val REFUSALS = setOf(Kind.NO_CONTRACT, Kind.NO_QUOTE, Kind.GUARD, Kind.EXPOSURE, Kind.ORDER_REFUSED, Kind.REFUSED)
    private val TROUBLE = setOf(Kind.LOADING, Kind.NO_DATA, Kind.ERROR)

    /** At most this many breaks are told one by one for a book (the rest counted). */
    const val MAX_LISTED = 3

    // ---- the words ------------------------------------------------------------------------------------------------

    private fun n(x: Double) = String.format(Locale.ENGLISH, "%,.0f", x)
    private fun pts(x: Double) = String.format(Locale.ENGLISH, "%.0f", abs(x))
    private fun hm(t: LocalTime) = String.format(Locale.ENGLISH, "%02d:%02d", t.hour, t.minute)
    private fun hm(t: LocalDateTime) = hm(t.toLocalTime())
    private fun plural(k: Int, one: String, many: String = one + "s") = "$k ${if (k == 1) one else many}"
    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    private val HOURS = "${hm(LiquidityRules.FIRST_ENTRY)}-${hm(LiquidityRules.LAST_ENTRY)}"

    /** A book's source as it is now (a renamed one under its new name). */
    private fun sourceOf(source: String) = LiquidityRules.RENAMED[source] ?: source
    private fun armOf(source: String): Arm? = LiquidityRules.BOOKS.firstOrNull { it.source == sourceOf(source) }

    /** "BankNifty 15-min". */
    fun bookName(arm: Arm): String = "${LiquidityMap.indexName(LiquidityRules.underlyingOf(arm))} ${LiquidityRules.minutesOf(arm)}-min"

    private fun exitWords(why: String?): String = when (why) {
        "stop" -> "its 15% stop"
        "index_stop" -> "its index stop"
        "time_stop" -> "its 20-minute time stop"
        "next_liquidity" -> "the next level (its target)"
        "failed_break" -> "a failed break"
        "new_liquidity" -> "new liquidity forming"
        "session_end" -> "the 15:10 exit"
        "closed_by_you", "operator_stop" -> "your close"
        null -> "an exit"
        else -> why.replace('_', ' ')
    }

    private fun tradeWords(t: BotTrades.Trade): String {
        val right = if (t.right == "CE") "a call" else "a put"
        val name = armOf(t.source)?.let(::bookName) ?: BotTrades.label(t.source)
        val where = if (t.live) " at Zerodha" else ""
        val out = if (t.exit == null) "still open" else "out" + (t.exitTime?.let { " at ${hm(it)}" } ?: "") + " on ${exitWords(t.why)}"
        return "$name bought $right$where at ${hm(t.entryTime)}, $out"
    }

    /** "the 10:15 break above 54,180" (or "the 10:15 signal" when its level is not known). */
    private fun breakWords(d: Decision): String {
        val at = d.bar?.let { "the ${hm(it)} " } ?: "a "
        val side = d.side
        val level = d.level
        return if (side == null || level == null) "${at}signal" else "${at}break ${if (side > 0) "above" else "below"} ${n(level)}"
    }

    /** Why one break was not taken, as a sentence. */
    fun notTakenWords(d: Decision, underlying: String): String {
        val what = breakWords(d)
        val v = d.verdict
        return when (kind(v)) {
            Kind.NO_ROOM -> {
                val need = pts(LiquidityRules.MIN_ROOM_STOPS * LiquidityRules.indexStopPoints(underlying))
                val target = d.target
                val close = d.close
                val side = d.side
                if (target != null && close != null && side != null)
                    "Skipped $what: the next level ahead, ${n(target)}, was only ${pts(side * (target - close))} pts from the close (it needs $need)"
                else "Skipped $what: the next level ahead was closer than $need pts (the room rule)"
            }
            Kind.LAPSED -> cap("$what waited for your approval and lapsed - nothing was bought")
            Kind.SKIPPED_BY_YOU -> "You skipped $what"
            Kind.NO_CONTRACT -> cap("$what was not entered: its option contract could not be loaded")
            Kind.NO_QUOTE -> cap("$what was not entered: there was no option price to buy at")
            Kind.GUARD -> cap("$what was refused by Bot settings (your daily limits): ${v.removePrefix("guard_refused").removePrefix(":").trim()}")
            Kind.EXPOSURE -> cap("$what was not entered: " + AutoSide.describe(v).removePrefix("Not entered: ").trimEnd('.'))
            Kind.ORDER_REFUSED -> cap("$what was not entered: the order was refused (${v.removePrefix("order_refused").removePrefix(":").trim()})")
            Kind.REFUSED -> cap("$what was refused: ${v.removePrefix("refused").removePrefix(":").trim()}")
            Kind.STOPPED -> cap("$what came after the bot was stopped for today - not entered")
            Kind.ERROR -> cap("$what could not be checked (an error on that pass)")
            Kind.AWAITING -> cap("$what asked for your approval")
            Kind.ENTERED -> cap("$what was entered")
            else -> cap("$what: ${v.replace('_', ' ')}")
        }
    }

    /** The book's status now, said only when it explains the moment (null otherwise). */
    private fun nowWords(status: String): String? = when (kind(status)) {
        Kind.HOLDING -> "Now holding its position - one position per chart, so no new entry until it exits"
        Kind.AWAITING -> "Now a signal waits for your approval (Home)"
        Kind.STOPPED -> "Now stopped for today"
        Kind.SQUARED_OFF -> "Done for the day (15:10)"
        Kind.LOADING -> "Now its candles are still loading - the levels need ${LiquidityMap.MIN_BARS} closed bars"
        Kind.NO_DATA -> "Now the index candles cannot be read, so it cannot decide"
        Kind.ERROR -> "Its last pass could not check the chart"
        else -> null
    }

    /** What would trigger the book's next entry, from its levels [r] (null: not read). */
    fun nextWords(r: LiquidityMap.Read?, now: LocalDateTime): String {
        if (r == null) return "I could not read its levels just now"
        if (r.state == LiquidityMap.State.NO_DATA) return "No candles to read its levels from just now"
        if (r.state == LiquidityMap.State.LOADING) return "Its levels can't be read yet: ${r.bars} closed bars of the ${LiquidityMap.MIN_BARS} they need"
        val price = r.price ?: return "I could not read its levels just now"
        fun side(s: LiquidityMap.Side?, up: Boolean): String {
            val dir = if (up) "above" else "below"
            val t = s?.trigger ?: return "no pool sits on a swing $dir"
            val d = (if (up) 1 else -1) * (t.edge - price)
            val away = if (d >= 0) "${pts(d)} pts ${if (up) "up" else "down"}" else "price already ${pts(d)} pts past it, its bar not yet closed"
            val right = if (up) "call" else "put"
            val target = s.target
            return if (target == null || s.enough) "a ${r.minutes}-min close $dir ${n(t.edge)} ($away) buys a $right"
            else "a close $dir ${n(t.edge)} ($away) would be skipped: only ${pts(s.room ?: 0.0)} pts to the next level ${n(target)} " +
                "(needs ${pts(LiquidityRules.MIN_ROOM_STOPS * LiquidityRules.indexStopPoints(r.underlying))})"
        }
        val asOf = r.priceAt?.takeIf { !LiquidityMap.fresh(r, now) }?.let {
            if (it.toLocalDate() != now.toLocalDate()) " as of ${it.toLocalDate().dayOfMonth} ${it.month.name.lowercase(Locale.ENGLISH).replaceFirstChar { c -> c.uppercase() }.take(3)} ${hm(it)}"
            else " as of ${hm(it)}"
        } ?: ""
        val t = now.toLocalTime()
        val lead = if (t.isBefore(LiquidityRules.FIRST_ENTRY) || t.isAfter(LiquidityRules.LAST_ENTRY)) "When its entry hours open again, next" else "Next"
        return "$lead (price ${n(price)}$asOf): ${side(r.above, true)}; ${side(r.below, false)}"
    }

    /**
     * The nearest trigger with room still ahead of the price across [reads] (one already passed, its bar not yet closed, is
     * not "next"): (book, side), or null.
     */
    internal fun nearest(reads: List<LiquidityMap.Read>): Pair<LiquidityMap.Read, LiquidityMap.Side>? =
        reads.filter { it.state == LiquidityMap.State.OK && it.price != null }.flatMap { r ->
            listOfNotNull(r.above, r.below).filter { it.trigger != null && (it.target == null || it.enough) }.map { r to it }
        }.filter { (r, s) -> s.side * (s.trigger!!.edge - r.price!!) >= 0 }.minByOrNull { (r, s) -> abs(s.trigger!!.edge - r.price!!) }

    /**
     * The nearest trigger with room still ahead of the price across [reads] ([nearest]) in words - "BankNifty 5-min, a close
     * above 54,180 - 60 pts away (it would buy a call)" - or null when there is none (the opening read says it too).
     */
    fun nearestWords(reads: List<LiquidityMap.Read>): String? = nearest(reads)?.let { (r, s) ->
        val tr = s.trigger!!
        val d = s.side * (tr.edge - r.price!!)
        if (d < 0) null
        else "${LiquidityMap.indexName(r.underlying)} ${r.minutes}-min, a close ${if (s.side > 0) "above" else "below"} " +
            "${n(tr.edge)} - ${pts(d)} pts away (it would buy a ${if (s.side > 0) "call" else "put"})"
    }

    /**
     * Each bar's last decision, by book and bar, in the order the bars were first decided: a bar first recorded
     * "awaiting_approval" and later lapsed, skipped or entered is told by how it ended.
     */
    private fun lastPerBar(ds: List<Decision>): List<Decision> =
        ds.filter { it.bar != null }.sortedBy { it.at }.associateBy { sourceOf(it.book) to it.bar }.values.toList()

    /** One book's story today: its trades, the bars it decided, each break not taken and why, data trouble, now, and next. */
    fun bookStory(arm: Arm, f: Facts): String {
        val src = arm.source
        val und = LiquidityRules.underlyingOf(arm)
        val mine = f.decisions.filter { sourceOf(it.book) == src }.sortedBy { it.at }
        val trades = f.trades.filter { sourceOf(it.source) == src }.sortedBy { it.entryTime }
        val st = f.books.firstOrNull { sourceOf(it.book) == src }
        val out = ArrayList<String>()
        if (trades.isNotEmpty()) out += "Traded: " + trades.joinToString("; ") { tradeWords(it) }
        val barred = lastPerBar(mine)
        val watched = maxOf(st?.decided ?: 0, barred.mapNotNull { it.bar }.distinct().size)
        val missed = barred.filter { kind(it.verdict) in NOT_TAKEN }
        val pending = barred.filter { kind(it.verdict) == Kind.AWAITING }
        val inHours = barred.count { kind(it.verdict) != Kind.OUTSIDE_HOURS }
        if (watched > 0) out += "Decided on ${plural(watched, "bar")} today" + when {
            missed.isNotEmpty() || pending.isNotEmpty() || trades.isNotEmpty() -> ""
            barred.isNotEmpty() && inHours == 0 -> ", all outside its entry hours ($HOURS)"
            else -> ": none closed through a liquidity pool sitting on a swing zone"
        }
        pending.take(MAX_LISTED).forEach { out += notTakenWords(it, und) + " - it is waiting for you (Home)" }
        missed.take(MAX_LISTED).forEach { out += notTakenWords(it, und) }
        if (missed.size > MAX_LISTED) out += "And ${plural(missed.size - MAX_LISTED, "more break")} not taken (${missed.drop(MAX_LISTED).groupBy { kind(it.verdict) }
            .entries.joinToString(", ") { (k, v) -> "${v.size} ${reasonOf(k)}" }})"
        val trouble = mine.filter { it.bar == null && kind(it.verdict) in TROUBLE }
        if (trouble.isNotEmpty()) out += "Data: " + trouble.groupBy { kind(it.verdict) }.entries.joinToString("; ") { (k, v) ->
            val times = v.map { hm(it.at) }.distinct()
            "${troubleOf(k)} at ${times.take(3).joinToString(", ")}" + (if (times.size > 3) " and ${times.size - 3} more times" else "")
        }
        val stoppedAt = mine.firstOrNull { it.bar == null && kind(it.verdict) == Kind.STOPPED }
        if (stoppedAt != null && f.stopped == null) out += "Stood still from ${hm(stoppedAt.at)} while the bot was stopped for the day"
        if (f.armed != false) st?.status?.let(::nowWords)?.let { out += it }
        if (watched == 0 && mine.isEmpty() && trades.isEmpty() && f.armed != false && !f.now.toLocalTime().isBefore(LiquidityRules.FIRST_ENTRY))
            out += "No decisions of it on record today"
        out += nextWords(f.reads.firstOrNull { it.underlying == und && it.minutes == LiquidityRules.minutesOf(arm) }, f.now)
        return "${bookName(arm)}: " + out.joinToString(". ") + "."
    }

    private fun reasonOf(k: Kind): String = when (k) {
        Kind.NO_ROOM -> "for no room"
        Kind.LAPSED -> "lapsed unapproved"
        Kind.SKIPPED_BY_YOU -> "skipped by you"
        Kind.GUARD -> "refused by Bot settings"
        Kind.EXPOSURE -> "refused by the one-position-a-side rule"
        Kind.NO_QUOTE, Kind.NO_CONTRACT -> "with no option to buy"
        Kind.STOPPED -> "after the day's stop"
        else -> "refused"
    }

    private fun troubleOf(k: Kind): String = when (k) {
        Kind.LOADING -> "candles still loading"
        Kind.NO_DATA -> "index candles missing"
        else -> "a pass that could not check the chart"
    }

    /** Jarvis's answer to [q] from the day's [f]acts. */
    fun answer(q: Q, f: Facts): String {
        val books = LiquidityRules.BOOKS.filter { LiquidityRules.underlyingOf(it) in q.underlyings }.ifEmpty { LiquidityRules.BOOKS }
        val day = f.now.toLocalDate()
        if (!f.tradingDay) return "Today isn't a trading day, Boss, so Liquidity 15+5 had nothing to trade. " +
            "It decides on BankNifty, FinNifty and Midcap Nifty bars in market hours, entries $HOURS."
        val scope = books.map { it.source }.toSet()
        val trades = f.trades.filter { sourceOf(it.source) in scope && it.entryTime.toLocalDate() == day }
        val decisions = f.decisions.filter { sourceOf(it.book) in scope && it.at.toLocalDate() == day }
        val facts = f.copy(trades = trades, decisions = decisions)
        val t = f.now.toLocalTime()
        val before = t.isBefore(LiquidityRules.FIRST_ENTRY)
        val after = t.isAfter(LiquidityRules.LAST_ENTRY)
        val hours = when {
            before -> "Its entry hours ($HOURS) haven't begun yet."
            after -> "Its entry hours ($HOURS) are over for today."
            else -> "It is in its entry hours now (to ${hm(LiquidityRules.LAST_ENTRY)})."
        }
        val head = ArrayList<String>()
        val seen = lastPerBar(decisions)
        val awaiting = seen.count { kind(it.verdict) == Kind.AWAITING }
        val missed = seen.filter { kind(it.verdict) in NOT_TAKEN }
        if (trades.isNotEmpty()) {
            head += "Liquidity 15+5 did trade today, Boss: ${plural(trades.size, "trade")} - " +
                trades.sortedBy { it.entryTime }.joinToString("; ") { tradeWords(it) } + "."
            head += "\"Liquidity trades today\" walks through each one, and \"why did the liquidity bot exit\" explains the exits."
            if (q.waiting && !after) head += "For its next entry it waits for a close through a liquidity pool that sits on a swing zone, with room to the next level."
        } else {
            val reasons = ArrayList<String>()
            when {
                f.armed == false -> head += "Liquidity 15+5 is switched off, Boss, so it takes no trades - it trades only while armed (Strategies)." +
                    (if (decisions.isNotEmpty()) " It was armed earlier today: what it saw is below." else "")
                f.stopped != null -> head += "No Liquidity trade today, Boss: ${DayStop.line(f.stopped)}"
                q.waiting -> head += "Boss, Liquidity 15+5 waits for a close through a liquidity pool that sits on a swing zone, with room to the next " +
                    "level (one index stop: BankNifty 30 pts, FinNifty 15, Midcap Nifty 8) - then it buys the call (up) or put (down)."

                before && decisions.isEmpty() -> head += "No Liquidity trade yet, Boss - it is early."
                else -> {
                    val noRoom = missed.count { kind(it.verdict) == Kind.NO_ROOM }
                    val refused = missed.count { kind(it.verdict) in REFUSALS }
                    val lapsed = missed.count { kind(it.verdict) == Kind.LAPSED || kind(it.verdict) == Kind.SKIPPED_BY_YOU }
                    if (noRoom > 0) reasons += "${plural(noRoom, "break was", "breaks were")} skipped because the next level ahead was too close (the room rule)"
                    if (refused > 0) reasons += "${plural(refused, "break was", "breaks were")} refused before an order"
                    if (lapsed > 0) reasons += "${plural(lapsed, "signal")} waited for your approval and ${if (lapsed == 1) "was" else "were"} not taken"
                    val others = missed.size - noRoom - refused - lapsed
                    if (others > 0) reasons += "${plural(others, "break was", "breaks were")} not entered - see below"
                    if (awaiting > 0) reasons += if (awaiting == 1) "a signal is waiting for your approval" else "$awaiting signals are waiting for your approval"
                    val watched = maxOf(seen.size, f.books.filter { sourceOf(it.book) in scope }.sumOf { it.decided })
                    if (missed.isEmpty() && awaiting == 0 && watched > 0) reasons += "no close took a liquidity pool sitting on a swing zone - the only break it trades"
                    if (decisions.any { it.bar == null && kind(it.verdict) in TROUBLE }) reasons += "its candles were missing or still loading at times"
                    if (watched == 0 && (decisions.isEmpty() || reasons.isEmpty())) reasons += "I have no record of it deciding on a bar today" +
                        (if (f.armed == null) " (and I couldn't read its switch)" else "")
                    head += "No Liquidity trade today, Boss: " + reasons.joinToString("; ") + "."
                }
            }
            nearestWords(f.reads.filter { it.underlying in q.underlyings })?.let { head += "Nearest trigger: $it." }
        }
        head += hours
        val lines = ArrayList<String>()
        lines += head.joinToString(" ")
        books.forEach { lines += bookStory(it, facts) }
        val since = f.since
        if (since != null && since.toLocalDate() == day && since.toLocalTime().isAfter(LiquidityRules.SESSION_OPEN))
            lines += "(My record of its bars runs from ${hm(since)}, when the app last started; earlier bars are counted but not described.)"
        lines += "From the arm's own records - information only: nothing was armed, placed or changed."
        return lines.joinToString("\n")
    }
}
