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
}
