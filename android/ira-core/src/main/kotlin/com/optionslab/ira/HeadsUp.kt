package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * Heads-ups on Boss's own open positions (round 2 of the day's usefulness, 2026-10-05), said once each a day:
 *  - one position's open loss reaching half, then three quarters, of Boss's own daily loss limit for that account;
 *  - a sold option whose premium has decayed 80% or more: most of the profit is in hand, the rest is little reward
 *    for the risk still carried.
 * Words only: nothing here places, changes or closes anything - booking it, or not, is Boss's call. Pure.
 */
object HeadsUp {
    const val HALF = 0.5
    const val MOST = 0.75
    const val DECAYED = 0.8

    /**
     * One open position. [key]: what it is told under (account and symbol, e.g. "L:NIFTY..."); [live]: on Zerodha, else
     * the paper account; [qty]: signed (negative when sold).
     */
    data class Pos(val key: String, val symbol: String, val live: Boolean, val qty: Int, val avg: Double, val ltp: Double) {
        val pnl get() = (ltp - avg) * qty
    }

    enum class Kind { LOSS_HALF, LOSS_MOST, DECAYED }

    /**
     * One heads-up: [text] for the chat (named, with the numbers); [spoken] aloud with no symbol and no amounts (a
     * locked phone may be heard); [marks] the told-keys to keep, so it is not said again today.
     */
    data class Alert(val kind: Kind, val text: String, val spoken: String, val marks: Set<String>)

    private val OPTION = Regex("\\d(CE|PE)$")
    fun isOption(symbol: String) = OPTION.containsMatchIn(symbol.uppercase().trim())

    fun mark(p: Pos, k: Kind) = "${p.key}|${k.name}"

    private fun rs(x: Double) = "Rs " + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun pct(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)
    private fun where(p: Pos) = if (p.live) "Zerodha" else "paper"

    /**
     * What to say now, given [told] (the keys already said today). [liveLimit] / [paperLimit]: Boss's daily loss limit
     * for each account in rupees (0 or null: none set, so no loss heads-up). Worst first.
     */
    fun check(positions: List<Pos>, liveLimit: Double?, paperLimit: Double?, told: Set<String>): List<Alert> {
        val out = ArrayList<Pair<Double, Alert>>()
        for (p in positions) {
            if (p.qty == 0 || p.avg <= 0 || p.ltp <= 0) continue  // (no price yet: not a loss, not decayed)
            loss(p, if (p.live) liveLimit else paperLimit, told)?.let { out += -p.pnl to it }
            decay(p, told)?.let { out += 0.0 to it }
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    private fun loss(p: Pos, limit: Double?, told: Set<String>): Alert? {
        if (limit == null || limit <= 0 || p.pnl >= 0) return null
        val share = -p.pnl / limit
        val most = mark(p, Kind.LOSS_MOST); val half = mark(p, Kind.LOSS_HALF)
        return when {
            share >= MOST && most !in told -> Alert(Kind.LOSS_MOST,
                "Boss, ${p.symbol} (${where(p)}) is down ${rs(p.pnl)}: ${pct(share)} of your ${rs(limit)} daily loss limit on this one position. " +
                    "Is the reason you took it still true? Your stop, your call - I won't touch it.",
                "Boss, one of your positions has lost three quarters of your daily loss limit by itself. Have a look at it.",
                setOf(most, half))
            share >= HALF && share < MOST && half !in told -> Alert(Kind.LOSS_HALF,
                "Boss, ${p.symbol} (${where(p)}) is down ${rs(p.pnl)}: half your ${rs(limit)} daily loss limit on this one position. " +
                    "Check its stop is where you want it.",
                "Boss, one of your positions has lost half your daily loss limit by itself. Have a look at it.",
                setOf(half))
            else -> null
        }
    }

    private fun decay(p: Pos, told: Set<String>): Alert? {
        if (p.qty >= 0 || !isOption(p.symbol)) return null
        val kept = (p.avg - p.ltp) / p.avg
        val k = mark(p, Kind.DECAYED)
        if (kept < DECAYED || k in told) return null
        val left = p.ltp * -p.qty
        return Alert(Kind.DECAYED,
            "Boss, the ${p.symbol} you sold (${where(p)}) at ${AppFacts.px(p.avg)} is now ${AppFacts.px(p.ltp)}: ${pct(kept)} of the premium is yours " +
                "(${rs(p.pnl)} so far). Only about ${rs(left)} is left to earn while the risk stays. Booking it is your call - I won't touch it.",
            "Boss, an option you sold has decayed most of the way: most of its profit is in hand. Have a look.",
            setOf(k))
    }
}
