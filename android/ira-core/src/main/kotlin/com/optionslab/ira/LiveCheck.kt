package com.optionslab.ira

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The Live check (18 Oct 2026; Boss: "test each and everything with actual works on live, like zerodha orders, position,
 * prices ... should work at its minimum time ... nothing should fail"). Boss runs it on the phone in market hours; it proves
 * each live piece works and how fast, and it never places an order: every Zerodha call it makes reads (the margin checks
 * are Zerodha's read-only calculators), and its one trade is on a private paper book that is never saved.
 *
 * Pure (no Android, no network): the app's [com.optionslab.app.data.LiveSelfTest] runs its steps through a [Runner], which
 *  - times each step, judges it against its budget (a pass over its budget is [Verdict.WARN], "slow"), and gives up on it at
 *    its time limit ([Verdict.FAIL], "no answer in N s");
 *  - never lets one step's failure stop the others (an error is that step's FAIL, with a reason cleaned by [clean]);
 *  - hands every step on as it finishes, for the card.
 * The steps' own thresholds (ticks a second, gaps, drift, ages) are here too ([Budgets], [rate], [gap], [agree], [drift],
 * [age]), so each is unit-tested on the JVM.
 *
 * Results carry counts, timings and pass / fail only: never an amount, a token or an order id.
 */
object LiveCheck {
    enum class Verdict { PASS, WARN, FAIL, CLOSED, SKIPPED }

    /** The card's groups, in the card's order. */
    enum class Group(val label: String) {
        ACCOUNT("Zerodha account"),
        STREAM("Price stream"),
        CHAIN("Option chain"),
        ORDER("Order readiness (nothing sent)"),
        POSITIONS("Positions and stops"),
        PAPER("Paper round trip on live prices"),
        FEEDS("Data feeds"),
        CLOCK("Clock"),
    }

    data class Step(val group: Group, val name: String, val verdict: Verdict, val detail: String, val ms: Long, val budgetMs: Long? = null) {
        /** Not a failure (a pass, a slow pass, the market closed or skipped). */
        val ok: Boolean get() = verdict != Verdict.FAIL
        /** The card's mark: ✓ pass, ! slow or a warning, ✗ fail, – closed or skipped. */
        val mark: String get() = when (verdict) {
            Verdict.PASS -> "✓"
            Verdict.WARN -> "!"
            Verdict.FAIL -> "✗"
            Verdict.CLOSED, Verdict.SKIPPED -> "–"
        }
    }

    /** What a step's own code says, before its time is judged. */
    data class Said(val verdict: Verdict, val detail: String)

    fun pass(detail: String) = Said(Verdict.PASS, detail)
    fun warn(detail: String) = Said(Verdict.WARN, detail)
    fun fail(detail: String) = Said(Verdict.FAIL, detail)
    fun closed(detail: String = MARKET_CLOSED) = Said(Verdict.CLOSED, detail)
    fun skipped(detail: String) = Said(Verdict.SKIPPED, detail)

    /** A stream or paper step outside market hours: said instead of failing. */
    const val MARKET_CLOSED = "market closed"

    /**
     * The budgets each step is judged by (milliseconds unless named otherwise). Generous on purpose: a pass means the piece
     * is fast enough to trade on, a WARN that it works but slower than it should, a FAIL that it does not work in time.
     */
    object Budgets {
        /** One Zerodha REST read (100-400 ms typical on mobile data; the relay adds a round trip). */
        const val READ_MS = 1_500L
        /** Today's contract list (a few MB the first time; then the day's copy on the phone). */
        const val CONTRACTS_MS = 5_000L
        /** The stream's socket open (TLS handshake included). */
        const val CONNECT_MS = 3_000L
        /** From asking for an instrument to its first tick (Kite pushes an index about once a second). */
        const val FIRST_TICK_MS = 2_000L
        /** A first tick or a connect not seen in this long fails. */
        const val STREAM_TIMEOUT_MS = 10_000L
        /** The window ticks are counted over. */
        const val WINDOW_MS = 10_000L
        /** Ticks a second over the window: at least [RATE_PASS] passes, at least [RATE_WARN] warns. */
        const val RATE_PASS = 1.0
        const val RATE_WARN = 0.3
        /** The largest gap between ticks: [GAP_PASS_MS] passes, up to the stall watchdog's [StreamHealth.SILENCE_MS] warns. */
        const val GAP_PASS_MS = 2_000L
        val GAP_WARN_MS = StreamHealth.SILENCE_MS
        /** Stream price against Zerodha's REST quote, as a fraction: within [AGREE_PASS] passes, within [AGREE_WARN] warns. */
        const val AGREE_PASS = 0.0005
        const val AGREE_WARN = 0.0025
        /** Back up after a forced drop (the stream's first retry waits 0.5 s). */
        const val RECONNECT_MS = 3_000L
        const val RECONNECT_TIMEOUT_MS = 30_000L
        /**
         * Each open position's last stop check should be younger than this (Boss's rule: full cadence, every 15 s at worst); the
         * stream checks it on every tick. Older but under [WATCH_FAIL_MS] warns (the slow watch loop waits 15 s after each pass,
         * so a pass that took a few seconds lands just over 15 s with nothing wrong); older than that, or never, fails.
         */
        const val WATCH_AGE_MS = 15_000L
        const val WATCH_FAIL_MS = 30_000L
        /** A feed's last 1-minute bar in market hours: [BAR_PASS_SEC] passes, [BAR_WARN_SEC] warns. */
        const val BAR_PASS_SEC = 120L
        const val BAR_WARN_SEC = 300L
        /** The phone clock against the exchange's: [DRIFT_PASS_MS] passes, [DRIFT_WARN_MS] warns. */
        const val DRIFT_PASS_MS = 500L
        const val DRIFT_WARN_MS = 2_000L
        /** The paper round trip's steps, each in memory on the phone. */
        const val PAPER_PLACE_MS = 500L
        const val PAPER_FILL_MS = 2_000L
        const val PAPER_ATTACH_MS = 500L
        const val PAPER_CLOSE_MS = 1_000L
        /** Building and checking an order or a GTT on the phone. */
        const val BUILD_MS = 200L
        /** Any step not answered in this long fails. */
        const val TIMEOUT_MS = 20_000L
    }

    /**
     * Runs the app's steps one at a time. Each block runs in [context] (the app's IO dispatcher: never the screen's thread),
     * under its own time limit; whatever it throws is that step's FAIL (a cancellation of the whole run is passed on).
     */
    class Runner(
        private val context: CoroutineContext = EmptyCoroutineContext,
        private val clock: () -> Long = System::currentTimeMillis,
        private val reason: (Throwable) -> String = { it.javaClass.simpleName },
        private val onStep: (Step) -> Unit = {},
    ) {
        private val done = ArrayList<Step>()
        val steps: List<Step> get() = synchronized(done) { done.toList() }

        suspend fun step(group: Group, name: String, budgetMs: Long, timeoutMs: Long = Budgets.TIMEOUT_MS, block: suspend () -> Said): Step {
            val t0 = clock()
            val s = try {
                val said = withTimeoutOrNull(timeoutMs) { withContext(context) { block() } }
                val ms = (clock() - t0).coerceAtLeast(0L)
                if (said == null) Step(group, name, Verdict.FAIL, "no answer in ${seconds(timeoutMs)}", ms, budgetMs)
                else judge(group, name, said, ms, budgetMs)
            } catch (e: TimeoutCancellationException) {
                // A time limit inside the step's own code (not this run being cancelled).
                Step(group, name, Verdict.FAIL, "timed out", (clock() - t0).coerceAtLeast(0L), budgetMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Step(group, name, Verdict.FAIL, clean(runCatching { reason(e) }.getOrDefault(e.javaClass.simpleName)), (clock() - t0).coerceAtLeast(0L), budgetMs)
            }
            synchronized(done) { done += s }
            onStep(s)
            return s
        }
    }

    /** A step's verdict with its time: a pass that took longer than [budgetMs] is a WARN ("slow"); the rest stand. */
    fun judge(group: Group, name: String, said: Said, ms: Long, budgetMs: Long): Step =
        if (said.verdict == Verdict.PASS && ms > budgetMs)
            Step(group, name, Verdict.WARN, "${said.detail} · slow (budget $budgetMs ms)", ms, budgetMs)
        else Step(group, name, said.verdict, said.detail, ms, budgetMs)

    private fun seconds(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000} s" else "$ms ms"

    private val TOKENISH = Regex("[A-Za-z0-9_\\-+/=]{24,}")
    private val LONG_NUMBER = Regex("\\d{9,}")
    private val RUPEES = Regex("(₹|Rs\\.?|INR)\\s*[\\d,]+(\\.\\d+)?", RegexOption.IGNORE_CASE)
    /** A decimal amount, but never an IPv4 address (the static-IP step names both addresses). */
    private val DECIMAL = Regex("(?<!\\d)(?<!\\d\\.)\\d[\\d,]*\\.\\d+(?!\\.?\\d)")
    /** A whole number after a money word ("Required margin is 52345", "available cash: 1,200"): an amount too. */
    private val AMOUNT = Regex("\\b(margin|funds?|required|available|cash|balance|amount|value|premium|limit|exposure|need|needed|short)\\b([^0-9.]{0,24})\\d[\\d,]*",
        RegexOption.IGNORE_CASE)

    /**
     * An error's words as a step may show them: no token-like string, no order id (9+ digits), no rupee figure, decimal amount
     * or whole number after a money word (Zerodha's margin refusal names both what is needed and what is free). Never longer
     * than 160 characters.
     */
    fun clean(text: String): String {
        val t = text.replace(TOKENISH, "…").replace(RUPEES, "₹…").replace(LONG_NUMBER, "#").replace(DECIMAL, "#").replace(AMOUNT, "$1$2#")
            .replace(Regex("\\s+"), " ").trim()
        return (if (t.length > 160) t.take(159) + "…" else t).ifEmpty { "failed" }
    }

    // ---- the steps' own thresholds ---------------------------------------------------------------------------------

    /** [count] ticks over [ms]: per second (0 for no time). */
    fun perSecond(count: Long, ms: Long): Double = if (ms <= 0) 0.0 else count * 1000.0 / ms

    /** Ticks a second over the window, judged ("4.2 ticks a second over 10 s (42 ticks)"). */
    fun rate(count: Long, ms: Long): Said {
        val r = perSecond(count, ms)
        val text = "%.1f ticks a second over %d s (%d ticks)".format(java.util.Locale.ROOT, r, ms / 1000, count)
        return when {
            r >= Budgets.RATE_PASS -> pass(text)
            r >= Budgets.RATE_WARN -> warn("$text · fewer than ${Budgets.RATE_PASS.toInt()} a second")
            else -> fail(if (count == 0L) "no ticks in ${ms / 1000} s" else "$text · far too few")
        }
    }

    /**
     * The largest gap between ticks seen at [arrivals] (phone ms, any order, repeats ignored) within [from]..[to], the window's
     * own start and end counted as edges (a stream that stopped half way shows the silence to the end).
     */
    fun largestGap(arrivals: Collection<Long>, from: Long, to: Long): Long {
        val inside = arrivals.filter { it in from..to }.distinct().sorted()
        if (inside.isEmpty()) return (to - from).coerceAtLeast(0L)
        var worst = inside.first() - from
        for (i in 1 until inside.size) worst = maxOf(worst, inside[i] - inside[i - 1])
        return maxOf(worst, to - inside.last())
    }

    /** The largest gap judged; [sampleMs]: how often the arrivals were looked at (a gap is known to within it). */
    fun gap(gapMs: Long, sampleMs: Long): Said {
        val text = "largest gap $gapMs ms (looked at every $sampleMs ms)"
        return when {
            gapMs <= Budgets.GAP_PASS_MS -> pass(text)
            gapMs <= Budgets.GAP_WARN_MS -> warn("$text · over ${Budgets.GAP_PASS_MS} ms")
            else -> fail("$text · the stall watchdog would have closed the stream")
        }
    }

    /** The stream's price against Zerodha's REST quote of the same instrument (no price shown: only how far apart). */
    fun agree(stream: Double?, rest: Double?): Said {
        if (stream == null || stream <= 0 || !stream.isFinite()) return fail("no stream price to compare")
        if (rest == null || rest <= 0 || !rest.isFinite()) return fail("no REST quote to compare")
        val off = kotlin.math.abs(stream - rest) / rest
        val text = "within %.3f%% of the REST quote".format(java.util.Locale.ROOT, off * 100)
        return when {
            off <= Budgets.AGREE_PASS -> pass(text)
            off <= Budgets.AGREE_WARN -> warn(text + " · over %.2f%%".format(java.util.Locale.ROOT, Budgets.AGREE_PASS * 100))
            else -> fail("$text · the stream and the REST quote disagree")
        }
    }

    /** The phone clock's offset against the exchange's (+: the phone is ahead), judged; null: nothing measured yet. */
    fun drift(offsetMs: Long?, source: String): Said {
        if (offsetMs == null) return warn("not measured yet ($source)")
        val a = kotlin.math.abs(offsetMs)
        val text = "phone ${if (offsetMs >= 0) "ahead" else "behind"} by $a ms ($source)"
        return when {
            a <= Budgets.DRIFT_PASS_MS -> pass(text)
            a <= Budgets.DRIFT_WARN_MS -> warn("$text · over ${Budgets.DRIFT_PASS_MS} ms")
            else -> fail("$text · set the phone's time to automatic")
        }
    }

    /** A feed's last 1-minute bar [ageSec] old in market hours, judged; null: no bar today. */
    fun age(ageSec: Long?, feed: String): Said {
        if (ageSec == null) return fail("$feed: no bar today")
        val text = "$feed: last bar ${ageSec} s old"
        return when {
            ageSec <= Budgets.BAR_PASS_SEC -> pass(text)
            ageSec <= Budgets.BAR_WARN_SEC -> warn("$text · over ${Budgets.BAR_PASS_SEC} s")
            else -> fail("$text · the feed is stale")
        }
    }

    /** One open position as the watch holds it: its stop and target known, and its last check's age (null: never checked). */
    data class Held(val paper: Boolean, val stop: Boolean, val target: Boolean, val checkedAgoMs: Long?)

    /**
     * Every open position watched: each one checked within [Budgets.WATCH_AGE_MS] (a WARN up to [Budgets.WATCH_FAIL_MS], a FAIL
     * beyond it or never checked), each with a stop the check can see (a WARN otherwise: an arm may keep its stop in its own
     * book, out of this check's sight).
     */
    fun watched(held: List<Held>): Said {
        if (held.isEmpty()) return pass("nothing open")
        val stale = held.count { it.checkedAgoMs == null || it.checkedAgoMs >= Budgets.WATCH_FAIL_MS }
        val late = held.count { it.checkedAgoMs != null && it.checkedAgoMs >= Budgets.WATCH_AGE_MS && it.checkedAgoMs < Budgets.WATCH_FAIL_MS }
        val stops = held.count { it.stop }
        val targets = held.count { it.target }
        val worst = held.mapNotNull { it.checkedAgoMs }.maxOrNull()
        val text = "${held.size} open (${held.count { it.paper }} paper, ${held.count { !it.paper }} live): $stops with a stop, $targets with a target, " +
            (worst?.let { "last checked at most ${it / 1000} s ago" } ?: "never checked")
        return when {
            stale > 0 -> fail("$text · $stale not checked in the last ${Budgets.WATCH_FAIL_MS / 1000} s")
            late > 0 -> warn("$text · $late not checked in the last ${Budgets.WATCH_AGE_MS / 1000} s")
            stops < held.size -> warn("$text · ${held.size - stops} with no stop this check can see (an arm may hold it in its own book)")
            else -> pass(text)
        }
    }

    // ---- the card, the diary and Jarvis -------------------------------------------------------------------------------

    /** The steps by group, in the card's order (groups with no step left out). */
    fun grouped(steps: List<Step>): List<Pair<Group, List<Step>>> =
        Group.entries.mapNotNull { g -> steps.filter { it.group == g }.takeIf { it.isNotEmpty() }?.let { g to it } }

    /** A group's heading: "Price stream · 5 of 6 passed". */
    fun groupLine(group: Group, steps: List<Step>): String {
        val counted = steps.filter { it.verdict != Verdict.CLOSED && it.verdict != Verdict.SKIPPED }
        return if (counted.isEmpty()) "${group.label} · ${if (steps.all { it.verdict == Verdict.CLOSED }) MARKET_CLOSED else "skipped"}"
        else "${group.label} · ${counted.count { it.verdict == Verdict.PASS }} of ${counted.size} passed"
    }

    /**
     * The one-line verdict: "23 of 24 passed, 1 failed, slowest: Paper fill 840 ms". Market-closed and skipped steps are
     * not counted as passes or failures (they are named after them).
     */
    fun summary(steps: List<Step>): String {
        if (steps.isEmpty()) return "No checks run yet"
        val counted = steps.filter { it.verdict != Verdict.CLOSED && it.verdict != Verdict.SKIPPED }
        val parts = ArrayList<String>()
        parts += "${counted.count { it.verdict == Verdict.PASS }} of ${counted.size} passed"
        counted.count { it.verdict == Verdict.WARN }.takeIf { it > 0 }?.let { parts += "$it slow or warned" }
        counted.count { it.verdict == Verdict.FAIL }.takeIf { it > 0 }?.let { parts += "$it failed" }
        steps.count { it.verdict == Verdict.CLOSED }.takeIf { it > 0 }?.let { parts += "$it waiting for market hours" }
        steps.count { it.verdict == Verdict.SKIPPED }.takeIf { it > 0 }?.let { parts += "$it skipped" }
        counted.maxByOrNull { it.ms }?.let { parts += "slowest: ${it.name} ${it.ms} ms" }
        return parts.joinToString(", ")
    }

    /** The diagnostics diary's line for a step ("FAILED Price stream · First tick: NIFTY 50: no answer in 10 s (10004 ms)"). */
    fun diaryLine(s: Step): String {
        val word = when (s.verdict) {
            Verdict.PASS -> "ok"
            Verdict.WARN -> "WARN"
            Verdict.FAIL -> "FAILED"
            Verdict.CLOSED -> "closed"
            Verdict.SKIPPED -> "skipped"
        }
        return "$word ${s.group.label} · ${s.name}: ${s.detail} (${s.ms} ms)"
    }

    /** The diary's area for the check's lines; the summary line starts with [SUMMARY]. */
    const val AREA = "live-check"
    const val SUMMARY = "summary: "

    /** The latest summary in the diary's [lines] ("MM-dd HH:mm:ss [live-check] summary: ..."), with its stamp; null: none. */
    fun lastSummary(lines: List<String>): String? = lines.lastOrNull { "[$AREA] $SUMMARY" in it }?.let { l ->
        val stamp = l.substringBefore(" [").trim()
        "Live check (last run $stamp): " + l.substringAfter("[$AREA] $SUMMARY").trim()
    }

    /**
     * 27 Oct (the 23 Oct file said only "Live check at 09:20: 1 failed"): the failed steps by name, "Price stream · First tick;
     * Paper · Round trip" (up to [max], then "and N more"); null when none failed.
     */
    fun failedNames(steps: List<Step>, max: Int = 3): String? {
        val bad = steps.filter { it.verdict == Verdict.FAIL }
        if (bad.isEmpty()) return null
        return bad.take(max).joinToString("; ") { "${it.group.label} · ${it.name}" } + if (bad.size > max) "; and ${bad.size - max} more" else ""
    }

    /**
     * The last run's failed steps from the diary's [lines] (the "FAILED ..." lines between the last summary and the one before
     * it), each with its reason; empty when the last run had none or there is no run.
     */
    fun lastFailures(lines: List<String>): List<String> {
        val end = lines.indexOfLast { "[$AREA] $SUMMARY" in it }
        if (end < 0) return emptyList()
        val start = lines.subList(0, end).indexOfLast { "[$AREA] $SUMMARY" in it } + 1
        return lines.subList(start, end).filter { "[$AREA] FAILED " in it }.map { it.substringAfter("[$AREA] FAILED ").trim() }
    }

    // ---- 09:20 on a trading day, behind its switch (off by default) -----------------------------------------------------

    /** The automatic run's window: from 09:20, for 15 minutes (a phone asleep at 09:20 still runs it once soon after). */
    const val AUTO_FROM_MIN = 9 * 60 + 20
    const val AUTO_UNTIL_MIN = 9 * 60 + 35

    /** Run the automatic check now: its switch on, a trading day, in its window, and not run by it today yet. */
    fun autoDue(on: Boolean, tradingDay: Boolean, minute: Int, doneOn: LocalDate?, today: LocalDate): Boolean =
        on && tradingDay && minute in AUTO_FROM_MIN until AUTO_UNTIL_MIN && doneOn != today

    // ---- "Jarvis, run the live check" ---------------------------------------------------------------------------------

    private val ASK = Regex("\\b(live|self)[ -]?(check|test)\\b|\\bcheck everything live\\b", RegexOption.IGNORE_CASE)

    private val ABOUT_RESULT = Regex("\\b(what|why|when|which|result|results|last|show|find|found|fail|failed|failing|passed|say|said|kya hua|kab)\\b",
        RegexOption.IGNORE_CASE)

    /** Boss names the check: "run the live check", "live check karo", "do the self-test" - or asks about its last result. */
    fun asked(q: String): Boolean = ASK.containsMatchIn(q)

    /**
     * Asked about the last run, not for a new one ("what did the live check find?", "why did the self-test fail?"): answered
     * from the last result (a run drops the stream and trades on paper, so a question never starts one).
     */
    fun resultAsked(q: String): Boolean = asked(q) && ABOUT_RESULT.containsMatchIn(q)

    /** Jarvis's answer once it ran: the summary, then up to three failures by name with their reason. */
    fun answer(steps: List<Step>): String {
        if (steps.isEmpty()) return "The live check did not run, Boss."
        val bad = steps.filter { it.verdict == Verdict.FAIL }
        val head = "Live check done, Boss: ${summary(steps)}."
        if (bad.isEmpty()) return head
        val named = bad.take(3).joinToString("; ") { "${it.name} (${it.detail})" }
        return "$head Failed: $named${if (bad.size > 3) "; and ${bad.size - 3} more on the card" else ""}."
    }
}
