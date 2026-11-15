package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 15 Nov 2026 (Boss's ₹500 step trail per lot; failed gates, Boss's exception): "explain my bots' trades" and the weak-link answer read a
 * Liquidity trade on the step trail as such - its open line names its ₹500-a-lot stop or its trail's step, labelled, and its exits are
 * worded and counted as a stop and a profit lock.
 */
class StepTrailBotTradesTest {
    private val day: LocalDate = LocalDate.of(2026, 11, 16)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private val switches = listOf(BotTrades.Switch("Liquidity 15+5", true))

    private fun chain(t: BotTrades.Trade) = BotTrades.read(listOf(t), switches, 54_200.0 to 54_000.0, emptyList(), at(15, 20)).chains.single()

    @Test fun anOpenStepTrailTradeNamesItsStop() {
        // A lot of 30 at 300: the 15% stop is 255; -₹500 a lot is 283.35 (tighter).
        val open = BotTrades.Trade("liquidity5", "BANKNIFTY14OCT2654300CE", "CE", 30, 300.0, at(12, 0), at(11, 55), lot = 30, fixed = true)
        val e = chain(open).exit
        assertTrue(e.contains("its ₹500-a-lot stop (283.35; failed gates, Boss's exception)"), e)
        // Its best so far 340 (+₹1,200 a lot): the trail's +₹500 a lot, 316.70.
        val up = chain(open.copy(peak = 340.0)).exit
        assertTrue(up.contains("its ₹500 step trail's stop (316.70; failed gates, Boss's exception)"), up)
        val plain = chain(open.copy(fixed = false)).exit
        assertTrue(plain.contains("its stop 15% under the price paid"), plain)
        // A cheap option: the 15% is tighter, so the 15% is named.
        assertTrue(chain(open.copy(entry = 50.0)).exit.contains("its stop 15% under the price paid"))
    }

    @Test fun itsExitsAreWordedAsTheRule() {
        val base = BotTrades.Trade("liquidity5", "BANKNIFTY14OCT2654300CE", "CE", 30, 300.0, at(12, 0), at(11, 55), lot = 30, fixed = true,
            exitTime = at(12, 10), charges = 50.0)
        assertTrue(chain(base.copy(exit = 283.35, why = "₹500 stop")).exit.contains("out at its ₹500-a-lot stop (failed gates"))
        assertTrue(chain(base.copy(exit = 316.7, why = "step trail")).exit.contains("out at its ₹500 step trail (failed gates"))
    }

    @Test fun theWeakLinkCountsThemAsAStopAndAProfitLock() {
        val t = WeakLink.Trade("Liquidity 5m", at(12, 0), at(12, 3), "₹500 stop", -550.0)
        val words = WeakLink.exits(listOf(t, t.copy(why = "step trail", net = 450.0)))
        assertTrue(words.contains("1 at the stop") && words.contains("1 at the profit lock"), words)
        assertTrue(words.contains("Of the 1 stop, 1 came within"), words)
    }
}
