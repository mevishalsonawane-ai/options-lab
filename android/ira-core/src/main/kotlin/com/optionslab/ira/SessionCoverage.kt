package com.optionslab.ira

/**
 * Is every open market that needs watching watched? (12 Oct, 16:30: the market watch and the price stream ended "the market
 * closed (normal)" while MCX was open to 23:30 with three MCX arms armed; they came back at 17:01.) The watch's own "should I
 * run?" rule was the one that was wrong, and the stall alarm and the health monitor both asked that same rule - or ran inside
 * the watch that had ended - so nothing could see the gap.
 *
 * This asks a simpler, independent question from plain facts: for each market, is it open now, and is anything there to watch
 * (a position held, an arm or strategy armed)? If so and the watch is not running (or the price stream it needs is down), that
 * is a gap - one sentence per market, with what is unwatched. Pure.
 */
object SessionCoverage {
    /** One market now: [open] (its own calendar and hours), [held] positions or working orders there, [armed] what is armed on it. */
    data class Market(val name: String, val open: Boolean, val held: Int = 0, val armed: List<String> = emptyList()) {
        val needs: Boolean get() = open && (held > 0 || armed.isNotEmpty())
    }

    data class Gap(val market: String, val watch: Boolean, val text: String) {
        val key: String get() = "coverage:$market:${if (watch) "watch" else "stream"}"
    }

    private fun what(m: Market): String {
        val parts = ArrayList<String>()
        if (m.held > 0) parts += "${m.held} position${if (m.held == 1) "" else "s"} or order${if (m.held == 1) "" else "s"} open"
        if (m.armed.isNotEmpty()) parts += "${m.armed.size} armed (${AutomationSummary.names(m.armed)})"
        return parts.joinToString(" and ")
    }

    /**
     * The gaps now: a market open with something to watch while [watchAlive] is false (the market watch is not running), or while
     * [streamUp] is false (the live price stream is down; null: not needed or not known - paper without Zerodha).
     */
    fun gaps(markets: List<Market>, watchAlive: Boolean, streamUp: Boolean?): List<Gap> = markets.filter { it.needs }.mapNotNull { m ->
        when {
            !watchAlive -> Gap(m.name, true, "${m.name} is open with ${what(m)}, but the market watch is not running: stops, targets and the arms' exits there are not being watched")
            streamUp == false -> Gap(m.name, false, "${m.name} is open with ${what(m)}, but the live price stream is down: exits there decide on slower prices")
            else -> null
        }
    }

    /**
     * The coverage problems that stay open after a check found [gaps] ([told]: they outlasted the grace and were reported now):
     * none when there is no gap - every earlier coverage problem is over (17 Oct: Boss saw MCX's evening gap still listed at
     * 01:07, after MCX's close) - only the gaps reported now when they were told, and null (leave every problem as it is)
     * while a gap waits for its grace.
     */
    fun keepOpen(gaps: List<Gap>, told: Boolean): Set<String>? = when {
        gaps.isEmpty() -> emptySet()
        told -> gaps.map { it.key }.toSet()
        else -> null
    }

    /**
     * The watch's own "should I run?" for the markets Session coverage looks at (17 Oct; the watch's loop asks it): NSE's window
     * ([nseWindow]: its session, and a quarter-hour after F&O's close while a strategy run is open), or any of [markets] that
     * needs watching - read only when NSE's window has not already answered. Never false where [gaps] would find a gap for a
     * watch that is not running - the two read the same facts.
     */
    fun watchShouldRun(nseWindow: Boolean, markets: () -> List<Market>): Boolean = nseWindow || markets().any { it.needs }

    /**
     * 16 Oct (Boss's diagnostics: "MCX is open with 2 armed, but the market watch is not running" at 09:01 and 17:56): the
     * dead-man check that found the gap is also what starts the watch (MCX's 09:00 open, a restart after Android ended the
     * app), and it judged the gap from the "alive" it read before that start, so every such start was reported as a SERIOUS
     * problem. A gap is now told only when it is still there [GRACE_MS] after it was first seen (the start takes seconds;
     * Boss's restarts took about 16 s); the check looks again once the grace is over.
     */
    const val GRACE_MS = 2 * 60_000L

    /** A new gap within this long of the last one is told at once: a watch that keeps dying is not hidden by the grace. */
    const val REPEAT_MS = 30 * 60_000L

    /**
     * When a gap was first seen; [report] is true once it has lasted [graceMs], or at once when it began within [repeatMs] of
     * the previous gap's start (the watch keeps ending and being brought back). Thread-safe; memory only.
     */
    class GapTimer(private val graceMs: Long = GRACE_MS, private val repeatMs: Long = REPEAT_MS) {
        private var since = 0L
        private var lastStart = 0L

        /** [gap] seen at [now]: true when it was first seen at least [graceMs] ago, or it is a repeat (no gap clears it). */
        @Synchronized fun report(gap: Boolean, now: Long): Boolean {
            if (!gap) { since = 0L; return false }
            if (since == 0L || now < since) {
                val repeat = lastStart > 0L && now >= lastStart && now - lastStart <= repeatMs
                since = now; lastStart = now
                return repeat
            }
            return now - since >= graceMs
        }

        /** A gap is waiting for its grace to end (the check should look again). */
        @Synchronized fun pending(): Boolean = since != 0L
    }
}
