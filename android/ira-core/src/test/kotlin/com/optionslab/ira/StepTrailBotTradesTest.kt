package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 15 Nov 2026 (Boss's stop rule, third version: breakeven at +10%; failed gates, Boss's exception): "explain my bots' trades"
 * and the weak-link answer read a Liquidity trade on the rule as such - its open line names its own stop or, once earned, its breakeven, labelled,
 * and its exits (the morning's ₹500 step trail's too, on older records) are
 * worded and counted as a stop and a profit lock.
 */
class StepTrailBotTradesTest {
    private val day: LocalDate = LocalDate.of(2026, 11, 16)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private val switches = listOf(BotTrades.Switch("Liquidity 15+5", true))

    private fun chain(t: BotTrades.Trade) = BotTrades.read(listOf(t), switches, 54_200.0 to 54_000.0, emptyList(), at(15, 20)).chains.single()

    private fun two(x: Double) = String.format(java.util.Locale.ENGLISH, "%.2f", x)

    @Test fun anOpenRuleTradeNamesItsStop() {
        // A lot of 30 at 500: the rule sets no entry stop, so the 15% stop (425) is named until the option has been 10% up.
        val open = BotTrades.Trade("liquidity5", "BANKNIFTY14OCT2654300CE", "CE", 30, 500.0, at(12, 0), at(11, 55), lot = 30, fixed = true)
        assertNull(com.optionslab.engine.orb.StepTrail.stopLevel(500.0, 30))
        assertTrue(chain(open).exit.contains("its stop 15% under the price paid (425.00)"), chain(open).exit)
        // Under +10% (best 545, +9%): still the 15% stop.
        assertTrue(chain(open.copy(peak = 545.0)).exit.contains("its stop 15% under the price paid"))
        // Its best so far 551 (+10.2%): the breakeven, the fill plus the charges, labelled.
        val be = com.optionslab.engine.orb.StepTrail.breakevenLevel(500.0, 30)!!
        val up = chain(open.copy(peak = 551.0)).exit
        assertTrue(up.contains("its breakeven stop, moved there at +10% (${two(be)}; failed gates, Boss's exception)"), up)
        // Without the rule: the 15% whatever the best.
        assertTrue(chain(open.copy(fixed = false, peak = 551.0)).exit.contains("its stop 15% under the price paid"))
    }

    @Test fun itsExitsAreWordedAsTheRule() {
        val base = BotTrades.Trade("liquidity5", "BANKNIFTY14OCT2654300CE", "CE", 30, 300.0, at(12, 0), at(11, 55), lot = 30, fixed = true,
            exitTime = at(12, 10), charges = 50.0)
        assertTrue(chain(base.copy(exit = 301.0, why = "breakeven lock")).exit.contains("out at its breakeven stop (moved there at +10%; failed gates"))
        // The morning's version's records still read as before.
        assertTrue(chain(base.copy(exit = 283.35, why = "₹500 stop")).exit.contains("out at its ₹500-a-lot stop (failed gates"))
        assertTrue(chain(base.copy(exit = 316.7, why = "step trail")).exit.contains("out at its ₹500 step trail (failed gates"))
    }

    @Test fun theWeakLinkCountsThemAsAStopAndAProfitLock() {
        val t = WeakLink.Trade("Liquidity 5m", at(12, 0), at(12, 3), "₹500 stop", -550.0)
        val words = WeakLink.exits(listOf(t, t.copy(why = "step trail", net = 450.0)))
        assertTrue(words.contains("1 at the stop") && words.contains("1 at the profit lock"), words)
        assertTrue(words.contains("Of the 1 stop, 1 came within"), words)
        // The breakeven counts as a profit lock.
        val n = WeakLink.Trade("Liquidity 5m", at(12, 0), at(12, 30), "breakeven lock", -20.0)
        assertTrue(WeakLink.exits(listOf(n)).contains("1 at the profit lock"))
    }
}
