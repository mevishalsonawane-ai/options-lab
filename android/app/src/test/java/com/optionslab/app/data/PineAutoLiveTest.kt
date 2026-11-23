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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
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
        PineAuto.forgetStaleForTest()
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
        // 22 Nov 2026 (B6 review, item 5): the day's instruments list in memory, as the app's own reads leave it (an entry under the
        // scripts' lock no longer downloads it: [withTheInstrumentListNotInMemoryNothingIsSentAndItLoadsInTheBackground]).
        runBlocking { Broker.instruments() }
        kite.requests.clear()
        id = PineScripts.put(PineScripts.Item(0, "Level", code)).id
        auto()
    }

    @After fun down() {
        PineAuto.testOwners = null
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
    /** The orders sent on side [s], less the resting stops (SL): the buys and the market sells. */
    private fun side(s: String) = kite.placed.filter { it.form["transaction_type"] == s && it.form["order_type"] != "SL" }
    private fun stops() = kite.placed.filter { it.form["order_type"] == "SL" }

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
            "quantity" to "30", "product" to "MIS", "tag" to "irapine"), side("BUY").single().form.filterKeys { it in keys })
        val h = held()!!
        assertTrue(h.live); assertFalse(h.unconfirmed); assertEquals(30, h.qty); assertEquals(300.0, h.entry, 0.0)
        assertTrue(log(), log().contains("Bought 30 $ce at 300.00 (LIVE)"))
        // Its stop-loss (30 under the buy) rests at Zerodha at once (08 Oct): an SL sell of what was bought, its own tag.
        val stop = stops().single().form
        assertEquals(ce, stop["tradingsymbol"]); assertEquals("SELL", stop["transaction_type"]); assertEquals("30", stop["quantity"])
        assertEquals("MIS", stop["product"]); assertEquals("irapine", stop["tag"])
        assertEquals(270.0, stop["trigger_price"]!!.toDouble(), 1e-9)
        assertEquals(270.0, h.stopAt!!, 1e-9)
        assertEquals("TRIGGER PENDING", kite.order(h.stop!!).status)
        pass(52_030.0)
        assertEquals("the same signal again buys nothing more", 1, side("BUY").size)
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
        assertEquals("fresh candles with the same signal do trade", 1, side("BUY").size)
    }

    /**
     * 20 Nov 2026 (audit, B5 item 6: "Pine accepts a candle about 17 minutes old"): after a gap the feed's newest candle is a new one but
     * one candle behind - 11:10-11:15, read at 11:22 (closed 7 minutes ago), which the old rule (3 candles plus 2 minutes) took. In Live
     * the new rule is logged only (CLAUDE.md: a new rule that could skip live trades starts in shadow): the entry goes as before.
     */
    @Test fun inLiveALateNewCandleIsLoggedInShadowAndTheEntryGoesAsBefore() {
        switchOn()
        pass(51_900.0)                                             // 11:07: SELL on the 11:00 candle
        candles = { AutomationSupport.bars(now.minusMinutes(5), List(9) { 51_900.0 } + 52_010.0) }
        pass(52_010.0, minutes = 15)                               // 11:22: the newest candle 11:10-11:15
        assertEquals("live unchanged: it trades", 1, side("BUY").size)
        assertEquals(log(), 1, Regex("shadow \\(B5 item 6\\)").findAll(log()).count())
    }

    /** In Paper the same late candle is not entered on (said once), and the change is read again on the next, fresh candle. */
    @Test fun inPaperALateNewCandleIsNotEnteredOnAndTheNextFreshOneIsRead() {
        // (Paper prices its entry from the candle feed: a local fake, never the real host.)
        val clock = Market.testClock
        val upstox = com.optionslab.app.testing.FakeUpstox()            // (it clears the market clock: put back)
        Market.testClock = clock
        try { paperLateCandle() } finally { upstox.close() }
    }

    private fun paperLateCandle() {
        AutomationSupport.liveSettings(live = false)
        switchOn()
        pass(51_900.0)                                             // 11:07
        candles = { AutomationSupport.bars(now.minusMinutes(5), List(9) { 51_900.0 } + 52_010.0) }
        pass(52_010.0, minutes = 15)                               // 11:22: one candle late
        assertTrue(log(), log().contains("no entry on it; read again on the next"))
        assertNull(held())
        pass(52_010.0, minutes = 0)                                // the same late candle on the next pass: said once
        assertEquals(log(), 1, Regex("no entry on it").findAll(log()).count())
        assertEquals(log(), 1, Regex("Signal: BUY").findAll(log()).count())
        assertNull(held())
        candles = null
        pass(52_010.0, minutes = 1)                                // 11:23: the 11:15-11:20 candle, 3 minutes after its close
        assertEquals("the change is read again on the fresh candle: ${log()}", 2, Regex("Signal: BUY").findAll(log()).count())
        assertFalse(log(), log().contains("shadow (B5 item 6)"))
        assertTrue("nothing at Zerodha in Paper", kite.placed.isEmpty())
    }

    @Test fun aChangeMissedWhileTheAppWasAwayIsCaughtUp() {
        switchOn()
        pass(51_900.0)
        // The watch did not run for 15 minutes; the signal turned BUY two candles ago, while it was away. Missed data is
        // not a pause: the day's candles are read again and the signal as it is now is traded.
        candles = { AutomationSupport.bars(now, List(7) { 51_900.0 } + List(3) { 52_010.0 }) }
        pass(52_010.0, minutes = 15)
        assertEquals("missed candles are caught up, not skipped", 1, side("BUY").size)
    }

    @Test fun aSlowPassThatSkippedACandleIsNotAPause() {
        switchOn()
        pass(51_900.0)
        // One pass took 10 minutes (a slow phone network): the signal turned BUY on the candle it skipped. Still bought.
        candles = { AutomationSupport.bars(now, List(8) { 51_900.0 } + List(2) { 52_010.0 }) }
        pass(52_010.0, minutes = 10)
        assertEquals("a skipped candle is not a pause", 1, side("BUY").size)
    }

    @Test fun aChangeOnTheNewestCandleAfterAPauseStillTrades() {
        switchOn()
        pass(51_900.0)
        // Away for 15 minutes, but the signal stayed SELL until the newest candle turned it BUY.
        pass(52_010.0, minutes = 15)
        assertEquals(1, side("BUY").size)
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

    /**
     * 20 Nov 2026 (audit B5 deep review): the live stop's reply is lost and the book does not show it yet: UNKNOWN - looked for on the
     * later passes, never rested a second time; once the book shows it, it is the holding's stop.
     */
    @Test fun aLiveStopWhoseReplyIsLostIsLookedForAndNeverRestedTwice() {
        kite.afterNext("/orders/regular") { kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT); kite.trailReads = 50 }
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        bought()
        assertEquals(1, stops().size)
        assertNull(held()!!.stop)
        pass(52_020.0)
        assertEquals("no second stop while its lost reply is looked for", 1, stops().size)
        kite.showInBook()
        pass(52_030.0)
        assertEquals(1, stops().size)
        assertEquals(kite.orders.values.single { it.type == "SL" }.id, held()!!.stop)
    }

    /**
     * 20 Nov 2026 (deep review, confirmed): a lost stop given up on (the book read, the order not matched) while a stop of the scripts'
     * is in fact resting on the contract: that working stop is taken as the holding's own, never a second SL beside it.
     */
    @Test fun aLostStopGivenUpWhileAStopRestsTakesThatStopNeverASecond() {
        kite.afterNext("/orders/regular") { kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT); kite.trailReads = 50 }
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        bought()
        assertEquals(1, stops().size)
        assertNull(held()!!.stop)
        // The resting stop no longer matches what was sent (its search will never find it), and the window is over: given up on.
        val resting = kite.orders.values.single { it.type == "SL" }
        resting.trigger = resting.trigger - 1.0
        kite.showInBook()
        Broker.lostSearchMsForTest = 0
        pass(52_020.0)
        assertEquals("no second SL", 1, stops().size)
        assertEquals("the working stop taken as its own", resting.id, held()!!.stop)
    }

    /**
     * 20 Nov 2026 (delta review): Boss's own sell order covers one of the two lots when the stop goes, so it rests for one; once his
     * order is gone, the same stop is grown to both lots (one stop, never more resting than held). A part the stop sold leaves the lot
     * left with a stop again.
     */
    @Test fun aStopRestingForPartOfTheHoldingIsGrownOnceRoomAppears() {
        auto { it.copy(lots = 2) }
        PineAuto.topUpGapMsForTest = 0
        switchOn()
        pass(51_900.0)
        val boss = kite.restingInKite(ce, "SELL", 30, 400.0, type = "LIMIT", tag = "", product = "MIS")
        pass(52_010.0)
        assertEquals(60, held()!!.qty)
        val sl = kite.orders.values.single { it.type == "SL" }
        assertEquals("rests for what Boss's order leaves", 30, sl.qty)
        kite.order(boss).status = "CANCELLED"
        pass(52_020.0)
        assertEquals("one stop, never two", 1, stops().size)
        assertEquals("grown to the whole holding", 60, kite.order(sl.id).qty)
        // The stop sells one lot: the lot left gets a stop again.
        kite.order(sl.id).filled = 30
        kite.order(sl.id).status = "COMPLETE"
        kite.position(ce, 30, 300.0, product = "MIS")
        pass(52_030.0)
        pass(52_040.0)
        assertEquals(30, held()!!.qty)
        assertNotNull("the lot left has a stop again", held()!!.stop)
        assertEquals(2, stops().size)
    }

    /**
     * 20 Nov 2026 (delta review): a lost stop found for more than the holding (sent for 2 lots, 1 held) is cut to the holding at
     * Zerodha before it is taken as its stop - never left resting for more than is held; no second stop is sent.
     */
    @Test fun aLostStopFoundForMoreThanTheHoldingIsCutToIt() {
        kite.afterNext("/orders/regular") { kite.drop += "/orders/regular" }    // the stop's POST read and dropped: nothing placed
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        bought()
        kite.drop.clear()
        val h = held()!!
        assertNull(h.stop)
        val key = "pinestop:$id:${h.kite}:${h.entry}"
        assertTrue(Broker.lostPending(key))
        // (The stop sent, as this test has it, was for 60 at 250 - and that is what rests.)
        Broker.forgetLost(key)
        Broker.noteLost(key, Kite.Order(h.kite!!, Kite.Side.SELL, 60, 30, "MIS", "SL", 237.5, 0.05, "NFO", "irapine", triggerPrice = 250.0),
            emptyList(), "test", liveOnly = true)
        val big = kite.restingInKite(h.kite!!, "SELL", 60, 237.5, type = "SL", trigger = 250.0, tag = "irapine", product = "MIS")
        val sent = stops().size
        pass(52_020.0)
        assertEquals("taken as its stop", big, held()!!.stop)
        assertEquals("cut to the 30 held", 30, kite.order(big).qty - kite.order(big).filled)
        assertEquals("no second stop", sent, stops().size)
    }

    @Test fun theDailyLossLimitSellsAndPausesTheScriptForTheDay() {
        auto { it.copy(maxDayLoss = 1_000.0, stopPts = 100.0) }     // a stop wider than the fall: the day's limit sells
        bought()
        kite.quote("NFO:$ce", 250.0, 249.95, 250.05)              // (250 - 300) x 30 = -1,500
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("Daily loss limit reached: no more trades today"))
        assertEquals(-1_500.0, PineAuto.todayOf(id)!!, 0.01)
        pass(51_900.0); pass(52_010.0)                             // new signals: no new trade today
        assertEquals(1, side("BUY").size)
    }

    @Test fun resettingThePaperAccountNeverLiftsALiveDailyLossPause() {
        auto { it.copy(maxDayLoss = 1_000.0, stopPts = 100.0) }
        bought()
        kite.quote("NFO:$ce", 250.0, 249.95, 250.05)
        pass(52_010.0)
        assertTrue(log(), log().contains("Daily loss limit reached: no more trades today"))
        runBlocking { PineAuto.resetPaper() }
        assertEquals("the live day's loss is kept", -1_500.0, PineAuto.todayOf(id)!!, 0.01)
        pass(51_900.0); pass(52_010.0)
        assertEquals("still paused: no new live buy today", 1, side("BUY").size)
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

    // ---- line-by-line review G3 (11 Oct) ------------------------------------------------------------------------------

    @Test fun aSignalExitZerodhaRefusedIsTriedAgainAndTheStopGoesBackMeanwhile() {
        bought()                                                   // 30 at 300, its stop resting at 270
        val stop = held()!!.stop!!
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION)              // the market sell is refused
        pass(51_900.0)                                             // SELL signal; shortWith = exit
        assertEquals("the stop was taken out for the sale", "CANCELLED", kite.order(stop).status)
        assertTrue("marked the app's own cancel (22 Nov 2026, B6 review, item 9)", PineAuto.ownCancelForTest(stop))
        val h = held()!!
        assertEquals("still held: the sale did not go", 30, h.qty)
        // M1: the stop is back at Zerodha where it rested, for what is still held.
        val back = h.stop!!
        assertTrue(back != stop)
        assertEquals("TRIGGER PENDING", kite.order(back).status)
        assertEquals(270.0, kite.order(back).trigger, 1e-9)
        assertEquals(270.0, h.stopAt!!, 1e-9)
        // H1: the next candle still says SELL (no new change of signal): the owed exit is sent again, and goes.
        pass(51_900.0)
        assertEquals("the refused sell, then the retry", 2, side("SELL").size)
        assertNull("sold on the retry", held())
        assertTrue(log(), log().contains("signal changed (tried again)"))
        assertEquals("CANCELLED", kite.order(back).status)
    }

    @Test fun aLiveBuyIsBookedAndItsStopPlacedEvenWhenItsOrderLabelCannotBeSaved() {
        switchOn()
        pass(51_900.0)
        // The strategies vault cannot be written now (a Keystore or disk fault).
        com.optionslab.app.security.Vault.testFailWrites = setOf("strategies.vault")
        try {
            pass(52_010.0)
        } finally { com.optionslab.app.security.Vault.testFailWrites = emptySet() }
        assertEquals(1, side("BUY").size)
        val h = checkNotNull(held()) { "the live buy is booked: " + log() }
        assertEquals(30, h.qty)
        assertEquals("its stop rests at Zerodha", "TRIGGER PENDING", kite.order(h.stop!!).status)
        assertFalse(log(), log().contains("Error:"))
    }

    @Test fun anEntryRefusedAfterTheExposureCheckDropsItsClaimAtOnce() {
        AutoExposure.resetForTest()
        switchOn()
        pass(51_900.0)
        kite.quote("NFO:$ce", 0.0)                                 // no Zerodha quote: nothing is sent
        pass(52_010.0)
        assertTrue(kite.placed.isEmpty())
        assertTrue(log(), log().contains("No Zerodha quote for $ce: nothing sent"))
        assertEquals("no claim left standing for an entry that never happened", emptyList<String>(), AutoExposure.claims())
    }

    @Test fun switchingOffSellsWhatItHolds() {
        bought()
        assertEquals("ok", runBlocking { PineAuto.arm(id, false) })
        assertEquals(1, side("SELL").size)
        assertNull(held())
        assertFalse(PineScripts.get(id)!!.auto.on)
    }

    // ---- Boss's 06 Oct fixes ------------------------------------------------------------------------------------

    /** A second script on the same symbol and chart, armed, with [code2] (another strategy unless it is the same code). */
    private fun second(code2: String, name: String = "Level 2"): Long {
        val id2 = PineScripts.put(PineScripts.Item(0, name, code2)).id
        PineScripts.setAuto(id2, PineScripts.Auto(on = true, symbol = "BANKNIFTY", interval = "5m", lots = 1, buy = "Buy", sell = "Sell", shortWith = "exit"))
        runBlocking { PineAuto.arm(id2, true, pinConfirmed = true) }
        return id2
    }

    @Test fun twoScriptsNeverHoldTheSameSideOfOneIndexAtOnce() {
        // Another strategy (its level differs) on the same index: both say BUY on the same candle; only the first buys.
        val id2 = second(code.replace("52000", "51950"))
        switchOn()
        pass(51_900.0)
        pass(52_010.0)
        assertEquals("one buy, never two", 1, side("BUY").size)
        assertTrue(held() != null)
        assertNull(PineAuto.held.value[id2])
        val log2 = PineAuto.log.value.filter { it.script == id2 }.joinToString("\n") { it.text }
        assertTrue(log2, log2.contains("Not bought: same_side_already_held: Pine #$id · Level holds "))
    }

    @Test fun anIdenticalScriptSavedBeforeThisUpdateIsSwitchedOffAndItsHoldingManagedToItsExit() {
        bought()                                                   // #id holds the call
        val id2 = second("// the same strategy, added again at 09:50\n" + code.replace("\n", "\n\n"), name = "Level again")
        // As saved before this update: the newer copy holds the call and the duplicates switch-off has not run yet.
        val f = java.io.File(context.noBackupFilesDir, "pine_auto.vault")
        val o = org.json.JSONObject(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
        val h = o.getJSONObject("held").getJSONObject(id.toString())
        o.put("held", org.json.JSONObject().put(id2.toString(), h))
        o.remove("migrated")
        PineAuto.wipe()
        com.optionslab.app.security.Vault.writeFile(f, o.toString().toByteArray(Charsets.UTF_8))
        runBlocking { PineAuto.load() }
        pass(52_010.0)
        assertTrue("the oldest stays armed", PineScripts.get(id)!!.auto.on)
        assertFalse("the repeat is switched off", PineScripts.get(id2)!!.auto.on)
        assertNotNull("never deleted", PineScripts.get(id2))
        val log2 = { PineAuto.log.value.filter { it.script == id2 }.joinToString("\n") { it.text } }
        assertTrue(log2(), log2().contains("Switched off: the same strategy as #$id"))
        assertNotNull("its call is still held, managed to its exit", PineAuto.held.value[id2])
        assertTrue("not dumped", side("SELL").isEmpty())
        assertEquals("nothing new bought", 1, side("BUY").size)
        pass(51_900.0)                                             // its signal turns: the held call is sold (its exit)
        assertEquals(1, side("SELL").size)
        assertNull(PineAuto.held.value[id2])
        pass(52_010.0)                                             // BUY again: only the armed original buys
        assertEquals(2, side("BUY").size)
        assertNotNull(held())
        assertNull(PineAuto.held.value[id2])
        pass(51_900.0); pass(52_010.0)
        assertTrue("the switch-off runs once and never re-arms", !PineScripts.get(id2)!!.auto.on)
    }

    @Test fun aLosingScriptSwitchedOffOnBosssYesKeepsItsHoldingToItsExit() {
        bought()
        assertEquals("ok", runBlocking { PineAuto.windDown(id, "paper record negative (15 trades after charges; Boss approved)") })
        assertFalse(PineScripts.get(id)!!.auto.on)
        assertNotNull("not sold now", held())
        assertTrue(side("SELL").isEmpty())
        assertTrue(log(), log().contains("Switched off: paper record negative"))
        pass(52_020.0)
        assertEquals("nothing more bought", 1, side("BUY").size)
        pass(51_900.0)                                             // its exit: the signal turns
        assertEquals(1, side("SELL").size)
        assertNull(held())
        pass(52_010.0)
        assertEquals("never switched back on", 1, side("BUY").size)
        assertEquals("already off", runBlocking { PineAuto.windDown(id, "again") })
    }

    /**
     * 22 Oct (arms audit): Pine is hidden (Boss's 15 Oct cleanup) while a script still holds an option, so the cleanup waits for
     * its exit. The exit runs as before; the signal's flip buys nothing more (before, it sold the call and bought the put).
     */
    @Test fun aHiddenScriptWaitingForItsExitSellsOnTheFlipAndBuysNothingMore() {
        bought()
        HiddenArms.resetForTest(context)
        try {
            runBlocking { HiddenArms.hideAgain(com.optionslab.engine.orb.ArmsCleanup.PINE) }
            assertTrue(HiddenArms.blocks(com.optionslab.engine.orb.ArmsCleanup.PINE))
            pass(52_020.0)
            assertEquals("held to its own exit", 1, side("BUY").size)
            assertNotNull(held())
            pass(51_900.0)                                         // the signal turns: its exit sells
            assertEquals(1, side("SELL").size)
            assertNull(held())
            pass(52_010.0)                                         // and turns again: nothing new is bought while hidden
            assertEquals("nothing bought while hidden", 1, side("BUY").size)
            assertNull(held())
            assertTrue("the script's own switch is untouched here (the cleanup switches it off once flat)", PineScripts.get(id)!!.auto.on)
        } finally { HiddenArms.resetForTest(context) }
    }

    @Test fun aHoldingClosedOutsideTheAppIsForgotten() {
        bought()
        val stop = held()!!.stop!!
        // 19 Nov 2026 (audit): closed in the Kite app - Boss's own sale in Zerodha's order book (one read at 0 alone no longer drops a
        // held position: [aSingleFlatReadNeverTakesOutAHeldPositionsStop]).
        kite.soldInKite(ce, 30, 310.0)
        pass(52_010.0)
        assertNull(held())
        assertTrue(side("SELL").isEmpty())
        assertTrue(log(), log().contains("is no longer held"))
        // Its resting stop is taken out at Zerodha too, confirmed: it can never sell what is no longer held.
        assertEquals("CANCELLED", kite.order(stop).status)
        assertTrue("marked the app's own cancel (22 Nov 2026, B6 review, item 9)", PineAuto.ownCancelForTest(stop))
        assertFalse("nothing left to take out", PineAuto.holding())
    }

    /**
     * 19 Nov 2026 (audit, CRITICAL): Boss sells the option in Kite at its target; the fast lane's exit then finds Zerodha flat. Before,
     * the holding was dropped as "already closed" BEFORE its resting SL SELL was cancelled: the stop stayed at Zerodha, and a fall back
     * to it would have opened a short nothing tracked. Now the stop is cancelled and confirmed gone, and nothing is sold.
     */
    @Test fun aSaleInKiteAtTheTargetThenTheFastLanesExitLeavesNoWorkingStop() {
        bought()
        val stop = held()!!.stop!!
        kite.quote("NFO:$ce", 360.0, 359.95, 360.05)              // the target (300 + 60)
        kite.soldInKite(ce, 30, 360.0)                            // Boss sold it in Kite
        kite.requests.clear()
        runBlocking { PineAuto.watchOnly(fast = true) }
        assertNull(held())
        assertEquals("CANCELLED", kite.order(stop).status)
        assertTrue("no sale of the app's", side("SELL").isEmpty())
        assertTrue("no exit of the app's works on the contract",
            kite.orders.values.none { it.working && it.symbol == ce && it.side == "SELL" })
        assertTrue(log(), log().contains("$ce already closed"))
        assertTrue(log(), log().contains("its stop and exits at Zerodha are taken out"))
        assertFalse(PineAuto.holding())
        // The price falls back through the old stop's level: nothing is sold, no short.
        kite.quote("NFO:$ce", 260.0, 259.95, 260.05)
        runBlocking { PineAuto.watchOnly() }
        pass(52_010.0)
        assertTrue(kite.placed.none { it.form["transaction_type"] == "SELL" && it.form["order_type"] != "SL" })
        assertEquals(0, kite.positions["NFO:$ce:MIS"]!!.first)
    }

    /**
     * 19 Nov 2026 (audit): the closed holding's stop could not be cancelled (Zerodha refused the cancel): it is kept as being taken out,
     * tried again on every pass, Boss is told once after [PineAuto.REMOVE_TRIES] tries, and it is taken out once Zerodha accepts.
     */
    @Test fun aStopThatCannotBeTakenOutIsTriedAgainEveryPassAndBossIsTold() {
        com.optionslab.app.work.Alerts.forgetPosted()
        bought()
        val stop = held()!!.stop!!
        kite.soldInKite(ce, 30, 310.0)
        kite.down += "/orders/regular/$stop"                      // every cancel of it fails
        pass(52_010.0)
        assertNull(held())
        assertEquals("TRIGGER PENDING", kite.order(stop).status)
        assertTrue("still being taken out: the 15-second check keeps running", PineAuto.holding())
        val told = { com.optionslab.app.work.Alerts.posted.filter { it.text.contains("could not be taken out at Zerodha") } }
        assertTrue("not told on the first try", told().isEmpty())
        runBlocking { PineAuto.watchOnly() }
        pass(52_010.0)
        assertEquals("told once after ${PineAuto.REMOVE_TRIES} tries", 1, told().size)
        assertTrue(told().single().text, told().single().text.contains(stop))
        pass(52_010.0)
        assertEquals("told once", 1, told().size)
        val cancels = kite.requests.count { it.method == "DELETE" && it.path == "/orders/regular/$stop" }
        assertTrue("tried on every check: $cancels", cancels >= 4)
        kite.down.clear()
        pass(52_010.0)
        assertEquals("CANCELLED", kite.order(stop).status)
        assertFalse(PineAuto.holding())
        assertTrue(kite.placed.none { it.form["transaction_type"] == "SELL" && it.form["order_type"] != "SL" })
    }

    /** 19 Nov 2026 (audit, review): a stop still being taken out is dropped in the next session (its DAY order ended): no endless check. */
    @Test fun aStopStillBeingTakenOutIsDroppedInTheNextSession() {
        bought()
        val stop = held()!!.stop!!
        kite.soldInKite(ce, 30, 310.0)
        kite.down += "/orders/regular/$stop"
        pass(52_010.0)
        assertTrue(PineAuto.holding())
        now = now.plusDays(1); PineAuto.testNow = now
        runBlocking { PineAuto.watchOnly() }
        assertFalse(PineAuto.holding())
    }

    /** A closed holding whose stop Zerodha keeps refusing to cancel: a removal pending, nothing held. */
    private fun removalPending(): String {
        bought()
        val stop = held()!!.stop!!
        kite.soldInKite(ce, 30, 310.0)
        kite.down += "/orders/regular/$stop"
        pass(52_010.0)
        assertNull(held())
        assertTrue(PineAuto.holding())
        return stop
    }

    /**
     * 19 Nov 2026 (second review, 1): with only a removal pending, the LIVE lane's fast look never waits on the lock a pass holds
     * (its candle reads can take seconds): the next books' stops come first.
     */
    @Test fun aFastLookWithOnlyARemovalPendingNeverWaitsOnThePass() {
        removalPending()
        val lock = PineAuto::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(null) as kotlinx.coroutines.sync.Mutex
        runBlocking { lock.lock() }                               // a pass under way
        try {
            runBlocking { kotlinx.coroutines.withTimeout(2_000) { PineAuto.watchOnly(fast = true, stream = true) } }
            runBlocking { kotlinx.coroutines.withTimeout(2_000) { PineAuto.watchOnly(fast = true) } }
        } finally { lock.unlock() }
        assertTrue("the 15-second check still takes it out", PineAuto.holding())
    }

    /**
     * 19 Nov 2026 (second review, 2): closed through the app's own Trade tab or a notification's Close (tag "iraalgo", a hand order) -
     * Boss's own sale, as one in Kite: taken as closed and its stop taken out on the same pass.
     */
    @Test fun aCloseFromTheAppsTradeTabIsBosssOwnSale() {
        bought()
        val stop = held()!!.stop!!
        val sale = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 30, 30, "MIS", "MARKET", null, 0.05, "NFO"), exit = true) }
        runBlocking { Strategies.tagOwner("kite:$sale", Origins.manual("Trade")) }
        assertEquals("COMPLETE", kite.order(sale).status)
        pass(52_010.0)
        assertNull(held())
        assertEquals("CANCELLED", kite.order(stop).status)
        assertFalse(PineAuto.holding())
    }

    /**
     * 19 Nov 2026 (second review, 3): bought again on the same contract while the earlier stop is still being taken out: never skipped
     * (a new live skip starts in shadow) - logged as a would-be wait and Boss told once; the removal cancels only the earlier stop.
     */
    @Test fun aBuyAgainWhileTheEarlierStopIsBeingTakenOutIsLoggedInShadowAndTold() {
        com.optionslab.app.work.Alerts.forgetPosted()
        val stop = removalPending()
        pass(51_900.0)                                             // SELL: nothing to sell (shortWith exit)
        pass(52_010.0)                                             // BUY again: the same call
        assertEquals("bought as before", 2, side("BUY").size)
        assertTrue(log(), log().contains("Shadow (logged only): would have waited"))
        val told = com.optionslab.app.work.Alerts.posted.filter { it.text.contains("again while the earlier holding's stop (order $stop)") }
        assertEquals(1, told.size)
        val newStop = held()!!.stop!!
        assertTrue(newStop != stop)
        kite.down.clear()
        pass(52_010.0)
        assertEquals("CANCELLED", kite.order(stop).status)
        assertEquals("the new holding's stop is never touched", "TRIGGER PENDING", kite.order(newStop).status)
        assertNotNull(held())
    }

    /**
     * 19 Nov 2026 (second review, 4): closed on a stream look, whose kept order book may miss an own exit sent since: the stop comes
     * out at once, and the contract's other own exits are looked for in a fresh read on the next 15-second check.
     */
    @Test fun aCloseOnAStreamLookScansAFreshOrderBookForItsOtherExits() {
        bought()
        val stop = held()!!.stop!!
        kite.quote("NFO:$ce", 360.0, 359.95, 360.05)              // at the target: the exit runs
        // Zerodha's expiry square-off sold it (an outside sale, known on a stream look too).
        kite.orders["9001"] = FakeKite.Order("9001", ce, "NFO", "SELL", 30, "MIS", "MARKET", 0.0, 0.0, "iraalgoexpiry", "COMPLETE", filled = 30, avg = 360.0)
        kite.position(ce, 0, 0.0, product = "MIS")
        runBlocking { Broker.orders() }                           // the kept read
        // An own exit sent since (its reply lost), not in the kept read.
        kite.orders["9002"] = FakeKite.Order("9002", ce, "NFO", "SELL", 30, "MIS", "LIMIT", 900.0, 0.0, "irapine", "OPEN")
        KiteStream.liveForTest(true)
        try { runBlocking { PineAuto.watchOnly(fast = true, stream = true) } } finally { KiteStream.liveForTest(false) }
        assertNull(held())
        assertEquals("the stop at once", "CANCELLED", kite.order(stop).status)
        runBlocking { PineAuto.watchOnly() }                       // the 15-second check
        assertEquals("found in a fresh read and taken out", "CANCELLED", kite.order("9002").status)
        assertFalse(PineAuto.holding())
    }

    /**
     * 19 Nov 2026 (second review, 5): the stop's fill not known when it was recorded (both reads failed): its own earlier fill is never
     * told as a short.
     */
    @Test fun aStopFillNotKnownWhenRecordedIsNeverToldAsAShort() {
        com.optionslab.app.work.Alerts.forgetPosted()
        bought()
        val stop = held()!!.stop!!
        kite.fill(stop, 270.0)                                     // the stop sold at Zerodha
        kite.down += "/orders"; kite.down += "/orders/$stop"       // ... and neither the order book nor its state can be read
        pass(52_010.0, minutes = 1)
        assertNotNull(held())
        pass(52_010.0, minutes = 5)                                // five minutes of reads at 0: taken as closed
        assertNull(held())
        kite.down.clear()
        runBlocking { PineAuto.watchOnly() }
        val said = com.optionslab.app.work.Alerts.posted.map { it.text }
        assertTrue(said.joinToString(), said.none { it.contains("may be open") })
        // (What filled is not known to be the close or a later sale: Boss is asked to look, without a short alarm.)
        assertEquals(said.joinToString(), 1, said.count { it.contains("not known whether before or after the position was closed") })
        assertFalse(PineAuto.holding())
    }

    /**
     * 19 Nov 2026 (third review, C1): the order labels cannot be read; Boss bought a lot through the Trade tab ("iraalgo") and sold one
     * in Kite - his own round trip - and one positions read shows the script's holding at 0: kept, its stop resting.
     */
    @Test fun withTheLabelsUnreadableBosssTradeTabBuyAndKiteSaleNeverCloseTheHolding() {
        bought()
        val stop = held()!!.stop!!
        kite.orders["9101"] = FakeKite.Order("9101", ce, "NFO", "BUY", 30, "MIS", "MARKET", 0.0, 0.0, "iraalgo", "COMPLETE", filled = 30, avg = 300.0)
        kite.soldInKite(ce, 30, 305.0)
        kite.position(ce, 0, 0.0, product = "MIS")                // one bad read
        PineAuto.testOwners = { throw IllegalStateException("labels unreadable") }
        pass(52_010.0, minutes = 1)
        assertNotNull("kept as held", held())
        assertEquals("TRIGGER PENDING", kite.order(stop).status)
    }

    /**
     * 19 Nov 2026 (third review, follow-up): Boss is told once for each earlier holding still being taken out - a second one on the same
     * contract the same day is told too, the first never again.
     */
    @Test fun eachEarlierHoldingStillBeingTakenOutIsToldOnce() {
        com.optionslab.app.work.Alerts.forgetPosted()
        val stop1 = removalPending()
        pass(51_900.0)
        pass(52_010.0)                                             // buy again: told of stop1
        val stop2 = held()!!.stop!!
        kite.soldInKite(ce, 30, 310.0)                             // the second holding sold in Kite too
        kite.down += "/orders/regular/$stop2"
        pass(52_010.0)
        assertNull(held())
        pass(51_900.0)
        pass(52_010.0)                                             // buy again: told of stop2 only
        val told = com.optionslab.app.work.Alerts.posted.map { it.text }.filter { it.contains("again while the earlier holding's") }
        assertEquals(told.joinToString("\n"), 2, told.size)
        assertTrue(told[0], told[0].contains(stop1))
        assertTrue(told[1], told[1].contains(stop2) && !told[1].contains(stop1))
    }

    /**
     * 19 Nov 2026 (third review, C3): before a buy again on the contract, every earlier stop still being taken out is tried again - one
     * Zerodha now accepts to cancel is gone before the buy, and nothing is left to wait for.
     */
    @Test fun aBuyAgainFirstTriesEveryEarlierStopStillBeingTakenOut() {
        val stop = removalPending()
        pass(51_900.0)
        kite.down.clear()                                          // Zerodha accepts the cancel again
        pass(52_010.0)                                             // BUY again
        assertEquals(2, side("BUY").size)
        assertEquals("CANCELLED", kite.order(stop).status)
        assertFalse(log(), log().contains("would have waited"))
    }

    /** 19 Nov 2026 (third review, C4): an entry tried before the buy is not tried again from the pass's older copy (one try a pass). */
    @Test fun anEntryTriedBeforeTheBuyIsNotTriedAgainOnTheSamePass() {
        val stop = removalPending()
        pass(51_900.0)
        val before = kite.requests.count { it.method == "DELETE" && it.path == "/orders/regular/$stop" }
        pass(52_010.0)                                             // BUY again, the cancel still refused
        val tries = kite.requests.count { it.method == "DELETE" && it.path == "/orders/regular/$stop" } - before
        assertEquals("one try this pass", 1, tries)
    }

    /**
     * 19 Nov 2026 (audit; the 18 Nov class of bug): ONE positions read showing a held option at 0, with no sale anywhere, neither drops
     * it nor takes its stop out - not by the fast lane's exit, not by the full pass. Read held again, it is sold at its target as usual.
     */
    @Test fun aSingleFlatReadNeverTakesOutAHeldPositionsStop() {
        bought()
        val stop = held()!!.stop!!
        kite.position(ce, 0, 0.0, product = "MIS")                // a bad read: 0, no sale in the order book
        kite.quote("NFO:$ce", 360.0, 359.95, 360.05)              // at the target: the exit runs
        kite.requests.clear()
        runBlocking { PineAuto.watchOnly(fast = true) }
        pass(52_010.0, minutes = 0)
        assertNotNull("kept as held", held())
        assertEquals("its stop stays at Zerodha", "TRIGGER PENDING", kite.order(stop).status)
        assertTrue(kite.requests.none { it.method == "DELETE" })
        assertTrue(side("SELL").isEmpty())
        assertTrue(log(), log().contains("not taken as closed until confirmed"))
        kite.position(ce, 30, 300.0, product = "MIS")              // read held again
        runBlocking { PineAuto.watchOnly() }
        assertEquals("sold at its target", listOf("30"), side("SELL").map { it.form["quantity"] })
        assertNull(held())
    }

    /** 19 Nov 2026 (audit): reads that keep showing it at 0 for five minutes, with no sale anywhere, close it; its stop is taken out. */
    @Test fun readsAtZeroForFiveMinutesCloseItAndTakeItsStopOut() {
        com.optionslab.app.work.Alerts.forgetPosted()
        bought()
        val stop = held()!!.stop!!
        kite.position(ce, 0, 0.0, product = "MIS")
        pass(52_010.0, minutes = 1)
        assertNotNull(held())
        assertEquals("TRIGGER PENDING", kite.order(stop).status)
        pass(52_010.0, minutes = 1)
        assertNotNull("30 s with no sale: kept, Boss told", held())
        assertTrue(com.optionslab.app.work.Alerts.posted.any { it.text.contains("at 0 for 30 s with no sale") })
        pass(52_010.0, minutes = 4)
        assertNull(held())
        assertEquals("CANCELLED", kite.order(stop).status)
        assertTrue(side("SELL").isEmpty())
    }

    @Test fun theProfitLockSellsWhatGaveBackPastItsLockedLevel() {
        auto { it.copy(targetPts = 100.0, profitLock = true) }
        bought()                                                   // 30 at 300, its stop-loss resting at 270
        val stop = held()!!.stop!!
        // 55% of the way: the ladder locks 25 (325); the trail at +18.3% keeps half of 55 (327.50): the higher counts.
        kite.quote("NFO:$ce", 355.0, 354.95, 355.05)
        pass(52_010.0)
        assertTrue("not sold on the look that set the best", side("SELL").isEmpty())
        assertEquals(355.0, held()!!.peak, 0.0)
        assertTrue(log(), log().contains("profit lock now at 327.50"))
        // 08 Oct: the lock MOVES the resting stop up - a modify of the same order at Zerodha, never a new sell.
        assertEquals(327.5, kite.order(stop).trigger, 1e-9)
        assertEquals("TRIGGER PENDING", kite.order(stop).status)
        assertEquals(327.5, held()!!.stopAt!!, 1e-9)
        assertTrue(log(), log().contains("resting stop moved up to 327.50 at Zerodha (profit lock)"))
        // The price gives back under the lock: Zerodha sells at the resting stop by itself; the app sends no sell of its own.
        kite.quote("NFO:$ce", 320.0, 319.95, 320.05)
        pass(52_010.0)
        assertTrue("the stop rests at the lock: no market sell", side("SELL").isEmpty())
        kite.fill(stop, 327.5)
        pass(52_010.0)
        assertTrue(side("SELL").isEmpty())
        assertTrue(log(), log().contains("Sold 30 $ce at 327.50 (profit lock, the resting stop, LIVE)"))
        assertNull(held())
        assertEquals(825.0, PineAuto.todayOf(id)!!, 0.01)
        assertEquals("nothing new is bought by the lock", 1, side("BUY").size)
    }

    @Test fun withTheDefaultStopAndTargetTheLockSellsATenPercentGainThatFalls() {
        // Boss's case (2026-10-06): +10% fell back to +2%. Every script now has the stop 30, target 60 and the lock.
        val a = PineScripts.get(id)!!.auto
        assertTrue(a.profitLock); assertEquals(30.0, a.stopPts, 0.0); assertEquals(60.0, a.targetPts, 0.0)
        bought()                                                   // 30 at 300
        kite.quote("NFO:$ce", 330.0, 329.95, 330.05)              // +10%: half of the 30 gained is kept (315)
        pass(52_010.0)
        assertTrue("not sold on the look that set the best", side("SELL").isEmpty())
        assertEquals(330.0, held()!!.peak, 0.0)
        assertTrue(log(), log().contains("profit lock now at 315.00"))
        kite.quote("NFO:$ce", 320.0, 319.95, 320.05)              // still above 315: held
        pass(52_010.0)
        assertTrue(side("SELL").isEmpty())
        assertEquals("the resting stop moved up to the lock", 315.0, held()!!.stopAt!!, 1e-9)
        kite.quote("NFO:$ce", 306.0, 305.95, 306.05)              // +2%: below the lock, Zerodha's stop sells there
        pass(52_010.0)
        assertTrue(side("SELL").isEmpty())
        kite.fill(held()!!.stop!!, 315.0)
        pass(52_010.0)
        assertTrue(log(), log().contains("Sold 30 $ce at 315.00 (profit lock, the resting stop, LIVE)"))
        assertNull(held())
        assertEquals("nothing new is bought by the lock", 1, side("BUY").size)
    }

    @Test fun theProfitLockCannotBeSwitchedOff() {
        // Boss's 06 Oct rule: a lock written off (an old screen, Jarvis, a restore) is saved on, and the same move is sold.
        auto { it.copy(targetPts = 100.0, profitLock = false, profitLockChosen = true) }
        assertTrue(PineScripts.get(id)!!.auto.profitLock)
        bought()
        val stop = held()!!.stop!!
        kite.quote("NFO:$ce", 355.0, 354.95, 355.05)
        pass(52_010.0)
        // The price ran through the stop's limit (311.10) without it filling: the app takes it out and sells at market.
        kite.quote("NFO:$ce", 300.0, 299.95, 300.05)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("at 300.00: profit lock (locked at 327.50)"))
        assertEquals("its resting stop was taken out first", "CANCELLED", kite.order(stop).status)
        assertNull(held())
    }

    // ---- Boss's 06 Oct rule: a stop-loss, a target and the profit lock on every Pine trade ---------------------------

    @Test fun aScriptWithoutStrategyExitIsSoldAtTheStop() {
        // The Level script is an indicator: no strategy.exit of its own. The app's stop (entry - 30) still sells it.
        bought()                                                   // 30 at 300, the stop resting at Zerodha at 270
        kite.quote("NFO:$ce", 275.0, 274.95, 275.05)
        pass(52_010.0)
        assertTrue("above 270: held", side("SELL").isEmpty())
        // Under 270: Zerodha's resting stop sells it there (08 Oct), not a market sell of the app's after the fact.
        kite.quote("NFO:$ce", 269.0, 268.95, 269.05)
        pass(52_010.0)
        assertTrue(side("SELL").isEmpty())
        kite.fill(held()!!.stop!!, 270.0)
        pass(52_010.0)
        assertTrue(side("SELL").isEmpty())
        assertTrue(log(), log().contains("Sold 30 $ce at 270.00 (stop-loss, the resting stop, LIVE)"))
        assertNull(held())
        assertEquals(-900.0, PineAuto.todayOf(id)!!, 0.01)
    }

    @Test fun aScriptWithoutStrategyExitIsSoldAtTheTargetAndTheLadderMovesOnTheWay() {
        bought()                                                   // 30 at 300
        kite.quote("NFO:$ce", 346.0, 345.95, 346.05)              // 77% of the way to +60: the ladder locks
        pass(52_010.0)
        assertTrue(side("SELL").isEmpty())
        assertTrue(log(), log().contains("profit lock now at"))
        kite.quote("NFO:$ce", 361.0, 360.95, 361.05)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("at 361.00: target"))
    }

    @Test fun aZeroStopOrTargetIsNeverSavedAndArmingWithoutThemIsRefused() {
        auto { it.copy(stopPts = 0.0, targetPts = 0.0) }
        val a = PineScripts.get(id)!!.auto
        assertEquals("a zero keeps what was there", 30.0, a.stopPts, 0.0); assertEquals(60.0, a.targetPts, 0.0)
        assertEquals(com.optionslab.ira.PineProtection.ARM_REFUSAL,
            com.optionslab.ira.PineProtection.armRefusal("trade", 0.0, a.targetPts))
        assertTrue(com.optionslab.ira.PineProtection.ARM_REFUSAL.startsWith("Set a stop-loss and a target first"))
        // A narrower stop is kept as typed.
        auto { it.copy(stopPts = 12.0) }
        assertEquals(12.0, PineScripts.get(id)!!.auto.stopPts, 0.0)
        switchOn()
        assertTrue(PineScripts.get(id)!!.auto.on)
    }

    @Test fun anArmedScriptSavedWithoutAStopOrTargetIsProtectedAndToldOnce() {
        val f = java.io.File(context.filesDir, "pine.vault")
        val text = org.json.JSONArray()
            .put(org.json.JSONObject().put("id", 1).put("name", "EMA").put("code", code)
                .put("auto", org.json.JSONObject().put("on", true).put("stopPts", 0.0).put("targetPts", 0.0).put("profitLock", false)
                    .put("profitLockChosen", true).put("buy", "Buy").put("sell", "Sell")))
            .put(org.json.JSONObject().put("id", 2).put("name", "Narrow").put("code", code)
                .put("auto", org.json.JSONObject().put("on", true).put("stopPts", 10.0).put("targetPts", 0.0)))
            .toString()
        PineScripts.wipe()
        com.optionslab.app.security.Vault.writeFile(f, text.toByteArray(Charsets.UTF_8))
        // As after a restart: the scripts are read from the file again.
        PineScripts::class.java.getDeclaredField("loaded").apply { isAccessible = true }.set(null, false)
        val items = PineScripts.loadNow()!!.associateBy { it.id }
        val ema = items.getValue(1).auto
        assertTrue("kept armed", ema.on); assertEquals(30.0, ema.stopPts, 0.0); assertEquals(60.0, ema.targetPts, 0.0); assertTrue(ema.profitLock)
        assertEquals("a nonzero stop is never widened", 10.0, items.getValue(2).auto.stopPts, 0.0)
        assertEquals(60.0, items.getValue(2).auto.targetPts, 0.0)
        runBlocking { PineAuto.load() }
        val lines = PineAuto.log.value.map { it.text }
        assertTrue(lines.toString(), lines.contains("Pine 'EMA': stop-loss 30 and target 60 added, profit lock on — Boss's 06 Oct rule"))
        assertTrue(lines.toString(), lines.contains("Pine 'Narrow': target 60 added, profit lock on — Boss's 06 Oct rule"))
        // Written back: read again, nothing more is said.
        val again = PineScripts.parseNoting(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
        assertTrue(again.second.isEmpty()); assertFalse(again.third)
        runBlocking { PineAuto.load() }
        assertEquals(1, PineAuto.log.value.count { it.text.startsWith("Pine 'EMA':") })
    }

    @Test fun aHoldingSavedBeforeTheProfitLockStartsItsBestAtTheBuyPriceThenTheLtp() {
        auto { it.copy(targetPts = 100.0, profitLock = true) }
        bought()
        val f = java.io.File(context.noBackupFilesDir, "pine_auto.vault")
        val o = org.json.JSONObject(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
        o.getJSONObject("held").getJSONObject(id.toString()).remove("peak")      // as an older app saved it
        PineAuto.wipe()
        com.optionslab.app.security.Vault.writeFile(f, o.toString().toByteArray(Charsets.UTF_8))
        runBlocking { PineAuto.load() }
        assertEquals(300.0, held()!!.peak, 0.0)
        kite.quote("NFO:$ce", 340.0, 339.95, 340.05)
        pass(52_010.0)
        assertEquals("the first look sets the best to max(entry, LTP)", 340.0, held()!!.peak, 0.0)
        assertTrue(side("SELL").isEmpty())
    }

    @Test fun scriptsSavedBeforeTheProfitLockAreMigrated() {
        val jarvisCode = "//@version=5\nstrategy(\"x\")\n// Written by Jarvis from the Hammer pattern: entry after the pattern's candle closes"
        val text = org.json.JSONArray()
            .put(org.json.JSONObject().put("id", 1).put("name", "Jarvis: Hammer NIFTY 5m").put("code", jarvisCode)
                .put("auto", org.json.JSONObject().put("on", true).put("symbol", "NIFTY")))
            .put(org.json.JSONObject().put("id", 2).put("name", "Renamed by Boss").put("code", jarvisCode)
                .put("auto", org.json.JSONObject().put("on", false)))
            .put(org.json.JSONObject().put("id", 3).put("name", "Mine with a stop").put("code", code)
                .put("auto", org.json.JSONObject().put("on", true).put("stopPts", 30.0)))
            .put(org.json.JSONObject().put("id", 4).put("name", "Mine, bare").put("code", code)
                .put("auto", org.json.JSONObject().put("on", false)))
            .put(org.json.JSONObject().put("id", 5).put("name", "Saved off by the old default").put("code", code)
                .put("auto", org.json.JSONObject().put("targetPts", 80.0).put("profitLock", false)))
            .put(org.json.JSONObject().put("id", 6).put("name", "Switched off by Boss").put("code", code)
                .put("auto", org.json.JSONObject().put("profitLock", false).put("profitLockChosen", true)))
            .put(org.json.JSONObject().put("id", 7).put("name", "His own trail").put("code", code)
                .put("auto", org.json.JSONObject().put("trail", org.json.JSONObject().put("be", 4.0)
                    .put("steps", org.json.JSONArray().put(org.json.JSONArray().put(6.0).put(140.0)).put(org.json.JSONArray().put(-1.0).put(70.0))))))
            .put(org.json.JSONObject().put("id", 8).put("name", "No auto at all").put("code", code))
            .toString()
        val items = PineScripts.parse(text).associateBy { it.id }
        assertTrue("Jarvis's, armed now", items.getValue(1).auto.profitLock); assertTrue(items.getValue(1).auto.byJarvis)
        assertTrue("Jarvis's by its own comment", items.getValue(2).auto.profitLock); assertTrue(items.getValue(2).auto.byJarvis)
        assertTrue("the owner's with a stop", items.getValue(3).auto.profitLock); assertFalse(items.getValue(3).auto.byJarvis)
        assertEquals("his stop kept", 30.0, items.getValue(3).auto.stopPts, 0.0); assertEquals(60.0, items.getValue(3).auto.targetPts, 0.0)
        assertTrue("the owner's with no stop or target", items.getValue(4).auto.profitLock)
        assertEquals(30.0, items.getValue(4).auto.stopPts, 0.0); assertEquals(60.0, items.getValue(4).auto.targetPts, 0.0)
        assertTrue("a false the old default wrote is not his choice", items.getValue(5).auto.profitLock)
        assertEquals(80.0, items.getValue(5).auto.targetPts, 0.0)
        // Boss's 06 Oct rule: even a lock he once switched off himself is on now (the field is kept, not counted).
        assertTrue(items.getValue(6).auto.profitLock); assertTrue(items.getValue(6).auto.profitLockChosen)
        assertEquals(com.optionslab.engine.orb.ProfitLock.Trail(), items.getValue(4).auto.trail)
        assertEquals(com.optionslab.engine.orb.ProfitLock.Trail(4.0, listOf(com.optionslab.engine.orb.ProfitLock.Trail.Step(6.0, 95.0),
            com.optionslab.engine.orb.ProfitLock.Trail.Step(0.0, 70.0))), items.getValue(7).auto.trail)
        assertTrue(items.getValue(8).auto.profitLock)
        assertTrue("a new script starts with it on", PineScripts.Auto().profitLock)
        assertEquals(30.0, PineScripts.Auto().stopPts, 0.0); assertEquals(60.0, PineScripts.Auto().targetPts, 0.0)
        // Saved and read back: the trail survives; the lock is saved on whatever was written.
        val mine = PineScripts.Auto(profitLock = false, profitLockChosen = true,
            trail = com.optionslab.engine.orb.ProfitLock.Trail(6.0, listOf(com.optionslab.engine.orb.ProfitLock.Trail.Step(12.0, 60.0))))
        PineScripts.setAuto(id, mine)
        val saved = String(com.optionslab.app.security.Vault.readFileSteady(java.io.File(context.filesDir, "pine.vault"))!!, Charsets.UTF_8)
        assertEquals(mine.copy(profitLock = true), PineScripts.parse(saved).single { it.id == id }.auto)
    }

    // ---- 14 Oct (Boss): the signal is read on the index chart, then the option is bought -------------------------------

    @Test fun aScriptOnAnOptionMcxOrUnknownChartIsRefusedAndStaysOff() {
        for (bad in listOf(ce, "CRUDEOIL26NOVFUT", "GOLDM26DEC72000CE", "RELIANCE", "", "?!")) {
            auto { it.copy(symbol = bad) }
            val r = runBlocking { PineAuto.arm(id, true, pinConfirmed = true) }
            assertEquals(bad, "Pine scripts run on the index chart and then buy the option: ${bad.ifEmpty { "(no symbol)" }} is not an index", r)
            assertFalse(bad, PineScripts.get(id)!!.auto.on)
        }
        pass(51_900.0)
        pass(52_010.0)
        assertTrue("nothing ran, nothing was sent", kite.requests.none { it.method == "POST" })
        assertNull(held())
    }

    @Test fun anIndexWrittenAnotherWayIsArmedAsItsRootAndTrades() {
        auto { it.copy(symbol = "bank nifty") }
        switchOn()
        assertEquals("BANKNIFTY", PineScripts.get(id)!!.auto.symbol)
        assertTrue(PineScripts.get(id)!!.auto.on)
        pass(51_900.0)
        pass(52_010.0)
        assertNotNull(held())
        assertEquals("the index's signal bought the option", ce, side("BUY").single().form["tradingsymbol"])
    }

    @Test fun aScriptSwitchedOnSomeOtherWayOnANonIndexChartIsSwitchedOffBeforeItRuns() {
        // Not through arm (which refuses it): the saved settings say on, on an option's chart.
        PineScripts.setAuto(id, PineScripts.Auto(on = true, symbol = ce, interval = "5m", buy = "Buy", sell = "Sell", shortWith = "exit"))
        pass(51_900.0)
        pass(52_010.0)
        assertFalse(PineScripts.get(id)!!.auto.on)
        assertTrue(log(), log().contains("Switched off: Pine scripts run on the index chart and then buy the option: $ce is not an index."))
        assertTrue(kite.requests.none { it.method == "POST" })
    }

    @Test fun aSavedFileWithAnOptionOrMcxSymbolIsReadSwitchedOffAndToldOnce() {
        val f = java.io.File(context.filesDir, "pine.vault")
        fun script(id: Int, name: String, auto: org.json.JSONObject) =
            org.json.JSONObject().put("id", id).put("name", name).put("code", code).put("auto", auto.put("stopPts", 30.0).put("targetPts", 60.0))
        val text = org.json.JSONArray()
            .put(script(1, "On an option", org.json.JSONObject().put("on", true).put("symbol", ce)))
            .put(script(2, "Lower case", org.json.JSONObject().put("on", true).put("symbol", "nifty")))
            .put(script(3, "MCX, off", org.json.JSONObject().put("on", false).put("symbol", "CRUDEOIL26NOVFUT")))
            .put(script(4, "No symbol saved", org.json.JSONObject().put("on", true)))
            .put(script(5, "Blank", org.json.JSONObject().put("on", true).put("symbol", "")))
            .put(script(6, "Valid index", org.json.JSONObject().put("on", true).put("symbol", "FINNIFTY")))
            .toString()
        PineScripts.wipe()
        com.optionslab.app.security.Vault.writeFile(f, text.toByteArray(Charsets.UTF_8))
        // As after a restart: the scripts are read from the file again.
        PineScripts::class.java.getDeclaredField("loaded").apply { isAccessible = true }.set(null, false)
        val items = PineScripts.loadNow()!!.associateBy { it.id }
        assertFalse("an option's chart is never run", items.getValue(1).auto.on)
        assertEquals("its symbol is kept, to say which was refused", ce, items.getValue(1).auto.symbol)
        assertTrue(items.getValue(2).auto.on); assertEquals("NIFTY", items.getValue(2).auto.symbol)
        assertFalse(items.getValue(3).auto.on); assertEquals("CRUDEOIL26NOVFUT", items.getValue(3).auto.symbol)
        assertTrue("an older save without a symbol keeps its index", items.getValue(4).auto.on); assertEquals("BANKNIFTY", items.getValue(4).auto.symbol)
        assertFalse(items.getValue(5).auto.on)
        assertTrue("a valid index keeps working", items.getValue(6).auto.on); assertEquals("FINNIFTY", items.getValue(6).auto.symbol)
        runBlocking { PineAuto.load() }
        val lines = PineAuto.log.value.map { it.text }
        assertTrue(lines.toString(), lines.contains("Pine 'On an option': switched off - Pine scripts run on the index chart and then buy the option: $ce is not an index."))
        assertTrue(lines.toString(), lines.contains("Pine 'Blank': switched off - Pine scripts run on the index chart and then buy the option: (no symbol) is not an index."))
        assertTrue("an off one is not told", lines.none { it.startsWith("Pine 'MCX, off'") })
        assertTrue("no holding: simply off", lines.none { it.contains("managed to its exit") })
        assertTrue(PineAuto.held.value.isEmpty())
        // Written back: read again, nothing more is said and nothing changes.
        val again = PineScripts.parseNoting(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
        assertTrue(again.second.isEmpty()); assertFalse(again.third)
        assertEquals("NIFTY", again.first.single { it.id == 2L }.auto.symbol)
    }

    @Test fun aLiveHoldingOfAScriptRefusedOnLoadIsManagedToItsOwnExitNeverDumped() {
        bought()                                                   // 30 at 300 on Zerodha, its stop resting at 270
        // As after an update and a restart: the saved script names an option's chart.
        val f = java.io.File(context.filesDir, "pine.vault")
        val saved = org.json.JSONArray(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
        saved.getJSONObject(0).getJSONObject("auto").put("symbol", ce)
        PineScripts.wipe()
        com.optionslab.app.security.Vault.writeFile(f, saved.toString().toByteArray(Charsets.UTF_8))
        PineScripts::class.java.getDeclaredField("loaded").apply { isAccessible = true }.set(null, false)
        runBlocking { PineAuto.load() }
        assertFalse("refused: off", PineScripts.get(id)!!.auto.on)
        assertNotNull("still held", held())
        assertTrue(log(), log().contains("Its open option is managed to its exit (its stop, target and profit lock); nothing more is bought."))
        // The position watch looks at it from the first look; at 300 nothing is due.
        runBlocking { PineAuto.watchOnly() }
        assertNotNull(held())
        // A full pass never sells it for being switched off, and never reads a signal on the option's chart (the index's
        // signal turning SELL would have sold it before).
        pass(51_900.0)
        pass(52_010.0)
        pass(51_900.0)
        assertNotNull(held())
        assertTrue("no market sale", side("SELL").isEmpty())
        assertFalse(log(), log().contains("no longer auto-trading"))
        assertEquals("nothing more bought", 1, side("BUY").size)
        // Its own target still sells it, from the position watch.
        kite.quote("NFO:$ce", 361.0, 360.95, 361.05)
        runBlocking { PineAuto.watchOnly() }
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("at 361.00: target"))
    }

    @Test fun aHoldingOfAScriptAlreadySavedOffOnANonIndexChartIsNeverDumpedAfterARestart() {
        bought()                                                   // 30 at 300 on Zerodha, its stop resting at 270
        // The process was killed after the scripts' file was rewritten (off, on the option's chart) and before the
        // auto-trader saved anything: the file says off, nothing says "refused", and the auto-trader's book is read afresh.
        val f = java.io.File(context.filesDir, "pine.vault")
        val saved = org.json.JSONArray(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
        saved.getJSONObject(0).getJSONObject("auto").put("symbol", ce).put("on", false)
        PineScripts.wipe()
        com.optionslab.app.security.Vault.writeFile(f, saved.toString().toByteArray(Charsets.UTF_8))
        PineScripts::class.java.getDeclaredField("loaded").apply { isAccessible = true }.set(null, false)
        PineScripts.loadNow()
        assertTrue("nothing remembered as refused", PineScripts.takeRefused().isEmpty())
        AutomationSupport.reloadFromDisk(PineAuto)
        pass(52_010.0)
        pass(51_900.0)                                             // the index's signal turning would have sold it before
        assertNotNull("not sold for being off", held())
        assertTrue("no market sale", side("SELL").isEmpty())
        assertFalse(log(), log().contains("no longer auto-trading"))
        assertTrue(log(), log().contains("Its open option is managed to its exit"))
        assertEquals("nothing more bought", 1, side("BUY").size)
        // Its own target still sells it, from the position watch.
        kite.quote("NFO:$ce", 361.0, 360.95, 361.05)
        runBlocking { PineAuto.watchOnly() }
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("at 361.00: target"))
    }

    @Test fun readingTheScriptsOnceLoadedTakesNoLockSoAPassNeverWaitsBehindAVaultWrite() {
        assertNotNull(PineScripts.loadNow())
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // A save from the Pine screen holds the scripts' lock (PineScripts.put / setAuto are synchronized on it).
        val writer = Thread { synchronized(PineScripts) { holding.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) } }
        writer.start()
        assertTrue(holding.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        val list = PineScripts.loadNow()
        val ms = (System.nanoTime() - t0) / 1_000_000
        release.countDown(); writer.join(10_000)
        assertNotNull(list)
        assertTrue("loadNow waited $ms ms behind the lock", ms < 1_000)
    }

    @Test fun twoSpellingsOfOneIndexAreOneScriptAfterLoadAndOnlyTheFirstKeepsTrading() {
        val f = java.io.File(context.filesDir, "pine.vault")
        val other = "//@version=5\nindicator(\"Other\")\nplotshape(close > open, \"Buy\")\nplotshape(close < open, \"Sell\")"
        fun script(id: Int, name: String, code: String, symbol: String, interval: String = "5m") = org.json.JSONObject().put("id", id).put("name", name)
            .put("code", code).put("auto", org.json.JSONObject().put("on", true).put("symbol", symbol).put("interval", interval)
                .put("stopPts", 30.0).put("targetPts", 60.0))
        val text = org.json.JSONArray()
            .put(script(1, "Level, as NIFTY 50", code, "NIFTY 50"))
            .put(script(2, "Level, as NIFTY", "$code\n// the same, saved again", "NIFTY"))
            .put(script(3, "Another script on NIFTY", other, "NIFTY"))
            .put(script(4, "Level on 15m", code, "nifty", "15m"))
            .toString()
        PineScripts.wipe()
        com.optionslab.app.security.Vault.writeFile(f, text.toByteArray(Charsets.UTF_8))
        PineScripts::class.java.getDeclaredField("loaded").apply { isAccessible = true }.set(null, false)
        assertEquals(listOf("NIFTY", "NIFTY", "NIFTY", "NIFTY"), PineScripts.loadNow()!!.sortedBy { it.id }.map { it.auto.symbol })
        runBlocking { PineAuto.load() }
        val on = PineScripts.items.value.associate { it.id to it.auto.on }
        assertEquals(mapOf(1L to true, 2L to false, 3L to true, 4L to true), on)
        val lines = PineAuto.log.value.filter { it.script == 2L }.map { it.text }
        assertEquals(listOf(com.optionslab.ira.PineDupes.note(1)), lines)
        // Once: another load says nothing more and switches nothing else off.
        runBlocking { PineAuto.load() }
        assertEquals(1, PineAuto.log.value.count { it.script == 2L && it.text.startsWith("Switched off: the same strategy") })
        assertEquals(mapOf(1L to true, 2L to false, 3L to true, 4L to true), PineScripts.items.value.associate { it.id to it.auto.on })
    }

    // ---- 22 Nov 2026 (B6 review): lost sales and buys, the app's own cancels, the instruments list --------------------------------------

    /**
     * Item 2: the exit sale's answer is lost and the book does not show it through ~4 s of looks. Before, the stop went back at once beside
     * a sale that had filled (a resting SELL on a flat position). Now nothing more is sold and no stop rests beside it while it is looked
     * for; once the book shows the sale, it is booked - one sale, nothing left working.
     */
    @Test fun aLostExitSaleIsHeldWithNoStopBesideItAndBookedOnceTheBookShowsIt() {
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        bought()
        val sl = held()!!.stop!!
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)            // the sale's answer is lost; it fills at Zerodha
        kite.trailReads = 50                                       // and the book does not list it yet
        pass(51_900.0)                                             // SELL signal: the exit
        assertEquals("CANCELLED", kite.order(sl).status)
        assertEquals("one sale", 1, side("SELL").size)
        assertEquals("flat at Zerodha", 0, kite.positions.getValue("NFO:$ce:MIS").first)
        assertEquals("no stop put back beside the sale", 1, stops().size)
        assertNotNull("held while its sale is looked for", held())
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "exit of $ce was lost" in it })
        // The next passes: still looked for - nothing sold again, no stop.
        pass(51_890.0)
        pass(51_880.0)
        assertEquals(1, side("SELL").size); assertEquals(1, stops().size)
        assertNotNull(held())
        kite.showInBook()
        pass(51_870.0)
        assertNull("booked from its own sale", held())
        assertTrue(log(), log().contains("Sold 30 $ce at"))
        assertEquals(1, side("SELL").size); assertEquals(1, stops().size)
        assertTrue("nothing working on the flat contract", kite.orders.values.none { it.working })
    }

    /**
     * Item 2: a lost sale the book never shows (Zerodha in fact rejected it) is given up on only once a read that succeeded past the window
     * still does not show it - then the holding goes on as before: its stop back, the owed exit sent again (and it goes).
     */
    @Test fun aLostExitSaleNeverShownIsGivenUpOnlyPastTheWindowThenTheHoldingGoesOn() {
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        bought()
        kite.nextPlace(FakeKite.Scenario(reply = Reply.DROP_AFTER_ACCEPT, outcome = FakeKite.Outcome.REJECT))   // nothing sold
        kite.trailReads = 50
        pass(51_900.0)
        assertEquals(1, side("SELL").size); assertEquals(1, stops().size)
        pass(51_890.0)
        assertEquals("still looked for: nothing sent", 1, side("SELL").size); assertEquals(1, stops().size)
        assertEquals("still held at Zerodha", 30, kite.positions.getValue("NFO:$ce:MIS").first)
        Broker.lostSearchMsForTest = 0                             // the window is over; the next read succeeds without it
        pass(51_880.0)
        assertEquals("its stop went back once given up on", 2, stops().size)
        assertEquals("then the owed exit went again", 2, side("SELL").size)
        assertNull("sold", held())
        assertEquals(0, kite.positions.getValue("NFO:$ce:MIS").first)
        assertTrue("nothing left working", kite.orders.values.none { it.working })
    }

    /**
     * Item 2: the stop taken out for a sale that then finds nothing to sell (Boss's own sale covers what is held) is never left out for
     * good: no stop beside his sale now (never more resting than held), and once there is room again it rests on the next check.
     */
    @Test fun aStopTakenOutForASaleWithNothingToSellGoesBackNeverLeftWithNone() {
        bought()
        val sl = held()!!.stop!!
        val boss = kite.restingInKite(ce, "SELL", 30, 900.0, type = "LIMIT", tag = "", product = "MIS")
        pass(51_900.0)                                             // SELL signal: the exit finds nothing to sell
        assertEquals("CANCELLED", kite.order(sl).status)
        assertTrue("no sale sent", side("SELL").isEmpty())
        assertNotNull(held())
        assertNull(held()!!.stop)
        assertEquals("no stop beside Boss's sale", 1, stops().size)
        // Boss's own sale gone and the signal back to BUY: the stop rests again on the next check.
        kite.order(boss).status = "CANCELLED"
        pass(52_010.0)
        val h = held()!!
        assertNotNull("its stop is back", h.stop)
        assertEquals("TRIGGER PENDING", kite.order(h.stop!!).status)
        assertEquals(30, kite.order(h.stop!!).qty)
        assertEquals(270.0, kite.order(h.stop!!).trigger, 1e-9)
    }

    /**
     * Item 3: the buy's answer is lost and the book does not show it through ~4 s of looks. Before, that was "nothing bought" - a buy that
     * filled was left at Zerodha with no stop. Now it is held as unconfirmed and looked for; once the book shows it filled, it is tracked
     * with its stop at Zerodha.
     */
    @Test fun aLostBuyNotFoundAtOnceIsHeldAndTrackedWithItsStopOnceItFills() {
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        switchOn()
        pass(51_900.0)
        kite.nextPlace(FakeKite.Scenario(reply = Reply.DROP_AFTER_ACCEPT, outcome = FakeKite.Outcome.OPEN))
        kite.trailReads = 50
        pass(52_010.0)
        val h = held()!!
        assertTrue("held, unconfirmed", h.unconfirmed)
        assertNull(h.order)
        assertFalse(log(), log().contains("nothing bought"))
        pass(52_020.0)
        assertTrue("still looked for", held()!!.unconfirmed)
        assertEquals("nothing more bought meanwhile", 1, side("BUY").size)
        assertTrue("no stop yet", stops().isEmpty())
        // It fills late, and the book shows it.
        val buy = kite.orders.values.single { it.side == "BUY" }
        kite.fill(buy.id, 301.0)
        kite.showInBook()
        pass(52_030.0)
        val settled = held()!!
        assertFalse(settled.unconfirmed)
        assertEquals(30, settled.qty); assertEquals(301.0, settled.entry, 1e-9)
        assertEquals("one buy, never two", 1, side("BUY").size)
        assertEquals("its stop rests at Zerodha", 1, stops().size)
        assertEquals(kite.orders.values.single { it.type == "SL" }.id, settled.stop)
    }

    /** Item 3: one never found is let go only once the book, read past the window, still does not show it - never sooner. */
    @Test fun aLostBuyNeverFoundIsLetGoOnlyPastTheWindow() {
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        switchOn()
        pass(51_900.0)
        kite.nextPlace(FakeKite.Scenario(reply = Reply.DROP_AFTER_ACCEPT, outcome = FakeKite.Outcome.REJECT))   // nothing bought
        kite.trailReads = 50
        pass(52_010.0)
        assertTrue(held()!!.unconfirmed)
        pass(52_020.0)
        assertTrue("kept within the window", held()!!.unconfirmed)
        Broker.lostSearchMsForTest = 0
        pass(52_030.0)
        assertNull("let go", held())
        assertTrue(log(), log().contains("No buy of $ce reached Zerodha: nothing held"))
        assertEquals(1, side("BUY").size)
    }

    /**
     * Item 5: the instruments list is not in memory (a cold start): under the scripts' lock nothing is read from the file or downloaded -
     * nothing is sent, said, and the list is loaded in the background; the next change of signal buys.
     */
    @Test fun withTheInstrumentListNotInMemoryNothingIsSentAndItLoadsInTheBackground() {
        // A cold start with no list to be had yet: none in memory, no file, Zerodha's download failing for now.
        Broker.forgetInstrumentsMemoForTest()
        java.io.File(context.filesDir, "kite_instruments.json").delete()
        kite.down += "/instruments/NFO"
        switchOn()
        pass(51_900.0)
        pass(52_010.0)
        assertTrue("nothing sent", kite.placed.isEmpty())
        assertNull(held())
        assertTrue(log(), log().contains("instrument list is not loaded yet"))
        // Zerodha answers again: the list is loaded in the background (never under the scripts' lock); the next change buys.
        kite.down.clear(); Broker.resetInstrumentsRetryForTest()
        val end = System.currentTimeMillis() + 5_000
        while (Broker.instrumentsInMemory() == null && System.currentTimeMillis() < end) Thread.sleep(20)
        assertNotNull("loaded in the background", Broker.instrumentsInMemory())
        pass(51_900.0)
        pass(52_010.0)
        assertEquals("the next change buys", 1, side("BUY").size)
    }

    /**
     * Item 9: a stop the app took out itself for an exit, whose end Zerodha shows only later, is said as the app's own - never as gone from
     * Zerodha (Boss's or the exchange's) - and the exit then goes.
     */
    @Test fun aStopTheAppTookOutItselfIsSaidAsItsOwnNeverAsGoneFromZerodha() {
        bought()
        val sl = held()!!.stop!!
        kite.cancelLagReads = 1_000                                // its cancel is taken, but its end shows only later
        AutomationSupport.clearAlerts()
        pass(51_900.0)                                             // the exit takes the stop out; still working, nothing is sold beside it
        assertTrue(PineAuto.ownCancelForTest(sl))
        assertTrue("nothing sold beside a stop still working", side("SELL").isEmpty())
        kite.order(sl).status = "CANCELLED"                        // its end shows now
        pass(51_900.0)                                             // the next check reads it, then the owed exit goes
        assertTrue(log(), log().contains("taken out by the app for an exit"))
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "taken out by the app for an exit" in it })
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().none { "is no longer at Zerodha" in it })
        assertNull("sold", held())
        assertEquals(1, side("SELL").size)
        assertTrue("nothing left working", kite.orders.values.none { it.working })
    }

    // ---- 22 Nov 2026 (B6 review, second look) ----------------------------------------------------------------------------------------

    /**
     * The review's chain: a buy whose answer was lost (held unconfirmed), then its exit sale's answer lost too - the sale fills, Zerodha is
     * flat. Before, the next pass settled the buy (now shown) and rested its stop on the flat contract, beside the sale; the sale found then
     * closed the holding and left that SL SELL resting. Now no stop rests while the lost sale is looked for; found, the buy is settled and the
     * sale booked against it - one buy, one sale, no stop ever, nothing working.
     */
    @Test fun anUnconfirmedBuysLostExitSaleNeverGetsAStopBesideItAndIsBookedOnceFound() {
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        switchOn()
        pass(51_900.0)
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)            // the buy's answer lost; it fills at Zerodha
        kite.trailReads = 500                                      // and the book does not list it yet
        pass(52_010.0)
        assertTrue("held, unconfirmed", held()!!.unconfirmed)
        assertEquals(30, kite.positions.getValue("NFO:$ce:MIS").first)
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)            // the exit's answer lost too; it fills
        kite.trailReads = 500
        pass(51_900.0)                                             // SELL signal: the exit
        assertEquals("one sale", 1, side("SELL").size)
        val sale = kite.orders.values.single { it.side == "SELL" && it.type == "MARKET" }
        assertEquals("flat at Zerodha", 0, kite.positions.getValue("NFO:$ce:MIS").first)
        assertTrue(stops().isEmpty())
        // The buy shows in the book now; the sale still does not.
        kite.showInBook(); kite.hideFromBook(sale.id, 500)
        pass(51_890.0)
        assertFalse("its buy settled meanwhile (its unfilled rest would be cancelled)", held()!!.unconfirmed)
        assertTrue("stamped as bought when its buy was sent (never 0, shared by every settled holding)", held()!!.at > 0L)
        assertTrue("no stop beside the lost sale", stops().isEmpty())
        assertEquals("nothing sold again", 1, side("SELL").size)
        assertNotNull("still held while its sale is looked for", held())
        pass(51_880.0)
        assertTrue(stops().isEmpty()); assertEquals(1, side("SELL").size)
        // The sale shows: the buy is settled and the sale booked against it.
        kite.showInBook()
        pass(51_870.0)
        assertNull("booked from its own sale", held())
        assertTrue(log(), log().contains("Sold 30 $ce at"))
        assertEquals("one buy", 1, side("BUY").size)
        assertEquals("one sale", 1, side("SELL").size)
        assertTrue("no stop ever", stops().isEmpty())
        assertTrue("nothing working on the flat contract", kite.orders.values.none { it.working })
    }

    /**
     * The same chain with the sale shown first and the buy still not: the found sale is kept (held: no stop, nothing sold again) until the
     * buy is settled - never a stop rested for the buy's whole fill on the contract the sale emptied - then booked against it.
     */
    @Test fun aLostSaleFoundBeforeItsBuyIsSettledIsKeptUntilItIsThenBooked() {
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        switchOn()
        pass(51_900.0)
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        kite.trailReads = 500
        pass(52_010.0)
        assertTrue(held()!!.unconfirmed)
        val buy = kite.orders.values.single { it.side == "BUY" }
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        kite.trailReads = 500
        pass(51_900.0)
        assertEquals(1, side("SELL").size)
        // The sale shows in the book; the buy still does not.
        kite.showInBook(); kite.hideFromBook(buy.id, 500)
        pass(51_890.0)
        assertTrue("its buy not settled yet", held()!!.unconfirmed)
        assertTrue("no stop", stops().isEmpty())
        pass(51_880.0)
        assertTrue(stops().isEmpty()); assertEquals("nothing sold again", 1, side("SELL").size)
        assertNotNull(held())
        // The buy shows: settled, and the kept sale booked against it.
        kite.showInBook()
        pass(51_870.0)
        assertNull("booked from its own sale", held())
        assertTrue(log(), log().contains("Sold 30 $ce at"))
        assertEquals(1, side("BUY").size); assertEquals(1, side("SELL").size)
        assertTrue("no stop ever", stops().isEmpty())
        assertTrue("nothing working", kite.orders.values.none { it.working })
        assertTrue(Broker.lostKeys("pineexit:").isEmpty())
    }

    /**
     * A lost sale found while its holding still names its stop (the stop's own state could not be read when the exit took it out): once the
     * sale closes the holding, that stop and its own exits are taken out and confirmed gone at Zerodha ([PineAuto]'s takeOutExits).
     */
    @Test fun aLostSaleFoundWhileItsHoldingStillNamesAStopTakesThatStopOut() {
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        bought()
        val sl = held()!!.stop!!
        kite.down += "/orders/$sl"                                 // the stop's own state cannot be read (its cancel is taken)
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        kite.trailReads = 500
        pass(51_900.0)
        assertEquals("CANCELLED", kite.order(sl).status)
        assertEquals(1, side("SELL").size)
        assertEquals("the holding still names its stop", sl, held()!!.stop)
        kite.down.clear(); kite.showInBook()
        val before = kite.requests.size
        pass(51_890.0)
        assertNull("booked from its own sale", held())
        assertTrue(log(), log().contains("Sold 30 $ce at"))
        assertTrue("its stop looked at and confirmed gone",
            kite.requests.drop(before).any { it.path == "/orders/$sl" || it.path == "/orders/regular/$sl" })
        assertEquals(1, side("SELL").size); assertEquals(1, stops().size)
        assertTrue("nothing working", kite.orders.values.none { it.working })
    }

    /**
     * A restart in a lost sale's window: the sale is saved with the book, so the hold is taken back - nothing sold again and no stop put
     * back beside it - and once the book shows the sale, it is booked.
     */
    @Test fun aLostExitSalesHoldIsTakenBackAfterARestartInItsWindow() {
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        bought()
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        kite.trailReads = 500
        pass(51_900.0)
        assertEquals(1, side("SELL").size); assertEquals(1, stops().size)
        assertEquals(1, Broker.lostKeys("pineexit:").size)
        // The restart: every memory gone, the book read back from disk.
        Broker.forgetLostForTest(); PineAuto.forgetStaleForTest()
        assertTrue(Broker.lostKeys("pineexit:").isEmpty())
        AutomationSupport.reloadFromDisk(PineAuto)
        runBlocking { PineAuto.load() }
        assertEquals("its hold taken back", 1, Broker.lostKeys("pineexit:").size)
        pass(51_890.0)
        assertEquals("nothing sold again", 1, side("SELL").size)
        assertEquals("no stop put back beside it", 1, stops().size)
        assertNotNull(held())
        kite.showInBook()
        pass(51_880.0)
        assertNull("booked from its own sale", held())
        assertTrue(log(), log().contains("Sold 30 $ce at"))
        assertEquals(1, side("SELL").size); assertEquals(1, stops().size)
        assertTrue("nothing working", kite.orders.values.none { it.working })
        assertTrue("its record dropped once found", Broker.lostKeys("pineexit:").isEmpty())
        // And dropped from the saved book: a second restart takes nothing back.
        Broker.forgetLostForTest(); PineAuto.forgetStaleForTest()
        AutomationSupport.reloadFromDisk(PineAuto)
        runBlocking { PineAuto.load() }
        assertTrue(Broker.lostKeys("pineexit:").isEmpty())
    }

    /**
     * WHO CANCELLED A STOP: the stop the app took out itself for an exit that then did not go (the signal turned back first) is the app's to
     * put back - it rests again at Zerodha on the next check, never left with none.
     */
    @Test fun aStopTheAppTookOutForAnExitThatDidNotGoRestsAgain() {
        bought()
        val sl = held()!!.stop!!
        // An earlier sale of the scripts' on the contract, filled two hours before (a part-sale long booked): never taken for this exit's.
        kite.orders["old1"] = FakeKite.Order("old1", ce, "NFO", "SELL", 30, "MIS", "MARKET", 0.0, 0.0, "irapine", "COMPLETE", filled = 30,
            avg = 290.0, at = Market.now().toLocalDateTime().minusHours(2))
        kite.cancelLagReads = 1_000                                // its cancel taken; its end shows only later: nothing sold beside it
        pass(51_900.0)
        assertTrue(PineAuto.ownCancelForTest(sl))
        assertTrue(side("SELL").isEmpty())
        kite.order(sl).status = "CANCELLED"                        // its end shows now
        pass(52_010.0)                                             // and the signal is back to BUY: no exit owed
        assertTrue("nothing sold", side("SELL").isEmpty())
        val h = held()!!
        assertNotNull("its stop rests again", h.stop)
        assertTrue(h.stop != sl)
        assertEquals("TRIGGER PENDING", kite.order(h.stop!!).status)
        assertEquals(30, kite.order(h.stop!!).qty)
        assertEquals(2, stops().size)
        assertTrue(log(), log().contains("it rests again on the next check"))
    }

    /**
     * The same, with Zerodha's positions unreadable when the stop's end is read: the holding has no stop from then (the app watches its
     * level), and whether it rests again is decided on the next check's fresh reads - then it rests.
     */
    @Test fun anOwnCancelledStopsReRestWaitsForAFreshPositionsReadThenRests() {
        bought()
        val sl = held()!!.stop!!
        kite.cancelLagReads = 1_000
        pass(51_900.0)
        assertTrue(side("SELL").isEmpty())
        kite.order(sl).status = "CANCELLED"
        kite.down += "/portfolio/positions"
        pass(52_010.0)
        assertNull("no stop on the holding: the app watches its level", held()!!.stop)
        assertEquals("none rested again yet", 1, stops().size)
        kite.down.clear()
        pass(52_020.0)
        val h = held()!!
        assertNotNull("decided on fresh reads: it rests again", h.stop)
        assertEquals("TRIGGER PENDING", kite.order(h.stop!!).status)
        assertEquals(2, stops().size)
        assertTrue(side("SELL").isEmpty())
    }

    // ---- 22 Nov 2026 (B6 review, third look) ---------------------------------------------------------------------------------------

    /**
     * M1: the app's cancel of its stop never took (answered, never landed) and a fresh read shows the stop still working 15 s on: the mark
     * ends. Boss's own cancel of it in Kite later is his - never put back (WHO CANCELLED A STOP).
     */
    @Test fun anOwnCancelThatNeverTookEndsItsMarkSoBosssLaterCancelIsNeverPutBack() {
        bought()
        val sl = held()!!.stop!!
        kite.cancelsLost = 1                                       // answered as taken; it never lands
        pass(51_900.0)                                             // SELL: the exit takes the stop out - still working: nothing sold beside it
        assertTrue(PineAuto.ownCancelForTest(sl))
        assertTrue(side("SELL").isEmpty())
        assertEquals("TRIGGER PENDING", kite.order(sl).status)
        PineAuto.ageOwnCancelForTest(sl, PineAuto.OWN_CANCEL_RECHECK_MS + 1_000)
        pass(52_010.0)                                             // back to BUY; a fresh read shows it still working 15 s on
        assertFalse("its cancel never took: no longer the app's own", PineAuto.ownCancelForTest(sl))
        assertEquals(sl, held()!!.stop)
        // Later Boss cancels it himself in Kite.
        kite.order(sl).status = "CANCELLED"
        AutomationSupport.clearAlerts()
        pass(52_020.0)
        assertNull("the app watches the level", held()!!.stop)
        assertEquals("Boss's cancel is never put back", 1, stops().size)
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "is no longer at Zerodha" in it })
    }

    /** The time of the app's own cancel is kept with the book: after a restart a fresh one still puts its stop back (never left with none). */
    @Test fun aFreshOwnCancelStillPutsItsStopBackAfterARestart() {
        bought()
        val sl = held()!!.stop!!
        kite.cancelLagReads = 1_000
        pass(51_900.0)
        assertTrue(side("SELL").isEmpty())
        PineAuto.forgetStaleForTest()                              // the restart: the memory gone, the book read back
        AutomationSupport.reloadFromDisk(PineAuto)
        runBlocking { PineAuto.load() }
        assertTrue(PineAuto.ownCancelForTest(sl))
        kite.order(sl).status = "CANCELLED"
        pass(52_010.0)
        val h = held()!!
        assertNotNull("put back", h.stop)
        assertEquals(2, stops().size)
        assertEquals(30, kite.order(h.stop!!).qty)
    }

    /** M1: an own cancel older than its freshness window is never put back either, whatever the reads show. */
    @Test fun anOwnCancelPastItsFreshnessIsNeverPutBack() {
        bought()
        val sl = held()!!.stop!!
        kite.cancelLagReads = 1_000
        pass(51_900.0)
        assertTrue(side("SELL").isEmpty())
        PineAuto.ageOwnCancelForTest(sl, 11 * 60_000L)
        kite.order(sl).status = "CANCELLED"
        pass(52_010.0)
        assertNull(held()!!.stop)
        assertEquals("not put back", 1, stops().size)
    }

    /**
     * M2: Boss's 1-lot SELL LIMIT, placed before the app's cancel of the 2-lot stop, fills after it - Zerodha holds 1 lot. Taken by its fill
     * time as a sale since the cancel: the stop is not put back (before, an SL for 2 lots rested on 1 held).
     */
    @Test fun aSaleOfBosssPlacedBeforeTheCancelThatFillsAfterItKeepsTheStopFromBeingPutBack() {
        bought(lots = 2)
        val sl = held()!!.stop!!
        assertEquals(60, kite.order(sl).qty)
        val boss = kite.restingInKite(ce, "SELL", 30, 900.0, type = "LIMIT", tag = "", product = "MIS")
        kite.cancelLagReads = 1_000
        pass(51_900.0)                                             // the exit: stop taken out (shown later); Boss's sale covers the rest
        assertTrue(side("SELL").isEmpty())
        kite.fill(boss, 300.0)                                     // Boss's sale fills after the app's cancel
        kite.order(sl).status = "CANCELLED"
        pass(52_010.0)                                             // back to BUY: no exit owed
        assertEquals(30, kite.positions.getValue("NFO:$ce:MIS").first)
        assertNull(held()!!.stop)
        assertEquals("never an SL above what Zerodha holds", 1, stops().size)
    }

    /** M2: Zerodha holds less than the holding with no sale seen since the cancel: never re-rested blindly - the app watches, Boss is told. */
    @Test fun lessHeldAtZerodhaThanTheHoldingNeverPutsTheStopBack() {
        bought(lots = 2)
        val sl = held()!!.stop!!
        val boss = kite.restingInKite(ce, "SELL", 30, 900.0, type = "LIMIT", tag = "", product = "MIS")
        kite.cancelLagReads = 1_000
        pass(51_900.0)
        kite.fill(boss, 300.0)
        kite.order(boss).updated = Market.now().toLocalDateTime().minusHours(1)   // its fill time unreadable as "since the cancel"
        kite.order(sl).status = "CANCELLED"
        AutomationSupport.clearAlerts()
        pass(52_010.0)
        assertNull(held()!!.stop)
        assertEquals(1, stops().size)
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "Zerodha holds 30 of $ce, less than the 60" in it })
    }

    /** M2: re-rested beside another owner's working sale, the stop is sized to what Zerodha holds less that sale - never above it. */
    @Test fun aStopPutBackBesideAnotherWorkingSaleRestsOnlyForWhatItLeaves() {
        bought(lots = 2)
        val sl = held()!!.stop!!
        kite.restingInKite(ce, "SELL", 30, 900.0, type = "LIMIT", tag = "", product = "MIS")   // Boss's sale, still working
        kite.cancelLagReads = 1_000
        pass(51_900.0)
        kite.order(sl).status = "CANCELLED"
        pass(52_010.0)
        val h = held()!!
        assertNotNull("put back", h.stop)
        assertEquals("for what Boss's working sale leaves", 30, kite.order(h.stop!!).qty)
        assertEquals(2, stops().size)
    }

    /**
     * M3: an exit sale fills in part while the stop's own state could not be read at the exit: the unsold rest's stop rests again on the next
     * check (its own booked sale is never taken for a sale since the cancel), sized to what Zerodha holds.
     */
    @Test fun theUnsoldRestOfAPartlyFilledExitGetsItsStopBack() {
        bought(lots = 2)
        val sl = held()!!.stop!!
        kite.down += "/orders/$sl"                                 // the stop's state cannot be read at the exit (its cancel is taken)
        kite.nextPlace(FakeKite.Scenario(outcome = FakeKite.Outcome.PARTIAL_THEN_CANCEL, partialQty = 30))
        pass(51_900.0)
        assertEquals(1, side("SELL").size)
        assertEquals("CANCELLED", kite.order(sl).status)
        assertEquals("the unsold rest still held", 30, held()!!.qty)
        assertEquals(30, kite.positions.getValue("NFO:$ce:MIS").first)
        kite.down.clear()
        pass(52_010.0)                                             // back to BUY: no exit owed; the stop's end is read
        val h = held()!!
        assertNotNull("the rest's stop rests again", h.stop)
        assertEquals(30, kite.order(h.stop!!).qty)
        assertEquals(2, stops().size)
        assertEquals(1, side("SELL").size)
    }

    /** A lost sale given up on at or after 15:15 never rests a stop again then: the square-off sells what is held. */
    @Test fun aLostSaleGivenUpOnAfter1515RestsNoStopAndTheSquareOffSells() {
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        bought()
        kite.nextPlace(FakeKite.Scenario(reply = Reply.DROP_AFTER_ACCEPT, outcome = FakeKite.Outcome.REJECT))   // nothing sold
        kite.trailReads = 500
        pass(51_900.0)
        assertEquals(1, side("SELL").size); assertEquals(1, stops().size)
        Broker.lostSearchMsForTest = 0                             // the window is over
        val to1516 = java.time.Duration.between(now, now.toLocalDate().atTime(15, 16).atZone(now.zone)).toMinutes()
        pass(51_890.0, minutes = to1516)
        assertEquals("no stop rested again after 15:15", 1, stops().size)
        assertEquals("the square-off sold it", 2, side("SELL").size)
        assertNull(held())
        assertEquals(0, kite.positions.getValue("NFO:$ce:MIS").first)
        assertTrue("nothing working", kite.orders.values.none { it.working })
    }

    /** Item 9: the app's own cancel marks are saved with the book - after a restart a cancel the app made is still read as its own. */
    @Test fun theAppsOwnCancelMarksAreKeptAcrossARestart() {
        bought()
        val sl = held()!!.stop!!
        kite.cancelLagReads = 1_000                                // its cancel taken; nothing sold beside a stop still working
        pass(51_900.0)
        assertTrue(PineAuto.ownCancelForTest(sl))
        PineAuto.forgetStaleForTest()                              // the memory gone, as after a restart
        assertFalse(PineAuto.ownCancelForTest(sl))
        AutomationSupport.reloadFromDisk(PineAuto)
        runBlocking { PineAuto.load() }
        assertTrue("read back from the book", PineAuto.ownCancelForTest(sl))
    }

    // ---- 23 Nov 2026 (B6 follow-ups, item 2): one seller at a time on the contract's row ([SquareOffExit.claimExit]) --------------------

    /** The MARKET sales sent, by tag and quantity (the stops, SL, left out). */
    private fun sales() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }
        .map { it.form["tag"] to it.form["quantity"] }

    /**
     * While a square-off (the 15:05 expiry square-off, the flat-by) holds the row, Pine's exit waits with its stop resting at Zerodha -
     * nothing taken out, nothing sold - and says so once; once the row is free, the next pass takes the stop out and sells.
     */
    @Test fun whileASquareOffHoldsTheRowPinesExitWaitsWithItsStopResting() {
        bought()
        val stop = held()!!.stop!!
        val claim = SquareOffExit.claimExit("NFO", ce, "MIS", "iraalgoexpiry")!!
        try {
            pass(51_900.0)                                         // SELL signal; shortWith = exit: the exit is due
            assertTrue("nothing sold", sales().isEmpty())
            assertEquals("its stop rests", "TRIGGER PENDING", kite.order(stop).status)
            assertEquals(30, held()!!.qty)
            assertTrue(log(), log().contains("the expiry square-off is selling it"))
        } finally { claim.release() }
        pass(51_900.0)
        assertEquals("its stop out first", "CANCELLED", kite.order(stop).status)
        assertEquals(listOf("irapine" to "30"), sales())
        assertNull(held())
        assertNull("the row free again", SquareOffExit.exitClaimedBy("NFO", ce, "MIS"))
    }

    /**
     * A square-off that comes to the row while Pine's exit is taking its stop out is refused the row: it takes nothing out and sends nothing
     * (counted as a pass that left it unsold); Pine sells - exactly one sale, and the row free again.
     */
    @Test fun whilePinesExitHoldsTheRowASquareOffTakesNothingOutAndSendsNothing() {
        bought()
        val stop = held()!!.stop!!
        val row = Broker.Position(ce, "MIS", 30, 300.0, 300.0, 0.0)
        val refused = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val claimedBy = java.util.concurrent.atomic.AtomicReference<String?>()
        kite.afterNext("/orders/regular/$stop") {
            // Pine is inside its exit, its stop's cancel just answered: the square-off tries the row now.
            claimedBy.set(SquareOffExit.exitClaimedBy("NFO", ce, "MIS"))
            refused.set(!runBlocking {
                SquareOffExit.send(row, 30, 0.05, emptyList(), emptyList(), Broker.Positions(listOf(row), emptyList()), AppSettings.load().limits(),
                    "expiry square-off", "iraalgoexpiry", Origins.EXPIRY, "expires today", "expiry") { _, _ -> }
            })
        }
        pass(51_900.0)
        assertEquals("Pine held the row as its stop came out", PineAuto.CLAIM_OWNER, claimedBy.get())
        assertEquals("the square-off was refused the row", true, refused.get())
        assertEquals(1, SquareOffExit.skipsForTest("NFO", ce, "MIS"))
        assertEquals("CANCELLED", kite.order(stop).status)
        assertEquals("exactly one sale: Pine's", listOf("irapine" to "30"), sales())
        assertNull(held())
        assertNull("the row free again", SquareOffExit.exitClaimedBy("NFO", ce, "MIS"))
    }

    /** A square-off's sale on the contract a moment ago ([SquareOffExit.inGap]): Pine's exit waits with its stop resting; past it, it sells. */
    @Test fun aSquareOffsSaleAMomentAgoMakesPinesExitWait() {
        bought()
        val stop = held()!!.stop!!
        SquareOffExit.resetForTest()                               // the gap on, as it ships
        var clock = 1_000_000L
        SquareOffExit.clock = { clock }
        SquareOffExit.notePlaced("NFO", ce, "MIS")
        pass(51_900.0)
        assertTrue("nothing sold beside it", sales().isEmpty())
        assertEquals("its stop rests", "TRIGGER PENDING", kite.order(stop).status)
        assertNull(SquareOffExit.exitClaimedBy("NFO", ce, "MIS"))
        clock += SquareOffExit.GAP_MS
        pass(51_900.0)
        assertEquals(listOf("irapine" to "30"), sales())
        assertNull(held())
    }

    /**
     * Pine's exit cut short while it waits for the contract's exit turn (the exit coordinator on, another sender holding the turn; the pass
     * cancelled) - its claim on the row is let go all the same, so no square-off is shut out of the row by it.
     */
    @Test fun anExitCutShortLetsTheRowGo() {
        bought()
        val stop = held()!!.stop!!
        ExitCoordinator.resetForTest(on = true)
        val other = runBlocking { ExitCoordinator.enter("NFO", ce, "another sender") }
        now = now.plusMinutes(5); PineAuto.testNow = now
        closes = List(9) { 51_900.0 } + 51_900.0
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { PineAuto.tick() }
        try {
            val end = System.currentTimeMillis() + 3_000
            while (!(kite.order(stop).status == "CANCELLED" && SquareOffExit.exitClaimedBy("NFO", ce, "MIS") == PineAuto.CLAIM_OWNER) &&
                System.currentTimeMillis() < end) Thread.sleep(5)
            assertEquals("waiting for its turn, the row claimed", PineAuto.CLAIM_OWNER, SquareOffExit.exitClaimedBy("NFO", ce, "MIS"))
            runBlocking { job.cancelAndJoin() }
            assertNull("let go though the exit was cut short", SquareOffExit.exitClaimedBy("NFO", ce, "MIS"))
            assertTrue("nothing sold", sales().isEmpty())
        } finally { runBlocking { job.cancelAndJoin() }; other.leave(); ExitCoordinator.resetForTest() }
    }

    /**
     * A stop put back (the lots a part-fill of the stop left) waits while a square-off holds the row - never placed beside its sale - and
     * goes in on the next check once the row is free.
     */
    @Test fun aStopPutBackWaitsWhileASquareOffHoldsTheRow() {
        bought(lots = 2)
        val stop = held()!!.stop!!
        // Zerodha's stop sold 30 of the 60, then ended: the 30 left get a stop again on the next checks.
        kite.order(stop).apply { status = "COMPLETE"; filled = 30; avg = 270.0 }
        kite.position(ce, 30, 300.0, product = "MIS")
        pass(52_020.0)
        assertEquals(30, held()!!.qty)
        assertNull(held()!!.stop)
        val before = stops().size
        val claim = SquareOffExit.claimExit("NFO", ce, "MIS", "iraalgoexpiry")!!
        try {
            pass(52_030.0)
            assertEquals("no stop put back while a square-off holds the row", before, stops().size)
            assertNull(held()!!.stop)
        } finally { claim.release() }
        pass(52_040.0)
        assertEquals("put back once the row is free", before + 1, stops().size)
        assertEquals("30", stops().last().form["quantity"])
        assertEquals("TRIGGER PENDING", kite.order(held()!!.stop!!).status)
        assertNull(SquareOffExit.exitClaimedBy("NFO", ce, "MIS"))
    }

    /**
     * The first stop after a live buy also goes in with the row claimed: while a square-off holds the row none is placed beside its sale,
     * and the next check rests it on fresh reads.
     */
    @Test fun theFirstStopAfterABuyWaitsWhileASquareOffHoldsTheRow() {
        val claim = SquareOffExit.claimExit("NFO", ce, "MIS", "iraalgoflat")!!
        try {
            bought()
            assertEquals(1, side("BUY").size)
            assertTrue("no stop beside the square-off's sale", stops().isEmpty())
            assertNull(held()!!.stop)
        } finally { claim.release() }
        pass(52_020.0)
        assertEquals("rested on the next check", 1, stops().size)
        assertEquals("30", stops().single().form["quantity"])
        assertEquals("TRIGGER PENDING", kite.order(held()!!.stop!!).status)
        assertNull(SquareOffExit.exitClaimedBy("NFO", ce, "MIS"))
    }

    /**
     * A live exit sale whose answer was lost is counted for the square-offs on its row while it is looked for ([Broker.lostSales]): they take
     * no stop out beside it and sell only what it leaves. Past its window it no longer holds them back.
     */
    @Test fun aLostExitSaleIsCountedForTheSquareOffsWhileItIsLookedFor() {
        bought()
        kite.nextPlace(reply = Reply.NETWORK_EXCEPTION)
        pass(51_900.0)
        assertEquals(30, SquareOffExit.lostPending(ce, "MIS"))
        assertEquals("never another product's row", 0, SquareOffExit.lostPending(ce, "NRML"))
        Broker.ageLostForTest(Broker.lostKeys("pineexit:").single(), Broker.LOST_SEARCH_MS + 60_000L)
        assertEquals("past its window: not counted", 0, SquareOffExit.lostPending(ce, "MIS"))
    }

    /**
     * Round 2: a square-off's sale on the contract long before this buy (the row's sale stamp never expires in memory) does not make the
     * first stop wait for fresh reads - those can still miss the new buy's fill, and the stop would be left out with a "holds none" alert. It
     * rests at once from the buy, the positions trailing it.
     */
    @Test fun aSquareOffsOldSaleNeverHoldsTheFirstStopBack() {
        SquareOffExit.resetForTest()                               // the square-offs' gap as it ships
        SquareOffExit.clock = { 1_000L }                           // a square-off sold this contract long ago, in this process
        SquareOffExit.noteSold("NFO", ce, "MIS")
        SquareOffExit.clock = System::currentTimeMillis
        kite.positionsTrailReads = 1_000                           // Zerodha's positions trail the buy's fill
        bought()
        assertEquals("its stop rests at once", 1, stops().size)
        assertEquals("30", stops().single().form["quantity"])
        assertNotNull(held()!!.stop)
        assertTrue(AutomationSupport.alerts().none { "already cover what Zerodha holds" in it })
    }

    /**
     * Round 2: a square-off's sale on the contract around this buy (within its gap before it, or after) does: the first stop is sized on
     * fresh reads, never above what Zerodha holds - none while the positions trail the buy, then the next check rests it.
     */
    @Test fun aSquareOffsSaleAroundTheBuySizesTheFirstStopOnFreshReads() {
        SquareOffExit.resetForTest()                               // the square-offs' gap as it ships
        SquareOffExit.noteSold("NFO", ce, "MIS")                   // just before the buy (by the same clock)
        kite.positionsTrailReads = 1_000
        bought()
        assertTrue("none above what Zerodha shows held", stops().isEmpty())
        assertNull(held()!!.stop)
        kite.positionsCatchUp()
        pass(52_020.0)
        assertEquals("rested on the next check", 1, stops().size)
        assertEquals("30", stops().single().form["quantity"])
    }

    /**
     * B6 review, ninth round, MAJOR 3: Boss's own exit on the holding's row whose reply was lost may be selling it. Pine's exit sells nothing
     * beside it, and the stop it took out is not put back beside it on the next checks (the app watches the level meanwhile); once his exit is
     * known not to have gone, Pine's owed exit goes - once.
     */
    @Test fun noStopIsPutBackNorSoldBesideBossesLostExit() {
        bought()                                                   // 30 at 300, its stop resting at 270
        val stop = held()!!.stop!!
        Broker.lostSearchInlineForTest = true; Broker.lostLookGapMsForTest = 0L
        val key = SquareOffExit.bossLostKey("NFO", ce, "MIS")
        Broker.noteLost(key, Kite.Order(ce, Kite.Side.SELL, 30, 30, "MIS", "MARKET", null), emptyList(), "Your exit")
        try {
            pass(51_900.0)                                         // SELL signal: the stop comes out, nothing is sold beside his exit
            assertEquals("CANCELLED", kite.order(stop).status)
            assertTrue("nothing sold beside his exit", side("SELL").isEmpty())
            pass(51_900.0)
            pass(51_900.0)
            assertEquals("no stop put back beside it", 1, stops().size)
            assertTrue(side("SELL").isEmpty())
            Broker.ageLostForTest(key, Broker.LOST_SEARCH_MS + 1_000)
            pass(51_900.0)
            assertEquals("the owed exit goes once his is known not to have gone", 1, side("SELL").size)
            assertNull(held())
        } finally { Broker.lostSearchInlineForTest = false; Broker.lostLookGapMsForTest = null; Broker.forgetLost(key) }
    }
}
