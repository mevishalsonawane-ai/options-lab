package com.optionslab.ira

import com.optionslab.engine.orb.HeroRules
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * The expiry-day Hero arm's day in words (Boss, 06 Oct 2026): "what did hero do today", "why no hero trade", "is today a
 * hero day", "hero ne aaj kya kiya", "when is the next hero day". Answered from Hero's own records: whether today is a NIFTY
 * expiry day (from the contract list on the phone - never the weekday; [HeroRules.isExpiryDay]) and, if not, the next one;
 * its switch (or that it disarmed itself); its window (the straddle's low from 12:00, entries 13:30-14:45, out by 15:05);
 * what it read (the ATM straddle, its two legs and its low since 12:00, NIFTY's 15-minute move); each minute it decided
 * today and the verdict; the trade with its entry, its F07 exits (half at 5x, the rest at 20x or 15:05, the -60% stop) and
 * its net - or why there was none (not armed, no signal, a signal with no Rs 1-5 option, one lot over the Rs 5,000 budget,
 * stale data, outside its window, the app not running); then its paper record so far and that it is paper only and not
 * proven ([ForwardCheck.HERO]).
 *
 * Facts from Hero's own records only - nothing here arms, places, closes or changes anything. Pure: no clock, no storage,
 * no network. The day's decisions ([Decision]) are kept by the app in memory, never read by a decision, an exit or an order.
 */
object HeroDay {
    // ---- the question ---------------------------------------------------------------------------------------------

    /** [WHAT] what it did / how it decided, [WHY] why it did not trade, [DAY] is today its day, [NEXT] when is its next day. */
    enum class Kind { WHAT, WHY, DAY, NEXT }

    data class Q(val kind: Kind = Kind.WHAT)

    const val LOCKED = "Unlock the phone for that, Boss."

    private fun norm(text: String) = Spaced.joined(text)

    private val HERO = Regex(" (hero|heros|heroes) ")
    /** Hero MotoCorp (the stock, its bikes): never the arm. */
    private val MOTO = Regex(" (motocorp|moto|motor|motors|bike|bikes|scooter|scooters|share|shares|stock|stocks|honda|splendor) ")
    private val WHY = Regex(" (why|kyu|kyun|kyon|kyo|kiyu|how come|kis wajah|kya wajah|what stopped|what kept) ")
    private val NEG = Regex(" (no|not|didnt|dont|doesnt|hasnt|havent|isnt|wasnt|wont|nahi|nahin|nhi|nai|na|never|zero) ")
    private val DO = Regex(" (trade|trades|traded|trading|enter|entered|entry|take|took|taken|buy|bought|liya|li|kiya|ki|chala|chali|" +
        "hua|hui|position|signal|signals|fire|fired|firing) ")
    private val DID_WHAT = Regex(" (what|wat|whats) (did|has|have|was) (the )?(hero|heros)( arm)? (do|done|buy|bought|trade|traded|see|saw|" +
        "look at|read|pick|picked|choose|chose|decide|decided|end up) ")
    private val DID = Regex(" (did|has|is|will|does) (the )?hero( arm)? (trade|traded|trading|take a trade|buy|buy anything|bought|enter|entered|" +
        "do anything|fire|fired|firing|decide|decided) ")
    private val HINDI = Regex(" hero ne (aaj )?(kya|kaise|kaisa|kaun sa|kaunsa|konsa|kis) |" +
        " hero (aaj )?(kya|kab) (karega|kar raha|dekh|dekhega|dekh raha|decide|trade|chalega|lega) ")
    private val DECIDE = Regex(" (decide|decided|deciding|decision|decisions|choose|chose|chosen|pick|picked|faisla|skip|skipped|skips) ")
    private val WAIT = Regex(" (waiting for|waiting on|wait for|looking for|look at|looking at|watching|watch for|intezaar|intezar|wait kar) ")
    private val ASK = Regex(" (what|whats|wat|which|when|kya|kis|kab) ")
    private val WHEN = Regex(" when (will|does|is) (the )?hero( arm)? (trade|decide|buy|look|enter|fire|going to|next|run) ")
    /** Its day: "a hero day", "hero ka din", "an expiry day for hero", "hero's expiry". */
    private val DAY = Regex(" (hero|heros) (next )?(day|days|din) | hero (ka|wala|vala) din | expiry (day )?for (the )?hero | heros expiry ")
    private val NEXT = Regex(" (next|agla|agle|agli|upcoming|coming) ")
    /** "That trade" (one decision of the Thinking trail), never the day's story. */
    private val SPECIFIC = Regex(" (that|this|those|these|the|wo|woh|vo|voh|ye|yeh|us|is) (trade|trades|position|setup|signal|one|idea|call|put|order) ")
    /**
     * Not this: another day, its exits and results, its switch, size, budget or settings, its record or forward test alone,
     * its status, a definition, Zerodha, another arm.
     */
    private val NOT = Regex(" (yesterday|kal|parso|last|week|weeks|month|months|tomorrow|this year|monday|tuesday|wednesday|thursday|friday|" +
        "exit|exited|exits|exiting|get out|got out|lose|lost|loss|losses|losing|win|won|winning|profit|pnl|p l|made|net|kamaya|kamai|" +
        "switch|switched|turn on|turn off|on karo|off karo|band karo|chalu karo|band kar|chalu kar|enable|disable|setting|settings|" +
        "lot|lots|size|sizing|budget|backtest|back test|record|forward test|on track|health|healthy|doing|status|kaisa chal|kaise chal|haal|" +
        "mean|means|meaning|define|explain|live|zerodha|" +
        "liquidity|orb|solo|sweep|fade|gold|news) ")

    /** Is the Hero arm's day asked - what it did, why it did not trade, is today its day, when is its next? Null when not. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (!HERO.containsMatchIn(t) || MOTO.containsMatchIn(t) || NOT.containsMatchIn(t) || SPECIFIC.containsMatchIn(t)) return null
        val day = DAY.containsMatchIn(t)
        if (day) return Q(if (NEXT.containsMatchIn(t) || Regex(" (when|kab) ").containsMatchIn(t)) Kind.NEXT else Kind.DAY)
        if (WHY.containsMatchIn(t) && NEG.containsMatchIn(t) && DO.containsMatchIn(t)) return Q(Kind.WHY)
        val what = DID_WHAT.containsMatchIn(t) || DID.containsMatchIn(t) || HINDI.containsMatchIn(t) || DECIDE.containsMatchIn(t) ||
            ASK.containsMatchIn(t) && WAIT.containsMatchIn(t) || WHEN.containsMatchIn(t)
        return if (what) Q(Kind.WHAT) else null
    }

    // ---- the day's record (kept by the app, in memory) ---------------------------------------------------------------

    /**
     * One verdict of the Hero arm at [at]: [verdict] its code ("hero_straddle_not_expanded", "entered", ...). [minute] the
     * closed minute it decided on (null: a verdict between its decisions - waiting, holding, done). What it read then:
     * [spot] NIFTY, [atm] the ATM strike with its legs [ce] and [pe], the [straddle] and its [low] since 12:00, [stExp] the
     * straddle above that low (0.15 = 15%) and [mom] NIFTY's 15-minute move (0.0025 = 0.25%); a signal's [side] (+1 call,
     * -1 put, 0 none) and, when it got that far, the option picked ([strike] at [price]), its [limit] and [lots].
     */
    data class Decision(
        val at: LocalDateTime, val verdict: String, val minute: LocalTime? = null,
        val spot: Double? = null, val atm: Int? = null, val ce: Double? = null, val pe: Double? = null,
        val straddle: Double? = null, val low: Double? = null, val stExp: Double? = null, val mom: Double? = null,
        val side: Int = 0, val strike: Double? = null, val price: Double? = null, val limit: Double? = null, val lots: Int? = null,
    )

    /** At most this many decisions are kept a day (76 minutes in its window, the stale minutes looked at again, the rest). */
    const val MAX_DECISIONS = 400

    // ---- the facts --------------------------------------------------------------------------------------------------

    /**
     * One Hero trade as the arms' book keeps it: [symbol] ([call] or put), [qty] still held (or sold at the end), [sold]
     * early at the 5x target at [soldAt] / [soldTime], the [entry] fill at [entryTime] on the [signalBar]'s minute, and once
     * closed its [exit] at [exitTime] for [why], its [net] after [charges]; [mark] the last price while open; [seen] the
     * option's book at the signal and each exit.
     */
    data class Trade(
        val symbol: String, val call: Boolean, val qty: Int, val entry: Double, val entryTime: LocalDateTime, val signalBar: LocalDateTime? = null,
        val sold: Int = 0, val soldAt: Double? = null, val soldTime: LocalDateTime? = null,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val net: Double? = null, val charges: Double = 0.0,
        val mark: Double? = null, val seen: List<HeroRules.Seen> = emptyList(),
    ) {
        val open: Boolean get() = exit == null
        val bought: Int get() = qty + sold
    }

    /**
     * What the answer is made from at [now]: [tradingDay] today a session; [expiries] NIFTY's listed expiries from the contract
     * list on the phone (empty: not on the phone); [armed] the arm's switch (null: not read) and its [status] (a self-disarm's
     * reason in it); today's [decisions] (kept in memory [since] the app last started); [today] its trades entered today;
     * [record] its forward record ([ForwardCheck.HERO], null: not read) and [allTime] every closed paper trade it has (count,
     * net; null: not read).
     */
    data class Facts(
        val now: LocalDateTime, val tradingDay: Boolean = true, val expiries: List<LocalDate> = emptyList(),
        val armed: Boolean? = null, val status: String? = null, val decisions: List<Decision> = emptyList(), val since: LocalDateTime? = null,
        val today: List<Trade> = emptyList(), val record: ForwardCheck.Result? = null, val allTime: Pair<Int, Double>? = null,
    )

    // ---- the words ------------------------------------------------------------------------------------------------

    private val L = Locale.ENGLISH
    private fun px(x: Double) = String.format(L, "₹%,.2f", x)
    private fun lvl(x: Double) = String.format(L, "%,.2f", x).removeSuffix(".00")
    private fun pct(x: Double) = String.format(L, "%.0f%%", x * 100)
    private fun pct2(x: Double) = String.format(L, "%.2f%%", abs(x) * 100)
    private fun hhmm(t: LocalDateTime) = String.format(L, "%02d:%02d", t.hour, t.minute)
    private fun hhmm(t: LocalTime) = String.format(L, "%02d:%02d", t.hour, t.minute)
    private fun date(d: LocalDate) = d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", L))
    private fun s(n: Int) = if (n == 1) "" else "s"
    private fun rs(x: Double) = ForwardCheck.rs(x)

    /** The rule it trades by, in one sentence. */
    val RULE: String = "From ${hhmm(HeroRules.FIRST)} to ${hhmm(HeroRules.LAST)} it buys when the NIFTY ATM straddle is " +
        "${pct(HeroRules.ST_EXP)} or more above its low since ${hhmm(HeroRules.LOW_FROM)} and NIFTY has moved ${pct2(HeroRules.MOM)} in " +
        "${HeroRules.MOM_MINUTES} minutes - the nearest OTM option on that side priced ₹${HeroRules.MIN_PRICE.toInt()}-${HeroRules.MAX_PRICE.toInt()}, " +
        "a LIMIT order, up to ${rs(HeroRules.BUDGET)} of premium, once a day."

    /** The exits (the deep study's F07), in one sentence. */
    val EXITS: String = "Its exits (F07): ${HeroRules.EXITS}."

    /** The paper-only, not-proven line, always said. */
    val UNPROVEN: String = "Paper only - it never trades on Zerodha - and not proven: its research is a lottery (a few huge days make " +
        "all of it), and with its first exits it lost all 13 trades out of sample."

    /** Today's place among NIFTY's expiries: is it Hero's day, and the next one. */
    fun dayLine(f: Facts, kind: Kind = Kind.WHAT): String {
        val today = f.now.toLocalDate()
        if (f.expiries.isEmpty()) return "The NIFTY contract list is not on the phone, so I cannot tell whether today is an expiry day, Boss " +
            "(Hero never guesses one from the weekday)."
        val expiry = f.tradingDay && HeroRules.isExpiryDay(today, f.expiries)
        val next = f.expiries.filter { it.isAfter(today) }.minOrNull()
        val nextWords = next?.let { n ->
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, n)
            "${date(n)} (${if (days == 1L) "tomorrow" else "in $days days"})"
        }
        return when {
            expiry && kind == Kind.NEXT -> "Today, ${date(today)}, is a NIFTY expiry day - Hero's kind of day, Boss." +
                (nextWords?.let { " After today, the next is $it." } ?: "")
            expiry -> "Today, ${date(today)}, is a NIFTY expiry day (today's contracts expire) - Hero's kind of day, Boss."
            !f.tradingDay -> "No session today, Boss - Hero trades only on a NIFTY expiry day." +
                (nextWords?.let { " The next one is $it." } ?: " No later NIFTY expiry is in the contract list.")
            nextWords != null -> "Today is not a NIFTY expiry day, Boss, so Hero sits it out. Its next day is $nextWords - the nearest NIFTY expiry in the contract list."
            else -> "Today is not a NIFTY expiry day, Boss, and no later NIFTY expiry is in the contract list."
        }
    }

    /** The switch in words. */
    fun switchLine(f: Facts): String {
        val disarmed = f.status?.takeIf { it.startsWith(DISARMED) }?.removePrefix(DISARMED)?.trim()
        return when (f.armed) {
            true -> "It is armed: paper only, fully automatic."
            false -> if (disarmed != null) "It disarmed itself ($disarmed) and stays off until you arm it again by hand."
                else "It is switched off, so it does not trade (it is armed on Home, under Strategies)."
            null -> "I could not read its switch just now."
        }
    }

    private const val DISARMED = "hero_disarmed:"

    /** Its window at [now] on an expiry day. */
    fun windowLine(now: LocalTime): String = when {
        now.isBefore(HeroRules.LOW_FROM) -> "Its window: the straddle's low counts from ${hhmm(HeroRules.LOW_FROM)}, entries ${hhmm(HeroRules.FIRST)}-${hhmm(HeroRules.LAST)}, everything out by ${hhmm(HeroRules.EXIT_AT)}."
        now.isBefore(HeroRules.FIRST) -> "It is tracking the straddle's low since ${hhmm(HeroRules.LOW_FROM)}; entries open at ${hhmm(HeroRules.FIRST)} and close at ${hhmm(HeroRules.LAST)}."
        !now.isAfter(HeroRules.LAST) -> "It is inside its entry window (${hhmm(HeroRules.FIRST)}-${hhmm(HeroRules.LAST)}), out by ${hhmm(HeroRules.EXIT_AT)}."
        else -> "Its entry window (${hhmm(HeroRules.FIRST)}-${hhmm(HeroRules.LAST)}) has closed; anything held is out by ${hhmm(HeroRules.EXIT_AT)}."
    }

    /** A verdict in words. */
    fun verdictWords(v: String): String = when {
        v == "hero_stood_down" -> "stood down for the day: more than ${HeroRules.MAX_SKIPPED} minutes in a row of stale NIFTY or straddle data"
        v == "hero_done_for_today" -> "done for today (one entry a day)"
        v == "hero_no_master" -> "the NIFTY contract list was not loaded, so no trade (never a guess)"
        v == "hero_not_expiry_day" -> "not a NIFTY expiry day"
        v == "hero_waiting_for_window" -> "waiting for ${hhmm(HeroRules.FIRST)}"
        v == "hero_window_closed" -> "no new entries after ${hhmm(HeroRules.LAST)}"
        v == "hero_skipped_stale_bar" -> "the minute's NIFTY or straddle data was stale, so it was skipped"
        v == "hero_straddle_not_expanded" -> "the straddle was not ${pct(HeroRules.ST_EXP)} above its low"
        v == "hero_no_momentum" -> "the straddle had expanded but NIFTY had not moved ${pct2(HeroRules.MOM)} in ${HeroRules.MOM_MINUTES} minutes"
        v == "hero_no_strike" -> "it fired, but no OTM option priced ₹${HeroRules.MIN_PRICE.toInt()}-${HeroRules.MAX_PRICE.toInt()} passed the checks (traded recently, an ask, not stale)"
        v == "no_contract" -> "it fired, but the option's contract was not in the list"
        v == "hero_lot_unknown" -> "it fired, but the lot size was not in the contract list"
        v == "hero_zero_lots" -> "it fired, but one lot cost more than its ${rs(HeroRules.BUDGET)} budget"
        v == "entered" -> "it bought"
        v == "holding" -> "holding its position"
        v == "stopped_for_today" -> "the bot was stopped for today"
        v == "flat_after_square_off" -> "past the arms' square-off time"
        v == "refused: the kill switch is on" -> "it fired, but the kill switch is on"
        v.startsWith("refused:") -> "it fired, but " + v.removePrefix("refused:").trim()
        v.startsWith("guard_refused:") -> "it fired, but the account guard refused the order (" + v.removePrefix("guard_refused:").trim() + ")"
        v.startsWith("order_refused:") -> "it fired, but the order did not go through (" + v.removePrefix("order_refused:").trim() + ")"
        v.startsWith(DISARMED) -> "it disarmed itself (" + v.removePrefix(DISARMED).trim() + ")"
        v == "no_index_data" || v == "no_decision_bar" -> "no new minute to decide on"
        v == "error" -> "an error in its pass"
        else -> v.replace('_', ' ')
    }

    /** A signal that was not bought (after it fired). */
    fun firedButNot(v: String): Boolean = v in setOf("hero_no_strike", "no_contract", "hero_lot_unknown", "hero_zero_lots") ||
        v.startsWith("refused:") || v.startsWith("guard_refused:") || v.startsWith("order_refused:")

    /** What it read at one decision, in words (null: it read nothing that minute). */
    fun readWords(d: Decision): String? {
        val st = d.straddle ?: return null
        val legs = if (d.ce != null && d.pe != null) " (CE ${px(d.ce)} + PE ${px(d.pe)})" else ""
        val atm = d.atm?.let { " at the $it strike" } ?: ""
        val low = d.low?.let { " - its low since ${hhmm(HeroRules.LOW_FROM)} ${px(it)}" } ?: ""
        val exp = d.stExp?.let { ", ${pct(it)} above it (it needs ${pct(HeroRules.ST_EXP)})" } ?: ""
        val mom = d.mom?.let { "; NIFTY ${if (it >= 0) "up" else "down"} ${pct2(it)} in ${HeroRules.MOM_MINUTES} minutes (it needs ${pct2(HeroRules.MOM)} either way)" } ?: ""
        val spot = d.spot?.let { "NIFTY ${lvl(it)}, " } ?: ""
        return "${spot}the ATM straddle$atm ${px(st)}$legs$low$exp$mom"
    }

    /** The minutes it decided today, counted by verdict. */
    fun tally(ds: List<Decision>): String? {
        val decided = ds.filter { it.minute != null }
        if (decided.isEmpty()) return null
        val minutes = decided.mapNotNull { it.minute }.distinct().size
        val by = decided.groupBy { it.verdict }.entries.sortedByDescending { it.value.size }
        val first = decided.first().minute!!; val last = decided.last().minute!!
        return "It decided on $minutes minute${s(minutes)} (" + (if (first == last) "at ${hhmm(first)}" else "${hhmm(first)}-${hhmm(last)}") + "): " +
            by.joinToString("; ") { (v, l) -> "${l.size} - ${verdictWords(v)}" } + "."
    }

    /** The closest it came: the straddle's highest stretch above its low and NIFTY's biggest 15-minute move, with their minutes. */
    fun closest(ds: List<Decision>): String? {
        val st = ds.filter { it.stExp != null && it.minute != null }.maxByOrNull { it.stExp!! }
        val mo = ds.filter { it.mom != null && it.minute != null }.maxByOrNull { abs(it.mom!!) }
        if (st == null && mo == null) return null
        val parts = listOfNotNull(
            st?.let { "the straddle at most ${pct(it.stExp!!)} above its low (${hhmm(it.minute!!)}; it needs ${pct(HeroRules.ST_EXP)})" },
            mo?.let { "NIFTY's biggest ${HeroRules.MOM_MINUTES}-minute move ${pct2(it.mom!!)} ${if (it.mom >= 0) "up" else "down"} (${hhmm(it.minute!!)}; it needs ${pct2(HeroRules.MOM)})" })
        return "The closest it came: " + parts.joinToString("; ") + " - both in the same minute fire it."
    }

    /** An exit's reason in words. */
    fun exitWords(why: String?): String = when (why) {
        "hero_5x" -> "the 5x target"
        "hero_20x" -> "the 20x target"
        "hero_exit" -> "the ${hhmm(HeroRules.EXIT_AT)} time exit"
        "hero_stop" -> "the -60% stop (a minute's close at or below 0.4x the price paid)"
        "operator_stop" -> "the bot's stop for the day"
        "closed_by_you" -> "closed by you"
        "backstop_square_off" -> "the 15:15 square-off backstop"
        "session_end" -> "the session's end"
        null -> "exit not recorded"
        else -> why.replace('_', ' ')
    }

    /** One trade in words: the buy, its exits, what was sold and its net - or that it is still open. */
    fun tradeWords(t: Trade): List<String> {
        val out = ArrayList<String>()
        val premium = t.bought * t.entry
        out += "It bought ${t.bought} ${t.symbol} (${if (t.call) "a call, NIFTY up" else "a put, NIFTY down"}) on paper at ${px(t.entry)} at " +
            "${hhmm(t.entryTime)}" + (t.signalBar?.let { " on the ${hhmm(it)} signal" } ?: "") +
            " - ${rs(premium)} of premium against its ${rs(HeroRules.BUDGET)} budget."
        out += "Its exits (F07): half out at ${px(HeroRules.target1(t.entry))} (5x), the rest at ${px(HeroRules.target2(t.entry))} (20x) or " +
            "${hhmm(HeroRules.EXIT_AT)}, and a stop on a minute closing at or below ${px(HeroRules.stopLevel(t.entry))} (-60%)."
        t.seen.firstOrNull { it.what == "signal" }?.let { out += "The option's book at the signal: ${HeroRules.seenLine(it)}." }
        if (t.sold > 0) out += "It sold ${t.sold} at ${t.soldAt?.let { px(it) } ?: "the 5x target"}" + (t.soldTime?.let { " at ${hhmm(it)}" } ?: "") + " - the 5x target."
        out += if (t.open) "Still open: ${t.qty} held" + (t.mark?.let { ", last ${px(it)}" } ?: "") + ", watched every pass against those exits."
        else (if (t.sold > 0) "The rest (${t.qty})" else "All of it") + " went at ${px(t.exit!!)}" + (t.exitTime?.let { " at ${hhmm(it)}" } ?: "") +
            ": ${exitWords(t.why)}. " + (t.net?.let { "Net ${rs(it)} after ${rs(t.charges)} of charges." } ?: "Its result could not be read.")
        return out
    }

    /** Its paper record so far and the not-proven line. */
    fun recordLine(f: Facts): String {
        val r = f.record
        val head = when {
            r == null -> "Its paper record could not be read just now."
            r.trades == 0 -> "Paper record (F07 exits, since ${date(ForwardCheck.HERO.since ?: f.now.toLocalDate())}): no closed trade yet."
            else -> "Paper record (F07 exits, since ${date(ForwardCheck.HERO.since ?: f.now.toLocalDate())}): ${r.trades} closed trade${s(r.trades)}, " +
                "net ${rs(r.net)} (${ForwardCheck.words(r)}; drawdown ${rs(r.drawdown)} against the backtest's worst ${rs(ForwardCheck.HERO.maxDrawdown)})."
        }
        val all = f.allTime?.takeIf { a -> r != null && a.first > r.trades }?.let { (n, net) -> " In all, with its earlier exits: $n trade${s(n)}, net ${rs(net)}." } ?: ""
        return "$head$all $UNPROVEN"
    }

    /** The answer at [Facts.now], from Hero's own records only. */
    fun answer(q: Q, f: Facts): String {
        val today = f.now.toLocalDate()
        val now = f.now.toLocalTime()
        val parts = ArrayList<String>()
        parts += dayLine(f, q.kind)
        parts += switchLine(f)
        val expiry = f.tradingDay && f.expiries.isNotEmpty() && HeroRules.isExpiryDay(today, f.expiries)
        val ds = f.decisions.filter { it.at.toLocalDate() == today }
        val trades = f.today.filter { it.entryTime.toLocalDate() == today }
        if (!expiry) {
            // Not its day (or not known): what it trades on, and nothing to tell of today.
            if (f.expiries.isEmpty() && f.tradingDay) ds.lastOrNull()?.let { parts += "Its last verdict today (${hhmm(it.at)}): ${verdictWords(it.verdict)}." }
            if (q.kind != Kind.NEXT) parts += "It trades only on NIFTY expiry days: $RULE"
            trades.forEach { parts += tradeWords(it) }
            parts += recordLine(f)
            return parts.joinToString(" ")
        }
        parts += windowLine(now)
        if (q.kind == Kind.DAY || q.kind == Kind.NEXT) {
            parts += RULE
            parts += brief(f, ds, trades, now)
            parts += recordLine(f)
            return parts.joinToString(" ")
        }
        if (q.kind == Kind.WHY && trades.isNotEmpty()) parts += "It did trade today."
        ds.lastOrNull { it.straddle != null }?.let { d -> parts += "Its last read (${hhmm(d.minute ?: d.at.toLocalTime())}): ${readWords(d)}." }
        tally(ds)?.let { parts += it }
        ds.filter { it.side != 0 }.forEach { d ->
            val side = if (d.side > 0) "up (a call)" else "down (a put)"
            val pick = d.strike?.let { k -> " - picked the ${lvl(k)}" + (d.price?.let { " at ${px(it)}" } ?: "") + (d.limit?.let { ", limit ${px(it)}" } ?: "") +
                (d.lots?.let { ", $it lot${s(it)}" } ?: "") } ?: ""
            parts += "At ${hhmm(d.minute ?: d.at.toLocalTime())} it fired $side$pick: ${verdictWords(d.verdict)}."
        }
        if (trades.isNotEmpty()) {
            trades.forEach { parts += tradeWords(it) }
        } else {
            parts += whyNone(f, ds, now)
        }
        parts += recordLine(f)
        return parts.joinToString(" ")
    }

    /** Is today's day so far, briefly (for "is today a hero day"). */
    private fun brief(f: Facts, ds: List<Decision>, trades: List<Trade>, now: LocalTime): String {
        trades.lastOrNull()?.let { t ->
            return "Today it bought ${t.symbol} at ${px(t.entry)} at ${hhmm(t.entryTime)}" +
                (if (t.open) " and still holds it." else ", out at ${px(t.exit!!)}: ${exitWords(t.why)}" + (t.net?.let { ", net ${rs(it)}." } ?: "."))
        }
        if (f.armed != true) return "Switched off, it will not trade today."
        val last = ds.lastOrNull() ?: return if (now.isBefore(HeroRules.FIRST)) "It has nothing to decide before ${hhmm(HeroRules.FIRST)}."
            else "No decision of it is on record today."
        return "Its latest verdict (${hhmm(last.minute ?: last.at.toLocalTime())}): ${verdictWords(last.verdict)}."
    }

    /** Why no trade today (an expiry day), from its records. */
    private fun whyNone(f: Facts, ds: List<Decision>, now: LocalTime): String {
        val inWindow = ds.filter { it.minute != null }
        val stood = ds.firstOrNull { it.verdict == "hero_stood_down" }
        val guard = ds.lastOrNull { it.verdict == "stopped_for_today" || it.verdict == "refused: the kill switch is on" }
        val since = f.since?.takeIf { it.toLocalDate() == f.now.toLocalDate() }
        val last = f.now.toLocalDate().atTime(HeroRules.LAST)
        return when {
            f.armed == false && inWindow.isEmpty() -> "No trade today: it was not armed."
            stood != null -> "No trade today: at ${hhmm(stood.minute ?: stood.at.toLocalTime())} it ${verdictWords(stood.verdict)} (it never trades on stale data)."
            guard != null && inWindow.none { it.verdict == "entered" } -> "No trade today: ${verdictWords(guard.verdict)} (from ${hhmm(guard.at)})."
            ds.any { it.verdict == "hero_no_master" } && inWindow.isEmpty() -> "No trade today: ${verdictWords("hero_no_master")}."
            now.isBefore(HeroRules.FIRST) -> "No trade yet: its window opens at ${hhmm(HeroRules.FIRST)}. $RULE"
            inWindow.isNotEmpty() && inWindow.any { firedButNot(it.verdict) } ->
                "No trade " + (if (now.isAfter(HeroRules.LAST)) "today" else "yet") + ": each signal it fired was set aside, as above" +
                    (if (now.isAfter(HeroRules.LAST)) "." else "; a later minute may fire again until ${hhmm(HeroRules.LAST)}.")
            inWindow.isNotEmpty() -> (if (now.isAfter(HeroRules.LAST)) "No trade today: no signal - " else "No signal yet: ") +
                "the straddle's ${pct(HeroRules.ST_EXP)} and NIFTY's ${pct2(HeroRules.MOM)} never lined up in the same minute." +
                (closest(inWindow)?.let { " $it" } ?: "") + (if (now.isAfter(HeroRules.LAST)) "" else " $RULE")
            since != null && since.isAfter(last) -> "No trade today: the app started at ${hhmm(since)}, after its ${hhmm(HeroRules.FIRST)}-${hhmm(HeroRules.LAST)} window, so it did not decide."
            !now.isAfter(HeroRules.LAST) -> "No decision of this window is on record yet: it decides each minute while the app's market watch runs. $RULE"
            else -> "No trade today and no decision on record: it decides only while the app's market watch runs in its window" +
                (since?.let { " (the record is kept in memory since the app started at ${hhmm(it)})" } ?: "") + "."
        }
    }
}
