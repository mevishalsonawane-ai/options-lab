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
        assertTrue(kite.requests.none { it.method == "DELETE" })
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
        runBlocking { Strategies.manageOnly(compromised = false) }
        assertTrue("its GTT deleted", kite.gtts.isEmpty())
        assertTrue(sells().isEmpty())
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
        assertEquals("Bot stopped for today: armed strategies will not start.", Strategies.stopForToday(stopRunning = false, compromised = false))
        assertTrue(Strategies.stoppedToday())
        assertEquals("Bot running: armed strategies start at their times.", Strategies.startAgain())
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
}
