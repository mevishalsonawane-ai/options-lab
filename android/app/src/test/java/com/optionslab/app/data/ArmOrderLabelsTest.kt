package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Kite
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 15 Nov (audit: 27 live orders Zerodha rejected were shown with no arm): [ArmOrderLabels] - an arm's label noted before its
 * order is sent. An order with an id is named by it at once; a REJECTED order whose id never reached the phone is named by the
 * refused try with the same contract, side, quantity and tag, the nearest in time, one order a try; a lost reply, a filled
 * order, another contract, another tag or another day is never named; the notes are bounded; the order review shows the arm.
 */
class ArmOrderLabelsTest : RobolectricTest() {
    private val sym = "NIFTY26NOV25000CE"
    private val t0: LocalDateTime = LocalDateTime.of(2026, 11, 13, 10, 0, 0)
    private fun ms(t: LocalDateTime) = t.atZone(IST).toInstant().toEpochMilli()
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val kept = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    private lateinit var keepWas: (String, String) -> Unit

    private fun order(qty: Int = 75, symbol: String = sym, side: Kite.Side = Kite.Side.BUY, tag: String = "iraarm") =
        Kite.Order(symbol, side, qty, 75, "MIS", "MARKET", null, 0.05, "NFO", tag)

    private fun row(id: String, at: LocalDateTime = t0, status: String = "REJECTED", qty: Int = 75, filled: Int = 0, symbol: String = sym,
                    side: String = "BUY", tag: String = "iraarm", exchange: String = "NFO", placedAt: String = at.format(fmt),
                    product: String = "MIS", type: String = "MARKET") =
        Broker.OrderRow(id, symbol, side, qty, filled, 0.0, 0.0, status, "Insufficient funds", placedAt, exchange, product, type, tag = tag)

    @Before fun up() {
        ArmOrderLabels.resetForTest()
        AppState.resetForTest()
        keepWas = ArmOrderLabels.keep
        ArmOrderLabels.keep = { id, label -> kept += id to label }
    }

    @After fun down() {
        ArmOrderLabels.keep = keepWas
        ArmOrderLabels.resetForTest()
        AppState.resetForTest()
    }

    @Test fun anOrderWithAnIdIsNamedByItAtOnce() {
        ArmOrderLabels.sending(order(), "R51 · live entry", ms(t0))
        ArmOrderLabels.answered("101", "R51 · live entry", ms(t0))
        // Whatever became of it (rejected later, filled, cancelled), and whatever the row's other fields say.
        assertEquals("R51 · live entry", ArmOrderLabels.labelOf(row("101")))
        assertEquals("R51 · live entry", ArmOrderLabels.labelOf(row("101", status = "COMPLETE", filled = 75)))
        assertNull("another order", ArmOrderLabels.labelOf(row("102", status = "COMPLETE", filled = 75)))
        assertTrue("an id the phone had is not written again from here", kept.isEmpty())
        // A blank id is never kept.
        ArmOrderLabels.answered("", "R51 · live entry", ms(t0))
        assertEquals(1, ArmOrderLabels.sizesForTest().second)
    }

    @Test fun aRejectedOrderWithNoIdIsNamedByItsRefusedTry() {
        val ticket = ArmOrderLabels.sending(order(), "R72 · live entry", ms(t0))
        // Until Zerodha's answer is known the try names nothing (the order may be on its way, or its reply lost).
        assertNull(ArmOrderLabels.labelOf(row("201")))
        ArmOrderLabels.refused(ticket)
        assertEquals("R72 · live entry", ArmOrderLabels.labelOf(row("201", at = t0.plusSeconds(1))))
        assertEquals("kept past a restart, once", listOf("201" to "R72 · live entry"), kept.toList())
        // Asked again: the same answer, by its id now, and not written again.
        assertEquals("R72 · live entry", ArmOrderLabels.labelOf(row("201", at = t0.plusSeconds(1))))
        assertEquals(1, kept.size)
        // One order a try: a second rejected order like it is not this try's.
        assertNull(ArmOrderLabels.labelOf(row("202", at = t0.plusSeconds(2))))
    }

    @Test fun onlyARejectedOrderLikeTheTryIsNamed() {
        ArmOrderLabels.refused(ArmOrderLabels.sending(order(), "R51 · live entry", ms(t0)))
        assertNull("filled", ArmOrderLabels.labelOf(row("1", status = "COMPLETE", filled = 75)))
        assertNull("still open", ArmOrderLabels.labelOf(row("2", status = "OPEN")))
        assertNull("cancelled", ArmOrderLabels.labelOf(row("3", status = "CANCELLED")))
        assertNull("partly filled", ArmOrderLabels.labelOf(row("4", filled = 25)))
        assertNull("another quantity", ArmOrderLabels.labelOf(row("5", qty = 150)))
        assertNull("another contract", ArmOrderLabels.labelOf(row("6", symbol = "NIFTY26NOV25000PE")))
        assertNull("the other side", ArmOrderLabels.labelOf(row("7", side = "SELL")))
        assertNull("another bot's tag", ArmOrderLabels.labelOf(row("8", tag = "iraorb")))
        assertNull("a hand order (no tag)", ArmOrderLabels.labelOf(row("9", tag = "")))
        assertNull("another exchange", ArmOrderLabels.labelOf(row("10", exchange = "BFO")))
        assertNull("another order type", ArmOrderLabels.labelOf(row("10a", type = "LIMIT")))
        assertNull("another product", ArmOrderLabels.labelOf(row("10b", product = "NRML")))
        assertNull("too early", ArmOrderLabels.labelOf(row("11", at = t0.minusSeconds(ArmOrderLabels.BEFORE_MS / 1000 + 1))))
        assertNull("too late", ArmOrderLabels.labelOf(row("12", at = t0.plusSeconds(ArmOrderLabels.AFTER_MS / 1000 + 1))))
        assertNull("no time", ArmOrderLabels.labelOf(row("13", placedAt = "")))
        assertNull("a time that cannot be read", ArmOrderLabels.labelOf(row("14", placedAt = "not a time")))
        assertTrue(kept.isEmpty())
        // The edges of the window count, and the try is still free for its own order.
        assertEquals("R51 · live entry", ArmOrderLabels.labelOf(row("15", at = t0.minusSeconds(ArmOrderLabels.BEFORE_MS / 1000))))
    }

    @Test fun twoArmsRefusedOnOneContractCloseTogetherAreLeftUnnamed() {
        // R51 and R72 buy the same contract and lots two minutes apart (as on 3, 4 and 6 Nov); both are refused with no id. A
        // rejected order that fits both is never guessed between them.
        ArmOrderLabels.refused(ArmOrderLabels.sending(order(), "R51 · live entry", ms(t0)))
        ArmOrderLabels.refused(ArmOrderLabels.sending(order(), "R72 · live entry", ms(t0.plusMinutes(2))))
        assertNull(ArmOrderLabels.labelOf(row("302", at = t0.plusMinutes(2).plusSeconds(1))))
        assertNull(ArmOrderLabels.labelOf(row("301", at = t0.plusSeconds(1))))
        assertTrue(kept.isEmpty())
        // Further apart, each order fits only its own arm's try: named.
        val later = t0.plusMinutes(30)
        ArmOrderLabels.refused(ArmOrderLabels.sending(order(), "R51 · live entry", ms(later)))
        ArmOrderLabels.refused(ArmOrderLabels.sending(order(), "R72 · live entry", ms(later.plusMinutes(10))))
        assertEquals("R72 · live entry", ArmOrderLabels.labelOf(row("312", at = later.plusMinutes(10).plusSeconds(1))))
        assertEquals("R51 · live entry", ArmOrderLabels.labelOf(row("311", at = later.plusSeconds(1))))
        // Two refused tries of the SAME arm fit: named (the nearest takes it, the other stays free for its own order).
        ArmOrderLabels.refused(ArmOrderLabels.sending(order(qty = 150), "R51 · live entry", ms(later)))
        ArmOrderLabels.refused(ArmOrderLabels.sending(order(qty = 150), "R51 · live entry", ms(later.plusMinutes(1))))
        assertEquals("R51 · live entry", ArmOrderLabels.labelOf(row("321", qty = 150, at = later.plusSeconds(2))))
        assertEquals("R51 · live entry", ArmOrderLabels.labelOf(row("322", qty = 150, at = later.plusMinutes(1).plusSeconds(2))))
        assertNull("one order a try", ArmOrderLabels.labelOf(row("323", qty = 150, at = later.plusMinutes(1).plusSeconds(3))))
    }

    @Test fun theOrderTypeAndProductMustMatch() {
        val limit = Kite.Order(sym, Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 120.0, 0.05, "NFO", "iraarm")
        ArmOrderLabels.refused(ArmOrderLabels.sending(limit, "Hero · live entry", ms(t0)))
        assertNull("a MARKET MIS order is not this LIMIT NRML try's", ArmOrderLabels.labelOf(row("331")))
        assertEquals("Hero · live entry", ArmOrderLabels.labelOf(row("332", product = "NRML", type = "LIMIT")))
    }

    @Test fun theTagIsComparedAsZerodhaKeepsIt() {
        // Zerodha keeps letters and digits only, 20 at most.
        ArmOrderLabels.refused(ArmOrderLabels.sending(order(tag = "ira-arm_1"), "X · live entry", ms(t0)))
        assertEquals("X · live entry", ArmOrderLabels.labelOf(row("401", tag = "iraarm1")))
    }

    @Test fun aTicketThatIsNotKnownChangesNothing() {
        ArmOrderLabels.refused(999L)
        ArmOrderLabels.sending(order(), "R51 · live entry", ms(t0))
        ArmOrderLabels.refused(999L)
        assertNull(ArmOrderLabels.labelOf(row("501")))
    }

    @Test fun onlyZerodhasOwnRefusalCounts() {
        assertTrue(ArmOrderLabels.zerodhaRefused(Broker.KiteError("InputException", "Insufficient funds")))
        assertTrue(ArmOrderLabels.zerodhaRefused(Broker.KiteError("GeneralException", "Something went wrong")))
        assertFalse("a lost reply", ArmOrderLabels.zerodhaRefused(Broker.KiteError("NetworkException", "Gateway timed out")))
        assertFalse("the app's own door", ArmOrderLabels.zerodhaRefused(Broker.KiteError("entry_gate", "the kill switch is on")))
        assertFalse(ArmOrderLabels.zerodhaRefused(Broker.KiteError("paper_only", "paper only")))
        assertFalse(ArmOrderLabels.zerodhaRefused(java.io.IOException("timeout")))
    }

    @Test fun theNotesAreBoundedAndANewDayDropsTheOldOnes() {
        for (i in 1..(ArmOrderLabels.KEPT + 40)) {
            ArmOrderLabels.refused(ArmOrderLabels.sending(order(), "R51 · live entry", ms(t0) + i))
            ArmOrderLabels.answered("id$i", "R51 · live entry", ms(t0) + i)
        }
        assertEquals(ArmOrderLabels.KEPT to ArmOrderLabels.KEPT, ArmOrderLabels.sizesForTest())
        assertNull("the oldest id went first", ArmOrderLabels.labelOf(row("id1", status = "COMPLETE", filled = 75)))
        assertEquals("R51 · live entry", ArmOrderLabels.labelOf(row("id${ArmOrderLabels.KEPT + 40}", status = "COMPLETE", filled = 75)))
        // The next day's first order drops them all.
        val next = t0.plusDays(1)
        ArmOrderLabels.sending(order(), "R72 · live entry", ms(next))
        assertEquals(1 to 0, ArmOrderLabels.sizesForTest())
        assertNull(ArmOrderLabels.labelOf(row("id${ArmOrderLabels.KEPT + 40}", status = "COMPLETE", filled = 75)))
        assertNull("yesterday's refused tries too", ArmOrderLabels.labelOf(row("601")))
    }

    @Test fun theResetPutsTheAppsOwnKeeperBack() {
        val mine = ArmOrderLabels.keep
        ArmOrderLabels.resetForTest()
        assertTrue("the test's recorder is gone", ArmOrderLabels.keep !== mine)
    }

    @Test fun aKeeperThatThrowsNeverCostsTheLabel() {
        ArmOrderLabels.keep = { _, _ -> throw IllegalStateException("the labels' book could not be written") }
        ArmOrderLabels.refused(ArmOrderLabels.sending(order(), "R51 · live entry", ms(t0)))
        assertEquals("R51 · live entry", ArmOrderLabels.labelOf(row("701")))
    }

    @Test fun theOrderReviewShowsTheArmOfARejectedOrder() {
        ArmOrderLabels.refused(ArmOrderLabels.sending(order(), "R51 · live entry", ms(t0)))
        // Zerodha's order book as the app last read it: the rejected arm order (no label in the app's order labels), and one
        // rejected arm order nothing here knows of (shown as before).
        AppState.publishOrders(listOf(row("801", at = t0.plusSeconds(1)), row("802", at = t0.plusSeconds(1), symbol = "NIFTY26NOV25000PE")),
            System.currentTimeMillis())
        val day = t0.toLocalDate()
        val orders = OrderReviewHost.ordersIn(emptyMap(), ms(day.atStartOfDay()), ms(day.plusDays(1).atStartOfDay()) - 1)
            .filter { it.venue == com.optionslab.ira.OrderReview.Venue.LIVE }.associate { it.id to it.strategy }
        assertEquals(mapOf("kite:801" to "R51", "kite:802" to "Arms (live)"), orders)
        // A label already in the app's order labels wins.
        ArmOrderLabels.resetForTest()
        ArmOrderLabels.keep = { id, label -> kept += id to label }      // (the reset put the app's own keeper back)
        ArmOrderLabels.refused(ArmOrderLabels.sending(order(), "R51 · live entry", ms(t0)))
        val named = OrderReviewHost.ordersIn(mapOf("kite:801" to "R72 · live entry"), ms(day.atStartOfDay()), ms(day.plusDays(1).atStartOfDay()) - 1)
            .first { it.id == "kite:801" }
        assertEquals("R72", named.strategy)
    }
}
