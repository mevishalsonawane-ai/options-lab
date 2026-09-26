package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Reply
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.ZonedDateTime

/**
 * Pine auto-trade in Live ([PineAuto]) against the fake Kite, on candles and a clock the test controls
 * ([PineAuto.testBars], [PineAuto.testNow]): it waits for the next change of signal, never trades on stale
 * candles, buys the ATM option on a change, sells only what is held less other working sells, settles a buy
 * whose reply was lost, pauses for the day at its loss limit, and in alerts-only mode sends nothing.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PineAutoLiveTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val ce = "BANKNIFTY26OCT52000CE"
    private val pe = "BANKNIFTY26OCT52000PE"
    private lateinit var now: ZonedDateTime
    private var closes: List<Double> = emptyList()
    private var candles: (() -> List<Upstox.Bar>)? = null
    private var id = 0L

    private val code = """
        //@version=5
        indicator("Level signals", overlay = true)
        level = input.float(52000, "Level")
        buy = close > level
        sell = close < level
        plotshape(buy, "Buy", shape.labelup, location.belowbar, color.green)
        plotshape(sell, "Sell", shape.labeldown, location.abovebar, color.red)
    """.trimIndent()

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        AutomationSupport.freshPine(context)
        now = AutomationSupport.tradingDayAt(11, 2)
        PineAuto.testNow = now
        PineAuto.testBars = { _, _ -> candles?.invoke() ?: AutomationSupport.bars(now, closes) }
        val expiry = now.toLocalDate().plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|1", ce),
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.PE, 30, "NSE_FO|2", pe)))
        kite.instruments += FakeKite.Ins(33_000_001, ce, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(33_000_002, pe, "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$ce", 300.0, 299.95, 300.05)
        kite.quote("NFO:$pe", 280.0, 279.95, 280.05)
        id = PineScripts.put(PineScripts.Item(0, "Level", code)).id
        auto()
    }

    @After fun down() {
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun auto(change: (PineScripts.Auto) -> PineScripts.Auto = { it }) =
        PineScripts.setAuto(id, change(PineScripts.Auto(symbol = "BANKNIFTY", interval = "5m", lots = 1, buy = "Buy", sell = "Sell", shortWith = "exit")))

    private fun switchOn(pin: Boolean = true) = assertEquals("ok", runBlocking { PineAuto.arm(id, true, pinConfirmed = pin) })

    /** One pass of the watch, [minutes] after the last, on candles whose last close is [last]. */
    private fun pass(last: Double, minutes: Long = 5) {
        now = now.plusMinutes(minutes); PineAuto.testNow = now
        closes = List(9) { 51_900.0 } + last
        runBlocking { PineAuto.tick() }
    }

    private fun log() = PineAuto.log.value.filter { it.script == id }.joinToString("\n") { it.text }
    private fun held() = PineAuto.held.value[id]
    private fun side(s: String) = kite.placed.filter { it.form["transaction_type"] == s }

    /** Switched on while the signal says SELL, then it turns to BUY: the ATM call is bought. */
    private fun bought(lots: Int = 1) {
        if (lots != 1) auto { it.copy(lots = lots) }
        switchOn()
        pass(51_900.0)
        pass(52_010.0)
    }

    @Test fun aScriptSwitchedOnWaitsForTheNextChangeOfSignal() {
        switchOn()
        pass(52_010.0)                                             // already BUY when switched on: not jumped into
        pass(52_020.0)
        assertTrue(kite.requests.none { it.method == "POST" })
        assertTrue(log(), log().contains("Watching: the signal now is BUY; it trades on the next change"))
    }

    @Test fun aChangeOfSignalBuysTheAtmCallLive() {
        bought()
        val keys = setOf("tradingsymbol", "exchange", "transaction_type", "order_type", "quantity", "product", "tag")
        assertEquals(mapOf("tradingsymbol" to ce, "exchange" to "NFO", "transaction_type" to "BUY", "order_type" to "MARKET",
            "quantity" to "30", "product" to "MIS", "tag" to "irapine"), kite.placed.single().form.filterKeys { it in keys })
        val h = held()!!
        assertTrue(h.live); assertFalse(h.unconfirmed); assertEquals(30, h.qty); assertEquals(300.0, h.entry, 0.0)
        assertTrue(log(), log().contains("Bought 30 $ce at 300.00 (LIVE)"))
        pass(52_030.0)
        assertEquals("the same signal again buys nothing more", 1, kite.placed.size)
    }

    @Test fun staleCandlesAreNeverTradedOn() {
        switchOn()
        pass(51_900.0)
        // The feed failed today and handed back yesterday's candles, with a BUY on the last one.
        candles = { AutomationSupport.bars(now.minusDays(1), List(9) { 51_900.0 } + 52_010.0) }
        pass(52_010.0)
        // A stalled feed: its last candle is 40 minutes old.
        candles = { AutomationSupport.bars(now.minusMinutes(40), List(9) { 51_900.0 } + 52_010.0) }
        pass(52_010.0)
        assertTrue("nothing traded on stale candles", kite.placed.isEmpty())
        candles = null
        pass(52_010.0)
        assertEquals("fresh candles with the same signal do trade", 1, kite.placed.size)
    }

    @Test fun nothingIsDecidedOutsideMarketHours() {
        switchOn()
        pass(51_900.0)
        now = now.withHour(8).withMinute(0)
        pass(52_010.0)
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun anExitSellsWhatIsHeldLessOtherWorkingSells() {
        bought(lots = 2)
        assertEquals("60", side("BUY").single().form["quantity"])
        // A protection's stop already rests on one lot of it.
        val stop = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 30, 30, "MIS", "SL", 240.0, 0.05, "NFO", "iraprotect", triggerPrice = 250.0), exit = true) }
        kite.requests.clear()
        pass(51_900.0)                                             // SELL signal; shortWith = exit
        assertEquals("60 held, 30 already covered: 30 sold", listOf("30"), side("SELL").map { it.form["quantity"] })
        assertEquals("the other exit is left resting", "TRIGGER PENDING", kite.order(stop).status)
        assertTrue(kite.requests.none { it.method == "DELETE" && it.path.endsWith("/$stop") })
    }

    @Test fun itsOwnEarlierExitIsCancelledNotAddedTo() {
        bought()
        // A sell of its own from an earlier pass (reply lost) is still resting at Zerodha.
        val own = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 30, 30, "MIS", "LIMIT", 900.0, 0.05, "NFO", "irapine"), exit = true) }
        kite.requests.clear()
        pass(51_900.0)
        assertTrue(kite.requests.any { it.method == "DELETE" && it.path == "/orders/regular/$own" })
        assertEquals("one market sell of what is held", listOf("30"), side("SELL").map { it.form["quantity"] })
        assertNull(held())
    }

    @Test fun aLostBuyReplyIsTrackedAndSettledFromTheOrderBook() {
        switchOn()
        pass(51_900.0)
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        kite.down += "/orders"
        pass(52_010.0)
        val h = held()!!
        assertTrue("held, unconfirmed: nothing more is bought meanwhile", h.unconfirmed)
        assertNull(h.order)
        assertTrue(AutomationSupport.alerts().any { it.contains("Zerodha did not confirm the buy of $ce") })
        kite.down.clear()
        pass(52_020.0)
        val settled = held()!!
        assertFalse(settled.unconfirmed)
        assertEquals(30, settled.qty); assertEquals(300.0, settled.entry, 0.0)
        assertEquals("one buy, never two", 1, side("BUY").size)
        assertTrue(log(), log().contains("Zerodha confirmed the buy"))
    }

    @Test fun theDailyLossLimitSellsAndPausesTheScriptForTheDay() {
        auto { it.copy(maxDayLoss = 1_000.0) }
        bought()
        kite.quote("NFO:$ce", 250.0, 249.95, 250.05)              // (250 - 300) x 30 = -1,500
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("Daily loss limit reached: no more trades today"))
        assertEquals(-1_500.0, PineAuto.todayOf(id)!!, 0.01)
        pass(51_900.0); pass(52_010.0)                             // new signals: no new trade today
        assertEquals(1, side("BUY").size)
    }

    @Test fun alertsOnlyPlacesNothing() {
        auto { it.copy(mode = "alert") }
        switchOn()
        pass(51_900.0); pass(52_010.0)
        assertTrue(kite.requests.none { it.method == "POST" })
        assertTrue(log(), log().contains("Signal: BUY at 52010.00"))
        assertNull(held())
    }

    @Test fun liveNeedsThePinOnce() {
        switchOn(pin = false)
        pass(51_900.0); pass(52_010.0)
        assertTrue(kite.placed.isEmpty())
        assertTrue(log(), log().contains("Live needs your PIN once"))
    }

    @Test fun whatIsHeldIsSoldAt1515() {
        bought()
        now = now.withHour(15).withMinute(11)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("15:15 square-off"))
        assertNull(held())
    }

    @Test fun theKillSwitchSellsAndStandsStill() {
        bought()
        SecurePrefs.put("g.kill", true)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("the day's stop"))
        pass(51_900.0); pass(52_010.0)
        assertEquals("nothing new while the kill switch is on", 1, side("BUY").size)
    }

    @Test fun switchingOffSellsWhatItHolds() {
        bought()
        assertEquals("ok", runBlocking { PineAuto.arm(id, false) })
        assertEquals(1, side("SELL").size)
        assertNull(held())
        assertFalse(PineScripts.get(id)!!.auto.on)
    }

    @Test fun aHoldingClosedOutsideTheAppIsForgotten() {
        bought()
        kite.position(ce, 0, 0.0, product = "MIS")                 // closed in the Kite app
        pass(52_010.0)
        assertNull(held())
        assertTrue(side("SELL").isEmpty())
        assertTrue(log(), log().contains("is no longer held"))
    }
}
