package com.optionslab.app.data

import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.Right
import com.optionslab.ira.PaperElsewhere
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 20 Nov 2026 (audit, B5 item 4: wrong prices and records): what an arm books for a paper trade that left the paper book without its
 * own sale ([Paper.soldElsewhere]) - the sale of its own product, shared between two arms' trades only up to what it sold, else its
 * last price; never another product's sale. Record only.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PaperSoldElsewhereTest : RobolectricTest() {
    private lateinit var upstox: FakeUpstox
    private lateinit var c: Paper.Contract

    @Before fun up() {
        TradeFixtures.paperSettings()
        upstox = FakeUpstox()
        TradeFixtures.seed(context, upstox)
        c = Paper.contractFor("NIFTY", TradeFixtures.nearExpiry, 24_500.0, Right.CE) ?: throw AssertionError("contract not listed")
        Paper.forgetElsewhereForTest()
    }

    @After fun down() {
        Paper.forgetElsewhereForTest()
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun since() = java.time.LocalDateTime.of(2000, 1, 1, 0, 0)

    @Test fun aSquareOffOfTwoArmsLotsIsBookedToEachOnlyUpToWhatItSold() {
        val from = since()
        val lot = c.lotSize
        assertTrue(runBlocking { Paper.place(c, "BUY", 2, "MARKET", "MIS", null, null) }.ok)
        // Closed by hand (or the 15:15 square-off): one sale of both arms' lots.
        assertTrue(runBlocking { Paper.close(c.symbol, "MIS") }.ok)
        val sale = Paper.state.trades.last { it.symbol == c.symbol && it.action == "SELL" }
        val a = Paper.soldElsewhere("A|x", c.symbol, from, "MIS", qty = lot)!!
        assertEquals(PaperElsewhere.How.SALE, a.how)
        assertEquals(sale.price.toDouble(), a.price.toDouble(), 1e-9)
        assertEquals(sale.timestamp, a.timestamp)
        // Review: one fill shared by two trades lends each its share of the charges, never all of them.
        assertEquals(sale.charges.toDouble() / 2, a.charges.toDouble(), 1e-6)
        // The other arm's lot was sold in the same fill: booked at it too.
        val b = Paper.soldElsewhere("B|x", c.symbol, from, "MIS", qty = lot)!!
        assertEquals(PaperElsewhere.How.SALE, b.how)
        // A third trade would take more than it sold: never that sale again (its last price instead).
        val third = Paper.soldElsewhere("C|x", c.symbol, from, "MIS", qty = lot)
        assertNotNull(third)
        assertEquals(PaperElsewhere.How.LAST_PRICE, third!!.how)
        // The same trade asking again keeps its own booking.
        assertEquals(PaperElsewhere.How.SALE, Paper.soldElsewhere("A|x", c.symbol, from, "MIS", qty = lot)!!.how)
    }

    @Test fun anotherProductsSaleIsNeverBooked() {
        val from = since()
        assertTrue(runBlocking { Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null) }.ok)
        assertTrue(runBlocking { Paper.close(c.symbol, "MIS") }.ok)
        val night = Paper.soldElsewhere("Night|x", c.symbol, from, "NRML", qty = c.lotSize)!!
        assertEquals("an MIS sale is not a NRML trade's exit", PaperElsewhere.How.LAST_PRICE, night.how)
    }

    @Test fun nothingKnownLeavesTheArmsOldAnswer() {
        // Never traded, never priced here: nothing to book (the arm keeps its entry price, as before).
        val other = Paper.contractFor("NIFTY", TradeFixtures.nearExpiry, 24_300.0, Right.PE) ?: throw AssertionError("contract not listed")
        assertNull(Paper.soldElsewhere("A|y", other.symbol, since(), "MIS", qty = other.lotSize))
    }

    @Test fun nightsClosedElsewhereTakesTheBookingAndKeepsItsOldAnswerWhenNoneIsKnown() {
        val p = NightArm.Pos("NIFTY", c.symbol, "CE", 65, 100.0, Market.today(), since(), "P1", charges = 20.0)
        val now = Market.now().toLocalDateTime().withNano(0)
        val sold = Paper.SoldElsewhere(java.math.BigDecimal("112.5"), now.minusMinutes(1), java.math.BigDecimal("15"), PaperElsewhere.How.SALE)
        val done = NightArm.closedElsewhere(p, 0, sold, now)!!
        assertEquals(112.5, done.exit!!, 1e-9); assertEquals(35.0, done.charges, 1e-9); assertEquals(now.minusMinutes(1), done.exitTime)
        assertNull("still held", NightArm.closedElsewhere(p, 65, sold, now))
        val none = NightArm.closedElsewhere(p, 0, null as Paper.SoldElsewhere?, now)!!
        assertEquals(100.0, none.exit!!, 1e-9); assertEquals(now, none.exitTime)
    }
}
