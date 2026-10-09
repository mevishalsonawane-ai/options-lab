package com.optionslab.app.data

import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.OrderFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.nio.ByteBuffer

/**
 * The live order flow from Zerodha's full-mode packets ([OrderFlowLive]): a 184-byte packet through the stream's own path
 * updates the followed future's read (OFI, signed volume, the book), the shadow gate logs a signal with that read, and an
 * instrument the flow does not follow is never taken.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrderFlowLiveTest : RobolectricTest() {
    private val fut = 13_000_001L
    private val other = 13_000_257L

    @After fun down() {
        OrderFlowLive.resetForTest()
        KiteStream.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** A full-mode packet: last price, volume, the book's best bid and offer (and a second level each), OI. */
    private fun packet(token: Long, last: Double, volume: Int, bid: Double, bidQty: Int, ask: Double, askQty: Int, oi: Int): ByteArray =
        ByteBuffer.allocate(184).apply {
            putInt(token.toInt()); putInt((last * 100).toInt()); putInt(10); putInt((last * 100).toInt()); putInt(volume)
            putInt(9_000); putInt(5_000)                                         // total buy, total sell
            repeat(4) { putInt((last * 100).toInt()) }                            // open high low close
            putInt(1_790_000_000); putInt(oi); putInt(oi); putInt(oi); putInt(1_790_000_000)
            putInt(bidQty); putInt((bid * 100).toInt()); putShort(4); putShort(0)
            putInt(50); putInt(((bid - 1) * 100).toInt()); putShort(1); putShort(0)
            repeat(3) { putInt(0); putInt(0); putShort(0); putShort(0) }
            putInt(askQty); putInt((ask * 100).toInt()); putShort(3); putShort(0)
            putInt(50); putInt(((ask + 1) * 100).toInt()); putShort(1); putShort(0)
            repeat(3) { putInt(0); putInt(0); putShort(0); putShort(0) }
        }.array()

    private fun message(vararg packets: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(2 + packets.sumOf { 2 + it.size })
        b.putShort(packets.size.toShort())
        packets.forEach { b.putShort(it.size.toShort()); b.put(it) }
        return b.array()
    }

    @Test fun aFullModePacketUpdatesTheFlow() {
        OrderFlowLive.layoutForTest(listOf(OrderFlow.Board.Entry(fut, "BANKNIFTY", OrderFlow.Role.FUTURE)))
        val t0 = System.currentTimeMillis()
        // The book first, then 30 traded at the offer while the bid steps up: buying.
        KiteStream.feedForTest(KiteTicks.parse(message(packet(fut, 55_000.0, 1_000, 54_999.0, 300, 55_001.0, 300, 1_000_000))), t0)
        KiteStream.feedForTest(KiteTicks.parse(message(packet(fut, 55_001.0, 1_030, 55_000.0, 400, 55_002.0, 100, 1_000_500),
            packet(other, 10.0, 5, 9.0, 1, 11.0, 1, 0))), t0 + 300)
        OrderFlowLive.pumpForTest(t0 + 400)
        val r = OrderFlowLive.reads.value["BANKNIFTY"]
        assertTrue("a read is published for the future's index", r != null)
        r!!
        assertTrue("30 bought at the offer: ${r.cvd10}", r.cvd10 > 0)
        assertTrue("the bid stepped up and the offer was lifted: ${r.ofi10}", r.ofi10 > 0)
        assertEquals(55_001.0, r.mid, 1e-9)
        assertTrue("the 5-level book leans to the bids: ${r.depth}", r.depth > 0)
        assertEquals(1.8, r.ratio, 1e-9)
        assertTrue("two seconds are no history yet", !r.warm)
        // The instrument the flow does not follow is never taken; the coverage line says what is followed.
        assertEquals(listOf(fut), OrderFlowLive.entries().map { it.token })
        assertTrue(OrderFlowLive.coverageLine(t0 + 400), OrderFlowLive.coverageLine(t0 + 400).startsWith("Order flow: 1 instrument in full mode (1 ticking)"))
        // Nothing else is read: an index the flow has no future for has no read.
        assertNull(OrderFlowLive.readNow("NIFTY"))
    }

    @Test fun theShadowLogsTheFlowBesideASignalWithoutSkippingIt() {
        OrderFlowLive.testRead = { u -> com.optionslab.app.testing.FlowFixtures.read(u, 30, 1_000) }
        FlowGate.testNowSec = 1_000
        val t = FlowGate.check("orb", "BANKNIFTY", +1, live = false, at = "2026-10-09T11:05", what = "CE")
        assertTrue("SHADOW never skips", !t.skip)
        assertEquals(OrderFlow.Agreement.DISAGREES, t.agreement)
        // The same signal decided again (an approval): logged once.
        FlowGate.check("orb", "BANKNIFTY", +1, live = false, at = "2026-10-09T11:05", what = "CE")
        FlowGate.taken(t, "P-1")
        val s = FlowGate.signalsForTest().single()
        assertEquals("orb", s.key); assertEquals(30, s.buyers); assertEquals("P-1", s.orderId); assertEquals("entered", s.verdict)
        // OFF logs nothing.
        FlowGate.set("pine", OrderFlow.Mode.OFF)
        FlowGate.check("pine", "NIFTY", -1, live = false, at = "x")
        assertEquals(1, FlowGate.signalsForTest().size)
        // Written to its plain file on a flush, and read back.
        FlowGate.flush()
        assertTrue(FlowGate.files().last().readText().startsWith("S|orb@2026-10-09T11:05@CE|"))
        assertEquals(1, FlowGate.signalsForTest().size)
    }

    @Test fun aClosedPaperRoundTripIsTheSignalsResult() {
        fun tr(id: String, order: String, action: String, qty: Int, px: Double, min: Int) = com.optionslab.engine.sandbox.Trade(id, order, "NIFTYX",
            "NFO", action, qty, java.math.BigDecimal(px), "MIS", null, java.time.LocalDateTime.of(2026, 10, 9, 10, min), java.math.BigDecimal("10"))
        val trades = listOf(tr("1", "E", "BUY", 75, 100.0, 0), tr("2", "S1", "SELL", 75, 120.0, 20), tr("3", "Z", "SELL", 75, 90.0, 30))
        assertEquals(75 * 20.0 - 20.0, FlowGate.netOf(trades, "E")!!, 1e-9)
        assertNull("still open", FlowGate.netOf(trades.take(1), "E"))
        assertNull("no such order", FlowGate.netOf(trades, "Q"))
    }
}
