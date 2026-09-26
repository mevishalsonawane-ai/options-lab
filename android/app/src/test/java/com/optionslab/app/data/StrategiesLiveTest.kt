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

    /** Save [def] live-enabled and return its id. */
    private fun saved(vararg legs: Pair<Position, OptionType>, lots: Int = 1, sl: Double? = 30.0): Long = runBlocking {
        assertNull(Strategies.save(AutomationSupport.strategy("Test basket", *legs, lots = lots, sl = sl)))
        val id = Strategies.all().single { it.def.name == "Test basket" }.def.id
        Strategies.setLiveEnabled(id, true)
        id
    }

    private fun startLive(id: Long, compromised: Boolean = false) =
        runBlocking { Strategies.start(id, RunMode.LIVE, "manual", confirmedByOwner = true, compromised = compromised) }

    private fun entry(id: Long) = runBlocking { Strategies.all().single { it.def.id == id } }
    private fun sells() = kite.placed.filter { it.form["transaction_type"] == "SELL" }
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

    @Test fun anAutomaticLiveArmStartsAtItsTimeAndSquaresOffAtItsExit() {
        live()
        val id = armedLive(automatic = true)
        at(9, 19); tick()
        assertTrue("nothing before the start time", kite.placed.isEmpty())
        at(9, 21)
        val notes = tick()
        assertTrue(notes.toString(), notes.any { it.contains("scheduled live start") })
        assertEquals("BUY", kite.placed.single().form["transaction_type"])
        assertTrue(entry(id).running)
        at(9, 30); tick()
        assertEquals("started once", 1, kite.placed.size)
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
        assertEquals("Enable live trading for Test basket (Trade → Strategies) before arming it live.", Strategies.setArmed(id, true, RunMode.LIVE))
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
        assertEquals("Positional has no start time. Set its entry time in Trade → Strategies, then arm it.", Strategies.setArmed(pid, true, RunMode.SANDBOX))
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
