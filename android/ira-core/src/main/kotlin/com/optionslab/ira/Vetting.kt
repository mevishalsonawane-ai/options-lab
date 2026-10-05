package com.optionslab.ira

import java.util.Locale

/**
 * The local market expert's last gate (part 7 of "an AGI for this app"): a strategy that passed its two-year backtest
 * still trades on paper first; only its forward paper record says whether it held up. Held up: [MIN_TRADES] paper
 * trades or more, a net profit, a profit factor of [MIN_PF] or more, and its worst run of losses within [MAX_DD_SHARE]
 * of its gross profit. Failed: as many trades and a net loss. Anything else is still being tested. Jarvis brings Boss
 * only what held up - and going live stays Boss's own step (arming in Live with his PIN). Pure.
 */
object Vetting {
    const val MIN_TRADES = 15
    const val MIN_PF = 1.2
    const val MAX_DD_SHARE = 0.6

    enum class State { TESTING, HELD_UP, FAILED }

    data class Verdict(val name: String, val state: State, val trades: Int, val net: Double, val pf: Double?, val maxDd: Double, val winRate: Double) {
        fun text(): String = when (state) {
            State.TESTING -> "$name is still on its paper test: $trades of $MIN_TRADES trades, ${AppFacts.rs(net)} so far."
            // (The name is never part of a format string: a "%" in it must not break the sentence.)
            State.HELD_UP -> "$name held up on paper: $trades trades, ${AppFacts.rs(net)}, " + "won %.0f%%, profit factor %s, worst run -Rs %,.0f.".format(Locale.ENGLISH, winRate * 100, pf?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "no losses", maxDd)
            State.FAILED -> "$name failed its paper test: $trades trades, ${AppFacts.rs(net)}, " + "won %.0f%%.".format(Locale.ENGLISH, winRate * 100)
        }
    }

    /** [nets]: each closed paper trade's rupees, oldest first. */
    fun judge(name: String, nets: List<Double>): Verdict {
        val net = nets.sum()
        val gain = nets.filter { it > 0 }.sum(); val loss = -nets.filter { it < 0 }.sum()
        val pf = if (loss > 0) gain / loss else null
        var peak = 0.0; var run = 0.0; var dd = 0.0
        for (n in nets) { run += n; peak = maxOf(peak, run); dd = maxOf(dd, peak - run) }
        val win = if (nets.isEmpty()) 0.0 else nets.count { it > 0 }.toDouble() / nets.size
        val state = when {
            nets.size < MIN_TRADES -> State.TESTING
            net <= 0 -> State.FAILED
            (pf == null || pf >= MIN_PF) && dd <= gain * MAX_DD_SHARE -> State.HELD_UP
            else -> State.TESTING                                   // making a little, not convincingly: more trades
        }
        return Verdict(name, state, nets.size, net, pf, dd, win)
    }

    /** "What held up": the held-up first (best net first), then the failed, then those still testing. */
    fun say(vs: List<Verdict>): String {
        if (vs.isEmpty()) return "Nothing is on a paper test yet, Boss: strategies I find (or you add) trade on paper first, and I judge them after $MIN_TRADES trades."
        val held = vs.filter { it.state == State.HELD_UP }.sortedByDescending { it.net }
        val failed = vs.filter { it.state == State.FAILED }.sortedBy { it.net }
        val testing = vs.filter { it.state == State.TESTING }.sortedByDescending { it.trades }
        val head = if (held.isEmpty()) "Nothing has held up on paper yet, Boss." else "Held up on paper, Boss: "
        return head + (held + failed + testing).joinToString(" ") { it.text() } +
            if (held.isNotEmpty()) " Going live is your step: arm it in Live with your PIN." else ""
    }

    fun asked(text: String): Boolean = rx("(?i)\\b(what|which)( strategies| ideas| arms)? (held up|has held up|have held up|passed|works?)\\b|\\bpaper tests?\\b|\\bbest (idea|strategy|strategies)\\b|\\bwhat (should|can) i (take|trade) live\\b").containsMatchIn(text)
}
