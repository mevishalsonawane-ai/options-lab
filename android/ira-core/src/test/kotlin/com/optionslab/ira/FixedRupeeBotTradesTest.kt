package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 14 Nov 2026 (review; Boss's exception, failed gates): "explain my bots' trades" and the weak-link answer read a Liquidity trade on the
 * fixed ₹2,000 target / ₹1,000 stop as such - its open line names the ₹1k level and the ₹2k target, labelled, and its exits are
 * worded and counted as a stop and a target.
 */
class FixedRupeeBotTradesTest {
    private val day: LocalDate = LocalDate.of(2026, 11, 16)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private val switches = listOf(BotTrades.Switch("Liquidity 15+5", true))

    private fun chain(t: BotTrades.Trade) = BotTrades.read(listOf(t), switches, 54_200.0 to 54_000.0, emptyList(), at(15, 20)).chains.single()

    @Test fun anOpenFixedTradeNamesItsOneThousandStopAndTwoThousandTarget() {
        // 30 units at 300: the 15% stop is 255 (Rs 1,350); the ₹1k level 266.70 is tighter; the ₹2k target 366.70.
        val open = BotTrades.Trade("liquidity5", "BANKNIFTY14OCT2654300CE", "CE", 30, 300.0, at(12, 0), at(11, 55), fixed = true)
        val e = chain(open).exit
        assertTrue(e.contains("₹1,000 stop for the whole position (266.70; failed gates, Boss's exception)"), e)
        assertTrue(e.contains("₹2,000 target for the whole position (366.70"), e)
        val plain = chain(open.copy(fixed = false)).exit
        assertTrue(plain.contains("its stop 15% under the price paid"), plain)
        assertFalse(plain.contains("₹2,000"), plain)
        // A cheap option: the 15% is tighter, so the 15% is named (the ₹2k target still).
        val cheap = chain(open.copy(entry = 150.0)).exit
        assertTrue(cheap.contains("its stop 15% under the price paid") && cheap.contains("₹2,000 target"), cheap)
    }

    @Test fun itsExitsAreWordedAsTheRule() {
        val base = BotTrades.Trade("liquidity5", "BANKNIFTY14OCT2654300CE", "CE", 30, 300.0, at(12, 0), at(11, 55), fixed = true,
            exitTime = at(12, 10), charges = 50.0)
        assertTrue(chain(base.copy(exit = 266.7, why = "₹1k stop")).exit.contains("out at its ₹1,000 stop for the whole position (failed gates"))
        assertTrue(chain(base.copy(exit = 366.7, why = "₹2k target")).exit.contains("out at its ₹2,000 target for the whole position (failed gates"))
    }

    @Test fun theWeakLinkCountsThemAsStopsAndTargets() {
        val t = WeakLink.Trade("Liquidity 5m", at(12, 0), at(12, 3), "₹1k stop", -1_050.0)
        val words = WeakLink.exits(listOf(t, t.copy(why = "₹2k target", net = 1_950.0)))
        assertTrue(words.contains("1 at the stop") && words.contains("1 at the target"), words)
        assertTrue(words.contains("Of the 1 stop, 1 came within"), words)
    }
}
