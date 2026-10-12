package com.optionslab.engine.orb

import java.time.LocalDate

/**
 * Liquidity 15+5's evening replay of one book's entry decisions on a day (Boss's go-live checklist, 12 Oct: "at least 90% of
 * the paper trades match the evening replay"): the day's index minutes (with the earlier sessions the levels need) folded
 * into the book's bars, and every completed bar of the day decided as the app decides it live ([LiquidityRules.signal] on the
 * bars up to it, the entry hours, the room filter, the 1-ITM strike). It ignores whether the book was holding: the checklist
 * compares each paper entry with these signals and counts a signal the book did not take only while it was flat.
 * Pure: no clock, no network, no orders.
 */
object LiquidityDayReplay {
    /** One replayed decision: the signal [bar] (its start), the side (+1 call, -1 put), the strike bought and the level broken. */
    data class Signal(val bar: java.time.LocalDateTime, val side: Int, val strike: Int, val level: Double)

    /** [arm]'s signals on [day] from [ones] (the index's 1-minute bars: earlier sessions and the day). */
    fun signals(arm: Arm, ones: List<Bar>, day: LocalDate): List<Signal> {
        val tf = LiquidityRules.minutesOf(arm)
        val und = LiquidityRules.underlyingOf(arm)
        val all = LiquidityRules.fold(ones.filter { !it.start.toLocalDate().isAfter(day) }, tf)
        val out = ArrayList<Signal>()
        for (i in all.indices) {
            val bar = all[i]
            if (bar.start.toLocalDate() != day) continue
            if (i + 1 < 2 * LiquidityRules.SWING_LOOKBACK + 2) continue
            if (!LiquidityRules.mayEnterAt(bar.start.plusMinutes(tf.toLong()))) continue
            val bars = all.subList(0, i + 1)
            val s = LiquidityRules.signal(bars, LiquidityRules.zones(bars)) ?: continue
            if (!LiquidityRules.hasRoom(s, bar.close, und)) continue
            out += Signal(bar.start, s.side, LiquidityRules.entryStrike(s.side, bar.close, und), s.level)
        }
        return out
    }
}
