package com.optionslab.app.data

import com.optionslab.app.ira.IraNewsTrades
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.LiveMirror
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
 * 20 Nov 2026 (Boss's exception: "try cheaper options increment it 1 by 1"): the cheaper strike on R51 / R72's live copies ([ArmLive])
 * against the fake Kite. The lots cut first; the free cash short of one lot before sending, or Zerodha's definite refusal for funds, buys
 * one lot of the first cheaper strike that fits - its paper trade keeps its own strike - and a lost reply never steps. Every gate holds
 * (the switch off, the kill switch, Stop for today, the order door). The live copy's stop rests and moves up, its paper exit and its
 * square-off sell, and a restart restores it - all on the cheaper option.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class CheaperStrikeArmLiveTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val s52100 = "BANKNIFTY26OCT52100CE"
    private val s52200 = "BANKNIFTY26OCT52200CE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private val contract by lazy { Paper.Contract("BANKNIFTY-TEST-52000CE", "BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|TESTCE") }
    private val legs = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()
    private val r72 = com.optionslab.engine.orb.BreadthDonchianArm.SOURCE
    private val r51 = com.optionslab.engine.orb.DonchianArm.SOURCE

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        // The arm's strike and the strikes further out: 52100 at 150, 52200 at 100, 52300 at 60 (one lot of 30 each).
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_000, "BANKNIFTY26OCT51900CE", "BANKNIFTY", expiry, 51_900.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_002, s52100, "BANKNIFTY", expiry, 52_100.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_003, s52200, "BANKNIFTY", expiry, 52_200.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_004, "BANKNIFTY26OCT52300CE", "BANKNIFTY", expiry, 52_300.0, "CE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        kite.quote("NFO:$s52100", 150.0, 149.95, 150.05)
        kite.quote("NFO:$s52200", 100.0, 99.95, 100.05)
        kite.quote("NFO:BANKNIFTY26OCT52300CE", 60.0, 59.95, 60.05)
        runBlocking { Broker.instruments() }
        // The app's own list (the cheaper option's highs are read from it).
        AutomationSupport.contracts(context, listOf(52_000.0, 52_100.0, 52_200.0).map {
            Upstox.Contract("BANKNIFTY", expiry, it, Right.CE, 30, "NSE_FO|C${it.toInt()}", "BANKNIFTY-TEST-${it.toInt()}CE") })
        IraNewsTrades.paperFirst = false
        ArmLive.testSync = true
        ArmLive.testDefaultOff = false
        ArmLive.testNow = AutomationSupport.earlierToday()
        FillSlippage.resetForTest()
        if (com.optionslab.app.ira.Autopilot.enabled()) { com.optionslab.app.ira.Autopilot.resetForTest(); com.optionslab.app.ira.Autopilot.init(context); com.optionslab.app.ira.Autopilot.testLive = false }
        for (a in LiveMirror.ARMS) if (a.key != LiveMirror.SOLO_MIDDAY) runBlocking { ArmLive.setShadow(a.key, false, pinConfirmed = true, log = false) }
        for (f in LiveMirror.ARMS.map { it.family }.distinct())
            ArmLive.register(f) { legs.entries.filter { e -> LiveMirror.spec(e.value.first)?.family == f }.map { ArmLive.PaperLeg(it.key, it.value.first, it.value.second) } }
        CheaperStrike.resetForTest(on = true)
        kite.requests.clear()
    }

    @After fun down() {
        ArmLive.testSync = false
        ArmLive.testNow = null
        Market.testOrdersAnyTime = true
        Broker.testAtDoor = null
        ArmLive.testLotCap = null
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun ask(key: String, ref: String, qty: Int = 30, stop: Double? = 170.0): ArmLive.Ask {
        legs[ref] = key to qty
        return ArmLive.Ask(key, ref, contract, 1, qty, 200.0, stop = stop)
    }
    /** The app's own name for the call at [k] (as its contract list names it: [Paper.symbolOf]). */
    private fun paperSym(k: Double) = Paper.symbolOf(Upstox.Contract("BANKNIFTY", expiry, k, Right.CE, 30, "NSE_FO|C${k.toInt()}", ""))
    private fun watch() = runBlocking { ArmLive.watch() }
    private fun view() = ArmLive.view.value
    private fun buys() = kite.placed.filter { it.form["transaction_type"] == "BUY" }
    private fun said(t: String): Boolean {
        val end = System.currentTimeMillis() + 10_000
        while (Diag.lines().none { it.contains(t) } && System.currentTimeMillis() < end) Thread.sleep(20)
        return Diag.lines().any { it.contains(t) }
    }

    // ---- the triggers -----------------------------------------------------------------------------------------------------------

    /** Rs 5,000 free cannot pay one lot of 52000 (about Rs 6,000): one lot of 52100 (about Rs 4,500) is bought; the paper strike is kept. */
    @Test fun shortOfOneLotBeforeSendingItBuysOneLotOfTheNextStrike() {
        kite.cash = 5_000.0
        ArmLive.entered(ask(r72, "pre"))
        val buy = buys().single()
        assertEquals(s52100, buy.form["tradingsymbol"]); assertEquals("30", buy.form["quantity"]); assertEquals("LIMIT", buy.form["order_type"])
        val p = view().openReal.single()
        assertEquals(s52100, p.symbol); assertEquals(contract.symbol, p.paperSymbol); assertEquals(sym, p.stepFrom)
        assertEquals(paperSym(52_100.0), p.liveContract)
        // Its stop rests at Zerodha on the cheaper option: the paper's -15% on the live fill.
        val stop = kite.placed.single { it.form["order_type"] == "SL" }
        assertEquals(s52100, stop.form["tradingsymbol"])
        assertEquals(p.stopTrigger!!, stop.form["trigger_price"]!!.toDouble(), 1e-9)
        assertEquals(com.optionslab.engine.Kite.onTick(p.entry * 0.85, 0.05, com.optionslab.engine.Kite.Side.SELL), p.stopTrigger!!, 1e-9)
        val r = CheaperStrike.recsOf().single()
        assertTrue(r.bought); assertEquals(1, r.steps); assertEquals("BANKNIFTY 52000 CE", r.from); assertEquals("52100 CE", r.to)
        assertTrue(said("${LiveMirror.spec(r72)!!.label}: funds short for BANKNIFTY 52000 CE, bought 52100 CE (1 lot)"))
        assertTrue("the stepped buy fitted: the funds check never saw a short entry", FundsCheck.recsOf().isEmpty())
        assertTrue(view().log.toString(), view().log.any { it.contains("bought $s52100 30") })
    }

    /** Boss: "lots first, then strikes" - 3 lots set and 2 fitting buys 2 of the arm's own strike; none fitting steps to 1 lot cheaper. */
    @Test fun theLotsCutRunsFirstAndTheCheaperStrikeBuysOneLot() {
        AppSettings.save(AppSettings.load().copy(armLiveLots = 3, maxLotsPerOrder = 5, guardMaxLots = 10))
        SecurePrefs.putAll(mapOf("g.trades" to 0, "k.maxOrders" to 500))          // two entries in one test: no day caps
        // (The smaller account first: the account guard reads a fall from a larger one as a drawdown.)
        kite.cash = 5_000.0
        ArmLive.entered(ask(r72, "none"))
        val b = buys().singleOrNull() ?: error("nothing bought: ${view().refused}")
        assertEquals(s52100, b.form["tradingsymbol"]); assertEquals("1 lot, never Boss's 3", "30", b.form["quantity"])
        kite.flat(); kite.orders.values.filter { it.working }.forEach { it.status = "CANCELLED" }
        kite.requests.clear()
        val before = CheaperStrike.recsOf().size
        kite.cash = 13_000.0
        ArmLive.entered(ask(r51, "two"))
        val b2 = buys().singleOrNull() ?: error("nothing bought: ${view().refused}")
        assertEquals(sym, b2.form["tradingsymbol"]); assertEquals("2 of the 3 lots fit: no step", "60", b2.form["quantity"])
        assertEquals("no step while the lots cut found lots", before, CheaperStrike.recsOf().size)
    }

    /** Zerodha refuses the arm's own strike for funds (a definite answer): one lot of the next strike goes, once. */
    @Test fun aZerodhaFundsRefusalStepsOnce() {
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Insufficient funds. Required margin is 6000.00 but available margin is 5500.00.")
        ArmLive.entered(ask(r72, "rej"))
        assertEquals(listOf(sym, s52100), buys().map { it.form["tradingsymbol"] })
        assertEquals(s52100, view().openReal.single().symbol)
        assertEquals("the refusal's trigger", CheaperStrike.Trigger.REJECTED.words, CheaperStrike.recsOf().single().why)
        assertTrue("its row is not 'not mirrored'", view().notMirrored.none { it.ref == "rej" })
    }

    /** REJECTED in Zerodha's book for margin (the order went, nothing filled): the next strike goes, once. */
    @Test fun aRejectedForMarginInTheBookStepsOnce() {
        kite.nextPlace(outcome = FakeKite.Outcome.REJECT, message = "RMS:Margin Exceeds, Required:6,000.00, Available:5,500.00")
        ArmLive.entered(ask(r72, "rejbook"))
        assertEquals(listOf(sym, s52100), buys().map { it.form["tradingsymbol"] })
        assertEquals(s52100, view().openReal.single().symbol)
    }

    /** The cheaper strike refused too: never a third try; said as before. A refusal for something else never steps. */
    @Test fun neverMoreThanOneStepPerSignalAndOnlyForFunds() {
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Insufficient funds.")
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Insufficient funds.")
        ArmLive.entered(ask(r72, "twice"))
        assertEquals(listOf(sym, s52100), buys().map { it.form["tradingsymbol"] })
        assertTrue(view().openReal.isEmpty())
        assertFalse(CheaperStrike.recsOf().single().bought)
        kite.requests.clear()
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "RMS:Trigger price is outside the circuit limit")
        ArmLive.entered(ask(r72, "other"))
        assertEquals(listOf(sym), buys().map { it.form["tradingsymbol"] })
    }

    /** A lost reply (the order may exist) never steps: no second entry on an unknown. */
    @Test fun aLostReplyNeverSteps() {
        kite.nextPlace(FakeKite.Reply.NETWORK_EXCEPTION)
        ArmLive.entered(ask(r72, "lost"))
        assertTrue(buys().none { it.form["tradingsymbol"] != sym })
        assertTrue(CheaperStrike.recsOf().isEmpty())
        kite.requests.clear()
        kite.nextPlace(FakeKite.Reply.DROP_AFTER_ACCEPT)
        ArmLive.entered(ask(r51, "dropped"))
        assertTrue(buys().none { it.form["tradingsymbol"] != sym })
        assertTrue(CheaperStrike.recsOf().isEmpty())
    }

    // ---- the gates ---------------------------------------------------------------------------------------------------------------

    @Test fun theSwitchOffSendsTheEntryAsBefore() {
        CheaperStrike.set(false, log = false)
        kite.cash = 5_000.0
        ArmLive.entered(ask(r72, "off"))
        assertEquals(sym, buys().single().form["tradingsymbol"])
        assertTrue(CheaperStrike.recsOf().isEmpty())
        assertFalse("the funds check (shadow) logged it as before", FundsCheck.recsOf().single().applied)
    }

    @Test fun anotherArmNeverSteps() {
        kite.cash = 5_000.0
        ArmLive.entered(ask(com.optionslab.engine.orb.OovArm.SOURCE, "oov"))
        assertEquals(sym, buys().single().form["tradingsymbol"])
        assertTrue(CheaperStrike.recsOf().isEmpty())
    }

    @Test fun theKillSwitchAndStopForTodayStillRefuseBeforeAnyStep() {
        kite.cash = 5_000.0
        SecurePrefs.put("g.kill", true)
        ArmLive.entered(ask(r72, "kill"))
        assertTrue(kite.placed.isEmpty()); assertTrue(CheaperStrike.recsOf().isEmpty())
        SecurePrefs.put("g.kill", false)
        runBlocking { Strategies.stopForToday(false, false, log = false) }
        ArmLive.entered(ask(r72, "stopped"))
        assertTrue(kite.placed.isEmpty()); assertTrue(CheaperStrike.recsOf().isEmpty())
    }

    /** The cheaper strike meets the order door again: the kill switch turned on as it reaches the door - nothing is bought at all. */
    @Test fun theCheaperStrikeMeetsTheOrderDoorAgain() {
        kite.cash = 5_000.0
        Broker.testAtDoor = { o -> if (o.tradingSymbol == s52100) SecurePrefs.put("g.kill", true) }
        ArmLive.entered(ask(r72, "door"))
        assertTrue("nothing reached Zerodha", buys().isEmpty())
        assertTrue(view().openReal.isEmpty())
        SecurePrefs.put("g.kill", false)
    }

    @Test fun tooLateForTheSquareOffNothingIsSteppedOrSent() {
        kite.cash = 5_000.0
        ArmLive.testNow = Market.today().atTime(15, 16).atZone(com.optionslab.engine.IST)
        ArmLive.entered(ask(r72, "late"))
        assertTrue(kite.placed.isEmpty()); assertTrue(CheaperStrike.recsOf().isEmpty())
    }

    // ---- the live copy's exits on the cheaper option -----------------------------------------------------------------------------

    @Test fun thePaperExitSellsTheCheaperOptionWithItsStopTakenOutFirst() {
        kite.cash = 5_000.0
        ArmLive.entered(ask(r72, "exit"))
        val sid = view().openReal.single().stopOrderId!!
        watch()
        kite.requests.clear()
        legs["exit"] = r72 to 0
        watch()
        val cancel = kite.requests.indexOfFirst { it.method == "DELETE" && it.path.endsWith("/$sid") }
        val sell = kite.requests.indexOfFirst { it.method == "POST" && it.path == "/orders/regular" && it.form["transaction_type"] == "SELL" }
        assertTrue("the stop out before the sell", cancel in 0 until sell)
        assertEquals(s52100, kite.requests[sell].form["tradingsymbol"])
        assertEquals("paper_exit", view().closed.single().why)
        assertEquals(0, kite.positions["NFO:$s52100:MIS"]?.first ?: 0)
        assertNull("the paper strike was never touched", kite.positions["NFO:$sym:MIS"])
    }

    @Test fun thePaperLockMovesTheCheaperOptionsZerodhaStopUpScaledToItsFill() {
        kite.cash = 5_000.0
        ArmLive.entered(ask(r72, "lock"))
        val p = view().openReal.single()
        val sid = p.stopOrderId!!
        ArmLive.level("lock", 190.0)                                // the paper trade's stop moved to -5% of its 200
        watch()
        val want = com.optionslab.engine.Kite.onTick(p.entry * 0.95, 0.05, com.optionslab.engine.Kite.Side.SELL)
        assertEquals(want, kite.order(sid).trigger, 1e-9)
        assertEquals(s52100, kite.order(sid).symbol)
        assertTrue("a modify of the same order", kite.requests.any { it.method == "PUT" && it.path.endsWith("/$sid") })
    }

    @Test fun theSquareOffSellsTheCheaperOption() {
        kite.cash = 5_000.0
        ArmLive.entered(ask(r72, "sq"))
        ArmLive.testNow = Market.today().atTime(15, 15).atZone(com.optionslab.engine.IST)
        watch()
        assertEquals("square_off", view().closed.single().why)
        assertEquals(0, kite.positions["NFO:$s52100:MIS"]?.first ?: 0)
        assertTrue(kite.placed.any { it.form["transaction_type"] == "SELL" && it.form["tradingsymbol"] == s52100 })
    }

    @Test fun aRestartRestoresTheCheaperCopyAndItsPaperLink() {
        kite.cash = 5_000.0
        ArmLive.entered(ask(r72, "restart"))
        watch()
        ArmLive.drainWritesForTest()
        ArmLive.forgetMemoryForTest()
        runBlocking { ArmLive.holding() }
        val p = view().openReal.single()
        assertEquals(s52100, p.symbol); assertEquals(contract.symbol, p.paperSymbol); assertEquals(sym, p.stepFrom)
        assertEquals(paperSym(52_100.0), p.liveContract)
        assertEquals("one stop only", 1, kite.orders.values.count { it.side == "SELL" && it.type == "SL" && it.working })
        // Its paper exit after the restart still sells the cheaper option.
        legs["restart"] = r72 to 0
        watch()
        assertEquals("paper_exit", view().closed.single().why)
        assertEquals(0, kite.positions["NFO:$s52100:MIS"]?.first ?: 0)
        assertEquals("one buy only", 1, kite.orders.values.count { it.side == "BUY" })
    }

    /** No strike further out fits (Rs 1,000): it gives up, logged, and the entry goes on exactly as before. */
    @Test fun noStrikeFittingTheEntryGoesAsBefore() {
        kite.cash = 1_000.0
        ArmLive.entered(ask(r72, "giveup"))
        assertEquals(sym, buys().single().form["tradingsymbol"])
        val r = CheaperStrike.recsOf().single()
        assertFalse(r.bought); assertEquals(3, r.steps)
        assertNotNull(FundsCheck.recsOf().singleOrNull())
    }
}
