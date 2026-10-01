package com.optionslab.app.data

import com.optionslab.engine.sandbox.TradeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Who placed an order, and who opened a position, in words ([Origins]). */
class OriginsTest {
    private val owners = mapOf(
        "paper:1" to "ORB · entry",
        "paper:2" to Origins.manual("Chart"),
        "paper:3" to "Protection · stop",
        "paper:4" to Origins.AUTO_SQUARE_OFF,
        "kite:10" to "Pine · Supertrend · entry",
        "kite:11" to Origins.manual("Option chart"),
        "kite:12" to Origins.EXPIRY,
    )

    @Test fun eachKindOfOrderIsNamed() {
        assertEquals("Strategy: ORB · entry" to true, Origins.of(owners, "paper:1"))
        assertEquals("Manual · Chart" to false, Origins.of(owners, "paper:2"))
        assertEquals("Auto: Protection · stop" to true, Origins.of(owners, "paper:3"))
        assertEquals("Auto: Auto square-off 15:15" to true, Origins.of(owners, "paper:4"))
        assertEquals("Strategy: Pine · Supertrend · entry" to true, Origins.of(owners, "kite:10", "irapine"))
        assertEquals("Manual · Option chart" to false, Origins.of(owners, "kite:11", "iraalgo"))
        assertEquals("Auto: Expiry square-off" to true, Origins.of(owners, "kite:12", "iraalgoexpiry"))
    }

    @Test fun aZerodhaOrderWithoutALabelIsNamedByItsTag() {
        assertEquals("Strategy: ORB" to true, Origins.of(emptyMap(), "kite:1", "iraorb"))
        assertEquals("Strategy: Pine" to true, Origins.of(emptyMap(), "kite:1", "irapine"))
        assertEquals("Strategy order" to true, Origins.of(emptyMap(), "kite:1", "iraalgostrat"))
        assertEquals("Auto: Protection" to true, Origins.of(emptyMap(), "kite:1", "iraprotect"))
        assertEquals("Auto: Expiry square-off" to true, Origins.of(emptyMap(), "kite:1", "iraalgoexpiry"))
        assertEquals("Manual" to false, Origins.of(emptyMap(), "kite:1", "iraalgo"))
        // No tag at all: placed on Kite's own site or app, not by IraAlgo.
        assertEquals(Origins.OUTSIDE to false, Origins.of(emptyMap(), "kite:1", ""))
        assertEquals(Origins.OUTSIDE to false, Origins.of(emptyMap(), "kite:1", "sometag"))
        // Tag not known (the order is not in the book): no guess beyond "Manual".
        assertEquals("Manual" to false, Origins.of(emptyMap(), "kite:1", null))
    }

    @Test fun aPaperOrderIsNeverNamedByATagOrAnotherVenue() {
        assertEquals("Manual" to false, Origins.of(emptyMap(), "paper:9", "iraorb"))
        assertEquals("Manual" to false, Origins.of(mapOf("kite:9" to "ORB · entry"), "paper:9"))
    }

    private fun fill(id: String, buy: Boolean, qty: Int, tag: String? = null) = Origins.Fill(id, tag, buy, qty)

    @Test fun aPositionIsNamedByTheOrdersThatOpenedWhatItHoldsNow() {
        // ORB bought 75, then a hand buy of 75 from the chart: both opened what is held.
        val fills = listOf(fill("paper:1", true, 75), fill("paper:2", true, 75))
        assertEquals("ORB + Manual", Origins.position(owners, fills, 150))
        // Only the newest 75 is still held after a partial exit: the hand buy.
        assertEquals("Manual", Origins.position(owners, fills + fill("paper:3", false, 75), 75))
    }

    @Test fun aShortIsNamedByItsSells() {
        val fills = listOf(fill("kite:10", true, 50, "irapine"), fill("kite:11", false, 100, "iraalgo"))
        assertEquals("Manual", Origins.position(owners, fills, -50))
    }

    @Test fun aClosedPositionIsNamedByWhoOpenedItFirst() {
        val fills = listOf(fill("paper:1", true, 75), fill("paper:3", false, 75))
        assertEquals("ORB", Origins.position(owners, fills, 0))
    }

    @Test fun aClosedOptionTradedByTwoArmsTodayNamesBoth() {
        // ORB in and out, then another opener in and out: the closed row's P&L is both of theirs.
        val fills = listOf(fill("paper:1", true, 30), fill("paper:3", false, 30), fill("paper:2", true, 30), fill("paper:4", false, 30))
        assertEquals("ORB + Manual", Origins.position(owners, fills, 0))
        // The exits never name anyone; the same arm twice is named once.
        assertEquals("ORB", Origins.position(owners, listOf(fill("paper:1", true, 30), fill("paper:3", false, 30), fill("paper:1", true, 30), fill("paper:3", false, 30)), 0))
    }

    @Test fun noFillsNoName() {
        assertNull(Origins.position(owners, emptyList(), 75))
        // Carried overnight: today only sold part of it, nothing on the buy side explains the long.
        assertNull(Origins.position(owners, listOf(fill("paper:3", false, 25)), 50))
    }

    @Test fun thePositionPillIsBoldOnlyWhenTheAppOpenedSomeOfIt() {
        assertEquals("Opened by ORB + Manual" to true, Origins.positionDisplay("ORB + Manual"))
        assertEquals("Opened by Manual" to false, Origins.positionDisplay("Manual"))
        assertEquals("Opened by ${Origins.OUTSIDE}" to false, Origins.positionDisplay(Origins.OUTSIDE))
    }

    @Test fun paperPositionsReadTheTradeBookOfThatSymbolAndProductOnly() {
        fun t(order: String, sym: String, action: String, qty: Int, at: String, product: String = "MIS") =
            TradeRow("t$order", order, sym, "NFO", action, qty, 100.0, 100.0, 100.0 * qty, product, "", at)
        val trades = listOf(
            t("2", "NIFTY25000CE", "BUY", 75, "2026-09-28 10:20:00"),
            t("1", "NIFTY25000CE", "BUY", 75, "2026-09-28 10:05:00"),
            t("9", "NIFTY24000PE", "BUY", 75, "2026-09-28 10:06:00"),
            t("8", "NIFTY25000CE", "BUY", 75, "2026-09-28 10:07:00", product = "NRML"),
        )
        assertEquals("ORB + Manual", Origins.paperPosition(owners, trades, "NIFTY25000CE", "MIS", 150))
    }

    @Test fun livePositionsTakeEachFillsTagFromTheOrderBook() {
        val trades = listOf(Broker.Trade("a", "77", "BANKNIFTY55000PE", "NFO", "BUY", 35, 200.0, "MIS", "2026-09-28 10:05:00"))
        val orders = listOf(Broker.OrderRow("77", "BANKNIFTY55000PE", "BUY", 35, 35, 200.0, 200.0, "COMPLETE", "", "10:05:00",
            product = "MIS", tag = "iraorb"))
        assertEquals("ORB", Origins.livePosition(emptyMap(), trades, orders, "BANKNIFTY55000PE", "MIS", 35))
    }
}
