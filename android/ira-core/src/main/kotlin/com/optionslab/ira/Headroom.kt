package com.optionslab.ira

import com.optionslab.engine.risk.AccountGuard
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * How close Boss is to his limits (usefulness, round 13, 2026-10-05): "how close am I to my limits?", "how much can I
 * still lose today?", "how many trades do I have left?", "limit se kitna door hoon" - one sentence first naming the limit
 * he is nearest, then the rest: today's loss against the daily loss limit (and what is left of it), the orders sent
 * against the trades-a-day limit, the instruments held against the open-positions limit, the drawdown against its limit
 * (from the capital and from the peak, the worse of the two - as the guard measures it), the minutes to the entry
 * cut-off, and the kill switch and the daily loss breaker as they stand.
 *
 * Every number is the guard's own ([AccountGuard.Account] and [AccountGuard.Limits], the same the order checks use), so
 * what he hears is what the guard will judge. Facts only: nothing is placed, changed or closed, no limit is moved, and a
 * limit near is never a suggestion to trade or to stop - the call is his. His account, so never on a locked phone (the
 * app says so). Paper is practice: its orders are not refused by these limits (said so); the daily loss breaker still
 * stops the paper bots at the paper daily loss limit. Pure.
 */
object Headroom {
    enum class Asked { ALL, LOSS, TRADES }

    /** One account as the guard sees it. [enforced]: its entries are refused at the limits (Zerodha; paper is practice). */
    data class Book(val where: String, val account: AccountGuard.Account, val limits: AccountGuard.Limits, val enforced: Boolean)

    enum class Kind(val label: String) { LOSS("daily loss"), ORDERS("orders a day"), OPEN("open positions"), DRAWDOWN("drawdown") }

    /** One limit in use: [share] of it used (1.0 = at it), and the words. */
    data class Use(val kind: Kind, val share: Double, val text: String)

    /** At or past this share, a limit is "near". */
    const val NEAR = 0.7

    private fun rs(x: Double) = "Rs " + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun pct(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)
    private fun pc1(x: Double) = "%.1f%%".format(Locale.ENGLISH, x)
    private fun hm(m: Int) = "%02d:%02d".format(Locale.ENGLISH, m / 60, m % 60)
    private fun dur(m: Int) = if (m < 60) "$m min" else "${m / 60} h" + if (m % 60 > 0) " ${m % 60} min" else ""

    /** Each limit that is on (0 = off), with how much of it is used. A figure not known is left out. */
    fun uses(b: Book): List<Use> {
        val a = b.account; val l = b.limits
        val out = ArrayList<Use>()
        if (l.maxDailyLoss > 0 && a.dayPnl.isFinite()) {
            val lost = max(0.0, -a.dayPnl)
            val left = l.maxDailyLoss + a.dayPnl
            val share = lost / l.maxDailyLoss
            out += Use(Kind.LOSS, share, when {
                left <= 0 -> "today's loss ${rs(lost)} has reached the ${rs(l.maxDailyLoss)} daily loss limit"
                a.dayPnl >= 0 -> "you're up ${rs(a.dayPnl)} today, ${rs(left)} from the ${rs(l.maxDailyLoss)} daily loss limit"
                else -> "today's loss is ${rs(lost)} of the ${rs(l.maxDailyLoss)} daily loss limit (${pct(share)}), ${rs(left)} left"
            })
        }
        if (l.maxTradesPerDay > 0) {
            val n = a.ordersToday; val left = l.maxTradesPerDay - n
            out += Use(Kind.ORDERS, n.toDouble() / l.maxTradesPerDay,
                if (left <= 0) "$n orders sent today, at the limit of ${l.maxTradesPerDay}"
                else "$n of ${l.maxTradesPerDay} orders sent today, $left left (exits and stops count too)")
        }
        if (l.maxOpenPositions > 0) {
            val held = a.holdings.filter { it.qty != 0 }.map { it.symbol }.toSet().size
            out += Use(Kind.OPEN, held.toDouble() / l.maxOpenPositions,
                if (held >= l.maxOpenPositions) "$held open positions, at the limit of ${l.maxOpenPositions} (adding to one held still goes)"
                else "$held of ${l.maxOpenPositions} open positions")
        }
        if (l.maxDrawdownPct > 0 && a.equity.isFinite()) {
            val fromCap = if (a.capital > 0 && a.capital.isFinite()) (a.capital - a.equity) / a.capital * 100 else null
            val fromPeak = if (a.peakEquity > 0 && a.peakEquity.isFinite()) (a.peakEquity - a.equity) / a.peakEquity * 100 else null
            val worst = listOfNotNull(fromCap, fromPeak).maxOrNull()
            if (worst != null) {
                val dd = max(0.0, worst)
                val from = if (fromPeak != null && (fromCap == null || fromPeak >= fromCap) && dd > 0) "its peak" else "the capital"
                out += Use(Kind.DRAWDOWN, dd / l.maxDrawdownPct,
                    if (dd <= 0) "no drawdown (limit ${pc1(l.maxDrawdownPct)})"
                    else "drawdown ${pc1(dd)} below $from of the ${pc1(l.maxDrawdownPct)} limit")
            }
        }
        return out
    }

    /** The cut-off for new entries, as it stands at [minute] (null: no cut-off). */
    fun cutoff(l: AccountGuard.Limits, minute: Int): String? {
        val cut = l.entryCutoffMinute ?: return null
        return if (minute >= cut) "past the ${hm(cut)} cut-off: no new entries today"
        else "new entries until ${hm(cut)} (${dur(cut - minute)} from now)"
    }

    /** What [asked] reads, of [b]'s uses. */
    private fun scoped(us: List<Use>, asked: Asked): List<Use> = when (asked) {
        Asked.ALL -> us
        Asked.LOSS -> us.filter { it.kind == Kind.LOSS || it.kind == Kind.DRAWDOWN }
        Asked.TRADES -> us.filter { it.kind == Kind.ORDERS || it.kind == Kind.OPEN }
    }

    /** One account in words: the nearest limit first, in one sentence, then the rest. */
    fun lines(b: Book, asked: Asked, breakerTripped: Boolean = false): List<String> {
        val name = if (b.enforced) b.where else "${b.where} (practice: these limits do not refuse paper orders)"
        val us = scoped(uses(b), asked).sortedByDescending { it.share }
        val out = ArrayList<String>()
        if (b.limits.killSwitch && b.enforced) out += "On ${b.where} the kill switch is on: no new positions until you clear it yourself; exits still go."
        if (us.isEmpty()) {
            out += when (asked) {
                Asked.LOSS -> "On $name no daily loss or drawdown limit is set (or today's P&L could not be read)."
                Asked.TRADES -> "On $name no orders-a-day or open-positions limit is set."
                Asked.ALL -> "On $name no limits are set."
            }
        } else {
            val top = us.first()
            out += when {
                top.share >= 1.0 -> "On $name you're at a limit: ${top.text}" + (if (b.enforced) " - only exits are allowed for it." else ".")
                top.share >= NEAR -> "On $name the nearest limit is the ${top.kind.label}: ${top.text}."
                else -> "On $name you're well inside your limits; the nearest is the ${top.kind.label}: ${top.text}."
            }
            val rest = us.drop(1).map { it.text }
            if (rest.isNotEmpty()) out += rest.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
        }
        if (asked != Asked.LOSS) cutoff(b.limits, b.account.minuteOfDay)?.let { c -> if (b.enforced) out += c.replaceFirstChar { it.uppercase() } + "." }
        if (breakerTripped && asked != Asked.TRADES) out += "The daily loss breaker tripped today: the bots sold and stopped."
        return out
    }

    /**
     * The answer: each account read ([books]; the one his mode trades first), or a word that none could be read. Ends with
     * the decision left to him.
     */
    fun say(books: List<Book>, asked: Asked, breakerTripped: Boolean = false): String {
        if (books.isEmpty()) return "I couldn't read your accounts just now, Boss, so I can't say how close you are to your limits."
        val lines = books.flatMapIndexed { i, b -> lines(b, asked, breakerTripped && i == books.lastIndex) }
        return (lines + "Facts only, Boss - what you do with them is your call.").joinToString(" ")
    }

    // ---- asked -------------------------------------------------------------------------------------------------

    private fun words(s: String) = Spaced.joined(s)

    /** Words of changing a limit, an order or a stop: never this question. */
    private val ACTS = rx(" (set|change|raise|increase|lower|reduce|decrease|turn|switch|remove|clear|disable|enable|reset|cancel|buy|sell|square|kar do|karo|badha|ghata|badhao|ghatao) ")
    private const val LIM = "(limit|limits|risk limits|daily loss limit|loss limit|day loss limit|daily limit|trade limit|trades limit|order limit|max trades|max loss|guard|guards|guardrails)"
    private val ALL = rx(
        " how (close|near) am i to (my |the |any )?$LIM | how (far|close) am i from (my |the |any )?$LIM " +
        "| am i (near|close to|nearing|approaching|at|hitting) (my |the |any )?$LIM | (am i|are we) (within|inside|under) (my |the )?$LIM " +
        "| how much (room|headroom|room to trade|space) (do i have|is left|have i got|left)| (my|whats my|what is my|show me my|show my) headroom " +
        "| how much of my (limits?|daily loss limit|loss limit|risk) (have i|did i|is) (used|left|gone)| where do i stand (on|against|with) my $LIM " +
        "| (limit|limits) (ke|se) (kitna|kitne) (paas|pass|door|dur) (hoon|hu|hun|hai|hain) | kitna (paas|door|dur) (hoon|hu|hun) (limit|limits) (se|ke) " +
        // Round 10: "how close is my loss to the limit", "am I blocked from trading", "how much more can I trade today".
        "| how (close|near) is my (loss|drawdown|day s loss|days loss) to (my |the )?$LIM | (am i|are we) (blocked|locked out|stopped|barred) from trading " +
        "| how much more can i trade( today)? $")
    private val LOSS = rx(
        " how much (more )?can i (still )?(lose|loose|afford to lose)| how much (more )?(loss )?(is |do i have )?(left|remaining) (on|of|in) (my |the )?(daily )?(loss )?limit " +
        "| how much (loss|room) (is |do i have )?(left|remaining)( today)? | how close am i to (my |the )?(daily )?(max )?loss | (my )?(daily )?loss limit (left|remaining) " +
        "| kitna (aur )?(loss|nuksan|nuksaan) (le|utha|kha|seh|sah) sakta (hoon|hu|hun) | (aur )?kitna (loss|nuksan|nuksaan) (bacha|baaki|baki) (hai|he) ")
    private val TRADES = rx(
        " how many (more )?(trades|orders) (can i|may i|am i allowed to) (still )?(take|place|do|make|send)| how many (more )?(trades|orders) (do i have |have i got |are |is )?(left|remaining)" +
        "| how many (trades|orders) (have i|did i) (got )?(left|remaining)| (trades|orders) (left|remaining) (today|for today|for the day) " +
        "| kitne (aur )?(trade|trades|order|orders) (le|kar|laga|lagaa) sakta (hoon|hu|hun) | kitne (trade|trades|order|orders) (bache|baaki|baki|bachey) (hai|hain|he) " +
        // Round 10: "kitne trade aur kar sakta hoon", "kya main aur trade kar sakta hoon", "am I allowed to trade more today",
        // "am I overtrading" (his own count against the guard's - never the arms', which BotHealth reads).
        "| kitne (trade|trades|order|orders) aur (le|kar|laga) sakta (hoon|hu|hun) | (kya )?(main |mai )?aur (trade|trades) (le|kar) sakta (hoon|hu|hun) " +
        "| (am i|are we) allowed (to trade more|to take (another|one more|more) (trade|trades)|more trades) | (am i|are we) (overtrading|over trading|trading too much) ")

    /** "How close am I to my limits?" (ALL), "how much can I still lose today?" (LOSS), "how many trades left?" (TRADES); null otherwise. */
    fun asked(text: String): Asked? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Asked?>(64)

    private fun askedFresh(text: String): Asked? {
        val t = words(text)
        if (ACTS.containsMatchIn(t)) return null
        // "What's my worst case today", "what if all my stops get hit" (round 26): the day's loss room with the open legs at
        // their stops ([AtStops]) - read before the what-if below, which its own "if" would otherwise turn away.
        if (AtStops.ASKED.containsMatchIn(t)) return Asked.LOSS
        // A what-if ("how much can I lose if Nifty falls 1%"): his book at a move, the account's own (Exposure; round 10).
        if (rx(" (if|agar|suppose|supposing|imagine) ").containsMatchIn(t)) return null
        // Another day's or the market's: not this (only today's limits are counted).
        if (rx(" (yesterday|last week|this week|last month|this month|tomorrow|kal) ").containsMatchIn(t)) return null
        return when {
            // "How close am I to my daily loss limit": the loss alone; "...to my trade limit": the trades alone.
            ALL.containsMatchIn(t) -> when {
                rx(" (loss|drawdown|nuksan|nuksaan) ").containsMatchIn(t) -> Asked.LOSS
                rx(" (trade|trades|order|orders) ").containsMatchIn(t) -> Asked.TRADES
                else -> Asked.ALL
            }
            // A position's or a what-if's loss is the account's MOVE / the scenarios', not the day's limit room (review, 5 Oct).
            LOSS.containsMatchIn(t) -> if (rx(" (if|agar|suppose|falls?|drops?|rises?|moves?) | (put|puts|call|calls|position|positions|trade|strangle|straddle|spread|condor) ").containsMatchIn(t)) null else Asked.LOSS
            TRADES.containsMatchIn(t) -> Asked.TRADES
            else -> null
        }
    }
}
