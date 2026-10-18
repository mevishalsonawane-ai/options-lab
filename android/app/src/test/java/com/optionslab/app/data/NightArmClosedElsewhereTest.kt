package com.optionslab.app.data

import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.orb.NightRules
import com.optionslab.engine.sandbox.Quote
import com.optionslab.engine.sandbox.SandboxEvent
import com.optionslab.engine.sandbox.Trade
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 18 Oct, review: a Night (R3) position Boss closed by hand (or an exit-all) before 09:16 is booked as closed elsewhere at that
 * sale, never sold again (a SELL on a flat paper book opened a naked short nothing manages), and the 09:16 sale never sells
 * more than the paper book still holds.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class NightArmClosedElsewhereTest : RobolectricTest() {
    private val entryDay = LocalDate.of(2026, 10, 14)
    private val entry = entryDay.atTime(15, 20)
    private val pos = NightArm.Pos("NIFTY", "NIFTY26O2025000CE", "CE", 65, 100.0, entryDay, entry, "P1", charges = 20.0)
    private val now = LocalDateTime.of(2026, 10, 15, 9, 16)

    private fun trade(id: String, action: String, price: String, at: LocalDateTime, symbol: String = pos.symbol, charges: String = "15") =
        Trade("T$id", "O$id", symbol, "NFO", action, 65, BigDecimal(price), "NRML", null, at, BigDecimal(charges))

    @Test fun stillHeldIsNotClosedElsewhere() {
        assertNull(NightArm.closedElsewhere(pos, 65, emptyList(), now))
        assertNull(NightArm.closedElsewhere(pos, 1, emptyList(), now))
    }

    @Test fun aHandCloseIsBookedAtThatSaleWithItsCharges() {
        val trades = listOf(
            trade("0", "BUY", "100", entry),
            // An older sale of the same symbol before this entry is not this trade's exit.
            trade("1", "SELL", "80", entry.minusDays(2)),
            trade("2", "SELL", "112.5", entry.plusMinutes(5)),
            // Another symbol's later sale is not it either.
            trade("3", "SELL", "300", entry.plusMinutes(8), symbol = "NIFTY26O2025000PE"),
        )
        val done = NightArm.closedElsewhere(pos, 0, trades, now)!!
        assertEquals("closed_elsewhere", done.why)
        assertEquals(112.5, done.exit!!, 1e-9)
        assertEquals(entry.plusMinutes(5), done.exitTime)
        assertEquals(35.0, done.charges, 1e-9)
        assertEquals((112.5 - 100.0) * 65 - 35.0, done.net!!, 1e-9)
    }

    @Test fun noSaleFoundBooksItAtItsEntryNow() {
        // Edge: the sale is not in the kept trades (or a short is held: negative): flat at its entry, never sold again.
        for (held in listOf(0, -65)) {
            val done = NightArm.closedElsewhere(pos, held, emptyList(), now)!!
            assertEquals(100.0, done.exit!!, 1e-9)
            assertEquals(now, done.exitTime)
            assertEquals(20.0, done.charges, 1e-9)
        }
    }

    @Test fun theSaleNeverSellsMoreThanIsHeld() {
        assertEquals(65, NightArm.sellQty(65, 65))
        assertEquals(65, NightArm.sellQty(65, 130))
        assertEquals(65, NightArm.sellQty(130, 65))
        assertEquals(0, NightArm.sellQty(65, -65))
    }

    /**
     * 18 Oct (review D8): its paper trades reach the live path ([ArmLive]) end to end - its registered book shows the overnight
     * trade under its own key with the paper quantity, and when Boss closed it by hand before 09:16 the next session's pass tells
     * the live path it is closed, by the same reference, and the book shows it held no more. (Night's 15:20 buy needs the day-end
     * OI build-up and Zerodha breadth, which the fakes do not serve, so the trade is bought on paper here and put in its book.)
     */
    @Test fun itsPaperTradeReachesTheLivePathAndAHandCloseIsToldClosed() {
        val upstox = FakeUpstox()                            // (clears Market.testClock: set it after)
        try {
            ArmLive.testHeard = java.util.Collections.synchronizedList(ArrayList<String>())
            TradeFixtures.paperSettings()
            val day = AutomationSupport.tradingDayAt(15, 20).toLocalDate()
            var next = day.plusDays(1)
            while (!Market.isTradingDay(next)) next = next.plusDays(1)
            fun at(d: LocalDate, t: LocalTime) { Market.testClock = Clock.fixed(d.atTime(t).plusSeconds(20).atZone(IST).toInstant(), IST) }
            at(day, LocalTime.of(15, 20))
            TradeFixtures.seed(context, upstox)
            // 15:20: one lot (75) of the NIFTY 24,500 CE bought on paper, NRML, as the arm buys it.
            val c = Paper.contractFor("NIFTY", TradeFixtures.nearExpiry, 24_500.0, Right.CE)!!
            val r = runBlocking { Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, Quote(100.0)) }
            assertTrue(r.message, r.ok)
            val fill = r.events.filterIsInstance<SandboxEvent.Fill>().first()
            assertEquals(75, fill.quantity)
            val entryTime = day.atTime(15, 20, 20)
            val trade = JSONObject().put("index", "NIFTY").put("symbol", c.symbol).put("right", "CE").put("qty", fill.quantity)
                .put("entry", fill.price).put("entryDay", day.toString()).put("entryTime", entryTime.toString()).put("orderId", r.orderId)
                .put("charges", 0.0)
            Vault.writeFile(File(context.filesDir, "night.vault"),
                JSONObject().put("armed", false).put("positions", JSONArray().put(trade)).toString().toByteArray(Charsets.UTF_8))
            NightArm.init(context)
            AutomationSupport.reloadFromDisk(NightArm)
            assertEquals(1, runBlocking { NightArm.refresh() }.open.size)
            val ref = "night:NIFTY:${r.orderId ?: entryTime}"
            assertEquals(listOf(ArmLive.PaperLeg(ref, NightRules.SOURCE, 75)), ArmLive.legsForTest(LiveMirror.NIGHT))
            // 15:21: Boss closes it by hand on paper.
            at(day, LocalTime.of(15, 21))
            val sold = runBlocking { Paper.place(c, "SELL", 1, "MARKET", "NRML", null, null, Quote(110.0)) }
            assertTrue(sold.message, sold.ok)
            // The next session's 09:16 pass: booked closed elsewhere (never sold again) and the live path is told so.
            at(next, LocalTime.of(9, 16))
            runBlocking { NightArm.tick() }
            val heard = ArmLive.testHeard!!.toList()
            assertEquals("closed_elsewhere", NightArm.view.value.closed.single().why)
            assertTrue(heard.toString(), heard.contains("closed|$ref|closed_elsewhere"))
            assertTrue(ArmLive.legsForTest(LiveMirror.NIGHT).orEmpty().none { it.ref == ref && it.qty > 0 })
            assertEquals(0, Paper.state.positions.filter { it.symbol == c.symbol }.sumOf { it.quantity })
        } finally {
            Market.testClock = null
            upstox.close()
        }
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }
}
