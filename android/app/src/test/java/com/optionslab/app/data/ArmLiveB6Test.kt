package com.optionslab.app.data

import com.optionslab.app.ira.IraNewsTrades
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.orb.LiveMirror
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 22 Nov 2026 (B6 review of the exits, the arms' live copies - [ArmLive]): against the fake Kite.
 *  - M4: an exit sale whose reply was lost is held - no second sale, no stop beside it - until it is found, or a read of the order book
 *    that succeeded at least two minutes after it was sent does not show it; it is kept in the book across a restart; the square-offs see
 *    it (and each sale on its way) through [SquareOffExit.lostPendingProviders], the expiry square-off's 15:08 backstop included; the
 *    arms' own exits wait while a square-off's sale is on its way.
 *  - m1: the arms' GTT stops fire a LIMIT 10% past the trigger.
 *  - m2: a malformed positions row is "not read" for a lost entry, never "nothing held".
 *  - m5: a closed trade's found lost stop is cancelled after the open trades' look, and again until Zerodha confirms it.
 *  - WHO CANCELLED A STOP: an own-cancel mark whose cancel never took ends, so Boss's later cancel of that stop is his.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ArmLiveB6Test : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private val contract by lazy { Paper.Contract("BANKNIFTY-TEST-52000CE", "BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|TESTCE") }
    private val legs = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()
    private val donchian = com.optionslab.engine.orb.DonchianArm.SOURCE

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        com.optionslab.app.work.Alerts.forgetPosted()
        AutomationSupport.resetExpiryTold()
        SecurePrefs.put(NtfyReader.K_TOKEN, "tk_test0001")
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        IraNewsTrades.paperFirst = false
        ArmLive.testSync = true
        ArmLive.testDefaultOff = false
        ArmLive.testNow = AutomationSupport.earlierToday()
        FillSlippage.resetForTest()
        for (a in LiveMirror.ARMS) if (a.key != LiveMirror.SOLO_MIDDAY) runBlocking { ArmLive.setShadow(a.key, false, pinConfirmed = true, log = false) }
        for (f in LiveMirror.ARMS.map { it.family }.distinct())
            ArmLive.register(f) { legs.entries.filter { e -> LiveMirror.spec(e.value.first)?.family == f }.map { ArmLive.PaperLeg(it.key, it.value.first, it.value.second) } }
        kite.requests.clear()
    }

    @After fun down() {
        ArmLive.testSync = false
        ArmLive.testNow = null
        ArmLive.testLotCap = null
        Broker.lostLookGapMsForTest = null; Broker.lostSearchInlineForTest = false; Broker.lostSearchMsForTest = null
        SquareOffExit.resetForTest(gap = 0L)
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun ask(key: String, ref: String, qty: Int = 30, stop: Double? = 170.0, c: Paper.Contract = contract): ArmLive.Ask {
        legs[ref] = key to qty
        return ArmLive.Ask(key, ref, c, 1, qty, 200.0, stop = stop)
    }
    private fun watch() = runBlocking { ArmLive.watch() }
    private fun view() = ArmLive.view.value
    private fun held(s: String = sym) = kite.positions["NFO:$s:MIS"]?.first ?: 0
    private fun marketSells() = kite.placed.count { it.form["order_type"] == "MARKET" && it.form["transaction_type"] == "SELL" }
    private fun posted(text: String) = com.optionslab.app.work.Alerts.posted.count { text in it.text }
    private fun later(seconds: Long) { ArmLive.testNow = ArmLive.testNow!!.plusSeconds(seconds) }

    /** [ref]'s paper trade sold, its live exit's reply lost: [fills] it fills at Zerodha (else Zerodha refused it); neither book shows it. */
    private fun lostExit(ref: String, fills: Boolean = true) {
        ArmLive.entered(ask(donchian, ref))
        watch()
        val before = marketSells()
        kite.trailReads = 1_000                                              // the sale will not show in the order book
        kite.positionsTrailReads = 1_000                                     // nor in the positions
        kite.nextPlace(FakeKite.Scenario(reply = FakeKite.Reply.DROP_AFTER_ACCEPT, outcome = if (fills) FakeKite.Outcome.FILL else FakeKite.Outcome.REJECT))
        legs[ref] = donchian to 0
        watch()
        assertEquals(before + 1, marketSells())
        assertEquals("held", setOf("NFO|$sym|MIS"), ArmLive.lostExitsForTest())
    }

    // ---- M4: a lost exit is let go only on a good read two minutes after it went ------------------------------------------------

    /**
     * The finding: let go after 10 s and one empty search, a slow order book could see a second sale or a stop beside the first. Now it is
     * held - no second sale, no stop rested - through two minutes of empty searches; only a read that succeeded two minutes after it was
     * sent lets it go (said once), and the exit then goes once more.
     */
    @Test fun aLostExitIsHeldUntilAGoodReadTwoMinutesAfterItWent() {
        lostExit("held", fills = false)
        for (s in listOf(11L, 30L, 30L, 30L, 16L)) {                         // to 117 s: searched and not found each time
            later(s); watch()
            assertEquals("never sent again while it may be selling", 1, marketSells())
            assertTrue("no stop rested beside it", kite.orders.values.none { it.working && it.side == "SELL" })
        }
        assertEquals(0, posted("was not found in Zerodha's order book"))
        later(4); watch()                                                    // a good read 121 s after it went
        assertEquals("let go, said once", 1, posted("was not found in Zerodha's order book on a read at least 2 minutes after it was sent"))
        assertEquals("sold once more", 2, marketSells())
        assertEquals("flat", 0, held())
        assertEquals("paper_exit", view().closed.single().why)
        assertTrue(ArmLive.lostExitsForTest().isEmpty())
    }

    /** The live lane (every tick) never searches and never lets go - only a regular look's good read does. */
    @Test fun theLiveLaneNeverLetsALostExitGo() {
        lostExit("lane", fills = false)
        later(125)
        repeat(3) { runBlocking { ArmLive.watch(stream = true) } }
        assertEquals("held on the live lane", 1, marketSells())
        assertEquals(setOf("NFO|$sym|MIS"), ArmLive.lostExitsForTest())
        watch()
        assertEquals(2, marketSells())
        assertEquals(0, held())
    }

    /** A read that fails is no read: past two minutes it still holds (Boss told once its state is unknown); the next good read decides. */
    @Test fun aFailedSearchNeverLetsALostExitGo() {
        lostExit("unread", fills = false)
        later(125)
        // The look's own read of the order book works; each search's read of it after fails (three looks).
        repeat(3) {
            kite.afterNext("/orders") { kite.down += "/orders" }
            watch()
            kite.down.clear()
            later(4)
        }
        assertEquals("held", 1, marketSells())
        assertEquals(setOf("NFO|$sym|MIS"), ArmLive.lostExitsForTest())
        assertEquals("said once", 1, posted("could not be looked for"))
        watch()
        assertEquals(2, marketSells())
    }

    /** M4: the lost exit is kept in the book: after a restart it is still held, the square-offs see it once the book is read, and it is the exit when found. */
    @Test fun aLostExitSurvivesARestartAndIsTheExitWhenFound() {
        SquareOffExit.resetForTest(gap = SquareOffExit.GAP_MS)               // (the app's gap: a booked sale counted through it)
        lostExit("restart")
        ArmLive.drainWritesForTest()
        ArmLive.forgetMemoryForTest()                                         // the phone restarted
        assertEquals("the book not read yet: not known", -1, ArmLive.unsettledSales(sym, "MIS"))
        runBlocking { ArmLive.refresh() }
        assertEquals("taken back from the book", 30, ArmLive.unsettledSales(sym, "MIS"))
        later(4); watch()
        assertEquals("never sent again after the restart", 1, marketSells())
        assertTrue("no stop beside it", kite.orders.values.none { it.working && it.side == "SELL" })
        kite.showInBook(); kite.positionsCatchUp()
        later(4); watch()
        assertEquals("the lost order is the exit", "paper_exit", view().closed.single().why)
        assertEquals(1, marketSells())
        assertEquals(0, held())
        assertEquals("counted for the gap once booked", 30, ArmLive.unsettledSales(sym, "MIS"))
        ArmLive.ageBookedSalesForTest(SquareOffExit.GAP_MS)
        assertEquals(0, ArmLive.unsettledSales(sym, "MIS"))
        // ... and forgotten on disk too: a second restart holds nothing.
        ArmLive.drainWritesForTest(); ArmLive.forgetMemoryForTest(); runBlocking { ArmLive.refresh() }
        assertTrue(ArmLive.lostExitsForTest().isEmpty())
    }

    /** The provider's "not known" lasts only while the book is unread within two minutes of the start (a lost exit older shows at Zerodha). */
    @Test fun theProviderIsUnknownOnlyBeforeTheBookIsReadInItsFirstTwoMinutes() {
        ArmLive.forgetMemoryForTest()
        assertEquals(-1, ArmLive.unsettledSales(sym, "MIS"))
        assertEquals("the square-offs read it as a sale of unknown size", null, SquareOffExit.lostPending(sym, "MIS"))
        ArmLive.startedForTest(ArmLive.LOST_LET_GO_MS + 1_000)
        assertEquals(0, ArmLive.unsettledSales(sym, "MIS"))
        ArmLive.forgetMemoryForTest()
        runBlocking { ArmLive.refresh() }
        assertEquals(0, ArmLive.unsettledSales(sym, "MIS"))
        assertEquals(0, SquareOffExit.lostPending(sym, "MIS"))
    }

    /** The provider counts the lost exit on its own row only, and a sale on its way while it is being sent; saleStarting is told. */
    @Test fun theProviderCountsALostExitAndASaleOnItsWay() {
        SquareOffExit.resetForTest(gap = SquareOffExit.GAP_MS)               // (the app's gap: a booked sale counted through it)
        ArmLive.entered(ask(donchian, "flight"))
        watch()
        @Suppress("UNCHECKED_CAST")
        val started = SquareOffExit::class.java.getDeclaredField("salesStarted").apply { isAccessible = true }.get(SquareOffExit) as Map<String, Long>
        val before = started["$sym:MIS"] ?: 0L
        var during = -2
        var startedDuring = -1L
        kite.afterNext("/orders/regular") { during = ArmLive.unsettledSales(sym, "MIS"); startedDuring = started["$sym:MIS"] ?: 0L }
        legs["flight"] = donchian to 0
        watch()
        assertEquals("counted while it is sent", 30, during)
        assertEquals("the square-offs told a sale started", before + 1, startedDuring)
        assertEquals("booked: still counted for the square-offs' gap", 30, ArmLive.unsettledSales(sym, "MIS"))
        // (Fourth round, m3: never for Boss's own exit - its lag guard counts that fill already.)
        assertEquals(0, ArmLive.unsettledSales(sym, "MIS", booked = false))
        assertEquals(0, SquareOffExit.pendingForBoss(sym, "MIS")["armlive"])
        ArmLive.ageBookedSalesForTest(SquareOffExit.GAP_MS)
        assertEquals("then nothing on its way", 0, ArmLive.unsettledSales(sym, "MIS"))
        // A lost one: on its own row only.
        com.optionslab.app.work.Alerts.forgetPosted()
        lostExit("lost2")
        assertEquals(30, ArmLive.unsettledSales(sym, "MIS"))
        assertEquals(0, ArmLive.unsettledSales(sym, "NRML"))
        assertEquals(0, ArmLive.unsettledSales("NIFTY26OCT24500CE", "MIS"))
        assertEquals(30, SquareOffExit.lostPending(sym, "MIS"))
    }

    /** The arms' own exit waits while a square-off's sale on the contract is on its way (its gap): its stop stays resting, nothing sold. */
    @Test fun anArmsExitWaitsWhileASquareOffsSaleIsOnItsWay() {
        ArmLive.entered(ask(donchian, "gap"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        SquareOffExit.resetForTest(gap = SquareOffExit.GAP_MS)
        SquareOffExit.notePlaced("NFO", sym, "MIS")
        legs["gap"] = donchian to 0
        watch()
        assertEquals("nothing sold beside it", 0, marketSells())
        assertTrue("its stop left resting", kite.order(sid).working)
        SquareOffExit.resetForTest(gap = 0L)
        watch()
        assertEquals(1, marketSells())
        assertEquals("CANCELLED", kite.order(sid).status)
        assertEquals(0, held())
    }

    /**
     * One seller at a time on a row ([SquareOffExit.claimExit]): while a square-off holds the row's claim, the arm's exit takes nothing out
     * and sells nothing (its stop rests); while the arm's exit holds it, a square-off cannot take it.
     */
    @Test fun anArmsExitWaitsWhileASquareOffHoldsTheRowsClaim() {
        ArmLive.entered(ask(donchian, "claim"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", "iraalgoexpiry")!!
        try {
            legs["claim"] = donchian to 0
            watch()
            assertEquals("nothing sold beside it", 0, marketSells())
            assertTrue("its stop left resting", kite.order(sid).working)
        } finally { claim.release() }
        // The arm's exit holds the claim while it sells: a square-off cannot take it then.
        var squareOffGot: Boolean? = null
        kite.afterNext("/orders/regular") { squareOffGot = SquareOffExit.claimExit("NFO", sym, "MIS", "iraalgoflat")?.also { it.release() } != null }
        watch()
        assertEquals(1, marketSells())
        assertEquals("the row was the arm's while its sale went", false, squareOffGot)
        assertEquals("let go after", null, SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
        assertEquals(0, held())
    }

    /**
     * #7 (fifth round): one of these arms' own stops going back holds the row a moment. The arm's exit is never waited for under the book's
     * lock: this pass skips the trade at once (nothing taken out or sold, its stop left resting) and the next pass sells it.
     */
    @Test fun anArmsExitSkipsThePassWhileItsOwnStopGoesBackAndNeverWaits() {
        ArmLive.entered(ask(donchian, "back"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        legs["back"] = donchian to 0
        /** One pass with the row held by [owner]: how long it took; nothing sold, the stop left resting. */
        fun heldPass(owner: String): Long {
            val claim = SquareOffExit.claimExit("NFO", sym, "MIS", owner)!!
            try {
                val t0 = System.nanoTime()
                watch()
                return (System.nanoTime() - t0) / 1_000_000
            } finally {
                claim.release()
                assertEquals("nothing sold beside it", 0, marketSells())
                assertTrue("its stop left resting", kite.order(sid).working)
            }
        }
        // A square-off's hold is never waited for; one of the arms' own stops going back now is not either (the old wait was a full second).
        val other = heldPass("iraalgoflat")
        val own = heldPass(ArmLive.STOP_CLAIM_OWNER)
        assertTrue("never waited for its own stop: $own ms, against $other ms with a square-off holding it", own < other + 700)
        watch()
        assertEquals("sold on the next pass", 1, marketSells())
        assertEquals(0, held())
        assertEquals(null, SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
    }

    /**
     * The handover: the expiry square-off leaves an arm's expiring trade to the arm until 15:08, then sells what is left itself. An arm's exit
     * sale lost at noon on expiry day, not shown yet: at the 15:08 backstop the square-off sells nothing beside it (with it filled, selling
     * the 30 Zerodha's positions still show would go short) and says why; once the arm finds and books it, nothing is left to sell.
     */
    @Test fun theExpiryBackstopSellsNothingBesideAnArmsLostExit() {
        val today = Market.today()
        val s = "BANKNIFTY26NOV52000CE"
        kite.instruments += FakeKite.Ins(22_000_050, s, "BANKNIFTY", today, 52_000.0, "CE", 30)
        kite.quote("NFO:$s", 200.0, 199.95, 200.05)
        java.io.File(context.filesDir, "kite_instruments.json").delete(); runBlocking { Broker.instruments() }
        val c = Paper.Contract("BANKNIFTY-TEST-52000CE-X", "BANKNIFTY", today, 52_000.0, Right.CE, 30, "NSE_FO|TESTX")
        ArmLive.entered(ask(donchian, "exp", c = c))
        watch()
        assertEquals(30, held(s))
        kite.trailReads = 1_000; kite.positionsTrailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        legs["exp"] = donchian to 0
        watch()
        assertEquals(30, ArmLive.unsettledSales(s, "MIS"))
        ExpirySquareOff.testNow = today.atTime(15, 8).atZone(com.optionslab.engine.IST)
        runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
        fun expirySells() = kite.placed.count { it.form["tag"] == "iraalgoexpiry" }
        assertEquals("nothing sold beside the arm's lost exit", 0, expirySells())
        assertTrue("Boss told why", com.optionslab.app.work.Alerts.queue.value.any { "exit sale on it has not settled yet" in it.text })
        kite.showInBook(); kite.positionsCatchUp()
        later(4); watch()
        assertEquals("booked as the arm's exit", "paper_exit", view().closed.single().why)
        ArmLive.ageBookedSalesForTest(SquareOffExit.GAP_MS)
        assertEquals(0, ArmLive.unsettledSales(s, "MIS"))
        ExpirySquareOff.testNow = today.atTime(15, 9).atZone(com.optionslab.engine.IST)
        runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
        assertEquals(0, expirySells())
        assertEquals("flat, never short", 0, held(s))
    }

    /**
     * With the square-offs' protocol: an arm's exit sale started and booked after the expiry square-off read Zerodha's books (the arm's whole
     * exit runs between its reads and its decision) is never sold again - the square-off sees a sale started since its reads and leaves the
     * row to its next pass: exactly one sale, flat.
     */
    @Test fun anArmsSaleStartedAndBookedAfterTheSquareOffsReadsIsNeverSoldAgain() {
        val today = Market.today()
        val s = "BANKNIFTY26NOV52000CE"
        kite.instruments += FakeKite.Ins(22_000_050, s, "BANKNIFTY", today, 52_000.0, "CE", 30)
        kite.quote("NFO:$s", 200.0, 199.95, 200.05)
        java.io.File(context.filesDir, "kite_instruments.json").delete(); runBlocking { Broker.instruments() }
        val c = Paper.Contract("BANKNIFTY-TEST-52000CE-X", "BANKNIFTY", today, 52_000.0, Right.CE, 30, "NSE_FO|TESTX")
        ArmLive.entered(ask(donchian, "after", c = c))
        watch()
        assertEquals(30, held(s))
        legs["after"] = donchian to 0                                        // its paper trade sold: its exit is due
        try {
            ExpirySquareOff.testAfterReads = { ArmLive.watch() }              // the arm's whole exit, after the square-off's reads
            ExpirySquareOff.testNow = today.atTime(15, 8).atZone(com.optionslab.engine.IST)
            runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
        } finally { ExpirySquareOff.testAfterReads = null }
        fun expirySells() = kite.placed.count { it.form["tag"] == "iraalgoexpiry" }
        assertEquals("the arm sold its lot", 1, marketSells())
        assertEquals("paper_exit", view().closed.single().why)
        assertEquals("the square-off sold nothing on its older reads", 0, expirySells())
        assertEquals("flat, never short", 0, held(s))
        ExpirySquareOff.testNow = today.atTime(15, 9).atZone(com.optionslab.engine.IST)
        runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
        assertEquals("nor on its next pass", 0, expirySells())
    }

    // ---- second round of the B6 review ---------------------------------------------------------------------------------------

    /** m-2: a square-off holding the row past its gap (its stops slow to come out): the arm never rests its stop again beside its sale. */
    @Test fun theArmsStopIsNotRestedAgainWhileASquareOffHoldsTheRowPastItsGap() {
        ArmLive.entered(ask(donchian, "past"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", "iraalgoexpiry")!!
        try {
            runBlocking { ArmLive.markOwnCancels(listOf(sid)) }               // the square-off's takeOut: marked, then cancelled
            runBlocking { Broker.cancel(sid) }
            later(20); watch()                                               // past any gap: its claim still held
            assertEquals("no stop beside its sale", 0, kite.orders.values.count { it.working && it.side == "SELL" })
        } finally { claim.release() }
        var claimedWhileRested: String? = "not rested"
        kite.afterNext("/orders/regular") { claimedWhileRested = SquareOffExit.exitClaimedBy("NFO", sym, "MIS") }
        later(5); watch()
        assertEquals("rested again once it let go", 1, kite.orders.values.count { it.working && it.side == "SELL" })
        assertEquals("the row claimed under the arms' stop name while it went in", ArmLive.STOP_CLAIM_OWNER, claimedWhileRested)
        assertEquals("said as a stop going back", "an arm's stop going back at Zerodha", SquareOffExit.claimWords(ArmLive.STOP_CLAIM_OWNER))
        assertEquals(null, SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
        assertEquals(30, held())
    }

    /**
     * With the square-offs' third round: an arm's exit sale whose reply was lost is counted once - by the arms' own count ([ArmLive.unsettledSales],
     * its tag in [SquareOffExit.lostPendingTags]) - and never also holds the row back as another sender's sale the fresh read does not show:
     * at the 15:08 backstop the square-off sells Boss's own 30 beside it, never the arm's 30 again - flat.
     */
    @Test fun anArmsLostSaleIsCountedOnceAndNeverHoldsTheRowBack() {
        val today = Market.today()
        val s = "BANKNIFTY26NOV52000CE"
        kite.instruments += FakeKite.Ins(22_000_050, s, "BANKNIFTY", today, 52_000.0, "CE", 30)
        kite.quote("NFO:$s", 200.0, 199.95, 200.05)
        java.io.File(context.filesDir, "kite_instruments.json").delete(); runBlocking { Broker.instruments() }
        kite.position(s, 30, 190.0, product = "MIS")                         // Boss's own lot
        val c = Paper.Contract("BANKNIFTY-TEST-52000CE-X", "BANKNIFTY", today, 52_000.0, Right.CE, 30, "NSE_FO|TESTX")
        ArmLive.entered(ask(donchian, "once", c = c))
        watch()
        assertEquals(60, held(s))
        assertTrue(LiveMirror.TAG in SquareOffExit.lostPendingTags)
        kite.trailReads = 1_000; kite.positionsTrailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)             // the arm's sale fills; its reply is lost, neither book shows it
        legs["once"] = donchian to 0
        watch()
        assertEquals(30, ArmLive.unsettledSales(s, "MIS"))
        ExpirySquareOff.testNow = today.atTime(15, 8).atZone(com.optionslab.engine.IST)
        runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
        assertEquals("Boss's 30 sold beside it, the arm's never again", listOf("30"),
            kite.placed.filter { it.form["tag"] == "iraalgoexpiry" }.map { it.form["quantity"] })
        assertEquals("flat, never short", 0, held(s))
    }

    /**
     * m-3: the claim is taken only once the books and the gates say the exit can go. Boss's own SL-M covering the lot (never cancelled for
     * him) blocks the due exit: the row is never claimed meanwhile; once his order is gone, the exit claims it once and sells.
     */
    @Test fun aBlockedExitNeverHoldsTheRowsClaim() {
        ArmLive.entered(ask(donchian, "blocked"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        kite.nextPlace(outcome = FakeKite.Outcome.OPEN)
        val his = runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 30, 30, "MIS", "SL-M", null, 0.05,
            "NFO", "", triggerPrice = 150.0), exit = true) }
        val before = ArmLive.claimsTakenForTest.get()
        legs["blocked"] = donchian to 0
        later(5); watch()
        later(5); watch()
        assertEquals("never claimed while blocked", before, ArmLive.claimsTakenForTest.get())
        assertEquals("nothing sold", 0, marketSells())
        assertTrue("its stop left resting", kite.order(sid).working)
        runBlocking { Broker.cancel(his) }
        later(65); watch()
        assertEquals("claimed once to sell", before + 1, ArmLive.claimsTakenForTest.get())
        assertEquals(1, marketSells())
        assertEquals(0, held())
        assertEquals(null, SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
    }

    /**
     * m-7: before the book is read, "not known" only for the rows these arms may hold - never Boss's stocks; known once the start's positions
     * step reads it ([PositionsFirst] asks [ArmLive.holding] first thing).
     */
    @Test fun theProviderIsUnknownOnlyForRowsTheArmsMayHold() {
        ArmLive.forgetMemoryForTest(); ArmLive.startedForTest(0)
        assertEquals(-1, ArmLive.unsettledSales(sym, "MIS"))
        assertEquals(-1, ArmLive.unsettledSales("SENSEX26NOV80000PE", "NRML"))
        assertEquals("Boss's stock", 0, ArmLive.unsettledSales("RELIANCE", "CNC"))
        assertEquals("Boss's intraday stock", 0, ArmLive.unsettledSales("INFY", "MIS"))
        assertEquals("a stock whose name ends in CE", 0, ArmLive.unsettledSales("RELIANCE", "MIS"))
        assertEquals(-1, ArmLive.unsettledSales("CRUDEOIL26DECFUT", "NRML"))
        runBlocking { ArmLive.holding() }
        assertEquals("known once read", 0, ArmLive.unsettledSales(sym, "MIS"))
    }

    /** Nit: a closed trade's found lost stop is cancelled at most [ArmLive.CLOSED_STOP_TRIES] passes, then left to Boss - told once. */
    @Test fun aClosedTradesLostStopIsTriedAtMostTwentyPasses() {
        kite.afterNext("/orders/regular") { kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT); kite.trailReads = 50 }
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        ArmLive.entered(ask(donchian, "capped"))                              // its stop's reply lost
        legs["capped"] = donchian to 0
        watch()
        assertTrue("closed", view().openReal.isEmpty())
        val lost = kite.orders.values.single { it.type == "SL" && it.symbol == sym }
        kite.showInBook()
        kite.cancelsLost = 1_000                                             // its cancels are taken but never land
        kite.requests.clear()
        repeat(ArmLive.CLOSED_STOP_TRIES + 3) { watch() }
        assertEquals("tried at most twenty passes", ArmLive.CLOSED_STOP_TRIES, kite.requests.count { it.method == "DELETE" && it.path.endsWith(lost.id) })
        assertEquals("told once", 1, posted("could still not be confirmed cancelled after"))
        assertTrue(lost.working)
    }

    // ---- m1: the arms' GTT stops ----------------------------------------------------------------------------------------------

    @Test fun theArmsGttStopsFireWithTenPercentRoom() {
        assertEquals(90.0, ArmLive.gttLeg(100.0, 0.05, 1), 1e-9)
        assertEquals(110.0, ArmLive.gttLeg(100.0, 0.05, -1), 1e-9)
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, "night"))
        val g = kite.gtts.values.single()
        assertEquals(170.0, g.getJSONObject("condition").getJSONArray("trigger_values").getDouble(0), 1e-9)
        assertEquals("170 less 10%, not 161.50 (5%)", 153.0, g.getJSONArray("orders").getJSONObject(0).getDouble("price"), 1e-9)
    }

    // ---- m2: a malformed positions row is not read --------------------------------------------------------------------------------

    private fun clearSettleAsked() {
        (ArmLive::class.java.getDeclaredField("settleAsked").apply { isAccessible = true }.get(ArmLive) as MutableMap<*, *>).clear()
    }

    /** A lost entry whose position row Zerodha's reply left out as malformed: kept (asked again), never dropped as "never placed". */
    @Test fun aLostEntryIsNeverDroppedOnAMalformedPositionsRow() {
        kite.trailReads = 1_000                                              // the order book never lists the buy
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)             // its reply lost; it fills at Zerodha
        ArmLive.entered(ask(donchian, "bad-row"))
        assertTrue(view().openReal.single().unconfirmed)
        kite.positions.remove("NFO:$sym:MIS")
        kite.extraNetRows = listOf(JSONObject().put("tradingsymbol", sym).put("exchange", "NFO").put("product", "MIS"))   // no quantity
        later(5 * 60)
        repeat(4) { clearSettleAsked(); watch(); later(15) }
        assertEquals("kept, never dropped", 1, view().openReal.size)
        assertEquals(0, posted("treated as never placed"))
        kite.extraNetRows = emptyList(); kite.position(sym, 30, 200.0, product = "MIS")
        clearSettleAsked(); watch()
        val p = view().openReal.single()
        assertFalse("taken back once read", p.unconfirmed)
        assertNotNull(p.stopOrderId)
    }

    /** The earlier-day branch: a carried lost entry whose row is left out as malformed is kept, never "never confirmed". */
    @Test fun anEarlierDaysLostEntryIsNeverDroppedOnAMalformedRow() {
        kite.trailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, "night-lost"))
        assertTrue(view().openReal.single().unconfirmed)
        kite.positions.remove("NFO:$sym:NRML")
        kite.extraNetRows = listOf(JSONObject().put("tradingsymbol", sym).put("exchange", "NFO").put("product", "NRML"))
        ArmLive.testNow = ArmLive.testNow!!.plusDays(1)
        clearSettleAsked(); watch()
        assertEquals("kept", 1, view().openReal.size)
        assertEquals(0, posted("was never confirmed by Zerodha"))
        kite.extraNetRows = emptyList(); kite.position(sym, 30, 200.0)
        clearSettleAsked(); watch()
        assertFalse(view().openReal.single().unconfirmed)
    }

    // ---- m5: a closed trade's lost stop ---------------------------------------------------------------------------------------

    /** Cancelled after the open trades' look (open positions first), and again on the next pass when its first cancel did not land. */
    @Test fun aClosedTradesLostStopIsCancelledAfterTheLookAndAgainUntilConfirmed() {
        kite.afterNext("/orders/regular") { kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT); kite.trailReads = 50 }
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        ArmLive.entered(ask(donchian, "closed"))                              // its stop's reply lost
        assertTrue(view().open.single().stopOrderId == null)
        legs["closed"] = donchian to 0
        watch()
        assertTrue("closed", view().openReal.isEmpty())
        // Another arm's trade open on another contract: its look comes first.
        val s2 = "BANKNIFTY26OCT52100CE"
        kite.instruments += FakeKite.Ins(22_000_101, s2, "BANKNIFTY", expiry, 52_100.0, "CE", 30)
        kite.quote("NFO:$s2", 200.0, 199.95, 200.05)
        java.io.File(context.filesDir, "kite_instruments.json").delete(); runBlocking { Broker.instruments() }
        ArmLive.entered(ask(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "open",
            c = Paper.Contract("BANKNIFTY-TEST-52100CE", "BANKNIFTY", expiry, 52_100.0, Right.CE, 30, "NSE_FO|T1")))
        val lost = kite.orders.values.single { it.type == "SL" && it.symbol == sym }
        kite.showInBook()
        kite.cancelsLost = 1                                                 // its first cancel is taken but never lands
        kite.requests.clear()
        watch()
        val reqs = kite.requests.toList()
        val positions = reqs.indexOfFirst { it.method == "GET" && it.path == "/portfolio/positions" }
        val cancel = reqs.indexOfFirst { it.method == "DELETE" && it.path.endsWith(lost.id) }
        assertTrue("the open trades' look read the books first: $reqs", positions in 0 until cancel)
        assertTrue("its cancel did not land", lost.working)
        assertTrue(ArmLive.recoverHint)
        watch()
        assertEquals("cancelled again and confirmed", "CANCELLED", lost.status)
        watch()
        assertEquals("never a third cancel", 2, kite.requests.count { it.method == "DELETE" && it.path.endsWith(lost.id) })
        assertTrue(view().openReal.single().stopOrderId != null)
    }

    // ---- WHO CANCELLED A STOP: own-cancel marks that never took ---------------------------------------------------------------

    /**
     * Review plausible 3: a mark whose cancel never took stayed for ever, so Boss's own later cancel of that stop in Kite was read as the
     * app's and put back. Now it ends once the stop is seen still working two minutes after the mark: Boss's cancel is his - never put back.
     */
    @Test fun anOwnCancelMarkWhoseCancelNeverTookEndsSoBossesCancelIsHis() {
        ArmLive.entered(ask(donchian, "mark"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        runBlocking { ArmLive.markOwnCancelForTest(sid) }                     // the app's cancel, which never took
        watch()
        assertTrue("kept while its cancel may still land", ArmLive.ownCancelForTest(sid))
        ArmLive.ageOwnCancelForTest(sid, ArmLive.OWN_CANCEL_SETTLE_MS + 1_000)
        watch()
        assertFalse("seen working two minutes on: ended", ArmLive.ownCancelForTest(sid))
        kite.order(sid).status = "CANCELLED"                                  // Boss cancels it in Kite
        watch()
        assertTrue("his cancel respected", view().openReal.single().stopByYou)
        assertEquals("never put back", 1, kite.placed.count { it.form["order_type"] == "SL" })
        // ... kept across a restart as it was (ended), the stop still his.
        ArmLive.drainWritesForTest(); ArmLive.forgetMemoryForTest(); runBlocking { ArmLive.refresh() }
        assertFalse(ArmLive.ownCancelForTest(sid))
    }

    /** A mark taken back from the book after a restart keeps the time it was made: kept two minutes, then it ends while its stop works. */
    @Test fun anOwnCancelMarkTakenBackAfterARestartKeepsItsTime() {
        ArmLive.entered(ask(donchian, "restored"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        runBlocking { ArmLive.markOwnCancelForTest(sid) }
        ArmLive.drainWritesForTest(); ArmLive.forgetMemoryForTest(); runBlocking { ArmLive.refresh() }
        assertTrue("kept in the book", ArmLive.ownCancelForTest(sid))
        watch()
        assertTrue("within its two minutes: kept", ArmLive.ownCancelForTest(sid))
        ArmLive.ageOwnCancelForTest(sid, ArmLive.OWN_CANCEL_SETTLE_MS + 1_000)
        watch()
        assertFalse(ArmLive.ownCancelForTest(sid))
        // A mark the app's own exit makes and whose cancel lands is still the app's: put back while held.
        runBlocking { ArmLive.markOwnCancelForTest(sid) }
        kite.order(sid).status = "CANCELLED"
        watch()
        assertFalse(view().openReal.single().stopByYou)
        assertEquals("put back", 2, kite.placed.count { it.form["order_type"] == "SL" })
    }

    /**
     * A cancel Zerodha refused outright did not take: the mark it made ends at once (and nothing is sold beside the stop); one another of the
     * app's paths made before it (a square-off's) stays.
     */
    @Test fun aRefusedCancelsMarkEndsAtOnce() {
        ArmLive.entered(ask(donchian, "refused"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        kite.order(sid).status = "PUT ORDER REQ RECEIVED"                     // Zerodha refuses its cancel ("being processed")
        legs["refused"] = donchian to 0
        watch()
        assertFalse(ArmLive.ownCancelForTest(sid))
        assertEquals("nothing sold beside a stop not confirmed out", 0, marketSells())
        // Marked first by a square-off (its cancel taken): the exit's own refused cancel leaves that mark.
        runBlocking { ArmLive.markOwnCancels(listOf(sid)) }
        watch()
        assertTrue(ArmLive.ownCancelForTest(sid))
    }

    // ---- 23 Nov 2026, B6-B sixth round with B6-G's follow-ups --------------------------------------------------------------------------

    /**
     * Kite's unsure answer to the arm's exit sale ([Broker.KiteUnsure]: its GeneralException, nothing in the book after the door's looks):
     * straight to the lost exit - no second ~4 s search of its own under the book's lock - and nothing more sold while it is looked for.
     */
    @Test fun anUnsureAnswerToAnArmsExitGoesStraightToItsLostExit() {
        ArmLive.entered(ask(donchian, "unsure"))
        watch()
        kite.nextPlace(reply = FakeKite.Reply.GENERAL_EXCEPTION)
        legs["unsure"] = donchian to 0
        val searches = Broker.callerSearchesForTest.get()
        watch()
        assertEquals("no second search of its own", searches, Broker.callerSearchesForTest.get())
        assertEquals("held as a lost exit", setOf("NFO|$sym|MIS"), ArmLive.lostExitsForTest())
        val sells = kite.placed.count { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }
        watch()
        assertEquals("nothing more sold while it is looked for", sells, kite.placed.count { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" })
    }

    /**
     * B6-G's [SquareOffExit.armLimitStale] with the arm's own exit: the arm's LIMIT exit (MARKET blocked) left resting when its own cancel was
     * lost, its stop already out. At the expiry backstop, 60 s on, the square-off cancels that stale LIMIT (marked as the app's own) and sells;
     * while its sale works, the arm never puts its stop back beside it (the room its working sale leaves is none) - one sale, never two.
     */
    @Test fun anOrphanedArmLimitTakenOverAtTheBackstopNeverGetsTheArmsStopBackBesideTheSale() {
        val today = Market.today()
        val s = "BANKNIFTY26NOV52000CE"
        kite.instruments += FakeKite.Ins(22_000_050, s, "BANKNIFTY", today, 52_000.0, "CE", 30)
        kite.quote("NFO:$s", 200.0, 199.95, 200.05)
        java.io.File(context.filesDir, "kite_instruments.json").delete(); runBlocking { Broker.instruments() }
        val c = Paper.Contract("BANKNIFTY-TEST-52000CE-X", "BANKNIFTY", today, 52_000.0, Right.CE, 30, "NSE_FO|TESTX")
        ArmLive.entered(ask(donchian, "orph", c = c))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        // Its exit: MARKET blocked, sent as a LIMIT that rests; its own cancel of it after its wait is lost - left resting, the stop out.
        kite.nextPlace(reply = FakeKite.Reply.INPUT_EXCEPTION, message = "MIS MARKET orders are blocked for this BANKNIFTY contract due to its open interest (OI) being less than 100 lots.")
        kite.nextPlace(outcome = FakeKite.Outcome.OPEN)
        kite.afterNext("/orders/regular") { kite.afterNext("/orders/regular") { kite.cancelsLost = 1 } }
        legs["orph"] = donchian to 0
        watch()
        assertEquals("its stop out for the exit", "CANCELLED", kite.order(sid).status)
        val lim = kite.orders.values.single { it.type == "LIMIT" && it.tag == LiveMirror.TAG && it.side == "SELL" }
        assertTrue("left resting", lim.working)
        // The backstop, a minute on: the stale LIMIT is the square-off's to take over; its own sale rests a moment.
        val t0 = Market.now().toInstant()
        Market.testClock = java.time.Clock.fixed(t0.plusSeconds(60), com.optionslab.engine.IST)
        kite.nextPlace(outcome = FakeKite.Outcome.OPEN)
        ExpirySquareOff.testNow = today.atTime(15, 8).atZone(com.optionslab.engine.IST)
        runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
        fun expirySells() = kite.placed.filter { it.form["tag"] == "iraalgoexpiry" }
        assertEquals("the stale LIMIT taken out", "CANCELLED", kite.order(lim.id).status)
        assertEquals("one square-off sale", 1, expirySells().size)
        val stops = kite.placed.count { it.form["order_type"] == "SL" }
        fun armSells() = kite.placed.count { it.form["tag"] == LiveMirror.TAG && it.form["transaction_type"] == "SELL" && it.form["order_type"] != "SL" }
        val armSold = armSells()
        later(5); watch()
        later(20); watch()
        assertEquals("no stop put back beside the square-off's sale", stops, kite.placed.count { it.form["order_type"] == "SL" })
        assertEquals("nothing sold by the arm beside it", armSold, armSells())
        assertEquals("one square-off sale, never two", 1, expirySells().size)
    }

    /** B6-G merge: one rule for the row's stop owners - a name ending "-stop" is a stop going in, never a sale. */
    @Test fun everyStopOwnerIsToldByItsSuffix() {
        for (o in listOf(ArmLive.STOP_CLAIM_OWNER, Protections.STOP_CLAIM_OWNER, OrbArms.CLAIM_STOP_OWNER, PineAuto.CLAIM_STOP_OWNER, Strategies.CLAIM_STOP_OWNER))
            assertTrue(o, SquareOffExit.isStopOwner(o))
        for (o in listOf(ArmLive.CLAIM_OWNER, Protections.CLAIM_OWNER, OrbArms.CLAIM_OWNER, PineAuto.CLAIM_OWNER, Strategies.CLAIM_OWNER, "iraalgoflat",
                "iraalgoexpiry", "${SquareOffExit.BOSS_OWNER}#ab12cd34", null))
            assertFalse("$o", SquareOffExit.isStopOwner(o))
    }

    // ---- seventh round of the B6 review ----------------------------------------------------------------------------------------

    /**
     * #4: Boss's own exit on the arm's row whose reply was lost may be selling - the arm leaves the contract alone (hands off) and never rests
     * its stop again beside it while it is looked for; given up on after a good read 2 minutes on, the stop goes back.
     */
    @Test fun theArmsStopIsNotRestedAgainWhileBossesLostExitMayBeSelling() {
        ArmLive.entered(ask(donchian, "boss"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        runBlocking { ArmLive.markOwnCancels(listOf(sid)) }                   // his exit's takeover: marked, then cancelled
        runBlocking { Broker.cancel(sid) }
        val key = SquareOffExit.bossLostKey("NFO", sym, "MIS")
        Broker.lostSearchInlineForTest = true; Broker.lostLookGapMsForTest = 0L
        Broker.noteLost(key, com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 30, 30, "MIS", "MARKET", null), listOf(sid), "Your exit")
        assertTrue("no stop placed while it may be selling", ArmLive.noStopBeside("NFO", sym))
        assertFalse("the arm's own watch goes on (only stops are held)", ArmLive.handsOffNow("NFO", sym))
        later(20); watch()
        later(20); watch()
        assertEquals("no stop beside his exit", 0, kite.orders.values.count { it.working && it.side == "SELL" })
        assertEquals("its level watched by the app", 170.0, view().openReal.single().stopTrigger!!, 1e-9)
        assertTrue("still looked for", Broker.lostPending(key))
        Broker.ageLostForTest(key, Broker.LOST_SEARCH_MS + 1_000)
        later(5); watch()
        later(5); watch()
        assertFalse("given up on", Broker.lostPending(key))
        assertFalse(ArmLive.noStopBeside("NFO", sym))
        assertEquals("its stop rested again", 1, kite.orders.values.count { it.working && it.side == "SELL" && it.type == "SL" })
        assertEquals(30, held())
    }

    /**
     * Seventh round, review: while Boss's lost exit is looked for, the arm's level is still watched at full cadence (the open-positions rule) -
     * the price through its stop, the app sells there itself; only its stop is not rested beside his exit. Eighth round, M1: it sells only what
     * his exit leaves - here his own 30 on the row beside the arm's 30, his exit for 30: the arm's 30 go, the row ends flat.
     */
    @Test fun theArmStillSellsAtItsLevelWhileBossesLostExitIsLookedFor() {
        ArmLive.entered(ask(donchian, "boss-level"))
        watch()
        kite.position(sym, 60, 200.0, product = "MIS")                      // Boss's own 30 on the same row
        val sid = view().openReal.single().stopOrderId!!
        runBlocking { ArmLive.markOwnCancels(listOf(sid)) }
        runBlocking { Broker.cancel(sid) }
        val key = SquareOffExit.bossLostKey("NFO", sym, "MIS")
        Broker.lostSearchInlineForTest = true; Broker.lostLookGapMsForTest = 0L
        Broker.noteLost(key, com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 30, 30, "MIS", "MARKET", null), listOf(sid), "Your exit")
        later(20); watch()
        assertEquals("no stop beside his exit", 0, kite.orders.values.count { it.working && it.side == "SELL" })
        val before = marketSells()
        kite.quote("NFO:$sym", 169.0, 168.95, 169.05)                      // at its level: the app sells there itself
        later(5); watch()
        assertEquals("sold at its level", before + 1, marketSells())
        assertEquals("the arm's 30 only", "30", kite.placed.last { it.form["order_type"] == "MARKET" }.form["quantity"])
        assertEquals("his 30 left for his own exit", 30, held())
    }

    /**
     * Eighth round, M1 (the gap that could sell twice): Boss's exit reached Zerodha and sold the row, but its reply was lost and neither the
     * order book nor the positions show it yet. The arm's look goes on; the price touches its level - it sells nothing beside his exit (what
     * Zerodha shows held, less what his exit may be selling), and the account never goes short.
     */
    @Test fun theArmsLevelExitSellsNothingBesideBossesLostExitThatWent() {
        ArmLive.entered(ask(donchian, "boss-went"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        runBlocking { ArmLive.markOwnCancels(listOf(sid)) }                  // his exit's takeover
        runBlocking { Broker.cancel(sid) }
        // His exit: at Zerodha and filled, but hidden from both books.
        kite.trailReads = 1_000; kite.positionsTrailReads = 1_000
        val his = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 30, 30, "MIS", "MARKET", null)
        runBlocking { Broker.placeOrder(his, exit = true) }
        ExitCoordinator.restartForTest()                                     // the exit registry's minute is over; his search is not
        Broker.lostSearchInlineForTest = true; Broker.lostLookGapMsForTest = 0L
        SquareOffExit.noteBossLost(his, kite.orders.keys.toList(), null, Origins.MANUAL)
        assertEquals("sold at Zerodha", 0, kite.positions.getValue("NFO:$sym:MIS").first)
        val before = marketSells()
        kite.quote("NFO:$sym", 169.0, 168.95, 169.05)                      // the arm's level
        later(20); watch()
        later(5); watch()
        assertEquals("nothing sold beside his exit", before, marketSells())
        assertEquals("never short", 0, kite.positions.getValue("NFO:$sym:MIS").first)
        assertEquals("no stop beside it either", 0, kite.orders.values.count { it.working && it.side == "SELL" })
    }

    /**
     * Eighth round (missing test): the expiry square-off counts Boss's own lost exit sale - at 15:08 it sells nothing beside it, and once his
     * sale shows it finds the row flat: one sale, never two.
     */
    @Test fun theExpirySquareOffCountsBossesLostExitSale() {
        val today = Market.today()
        val s = "BANKNIFTY26NOV52000CE"
        kite.instruments += FakeKite.Ins(22_000_051, s, "BANKNIFTY", today, 52_000.0, "CE", 30)
        kite.quote("NFO:$s", 200.0, 199.95, 200.05)
        java.io.File(context.filesDir, "kite_instruments.json").delete(); runBlocking { Broker.instruments() }
        kite.position(s, 30, 200.0, product = "MIS")
        kite.trailReads = 1_000; kite.positionsTrailReads = 1_000
        val his = com.optionslab.engine.Kite.Order(s, com.optionslab.engine.Kite.Side.SELL, 30, 30, "MIS", "MARKET", null)
        runBlocking { Broker.placeOrder(his, exit = true) }
        ExitCoordinator.restartForTest()                                     // the exit registry's minute is over; his search is not
        SquareOffExit.noteBossLost(his, kite.orders.keys.toList(), null, Origins.MANUAL)
        assertEquals("counted for the square-offs", 30, SquareOffExit.lostPendingProviders.getValue(SquareOffExit.BOSS_OWNER)(s, "MIS"))
        ExpirySquareOff.testNow = today.atTime(15, 8).atZone(com.optionslab.engine.IST)
        runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
        fun expirySells() = kite.placed.count { it.form["tag"] == "iraalgoexpiry" }
        assertEquals("nothing sold beside his lost exit", 0, expirySells())
        kite.showInBook(); kite.positionsCatchUp()
        ExpirySquareOff.testNow = today.atTime(15, 9).atZone(com.optionslab.engine.IST)
        runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
        assertEquals(0, expirySells())
        assertEquals("flat, never short", 0, held(s))
    }

    /**
     * 24 Nov 2026 (B6-I round 6, item 2): the orders an arm's due exit may cancel to make room - a plain LIMIT of Boss's by the one tag rule (no
     * tag, another app's, his own "iraalgo" from the app's order screen), never one the app sent by itself, never one in [mine] (its own, and
     * the orders a fired GTT of the app's - or one not known to be his - placed), never a stop.
     */
    @Test fun anArmsExitCancelsOnlyBosssOwnLimitsByTheOneTagRule() {
        fun row(tag: String, type: String = "LIMIT", status: String = "OPEN", id: String = "o1") =
            Broker.OrderRow(id, "NIFTY26N0925150CE", "SELL", 75, 0, 250.0, 0.0, status, "", "", product = "MIS", type = type, tag = tag)
        for (t in listOf("", "iraalgo", "sensibull")) assertTrue("his: '$t'", ArmLive.cancellable(row(t), emptySet()))
        for (t in listOf("iraarm", "iraorb", "iraprotect", "irapine", "iraalgostrat", "iraalgoexpiry", "iraalgoflat"))
            assertFalse("the app's: '$t'", ArmLive.cancellable(row(t), emptySet()))
        assertFalse("a GTT's order of the app's, or one not known to be his", ArmLive.cancellable(row(""), setOf("o1")))
        assertFalse("never a stop", ArmLive.cancellable(row("", type = "SL", status = "TRIGGER PENDING"), emptySet()))
        assertFalse("never one no longer working", ArmLive.cancellable(row("", status = "COMPLETE"), emptySet()))
    }
}
