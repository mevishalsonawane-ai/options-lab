package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Outcome
import com.optionslab.app.testing.FakeKite.Reply
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.ira.ExitRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 20 Oct, enhancement round 2 (idea 1): the exit coordinator ([ExitCoordinator]) against the fake Kite. The door notes every exit
 * (refused ones dropped, lost replies looked for); a read missing one is read again only when the switch is on; two senders on one
 * contract take turns; Boss's own exit never waits; a restart starts empty; nothing on the order path reads the settings vault.
 * The races (an exit filling between the reads, a part fill, the book trailing the order, a GTT that fired, Boss's own exit at the
 * same moment) are driven through the 15:05 expiry square-off, whose pass reads the books once for every contract.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExitCoordinatorTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val ce = "NIFTY26SEP24500CE"
    private lateinit var today: java.time.LocalDate

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        today = Market.today()
        kite.instruments += FakeKite.Ins(1_001, ce, "NIFTY", today, 24_500.0, "CE", 75)
        kite.quote("NFO:$ce", 10.0, 9.95, 10.05)
        runBlocking { Broker.instruments() }
        AutomationSupport.resetExpiryTold()
        com.optionslab.app.testing.Background.clearAlerts()
        ExpirySquareOff.armOwns = { false }
        ExitCoordinator.resetForTest()
        kite.requests.clear()
        at(15, 6)
    }

    @After fun down() {
        ExpirySquareOff.armOwns = { ArmLive.ownerOf(it) != null }
        ExitCoordinator.resetForTest()
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(h: Int, m: Int) { ExpirySquareOff.testNow = today.atTime(h, m).atZone(java.time.ZoneId.of("Asia/Kolkata")) }
    private fun pass() = runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
    private fun held() = kite.positions["NFO:$ce:NRML"]?.first ?: 0
    private fun sells() = kite.placed.filter { it.form["transaction_type"] == "SELL" }.sumOf { it.form["quantity"]!!.toInt() }
    /** Boss's own exit from the Trade screen (no bot tag), through the same door. */
    private fun bossSells(qty: Int) = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, qty, 75, "NRML", "MARKET", null, 0.05, "NFO", ""), exit = true) }
    private fun orderRead() = kite.requests.count { it.method == "GET" && it.path == "/orders" }

    // ---- the door's notes ---------------------------------------------------------------------------------------------------

    @Test fun anExitSentIsLookedForUntilTheBookShowsIt() = runBlocking {
        kite.position(ce, 75, 20.0)
        kite.trailReads = 1
        val id = bossSells(75)
        assertEquals(listOf(id), ExitCoordinator.pendingForTest().map { it.id })
        assertEquals("yours", ExitCoordinator.pendingForTest().single().by)
        Broker.orders()                                            // the book trails: not shown yet
        assertEquals(1, ExitCoordinator.pendingForTest().size)
        Broker.orders()
        assertTrue("shown: done with", ExitCoordinator.pendingForTest().isEmpty())
    }

    @Test fun aRefusedExitIsNotLookedForAndAnEntryIsNeverNoted() = runBlocking {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Quantity exceeds the freeze limit.")
        runCatching { bossSells(75) }
        assertTrue(ExitCoordinator.pendingForTest().isEmpty())
        Broker.placeOrder(Kite.Order(ce, Kite.Side.BUY, 75, 75, "NRML", "MARKET", null, 0.05, "NFO", ""))
        assertTrue("an entry is no exit", ExitCoordinator.pendingForTest().isEmpty())
        assertEquals(1, ExitCoordinator.countsForTest().sent)
    }

    @Test fun aLostReplyIsLookedForAndTheBookShowingItEndsIt() = runBlocking {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        runCatching { bossSells(75) }
        assertEquals(ExitRegistry.State.LOST, ExitCoordinator.pendingForTest().single().state)
        Broker.orders()
        assertTrue("the order in the book is the lost reply's", ExitCoordinator.pendingForTest().isEmpty())
    }

    @Test fun aGttPlacedIsLookedForUntilTheListShowsIt() = runBlocking {
        kite.position(ce, 75, 20.0)
        Broker.placeGtt(Kite.protect(Kite.Spec("NFO", ce, 0L, 75, 0.05), "NRML", 75, 10.0, 8.0, null).first!!)
        assertEquals(ExitRegistry.Kind.GTT, ExitCoordinator.pendingForTest().single().kind)
        Broker.gtts()
        assertTrue(ExitCoordinator.pendingForTest().isEmpty())
    }

    @Test fun aRestartStartsEmptyAndNothingWaits() = runBlocking {
        kite.position(ce, 75, 20.0)
        kite.trailReads = 5
        bossSells(75)
        ExitCoordinator.restartForTest()                          // the phone restarted: memory gone, Zerodha's book is the truth
        ExitCoordinator.resetForTest(on = true)
        kite.requests.clear()
        ExitCoordinator.orders("NFO", ce, "test")
        assertEquals("one read, no waiting", 1, orderRead())
    }

    // ---- the settled read -----------------------------------------------------------------------------------------------------

    @Test fun inShadowAReadMissingAnExitIsReadOnceAndCounted() = runBlocking {
        kite.position(ce, 75, 20.0)
        kite.trailReads = 2
        bossSells(75)
        kite.requests.clear()
        val rows = ExitCoordinator.orders("NFO", ce, "test")
        assertTrue("the trailing book as it is", rows.isEmpty())
        assertEquals(1, orderRead())
        assertEquals(1, ExitCoordinator.countsForTest().missedInShadow)
        assertTrue(ExitCoordinator.line(), ExitCoordinator.line().contains("shadow (logged only)"))
    }

    @Test fun onAReadMissingAnExitIsReadAgainUntilItShows() = runBlocking {
        ExitCoordinator.resetForTest(on = true)
        kite.position(ce, 75, 20.0)
        kite.trailReads = 2
        val id = bossSells(75)
        kite.requests.clear()
        val rows = ExitCoordinator.orders("NFO", ce, "test")
        assertEquals(listOf(id), rows.map { it.id })
        assertEquals(3, orderRead())
        assertEquals(1, ExitCoordinator.countsForTest().readAgain)
    }

    /** A lost reply that never became an order: read again for under 2 s, then the exit goes on the last read (never blocked). */
    @Test fun onAnExitThatNeverShowsIsLetThroughQuickly() = runBlocking {
        ExitCoordinator.resetForTest(on = true)
        kite.position(ce, 75, 20.0)
        kite.nextPlace(reply = Reply.NETWORK_EXCEPTION)            // not a definite refusal: it may exist
        runCatching { bossSells(75) }
        kite.requests.clear()
        val t0 = System.currentTimeMillis()
        ExitCoordinator.orders("NFO", ce, "test")
        assertTrue(System.currentTimeMillis() - t0 < 3_000)
        assertEquals(4, orderRead())
        assertEquals(1, ExitCoordinator.countsForTest().letThrough)
    }

    @Test fun anotherContractsExitNeverMakesARead() = runBlocking {
        ExitCoordinator.resetForTest(on = true)
        kite.position(ce, 75, 20.0)
        kite.trailReads = 3
        bossSells(75)
        kite.requests.clear()
        ExitCoordinator.orders("NFO", "NIFTY26SEP24600CE", "test")
        assertEquals(1, orderRead())
    }

    // ---- races through a real sender (the expiry square-off) ----------------------------------------------------------------

    /**
     * Boss's own exit a moment before the pass, resting (a thin book) and not in the book's list yet: in shadow the pass sells it
     * again, as before this round (and when Boss's exit fills too, the account is short); on, it never does.
     */
    @Test fun inShadowBossesExitTheBookHasNotShownIsSoldAgainAsBefore() {
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.OPEN)
        kite.trailReads = 1
        bossSells(75)
        pass()
        assertEquals("shadow changes nothing: the second sell went", 150, sells())
        kite.fill(kite.orders.values.first().id)
        assertEquals("the short the coordinator exists to stop", -75, held())
        assertEquals(1, ExitCoordinator.countsForTest().missedInShadow)
    }

    @Test fun onBossesExitTheBookHasNotShownIsNeverSoldAgain() {
        ExitCoordinator.resetForTest(on = true)
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.OPEN)
        kite.trailReads = 1
        bossSells(75)
        pass()
        assertEquals("left to the next pass", 75, sells())
        kite.fill(kite.orders.values.single().id)                 // Boss's exit fills
        at(15, 7); pass()
        assertEquals("nothing more", 75, sells())
        assertEquals("flat, never short", 0, held())
        assertTrue(ExitCoordinator.countsForTest().readAgain >= 1)
    }

    /** A part fill: Boss's exit filled 25 of 75 and still works for 50, not in the list yet: never sold beside it. */
    @Test fun onAPartFilledExitNotShownYetIsNeverDoubled() {
        ExitCoordinator.resetForTest(on = true)
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.PARTIAL, partialQty = 25)
        kite.trailReads = 1
        bossSells(75)
        assertEquals(50, held())
        pass()
        assertEquals("nothing sent beside it", 75, sells())
        kite.fill(kite.orders.values.single().id)
        at(15, 7); pass()
        assertEquals(75, sells())
        assertEquals("flat, never short", 0, held())
    }

    /**
     * Another sender inside its turn, its exit resting and Zerodha slow to answer its POST: the pass waits for the turn, then
     * sees an exit it read before it was answered and leaves the contract to the next pass. One sell only.
     */
    @Test fun onTwoSendersAtOnceTheSecondWaitsItsTurnAndSellsNothingMore() = runBlocking {
        ExitCoordinator.resetForTest(on = true)
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.OPEN)
        kite.slowMs["/orders/regular"] = 400
        val inside = CompletableDeferred<Unit>()
        val arm = async(Dispatchers.IO) {
            ExitCoordinator.turn("NFO", ce, "arm") {
                inside.complete(Unit)
                Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 75, 75, "NRML", "MARKET", null, 0.05, "NFO", "iraarm"), exit = true)
            }
        }
        inside.await()
        ExpirySquareOff.maybeRun(context, AppSettings.load())
        arm.await()
        kite.slowMs.clear()
        assertEquals("one sell only", 75, sells())
        assertTrue(ExitCoordinator.countsForTest().waited >= 1)
        kite.fill(kite.orders.values.single().id)
        assertEquals(0, held())
    }

    /**
     * Review: the pass reads the books, then an arm (inside its turn) sends its exit and another worker reads the orders, which
     * shows it. The pass's own read never had it: it must still leave the contract to its next look, never sell beside it.
     */
    @Test fun onAnotherWorkersReadNeverVouchesForThePassesOlderRead() = runBlocking {
        ExitCoordinator.resetForTest(on = true)
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.OPEN)
        val inside = CompletableDeferred<Unit>()
        val arm = async(Dispatchers.IO) {
            ExitCoordinator.turn("NFO", ce, "arm") {
                inside.complete(Unit)
                while (kite.requests.none { it.path == "/portfolio/positions" }) kotlinx.coroutines.delay(5)   // the pass has read both books
                Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 75, 75, "NRML", "MARKET", null, 0.05, "NFO", "iraarm"), exit = true)
                Broker.orders()                                    // another worker's read: it shows the arm's exit
            }
        }
        inside.await()
        ExpirySquareOff.maybeRun(context, AppSettings.load())
        arm.await()
        assertEquals("one sell only", 75, sells())
        kite.fill(kite.orders.values.single().id)
        assertEquals(0, held())
    }

    @Test fun inShadowTwoSendersAtOnceAreOnlyCounted() = runBlocking {
        kite.position(ce, 75, 20.0)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val arm = async(Dispatchers.IO) { ExitCoordinator.turn("NFO", ce, "arm") { inside.complete(Unit); release.await() } }
        inside.await()
        val t0 = System.currentTimeMillis()
        ExpirySquareOff.maybeRun(context, AppSettings.load())
        assertTrue("nothing waited in shadow", System.currentTimeMillis() - t0 < 3_000)
        release.complete(Unit); arm.await()
        assertEquals(1, ExitCoordinator.countsForTest().twoAtOnce)
        assertEquals(75, sells())
    }

    /** A sender stuck in its turn never holds an exit back past the longest wait. */
    @Test fun onAStuckSenderNeverHoldsTheExitBackPastTheWait() = runBlocking {
        ExitCoordinator.resetForTest(on = true)
        ExitCoordinator.waitForTest(200)
        kite.position(ce, 75, 20.0)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stuck = async(Dispatchers.IO) { ExitCoordinator.turn("NFO", ce, "stuck") { inside.complete(Unit); release.await() } }
        inside.await()
        ExpirySquareOff.maybeRun(context, AppSettings.load())
        assertEquals("the exit went", 75, sells())
        assertEquals(1, ExitCoordinator.countsForTest().turnLetThrough)
        release.complete(Unit); stuck.await()
    }

    /** An exit filling between the pass's two reads: still one sell (the orders are read first), the coordinator on or off. */
    @Test fun onAnExitFillingBetweenTheReadsIsStillNeverDoubled() {
        ExitCoordinator.resetForTest(on = true)
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.OPEN)
        pass()
        val first = kite.orders.values.single()
        at(15, 7)
        kite.afterNext("/portfolio/positions") { kite.fill(first.id) }
        pass()
        assertEquals(75, sells())
        assertEquals(0, held())
    }

    /** A GTT that fired (Zerodha's own order, never sent from the phone) is not waited for: the pass handles it as before. */
    @Test fun onAFiredGttsOrderIsNeverWaitedFor() = runBlocking {
        ExitCoordinator.resetForTest(on = true)
        kite.position(ce, 75, 20.0)
        val gid = Broker.placeGtt(Kite.protect(Kite.Spec("NFO", ce, 0L, 75, 0.05), "NRML", 75, 10.0, 9.0, null).first!!)
        Broker.gtts()
        kite.quote("NFO:$ce", 8.8, 8.75, 8.85)
        kite.triggerGtt(gid)                                       // its LIMIT under the bid: fills
        kite.requests.clear()
        pass()
        assertEquals("the fired GTT sold it: nothing more", 0, sells())
        assertEquals(0, held())
        assertEquals(0, ExitCoordinator.countsForTest().readAgain + ExitCoordinator.countsForTest().letThrough)
    }

    /** Boss's own exit never waits for a turn, even with a sender stuck inside one. */
    @Test fun bossesOwnExitNeverWaits() = runBlocking {
        ExitCoordinator.resetForTest(on = true)
        kite.position(ce, 75, 20.0)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stuck = async(Dispatchers.IO) { ExitCoordinator.turn("NFO", ce, "stuck") { inside.complete(Unit); release.await() } }
        inside.await()
        val t0 = System.currentTimeMillis()
        bossSells(75)
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
        release.complete(Unit); stuck.await()
    }

    // ---- the order path ---------------------------------------------------------------------------------------------------------

    @Test fun anExitAndItsSettledReadReadNothingFromTheSettingsVault() = runBlocking {
        ExitCoordinator.resetForTest(on = true)
        kite.position(ce, 150, 20.0)
        bossSells(75)                                              // warm the session
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        kite.trailReads = 1
        val pass = ExitCoordinator.enter("NFO", ce, "test")
        bossSells(75)
        ExitCoordinator.orders("NFO", ce, "test")
        pass.leave()
        SecurePrefs.countedThread = null
        assertEquals(0L, SecurePrefs.readsForTest.get())
    }

    @Test fun theSwitchIsSavedAndSaidPlainly() {
        assertTrue(ExitCoordinator.setOn(true).startsWith("Exit coordinator on"))
        assertTrue(ExitCoordinator.isOn())
        ExitCoordinator.resetForTest()
        ExitCoordinator.load()
        assertTrue("read back from the settings", ExitCoordinator.isOn())
        assertTrue(ExitCoordinator.setOn(false).contains("shadow"))
        ExitCoordinator.load()
        assertTrue(!ExitCoordinator.isOn())
    }
}
