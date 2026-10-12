package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * The order review (Boss, 12 Oct: "an agent same as the others, who will study each and every order placed, analyse it every
 * day, find the flaws, and resolve them, enhance and make it more profitable"). Once a trading day after the close the app
 * hands this every order and trade it kept - live and paper, every arm, Pine, the protections, Jarvis's paper and Boss's own
 * hand orders - with what each meant (its stop, target, window, square-off), what Zerodha or the paper book did with it, the
 * trade's path where it is stored, the slippage record, the order path's speed warnings and the sync check. Out comes, per
 * order or trade, a list of [Flaw]s (a category, a severity, the numbers and a rupee estimate), and a [Summary] per strategy.
 *
 * Honesty rules, all here:
 *  - A flaw is a fact with its numbers. "Stopped out, then the price reached the target" and "price went much further after
 *    the exit" are EVIDENCE only ([Category.evidenceOnly]): their rupees are what was missed, never counted as a cost.
 *  - Slippage is judged only against that strategy's own p90 with [MIN_BASE] earlier fills behind it; paper fills are the
 *    sandbox's model and never judged.
 *  - One bad day is not a pattern: a [Pattern] needs [PATTERN_N] occurrences on [PATTERN_DAYS] or more days within the last
 *    [WINDOW] reviewed trading days. Only patterns become lessons ([lessonInputs]) or lab candidates ([improvement]).
 *  - Parameter-like patterns are never applied: they become a [LabAsk] for the strategy lab's shadow variants (its pre-check,
 *    minimum days, sequential test and Benjamini-Hochberg decide), or a proposal when the lab cannot test that kind.
 *    Bug-like patterns are flagged "needs a fix". "More profitable" is measured: [beforeAfter] on each strategy's own nets.
 *
 * It never places, modifies or cancels an order, and changes nothing by itself. Pure: no clock, no Android, no disk.
 */
object OrderReview {
    enum class Venue(val key: String, val word: String) {
        LIVE("l", "live"), PAPER("p", "paper");
        companion object { fun of(k: String): Venue? = entries.firstOrNull { it.key == k } }
    }

    enum class Group(val word: String) { EXECUTION("execution"), RULE("rules"), OUTCOME("outcome") }

    /** What a flaw calls for. */
    enum class Fix(val key: String, val word: String) {
        NEEDS_FIX("f", "needs a fix"), LAB("l", "a candidate for the strategy lab"), NOTE("n", "noted");
        companion object { fun of(k: String): Fix? = entries.firstOrNull { it.key == k } }
    }

    enum class Severity(val key: String, val word: String) {
        INFO("i", "info"), WARN("w", "warning"), HIGH("h", "serious");
        companion object { fun of(k: String): Severity? = entries.firstOrNull { it.key == k } }
    }

    enum class Category(val key: String, val group: Group, val words: String, val fix: Fix, val evidenceOnly: Boolean = false) {
        REJECTED("rej", Group.EXECUTION, "order rejected", Fix.NEEDS_FIX),
        CANCELLED("can", Group.EXECUTION, "entry cancelled unfilled", Fix.NOTE),
        PARTIAL("par", Group.EXECUTION, "partly filled", Fix.NOTE),
        SLIPPAGE("slp", Group.EXECUTION, "slippage above its usual", Fix.NOTE),
        SLOW("slow", Group.EXECUTION, "order path slower than its normal", Fix.NOTE),
        DUPLICATE("dup", Group.EXECUTION, "duplicate order", Fix.NEEDS_FIX),
        ORPHAN("orph", Group.EXECUTION, "filled order no book follows", Fix.NEEDS_FIX),
        STOP_NOT_RESTING("nostop", Group.EXECUTION, "stop not resting", Fix.NEEDS_FIX),
        SYNC("sync", Group.EXECUTION, "app and Zerodha out of step", Fix.NEEDS_FIX),
        OUTSIDE_WINDOW("win", Group.RULE, "entry outside its own window", Fix.NEEDS_FIX),
        EXIT_DIFFERS("exit", Group.RULE, "exit not by its own rules", Fix.NOTE),
        LATE_EXIT("late", Group.RULE, "sold past its stop", Fix.NOTE),
        PAST_SQUARE_OFF("sqoff", Group.RULE, "held past its square-off", Fix.NEEDS_FIX),
        STOP_THEN_TARGET("stt", Group.OUTCOME, "stopped out, then price reached the target", Fix.LAB, evidenceOnly = true),
        GAVE_BACK("gb", Group.OUTCOME, "gave back most of its best gain", Fix.LAB),
        EARLY_EXIT("early", Group.OUTCOME, "exited early: price went much further", Fix.LAB, evidenceOnly = true),
        LOSS_OVER_PLAN("risk", Group.OUTCOME, "loss bigger than its planned risk", Fix.NOTE),
        THETA_BLEED("theta", Group.OUTCOME, "time in the trade with no move", Fix.LAB),
        // 12 Oct ("why did no agent find these?"): the classes the review had no detector for.
        /** Many trades a day and about no edge before charges: the charges ate the day (ORB's ~9 re-entries a day, R14). */
        CHARGES_EAT("chg", Group.OUTCOME, "charges ate the day's gross", Fix.NOTE),
        /** The same side bought again and again in a day, often right after a target or a lock. */
        REENTRY("reent", Group.RULE, "same side re-entered again and again", Fix.NOTE),
        /** A protection's target on the losing side of the price paid (a long's under it): it fills at once, for a loss. */
        WRONG_SIDE("wside", Group.EXECUTION, "protection target on the wrong side of the entry", Fix.NEEDS_FIX),
        /** A protection request that set nothing (no stop, no target), the old stop cancelled for it. */
        EMPTY_PROTECT("empty", Group.EXECUTION, "a stop replaced by nothing", Fix.NEEDS_FIX),
        /** A protection (a guard, a hand stop) put on a position a bot opened and exits by its own rules. */
        ARM_PROTECT("armp", Group.RULE, "a protection put on a bot's own position", Fix.NEEDS_FIX),
        /** Error lines in the diagnostics diary today, by kind ("Missing required field: quantity" x3). */
        ERRORS("err", Group.EXECUTION, "errors in the app's diary", Fix.NEEDS_FIX),
        ;
        companion object { fun of(k: String): Category? = entries.firstOrNull { it.key == k } }
    }

    // ---- thresholds (fixed, said in the evidence; never fitted to the data) -----------------------------------------------

    /** Earlier live fills of a strategy and leg before its p90 judges a fill. */
    const val MIN_BASE = 10
    /** Two orders alike (strategy, symbol, side, quantity) this close together: a duplicate. */
    const val DUP_MS = 5_000L
    /** A filled live entry with no trade of its book within this: an orphan. */
    const val ORPHAN_MS = 5 * 60_000L
    /** Sold this far past the stop (and at least [LATE_TICKS] ticks): a missed or late exit. */
    const val LATE_PCT = 0.03
    const val LATE_TICKS = 3
    /** A loss this many times its planned risk. */
    const val OVER_PLAN_X = 1.25
    const val OVER_PLAN_MIN_RS = 50.0
    /** Best gain at least this share of the entry, then this share of it given back. */
    const val GB_MIN_PCT = 0.05
    const val GIVEBACK = 0.6
    /** Held this long with the best gain under [THETA_PCT] of the entry and closed below it. */
    const val THETA_MIN = 30
    const val THETA_PCT = 0.05
    /** After an exit, a further move of this share of the entry (and at least the trade's own move). */
    const val EARLY_PCT = 0.10
    /** A pattern: this many occurrences, on this many days or more, in the last [WINDOW] reviewed days. */
    const val PATTERN_N = 3
    const val PATTERN_DAYS = 2
    const val WINDOW = 10
    /** Days kept (one small line a day). */
    const val KEEP_DAYS = 60
    /** Each side of a before / after comparison needs this many days. */
    const val MIN_COMPARE_DAYS = 10
    /** A strategy's day with this many closed trades or more is checked for charges eating its gross ([Category.CHARGES_EAT]). */
    const val CHURN_N = 4
    /** ... flagged when its gross before charges is under this many times its charges (charges at least half the gross, or no edge). */
    const val CHURN_X = 2.0
    /** ... serious at this many trades. */
    const val CHURN_HIGH_N = 8
    /** The same side entered this many times a day or more ([Category.REENTRY]) ... */
    const val REENTRY_N = 3
    /** ... or re-entered this many times within [REENTRY_GAP_MS] of a target or lock on that side. */
    const val AFTER_TARGET_N = 2
    const val REENTRY_GAP_MS = 30 * 60_000L
    /** Error lines of one kind in a day at which [Category.ERRORS] is serious. */
    const val ERRORS_HIGH_N = 3

    // ---- what the app hands in ----------------------------------------------------------------------------------------

    /** One order as Zerodha's order book or the paper book had it. [stop]: a resting stop (cancelled when a trade exits otherwise). */
    data class Order(
        val id: String, val venue: Venue, val strategy: String, val symbol: String, val side: String, val qty: Int, val filled: Int,
        val status: String, val message: String = "", val atMs: Long, val avg: Double = 0.0, val type: String = "LIMIT",
        val stop: Boolean = type.uppercase(Locale.ENGLISH).startsWith("SL"), val manual: Boolean = false,
    )

    /** One minute of the traded contract: [atMs] the minute's start. */
    data class Bar(val atMs: Long, val high: Double, val low: Double, val close: Double)

    /**
     * One trade: [long] true when it profits from a rise in what it holds (an option bought); [stop] / [target] its own on that
     * price (null: none); [best] / [worst] the best and worst price seen while held (null: not kept); [exitWhy] why it closed;
     * [ruleExit] / [ruleExitWhy] what its original rules would have done (the trade manager's record), [managerActed] true when
     * the manager changed it on purpose; [window] its entry minutes; [squareOff] the minute it must be out by (null: holds
     * overnight or not known); [net] after charges; [path] its minutes while held and [after] the rest of the day after the
     * exit (empty: not stored); [plan] Boss's own reason for a hand trade.
     */
    data class Trade(
        val id: String, val venue: Venue, val strategy: String, val symbol: String, val qty: Int,
        val entry: Double, val entryMs: Long, val exit: Double? = null, val exitMs: Long? = null, val long: Boolean = true,
        val stop: Double? = null, val target: Double? = null, val best: Double? = null, val worst: Double? = null,
        val exitWhy: String? = null, val ruleExit: Double? = null, val ruleExitWhy: String? = null, val managerActed: Boolean = false,
        val window: IntRange? = null, val squareOff: Int? = null, val net: Double? = null, val charges: Double = 0.0,
        val path: List<Bar> = emptyList(), val after: List<Bar> = emptyList(), val manual: Boolean = false, val plan: String? = null,
        val tick: Double = 0.05,
    ) {
        val d: Int get() = if (long) 1 else -1
        val closed: Boolean get() = exit != null
    }

    /**
     * A protection set on a position (the protect sheet, Jarvis's "guard my positions", a rescue): at [atMs], on [symbol] held
     * [long]; its [stop] and [target] (null: none); [replaced] an earlier stop or target was cancelled for it; [by] who set it;
     * [entry] the position's price when known (else the open trade's), [owner] the bot that opened the position when known.
     */
    data class Protect(
        val atMs: Long, val venue: Venue, val symbol: String, val long: Boolean, val stop: Double?, val target: Double?,
        val replaced: Boolean = false, val by: String = "", val entry: Double? = null, val owner: String? = null,
    )

    /**
     * The day's records. [fills]: every kept fill (today's judged against the earlier ones), [sync] the confirmed mismatches
     * seen today, [speed] the order path's warnings today, [tracked] the strategies whose books follow every filled entry
     * (only their orders can be orphans), [nowMs] when the review runs (a trade still open past its square-off).
     */
    data class Inputs(
        val day: LocalDate, val orders: List<Order> = emptyList(), val trades: List<Trade> = emptyList(),
        val fills: List<Slippage.Fill> = emptyList(), val sync: List<PositionSync.Mismatch> = emptyList(),
        val speed: List<String> = emptyList(), val tracked: Set<String> = emptySet(), val nowMs: Long = Long.MAX_VALUE,
        /** The protections set in the window (12 Oct). */
        val protections: List<Protect> = emptyList(),
        /** The diary's error lines today by kind ([DiagErrors]); [errorsLive] the account the app was in (Live, else Paper). */
        val errors: Map<String, Int> = emptyMap(), val errorsLive: Boolean = false,
    )

    // ---- what comes out --------------------------------------------------------------------------------------------------

    /** One flaw: [ref] the order or trade, the numbers in [evidence], [rupees] the estimate (a cost positive; evidence-only: missed). */
    data class Flaw(
        val ref: String, val strategy: String, val venue: Venue, val category: Category, val severity: Severity,
        val evidence: String, val rupees: Double, val fix: Fix = category.fix, val symbol: String = "", val manual: Boolean = false,
    ) {
        /** The rupees counted as a cost (never an evidence-only miss). */
        val cost: Double get() = if (category.evidenceOnly) 0.0 else rupees.coerceAtLeast(0.0)
    }

    /** One strategy's day on one account: its orders and trades, wins and losses, net after real costs, its flaws. */
    data class Summary(
        val strategy: String, val venue: Venue, val orders: Int, val trades: Int, val wins: Int, val losses: Int,
        /** After charges, at the real fills (the slippage is in the fills). */
        val net: Double, val charges: Double,
        /** The live fills' slippage in rupees (a cost positive; part of [net] already). */
        val slippage: Double,
        /** Per category: how many and the rupees (a cost, or missed for evidence-only categories). */
        val flaws: Map<Category, Pair<Int, Double>>,
    ) {
        val key: String get() = keyOf(strategy, venue)
        val cost: Double get() = flaws.entries.filter { !it.key.evidenceOnly }.sumOf { it.value.second.coerceAtLeast(0.0) }
    }

    data class Day(val day: LocalDate, val orders: Int, val trades: Int, val flaws: List<Flaw>, val summaries: List<Summary>,
                   val notes: List<String> = emptyList()) {
        val cost: Double get() = flaws.sumOf { it.cost }
        val missed: Double get() = flaws.filter { it.category.evidenceOnly }.sumOf { it.rupees.coerceAtLeast(0.0) }
    }

    fun keyOf(strategy: String, venue: Venue): String = "${strategy.replace('|', '/')}|${venue.key}"

    // ---- the review ----------------------------------------------------------------------------------------------------

    private fun r2(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)
    private fun rs(x: Double) = "₹" + String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun minuteOf(ms: Long): Int = AgentLessons.minuteOf(ms)
    private fun hhmm(m: Int): String = String.format(Locale.ENGLISH, "%02d:%02d", m / 60, m % 60)
    private fun hhmmOf(ms: Long) = hhmm(minuteOf(ms))
    private val TERMINAL = setOf("COMPLETE", "REJECTED", "CANCELLED", "CANCELED", "FILLED")

    /** How a trade closed, from its words ("stop", "profit_lock", "target 2", "time stop", "square-off", "manual", ...). */
    enum class ExitKind { STOP, TARGET, LOCK, TIME, SQUARE_OFF, MANUAL, MANAGER, SIGNAL, OTHER }

    fun exitKind(why: String?): ExitKind {
        val w = why.orEmpty().lowercase(Locale.ENGLISH)
        return when {
            w.isBlank() -> ExitKind.OTHER
            "manager" in w -> ExitKind.MANAGER
            "lock" in w || "trail" in w -> ExitKind.LOCK
            "square" in w || "expiry" in w || "eod" in w || "close" in w && "closed by" !in w -> ExitKind.SQUARE_OFF
            "time" in w -> ExitKind.TIME
            "stop" in w || w == "sl" || "sl hit" in w -> ExitKind.STOP
            "target" in w || "tgt" in w || "level" in w -> ExitKind.TARGET
            "manual" in w || "hand" in w || "boss" in w || "you closed" in w -> ExitKind.MANUAL
            "signal" in w || "pine" in w || "reverse" in w -> ExitKind.SIGNAL
            else -> ExitKind.OTHER
        }
    }

    /** A rejection's reason sorted: Boss's funds (his account, not a code fault) or a fixable cause. */
    fun rejectionFix(message: String): Pair<Fix, String> {
        val m = message.lowercase(Locale.ENGLISH)
        return when {
            Regex("insufficient|margin|funds|shortfall").containsMatchIn(m) -> Fix.NOTE to "funds or margin short (the account, not the app)"
            Regex("circuit|price band|price range|outside|ltp|tick size|price must").containsMatchIn(m) -> Fix.NEEDS_FIX to "the price was outside what the exchange allows"
            Regex("freeze|quantity|lot size|multiple of").containsMatchIn(m) -> Fix.NEEDS_FIX to "the quantity was not allowed"
            Regex("not allowed|blocked|ban|market order|disabled|rms").containsMatchIn(m) -> Fix.NEEDS_FIX to "the order type or contract was refused"
            Regex("session|token|login|expired").containsMatchIn(m) -> Fix.NEEDS_FIX to "the Zerodha session had lapsed"
            else -> Fix.NOTE to (if (m.isBlank()) "no reason given" else "the broker's reason as given")
        }
    }

    /** The day's review: every order and trade checked; the flaws and a summary per strategy and account. */
    fun review(i: Inputs): Day {
        val out = ArrayList<Flaw>()
        val notes = ArrayList<String>()
        val today = i.day.toString()
        // ---- the orders ----
        for (o in i.orders) {
            val st = o.status.uppercase(Locale.ENGLISH)
            if (st == "REJECTED") {
                val (fix, why) = rejectionFix(o.message)
                out += Flaw(o.id, o.strategy, o.venue, Category.REJECTED, if (o.venue == Venue.LIVE) Severity.HIGH else Severity.WARN,
                    "${o.side} ${o.qty} ${o.symbol} at ${hhmmOf(o.atMs)} rejected: ${o.message.ifBlank { "no reason given" }.take(120)} - $why",
                    0.0, fix, o.symbol, o.manual)
            } else if ((st == "CANCELLED" || st == "CANCELED") && o.filled == 0 && !o.stop && !o.manual) {
                out += Flaw(o.id, o.strategy, o.venue, Category.CANCELLED, Severity.INFO,
                    "${o.side} ${o.qty} ${o.symbol} at ${hhmmOf(o.atMs)} cancelled with nothing filled" +
                        (o.message.takeIf { it.isNotBlank() }?.let { " (${it.take(80)})" } ?: ""), 0.0, symbol = o.symbol)
            }
            if (st in TERMINAL && o.filled in 1 until o.qty) {
                out += Flaw(o.id, o.strategy, o.venue, Category.PARTIAL, if (o.venue == Venue.LIVE) Severity.WARN else Severity.INFO,
                    "${o.side} ${o.symbol}: ${o.filled} of ${o.qty} filled, then ${st.lowercase(Locale.ENGLISH)}", 0.0, symbol = o.symbol, manual = o.manual)
            }
        }
        // Duplicates: alike orders (strategy, symbol, side, quantity) moments apart, both filled.
        val alive = i.orders.filter { it.filled > 0 && !it.stop }.groupBy { "${it.venue}|${it.strategy}|${it.symbol}|${it.side.uppercase(Locale.ENGLISH)}|${it.qty}" }
        for ((_, os) in alive) {
            val s = os.sortedBy { it.atMs }
            for (k in 1 until s.size) if (s[k].atMs - s[k - 1].atMs in 0..DUP_MS) {
                val o = s[k]
                out += Flaw(o.id, o.strategy, o.venue, Category.DUPLICATE, if (o.venue == Venue.LIVE) Severity.HIGH else Severity.WARN,
                    "${o.side} ${o.qty} ${o.symbol} filled twice ${(o.atMs - s[k - 1].atMs) / 1000.0}s apart (orders ${s[k - 1].id} and ${o.id})",
                    0.0, symbol = o.symbol, manual = o.manual)
            }
        }
        // Orphans: a filled live entry of a strategy whose book follows every entry, with no trade of it near.
        for (o in i.orders) {
            if (o.venue != Venue.LIVE || o.filled <= 0 || o.stop || !o.side.equals("BUY", true) || o.strategy !in i.tracked) continue
            val followed = i.trades.any { t -> t.venue == Venue.LIVE && t.symbol.equals(o.symbol, true) && abs(t.entryMs - o.atMs) <= ORPHAN_MS }
            if (!followed) out += Flaw(o.id, o.strategy, o.venue, Category.ORPHAN, Severity.HIGH,
                "BUY ${o.filled} ${o.symbol} filled at ${hhmmOf(o.atMs)} but no ${o.strategy} trade follows it", 0.0, symbol = o.symbol)
        }
        // ---- slippage: today's live fills against each strategy's own earlier p90 ----
        val base = i.fills.filter { !it.paper && it.day != today }.groupBy { "${it.strategy}|${it.leg}" }
        var unjudged = 0
        for (f in i.fills) {
            if (f.paper || f.day != today) continue
            val prior = base["${f.strategy}|${f.leg}"].orEmpty()
            if (prior.size < MIN_BASE) { unjudged++; continue }
            val p90 = Slippage.quantile(prior.map { it.rupees }, 0.9)
            if (f.rupees > 0 && f.rupees > p90 && f.ticks >= 2) {
                out += Flaw("fill:${f.at}:${f.symbol}", f.strategy, Venue.LIVE, Category.SLIPPAGE, Severity.WARN,
                    "${f.side} ${f.qty} ${f.symbol} (${f.leg.word}) paid ₹${r2(f.rupees)} a unit (${String.format(Locale.ENGLISH, "%.1f", f.ticks)} ticks) " +
                        "against its usual p90 ₹${r2(p90)} over ${prior.size} fills", f.rupees * f.qty, symbol = f.symbol, manual = f.strategy == "Manual")
            }
        }
        if (unjudged > 0) notes += "$unjudged live fill${if (unjudged == 1) "" else "s"} not judged for slippage: fewer than $MIN_BASE earlier fills of that strategy to compare with."
        // ---- the sync check and the order path's speed ----
        for (m in i.sync) {
            val cat = if (m.kind == PositionSync.Kind.STOP_MISSING) Category.STOP_NOT_RESTING else Category.SYNC
            out += Flaw("sync:${m.key}", m.who ?: "Unknown", Venue.LIVE, cat, Severity.HIGH, "Zerodha sync: ${PositionSync.say(m)}", 0.0, symbol = m.symbol)
        }
        // A paper warning says so ("Paper (simulated orders): ..."): never counted against the live path (12 Oct).
        for (w in i.speed) out += Flaw("speed:${w.hashCode()}", "Order path", if (w.startsWith("Paper")) Venue.PAPER else Venue.LIVE, Category.SLOW,
            Severity.INFO, w.take(160), 0.0)
        // ---- the trades ----
        for (t in i.trades) out += tradeFlaws(t, i.nowMs)
        // ---- 12 Oct: the day as a whole, the protections, the diary's errors ----
        out += churn(i.trades, i.day)
        out += reentries(i.trades, i.day)
        out += protections(i.protections, i.trades)
        for ((kind, n) in i.errors.entries.sortedByDescending { it.value }) if (n > 0)
            out += Flaw("err:$kind", "App", if (i.errorsLive) Venue.LIVE else Venue.PAPER, Category.ERRORS, if (n >= ERRORS_HIGH_N) Severity.HIGH else Severity.WARN,
                "\"${kind.take(100)}\" $n time${if (n == 1) "" else "s"} in the diary today", 0.0)
        return Day(i.day, i.orders.size, i.trades.size, out, summaries(i, out), notes)
    }

    /** +1 for a trade that profits from a rise of the index (a CE bought, a PE sold), -1 the other way, 0 not an option. */
    fun sideOf(t: Trade): Int {
        val s = t.symbol.uppercase(Locale.ENGLISH)
        val r = when { s.endsWith("CE") -> 1; s.endsWith("PE") -> -1; else -> 0 }
        return r * t.d
    }

    private fun closedOn(t: Trade, day: LocalDate) = !t.manual && t.closed && t.exitMs != null && AgentLessons.dayOf(t.exitMs) == day

    /**
     * Charges eating the day ([Category.CHARGES_EAT]): a bot's [CHURN_N]+ closed trades on [day] whose gross before charges is
     * under [CHURN_X] times their charges - the charges at least half the gross, or no edge at all before them (not a plain
     * losing day: a gross loss bigger than the charges is the trades' own flaws). The rupees: the charges paid.
     */
    fun churn(trades: List<Trade>, day: LocalDate): List<Flaw> {
        val out = ArrayList<Flaw>()
        for ((k, ts) in trades.filter { closedOn(it, day) && it.net != null }.groupBy { keyOf(it.strategy, it.venue) }) {
            if (ts.size < CHURN_N) continue
            val charges = ts.sumOf { it.charges }
            if (charges <= 0) continue
            val gross = ts.sumOf { (it.net ?: 0.0) + it.charges }
            val net = gross - charges
            if (gross >= CHURN_X * charges || gross < -charges) continue
            val t0 = ts.first()
            val share = if (gross > 0) " (${pct(charges / gross)} of it)" else ""
            out += Flaw("chg:$k:$day", t0.strategy, t0.venue, Category.CHARGES_EAT, if (ts.size >= CHURN_HIGH_N) Severity.HIGH else Severity.WARN,
                "${ts.size} trades made ${rsSigned(gross)} before charges and paid ${rs(charges)} in charges$share: net ${rsSigned(net)}. " +
                    "About no edge a trade, so each extra trade only adds charges (research R14: fewer entries cut the loss)", charges, symbol = t0.symbol)
        }
        return out
    }

    /**
     * The same side again and again ([Category.REENTRY]): a bot's entries on [day] per side; flagged at [REENTRY_N]+ entries on one
     * side, or [AFTER_TARGET_N]+ re-entries within [REENTRY_GAP_MS] of a target or lock on that side. The rupees: what the
     * re-entries (every entry after the side's first) lost after charges, when they lost.
     */
    fun reentries(trades: List<Trade>, day: LocalDate): List<Flaw> {
        val out = ArrayList<Flaw>()
        val todays = trades.filter { !it.manual && AgentLessons.dayOf(it.entryMs) == day }
        for ((k, ts) in todays.groupBy { "${keyOf(it.strategy, it.venue)}|${sideOf(it)}" }) {
            val s = ts.sortedBy { it.entryMs }
            if (s.size < 2) continue
            // A re-entry: bought again after the side's previous trade was out (entries at the same moment are one entry's legs).
            var afterTarget = 0
            val again0 = ArrayList<Trade>()
            for (j in 1 until s.size) {
                val prev = s[j - 1]
                val out0 = prev.exitMs ?: continue
                if (s[j].entryMs < out0) continue
                again0 += s[j]
                val pk = exitKind(prev.exitWhy)
                if ((pk == ExitKind.TARGET || pk == ExitKind.LOCK) && s[j].entryMs - out0 in 0..REENTRY_GAP_MS) afterTarget++
            }
            val entries = again0.size + 1
            if (entries < REENTRY_N && afterTarget < AFTER_TARGET_N) continue
            val again = again0.filter { it.net != null }
            val againNet = again.sumOf { it.net ?: 0.0 }
            val againGross = again.sumOf { (it.net ?: 0.0) + it.charges }
            val t0 = s.first()
            val side = when (sideOf(t0)) { 1 -> "the up side"; -1 -> "the down side"; else -> "the same side" }
            out += Flaw("reent:$k:$day", t0.strategy, t0.venue, Category.REENTRY, Severity.WARN,
                "entered $side $entries times today" + (if (afterTarget > 0) ", $afterTarget right after a target or lock" else "") +
                    (if (again.isNotEmpty()) "; the ${again.size} re-entr${if (again.size == 1) "y" else "ies"} made ${rsSigned(againGross)} before charges, " +
                        "${rsSigned(againNet)} after" else ""),
                (-againNet).coerceAtLeast(0.0), symbol = t0.symbol)
        }
        return out
    }

    /**
     * The protections ([Protect]): one that set nothing ([Category.EMPTY_PROTECT]), a target on the wrong side of the price paid
     * ([Category.WRONG_SIDE]), one put on a position a bot opened ([Category.ARM_PROTECT]). The entry and the owner come from the
     * protection itself when known, else from the trade open on that symbol at the time.
     */
    fun protections(ps: List<Protect>, trades: List<Trade>): List<Flaw> {
        val out = ArrayList<Flaw>()
        for (p in ps) {
            val open = trades.filter { it.venue == p.venue && it.symbol.equals(p.symbol, true) && it.entryMs <= p.atMs && (it.exitMs == null || it.exitMs >= p.atMs) }
                .maxByOrNull { it.entryMs }
            val ref = "prot:${p.venue.key}:${p.symbol}:${p.atMs}"
            val who = (if (p.by.isBlank()) "A protection" else "${p.by}'s protection") + " on ${p.symbol} at ${hhmmOf(p.atMs)}"
            val name = p.by.ifBlank { "Protection" }
            val byBoss = p.by.equals("Manual", true)
            if (p.stop == null && p.target == null) {
                out += Flaw(ref, name, p.venue, Category.EMPTY_PROTECT, if (p.replaced) Severity.HIGH else Severity.WARN,
                    "$who set no stop and no target" + (if (p.replaced) ": the stop that was there was cancelled and nothing replaced it" else ""),
                    0.0, symbol = p.symbol, manual = byBoss)
                continue
            }
            val entry = p.entry ?: open?.entry
            val tgt = p.target
            if (tgt != null && entry != null && entry > 0 && (if (p.long) tgt < entry - 1e-9 else tgt > entry + 1e-9)) {
                val qty = open?.qty ?: 0
                out += Flaw(ref, name, p.venue, Category.WRONG_SIDE, Severity.HIGH,
                    "$who: target ${r2(tgt)} is ${if (p.long) "under" else "over"} the ${r2(entry)} ${if (p.long) "paid" else "sold at"} - it fills at once, for a loss",
                    abs(entry - tgt) * qty, symbol = p.symbol, manual = byBoss)
            }
            val owner = p.owner ?: open?.takeIf { !it.manual }?.strategy
            if (owner != null && !owner.equals(p.by, true) && !owner.equals("Manual", true))
                out += Flaw("$ref:arm", name, p.venue, Category.ARM_PROTECT, Severity.WARN,
                    "$who: the position is $owner's, which exits by its own rules (a second stop can close it early)", 0.0, symbol = p.symbol)
        }
        return out
    }

    /** In sight on the Automation card (never behind "Show details"): the serious flaws and the classes no one saw on 12 Oct. */
    fun inSight(f: Flaw): Boolean = f.severity == Severity.HIGH ||
        f.category in setOf(Category.CHARGES_EAT, Category.REENTRY, Category.WRONG_SIDE, Category.EMPTY_PROTECT, Category.ARM_PROTECT, Category.ERRORS)

    private fun bestAfter(t: Trade): Pair<Double, Long>? {
        if (t.after.isEmpty()) return null
        val b = if (t.long) t.after.maxByOrNull { it.high } else t.after.minByOrNull { it.low }
        return b?.let { (if (t.long) it.high else it.low) to it.atMs }
    }

    /** One trade's flaws (rules, then the outcome). */
    fun tradeFlaws(t: Trade, nowMs: Long = Long.MAX_VALUE): List<Flaw> {
        val out = ArrayList<Flaw>()
        fun flaw(c: Category, s: Severity, ev: String, rupees: Double = 0.0, fix: Fix = c.fix) =
            Flaw(t.id, t.strategy, t.venue, c, s, ev, rupees, fix, t.symbol, t.manual)
        val tick = if (t.tick > 0) t.tick else 0.05
        val d = t.d
        val entryMin = minuteOf(t.entryMs)
        val who = if (t.manual) "your ${t.symbol} trade" else "${t.strategy}'s ${t.symbol} trade"
        // Rules: the entry window (a bot's own), the square-off.
        if (!t.manual && t.window != null && entryMin !in t.window)
            out += flaw(Category.OUTSIDE_WINDOW, Severity.WARN, "$who entered at ${hhmm(entryMin)}, outside its window ${hhmm(t.window.first)}-${hhmm(t.window.last)}")
        if (t.squareOff != null) {
            val sameDay = t.exitMs?.let { AgentLessons.dayOf(it) == AgentLessons.dayOf(t.entryMs) } ?: true
            val outMin = t.exitMs?.let { minuteOf(it) }
            if (sameDay && outMin != null && outMin > t.squareOff + 1)
                out += flaw(Category.PAST_SQUARE_OFF, if (t.venue == Venue.LIVE) Severity.HIGH else Severity.WARN,
                    "$who was sold at ${hhmm(outMin)}, after its square-off at ${hhmm(t.squareOff)}")
            else if (t.exitMs == null && AgentLessons.dayOf(nowMs) == AgentLessons.dayOf(t.entryMs) && minuteOf(nowMs) > t.squareOff + 1)
                out += flaw(Category.PAST_SQUARE_OFF, Severity.HIGH, "$who is still open after its square-off at ${hhmm(t.squareOff)}")
        }
        val exit = t.exit ?: return out
        val exitMs = t.exitMs
        val kind = exitKind(t.exitWhy)
        // A missed or late exit: sold well past the stop.
        var late = false
        if (t.stop != null && t.stop > 0) {
            val past = d * (t.stop - exit)
            if (past > maxOf(LATE_TICKS * tick, LATE_PCT * t.stop)) {
                late = true
                val crossed = t.path.firstOrNull { b -> if (t.long) b.low <= t.stop else b.high >= t.stop }
                val after = if (crossed != null && exitMs != null && exitMs - crossed.atMs > 2 * 60_000L)
                    "; the stop was crossed at ${hhmmOf(crossed.atMs)}, sold at ${hhmmOf(exitMs)}" else ""
                out += flaw(Category.LATE_EXIT, Severity.WARN, "$who: stop ${r2(t.stop)}, sold at ${r2(exit)} (${r2(past)} past it)$after", past * t.qty)
            }
        }
        // Exit by something its rules would not have done (the manager's own changes are its own, and said by it).
        if (!t.manual && !t.managerActed && t.ruleExitWhy != null && t.exitWhy != null) {
            val rk = exitKind(t.ruleExitWhy)
            if (kind != rk && kind != ExitKind.MANAGER && !(late && kind == ExitKind.STOP)) {
                val diff = t.ruleExit?.let { d * (it - exit) * t.qty } ?: 0.0
                out += flaw(Category.EXIT_DIFFERS, Severity.INFO,
                    "$who closed by \"${t.exitWhy.take(40)}\" at ${r2(exit)}; its rules would have closed by \"${t.ruleExitWhy.take(40)}\"" +
                        (t.ruleExit?.let { " at ${r2(it)}" } ?: ""), diff.coerceAtLeast(0.0))
            }
        } else if (!t.manual && kind == ExitKind.MANUAL) {
            out += flaw(Category.EXIT_DIFFERS, Severity.INFO, "$who was closed by hand at ${r2(exit)}, not by its rules")
        }
        // A loss bigger than the planned risk (when not already a late exit).
        if (!late && t.stop != null && t.stop > 0) {
            val planned = d * (t.entry - t.stop) * t.qty
            val loss = -d * (exit - t.entry) * t.qty
            if (planned > 0 && loss > planned * OVER_PLAN_X && loss - planned > OVER_PLAN_MIN_RS)
                out += flaw(Category.LOSS_OVER_PLAN, Severity.WARN, "$who lost ${rs(loss)} against a planned risk of ${rs(planned)} (entry ${r2(t.entry)}, stop ${r2(t.stop)})", loss - planned)
        }
        // Gave back most of its best gain.
        if (t.best != null) {
            val mfe = d * (t.best - t.entry)
            if (mfe >= maxOf(GB_MIN_PCT * t.entry, 4 * tick)) {
                val gave = d * (t.best - exit)
                if (gave / mfe >= GIVEBACK) {
                    val lock = t.entry + d * 0.5 * mfe
                    out += flaw(Category.GAVE_BACK, Severity.WARN,
                        "$who reached ${r2(t.best)} (+${r2(mfe)}, ${pct(mfe / t.entry)}) and was sold at ${r2(exit)}: gave back ${pct(gave / mfe)} of its best; " +
                            "a lock at half the gain (${r2(lock)}) is the estimate", (d * (lock - exit)).coerceAtLeast(0.0) * t.qty)
                }
            }
        }
        // Time in the trade with no move (an option bought loses its time value while it waits).
        if (exitMs != null && t.long) {
            val held = ((exitMs - t.entryMs) / 60_000L).toInt()
            val mfe = (t.best ?: maxOf(t.entry, exit)) - t.entry
            if (held >= THETA_MIN && mfe < THETA_PCT * t.entry && exit < t.entry)
                out += flaw(Category.THETA_BLEED, Severity.INFO, "$who held $held min, best only +${r2(mfe.coerceAtLeast(0.0))} (${pct(mfe.coerceAtLeast(0.0) / t.entry)}), sold ${r2(t.entry - exit)} lower",
                    (t.entry - exit) * t.qty)
        }
        // After the exit (only when the day's minutes after it are stored): evidence, never proof.
        val ba = bestAfter(t)
        if (ba != null) {
            val (px, at) = ba
            if (kind == ExitKind.STOP && t.target != null && d * (px - t.target) >= 0)
                out += flaw(Category.STOP_THEN_TARGET, Severity.INFO,
                    "$who was stopped at ${r2(exit)}, then the price reached its target ${r2(t.target)} at ${hhmmOf(at)} (evidence, not proof: a wider stop also loses more when it is hit)",
                    (d * (t.target - exit)).coerceAtLeast(0.0) * t.qty)
            else if (kind != ExitKind.STOP && kind != ExitKind.SQUARE_OFF) {
                val further = d * (px - exit)
                val own = abs(exit - t.entry)
                if (further >= maxOf(EARLY_PCT * t.entry, own) && further > 4 * tick)
                    out += flaw(Category.EARLY_EXIT, Severity.INFO,
                        "$who closed at ${r2(exit)}; the price went on to ${r2(px)} by ${hhmmOf(at)} (+${r2(further)}; evidence, not proof)", further * t.qty)
            }
        }
        return out
    }

    private fun pct(x: Double): String = "${Math.round(x * 100)}%"

    /** Per strategy and account: counts, wins, net after charges, the live slippage, and the flaws by category. */
    fun summaries(i: Inputs, flaws: List<Flaw>): List<Summary> {
        val keys = LinkedHashSet<String>()
        i.orders.forEach { keys += keyOf(it.strategy, it.venue) }
        i.trades.forEach { keys += keyOf(it.strategy, it.venue) }
        flaws.forEach { keys += keyOf(it.strategy, it.venue) }
        val today = i.day.toString()
        val slip = i.fills.filter { !it.paper && it.day == today }.groupBy { it.strategy }.mapValues { (_, fs) -> fs.sumOf { it.rupees * it.qty } }
        return keys.map { k ->
            val strategy = k.substringBeforeLast('|'); val venue = Venue.of(k.substringAfterLast('|')) ?: Venue.PAPER
            val ts = i.trades.filter { keyOf(it.strategy, it.venue) == k && it.closed }
            val fs = flaws.filter { keyOf(it.strategy, it.venue) == k }
            Summary(strategy, venue, i.orders.count { keyOf(it.strategy, it.venue) == k }, ts.size,
                ts.count { (it.net ?: 0.0) > 0 }, ts.count { (it.net ?: 0.0) < 0 }, ts.sumOf { it.net ?: 0.0 }, ts.sumOf { it.charges },
                if (venue == Venue.LIVE) slip[strategy] ?: 0.0 else 0.0,
                fs.groupBy { it.category }.mapValues { (_, xs) -> xs.size to xs.sumOf { it.rupees.coerceAtLeast(0.0) } })
        }.sortedWith(compareByDescending<Summary> { it.venue == Venue.LIVE }.thenBy { it.strategy })
    }

    /** Two reviews of one day (the evening's and the next morning's, for MCX's late trades) as one: their windows never overlap. */
    fun merge(a: Day, b: Day): Day {
        val byKey = LinkedHashMap<String, Summary>()
        for (s in a.summaries + b.summaries) {
            val o = byKey[s.key]
            byKey[s.key] = if (o == null) s else o.copy(orders = o.orders + s.orders, trades = o.trades + s.trades, wins = o.wins + s.wins,
                losses = o.losses + s.losses, net = o.net + s.net, charges = o.charges + s.charges, slippage = o.slippage + s.slippage,
                flaws = (o.flaws.keys + s.flaws.keys).associateWith { c ->
                    val x = o.flaws[c] ?: (0 to 0.0); val y = s.flaws[c] ?: (0 to 0.0); (x.first + y.first) to (x.second + y.second) })
        }
        val seen = HashSet<String>()
        val flaws = (a.flaws + b.flaws).filter { seen.add("${it.ref}|${it.category}") }
        return Day(a.day, a.orders + b.orders, a.trades + b.trades, flaws, byKey.values.toList(), (a.notes + b.notes).distinct())
    }

    // ---- what is kept day after day -------------------------------------------------------------------------------------

    /** Per (strategy, account, category): how many and the rupees. */
    data class Tally(val n: Int, val rupees: Double)

    /** One reviewed day, small: the flaws counted per "strategy|venue|category", and per "strategy|venue" what was reviewed and its net. */
    data class DayLog(val day: LocalDate, val flaws: Map<String, Tally>, val reviewed: Map<String, Int>, val nets: Map<String, Double>)

    fun log(d: Day): DayLog {
        val flaws = d.flaws.groupBy { "${keyOf(it.strategy, it.venue)}|${it.category.key}" }.mapValues { (_, xs) -> Tally(xs.size, xs.sumOf { it.rupees.coerceAtLeast(0.0) }) }
        val reviewed = d.summaries.associate { it.key to maxOf(it.orders, it.trades, 1) }
        val nets = d.summaries.filter { it.trades > 0 }.associate { it.key to it.net }
        return DayLog(d.day, flaws, reviewed, nets)
    }

    /** [logs] with [l] in (its day replaced), newest [KEEP_DAYS] kept, oldest first. */
    fun keep(logs: List<DayLog>, l: DayLog): List<DayLog> = (logs.filter { it.day != l.day } + l).sortedBy { it.day }.takeLast(KEEP_DAYS)

    // ---- patterns -------------------------------------------------------------------------------------------------------

    data class Pattern(val strategy: String, val venue: Venue, val category: Category, val count: Int, val days: Int, val rupees: Double,
                       val first: LocalDate, val last: LocalDate) {
        val key: String get() = "${keyOf(strategy, venue)}|${category.key}"
        fun words(): String = "${strategy} (${venue.word}): ${category.words} - $count times on $days days, ${AgentLessons.day(first)}-${AgentLessons.day(last)}" +
            (if (rupees > 0.5) ", ${if (category.evidenceOnly) "missed" else "cost"} about ${rs(rupees)}" else "")
    }

    /**
     * The recurring flaws: [n] or more occurrences on [minDays] or more days within the last [window] reviewed days. One-offs
     * never. Each (strategy, account, category) once; most frequent first.
     */
    fun patterns(logs: List<DayLog>, n: Int = PATTERN_N, minDays: Int = PATTERN_DAYS, window: Int = WINDOW): List<Pattern> {
        val recent = logs.sortedBy { it.day }.takeLast(window)
        val keys = recent.flatMap { it.flaws.keys }.toSet()
        return keys.mapNotNull { k ->
            val hits = recent.mapNotNull { l -> l.flaws[k]?.let { l.day to it } }
            val count = hits.sumOf { it.second.n }
            if (count < n || hits.size < minDays) return@mapNotNull null
            val p = k.split('|')
            if (p.size < 3) return@mapNotNull null
            Pattern(p[0], Venue.of(p[1]) ?: return@mapNotNull null, Category.of(p[2]) ?: return@mapNotNull null, count, hits.size,
                hits.sumOf { it.second.rupees }, hits.first().first, hits.last().first)
        }.sortedWith(compareByDescending<Pattern> { it.count }.thenBy { it.key })
    }

    /**
     * Each recurring [patterns]' days for Jarvis's lessons ([AgentLessons.OrderFlawResult]): per day the strategy was reviewed,
     * how many of its orders or trades were reviewed and how many had the flaw, and the rupees (as a loss). Only patterns
     * become lessons: a one-off never does.
     */
    fun lessonInputs(logs: List<DayLog>, patterns: List<Pattern>): List<AgentLessons.OrderFlawResult> {
        val out = ArrayList<AgentLessons.OrderFlawResult>()
        for (p in patterns) {
            val sk = keyOf(p.strategy, p.venue)
            for (l in logs) {
                val reviewed = l.reviewed[sk] ?: continue
                val t = l.flaws[p.key]
                val hits = t?.n ?: 0
                out += AgentLessons.OrderFlawResult(l.day, "${norm(p.strategy)}:${p.venue.word}:${p.category.key}",
                    "${p.strategy} (${p.venue.word}): ${p.category.words}", maxOf(reviewed, hits), hits, -(t?.rupees ?: 0.0),
                    listOf("orders", "order", "review", "flaws", p.category.group.word, p.venue.word) + words(p.strategy))
            }
        }
        return out
    }

    private fun norm(s: String) = s.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun words(s: String) = s.lowercase(Locale.ENGLISH).split(Regex("[^a-z0-9+]+")).filter { it.length > 1 }

    // ---- turning patterns into improvements, honestly ------------------------------------------------------------------

    /** A shadow variant asked of the strategy lab: [param] moved one step in [dir] (+1 up, -1 down) for [labKey]. */
    data class LabAsk(val labKey: String, val param: String, val dir: Int, val why: String)

    enum class Route(val word: String) { NEEDS_FIX("needs a fix"), LAB("tested in the strategy lab"), PROPOSAL("a proposal only"), WATCH("watched") }

    /** What a pattern becomes: a "needs a fix" flag, a lab test, or a proposal only (the lab cannot test that kind). */
    data class Improvement(val pattern: Pattern, val route: Route, val words: String, val ask: LabAsk? = null)

    /** The lab's key for a strategy's label ("ORB Fresh" -> "orb_fresh", "Liquidity 5m FINNIFTY" -> "liquidity"); null: not one the lab tunes. */
    fun labKeyOf(strategy: String): String? {
        val s = strategy.trim().lowercase(Locale.ENGLISH)
        if (s.startsWith("liquidity")) return "liquidity"
        VariantLab.STRATEGIES.firstOrNull { it.label.lowercase(Locale.ENGLISH) == s || it.key == s }?.let { return it.key }
        return when {
            s.startsWith("vix") -> "vix_div"
            else -> null
        }
    }

    /** The parameter and direction a parameter-like category asks of [labKey]'s rules; null: the lab has no such knob there. */
    fun paramFor(labKey: String, c: Category): Pair<String, Int>? {
        val orb = labKey in setOf("orb", "orb_fresh", "orb_sweep", "range_fade")
        return when (c) {
            Category.STOP_THEN_TARGET -> when {
                orb -> ArmParams.STOP_PTS to 1
                labKey == "liquidity" || labKey == "vix_div" -> ArmParams.STOP_PCT to 1
                else -> null
            }
            Category.GAVE_BACK -> when {
                orb -> ArmParams.TARGET_PTS to -1
                labKey == "liquidity" -> ArmParams.LOCK to 1
                else -> null
            }
            Category.EARLY_EXIT -> when {
                orb -> ArmParams.TARGET_PTS to 1
                labKey == "liquidity" || labKey == "vix_div" -> ArmParams.TS to 1
                else -> null
            }
            Category.THETA_BLEED -> when {
                labKey == "liquidity" || labKey == "vix_div" -> ArmParams.TS to -1
                else -> null
            }
            else -> null
        }
    }

    /** What [p] becomes. Nothing here applies anything: a lab ask is tested by the lab's own rules, a proposal is words. */
    fun improvement(p: Pattern): Improvement {
        val c = p.category
        return when {
            c == Category.CHARGES_EAT || c == Category.REENTRY ->
                Improvement(p, Route.PROPOSAL, "${p.words()}. A proposal: fewer entries (a cap on entries a side a day, as research R14 found for ORB) " +
                    "or switching it off; nothing was changed.")
            c.fix == Fix.NEEDS_FIX || (c == Category.REJECTED) ->
                Improvement(p, Route.NEEDS_FIX, "${p.words()}. Needs a fix (flagged for you and the developer); nothing was changed.")
            c.fix == Fix.LAB -> {
                val key = labKeyOf(p.strategy)
                val pd = key?.let { paramFor(it, c) }
                if (key == null || pd == null)
                    Improvement(p, Route.PROPOSAL, "${p.words()}. A proposal only: the strategy lab cannot test this kind of change for ${p.strategy}.")
                else {
                    val pr = VariantLab.strategy(key)?.param(pd.first)
                    Improvement(p, Route.LAB, "${p.words()}. Asked the strategy lab to test ${pr?.words ?: pd.first} ${if (pd.second > 0) "higher" else "lower"} " +
                        "as a shadow variant (paper first; it is promoted only if the lab's tests confirm it).",
                        LabAsk(key, pd.first, pd.second, "order review: ${c.words} (${p.count} in ${p.days} days)"))
                }
            }
            else -> Improvement(p, Route.WATCH, "${p.words()}. Watched; nothing to change from it alone.")
        }
    }

    // ---- "more profitable" measured -------------------------------------------------------------------------------------

    data class BeforeAfter(val nBefore: Int, val meanBefore: Double, val nAfter: Int, val meanAfter: Double) {
        val enough: Boolean get() = nBefore >= MIN_COMPARE_DAYS && nAfter >= MIN_COMPARE_DAYS
        fun words(): String = if (!enough) "too few days to say yet ($nBefore before, $nAfter since; $MIN_COMPARE_DAYS each needed)"
            else "net after costs ${rsSigned(meanBefore)} a day over $nBefore days before, ${rsSigned(meanAfter)} a day over $nAfter days since"
    }

    private fun rsSigned(x: Double) = (if (x < 0) "-" else "+") + rs(x)

    /** [strategy]'s net after costs per traded day on [venue], before [from] (the last [MIN_COMPARE_DAYS] * 2 days) and from it on. */
    fun beforeAfter(logs: List<DayLog>, strategy: String, venue: Venue, from: LocalDate): BeforeAfter {
        val k = keyOf(strategy, venue)
        val days = logs.mapNotNull { l -> l.nets[k]?.let { l.day to it } }.sortedBy { it.first }
        val before = days.filter { it.first.isBefore(from) }.takeLast(MIN_COMPARE_DAYS * 2).map { it.second }
        val after = days.filter { !it.first.isBefore(from) }.map { it.second }
        return BeforeAfter(before.size, if (before.isEmpty()) 0.0 else before.average(), after.size, if (after.isEmpty()) 0.0 else after.average())
    }

    // ---- the words --------------------------------------------------------------------------------------------------------

    /** "Today: 7 orders · 2 flaws (est. ₹340) · 1 improvement testing" (no rupees when [money] is false). */
    fun cardLine(d: Day?, testing: Int, today: LocalDate, money: Boolean = true): String {
        if (d == null) return "Nothing reviewed yet (the review runs after the close)"
        val label = if (d.day == today) "Today" else AgentLessons.day(d.day)
        val shown = d.flaws.count { it.severity != Severity.INFO }
        val cost = d.cost
        val count = if (d.orders == 0 && d.trades > 0) n(d.trades, "trade") else n(d.orders, "order")
        return "$label: $count · ${n(shown, "flaw")}" + (if (money && cost >= 1) " (est. ${rs(cost)})" else "") +
            (if (testing > 0) " · ${n(testing, "improvement")} testing" else "")
    }

    private fun n(x: Int, one: String) = "$x ${if (x == 1) one else one + "s"}"

    /** One flaw in a line: "ORB Fresh (paper) · gave back most of its best gain: ... · est. ₹120". */
    fun flawLine(f: Flaw, money: Boolean = true): String =
        "${f.strategy} (${f.venue.word}) · ${f.category.words}: ${f.evidence}" +
            (if (money && f.rupees >= 1) " · ${if (f.category.evidenceOnly) "missed" else "est."} ${rs(f.rupees)}" else "") +
            (if (f.fix == Fix.NEEDS_FIX) " · needs a fix" else "")

    /** One strategy's day in a line. */
    fun summaryLine(s: Summary): String {
        val top = s.flaws.entries.sortedByDescending { it.value.first }.take(2).joinToString(", ") { "${it.key.words} ×${it.value.first}" }
        return "${s.strategy} (${s.venue.word}): ${n(s.orders, "order")}, ${n(s.trades, "trade")}, ${s.wins} won ${s.losses} lost, " +
            "net ${rsSigned(s.net)} after charges ${rs(s.charges)}" + (if (s.slippage > 0.5) " (slippage ${rs(s.slippage)} in it)" else "") +
            (if (top.isNotEmpty()) " · flaws: $top" else " · no flaws")
    }

    /** The evening notification's line, only when something matters; no rupees and no account figures (it may show on a lock screen). */
    fun notice(d: Day, needsFix: Int, testing: Int): String? {
        val high = d.flaws.count { it.severity == Severity.HIGH }
        if (high == 0 && needsFix == 0) return null
        val parts = ArrayList<String>()
        if (high > 0) parts += "${n(high, "serious flaw")} in today's orders"
        if (needsFix > 0) parts += "${n(needsFix, "recurring issue")} needs a fix"
        if (testing > 0) parts += "${n(testing, "improvement")} in the lab"
        return "Order review: " + parts.joinToString(" · ") + ". Tap to read."
    }

    // ---- Jarvis -----------------------------------------------------------------------------------------------------------

    enum class AskKind { REVIEW, WRONG, STRATEGY, SLIPPAGE_COST }
    data class Ask(val kind: AskKind, val strategy: String? = null)

    private val STRATS = listOf("liquidity" to "Liquidity", "orb fresh" to "ORB Fresh", "orb sweep" to "ORB Sweep", "range fade" to "Range Fade",
        "orb" to "ORB", "pine" to "Pine", "vix" to "VIX", "night" to "Night", "hero" to "Hero", "mcx" to "MCX", "manual" to "Manual", "protections?" to "Protection")
    private val REVIEW = Regex("\\b(?:review|audit|analy[sz]e|study)\\s+(?:(?:my|all|the|today'?s|todays|the day'?s|each|every)\\s+)*(?:orders?|trades and orders|orders and trades)\\b|\\border review\\b|\\breview of (?:my |today'?s )?orders\\b")
    private val WRONG = Regex("^(?:jarvis,? )?what went wrong(?: (?:today|with my orders|with my orders today|in my orders|in my orders today|with today'?s orders))?\\??$|\\bwhat went wrong (?:with|in) (?:my |today'?s )?orders\\b|\\b(?:any|what) flaws\\b|\\bflaws? (?:in|on|with) (?:my |today'?s )?orders\\b")
    private val FLAWS_IN = Regex("\\bflaws?\\b.*\\b(?:in|on|with|for)\\b")
    private val SLIP_COST = Regex("\\bhow much (?:did|has|have) (?:the )?(?:slippage|slipage) cost\\b|\\bslippage cost (?:me|us)\\b|\\bwhat did slippage cost\\b")

    /** "Review my orders", "what went wrong today", "flaws in ORB today", "how much did slippage cost me": the ask, or null. */
    fun asked(q: String): Ask? {
        val t = q.lowercase(Locale.ENGLISH).replace('’', '\'').replace(Regex("\\s+"), " ").trim().trimEnd('?', '.', '!').trim()
        if (Regex("\\b(?:buy|sell|cancel|modify|place|square off|exit|close)\\b").containsMatchIn(t) && !Regex("\\b(?:review|flaws?|wrong)\\b").containsMatchIn(t)) return null
        val strat = STRATS.firstOrNull { (w, _) -> Regex("\\b$w\\b").containsMatchIn(t) }?.second
        if (SLIP_COST.containsMatchIn(t)) return Ask(AskKind.SLIPPAGE_COST, strat)
        if (Regex("\\bflaws?\\b").containsMatchIn(t) && strat != null && FLAWS_IN.containsMatchIn(t)) return Ask(AskKind.STRATEGY, strat)
        if (WRONG.containsMatchIn(t)) return Ask(AskKind.WRONG, strat)
        if (REVIEW.containsMatchIn(t)) return Ask(if (strat != null) AskKind.STRATEGY else AskKind.REVIEW, strat)
        return null
    }

    /** Jarvis's short factual answer from the latest reviewed [d] (null: none yet) and the [improvements]. Unlocked phone only (rupees). */
    fun answer(d: Day?, ask: Ask, improvements: List<Improvement>, today: LocalDate): String {
        if (d == null) return "I have not reviewed any orders yet, Boss. I review every order and trade once a day after the close (15:45)."
        val when_ = if (d.day == today) "today" else "on ${AgentLessons.day(d.day)}"
        fun top(fs: List<Flaw>, k: Int = 3) = fs.sortedWith(compareByDescending<Flaw> { it.severity.ordinal }.thenByDescending { it.rupees }).take(k)
        return when (ask.kind) {
            AskKind.SLIPPAGE_COST -> {
                val ss = d.summaries.filter { it.venue == Venue.LIVE && (ask.strategy == null || it.strategy.startsWith(ask.strategy, true)) }
                val total = ss.sumOf { it.slippage }
                val over = d.flaws.filter { it.category == Category.SLIPPAGE && (ask.strategy == null || it.strategy.startsWith(ask.strategy, true)) }
                if (ss.none { it.slippage != 0.0 } && over.isEmpty()) "No live fills measured $when_${ask.strategy?.let { " for $it" } ?: ""}, Boss, so no slippage to count."
                else "Slippage $when_${ask.strategy?.let { " on $it" } ?: ""}: ${rs(total)} ${if (total >= 0) "cost" else "better than meant"} across live fills" +
                    (if (over.isNotEmpty()) "; ${n(over.size, "fill")} above that strategy's usual p90 (${rs(over.sumOf { it.rupees })})." else ".") +
                    " It is already inside the nets; measured only, no order is changed by it."
            }
            AskKind.STRATEGY -> {
                val s = ask.strategy ?: return answer(d, ask.copy(kind = AskKind.REVIEW), improvements, today)
                val fs = d.flaws.filter { it.strategy.startsWith(s, true) }
                val sums = d.summaries.filter { it.strategy.startsWith(s, true) }
                if (sums.isEmpty() && fs.isEmpty()) "No $s orders or trades reviewed $when_, Boss."
                else (sums.joinToString(" ") { summaryLine(it) + "." }) + (if (fs.isEmpty()) "" else " Top: " + top(fs).joinToString("; ") { "${it.category.words}: ${it.evidence}" } + ".")
            }
            AskKind.WRONG -> {
                val fs = d.flaws.filter { it.severity != Severity.INFO }
                if (fs.isEmpty()) "Nothing went wrong in the orders $when_, Boss: ${n(d.orders, "order")} and ${n(d.trades, "trade")} reviewed, no flaw above info level."
                else "${when_.replaceFirstChar { it.uppercase() }}: ${n(fs.size, "flaw")} (est. cost ${rs(d.cost)}). " +
                    top(fs).joinToString(" ") { "${it.strategy}: ${it.category.words} - ${it.evidence}." } +
                    (improvements.firstOrNull { it.route == Route.NEEDS_FIX }?.let { " Needs a fix: ${it.pattern.strategy}'s ${it.pattern.category.words}." } ?: "")
            }
            AskKind.REVIEW -> {
                val fs = d.flaws.filter { it.severity != Severity.INFO }
                val testing = improvements.count { it.route == Route.LAB }
                "Order review $when_: ${n(d.orders, "order")}, ${n(d.trades, "trade")}, ${n(fs.size, "flaw")} (est. cost ${rs(d.cost)}" +
                    (if (d.missed >= 1) "; missed ${rs(d.missed)} as evidence only" else "") + "). " +
                    d.summaries.take(3).joinToString(" ") { summaryLine(it) + "." } +
                    (if (testing > 0) " $testing improvement${if (testing == 1) " is" else "s are"} being tested in the strategy lab (paper first)." else "") +
                    " I never place, change or cancel an order."
            }
        }
    }

    /** A locked phone: no rupees, no account figures. */
    const val LOCKED = "Unlock the phone for that, Boss."

    // ---- the kept book ----------------------------------------------------------------------------------------------------

    /** A lab ask and what became of it: QUEUED (not seeded yet), then the lab's word ([status]) and its variant. */
    data class Request(val key: String, val day: LocalDate, val ask: LabAsk, val status: String = QUEUED, val variant: String? = null, val note: String = "")

    const val QUEUED = "queued"

    /** Everything kept: the days' logs, the latest reviewed day in full, the lab requests, the proposals, the last run. */
    data class Book(
        val logs: List<DayLog> = emptyList(), val last: Day? = null, val requests: List<Request> = emptyList(),
        val proposals: List<String> = emptyList(), val lastRun: LocalDate? = null, val cutoffMs: Long = 0L, val told: Set<String> = emptySet(),
    )

    const val MAX_FLAWS = 200
    const val MAX_REQUESTS = 40
    const val MAX_PROPOSALS = 20

    private fun c(s: String) = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
    /** A key's '|' kept as '^' on the line (the line's own separator is '|'). */
    private fun kc(k: String) = k.replace('^', '/').replace('\n', ' ').replace('|', '^').replace(';', ',').replace('=', '-')
    private fun ku(k: String) = k.replace('^', '|')
    private fun tallies(m: Map<String, Tally>) = m.entries.joinToString(";") { "${kc(it.key)}=${it.value.n}:${it.value.rupees}" }
    private fun untallies(s: String): Map<String, Tally> = s.split(';').filter { '=' in it }.mapNotNull { e ->
        val v = e.substringAfterLast('='); runCatching { ku(e.substringBeforeLast('=')) to Tally(v.substringBefore(':').toInt(), v.substringAfter(':').toDouble()) }.getOrNull() }.toMap()
    private fun nums(m: Map<String, Number>) = m.entries.joinToString(";") { "${kc(it.key)}=${it.value}" }
    private fun unnums(s: String) = s.split(';').filter { '=' in it }.mapNotNull { e -> e.substringAfterLast('=').toDoubleOrNull()?.let { ku(e.substringBeforeLast('=')) to it } }.toMap()

    /** Lines: "L|day|flaws|reviewed|nets", "D|day|orders|trades", "F|...", "S|...", "N|note", "R|...", "P|text", "M|lastRun|cutoff|told". */
    fun encode(b: Book): String = buildString {
        append("M|").append(b.lastRun ?: "").append('|').append(b.cutoffMs).append('|').append(b.told.joinToString(",") { kc(it).replace(',', ';') }).append('\n')
        for (l in b.logs.takeLast(KEEP_DAYS)) append("L|${l.day}|${tallies(l.flaws)}|${nums(l.reviewed)}|${nums(l.nets)}\n")
        b.last?.let { d ->
            append("D|${d.day}|${d.orders}|${d.trades}\n")
            for (f in d.flaws.take(MAX_FLAWS)) append("F|${c(f.ref)}|${c(f.strategy)}|${f.venue.key}|${f.category.key}|${f.severity.key}|${c(f.evidence)}|${f.rupees}|${f.fix.key}|${c(f.symbol)}|${if (f.manual) 1 else 0}\n")
            for (s in d.summaries) append("S|${c(s.strategy)}|${s.venue.key}|${s.orders}|${s.trades}|${s.wins}|${s.losses}|${s.net}|${s.charges}|${s.slippage}|" +
                s.flaws.entries.joinToString(";") { "${it.key.key}=${it.value.first}:${it.value.second}" } + "\n")
            for (n in d.notes) append("N|${c(n)}\n")
        }
        for (r in b.requests.takeLast(MAX_REQUESTS)) append("R|${kc(r.key)}|${r.day}|${r.ask.labKey}|${r.ask.param}|${r.ask.dir}|${c(r.ask.why)}|${c(r.status)}|${r.variant ?: ""}|${c(r.note)}\n")
        for (p in b.proposals.takeLast(MAX_PROPOSALS)) append("P|${c(p)}\n")
    }

    /** [encode] read back; a damaged line is skipped, never fatal. */
    fun decode(text: String?): Book {
        if (text.isNullOrBlank()) return Book()
        var lastRun: LocalDate? = null; var cutoff = 0L; var told = emptySet<String>()
        val logs = ArrayList<DayLog>(); val flaws = ArrayList<Flaw>(); val sums = ArrayList<Summary>(); val notes = ArrayList<String>()
        val reqs = ArrayList<Request>(); val props = ArrayList<String>()
        var dHead: Triple<LocalDate, Int, Int>? = null
        for (line in text.lines()) runCatching {
            val f = line.split('|')
            when (f[0]) {
                "M" -> { lastRun = f[1].takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }; cutoff = f[2].toLong(); told = f.getOrElse(3) { "" }.split(',').filter { it.isNotEmpty() }.map { ku(it) }.toSet() }
                "L" -> logs += DayLog(LocalDate.parse(f[1]), untallies(f[2]), unnums(f[3]).mapValues { it.value.toInt() }, unnums(f[4]))
                "D" -> dHead = Triple(LocalDate.parse(f[1]), f[2].toInt(), f[3].toInt())
                "F" -> flaws += Flaw(f[1], f[2], Venue.of(f[3])!!, Category.of(f[4])!!, Severity.of(f[5])!!, f[6], f[7].toDouble(), Fix.of(f[8])!!, f[9], f[10] == "1")
                "S" -> sums += Summary(f[1], Venue.of(f[2])!!, f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7].toDouble(), f[8].toDouble(), f[9].toDouble(),
                    f[10].split(';').filter { '=' in it }.mapNotNull { e -> Category.of(e.substringBefore('='))?.let { cat ->
                        val v = e.substringAfter('='); cat to (v.substringBefore(':').toInt() to v.substringAfter(':').toDouble()) } }.toMap())
                "N" -> notes += f[1]
                "R" -> reqs += Request(ku(f[1]), LocalDate.parse(f[2]), LabAsk(f[3], f[4], f[5].toInt(), f[6]), f[7], f[8].takeIf { it.isNotEmpty() }, f.getOrElse(9) { "" })
                "P" -> props += f[1]
            }
        }
        val last = dHead?.let { (d, o, t) -> Day(d, o, t, flaws, sums, notes) }
        return Book(logs.sortedBy { it.day }.distinctBy { it.day }.takeLast(KEEP_DAYS), last, reqs.takeLast(MAX_REQUESTS), props.takeLast(MAX_PROPOSALS), lastRun, cutoff, told)
    }
}
