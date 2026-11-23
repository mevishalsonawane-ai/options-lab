package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.AutomationSupport.NIFTY_CE
import com.optionslab.app.testing.AutomationSupport.NIFTY_PE
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Outcome
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.strategy.OptionType
import com.optionslab.engine.strategy.Position
import com.optionslab.engine.strategy.RunMode
import com.optionslab.engine.strategy.RunStatus
import kotlinx.coroutines.Dispatchers
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

/**
 * Saved strategies ([Strategies]) on the money path against the fake Kite: a live start sends exactly its
 * entries; automatic exits send only what Zerodha says is held less what is already on its way out; entries
 * are refused in Paper mode and on a compromised phone while exits still go; and a pass cut short by
 * cancellation still writes down the orders it placed. Plus the book-keeping rules (editing, arming, import).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class StrategiesLiveTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private var checkNetwork = true

    @Before fun up() {
        kite = FakeKite()
        AutomationSupport.niftyOptions(kite)
        kite.quote("NFO:$NIFTY_CE", 100.0, 99.95, 100.05)
        kite.quote("NFO:$NIFTY_PE", 80.0, 79.95, 80.05)
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
    }

    @After fun down() {
        Market.testClock = null
        kite.close()
        if (checkNetwork) assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun live() { kite.login(); AutomationSupport.liveSettings() }

    /**
     * 18 Oct (CI ran at 15:29 IST): the test basket is intraday with a 15:15 exit, so after 15:15 the stop check's pass exited
     * the leg by its time ("nothing sold above the stop" failed). Today at 11:00 IST, the clock still running (an offset, not
     * a fixed clock: the waits inside still see time pass).
     */
    private fun midSession() {
        // 25 Oct: 11:00 on the MARKET's day (the test rule's trading day), not the wall clock's date (a Sunday in CI).
        val sys = java.time.Clock.system(com.optionslab.engine.IST)
        val at = Market.today().atTime(11, 0).atZone(com.optionslab.engine.IST)
        Market.testClock = java.time.Clock.offset(sys, java.time.Duration.between(java.time.ZonedDateTime.now(sys), at))
    }

    /** Save [def] live-enabled and return its id. */
    private fun saved(vararg legs: Pair<Position, OptionType>, lots: Int = 1, sl: Double? = 30.0,
                      product: com.optionslab.engine.strategy.Product = com.optionslab.engine.strategy.Product.NRML): Long = runBlocking {
        assertNull(Strategies.save(AutomationSupport.strategy("Test basket", *legs, lots = lots, sl = sl, product = product)))
        val id = Strategies.all().single { it.def.name == "Test basket" }.def.id
        Strategies.setLiveEnabled(id, true)
        id
    }

    private fun startLive(id: Long, compromised: Boolean = false) =
        runBlocking { Strategies.start(id, RunMode.LIVE, "manual", confirmedByOwner = true, compromised = compromised) }

    private fun entry(id: Long) = runBlocking { Strategies.all().single { it.def.id == id } }
    /** The exits sent (18 Oct: a leg's stop resting at Zerodha, an SL order, is not an exit sent: [restingStops]). */
    private fun sells() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] != "SL" }
    private fun restingStops() = kite.placed.filter { it.form["order_type"] == "SL" }
    /** 18 Oct (review C7): an NRML leg's stop rests as a single-leg GTT (it outlives the session), never a DAY SL order. */
    private fun gttTrigger(g: org.json.JSONObject) = g.getJSONObject("condition").getJSONArray("trigger_values").getDouble(0)
    private fun gttQty(g: org.json.JSONObject) = g.getJSONArray("orders").getJSONObject(0).getInt("quantity")
    private fun buys() = kite.placed.filter { it.form["transaction_type"] == "BUY" }

    // ---- live start --------------------------------------------------------------------------

    @Test fun aLiveStartSendsExactlyItsEntry() {
        live()
        val id = saved(Position.B to OptionType.CE)
        val msg = startLive(id)
        assertEquals("Test basket started (live): 1 of 1 legs open.", msg)
        val want = mapOf("tradingsymbol" to NIFTY_CE, "exchange" to "NFO", "transaction_type" to "BUY", "order_type" to "MARKET",
            "quantity" to "75", "product" to "NRML", "validity" to "DAY", "tag" to "iraalgostrat")
        assertEquals(want, kite.placed.single().form.filterKeys { it in want })
        val e = entry(id)
        assertTrue(e.running)
        assertEquals(RunStatus.ACTIVE, e.run!!.status)
        assertEquals("Test basket · leg 1 entry", runBlocking { Strategies.owners() }["kite:${kite.orders.keys.single()}"])
    }

    @Test fun aLiveStartNeedsTheOwnersConfirmationAndLiveEnabled() {
        live()
        val id = saved(Position.B to OptionType.CE)
        assertEquals("A live start needs your confirmation in the app.",
            runBlocking { Strategies.start(id, RunMode.LIVE, "scheduler", confirmedByOwner = false, compromised = false) })
        runBlocking { Strategies.setLiveEnabled(id, false) }
        assertEquals("Enable live trading for Test basket first.", startLive(id))
        assertTrue(kite.requests.isEmpty())
        assertEquals("Test basket is already running.", run { runBlocking { Strategies.setLiveEnabled(id, true) }; startLive(id); startLive(id) })
        assertEquals("one entry, not two", 1, kite.placed.size)
    }

    @Test fun aWingIsBoughtBeforeTheShortItCoversIsSold() {
        live()
        val id = saved(Position.S to OptionType.PE, Position.B to OptionType.CE)
        startLive(id)
        // The wing goes first; the short itself may then be refused by the account guard (a naked short), never sent first.
        assertEquals("BUY", kite.placed.first().form["transaction_type"])
        assertEquals(NIFTY_CE, kite.placed.first().form["tradingsymbol"])
    }

    @Test fun aRejectedWingKeepsTheShortUnsent() {
        live()
        kite.nextPlace(outcome = Outcome.REJECT, message = "RMS:Margin Exceeds")
        val id = saved(Position.S to OptionType.PE, Position.B to OptionType.CE)
        startLive(id)
        assertEquals("the unhedged SELL is never sent", listOf("BUY"), kite.placed.map { it.form["transaction_type"] })
    }

    // ---- entries refused, exits still go -------------------------------------------------------

    @Test fun entriesAreRefusedInPaperModeAndNothingIsSent() {
        kite.login(live = false)                    // a Zerodha session, but the app shows Paper
        SecurePrefs.put("g.cutoff", -1)
        checkNetwork = false                         // the paper index quote tries the public feed; the guard blocks it
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        assertTrue("no order in Paper mode", kite.placed.isEmpty())
        val log = runBlocking { Strategies.log() }.joinToString("\n") { it.message }
        assertTrue(log, log.contains("Paper mode"))
        assertFalse(entry(id).run!!.openLegs().any())
    }

    @Test fun entriesAreRefusedOnACompromisedPhone() {
        live()
        val id = saved(Position.B to OptionType.CE)
        startLive(id, compromised = true)
        assertTrue(kite.placed.isEmpty())
        val log = runBlocking { Strategies.log() }.joinToString("\n") { it.message }
        assertTrue(log, log.contains("signs of compromise"))
        // 22 Nov 2026 (B6 review): the same refusal when the check has not finished yet (unknown) - said honestly, not only "compromise".
        assertTrue(log, log.contains("or its security check has not finished yet"))
    }

    @Test fun exitsStillGoInPaperModeAndOnACompromisedPhone() {
        live()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        SecurePrefs.put("k.mode", "sandbox"); SecurePrefs.put("k.allow", false)   // switched to Paper while it runs
        checkNetwork = false
        runBlocking { Strategies.stop(id, "manual", compromised = true) }
        assertEquals("the live run's position is still closed at Zerodha", 1, sells().size)
        assertEquals("75", sells().single().form["quantity"])
        assertFalse(entry(id).running)
    }

    /**
     * 20 Oct, review: a live MARKET entry whose quote could not be read was judged by the account guard at price 0, so the
     * order-value and exposure limits saw a zero-value order and let it through. An unknown price now refuses the entry.
     */
    @Test fun aLiveEntryWithNoQuoteIsRefusedNotPricedAtZero() {
        live()
        val id = saved(Position.B to OptionType.CE)
        kite.quotes.remove("NFO:$NIFTY_CE")
        startLive(id)
        assertTrue("nothing bought blind", buys().isEmpty())
        val log = runBlocking { Strategies.log() }.joinToString("\n") { it.message }
        assertTrue(log, log.contains("Order price is unknown"))
    }

    // ---- exits: only what is held, less what is already going out -------------------------------

    @Test fun anExitSendsOnlyWhatZerodhaHoldsLessWorkingExits() {
        live()
        val id = saved(Position.B to OptionType.CE, lots = 2)
        startLive(id)
        assertEquals("150", buys().single().form["quantity"])
        // The owner put a limit SELL of one lot on it by hand (resting above the market).
        val mine = runBlocking { Broker.placeOrder(Kite.Order(NIFTY_CE, Kite.Side.SELL, 75, 75, "NRML", "LIMIT", 150.0, 0.05, "NFO", "manual"), exit = true) }
        runBlocking { Strategies.stop(id, "manual", compromised = false) }
        val exit = sells().last()
        assertEquals("150 held, 75 already working: one lot sent, not two", "75", exit.form["quantity"])
        assertEquals("the owner's order is left alone", "OPEN", kite.order(mine).status)
        // (20 Nov 2026: the leg's own stop now rests from the start, and the exit takes it out; the owner's order is never cancelled.)
        assertTrue(kite.requests.none { it.method == "DELETE" && it.path.endsWith("/$mine") })
    }

    @Test fun anExitIsNotSentForALegClosedByHand() {
        live()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        kite.position(NIFTY_CE, 0, 0.0)             // closed in the Kite app
        runBlocking { Strategies.stop(id, "manual", compromised = false) }
        assertTrue("a SELL now would open a short", sells().isEmpty())
        val log = runBlocking { Strategies.log() }.joinToString("\n") { it.message }
        assertTrue(log, log.contains("would not close it; not sent"))
    }

    @Test fun anExitCoveredByWorkingOrdersSendsNothingMore() {
        live()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        runBlocking { Broker.placeOrder(Kite.Order(NIFTY_CE, Kite.Side.SELL, 75, 75, "NRML", "LIMIT", 150.0, 0.05, "NFO", "iraprotect"), exit = true) }
        val before = kite.placed.size
        runBlocking { Strategies.stop(id, "manual", compromised = false) }
        assertEquals("nothing more sent", before, kite.placed.size)
        val log = runBlocking { Strategies.log() }.joinToString("\n") { it.message }
        assertTrue(log, log.contains("already on its way out; nothing more sent"))
    }

    /**
     * 20 Oct, review: a protection's resting exit fills between the exit's two reads. Positions were read first (still held),
     * then the orders (that exit no longer working), so a second SELL went out and left a short. Orders are now read first.
     */
    @Test fun aRestingExitFillingBetweenTheReadsIsNotDoubled() {
        live()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        val protect = runBlocking { Broker.placeOrder(Kite.Order(NIFTY_CE, Kite.Side.SELL, 75, 75, "NRML", "LIMIT", 150.0, 0.05, "NFO", "iraprotect"), exit = true) }
        val before = kite.placed.size
        kite.afterNext("/portfolio/positions") { kite.fill(protect) }
        runBlocking { Strategies.stop(id, "manual", compromised = false) }
        assertEquals("nothing more sent", before, kite.placed.size)
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$NIFTY_CE:NRML").first)
    }

    /**
     * 20 Nov 2026 (audit B5 deep review): an entry whose reply was lost and that the book does not show yet is UNKNOWN, never reported
     * refused (a run could send it again or leave it untracked): looked for on the next passes, and once the book shows it, it is the
     * leg's entry - one buy, never two.
     */
    @Test fun aLostEntryReplyIsLookedForAndNeverReportedRefused() {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE, sl = null)
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        kite.trailReads = 50
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        startLive(id)
        assertEquals(1, buys().size)
        assertTrue("never refused", entry(id).run!!.legs.values.none { it.entryStatus == "rejected" })
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals(1, buys().size)
        assertTrue(entry(id).run!!.legs.values.none { it.entryStatus == "rejected" })
        kite.showInBook()
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals("one buy, never two", 1, buys().size)
        assertEquals("complete", entry(id).run!!.legs.values.single().entryStatus)
    }

    /**
     * 22 Nov 2026 (B6 review, item 1): Kite answers DataException for the entry (it took the order) and the book does not show it through
     * the door's ~4 s of looks (23 Nov 2026: no second search of the strategy's own after it). Before, the error was taken as Zerodha's
     * refusal ("rejected"): a buy that filled stayed untracked with no stop. Now it is looked for as a lost reply; once the book shows it
     * filled it is the leg's entry - one buy - and the leg's stop rests.
     */
    @Test fun anUnsureKiteErrorOnTheEntryIsLookedForAndTheFilledLegGetsItsStop() {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE, product = com.optionslab.engine.strategy.Product.MIS)
        kite.nextPlace(reply = FakeKite.Reply.DATA_EXCEPTION_AFTER_ACCEPT)
        kite.trailReads = 50
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        startLive(id)
        assertEquals(1, buys().size)
        assertTrue("never refused", entry(id).run!!.legs.values.none { it.entryStatus == "rejected" })
        kite.showInBook()
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals("one buy, never two", 1, buys().size)
        assertEquals("complete", entry(id).run!!.legs.values.single().entryStatus)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("the leg's stop rests at Zerodha", 1, restingStops().size)
    }

    @Test fun aStopLossOnTheTickSellsTheLegOnce() {
        live()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)           // 30-point stop from 100
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals(1, sells().size)
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals("the next pass sends no second exit", 1, sells().size)
    }

    /** 18 Oct, review: the 15-second stop check's part ([Strategies.manageOnly]) sells a leg through its stop, once. */
    @Test fun theStopChecksOwnPassSellsTheLegThroughItsStopOnce() {
        // 18 Oct (CI after 15:15 IST): on the real clock the basket's own 15:15 exit time sold the leg on the first pass
        // ("nothing sold above the stop" failed on any run after 15:15). The clock reads 10:00 IST today, and moves.
        val ten = Market.today().atTime(10, 0).atZone(com.optionslab.engine.IST).toInstant()
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), ten))
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        assertTrue(Strategies.runningHint)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("nothing sold above the stop", sells().isEmpty())
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(1, sells().size)
        runBlocking { Strategies.manageOnly(compromised = false) }
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals("no second exit from either pass", 1, sells().size)
    }

    /** A compromised phone: the stop check's exits still go (only entries are refused). */
    @Test fun theStopChecksOwnPassSellsEvenOnACompromisedPhone() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)
        runBlocking { Strategies.manageOnly(compromised = true) }
        assertEquals(1, sells().size)
    }

    /**
     * 22 Nov 2026 (B6 review): the 15-second stop check's strategies step ([com.optionslab.app.work.StopLane.strategiesLook]) with the
     * device check's verdict unknown - none kept, its scan slow (a restart's first seconds): a leg through its stop is still sold; only
     * entries wait for the verdict.
     */
    @Test fun theStopChecksStrategiesStepSellsALegWhileTheDeviceVerdictIsUnknown() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        val integrity = com.optionslab.app.security.Integrity
        integrity.resetForTest()
        integrity.testScanDelayMs = 10_000
        try {
            assertNull("no verdict yet", integrity.cachedCompromised(context))
            kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)
            val t0 = System.nanoTime()
            runBlocking { com.optionslab.app.work.StopLane.strategiesLook(context) }
            assertTrue("never waited on the 10 s scan", (System.nanoTime() - t0) / 1_000_000 < 8_000)
            assertEquals("the leg's exit went", 1, sells().size)
            assertNull("still unknown", integrity.cachedCompromised(context, ask = false))
        } finally { integrity.resetForTest() }
    }

    @Test fun theStopChecksOwnPassReadsNothingWithNoRunOpen() {
        live()
        saved(Position.B to OptionType.CE)
        assertFalse(Strategies.runningHint)
        kite.requests.clear()
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("no read at all: ${kite.requests}", kite.requests.isEmpty())
    }

    @Test fun closingOneLegSendsOnlyThatLeg() {
        live()
        val id = saved(Position.B to OptionType.CE, Position.B to OptionType.PE)
        startLive(id)
        runBlocking { Strategies.closeLeg(id, 2, compromised = false) }
        assertEquals(NIFTY_PE, sells().single().form["tradingsymbol"])
        assertTrue(entry(id).running)
    }

    @Test fun aPassCutShortStillWritesDownTheExitItSent() {
        live()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)
        kite.nextPlace(FakeKite.Scenario(outcome = Outcome.FILL_AFTER_POLLS, polls = 4))   // the exit takes a few seconds to fill
        runBlocking {
            val pass = launch(Dispatchers.IO) { Strategies.tickAll(compromised = false) }
            val end = System.currentTimeMillis() + 15_000
            while (sells().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
            pass.cancel()                                          // the watch stopped mid-pass
            pass.join()
        }
        assertEquals(1, sells().size)
        AutomationSupport.reloadFromDisk(Strategies)               // as after the process died
        val run = entry(id).run!!
        assertTrue("the exit order is on file: ${run.status}", run.legs.values.single().exitOrderId != null || run.stoppedAt != null)
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals("the exit is not sent twice after a restart", 1, sells().size)
    }

    // ---- 18 Oct: the leg's stop rests at Zerodha ------------------------------------------------------------------

    /** A live run's leg rests its stop at Zerodha as a real SL order (its run's own stop), so a dead phone cannot miss it. */
    @Test fun aLiveLegsStopRestsAtZerodhaAsAnSlOrder() {
        // An intraday (MIS) leg: a real SL order with its limit.
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE, product = com.optionslab.engine.strategy.Product.MIS)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val sl = restingStops().single()
        assertEquals(mapOf("transaction_type" to "SELL", "order_type" to "SL", "trigger_price" to "70.00", "price" to "66.50", "quantity" to "75",
            "tag" to "iraalgostrat", "product" to "MIS"), sl.form.filterKeys { it in setOf("transaction_type", "order_type", "trigger_price", "price", "quantity", "tag", "product") })
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("one stop, not two", 1, restingStops().size)
        assertEquals("TRIGGER PENDING", kite.order(kite.orders.keys.last()).status)
        assertTrue("no GTT beside it", kite.gtts.isEmpty())
    }

    /**
     * 20 Nov 2026 (audit B5 deep review): a leg's stop whose reply is lost and that the book does not show yet is UNKNOWN - looked for on
     * the next looks, never a second SL beside it; once the book shows it, it is the leg's stop.
     */
    @Test fun aLegsStopWhoseReplyIsLostIsLookedForAndNeverRestedTwice() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE, product = com.optionslab.engine.strategy.Product.MIS)
        startLive(id)
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        kite.trailReads = 50
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(1, restingStops().size)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("no second stop while its lost reply is looked for", 1, restingStops().size)
        kite.showInBook()
        runBlocking { Strategies.manageOnly(compromised = false) }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("one stop, never two", 1, restingStops().size)
        val sl = kite.orders.values.single { it.type == "SL" }
        assertEquals("adopted as the leg's stop", "Test basket · leg 1 stop", runBlocking { Strategies.owners() }["kite:${sl.id}"])
    }

    /**
     * 20 Nov 2026 (deep review, confirmed): with Zerodha's order book unreadable past the two minutes, a lost entry is never given up and
     * read as rejected (it may have filled): it stays UNKNOWN, nothing is sent again, and Boss is told its state is unknown.
     */
    @Test fun aLostEntryWithTheBookUnreadablePastTheWindowStaysUnknown() {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE, sl = null)
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        kite.trailReads = 50
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        // (The book unreadable from the send on: no read ever succeeds, before the window or after it.)
        kite.down += "/orders"
        startLive(id)
        Broker.lostSearchMsForTest = 0
        runBlocking { Strategies.tickAll(compromised = false) }
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals("never sent again", 1, buys().size)
        assertTrue("never read as rejected", entry(id).run!!.legs.values.none { it.entryStatus == "rejected" })
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "UNKNOWN" in it })
    }

    /**
     * 20 Nov 2026 (audit B5 deep review): an entry whose reply was lost before a restart can no longer be looked for: it stays UNKNOWN
     * (never refused, never sent again) and Boss is told once to check the order book.
     */
    @Test fun aLostEntryFromBeforeARestartStaysUnknownAndBossIsTold() {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE, sl = null)
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        kite.trailReads = 50
        startLive(id)
        Broker.forgetLostForTest()                                   // as after a restart: nothing left in memory to look for
        runBlocking { Strategies.tickAll(compromised = false) }
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals("never sent again", 1, buys().size)
        assertTrue(entry(id).run!!.legs.values.none { it.entryStatus == "rejected" })
        assertEquals("told once", 1, AutomationSupport.alerts().count { "cannot be looked for any more" in it })
    }

    @Test fun aCarriedLegsStopRestsAtZerodhaAsOneGttNeverADaySlOrder() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val g = kite.gtts.values.single()
        assertEquals(70.0, gttTrigger(g), 1e-9)
        assertEquals(75, gttQty(g))
        assertTrue("no DAY SL order", restingStops().isEmpty())
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("one stop, not two", 1, kite.gtts.size)
    }

    /** The run's own exit takes the resting stop out first (confirmed), then sells: the two never both fill. */
    @Test fun aLegsExitCancelsItsZerodhaStopFirstSoTheyNeverBothFill() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.requests.clear()
        runBlocking { Strategies.stop(id, "manual", compromised = false) }
        val cancel = kite.requests.indexOfFirst { it.method == "DELETE" && it.path == "/gtt/triggers/$gid" }
        val sell = kite.requests.indexOfFirst { it.method == "POST" && it.path == "/orders/regular" }
        assertTrue("the stop out before the sell", cancel in 0 until sell)
        assertTrue(kite.gtts.isEmpty())
        assertEquals(1, sells().size)
        assertEquals("flat, never short", 0, kite.positions["NFO:$NIFTY_CE:NRML"]?.first ?: 0)
    }

    @Test fun aStopFilledAtZerodhaClosesTheLegWithNoSecondSell() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 66.95, 67.05)
        val oid = kite.triggerGtt(gid)                                 // its LIMIT at 66.50 is under the bid: it fills
        assertEquals("COMPLETE", kite.order(oid).status)
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)             // and back above the stop
        runBlocking { Strategies.manageOnly(compromised = false) }
        runBlocking { Strategies.tickAll(compromised = false) }
        assertTrue("no sell beside the stop", sells().none { it.form["tag"] != "gtt" })
        assertEquals("flat, never short", 0, kite.positions["NFO:$NIFTY_CE:NRML"]?.first ?: 0)
        assertFalse("the leg is closed", entry(id).run!!.openLegs().any())
    }

    @Test fun anIntradayStopFilledAtZerodhaClosesTheLegWithNoSecondSell() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE, product = com.optionslab.engine.strategy.Product.MIS)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val sid = kite.orders.values.single { it.type == "SL" }.id
        kite.trigger(sid, 70.0)
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        runBlocking { Strategies.tickAll(compromised = false) }
        assertTrue("no sell beside the stop", sells().isEmpty())
        assertEquals("flat, never short", 0, kite.positions["NFO:$NIFTY_CE:MIS"]?.first ?: 0)
        assertFalse("the leg is closed", entry(id).run!!.openLegs().any())
    }

    @Test fun aStopIsNeverLeftRestingOnALegClosedByHand() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(1, kite.gtts.size)
        kite.position(NIFTY_CE, 0, 0.0)
        // 20 Nov 2026 (audit): one read showing it closed takes nothing out; the look's own second read, which agrees, does.
        val before = kite.positionReads
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("two separate reads", kite.positionReads - before >= 2)
        assertTrue("its GTT deleted", kite.gtts.isEmpty())
        assertTrue(sells().isEmpty())
    }

    /** The market's clock moved on by [ms] (still ticking). */
    private fun later(ms: Long) {
        val base = Market.testClock ?: java.time.Clock.system(com.optionslab.engine.IST)
        Market.testClock = java.time.Clock.offset(base, java.time.Duration.ofMillis(ms))
    }

    /** 20 Nov 2026 (audit): one read showing the leg closed, the look's second read showing it held: its stop stays. */
    @Test fun aSingleZeroReadNeverTakesALegsStopOut() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(1, kite.gtts.size)
        repeat(2) {
            kite.position(NIFTY_CE, 0, 0.0)
            kite.afterNext("/portfolio/positions") { kite.position(NIFTY_CE, 75, 100.0) }
            runBlocking { Strategies.manageOnly(compromised = false) }
            later(Strategies.FLAT_AGREE_MS + 1_000)
        }
        assertEquals("kept", 1, kite.gtts.size)
    }

    /** 20 Nov 2026 (deep review): a read that left the leg's row out as malformed never takes its stop out, however often. */
    @Test fun aSkippedRowNeverTakesALegsStopOut() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(1, kite.gtts.size)
        kite.positions.clear()
        kite.extraNetRows = listOf(org.json.JSONObject().put("tradingsymbol", NIFTY_CE).put("exchange", "NFO").put("product", "NRML"))
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) }; later(Strategies.FLAT_AGREE_MS + 1_000) }
        assertEquals("kept", 1, kite.gtts.size)
        assertTrue(sells().isEmpty())
    }

    /** 20 Nov 2026 (audit): a malformed or partial positions reply is never "no position": the leg's stop stays at Zerodha. */
    @Test fun aMalformedOrPartialPositionsReadNeverTakesALegsStopOut() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(1, kite.gtts.size)
        kite.positionsBody = org.json.JSONObject().put("day", org.json.JSONArray())          // no "net" list
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) }; later(Strategies.FLAT_AGREE_MS + 1_000) }
        assertEquals("kept on a malformed reply", 1, kite.gtts.size)
        kite.positionsBody = null
        kite.positions.clear()                                                                // a reply missing the contract's row
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) }; later(Strategies.FLAT_AGREE_MS + 1_000) }
        assertEquals("kept on a read with no row for it", 1, kite.gtts.size)
        assertTrue(sells().isEmpty())
    }

    /** 20 Nov 2026 (audit): Boss's live start rests its legs' stops at once, never waiting for the next 15-second look. */
    @Test fun aLiveStartRestsItsLegsStopsStraightAway() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE, product = com.optionslab.engine.strategy.Product.MIS)
        startLive(id)
        val sl = restingStops().single()
        assertEquals("70.00", sl.form["trigger_price"])
        val entryAt = kite.requests.indexOfFirst { it.method == "POST" && it.form["order_type"] == "MARKET" }
        val stopAt = kite.requests.indexOfFirst { it.method == "POST" && it.form["order_type"] == "SL" }
        assertTrue(stopAt > entryAt)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("one stop, not two", 1, restingStops().size)
    }

    /** Review C2: part sold by hand in Kite - the resting stop shrinks to what is left, so it can never sell into a short. */
    @Test fun aPartSoldByHandShrinksTheLegsRestingStop() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE, lots = 2, product = com.optionslab.engine.strategy.Product.MIS)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val sid = kite.orders.values.single { it.type == "SL" }.id
        assertEquals(150, kite.order(sid).qty)
        kite.position(NIFTY_CE, 75, 100.0, product = "MIS")
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(75, kite.order(sid).qty)
        assertTrue(kite.order(sid).working)
    }

    // ---- the weekday scheduler (on a fixed clock: Market.testClock) --------------------------------

    private val day by lazy { var d = Market.today(); while (!Market.isTradingDay(d)) d = d.plusDays(1); d }
    private fun at(h: Int, m: Int) {
        Market.testClock = java.time.Clock.fixed(day.atTime(h, m).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST)
    }
    private fun tick() = runBlocking { Strategies.tickAll(compromised = false) }

    /** Saved, live-enabled and armed live ([automatic] or asking for approval) for 09:20-15:15 on weekdays. */
    private fun armedLive(automatic: Boolean): Long {
        val id = saved(Position.B to OptionType.CE)
        assertNull(runBlocking { Strategies.setArmed(id, true, RunMode.LIVE, automatic = automatic) })
        return id
    }

    /**
     * 18 Oct, review: the stop check's own pass never starts a scheduled run, and does not use up the scheduler's slot:
     * the minute pass still starts it.
     */
    @Test fun theStopChecksOwnPassNeverStartsAScheduledRun() {
        live()
        val id = armedLive(automatic = true)
        at(9, 19); tick()
        at(9, 21)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("nothing started by the stop check", kite.placed.isEmpty())
        assertFalse(entry(id).running)
        val notes = tick()
        assertTrue(notes.toString(), notes.any { it.contains("scheduled live start") })
        assertEquals("BUY", buys().single().form["transaction_type"])
    }

    /**
     * 20 Oct, enhancement round 1 (idea 4): Boss's account stop rule over the whole account. Reached and switched on, an automatic
     * scheduled LIVE start is refused; in shadow (the default) it is only recorded and the start goes on.
     */
    @Test fun theAccountStopRuleRefusesAnAutomaticLiveStartOnlyWhenSwitchedOn() {
        live()
        val id = armedLive(automatic = true)
        at(9, 19); tick()
        AccountStopHost.dayRead(-14_000.0)                 // Zerodha's day: past -Rs 13,000 this month
        AccountStopHost.setOn(true)
        at(9, 21)
        val notes = tick()
        assertTrue(notes.toString(), notes.any { it.contains("refused: the account stop rule") })
        assertTrue("nothing sent", kite.placed.isEmpty())
        assertFalse(entry(id).running)
    }

    @Test fun inShadowTheAccountStopRuleOnlyLogsAndTheStartGoesOn() {
        live()
        armedLive(automatic = true)
        at(9, 19); tick()
        AccountStopHost.dayRead(-14_000.0)
        at(9, 21)
        val notes = tick()
        assertTrue(notes.toString(), notes.any { it.contains("scheduled live start") })
        assertEquals("BUY", buys().single().form["transaction_type"])
        assertTrue(AccountStopHost.line(), AccountStopHost.line().contains("in shadow: logged only"))
        assertTrue(AccountStopHost.line(), AccountStopHost.line().contains("REACHED"))
    }

    @Test fun anAutomaticLiveArmStartsAtItsTimeAndSquaresOffAtItsExit() {
        live()
        val id = armedLive(automatic = true)
        at(9, 19); tick()
        assertTrue("nothing before the start time", kite.placed.isEmpty())
        at(9, 21)
        val notes = tick()
        assertTrue(notes.toString(), notes.any { it.contains("scheduled live start") })
        assertEquals("BUY", buys().single().form["transaction_type"])
        assertTrue(entry(id).running)
        at(9, 30); tick()
        assertEquals("started once", 1, buys().size)
        at(15, 16); tick()
        assertEquals("squared off at the exit time", 1, sells().size)
    }

    @Test fun aLiveArmThatAsksWaitsForApprovalAndSendsNothingUntilThen() {
        live()
        val id = armedLive(automatic = false)
        at(9, 19); tick(); at(9, 21); tick()
        assertEquals(mapOf(id to RunMode.LIVE), runBlocking { Strategies.pending() })
        assertTrue("nothing is sent until approved", kite.placed.isEmpty())
        val msg = runBlocking { Strategies.approve(id, compromised = false) }
        assertEquals("Test basket started (live): 1 of 1 legs open.", msg)
        assertEquals(1, kite.placed.size)
        assertTrue(runBlocking { Strategies.pending() }.isEmpty())
    }

    @Test fun aSkippedApprovalIsDropped() {
        live()
        val id = armedLive(automatic = false)
        at(9, 19); tick(); at(9, 21); tick()
        runBlocking { Strategies.skip(id) }
        assertTrue(runBlocking { Strategies.pending() }.isEmpty())
        assertEquals("Nothing is waiting for approval.", runBlocking { Strategies.approve(id, compromised = false) })
        assertTrue(kite.placed.isEmpty())
    }

    /**
     * 19 Nov 2026 (audit batch 2: the strategies' automatic live entries): with "AI trades go live" off, an automatic scheduled LIVE
     * start is refused (nothing sent, it says why); Boss's own approval of a waiting live start, confirmed in the app, still goes.
     */
    @Test fun anAutomaticLiveStartNeedsAiTradesGoLiveBossesOwnStartDoesNot() {
        live()
        val id = armedLive(automatic = true)
        at(9, 19); tick()
        com.optionslab.app.ira.IraNewsTrades.paperFirst = true          // "AI trades go live" off
        try {
            at(9, 21)
            val notes = tick()
            assertTrue(notes.toString(), notes.any { it.contains("scheduled live start refused: \"AI trades go live\" is off") })
            assertTrue("nothing sent", kite.placed.isEmpty())
            assertFalse(entry(id).running)
            val log = runBlocking { Strategies.log() }.joinToString("\n") { it.message }
            assertTrue(log, log.contains("Scheduled start refused: \"AI trades go live\" is off"))
            assertNotNull(Strategies.autoLiveRefusal())
        } finally { com.optionslab.app.ira.IraNewsTrades.paperFirst = false }
        assertNull("on again: nothing refused", Strategies.autoLiveRefusal())
    }

    /**
     * 19 Nov 2026 (audit batch 2, second review): a scheduler-started run's entry is marked automatic all the way to the door: when
     * "AI trades go live" turns off after the scheduler's and the entry's own checks (here at the door itself, the last moment), the
     * door refuses it and nothing reaches Zerodha.
     */
    @Test fun aSchedulerRunWhoseAiLiveTurnsOffAfterItsCheckIsRefusedInsideTheDoor() {
        live()
        val id = armedLive(automatic = true)
        at(9, 19); tick()
        val flipped = java.util.concurrent.atomic.AtomicBoolean(false)
        Broker.testAtDoor = { o -> if (o.tag == "iraalgostrat" && flipped.compareAndSet(false, true)) com.optionslab.app.ira.IraNewsTrades.paperFirst = true }
        try {
            at(9, 21)
            val notes = tick()
            assertTrue("AI trades go live turned off after the scheduler's check", flipped.get())
            assertTrue(notes.toString(), notes.any { it.contains("scheduled live start") })
            assertTrue("nothing sent", buys().isEmpty())
            val log = runBlocking { Strategies.log() }.joinToString("\n") { it.message } + (entry(id).run?.startError ?: "")
            assertTrue(log, log.contains("refused: \"AI trades go live\" is off"))
            assertFalse("refused at the door, not before it", log.contains("automatic live entry refused"))
        } finally { Broker.testAtDoor = null; com.optionslab.app.ira.IraNewsTrades.paperFirst = false }
    }

    @Test fun bossesOwnApprovalOfALiveStartGoesWithAiTradesGoLiveOff() {
        live()
        val id = armedLive(automatic = false)
        at(9, 19); tick(); at(9, 21); tick()
        com.optionslab.app.ira.IraNewsTrades.paperFirst = true
        try {
            assertEquals("Test basket started (live): 1 of 1 legs open.", runBlocking { Strategies.approve(id, compromised = false) })
            assertEquals(1, kite.placed.size)
        } finally { com.optionslab.app.ira.IraNewsTrades.paperFirst = false }
    }

    @Test fun noArmedStartWhileTheBotIsStoppedForToday() {
        live()
        armedLive(automatic = true)
        at(9, 10)
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        tick(); at(9, 21); tick()
        assertTrue(kite.placed.isEmpty())
        val log = runBlocking { Strategies.log() }.joinToString("\n") { it.message }
        assertTrue(log, log.contains("Scheduled start skipped: the bot is stopped for today"))
    }

    @Test fun aScheduledStartOnAHolidayDoesNothing() {
        live()
        armedLive(automatic = true)
        var sat = day; while (sat.dayOfWeek != java.time.DayOfWeek.SATURDAY) sat = sat.plusDays(1)
        val t = { h: Int, m: Int -> Market.testClock = java.time.Clock.fixed(sat.atTime(h, m).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST) }
        t(9, 19); tick(); t(9, 21); tick()
        assertTrue(kite.placed.isEmpty())
    }

    // ---- book-keeping ----------------------------------------------------------------------------

    @Test fun anInvalidDefinitionIsNotSaved() = runBlocking {
        val bad = AutomationSupport.strategy("No legs", Position.B to OptionType.CE).copy(legs = emptyList())
        val err = Strategies.save(bad)
        assertTrue("an error is returned", err != null)
        assertTrue(Strategies.all().isEmpty())
    }

    @Test fun aRunningStrategyCannotBeEditedOrDeleted() {
        live()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        val def = entry(id).def
        assertEquals("Stop the strategy before editing it.", runBlocking { Strategies.save(def.copy(name = "Renamed")) })
        assertEquals("Stop it first.", runBlocking { Strategies.delete(id) })
        runBlocking { Strategies.stop(id, "manual", false) }
        assertNull(runBlocking { Strategies.delete(id) })
        assertTrue(runBlocking { Strategies.all() }.isEmpty())
    }

    @Test fun armingRules() = runBlocking {
        assertNull(Strategies.save(AutomationSupport.strategy("Test basket", Position.B to OptionType.CE)))
        val id = Strategies.all().single().def.id
        assertEquals("Enable live trading for Test basket (Research → Strategies) before arming it live.", Strategies.setArmed(id, true, RunMode.LIVE))
        assertNull(Strategies.setArmed(id, true, RunMode.SANDBOX))
        val sc = Strategies.all().single().def.scheduler!!
        assertTrue(sc.enabled); assertEquals(RunMode.SANDBOX, sc.defaultMode)
        assertEquals(true, Strategies.automatic()[id])
        Strategies.setLiveEnabled(id, true)
        assertNull(Strategies.setArmed(id, true, RunMode.LIVE))
        assertEquals("a live arm asks for approval by default", false, Strategies.automatic()[id])
        assertNull(Strategies.setArmed(id, false, RunMode.LIVE))
        assertFalse(Strategies.all().single().def.scheduler!!.enabled)
        assertEquals("That strategy no longer exists.", Strategies.setArmed(999, true, RunMode.SANDBOX))
        // No start time: cannot be armed.
        assertNull(Strategies.save(AutomationSupport.strategy("Positional", Position.B to OptionType.CE).copy(
            strategyType = com.optionslab.engine.strategy.StrategyType.POSITIONAL, entryTime = null, exitTime = null)))
        val pid = Strategies.all().single { it.def.name == "Positional" }.def.id
        assertEquals("Positional has no start time. Set its entry time in Research → Strategies, then arm it.", Strategies.setArmed(pid, true, RunMode.SANDBOX))
    }

    @Test fun importedOrbCopiesAreBlocked() = runBlocking {
        assertNull(Strategies.save(AutomationSupport.strategy("ORB Fresh", Position.B to OptionType.CE)))
        val id = Strategies.all().single().def.id
        assertTrue(Strategies.needsBreakoutRules(Strategies.all().single().def))
        assertFalse(Strategies.needsBreakoutRules(AutomationSupport.strategy("Morbid", Position.B to OptionType.CE)))
        assertEquals(Strategies.BREAKOUT_BLOCK, Strategies.setArmed(id, true, RunMode.SANDBOX))
        assertEquals(Strategies.BREAKOUT_BLOCK, Strategies.start(id, RunMode.SANDBOX, "manual", false, false))
        assertTrue(kite.requests.isEmpty())
    }

    @Test fun importArrivesDisarmedAndPaperOnly() = runBlocking {
        assertEquals("That is not JSON exported from the desktop app.", Strategies.importJson("{\"unterminated\": "))
        assertEquals("No strategy definitions found in that text.", Strategies.importJson("{\"data\": []}"))
        val def = AutomationSupport.strategy("Imported", Position.B to OptionType.CE).copy(liveEnabled = true,
            scheduler = com.optionslab.engine.strategy.SchedulerConfig(true, listOf(java.time.DayOfWeek.MONDAY), java.time.LocalTime.of(9, 20), null, RunMode.LIVE))
        val json = "{\"data\": [" + com.optionslab.engine.strategy.StrategyCodec.encode(def) + "]}"
        assertEquals("Imported Imported (disarmed, paper only).", Strategies.importJson(json))
        val got = Strategies.all().single().def
        assertFalse(got.liveEnabled)
        assertFalse(got.scheduler!!.enabled)
        assertEquals("Nothing imported. Already here: Imported.", Strategies.importJson(json))
    }

    @Test fun disarmAllAfterARestore() = runBlocking {
        assertNull(Strategies.save(AutomationSupport.strategy("Test basket", Position.B to OptionType.CE)))
        val id = Strategies.all().single().def.id
        Strategies.setLiveEnabled(id, true)
        Strategies.setArmed(id, true, RunMode.LIVE, automatic = true)
        Strategies.disarmAll()
        val d = Strategies.all().single().def
        assertFalse(d.liveEnabled); assertFalse(d.scheduler!!.enabled)
        assertTrue(Strategies.automatic().isEmpty())
    }

    @Test fun stopForTodayAndStartAgain() = runBlocking {
        assertFalse(Strategies.stoppedToday())
        assertEquals("Index bots stopped for today: armed strategies will not start. The commodity bots keep trading.", Strategies.stopForToday(stopRunning = false, compromised = false))
        assertTrue(Strategies.stoppedToday())
        assertEquals("Index bots running: armed strategies start at their times.", Strategies.startAgain())
        assertFalse(Strategies.stoppedToday())
        assertEquals("Nothing is waiting for approval.", Strategies.approve(1, compromised = false))
        assertTrue(Strategies.pending().isEmpty())
    }

    @Test fun theBookSurvivesARestart() {
        live()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        AutomationSupport.reloadFromDisk(Strategies)
        val e = entry(id)
        assertTrue(e.running)
        assertEquals("NIFTY", e.def.underlying)
        assertTrue(Strategies.runningHint)
    }

    // ---- 23 Nov 2026 (B6 follow-ups) ------------------------------------------------------------------------------------------------

    /** The strategy [liveLegWithItsStop] saved and started. */
    private var legStrategy = 0L

    /** A live MIS leg bought at 100, its stop (70) resting at Zerodha as an SL order: that order. */
    private fun liveLegWithItsStop(): FakeKite.Order {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE, product = com.optionslab.engine.strategy.Product.MIS)
        legStrategy = id
        startLive(id)
        assertEquals(1, buys().size)
        return kite.orders.values.single { it.type == "SL" && it.working }
    }

    /** The MARKET exits sent, by tag and quantity. */
    private fun exits() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }.map { it.form["tag"] to it.form["quantity"] }

    /**
     * Item 1: a leg's stop answered by Kite's GeneralException with nothing in the book after the door's ~4 s of looks ([Broker.KiteUnsure]):
     * kept UNKNOWN and looked for in the background at once - no second ~4 s search of its own under the strategies' lock - and no second
     * stop sent beside it while it may rest.
     */
    @Test fun anUnsureAnswerToALegsStopGoesStraightToTheBackgroundSearch() {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE, product = com.optionslab.engine.strategy.Product.MIS)
        kite.nextPlace(reply = FakeKite.Reply.OK)                          // the entry
        kite.nextPlace(reply = FakeKite.Reply.GENERAL_EXCEPTION)           // its leg's stop
        val searches = Broker.callerSearchesForTest.get()
        startLive(id)
        assertEquals(1, buys().size)
        assertEquals("one stop sent", 1, restingStops().size)
        assertEquals("no second search of its own", searches, Broker.callerSearchesForTest.get())
        assertTrue("looked for in the background", Broker.lostKeys("stratstop:").isNotEmpty())
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("none sent beside it while it is looked for", 1, restingStops().size)
    }

    /**
     * Item 1: the leg's exit sale answered so: accepted under a lost id at once (looked for on its later status reads) - no second search of
     * its own - its stop out (it came out for the sale), and nothing more sold while it is looked for.
     */
    @Test fun anUnsureAnswerToALegsExitIsKeptLostWithoutASecondSearch() {
        val stop = liveLegWithItsStop()
        kite.nextPlace(reply = FakeKite.Reply.GENERAL_EXCEPTION)
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)                    // under the 30-point stop: the exit is due
        val searches = Broker.callerSearchesForTest.get()
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("one sale sent", listOf("iraalgostrat" to "75"), exits())
        assertEquals("no second search of its own", searches, Broker.callerSearchesForTest.get())
        assertEquals("its stop came out for the sale", "CANCELLED", kite.order(stop.id).status)
        assertNull("the row free again", SquareOffExit.exitClaimedBy("NFO", NIFTY_CE, "MIS"))
        assertEquals("counted for the square-offs while it is looked for", 75, SquareOffExit.lostPending(NIFTY_CE, "MIS"))
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("nothing more sold while it is looked for", 1, exits().size)
    }

    /**
     * Item 2: while a square-off (the 15:05 expiry square-off, the flat-by) holds the row, the leg's exit waits with its stop resting at
     * Zerodha - nothing taken out, nothing sold; once the row is free, the next look takes the stop out and sells, once.
     */
    @Test fun whileASquareOffHoldsTheRowTheLegsExitWaitsWithItsStopResting() {
        val stop = liveLegWithItsStop()
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)
        val claim = SquareOffExit.claimExit("NFO", NIFTY_CE, "MIS", "iraalgoexpiry")!!
        try {
            runBlocking { Strategies.manageOnly(compromised = false) }
            assertTrue("nothing sold", exits().isEmpty())
            assertEquals("its stop rests", "TRIGGER PENDING", kite.order(stop.id).status)
        } finally { claim.release() }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("its stop out first", "CANCELLED", kite.order(stop.id).status)
        assertEquals(listOf("iraalgostrat" to "75"), exits())
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("sold once", 1, exits().size)
        assertNull("the row free again", SquareOffExit.exitClaimedBy("NFO", NIFTY_CE, "MIS"))
    }

    /**
     * Item 2: a square-off that comes to the row while the leg's exit is taking its stop out is refused the row - it takes nothing out and
     * sends nothing - and the strategy sells: exactly one sale.
     */
    @Test fun whileALegsExitHoldsTheRowASquareOffTakesNothingOutAndSendsNothing() {
        val stop = liveLegWithItsStop()
        val row = Broker.Position(NIFTY_CE, "MIS", 75, 100.0, 60.0, 0.0)
        val refused = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val claimedBy = java.util.concurrent.atomic.AtomicReference<String?>()
        kite.afterNext("/orders/regular/${stop.id}") {
            claimedBy.set(SquareOffExit.exitClaimedBy("NFO", NIFTY_CE, "MIS"))
            refused.set(!runBlocking {
                SquareOffExit.send(row, 75, 0.05, emptyList(), emptyList(), Broker.Positions(listOf(row), emptyList()), AppSettings.load().limits(),
                    "expiry square-off", "iraalgoexpiry", Origins.EXPIRY, "expires today", "expiry") { _, _ -> }
            })
        }
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("the strategy held the row as its stop came out", Strategies.CLAIM_OWNER, claimedBy.get())
        assertEquals("the square-off was refused the row", true, refused.get())
        assertEquals(1, SquareOffExit.skipsForTest("NFO", NIFTY_CE, "MIS"))
        assertEquals("CANCELLED", kite.order(stop.id).status)
        assertEquals("exactly one sale: the strategy's", listOf("iraalgostrat" to "75"), exits())
        assertNull("the row free again", SquareOffExit.exitClaimedBy("NFO", NIFTY_CE, "MIS"))
    }

    /**
     * Item 2: a square-off's sale on the contract a moment ago ([SquareOffExit.inGap]): the leg's exit waits with its stop resting, and no
     * stop goes in on the row either; past the gap it sells.
     */
    @Test fun aSquareOffsSaleAMomentAgoMakesTheLegsExitWait() {
        val stop = liveLegWithItsStop()
        SquareOffExit.resetForTest()                                       // the gap on, as it ships
        var clock = 1_000_000L
        SquareOffExit.clock = { clock }
        SquareOffExit.notePlaced("NFO", NIFTY_CE, "MIS")
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("nothing sold beside it", exits().isEmpty())
        assertEquals("its stop rests", "TRIGGER PENDING", kite.order(stop.id).status)
        clock += SquareOffExit.GAP_MS
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(listOf("iraalgostrat" to "75"), exits())
    }

    /**
     * Item 2: a leg's stop never goes in while a square-off holds the row, in its gap, or after a square-off's sale since the look read the
     * books - tried again on the next look, and placed once the row is free.
     */
    @Test fun aLegsStopWaitsWhileASquareOffHoldsTheRow() {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE, product = com.optionslab.engine.strategy.Product.MIS)
        val claim = SquareOffExit.claimExit("NFO", NIFTY_CE, "MIS", "iraalgoflat")!!
        try {
            startLive(id)
            assertEquals(1, buys().size)
            assertTrue("no stop while a square-off holds the row", restingStops().isEmpty())
            runBlocking { Strategies.manageOnly(compromised = false) }
            assertTrue(restingStops().isEmpty())
        } finally { claim.release() }
        // A square-off's sale on it since: no stop on that look's reads either.
        SquareOffExit.resetForTest()
        var clock = 1_000_000L
        SquareOffExit.clock = { clock }
        SquareOffExit.noteSold("NFO", NIFTY_CE, "MIS")
        SquareOffExit.notePlaced("NFO", NIFTY_CE, "MIS")
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("none in the square-off's gap", restingStops().isEmpty())
        clock += SquareOffExit.GAP_MS
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("placed once the row is free", 1, restingStops().size)
        assertNull(SquareOffExit.exitClaimedBy("NFO", NIFTY_CE, "MIS"))
    }

    /**
     * Item 2: a leg's stop going in holds the row under its own name - a square-off refused the row then is never told a sale is going out
     * (at its backstop minute Boss is told to close it himself, not to wait for a sale that does not exist).
     */
    @Test fun aLegsStopGoingInIsNeverSaidAsASaleGoingOut() {
        assertEquals("a saved strategy's stop going in at Zerodha", SquareOffExit.claimWords(Strategies.CLAIM_STOP_OWNER))
        assertEquals("a saved strategy's exit", SquareOffExit.claimWords(Strategies.CLAIM_OWNER))
        assertEquals("a Pine script's stop going in at Zerodha", SquareOffExit.claimWords(PineAuto.CLAIM_STOP_OWNER))
        assertEquals("a Pine script's exit", SquareOffExit.claimWords(PineAuto.CLAIM_OWNER))
        live()
        kite.position(NIFTY_CE, 75, 100.0, product = "MIS")
        val row = Broker.Position(NIFTY_CE, "MIS", 75, 100.0, 100.0, 0.0)
        for ((owner, selling) in listOf(Strategies.CLAIM_STOP_OWNER to false, PineAuto.CLAIM_STOP_OWNER to false, Strategies.CLAIM_OWNER to true)) {
            val told = ArrayList<String>()
            val claim = SquareOffExit.claimExit("NFO", NIFTY_CE, "MIS", owner)!!
            try {
                assertFalse(runBlocking {
                    SquareOffExit.send(row, 75, 0.05, emptyList(), emptyList(), Broker.Positions(listOf(row), emptyList()), AppSettings.load().limits(),
                        "expiry square-off", "iraalgoexpiry", Origins.EXPIRY, "expires today", "expiry", backstop = true) { _, t -> told += t }
                })
            } finally { claim.release() }
            assertTrue("$owner: told at the backstop: $told", told.isNotEmpty())
            assertEquals("$owner: $told", selling, told.any { "let it finish" in it })
        }
        assertTrue("nothing sent", kite.placed.isEmpty())
    }

    /**
     * Round 2: the leg's stop already filled at Zerodha is booked as the leg's exit even while a square-off holds the row - booking a fill
     * reads only; nothing is taken out or sold, so no claim is asked for it.
     */
    @Test fun aLegsStopFilledAtZerodhaIsBookedWhileASquareOffHoldsTheRow() {
        val stop = liveLegWithItsStop()
        kite.fill(stop.id, 70.0)                                           // Zerodha's stop sold the leg
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)
        val claim = SquareOffExit.claimExit("NFO", NIFTY_CE, "MIS", "iraalgoexpiry")!!
        try {
            runBlocking { Strategies.manageOnly(compromised = false) }
            assertTrue("nothing sent", exits().isEmpty())
            val leg = entry(legStrategy).run!!.legs.values.single()
            assertTrue("booked closed: ${leg.status}", leg.status != "open")
            assertEquals("booked at the stop's own fill", 70.0, leg.exitAvg!!, 1e-9)
        } finally { claim.release() }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("nothing sent after either", exits().isEmpty())
    }

    /**
     * Round 2: Boss's own Exit on a leg refused for now (a square-off selling its contract) says so - never "exit sent" - as it is not tried
     * again by itself; tapped again once the row is free, it goes.
     */
    @Test fun bossesOwnExitRefusedWhileASquareOffHoldsTheRowSaysSo() {
        val stop = liveLegWithItsStop()
        val legId = entry(legStrategy).run!!.legs.values.single().legId
        val claim = SquareOffExit.claimExit("NFO", NIFTY_CE, "MIS", "iraalgoflat")!!
        val said = try { runBlocking { Strategies.closeLeg(legStrategy, legId, compromised = false) } } finally { claim.release() }
        assertTrue(said, said.startsWith("Leg $legId exit not sent: refused for now: the flat-by is selling $NIFTY_CE"))
        assertTrue(said, said.endsWith("Tap again in a moment, or close it in Kite."))
        assertTrue("nothing sold", exits().isEmpty())
        assertEquals("its stop rests", "TRIGGER PENDING", kite.order(stop.id).status)
        assertEquals("Leg $legId exit sent.", runBlocking { Strategies.closeLeg(legStrategy, legId, compromised = false) })
        assertEquals(listOf("iraalgostrat" to "75"), exits())
        assertEquals("CANCELLED", kite.order(stop.id).status)
        // Closed now: a further tap makes no exit, and says so - never "sent".
        val again = runBlocking { Strategies.closeLeg(legStrategy, legId, compromised = false) }
        assertTrue(again, again.startsWith("Leg $legId: no exit was sent"))
        assertEquals(1, exits().size)
    }

    /** Round 2: a carried (NRML) leg's GTT stop that fired and filled is booked too while a square-off holds the row - read only, no sale. */
    @Test fun aLegsFiredGttStopIsBookedWhileASquareOffHoldsTheRow() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 66.95, 67.05)
        val oid = kite.triggerGtt(gid)                                     // its LIMIT under the bid: it fills
        assertEquals("COMPLETE", kite.order(oid).status)
        val claim = SquareOffExit.claimExit("NFO", NIFTY_CE, "NRML", "iraalgoexpiry")!!
        try {
            runBlocking { Strategies.manageOnly(compromised = false) }
            runBlocking { Strategies.tickAll(compromised = false) }
            assertTrue("no sell beside the stop", sells().none { it.form["tag"] != "gtt" })
            assertFalse("the leg is closed", entry(id).run!!.openLegs().any())
        } finally { claim.release() }
        assertEquals("flat, never short", 0, kite.positions["NFO:$NIFTY_CE:NRML"]?.first ?: 0)
    }

    // ---- 23 Nov 2026 (B6 follow-ups, round 3): a leg's stop that sold only part of the leg ---------------------------------------------

    /** A live leg of two lots (150) bought at 100, [product]: its strategy's id. */
    private fun twoLotLeg(product: com.optionslab.engine.strategy.Product = com.optionslab.engine.strategy.Product.MIS): Long {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE, lots = 2, product = product)
        legStrategy = id
        return id
    }

    private fun leg(id: Long) = entry(id).run!!.legs.values.single()

    /**
     * The leg's stop rests for the whole leg (150) and ends part filled: 75 sold at 70, then the rest of it cancelled by the exchange. That
     * part is booked - only it: the leg stays open for the 75 Zerodha still holds, its P&L counts the 75 sold - and a stop rests again for
     * those 75, never more, though Zerodha's positions still trail the fill. Nothing is sold beside it.
     */
    @Test fun aStopEndedPartFilledBooksOnlyThatPartAndTheRestGetsAStopAgain() {
        val id = twoLotLeg()
        startLive(id)
        val stop = kite.orders.values.single { it.type == "SL" && it.working }
        assertEquals(150, stop.qty)
        kite.positionsTrailReads = 1_000                                   // Zerodha's positions trail the stop's fill: they still show 150
        kite.fillPart(stop.id, 75, 70.0)
        // the rest of it cancelled at the exchange (23 Nov 2026, B6-H: in its words - blank words in market hours would be Boss's cancel)
        kite.order(stop.id).apply { status = "CANCELLED"; message = "Cancelled by the exchange (RMS)" }
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)                    // above the stop: no exit of the run's own is due
        runBlocking { Strategies.manageOnly(compromised = false) }
        val l = leg(id)
        assertEquals("open for the rest", "open", l.status)
        assertEquals(75, l.qty)
        assertEquals("only the 75 sold counted", (70.0 - 100.0) * 75, l.realizedPnl, 1e-6)
        assertTrue("no exit-rejected alarm", runBlocking { Strategies.log() }.none { it.kind == "leg_exit_rejected" })
        runBlocking { Strategies.manageOnly(compromised = false) }
        val again = kite.orders.values.single { it.type == "SL" && it.working }
        assertEquals("a stop again, for what the leg still holds - never the trailing positions' 150", 75, again.qty)
        assertTrue("nothing sold beside it", exits().isEmpty())
        assertEquals(75, kite.positions.getValue("NFO:$NIFTY_CE:MIS").first)
    }

    /**
     * The leg's stop rested for fewer lots than the leg (another working sale covered the rest) and filled: its 75 are booked - only them -
     * the leg stays open for the other 75, and once that other sale is gone a stop rests for them.
     */
    @Test fun aStopForFewerLotsThanTheLegBooksOnlyItsOwnFill() {
        val id = twoLotLeg()
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "MIS")   // a sale of Boss's resting in Kite
        startLive(id)
        val stop = kite.orders.values.single { it.type == "SL" && it.working }
        assertEquals("room for one lot only", 75, stop.qty)
        kite.fill(stop.id, 70.0)
        kite.order(other).status = "CANCELLED"                             // Boss takes his own sale back
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val l = leg(id)
        assertEquals("open for the rest", "open", l.status)
        assertEquals(75, l.qty)
        assertEquals((70.0 - 100.0) * 75, l.realizedPnl, 1e-6)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("a stop again for the 75 left", 75, kite.orders.values.single { it.type == "SL" && it.working }.qty)
        assertTrue(exits().isEmpty())
    }

    /**
     * A carried (NRML) leg's GTT stop fires and its order ends part filled (75 of 150, the rest cancelled): that part booked - only it - the
     * leg open for the rest, and a GTT stop rests again for the 75 left.
     */
    @Test fun aFiredGttStopEndedPartFilledBooksOnlyThatPart() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        assertEquals(150, gttQty(kite.gtts.getValue(gid)))
        kite.quote("NFO:$NIFTY_CE", 67.0, 60.0, 67.05)                     // the bid under its limit: the order rests
        val oid = kite.triggerGtt(gid)
        assertEquals("OPEN", kite.order(oid).status)
        kite.fillPart(oid, 75, 66.5)
        // (23 Nov 2026, B6-H: the rest cancelled in the exchange's words - blank words in market hours would be Boss's cancel.)
        kite.order(oid).apply { status = "CANCELLED"; message = "Cancelled by the exchange (RMS)" }
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val l = leg(id)
        assertEquals("open for the rest", "open", l.status)
        assertEquals(75, l.qty)
        assertEquals((66.5 - 100.0) * 75, l.realizedPnl, 1e-6)
        assertTrue("no exit-rejected alarm", runBlocking { Strategies.log() }.none { it.kind == "leg_exit_rejected" })
        runBlocking { Strategies.manageOnly(compromised = false) }
        val active = kite.gtts.values.filter { it.optString("status") == "active" }
        assertEquals("a GTT stop again for the 75 left", listOf(75), active.map { gttQty(it) })
        assertTrue("nothing sold beside it", sells().none { it.form["tag"] == "iraalgostrat" })
    }

    /** A carried leg whose GTT stop fired and whose order ended with nothing filled, in Zerodha's [words]: the active GTTs' quantities after. */
    private fun firedGttEndedUnfilled(words: String): List<Int> {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 60.0, 67.05)
        val oid = kite.triggerGtt(gid)
        kite.order(oid).apply { status = "CANCELLED"; message = words }
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(150, leg(id).qty)
        assertEquals("open", leg(id).status)
        assertTrue("nothing sold", sells().none { it.form["tag"] == "iraalgostrat" })
        return kite.gtts.values.filter { it.optString("status") == "active" }.map { gttQty(it) }
    }

    /** A fired GTT stop whose order the exchange ended with nothing filled is spent: the leg still fully held, a GTT rests again for all of it. */
    @Test fun aFiredGttStopTheExchangeEndedUnfilledRestsAgain() {
        assertEquals(listOf(150), firedGttEndedUnfilled("Cancelled by the exchange (RMS)"))
    }

    /** One whose order Boss cancelled himself in Kite (blank words, in market hours) is his: never put back (WHO CANCELLED A STOP). */
    @Test fun aFiredGttStopBossCancelledIsNeverPutBack() {
        assertEquals(emptyList<Int>(), firedGttEndedUnfilled(""))
        // (B6-H round 2: said as the GTT's own order, never "its SL order".)
        val said = runBlocking { Strategies.log() }.single { it.kind == "stop_cancelled_by_you" }.message
        assertTrue(said, said.contains("the GTT's order was cancelled") && !said.contains("SL order"))
    }

    /**
     * The run's own stop exit, due when the leg's SL has already ended part filled (75 sold at 70, the rest cancelled at the exchange):
     * that part is booked from the stop's fill - no claim, nothing taken out - with no "exit rejected" alarm; the leg stays open for the
     * 75 left, and the run's next exit sells only those 75.
     */
    @Test fun theRunsOwnExitBooksAStopEndedPartFilledAndSellsOnlyTheRest() {
        val id = twoLotLeg()
        startLive(id)
        val stop = kite.orders.values.single { it.type == "SL" && it.working }
        kite.fillPart(stop.id, 75, 70.0)
        kite.order(stop.id).apply { status = "CANCELLED"; message = "Cancelled by the exchange (RMS)" }
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)                    // under the 30-point stop: the run's own exit is due
        runBlocking { Strategies.tickAll(compromised = false) }
        val l = leg(id)
        // (23 Nov 2026, round 4: exact - this look books the stop's 75 only; the 75 left are sold by the run's next exit.)
        assertEquals("open for the rest", "open", l.status)
        assertEquals(75, l.qty)
        assertEquals("booked: only the 75 sold, at the stop's 70", (70.0 - 100.0) * 75, l.realizedPnl, 1e-6)
        val kinds = runBlocking { Strategies.log() }.map { it.kind }
        assertTrue("no exit-rejected alarm: $kinds", "leg_exit_rejected" !in kinds)
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals("the rest sold once, at market", listOf("iraalgostrat" to "75"), exits())
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$NIFTY_CE:MIS").first)
        val sale = kite.orders.values.single { it.tag == "iraalgostrat" && it.side == "SELL" && it.type == "MARKET" }
        assertEquals("the stop's 75 at 70 and the sale's 75 at its own fill", (70.0 - 100.0) * 75 + (sale.avg - 100.0) * 75, leg(id).realizedPnl, 1e-6)
    }

    // ---- 23 Nov 2026 (B6 follow-ups, round 4) ---------------------------------------------------------------------------------------

    /**
     * The run's own stop exit, due while the leg's SL still rests, which fills in part (75 of 150, at 70) as the exit takes it out: that
     * part is booked as a done exit of that size ("COMPLETE", as when the stop had ended before) - no "exit rejected" alarm; the leg stays
     * open for the 75 left, and the run's next exit sells only those 75, once.
     */
    @Test fun theRunsOwnExitBooksAStopThatFillsInPartAsItComesOutWithNoFalseAlarm() {
        val id = twoLotLeg()
        startLive(id)
        val stop = kite.orders.values.single { it.type == "SL" && it.working }
        kite.fillsAsCancelled(stop.id, 75, 70.0)
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)                    // under the 30-point stop: the run's own exit is due
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals("CANCELLED", kite.order(stop.id).status)
        val l = leg(id)
        assertEquals("open for the rest", "open", l.status)
        assertEquals(75, l.qty)
        assertEquals((70.0 - 100.0) * 75, l.realizedPnl, 1e-6)
        val kinds = runBlocking { Strategies.log() }.map { it.kind }
        assertTrue("no exit-rejected alarm: $kinds", "leg_exit_rejected" !in kinds)
        assertTrue("nothing sold beside the stop", exits().isEmpty())
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals("the rest sold once, at market", listOf("iraalgostrat" to "75"), exits())
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$NIFTY_CE:MIS").first)
    }

    /**
     * Boss's Exit on a leg whose SL fills in part (75 of 150) as his exit takes it out: that part is booked, and the exit is driven once
     * more for the 75 left - sold once, at market; he is told both. Flat, never short; the leg closed with both parts' P&L.
     */
    @Test fun bossesExitWhenTheStopFillsInPartAsItComesOutSellsTheRestOnce() {
        val id = twoLotLeg()
        startLive(id)
        val legId = leg(id).legId
        val stop = kite.orders.values.single { it.type == "SL" && it.working }
        kite.fillsAsCancelled(stop.id, 75, 70.0)
        val said = runBlocking { Strategies.closeLeg(id, legId, compromised = false) }
        assertEquals("Leg $legId: its stop at Zerodha had sold 75; exit sent for the rest of the leg.", said)
        assertEquals("CANCELLED", kite.order(stop.id).status)
        assertEquals("the rest sold once, at market", listOf("iraalgostrat" to "75"), exits())
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$NIFTY_CE:MIS").first)
        val sale = kite.orders.values.single { it.tag == "iraalgostrat" && it.side == "SELL" && it.type == "MARKET" }
        val l = leg(id)
        assertEquals(0, l.qty)
        assertEquals((70.0 - 100.0) * 75 + (sale.avg - 100.0) * 75, l.realizedPnl, 1e-6)
        assertTrue("no exit-rejected alarm", runBlocking { Strategies.log() }.none { it.kind == "leg_exit_rejected" })
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("nothing more", 1, exits().size)
    }

    /** A leg's SL that had already ended part filled (75 sold at 70) when Boss taps Exit: that part booked, the 75 left sold once. */
    @Test fun bossesExitAfterTheStopSoldPartSellsTheRestOnce() {
        val id = twoLotLeg()
        startLive(id)
        val legId = leg(id).legId
        val stop = kite.orders.values.single { it.type == "SL" && it.working }
        kite.fillPart(stop.id, 75, 70.0)
        kite.order(stop.id).apply { status = "CANCELLED"; message = "Cancelled by the exchange (RMS)" }
        val said = runBlocking { Strategies.closeLeg(id, legId, compromised = false) }
        assertEquals("Leg $legId: its stop at Zerodha had sold 75; exit sent for the rest of the leg.", said)
        assertEquals(listOf("iraalgostrat" to "75"), exits())
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$NIFTY_CE:MIS").first)
    }

    /** A leg's SL that had already sold the whole leg when Boss taps Exit: booked from it, nothing sent, and he is told so - never "sent". */
    @Test fun bossesExitAfterTheStopSoldTheWholeLegSaysNothingWasSent() {
        val id = twoLotLeg()
        startLive(id)
        val legId = leg(id).legId
        val stop = kite.orders.values.single { it.type == "SL" && it.working }
        kite.fill(stop.id, 70.0)
        val said = runBlocking { Strategies.closeLeg(id, legId, compromised = false) }
        assertEquals("Leg $legId: its stop at Zerodha had sold 150; nothing more was sent.", said)
        assertTrue("nothing sold", exits().isEmpty())
        assertEquals(0, leg(id).qty)
        assertEquals((70.0 - 100.0) * 150, leg(id).realizedPnl, 1e-6)
    }

    /**
     * ... and when the rest cannot go now (a square-off holds the row): the stop's part is still booked (reading only), nothing is sold, and
     * Boss is told what the stop sold and what is still open - never "sent". Tapped again once the row is free, the 75 left go, once.
     */
    @Test fun bossesExitAfterTheStopSoldPartSaysWhatIsStillOpenWhenTheRestIsRefused() {
        val id = twoLotLeg()
        startLive(id)
        val legId = leg(id).legId
        val stop = kite.orders.values.single { it.type == "SL" && it.working }
        kite.fillPart(stop.id, 75, 70.0)
        kite.order(stop.id).apply { status = "CANCELLED"; message = "Cancelled by the exchange (RMS)" }
        val claim = SquareOffExit.claimExit("NFO", NIFTY_CE, "MIS", "iraalgoflat")!!
        val said = try { runBlocking { Strategies.closeLeg(id, legId, compromised = false) } } finally { claim.release() }
        assertTrue(said, said.startsWith("Leg $legId: its stop at Zerodha had sold 75; 75 still open (refused for now: the flat-by is selling $NIFTY_CE"))
        assertTrue(said, said.endsWith("): tap Exit again, or close it in Kite."))
        assertTrue("nothing sold", exits().isEmpty())
        val l = leg(id)
        assertEquals("open", l.status)
        assertEquals(75, l.qty)
        assertEquals((70.0 - 100.0) * 75, l.realizedPnl, 1e-6)
        assertEquals("Leg $legId exit sent.", runBlocking { Strategies.closeLeg(id, legId, compromised = false) })
        assertEquals(listOf("iraalgostrat" to "75"), exits())
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$NIFTY_CE:MIS").first)
    }

    /** A carried (NRML) leg's GTT stop, put to "triggered" with no order listed yet (Zerodha placing it): its leg's GTT id. */
    private fun bareFiredGtt(id: Long): Long {
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.gtts.getValue(gid).put("status", "triggered")
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)                    // above the stop: no exit of the run's own is due
        return gid
    }

    /**
     * A fired GTT stop that lists no order yet is not put back on that look (its order may be on its way: a stop beside it could sell
     * twice). Its order shows on the next look and fills: booked from it, with no second stop and nothing sold beside it.
     */
    @Test fun aFiredGttListingNoOrderYetIsNotPutBackOnOneLook() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        val gid = bareFiredGtt(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("no stop put back beside it", listOf(gid), kite.gtts.keys.toList())
        kite.quote("NFO:$NIFTY_CE", 67.0, 66.95, 67.05)
        val oid = kite.triggerGtt(gid)                                     // its order shows now, and fills
        assertEquals("COMPLETE", kite.order(oid).status)
        later(Strategies.GTT_BARE_AGREE_MS + 1_000)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val l = leg(id)
        assertEquals("booked closed from its own order", 0, l.qty)
        assertEquals((kite.order(oid).avg - 100.0) * 150, l.realizedPnl, 1e-6)
        assertEquals("no second stop", listOf(gid), kite.gtts.keys.toList())
        assertTrue("nothing sold beside it", sells().none { it.form["tag"] != "gtt" })
        assertEquals("flat, never short", 0, kite.positions["NFO:$NIFTY_CE:NRML"]?.first ?: 0)
    }

    /** One that still lists no order on a second look [Strategies.GTT_BARE_AGREE_MS] later is taken as never placed: a stop rests again. */
    @Test fun aFiredGttStillListingNoOrderOnASecondLookIsPutBack() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        val gid = bareFiredGtt(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("not on the first look", listOf(gid), kite.gtts.keys.toList())
        later(Strategies.GTT_BARE_AGREE_MS + 1_000)
        runBlocking { Strategies.manageOnly(compromised = false) }
        runBlocking { Strategies.manageOnly(compromised = false) }
        val active = kite.gtts.values.filter { it.optString("status") == "active" }
        assertEquals("a GTT stop again, for the whole leg", listOf(150), active.map { gttQty(it) })
        assertTrue("nothing sold", sells().isEmpty())
    }

    /**
     * The run's own exit due while the leg's fired GTT lists no order yet: nothing sold beside it on that look (its order may be on its
     * way); still none on a look [Strategies.GTT_BARE_AGREE_MS] later, it is taken as never placed and the leg sold once, at market.
     */
    @Test fun theRunsExitWaitsOnAFiredGttListingNoOrderThenSellsOnce() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        bareFiredGtt(id)
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)                    // under the 30-point stop: the run's own exit is due
        runBlocking { Strategies.tickAll(compromised = false) }
        assertTrue("nothing sold beside it", sells().isEmpty())
        later(Strategies.GTT_BARE_AGREE_MS + 1_000)
        runBlocking { Strategies.tickAll(compromised = false) }
        runBlocking { Strategies.tickAll(compromised = false) }
        assertEquals("sold once", listOf("NRML" to "150"), sells().map { it.form["product"] to it.form["quantity"] })
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$NIFTY_CE:NRML").first)
    }

    /**
     * Held no more at Zerodha (the stop sold 75 at 70, Boss the other 75 by hand): the stop taken out has filled in part, so it stays on
     * record; after a save and a reload of the book, the next look books that part - only it, at the stop's own fill.
     */
    @Test fun aStopPartFilledAsItComesOffAFlatLegIsBookedNextLookAfterAReload() {
        val id = twoLotLeg()
        startLive(id)
        val stop = kite.orders.values.single { it.type == "SL" && it.working }
        kite.fillPart(stop.id, 75, 70.0)
        kite.position(NIFTY_CE, 0, 0.0, product = "MIS")                  // and Boss sold the other 75 in Kite
        runBlocking { Strategies.manageOnly(compromised = false) }        // two agreeing reads: the stop comes out
        assertEquals("CANCELLED", kite.order(stop.id).status)
        assertEquals("not booked yet", 150, leg(id).qty)
        Strategies.forgetCacheForTest()                                   // the book read from its file again
        runBlocking { Strategies.manageOnly(compromised = false) }
        val l = leg(id)
        assertEquals("only the stop's 75 booked", 75, l.qty)
        assertEquals((70.0 - 100.0) * 75, l.realizedPnl, 1e-6)
        assertTrue("no exit-rejected alarm", runBlocking { Strategies.log() }.none { it.kind == "leg_exit_rejected" })
        assertTrue("nothing sold", exits().isEmpty())
        assertTrue("no stop on a flat position", kite.orders.values.none { it.type == "SL" && it.working })
    }

    /**
     * No room for the stop (Boss's own sale of all 150 rests in Kite): the stop taken out has filled in part (75 at 70) as it came out, so
     * it stays on record; after a save and a reload, the next look books that part only. No stop beside Boss's sale; nothing sold.
     */
    @Test fun aStopPartFilledAsItComesOutForNoRoomIsBookedNextLookAfterAReload() {
        val id = twoLotLeg()
        startLive(id)
        val stop = kite.orders.values.single { it.type == "SL" && it.working }
        val boss = kite.restsInKite(NIFTY_CE, "SELL", 150, 150.0, product = "MIS")
        kite.fillsAsCancelled(stop.id, 75, 70.0)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("CANCELLED", kite.order(stop.id).status)
        assertEquals(75, kite.order(stop.id).filled)
        assertEquals("not booked yet", 150, leg(id).qty)
        Strategies.forgetCacheForTest()
        runBlocking { Strategies.manageOnly(compromised = false) }
        val l = leg(id)
        assertEquals("only the stop's 75 booked", 75, l.qty)
        assertEquals((70.0 - 100.0) * 75, l.realizedPnl, 1e-6)
        assertTrue("nothing sold", exits().isEmpty())
        assertTrue("no stop beside Boss's sale", kite.orders.values.none { it.type == "SL" && it.working })
        assertEquals("Boss's sale untouched", "OPEN", kite.order(boss).status)
    }

    /**
     * Two legs whose stops both ended filled before one look: each booked - the second never written over the first (each booked from the run
     * as it is then).
     */
    @Test fun twoLegsStopsFilledInOneLookAreBothBooked() {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE, Position.B to OptionType.PE, product = com.optionslab.engine.strategy.Product.MIS)
        startLive(id)
        val stops = kite.orders.values.filter { it.type == "SL" && it.working }
        assertEquals(2, stops.size)
        stops.forEach { kite.fill(it.id, it.trigger) }
        runBlocking { Strategies.manageOnly(compromised = false) }
        val legs = entry(id).run!!.legs.values
        assertTrue("both legs booked closed: ${legs.map { it.status }}", legs.none { it.status == "open" })
        assertTrue("nothing sold beside the stops", exits().isEmpty())
    }

    /**
     * B6 review, eighth round (missing test): Boss's own exit on the leg's contract whose reply was lost may be selling it - the leg's stop is
     * not rested beside it (the app watches the leg's level meanwhile) until it is known; given up on after a good read 2 minutes on, rested.
     */
    @Test fun aLegsStopIsNotRestedWhileBossesLostExitMayBeSellingIt() {
        live()
        midSession()
        val id = saved(Position.B to OptionType.CE, product = com.optionslab.engine.strategy.Product.MIS)
        Broker.lostSearchInlineForTest = true; Broker.lostLookGapMsForTest = 0L
        var key: String? = null
        kite.afterNext("/orders/regular") {                                  // the entry went: then his exit's reply on the contract is lost
            val s = kite.placed.last().form.getValue("tradingsymbol")
            key = SquareOffExit.bossLostKey("NFO", s, "MIS")
            Broker.noteLost(key!!, Kite.Order(s, Kite.Side.SELL, 75, 75, "MIS", "MARKET", null), emptyList(), "Your exit")
        }
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("no stop beside his exit", restingStops().isEmpty())
        Broker.ageLostForTest(key!!, Broker.LOST_SEARCH_MS + 1_000)
        runBlocking { Strategies.manageOnly(compromised = false) }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("rested once it is known not to have gone", 1, restingStops().size)
    }

    // ---- 23 Nov 2026 (B6-H): WHO CANCELLED A STOP for the strategies' leg stops; an earlier day's fired GTT; grow-back; log words ------------

    /** The run's log lines of [kind]. */
    private fun logged(kind: String) = runBlocking { Strategies.log() }.filter { it.kind == kind }

    /** The working SL orders resting at Zerodha. */
    private fun workingStops() = kite.orders.values.filter { it.type == "SL" && it.working }

    /** Item 1: the classifier's three outcomes for an SL (its ended order) and a GTT (gone), and its words for the stop's own fill. */
    @Test fun theStopEndClassifierTellsTheAppsZerodhasAndBossesApart() {
        val inHours = Market.today().atTime(11, 0)
        val afterClose = Market.today().atTime(15, 45)
        assertEquals(Strategies.StopEnd.APP, Strategies.stopEndOf(true, "CANCELLED", "", inHours))
        assertEquals(Strategies.StopEnd.ZERODHA, Strategies.stopEndOf(false, "REJECTED", "", inHours))
        assertEquals(Strategies.StopEnd.ZERODHA, Strategies.stopEndOf(false, "CANCELLED", "Cancelled by the exchange (RMS)", inHours))
        assertEquals(Strategies.StopEnd.ZERODHA, Strategies.stopEndOf(false, "CANCELLED", "Your order has been cancelled because of market protection.", inHours))
        assertEquals(Strategies.StopEnd.BOSS, Strategies.stopEndOf(false, "CANCELLED", "", inHours))
        assertEquals(Strategies.StopEnd.BOSS, Strategies.stopEndOf(false, "CANCELLED", "cancelled by user", inHours))
        Market.testOrdersAnyTime = false
        try {
            assertEquals("outside market hours: Zerodha's", Strategies.StopEnd.ZERODHA, Strategies.stopEndOf(false, "CANCELLED", "", afterClose))
            assertEquals(Strategies.StopEnd.BOSS, Strategies.stopEndOf(false, "CANCELLED", "", inHours))
            assertEquals("the app's own at any hour", Strategies.StopEnd.APP, Strategies.stopEndOf(true, "CANCELLED", "", afterClose))
            // An MCX contract by MCX's own hours (at the end's own time), never NSE's.
            assertEquals(if (McxMarket.calendarQuick().isOpen(afterClose)) Strategies.StopEnd.BOSS else Strategies.StopEnd.ZERODHA,
                Strategies.stopEndOf(false, "CANCELLED", "", afterClose, "MCX"))
        } finally { Market.testOrdersAnyTime = true }
        assertEquals(Strategies.StopEnd.BOSS, Strategies.gttEndOf(false, "deleted"))
        assertEquals(Strategies.StopEnd.APP, Strategies.gttEndOf(true, "deleted"))
        for (st in listOf("cancelled", "expired", "rejected", "disabled", null)) assertEquals("$st", Strategies.StopEnd.ZERODHA, Strategies.gttEndOf(false, st))
        assertEquals("stop", Strategies.stopWord("B", 100.0, 70.0))
        assertEquals("profit lock", Strategies.stopWord("B", 100.0, 110.0))
        assertEquals("stop", Strategies.stopWord("S", 100.0, 130.0))
        assertEquals("profit lock", Strategies.stopWord("S", 100.0, 90.0))
        assertEquals("stop", Strategies.stopWord("B", 0.0, 110.0))
        assertEquals("stop", Strategies.stopWord("B", 100.0, null))
    }

    /**
     * Item 1: a leg's SL order Boss cancelled themselves in Kite (in market hours, blank words, no mark of the app's own) is never put back -
     * they are told once - and the run watches its level itself: under it, the leg is sold once, at market.
     */
    @Test fun aLegsStopBossCancelledInKiteIsNeverPutBackAndTheRunSellsAtItsLevel() {
        val stop = liveLegWithItsStop()
        kite.order(stop.id).status = "CANCELLED"
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertTrue("never put back", workingStops().isEmpty())
        assertEquals("one stop ever", 1, restingStops().size)
        assertEquals("told once", 1, logged("stop_cancelled_by_you").size)
        assertTrue(logged("stop_cancelled_by_you").single().message.contains("looks cancelled by you in Kite"))
        // Restarted: still theirs - nothing put back, not told again.
        Strategies.forgetCacheForTest()
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue(workingStops().isEmpty())
        assertEquals(1, logged("stop_cancelled_by_you").size)
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)                    // under the 30-point stop
        runBlocking { Strategies.manageOnly(compromised = false) }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("the run sold at its level, once", listOf("iraalgostrat" to "75"), exits())
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$NIFTY_CE:MIS").first)
    }

    /** Item 1: one Zerodha ended in its own words (the exchange, its risk system) is put back: a stop rests again, Boss not told it was theirs. */
    @Test fun aLegsStopZerodhaCancelledIsPutBack() {
        val stop = liveLegWithItsStop()
        kite.order(stop.id).apply { status = "CANCELLED"; message = "Cancelled by the exchange (RMS)" }
        runBlocking { Strategies.manageOnly(compromised = false) }
        val again = workingStops().single()
        assertTrue("a new stop", again.id != stop.id)
        assertEquals(75, again.qty)
        assertEquals(70.0, again.trigger, 1e-9)
        assertTrue(logged("stop_cancelled_by_you").isEmpty())
    }

    /**
     * Item 1: the app's own cancel (the leg's exit taking its stop out) whose confirming read failed is marked - in the book, before the
     * cancel went - so after a restart, with the leg still held (that exit never went), the stop found cancelled is the app's: put back,
     * never read as Boss's.
     */
    @Test fun theAppsOwnCancelIsMarkedBeforeItGoesAndSurvivesARestart() {
        val stop = liveLegWithItsStop()
        val legId = leg(legStrategy).legId
        kite.down += "/orders/${stop.id}"                                  // its own history unreadable: the cancel cannot be confirmed
        // (B6-H round 2: the book's queued write made on the caller's thread, so the file shows what was queued; read BEFORE the fake handles
        // the cancel - what the phone had saved by the time the cancel reached Zerodha, never a timing.)
        Strategies.writeInlineForTest = true
        var markedOnDisk = false
        kite.beforeNext("/orders/regular/${stop.id}") {
            val f = Strategies.filesForErase().first()
            val o = org.json.JSONObject(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
            markedOnDisk = o.optJSONObject("ownCancels")?.has(stop.id) == true
        }
        val said = runBlocking { Strategies.closeLeg(legStrategy, legId, compromised = false) }
        assertTrue(said, said.startsWith("Leg $legId exit not sent"))
        assertEquals("cancelled at Zerodha", "CANCELLED", kite.order(stop.id).status)
        assertTrue("the mark was in the book on disk before the cancel reached Zerodha", markedOnDisk)
        assertTrue("nothing sold", exits().isEmpty())
        kite.down -= "/orders/${stop.id}"
        AutomationSupport.reloadFromDisk(Strategies)                       // as after a restart
        assertTrue(runBlocking { Strategies.ownCancelForTest(stop.id) })
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("put back", 1, workingStops().size)
        assertTrue("never read as Boss's", logged("stop_cancelled_by_you").isEmpty())
    }

    /**
     * Item 1: an own-cancel mark whose cancel never took ends once a fresh read still shows the stop working [Strategies.OWN_CANCEL_RECHECK_MS]
     * after it - never before - so Boss's own cancel of it in Kite afterwards is theirs: not put back.
     */
    @Test fun anOwnCancelMarkEndsWhenItsStopIsStillWorkingAndBossesLaterCancelIsHis() {
        val stop = liveLegWithItsStop()
        val legId = leg(legStrategy).legId
        kite.cancelsLost = 1                                                // answered as taken, never lands
        runBlocking { Strategies.closeLeg(legStrategy, legId, compromised = false) }
        assertTrue(stop.working)
        assertTrue(runBlocking { Strategies.ownCancelForTest(stop.id) })
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("kept while young", runBlocking { Strategies.ownCancelForTest(stop.id) })
        later(Strategies.OWN_CANCEL_RECHECK_MS + 1_000)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertFalse("ended: still working well after the mark", runBlocking { Strategies.ownCancelForTest(stop.id) })
        kite.order(stop.id).status = "CANCELLED"                           // now Boss cancels it in Kite
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("theirs: never put back", workingStops().isEmpty())
        assertEquals(1, logged("stop_cancelled_by_you").size)
    }

    /**
     * Item 1: a square-off's take-out marks the leg's stop as the app's own ([SquareOffExit.markTaken]): its sale refused, the leg still
     * held, the strategy puts its stop back - never read as Boss's.
     */
    @Test fun aSquareOffsTakeOutIsMarkedTheAppsOwnSoTheStopIsPutBack() {
        val stop = liveLegWithItsStop()
        val row = Broker.Position(NIFTY_CE, "MIS", 75, 100.0, 100.0, 0.0)
        kite.nextPlace(outcome = Outcome.REJECT, message = "RMS:Margin Exceeds, Required:1,20,000.00, Available:40,000.00")
        runBlocking {
            SquareOffExit.send(row, 75, 0.05, emptyList(), emptyList(), Broker.Positions(listOf(row), emptyList()), AppSettings.load().limits(),
                "expiry square-off", "iraalgoexpiry", Origins.EXPIRY, "expires today", "expiry") { _, _ -> }
        }
        assertEquals("taken out by the square-off", "CANCELLED", kite.order(stop.id).status)
        assertEquals("still held: its sale was refused", 75, kite.positions.getValue("NFO:$NIFTY_CE:MIS").first)
        assertTrue("marked the app's own", runBlocking { Strategies.ownCancelForTest(stop.id) })
        Strategies.markOwnCancels(listOf("990000001"))                    // an order no leg's stop is: not kept in the strategies' book
        assertFalse(runBlocking { Strategies.ownCancelForTest("990000001") })
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("put back", 1, workingStops().size)
        assertTrue(logged("stop_cancelled_by_you").isEmpty())
    }

    /** Item 1: one Boss cancels after a part of it sold: that part booked, the rest of the leg gets no stop again, and they are told once. */
    @Test fun aStopBossCancelsAfterAPartSoldBooksThePartAndRestsNoStopForTheRest() {
        val id = twoLotLeg()
        startLive(id)
        val stop = workingStops().single()
        kite.fillPart(stop.id, 75, 70.0)
        kite.order(stop.id).status = "CANCELLED"                           // Boss cancels the rest in Kite
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        val l = leg(id)
        assertEquals("open for the rest", "open", l.status)
        assertEquals(75, l.qty)
        assertEquals((70.0 - 100.0) * 75, l.realizedPnl, 1e-6)
        assertTrue("no stop for the rest", workingStops().isEmpty())
        assertEquals(1, logged("stop_cancelled_by_you").size)
        assertTrue(exits().isEmpty())
    }

    /**
     * Item 1: a stop Boss cancelled after a part of it sold, on a leg they then closed by hand too (Zerodha holds none): that part is booked by
     * the stop checks, with no note that the stop was theirs - a flat leg needs none.
     */
    @Test fun aPartFilledStopBossCancelledOnALegNoLongerHeldIsBookedWithoutANote() {
        val id = twoLotLeg()
        startLive(id)
        val stop = workingStops().single()
        kite.fillPart(stop.id, 75, 70.0)
        kite.order(stop.id).status = "CANCELLED"
        kite.position(NIFTY_CE, 0, 0.0, product = "MIS")                  // and the rest closed in Kite
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) }; later(Strategies.FLAT_AGREE_MS + 1_000) }
        assertEquals("the stop's 75 booked", 75, leg(id).qty)
        assertEquals((70.0 - 100.0) * 75, leg(id).realizedPnl, 1e-6)
        assertTrue(logged("stop_cancelled_by_you").isEmpty())
        assertTrue(workingStops().isEmpty())
        assertTrue(exits().isEmpty())
    }

    /** Item 1: a carried leg's GTT Boss deleted in Kite ("deleted", no mark) is never put back; one the app deleted itself is. */
    @Test fun aGttBossDeletedIsNeverPutBackButOneTheAppDeletedIs() {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.gtts.getValue(gid).put("status", "deleted")
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertTrue("never put back", kite.gtts.values.none { it.optString("status") == "active" })
        assertEquals(1, logged("stop_cancelled_by_you").size)
        assertTrue(logged("stop_cancelled_by_you").single().message.contains("looks deleted by you"))
    }

    /**
     * Item 1: a carried leg's GTT the app deleted itself (the leg's exit taking its stop out, the confirming read failed, the exit not sent)
     * is marked "gtt:<id>" before the delete goes, so a GTT list showing it "deleted" is the app's: put back, never read as Boss's.
     */
    @Test fun aGttTheAppDeletedIsPutBack() {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        val legId = leg(id).legId
        val listed = org.json.JSONObject(kite.gtts.getValue(gid).toString())
        kite.afterNext("/gtt/triggers/$gid") { kite.gttReadFailures = 1 }  // the list unreadable right after the delete
        val said = runBlocking { Strategies.closeLeg(id, legId, compromised = false) }
        assertTrue(said, said.startsWith("Leg $legId exit not sent"))
        assertTrue("deleted at Zerodha", gid !in kite.gtts)
        assertTrue(runBlocking { Strategies.ownCancelForTest("gtt:$gid") })
        kite.gtts[gid] = listed.put("status", "deleted")                    // Kite lists it as deleted
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertEquals("put back", listOf(75), kite.gtts.values.filter { it.optString("status") == "active" }.map { gttQty(it) })
        assertTrue(logged("stop_cancelled_by_you").isEmpty())
        assertTrue("nothing sold", sells().none { it.form["tag"] == "iraalgostrat" })
    }

    /** Item 1: a stop Boss cancelled on a leg no longer held (they closed it too) is simply taken off the books: no note, no stop, nothing sold. */
    @Test fun aStopCancelledOnALegNoLongerHeldIsDroppedWithoutANote() {
        val stop = liveLegWithItsStop()
        kite.position(NIFTY_CE, 0, 0.0, product = "MIS")
        kite.order(stop.id).status = "CANCELLED"
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) }; later(Strategies.FLAT_AGREE_MS + 1_000) }
        assertTrue(logged("stop_cancelled_by_you").isEmpty())
        assertTrue(workingStops().isEmpty())
        assertTrue(exits().isEmpty())
    }

    /**
     * Item 2: a carried leg whose GTT stop fired on an earlier day (its order not in today's book, and Kite no longer knows it): not let go on
     * one look; on a second look [Strategies.GTT_BARE_AGREE_MS] later Boss is told once, a stop rests again for what Zerodha still holds, and
     * the leg's own exit then sells what is held, once - never refused for ever.
     */
    @Test fun aCarriedLegsGttFiredOnAnEarlierDayIsLetGoAndItsExitsWork() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 60.0, 67.05)                     // the bid under its limit: its order rests, unfilled
        val oid = kite.triggerGtt(gid)
        runBlocking { Strategies.ageLegStopsForTest(1) }                    // the GTT was placed the day before
        gttFiredAt(gid, dayBefore().atTime(14, 0))                         // and fired that day (Zerodha's own time of its order)
        kite.orders.remove(oid)                                            // that day's order: gone from today's book and from Kite
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("not let go on one look", logged("stop_fired_earlier").isEmpty())
        later(Strategies.GTT_BARE_AGREE_MS + 1_000)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("told once", 1, logged("stop_fired_earlier").size)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("a stop again for what is held", listOf(150), kite.gtts.values.filter { it.optString("status") == "active" }.map { gttQty(it) })
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)                    // under the stop: the run's own exit
        runBlocking { Strategies.manageOnly(compromised = false) }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("sold once, what is held", listOf("NRML" to "150"), sells().filter { it.form["tag"] == "iraalgostrat" }.map { it.form["product"] to it.form["quantity"] })
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$NIFTY_CE:NRML").first)
        assertEquals(1, logged("stop_fired_earlier").size)
    }

    /**
     * Item 2: ... but an order read that gets no answer does not let the stop go on a passing fault: only after
     * [Strategies.EARLY_UNREAD_AGREE_MS] of looks with today's book never listing it (a today's order shows there long before).
     */
    @Test fun aCarriedLegsGttWhoseOrderCannotBeReadIsLetGoOnlyAfterTheLongWait() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 60.0, 67.05)
        val oid = kite.triggerGtt(gid)
        runBlocking { Strategies.ageLegStopsForTest(1) }
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        kite.hideFromBook(oid, 1_000)
        kite.down += "/orders/$oid"                                        // its own history: no answer
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) }; later(Strategies.GTT_BARE_AGREE_MS + 1_000) }
        assertTrue("not let go", logged("stop_fired_earlier").isEmpty())
        assertEquals("no second stop beside its order", listOf(gid), kite.gtts.keys.toList())
        assertTrue(sells().none { it.form["tag"] == "iraalgostrat" })
        later(Strategies.EARLY_UNREAD_AGREE_MS)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("let go after the long wait, told once", 1, logged("stop_fired_earlier").size)
    }

    /** Item 2: ... one whose order Kite still knows (read by its id) and that filled is booked from that fill: nothing sold. */
    @Test fun aCarriedLegsGttFiredOnAnEarlierDayThatFilledIsBookedFromItsOrder() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 66.95, 67.05)
        val oid = kite.triggerGtt(gid)                                     // its LIMIT under the bid: it fills
        assertEquals("COMPLETE", kite.order(oid).status)
        runBlocking { Strategies.ageLegStopsForTest(1) }
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        kite.hideFromBook(oid, 1_000)                                      // not in today's book; its own history still answers
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val l = leg(id)
        assertEquals("booked closed from its own fill", 0, l.qty)
        assertEquals((kite.order(oid).avg - 100.0) * 150, l.realizedPnl, 1e-6)
        assertTrue("nothing sold", sells().none { it.form["tag"] == "iraalgostrat" })
        assertTrue(logged("stop_fired_earlier").isEmpty())
    }

    /**
     * Item 3: a stop resting for fewer lots than the leg (Boss's own sale covered one lot) grows back once that sale is gone - the same SL
     * order modified to the whole leg, never a second stop beside it.
     */
    @Test fun aStopRestingForFewerLotsGrowsBackWhenRoomReturns() {
        val id = twoLotLeg()
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "MIS")
        startLive(id)
        val stop = workingStops().single()
        assertEquals("room for one lot only", 75, stop.qty)
        kite.order(other).status = "CANCELLED"                             // Boss takes their own sale back
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("grown to the whole leg", 150, kite.order(stop.id).qty)
        assertTrue(kite.order(stop.id).working)
        assertEquals("one stop, never two", 1, restingStops().size)
        assertEquals(1, logged("stop_grown").size)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(150, kite.order(stop.id).qty)
        assertTrue(exits().isEmpty())
    }

    /** Item 3: ... never past what Zerodha holds: Boss's sale filled, the positions still trailing it - the stop stays at the lot left. */
    @Test fun aStopNeverGrowsPastWhatZerodhaHoldsOnATrailingRead() {
        val id = twoLotLeg()
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "MIS")
        startLive(id)
        val stop = workingStops().single()
        assertEquals(75, stop.qty)
        kite.positionsTrailReads = 1_000                                   // Zerodha's positions trail the sale below: they still show 150
        kite.fill(other, 150.0)                                            // Boss's own sale fills
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("never grown past the 75 held", 75, kite.order(stop.id).qty)
        assertTrue(logged("stop_grown").isEmpty())
    }

    /** Item 3: a grow Zerodha refuses leaves the stop as it is (still a stop for its lot), Boss told once, and is not tried on every look. */
    @Test fun aRefusedGrowLeavesTheStopAndIsNotRetriedEveryLook() {
        val id = twoLotLeg()
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "MIS")
        startLive(id)
        val stop = workingStops().single()
        kite.order(other).status = "CANCELLED"
        kite.modifyRefusals = 1
        val puts = { kite.requests.count { it.method == "PUT" && it.path.endsWith("/${stop.id}") } }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("one modify tried", 1, puts())
        assertEquals("still a stop for its lot", 75, kite.order(stop.id).qty)
        assertTrue(kite.order(stop.id).working)
        assertEquals(1, logged("stop_not_grown").size)
        assertEquals("warn", logged("stop_not_grown").single().severity)
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertEquals("not tried again on every look", 1, puts())
        assertEquals(75, kite.order(stop.id).qty)
    }

    /** Item 3: a carried leg's GTT stop resting for fewer lots grows back too (the same GTT modified). */
    @Test fun aGttStopRestingForFewerLotsGrowsBack() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "NRML")
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        assertEquals(75, gttQty(kite.gtts.getValue(gid)))
        kite.order(other).status = "CANCELLED"
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("the same GTT", listOf(gid), kite.gtts.keys.toList())
        assertEquals("grown to the whole leg", 150, gttQty(kite.gtts.getValue(gid)))
    }

    /**
     * Item 4: a stop fill booked by the stop checks is logged as the stop's own fill - never "exit leg manual" or Boss's closure - and the
     * order is named as the leg's stop.
     */
    @Test fun aStopFillBookedByTheStopChecksIsLoggedAsTheStopsOwn() {
        val stop = liveLegWithItsStop()
        kite.trigger(stop.id, 70.0)                                        // triggered with the market through its limit: it fills at 66.50
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)                    // back above the stop: no exit of the run's own is due
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("the leg is closed", leg(legStrategy).status != "open")
        val line = logged("leg_stop_filled").single()
        assertEquals("Leg ${leg(legStrategy).legId}'s stop at Zerodha filled: SELL 75 $NIFTY_CE at 66.50 (booked from its own fill; nothing sent)", line.message)
        val all = runBlocking { Strategies.log() }
        assertTrue(all.joinToString { it.message }, all.none { it.kind == "leg_close_manual" || "exit_leg_manual" in it.message })
        assertEquals("Test basket · leg ${leg(legStrategy).legId} stop", runBlocking { Strategies.owners() }["kite:${stop.id}"])
        assertTrue(exits().isEmpty())
    }

    // ---- 23 Nov 2026 (B6-H round 2) ---------------------------------------------------------------------------------------------------

    /** The GTT [gid]'s fired order's result time set to [at] (Zerodha's own time of the order it placed). */
    private fun gttFiredAt(gid: Long, at: java.time.LocalDateTime) {
        kite.gtts.getValue(gid).getJSONArray("orders").getJSONObject(0).getJSONObject("result")
            .put("timestamp", at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
    }

    /** The trading day before today. */
    private fun dayBefore(): java.time.LocalDate { var d = Market.today().minusDays(1); while (!Market.isTradingDay(d)) d = d.minusDays(1); return d }

    /** Item 1: a stop Boss made smaller themselves in Kite is kept at their size - never grown back - and its size is kept as the leg's stop's. */
    @Test fun aStopBossResizedInKiteIsNeverGrownBack() {
        val id = twoLotLeg()
        startLive(id)
        val stop = workingStops().single()
        assertEquals(150, stop.qty)
        kite.order(stop.id).qty = 75                                       // Boss cuts it to one lot in Kite
        val puts = { kite.requests.count { it.method == "PUT" && it.path.endsWith("/${stop.id}") } }
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertEquals("their size kept", 75, kite.order(stop.id).qty)
        assertEquals("never modified back", 0, puts())
        assertEquals("one stop", 1, workingStops().size)
        assertEquals("told once", 1, logged("stop_resized_by_you").size)
        assertTrue(logged("stop_grown").isEmpty())
        // Restarted: still their size, still not grown.
        Strategies.forgetCacheForTest()
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(75, kite.order(stop.id).qty)
        assertEquals(0, puts())
    }

    /**
     * Item 3: Boss cancels the leg's SL in Kite and, before the next stop check, the run's own exit is due and is refused after it found the
     * stop gone: the stop's end is judged then - Boss's - so no later look rests it again; they are told once.
     */
    @Test fun aStopBossCancelledBeforeTheRunsExitIsJudgedAndNeverRestedAgain() {
        val stop = liveLegWithItsStop()
        kite.order(stop.id).status = "CANCELLED"                           // Boss cancels it in Kite
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)                    // under the stop: the run's own exit is due
        kite.nextPlace(reply = FakeKite.Reply.INPUT_EXCEPTION, message = "Quantity exceeds the freeze limit.")   // and its sale is refused
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("its sale tried once, refused", 1, exits().size)
        assertEquals("nothing sold", 75, kite.positions.getValue("NFO:$NIFTY_CE:MIS").first)
        assertEquals("judged theirs at once", 1, logged("stop_cancelled_by_you").size)
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)                    // back above it: a stop could rest again
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertTrue("never rested again", workingStops().isEmpty())
        assertEquals(1, logged("stop_cancelled_by_you").size)
    }

    /** Item 4a: a GTT placed the day before that fired TODAY (its own time) is never taken for an earlier day's - however long its order is unknown. */
    @Test fun aGttFiredTodayByItsOwnTimeIsNeverTakenForAnEarlierDays() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 60.0, 67.05)
        val oid = kite.triggerGtt(gid)                                     // fired now: its result's time is today's
        runBlocking { Strategies.ageLegStopsForTest(1) }                    // though the stop was placed the day before
        kite.orders.remove(oid)
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) }; later(Strategies.EARLY_UNREAD_AGREE_MS) }
        assertTrue("never let go", logged("stop_fired_earlier").isEmpty())
        assertEquals("no stop beside its order", listOf(gid), kite.gtts.keys.toList())
        assertTrue(sells().none { it.form["tag"] == "iraalgostrat" })
    }

    /** Item 4a: ... and one whose own time is an earlier day's is judged so, whatever day the stop's record says. */
    @Test fun aGttWhoseOwnFireTimeIsAnEarlierDayIsJudgedByIt() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 60.0, 67.05)
        val oid = kite.triggerGtt(gid)
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        kite.orders.remove(oid)                                            // Kite: "no such order"
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        later(Strategies.GTT_BARE_AGREE_MS + 1_000)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("let go, told once", 1, logged("stop_fired_earlier").size)
    }

    /** Item 4a: Kite's 500 GeneralException for that order is no answer: not let go on two looks, only after the long wait. */
    @Test fun aGeneralExceptionForTheOrderIsNoAnswer() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 60.0, 67.05)
        val oid = kite.triggerGtt(gid)
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        kite.hideFromBook(oid, 1_000)
        kite.kiteErrors["/orders/$oid"] = 500 to "GeneralException"
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) }; later(Strategies.GTT_BARE_AGREE_MS + 1_000) }
        assertTrue("not on a few looks", logged("stop_fired_earlier").isEmpty())
        later(Strategies.EARLY_UNREAD_AGREE_MS)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("after the long wait", 1, logged("stop_fired_earlier").size)
    }

    /** Item 4b: a count begun while the order was unknown ends once the order shows again - a later unknown spell counts from its own start. */
    @Test fun anEarlierDaysCountEndsWhenTheGttIsNoLongerOne() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 60.0, 67.05)
        val oid = kite.triggerGtt(gid)
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        val order = kite.orders.remove(oid)!!
        runBlocking { Strategies.manageOnly(compromised = false) }        // the count starts
        later(Strategies.GTT_BARE_AGREE_MS + 1_000)
        kite.orders[oid] = order                                           // the order shows in today's book: no longer such a one
        runBlocking { Strategies.manageOnly(compromised = false) }
        later(Strategies.GTT_BARE_AGREE_MS + 1_000)
        kite.orders.remove(oid)                                            // unknown again
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("a fresh count, not the old one", logged("stop_fired_earlier").isEmpty())
        later(Strategies.GTT_BARE_AGREE_MS + 1_000)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(1, logged("stop_fired_earlier").size)
    }

    /** An earlier day's GTT stop that sold the whole leg, its order unknown now and nothing held ([zeroPrice]: its order's price not given): (strategy, its limit). */
    private fun earlierDaysGttSoldAll(zeroPrice: Boolean): Pair<Long, Double> {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 66.95, 67.05)
        val oid = kite.triggerGtt(gid)                                     // its LIMIT under the bid: it filled, nothing held now
        assertEquals(0, kite.positions.getValue("NFO:$NIFTY_CE:NRML").first)
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        val limit = kite.gtts.getValue(gid).getJSONArray("orders").getJSONObject(0).getDouble("price")
        assertEquals("it filled at its limit", limit, kite.order(oid).avg, 1e-9)
        if (zeroPrice) kite.gtts.getValue(gid).getJSONArray("orders").getJSONObject(0).put("price", 0.0)
        kite.orders.remove(oid)
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        later(Strategies.GTT_BARE_AGREE_MS + 1_000)
        runBlocking { Strategies.manageOnly(compromised = false) }        // (its own follow-up read agrees nothing is held)
        runBlocking { Strategies.manageOnly(compromised = false) }
        return id to limit
    }

    /**
     * Item 4c (round 3, item 3): ... is closed in the run as sold by that stop at NO price - nothing booked from its order's limit, so no P&L,
     * banked figure or basket risk runs on a number no fill gave - and the limit is said only in the critical alert. Nothing sold.
     */
    @Test fun anEarlierDaysGttThatSoldTheLegIsClosedAtNoPriceItsLimitOnlyInTheAlert() {
        val (id, limit) = earlierDaysGttSoldAll(zeroPrice = false)
        val l = leg(id)
        assertEquals("closed in the run", 0, l.qty)
        assertNull("no price booked", l.exitAvg)
        assertEquals("no P&L from the limit", 0.0, l.realizedPnl, 1e-9)
        val said = logged("stop_fired_earlier").single()
        val px = "%.2f".format(java.util.Locale.ENGLISH, limit)
        assertTrue(said.message, said.message.contains("UNKNOWN price") && said.message.contains("no P&L booked"))
        assertTrue(said.message, said.message.contains("a limit of ₹$px: it may have sold at ₹$px or better"))
        assertTrue(said.message, said.message.contains("Check $NIFTY_CE in Kite"))
        assertEquals("an alert to check", "critical", said.severity)
        assertTrue(sells().none { it.form["tag"] == "iraalgostrat" })
    }

    /** Item 4c: ... with no price known, closed as sold at an UNKNOWN price - no P&L booked for it, flagged, Boss told - never a made-up price. */
    @Test fun anEarlierDaysGttWithNoPriceIsClosedFlaggedAtAnUnknownPrice() {
        val (id, _) = earlierDaysGttSoldAll(zeroPrice = true)
        val l = leg(id)
        assertEquals("closed in the run", 0, l.qty)
        assertNull("no price made up", l.exitAvg)
        assertEquals(0.0, l.realizedPnl, 1e-9)
        val said = logged("stop_fired_earlier").single()
        assertEquals("critical", said.severity)
        assertTrue(said.message, said.message.contains("UNKNOWN price"))
        assertFalse("no limit made up", said.message.contains("limit"))
        assertTrue(sells().none { it.form["tag"] == "iraalgostrat" })
    }

    /** Nit: the own-cancel marks last two trading days - a Friday's to Monday and Tuesday - never a day's clock. */
    @Test fun ownCancelMarksAreKeptByTradingDays() {
        var mon = Market.today()
        while (!(mon.dayOfWeek == java.time.DayOfWeek.MONDAY && Market.isTradingDay(mon) && Market.isTradingDay(mon.minusDays(3)) && Market.isTradingDay(mon.minusDays(4)) &&
                Market.isTradingDay(mon.plusDays(1)) && Market.isTradingDay(mon.plusDays(2)))) mon = mon.plusDays(1)
        assertEquals("Monday keeps from Thursday on", mon.minusDays(4), Strategies.keepMarksFrom(mon))
        fun at(d: java.time.LocalDate) { Market.testClock = java.time.Clock.fixed(d.atTime(11, 0).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST) }
        at(mon.minusDays(3))                                               // Friday
        Strategies.markOwnCancels(listOf("880000001"))
        runBlocking { Strategies.tagOwner("kite:x", "x") }                 // (a save: the mark goes in the book)
        at(mon)
        runBlocking { Strategies.tagOwner("kite:x", "x") }
        assertTrue("Friday's mark on Monday", runBlocking { Strategies.ownCancelForTest("880000001") })
        at(mon.plusDays(2))                                                // Wednesday: three trading days on
        runBlocking { Strategies.tagOwner("kite:x", "y") }
        assertFalse(runBlocking { Strategies.ownCancelForTest("880000001") })
    }

    /** Nit: the square-offs' marks are bounded by dropping the oldest: the newest is still taken back, the oldest no longer is. */
    @Test fun theSquareOffsMarksDropTheOldestFirst() {
        val ids = (0..500).map { "77000$it" }
        Strategies.markOwnCancels(ids)
        Strategies.unmarkOwnCancels(listOf(ids.last()))
        assertFalse("the newest taken back", runBlocking { Strategies.ownCancelForTest(ids.last()) })
        Strategies.unmarkOwnCancels(listOf(ids.first()))
        assertTrue("the oldest no longer known as a square-off's: kept", runBlocking { Strategies.ownCancelForTest(ids.first()) })
        assertTrue(runBlocking { Strategies.ownCancelForTest(ids[250]) })
    }

    /** Nit: an MCX contract's stop end judged at Zerodha's own time of it, by MCX's hours. */
    @Test fun anMcxStopEndIsJudgedAtItsOwnTimeByMcxHours() {
        var d = Market.today()
        while (McxMarket.calendarQuick().window(d) == null || d.dayOfWeek == java.time.DayOfWeek.FRIDAY) d = d.plusDays(1)
        Market.testOrdersAnyTime = false
        try {
            assertEquals("20:00, MCX open, NSE shut: Boss's", Strategies.StopEnd.BOSS, Strategies.stopEndOf(false, "CANCELLED", "", d.atTime(20, 0), "MCX"))
            assertEquals("02:00, MCX shut: Zerodha's", Strategies.StopEnd.ZERODHA, Strategies.stopEndOf(false, "CANCELLED", "", d.plusDays(1).atTime(2, 0), "MCX"))
            assertEquals("NSE at 20:00: Zerodha's", Strategies.StopEnd.ZERODHA, Strategies.stopEndOf(false, "CANCELLED", "", d.atTime(20, 0), "NFO"))
        } finally { Market.testOrdersAnyTime = true }
    }

    /** Nit: a trail never moves the leg's stop while Boss is exiting the contract from the app - their exit may have made it a LIMIT sale. */
    @Test fun aTrailNeverTurnsBossesLimitExitBackIntoAStop() {
        midSession()
        live()
        val d = AutomationSupport.strategy("Test basket", Position.B to OptionType.CE, product = com.optionslab.engine.strategy.Product.MIS)
        assertNull(runBlocking { Strategies.save(d.copy(legs = d.legs.map { it.copy(trail = com.optionslab.engine.strategy.Trail(10.0, 5.0)) })) })
        val id = runBlocking { Strategies.all().single { it.def.name == "Test basket" }.def.id }
        runBlocking { Strategies.setLiveEnabled(id, true) }
        legStrategy = id
        startLive(id)
        val stop = workingStops().single()
        val trigger0 = stop.trigger
        ArmLive.handsOff("NFO", NIFTY_CE)                                  // Boss exits it from the app: their exit made the stop a LIMIT sale
        kite.order(stop.id).apply { type = "LIMIT"; price = 119.0; status = "OPEN" }
        kite.quote("NFO:$NIFTY_CE", 125.0, 124.95, 125.05)                 // up: the leg's trail moves its stop
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("the trail moved the leg's own level", (leg(id).effectiveSl ?: 0.0) > trigger0)
        assertEquals("their sale left a LIMIT", "LIMIT", kite.order(stop.id).type)
        assertTrue("never modified", kite.requests.none { it.method == "PUT" && it.path.endsWith("/${stop.id}") })
        ArmLive.handsBack("NFO", NIFTY_CE)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("nor once they are done: it is no longer a stop", "LIMIT", kite.order(stop.id).type)
    }

    /** Item 1 (review): a stop the app's own paths resized - a sweep that made it a LIMIT sale, Boss's exit from the app - is never taken for their cut. */
    @Test fun aStopResizedByTheAppsOwnPathsIsNeverTakenForBossesCut() {
        val id = twoLotLeg()
        startLive(id)
        val stop = workingStops().single()
        ArmLive.handsOff("NFO", NIFTY_CE)                                  // Boss's exit from the app takes it in hand
        kite.order(stop.id).qty = 75
        runBlocking { Strategies.manageOnly(compromised = false) }
        ArmLive.handsBack("NFO", NIFTY_CE)
        kite.order(stop.id).apply { type = "LIMIT"; price = 69.0; status = "OPEN" }   // a sweep turned it into a LIMIT sale
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue(logged("stop_resized_by_you").isEmpty())
    }

    /** Item 1 (review): the app's own shrink whose answer was lost, that did go through, is recorded at its size - never taken for Boss's cut. */
    @Test fun anAppShrinkWhoseAnswerWasLostIsNeverTakenForBossesCut() {
        val id = twoLotLeg()
        startLive(id)
        val stop = workingStops().single()
        kite.position(NIFTY_CE, 75, 100.0, product = "MIS")               // part sold by hand: the stop shrinks
        kite.drop += "/orders/regular/${stop.id}"                          // its modify's answer lost
        runBlocking { Strategies.manageOnly(compromised = false) }
        kite.drop.clear()
        kite.order(stop.id).qty = 75                                       // it had gone through after all
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertTrue(logged("stop_resized_by_you").isEmpty())
        assertEquals(75, kite.order(stop.id).qty)
    }

    /**
     * Item 3 (review): Boss's cancel found by an exit that then sells the leg is still noted theirs (that sale could yet be refused at the
     * exchange: no stop would come back), said as the run's exit selling the leg - sold once.
     */
    @Test fun aStopBossCancelledFoundByAnExitThatSellsIsNotedAsSelling() {
        val stop = liveLegWithItsStop()
        kite.order(stop.id).status = "CANCELLED"
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(listOf("iraalgostrat" to "75"), exits())
        assertEquals("flat", 0, kite.positions.getValue("NFO:$NIFTY_CE:MIS").first)
        val said = logged("stop_cancelled_by_you").single().message
        assertTrue(said, said.contains("the run's own exit is selling the leg now"))
    }

    /**
     * Item 4c (review; round 3, item 3): an earlier day's GTT that covered fewer lots than the leg (Boss's own sale covered the rest, and sold
     * it): the leg closed at no price, the GTT's own quantity and limit said in the alert, and the rest said to be closed some other way.
     */
    @Test fun anEarlierDaysGttForFewerLotsIsSaidForItsOwnQuantityOnly() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "NRML")
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        assertEquals(75, gttQty(kite.gtts.getValue(gid)))
        kite.quote("NFO:$NIFTY_CE", 67.0, 66.95, 67.05)
        val oid = kite.triggerGtt(gid)                                     // its lot sold at its limit
        kite.fill(other, 150.0)                                            // and Boss's own sale sold the other
        assertEquals(0, kite.positions.getValue("NFO:$NIFTY_CE:NRML").first)
        val limit = kite.gtts.getValue(gid).getJSONArray("orders").getJSONObject(0).getDouble("price")
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        kite.orders.remove(oid)
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        later(Strategies.GTT_BARE_AGREE_MS + 1_000)
        runBlocking { Strategies.manageOnly(compromised = false) }
        runBlocking { Strategies.manageOnly(compromised = false) }
        val l = leg(id)
        assertEquals("closed in the run", 0, l.qty)
        assertEquals("nothing booked from the limit", 0.0, l.realizedPnl, 1e-9)
        assertNull(l.exitAvg)
        val said = logged("stop_fired_earlier").single()
        assertEquals("critical", said.severity)
        val px = "%.2f".format(java.util.Locale.ENGLISH, limit)
        assertTrue(said.message, said.message.contains("the GTT's order was for 75 at a limit of ₹$px") &&
            said.message.contains("the other 75 were closed some other way") && said.message.contains("(150 $NIFTY_CE)"))
    }

    // ---- 23 Nov 2026 (B6-H round 3) ------------------------------------------------------------------------------------------------------

    /** Item 4: before the open, a positions reply lacking the leg's row is no reading - the earlier day's leg is not closed, its record stays. */
    @Test fun anEarlierDaysLegIsNotClosedBeforeTheOpenOnAReplyMissingItsRow() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 66.95, 67.05)
        val oid = kite.triggerGtt(gid)
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        kite.orders.remove(oid)                                            // Kite: "no such order"
        kite.positions.remove("NFO:$NIFTY_CE:NRML")                        // and the reply before the open lacks the row
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        val sys = java.time.Clock.system(com.optionslab.engine.IST)
        val early = Market.today().atTime(8, 30).atZone(com.optionslab.engine.IST)
        Market.testClock = java.time.Clock.offset(sys, java.time.Duration.between(java.time.ZonedDateTime.now(sys), early))
        assertFalse(Market.isOpen())
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) }; later(Strategies.GTT_BARE_AGREE_MS + 1_000) }
        assertEquals("not closed", 150, leg(id).qty)
        assertTrue(logged("stop_fired_earlier").isEmpty())
        assertTrue("its stop record stays", runBlocking { Strategies.legStopsForTest() }.values.single().startsWith("gtt:$gid|"))
        midSession()                                                       // in market hours
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertEquals("closed then", 0, leg(id).qty)
        assertEquals(1, logged("stop_fired_earlier").size)
        assertTrue(runBlocking { Strategies.legStopsForTest() }.isEmpty())
        assertTrue(sells().none { it.form["tag"] == "iraalgostrat" })
    }

    /**
     * Item 4 (round 4): a read that left the leg's own row out as malformed is no reading: the earlier day's leg neither closes nor lets its
     * stop go (which would leave it open in the run with no way out) - it waits, its record kept, and Boss is told why after a while; on a
     * good read it is closed. (Another contract's malformed row holds nothing up: below.)
     */
    @Test fun anEarlierDaysLegWaitsWhileItsOwnRowIsUnreadable() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 66.95, 67.05)
        val oid = kite.triggerGtt(gid)                                     // it sold the leg: nothing held now
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        kite.orders.remove(oid)
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        kite.extraNetRows = listOf(org.json.JSONObject().put("tradingsymbol", NIFTY_CE).put("exchange", "NFO").put("product", "NRML"))
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) }; later(Strategies.GTT_BARE_AGREE_MS + 1_000) }
        assertEquals("not closed", 150, leg(id).qty)
        assertTrue("nothing let go or said", logged("stop_fired_earlier").isEmpty())
        assertTrue("its stop record stays", runBlocking { Strategies.legStopsForTest() }.values.single().startsWith("gtt:$gid|"))
        later(Strategies.EARLY_WAIT_SAY_MS)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val told = logged("stop_fired_earlier_waiting").single()
        assertTrue(told.message, told.message.contains("left its row out as unreadable") && told.message.contains("exits are refused") &&
            told.message.contains("close it there yourself") && told.message.contains("is not known"))
        later(Strategies.EARLY_WAIT_REPEAT_MS - 60_000)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("not again so soon", 1, logged("stop_fired_earlier_waiting").size)
        later(60_000)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("again after a while", 2, logged("stop_fired_earlier_waiting").size)
        kite.extraNetRows = emptyList()                                    // a good read
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertEquals("closed then", 0, leg(id).qty)
        assertEquals("critical", logged("stop_fired_earlier").single().severity)
    }

    /** Item 4 (round 4): another contract's malformed row tells nothing of this leg's: its earlier day's close goes on. */
    @Test fun anotherContractsMalformedRowDoesNotHoldTheEarlierDaysClose() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 66.95, 67.05)
        val oid = kite.triggerGtt(gid)
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        kite.orders.remove(oid)
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        kite.extraNetRows = listOf(org.json.JSONObject().put("tradingsymbol", NIFTY_PE).put("exchange", "NFO").put("product", "NRML"))
        runBlocking { Strategies.manageOnly(compromised = false) }
        later(Strategies.GTT_BARE_AGREE_MS + 1_000)
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertEquals("closed", 0, leg(id).qty)
        assertEquals(1, logged("stop_fired_earlier").size)
    }

    /**
     * Item 4 (round 4): a close that waits for the market to open is told to Boss once it has waited [Strategies.EARLY_WAIT_SAY_MS], naming
     * why - once only: nothing is wrong, it waits for the open. (A wait for any other reason is told again every
     * [Strategies.EARLY_WAIT_REPEAT_MS] at most: above.)
     */
    @Test fun anEarlierDaysCloseWaitingForTheOpenIsToldOnce() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 66.95, 67.05)
        val oid = kite.triggerGtt(gid)
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        kite.orders.remove(oid)
        kite.positions.remove("NFO:$NIFTY_CE:NRML")
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        val sys = java.time.Clock.system(com.optionslab.engine.IST)
        val early = Market.today().atTime(7, 30).atZone(com.optionslab.engine.IST)
        Market.testClock = java.time.Clock.offset(sys, java.time.Duration.between(java.time.ZonedDateTime.now(sys), early))
        val look = { runBlocking { Strategies.manageOnly(compromised = false) } }
        look(); later(Strategies.GTT_BARE_AGREE_MS + 1_000); look()           // counted: waits from here
        later(Strategies.EARLY_WAIT_SAY_MS - 60_000); look()
        assertTrue("not before it has waited a while", logged("stop_fired_earlier_waiting").isEmpty())
        later(60_000); look()
        val told = logged("stop_fired_earlier_waiting").single()
        assertTrue(told.message, told.message.contains("the market is shut") && told.message.contains("exits are refused") &&
            told.message.contains("Zerodha shows none of $NIFTY_CE held"))
        assertEquals("warn", told.severity)
        later(Strategies.EARLY_WAIT_REPEAT_MS + 60_000); look()
        assertEquals("not again while it waits for the open", 1, logged("stop_fired_earlier_waiting").size)
        assertEquals("still not closed", 150, leg(id).qty)
    }

    /**
     * Item 4: in hours too, a reply lacking the row of another of the run's legs that Zerodha holds under a stop resting now (its GTT active)
     * left the account's carried rows out: the earlier day's leg waits; once the reply shows that row, it is closed.
     */
    @Test fun anEarlierDaysLegWaitsWhileTheReplyLacksTheRunsOtherCarriedRow() {
        midSession()
        live()
        val id = saved(Position.B to OptionType.CE, Position.B to OptionType.PE, lots = 2, product = com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("a GTT for each leg", 2, kite.gtts.size)
        val sym = { g: org.json.JSONObject -> g.getJSONArray("orders").getJSONObject(0).getString("tradingsymbol") }
        val ceGid = kite.gtts.entries.single { sym(it.value) == NIFTY_CE }.key
        val peGid = kite.gtts.entries.single { sym(it.value) == NIFTY_PE }.key
        kite.quote("NFO:$NIFTY_CE", 67.0, 66.95, 67.05)
        val oid = kite.triggerGtt(ceGid)
        gttFiredAt(ceGid, dayBefore().atTime(14, 0))
        kite.orders.remove(oid)
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        val pe = kite.positions.remove("NFO:$NIFTY_PE:NRML")!!             // the reply lacks the PE leg's carried row
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) }; later(Strategies.GTT_BARE_AGREE_MS + 1_000) }
        val closed = { entry(id).run!!.legs.values.count { it.status == "closed" } }
        assertEquals("waits", 0, closed())
        assertTrue(logged("stop_fired_earlier").isEmpty())
        kite.positions["NFO:$NIFTY_PE:NRML"] = pe                          // the row shows again
        repeat(3) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertEquals("closed then", 1, closed())
        assertEquals(1, logged("stop_fired_earlier").size)
        assertEquals("the PE leg's GTT untouched", "active", kite.gtts.getValue(peGid).getString("status"))
    }

    /**
     * Item 1: a shrink Zerodha applied though Kite answered it with a 500 GeneralException is never read as Boss's cut: the smaller size was
     * in the book on disk before the modify went (so a kill before the pass saves loses nothing), and kept on that unsure answer.
     */
    @Test fun aShrinkThatTookThoughItsAnswerFailedIsNeverReadAsBossesCut() {
        val id = twoLotLeg()
        startLive(id)
        val stop = workingStops().single()
        assertEquals(150, stop.qty)
        kite.position(NIFTY_CE, 75, 100.0, product = "MIS")                // one lot sold by hand: the stop must shrink to 75
        Strategies.writeInlineForTest = true
        var onDisk: String? = null
        kite.beforeNext("/orders/regular/${stop.id}") {
            val f = Strategies.filesForErase().first()
            val o = org.json.JSONObject(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
            onDisk = o.optJSONObject("legStops")?.let { m -> m.keys().asSequence().map { m.getString(it) }.singleOrNull() }
            kite.order(stop.id).qty = 75                                   // Zerodha applies it ...
        }
        kite.kiteErrors["/orders/regular/${stop.id}"] = 500 to "GeneralException"   // ... and answers with an error
        try { runBlocking { Strategies.manageOnly(compromised = false) } } finally { kite.kiteErrors.remove("/orders/regular/${stop.id}") }
        assertEquals("75 in the book on disk before the modify went", 75, onDisk?.split('|')?.getOrNull(3)?.toIntOrNull())
        AutomationSupport.reloadFromDisk(Strategies)                       // as after a restart
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertTrue("never read as Boss's cut", logged("stop_resized_by_you").isEmpty())
        assertEquals(75, kite.order(stop.id).qty)
        assertEquals("one stop", 1, workingStops().size)
        assertEquals(75, runBlocking { Strategies.legStopsForTest() }.values.single().split('|')[3].toInt())
    }

    /** Item 3 (round 4): a shrink Kite refused outright (its InputException: nothing changed) puts the record back; the next look shrinks it. */
    @Test fun aShrinkKiteRefusedOutrightPutsTheRecordBack() {
        val id = twoLotLeg()
        startLive(id)
        val stop = workingStops().single()
        assertEquals(150, stop.qty)
        kite.position(NIFTY_CE, 75, 100.0, product = "MIS")                // one lot sold by hand: the stop must shrink to 75
        kite.kiteErrors["/orders/regular/${stop.id}"] = 400 to "InputException"
        try { runBlocking { Strategies.manageOnly(compromised = false) } } finally { kite.kiteErrors.remove("/orders/regular/${stop.id}") }
        assertEquals("nothing changed at Zerodha", 150, kite.order(stop.id).qty)
        assertEquals("the record back at its size", 150, runBlocking { Strategies.legStopsForTest() }.values.single().split('|')[3].toInt())
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("shrunk on the next look", 75, kite.order(stop.id).qty)
        assertEquals(75, runBlocking { Strategies.legStopsForTest() }.values.single().split('|')[3].toInt())
        assertTrue("never a cut", logged("stop_resized_by_you").isEmpty())
    }

    /** Item 2: a grow Kite accepted but Zerodha did not apply (it still shows the size it grew from) is no cut of Boss's: tried again later. */
    @Test fun aGrowKiteAcceptedButDidNotApplyIsNeverReadAsBossesCut() {
        val id = twoLotLeg()
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "MIS")
        startLive(id)
        val stop = workingStops().single()
        assertEquals(75, stop.qty)
        kite.order(other).status = "CANCELLED"                             // Boss takes their own sale back: room for the whole leg
        kite.afterNext("/orders/regular/${stop.id}") { kite.order(stop.id).qty = 75 }   // accepted, but Zerodha keeps 75
        val puts = { kite.requests.count { it.method == "PUT" && it.path.endsWith("/${stop.id}") } }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("the grow sent", 1, puts())
        assertEquals("Zerodha still shows 75", 75, kite.order(stop.id).qty)
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertTrue("never read as Boss's cut", logged("stop_resized_by_you").isEmpty())
        assertEquals("not tried again at once", 1, puts())
        Strategies.forgetGrowWaitsForTest()                                // its wait over
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("tried again", 2, puts())
        assertEquals("grown", 150, kite.order(stop.id).qty)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue(logged("stop_resized_by_you").isEmpty())
        assertEquals("one stop", 1, workingStops().size)
        assertEquals(listOf("150", ""), runBlocking { Strategies.legStopsForTest() }.values.single().split('|').let { listOf(it[3], it.getOrElse(5) { "" }) })
    }

    /**
     * Item 2 (round 4): a grow Kite accepted that the exchange refused at once is no cut of Boss's - though the phone's clock is 10 s behind
     * Zerodha's, so the refusal's time reads after the grow: its history never showed the new size. It is tried again later.
     */
    @Test fun aGrowRefusedAtOnceWithThePhonesClockBehindIsNoCut() {
        val id = twoLotLeg()
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "MIS")
        startLive(id)
        val stop = workingStops().single()
        kite.order(other).status = "CANCELLED"                             // room for the whole leg
        kite.refuseNextModifyLater(stop.id, at = Market.now().toLocalDateTime().plusSeconds(10))
        val puts = { kite.requests.count { it.method == "PUT" && it.path.endsWith("/${stop.id}") } }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(1, puts())
        assertEquals(75, kite.order(stop.id).qty)
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertTrue("never read as Boss's cut", logged("stop_resized_by_you").isEmpty())
        Strategies.forgetGrowWaitsForTest()
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("tried again and grown", 150, kite.order(stop.id).qty)
        assertTrue(logged("stop_resized_by_you").isEmpty())
    }

    /** Item 2 (round 4): the history rule - the old size, then the new at the exchange, then the old again; a validation state never counts. */
    @Test fun aGrowIsTakenBackOnlyWhenItsHistoryShowsItThenTheOldSize() {
        fun row(qty: Int, status: String = "TRIGGER PENDING") = Broker.OrderRow("1", NIFTY_CE, "SELL", qty, 0, 69.0, 0.0, status, "", "")
        assertTrue("grown, then cut back", Strategies.grownThenBack(listOf(row(75, "OPEN PENDING"), row(75), row(150), row(75)), 75, 150))
        assertFalse("never took", Strategies.grownThenBack(listOf(row(75, "OPEN PENDING"), row(75)), 75, 150))
        assertFalse("its size before an earlier shrink is not the grow's", Strategies.grownThenBack(listOf(row(150), row(75), row(75)), 75, 150))
        assertFalse("a refused modify's validation state", Strategies.grownThenBack(listOf(row(75), row(150, "MODIFY VALIDATION PENDING"), row(75)), 75, 150))
        assertTrue("shrunk, grown, cut back", Strategies.grownThenBack(listOf(row(150), row(75), row(150), row(75)), 75, 150))
    }

    /** Item 2 (round 4, review): a stop shrunk, then grown back with the grow refused, is no cut of Boss's - its first size is not the grow's. */
    @Test fun aShrunkStopWhoseGrowWasRefusedIsNoCut() {
        val id = twoLotLeg()
        startLive(id)
        val stop = workingStops().single()
        assertEquals(150, stop.qty)
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "MIS")   // Boss's own sale covers a lot: the stop shrinks
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(75, kite.order(stop.id).qty)
        kite.order(other).status = "CANCELLED"                             // their sale taken back: it grows - refused at the exchange
        kite.refuseNextModifyLater(stop.id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertTrue("never read as Boss's cut", logged("stop_resized_by_you").isEmpty())
        Strategies.forgetGrowWaitsForTest()
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("grown on the retry", 150, kite.order(stop.id).qty)
    }

    /**
     * Item 2 (round 4, self-review): a stop grown and shrunk by the app before - its history already shows the old size, the new and the old
     * again - whose next grow is refused is no cut of Boss's: only what came after that grow was sent is judged.
     */
    @Test fun aRefusedGrowAfterAnEarlierGrowAndShrinkIsNoCut() {
        val id = twoLotLeg()
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "MIS")
        startLive(id)
        val stop = workingStops().single()
        assertEquals(75, stop.qty)
        kite.order(other).status = "CANCELLED"
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("grown", 150, kite.order(stop.id).qty)
        runBlocking { Strategies.manageOnly(compromised = false) }        // seen at Zerodha
        val again = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "MIS")
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("shrunk by the app", 75, kite.order(stop.id).qty)
        kite.order(again).status = "CANCELLED"
        kite.refuseNextModifyLater(stop.id)                                // the next grow is refused at the exchange
        Strategies.forgetGrowWaitsForTest()
        runBlocking { Strategies.manageOnly(compromised = false) }
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertTrue("never read as Boss's cut", logged("stop_resized_by_you").isEmpty())
        Strategies.forgetGrowWaitsForTest()
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("grown on the retry", 150, kite.order(stop.id).qty)
    }

    /** Item 2 (round 4): with the stop's history unreadable nothing is decided - no cut, no grow - until a later look reads it. */
    @Test fun aGrowNotSeenWithItsHistoryUnreadableIsNotDecided() {
        val id = twoLotLeg()
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "MIS")
        startLive(id)
        val stop = workingStops().single()
        kite.order(other).status = "CANCELLED"
        kite.refuseNextModifyLater(stop.id)
        val puts = { kite.requests.count { it.method == "PUT" && it.path.endsWith("/${stop.id}") } }
        runBlocking { Strategies.manageOnly(compromised = false) }
        kite.down += "/orders/${stop.id}"
        Strategies.forgetGrowWaitsForTest()
        try { repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } } } finally { kite.down -= "/orders/${stop.id}" }
        assertTrue("no cut", logged("stop_resized_by_you").isEmpty())
        assertEquals("nor grown again", 1, puts())
        assertEquals("the grow still waits to be seen", "75", runBlocking { Strategies.legStopsForTest() }.values.single().split('|')[5])
        runBlocking { Strategies.manageOnly(compromised = false) }                   // read now: it did not take
        Strategies.forgetGrowWaitsForTest()
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(2, puts())
        assertEquals(150, kite.order(stop.id).qty)
        assertTrue(logged("stop_resized_by_you").isEmpty())
    }

    /**
     * Item 2 (round 4): Boss cutting a grown stop back to its old size in Kite is their cut - its history shows the grown size at the exchange
     * and the old one after - whatever the two clocks say.
     */
    @Test fun bossCuttingAGrownStopBackToItsOldSizeIsTheirCut() {
        val id = twoLotLeg()
        val other = kite.restsInKite(NIFTY_CE, "SELL", 75, 150.0, product = "MIS")
        startLive(id)
        val stop = workingStops().single()
        kite.order(other).status = "CANCELLED"
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("grown", 150, kite.order(stop.id).qty)
        // Boss cuts it back in Kite - with the phone's clock 10 s ahead of Zerodha's, so the exchange's time of it reads before the grow.
        kite.modifyInKite(stop.id, 75, at = Market.now().toLocalDateTime().minusSeconds(10))
        val puts = { kite.requests.count { it.method == "PUT" && it.path.endsWith("/${stop.id}") } }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("told it is theirs", 1, logged("stop_resized_by_you").size)
        Strategies.forgetGrowWaitsForTest()
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertEquals("never grown again", 1, puts())
        assertEquals(75, kite.order(stop.id).qty)
    }

    /**
     * Item 5: the run's exit that finds its stop cancelled by Boss sends its sale first - the stop's own row is read only after it, never a
     * round trip under the claim before the sale - and still judges Boss's cancel from that row.
     */
    @Test fun theExitSendsItsSaleBeforeReadingWhoCancelledItsStop() {
        val stop = liveLegWithItsStop()
        kite.order(stop.id).status = "CANCELLED"                           // Boss cancels it in Kite
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)                    // under the stop: the run's own exit is due
        val from = kite.requests.size
        runBlocking { Strategies.manageOnly(compromised = false) }
        val reqs = kite.requests.toList().drop(from)
        val sale = reqs.indexOfFirst { it.method == "POST" && it.path == "/orders/regular" && it.form["order_type"] == "MARKET" }
        assertTrue("the exit's sale went", sale >= 0)
        val reads = reqs.withIndex().filter { it.value.method == "GET" && it.value.path == "/orders/${stop.id}" }.map { it.index }
        // (Before the sale only the two reads that find it not filled and confirm it out - [legStopFilled], [legStopOut].)
        assertEquals("before the sale only the reads confirming it out", 2, reads.count { it < sale })
        assertTrue("its own row read after the sale", reads.any { it > sale })
        val said = logged("stop_cancelled_by_you").single()
        assertTrue(said.message, said.message.contains("the run's own exit is selling the leg now"))
    }

    /** Item 5: ... its row unreadable then: judged on the next look from the books (here the same pass's stop check) - never rested again. */
    @Test fun aStopWhoseRowCannotBeReadAfterTheExitIsJudgedFromTheBooks() {
        val stop = liveLegWithItsStop()
        kite.order(stop.id).status = "CANCELLED"                           // Boss cancels it in Kite
        kite.quote("NFO:$NIFTY_CE", 60.0, 59.95, 60.05)
        kite.nextPlace(reply = FakeKite.Reply.INPUT_EXCEPTION, message = "Quantity exceeds the freeze limit.")   // its sale refused
        // (Read twice to find it not filled and confirm it out - [legStopFilled], [legStopOut] - then its row unreadable.)
        kite.afterNext("/orders/${stop.id}") { kite.afterNext("/orders/${stop.id}") { kite.down += "/orders/${stop.id}" } }
        try { runBlocking { Strategies.manageOnly(compromised = false) } } finally { kite.down -= "/orders/${stop.id}" }
        assertEquals("its sale tried, refused", 1, exits().size)
        assertEquals("nothing sold", 75, kite.positions.getValue("NFO:$NIFTY_CE:MIS").first)
        assertEquals("judged Boss's, from the books", 1, logged("stop_cancelled_by_you").size)
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        repeat(2) { runBlocking { Strategies.manageOnly(compromised = false) } }
        assertTrue("never rested again", workingStops().isEmpty())
        assertEquals(1, logged("stop_cancelled_by_you").size)
    }

    /** Item 5: an earlier day's count goes with its stop record. */
    @Test fun anEarlierDaysCountGoesWithItsStopRecord() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$NIFTY_CE", 67.0, 60.0, 67.05)
        val oid = kite.triggerGtt(gid)                                     // its LIMIT not reached: nothing sold, the leg still held
        gttFiredAt(gid, dayBefore().atTime(14, 0))
        kite.orders.remove(oid)
        kite.quote("NFO:$NIFTY_CE", 72.0, 71.95, 72.05)
        runBlocking { Strategies.manageOnly(compromised = false) }        // the count starts
        assertTrue(Strategies.earlyCountsForTest().isNotEmpty())
        kite.gtts.getValue(gid).put("status", "deleted")                   // Boss deletes the GTT in Kite: its record goes on this look
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue(runBlocking { Strategies.legStopsForTest() }.isEmpty())
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("the count went with it", Strategies.earlyCountsForTest().isEmpty())
    }

    /** Item 1 (review): a GTT stop's shrink that could not even be tried (no price at that moment) is tried again - never recorded as done. */
    @Test fun aGttShrinkNotTriedIsTriedAgainNeverRecordedDone() {
        val id = twoLotLeg(com.optionslab.engine.strategy.Product.NRML)
        startLive(id)
        runBlocking { Strategies.manageOnly(compromised = false) }
        val gid = kite.gtts.keys.single()
        assertEquals(150, gttQty(kite.gtts.getValue(gid)))
        kite.position(NIFTY_CE, 75, 100.0)                                 // one lot sold by hand: the GTT must shrink
        kite.quotes.remove("NFO:$NIFTY_CE")                                // no price this look: the shrink cannot be worked out
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals(150, gttQty(kite.gtts.getValue(gid)))
        kite.quote("NFO:$NIFTY_CE", 100.0, 99.95, 100.05)
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("shrunk on the next look", 75, gttQty(kite.gtts.getValue(gid)))
    }

    // ---- B6-B round 9 (with B6-H): Boss's own exit from the app marks the leg stops it takes out -------------------------------------

    /** Boss's exit from the app for the whole row, sent and answered as [answer] says; the send's end awaited, then [after] on its model. */
    private fun bossExit(answer: () -> Unit, after: (com.optionslab.app.ui.AppModel) -> Unit = {}) {
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val m = com.optionslab.app.testing.BrokerArea.model(context as android.app.Application, store)
            m.loadAccount(quiet = true)
            com.optionslab.app.testing.BrokerArea.await("the account") { m.account.value as? com.optionslab.app.ui.Load.Done }
            m.planSquareOff(runBlocking { Broker.positions() }.first { it.symbol == NIFTY_CE })
            com.optionslab.app.testing.BrokerArea.await("the exit plan") { (m.plan.value as? com.optionslab.app.ui.Load.Done)?.value }
            answer()
            m.sendPlan()
            com.optionslab.app.testing.BrokerArea.await("the send", 40_000) {
                m.sending.value.takeIf { it is com.optionslab.app.ui.Load.Failed || it is com.optionslab.app.ui.Load.Done } }
            com.optionslab.app.testing.BrokerArea.settle(1_500)
            after(m)
        } finally { store.clear() }
    }

    /**
     * Boss's exit from the app takes a leg's stop out (cancelled: more is held than the stop covers), and his sale is refused. The stop is the
     * app's own cancel in the strategies' book: the leg's stop rests again - never read as his cancel in Kite.
     */
    @Test fun bossesExitFromTheAppMarksTheLegsStopTheAppsOwnSoItIsPutBack() {
        val stop = liveLegWithItsStop()
        kite.position(NIFTY_CE, 150, 100.0, product = "MIS")                // his own 75 beside the leg's
        // (Not a margin refusal: that one is taken over and sent again at once.)
        bossExit({ kite.nextPlace(outcome = Outcome.REJECT, message = "Your order price is lower than the current lower circuit limit") })
        assertEquals("taken out for his exit", "CANCELLED", kite.order(stop.id).status)
        assertTrue("marked the app's own", runBlocking { Strategies.ownCancelForTest(stop.id) })
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("put back", 1, workingStops().size)
        assertTrue(logged("stop_cancelled_by_you").isEmpty())
    }

    /**
     * Boss's exit from the app turns the leg's stop into his LIMIT exit (the one resting exit covering it all); that LIMIT ends cancelled
     * unfilled (as when he cancels it stuck). The converted order is the app's own: the leg's stop rests again, never read as his cancel.
     */
    @Test fun aLegsStopTurnedIntoBossesExitIsTheAppsOwnWhenThatExitIsCancelled() {
        val stop = liveLegWithItsStop()
        bossExit({ kite.afterNext("/orders/regular/${stop.id}") { kite.order(stop.id).status = "CANCELLED" } })
        assertEquals("turned into his LIMIT", "LIMIT", kite.order(stop.id).type)
        assertEquals("CANCELLED", kite.order(stop.id).status)
        assertTrue("marked the app's own", runBlocking { Strategies.ownCancelForTest(stop.id) })
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("put back", 1, workingStops().size)
        assertTrue(logged("stop_cancelled_by_you").isEmpty())
    }

    /**
     * Boss's exit from the app turns the leg's stop into his LIMIT, which sticks (working, unfilled). Seen working well past the strategies'
     * re-check, the mark made when it was turned ends (as for any cancel that never took); [then] runs on the model with the stuck leg.
     */
    private fun stuckExit(then: (com.optionslab.app.ui.AppModel, FakeKite.Order) -> Unit) {
        val stop = liveLegWithItsStop()
        runBlocking { Protections.active() }                               // the protections' list read (none of them): never a mark of theirs
        bossExit({ }) { m ->
            assertEquals("turned into his LIMIT", "LIMIT", kite.order(stop.id).type)
            assertTrue("stuck: still working", kite.order(stop.id).working)
            assertEquals(stop.id, m.stuck.value?.orderId)
            assertTrue("a resting exit turned into his", m.stuck.value!!.converted)
            later(Strategies.OWN_CANCEL_RECHECK_MS + 1_000)
            runBlocking { Strategies.manageOnly(compromised = false) }
            assertFalse("seen working well after it was turned: ended", runBlocking { Strategies.ownCancelForTest(stop.id) })
            assertFalse("never a protection's mark on a leg's stop", Protections.isTakenOver(stop.id))
            then(m, stop)
        }
    }

    /**
     * His cancel of that stuck exit from the app ([cancel]: Cancel, or the plan closed) marks it the app's own afresh, so the leg's stop is
     * put back - never read as his cancel in Kite - and the contract is handed back at once (no wait for its hands-off to run out).
     */
    private fun stuckExitCancelledFromTheApp(cancel: (com.optionslab.app.ui.AppModel) -> Unit) {
        stuckExit { m, stop ->
            cancel(m)
            com.optionslab.app.testing.BrokerArea.await("the cancel") { kite.order(stop.id).takeIf { it.status == "CANCELLED" } }
            com.optionslab.app.testing.BrokerArea.settle(500)
            assertTrue("marked afresh before it went", runBlocking { Strategies.ownCancelForTest(stop.id) })
            assertFalse("handed back", ArmLive.handsOffNow("NFO", NIFTY_CE))
        }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertEquals("put back", 1, workingStops().size)
        assertTrue(logged("stop_cancelled_by_you").isEmpty())
    }

    @Test fun aLegsStopTurnedIntoBossesStuckExitIsPutBackWhenHeCancelsItFromTheApp() = stuckExitCancelledFromTheApp { it.cancelStuck() }

    @Test fun aLegsStopTurnedIntoBossesStuckExitIsPutBackWhenHeClosesThePlan() = stuckExitCancelledFromTheApp { it.dismissPlan() }

    /**
     * Boss cancels his stuck exit in Kite himself (its hands-off over), then taps Cancel in the app: Zerodha shows it ended, so the app marks
     * nothing - his cancel stays his, and the leg's stop is not put back.
     */
    @Test fun aStuckExitBossCancelledInKiteFirstStaysHisWhenHeThenCancelsItInTheApp() {
        stuckExit { m, stop ->
            ArmLive.handsBack("NFO", NIFTY_CE)                              // its hands-off ran out
            kite.order(stop.id).status = "CANCELLED"                        // he cancels it in Kite
            m.message.value = null
            m.cancelStuck()
            com.optionslab.app.testing.BrokerArea.await("the refusal") { m.message.value?.takeIf { it.startsWith("Cancel failed") } }
            assertFalse("nothing marked", runBlocking { Strategies.ownCancelForTest(stop.id) })
        }
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("his: not put back", workingStops().isEmpty())
        assertEquals(1, logged("stop_cancelled_by_you").size)
    }

    /** The app's cancel of the stuck exit refused outright (it filled as the cancel went): nothing of the app's was cancelled - marks taken back. */
    @Test fun aStuckExitsCancelRefusedOutrightTakesItsMarksBack() {
        stuckExit { m, stop ->
            kite.fillsAsCancelled(stop.id, 75, 99.0)
            m.message.value = null
            m.cancelStuck()
            com.optionslab.app.testing.BrokerArea.await("the refusal") { m.message.value?.takeIf { it.startsWith("Cancel failed") } }
            assertEquals("COMPLETE", kite.order(stop.id).status)
            assertFalse("taken back", runBlocking { Strategies.ownCancelForTest(stop.id) })
        }
    }
}
