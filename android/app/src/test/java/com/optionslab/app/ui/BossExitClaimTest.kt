package com.optionslab.app.ui

import android.app.Application
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Protections
import com.optionslab.app.data.SquareOffExit
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.BrokerArea
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 22 Nov 2026 (B6 review, second round, m-6): Boss's exit from the app ([AppModel]'s takeover of the exits resting on a contract) could sell
 * beside an app-watched target's sale already past its own check. Now it takes the position row's exit claim ([SquareOffExit.claimExit],
 * "boss"), waiting a few seconds at most while another of the app's sales holds it - still held, nothing is sent and Boss is told a sale is
 * already going - and an exit sale that filled a moment ago, which the positions do not show yet, is counted as sold (the lag guard).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class BossExitClaimTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.Watchdog()
    private lateinit var kite: FakeKite
    private val store = androidx.lifecycle.ViewModelStore()
    private val expiry = com.optionslab.app.data.Market.today().plusDays(9)
    private val ce = BrokerArea.symbol("NIFTY", expiry, 24_500.0, "CE")

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        BrokerArea.clearAlerts()
        SecurePrefs.put("g.cutoff", -1)
        BrokerArea.listNifty(kite, listOf(expiry))
        kite.quote("NFO:$ce", 100.0, 99.95, 100.05)
        kite.position(ce, 75, 90.0)
    }

    @After fun down() {
        store.clear()
        BrokerArea.settle(1_500)
        SquareOffExit.resetForTest(gap = 0L)
        kite.close()
        assertFalse("Kite REST calls must all go to the fake", "api.kite.trade" in NetworkGuard.blocked)
    }

    private fun model(): AppModel {
        val m = BrokerArea.model(context as Application, store)
        m.loadAccount(quiet = true)
        BrokerArea.await("the account") { m.account.value as? Load.Done }
        return m
    }

    private fun position(sym: String) = runBlocking { Broker.positions() }.first { it.symbol == sym }

    private fun protectAndPlan(m: AppModel): Protections.Item {
        val said = runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 85.0, null, 130.0) }
        assertTrue(said, said.startsWith("Protected"))
        val p = runBlocking { Protections.active() }.single()
        m.planSquareOff(position(ce))
        BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        return p
    }

    private fun refused(m: AppModel): String = BrokerArea.await("the refusal", 40_000) {
        (m.sending.value as? Load.Failed)?.why ?: (m.sending.value as? Load.Done)?.let { throw AssertionError("sent: $it") }
    }

    private fun sent(m: AppModel): List<Broker.Fill> = BrokerArea.await("the send", 40_000) {
        (m.sending.value as? Load.Done)?.value ?: (m.sending.value as? Load.Failed)?.let { throw AssertionError(it.why) }
    }

    /** A protection's target sale holds the row past the wait: nothing is taken out or sent, and Boss is told a sale is already going. */
    @Test fun anExitWhileAnotherSaleHoldsTheRowIsNotSentAndSaid() {
        val m = model()
        val p = protectAndPlan(m)
        val claim = SquareOffExit.claimExit("NFO", ce, "NRML", Protections.CLAIM_OWNER)!!
        val placed = kite.placed.size
        try {
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.startsWith("Not sent: a protection's target sale is already selling $ce"))
            assertTrue("its stop left resting", kite.order(p.stopOrderId!!).working)
            assertTrue("nothing cancelled or modified", kite.writes.none { it.method == "DELETE" || it.method == "PUT" })
            assertEquals("no sale", placed, kite.placed.size)
        } finally { claim.release() }
        assertEquals("let go", null, SquareOffExit.exitClaimedBy("NFO", ce, "NRML"))
    }

    /**
     * Third round: the row is Boss's from before anything resting on it is cancelled until his leg is placed - a square-off's pass meanwhile
     * cannot take it (so it sells nothing beside him) - noted as starting for the square-offs, and let go once the leg is placed.
     */
    @Test fun bossesExitHoldsTheRowFromTheCancelUntilTheLegIsPlaced() {
        val m = model()
        val p = protectAndPlan(m)
        @Suppress("UNCHECKED_CAST")
        val started = SquareOffExit::class.java.getDeclaredField("salesStarted").apply { isAccessible = true }.get(SquareOffExit) as Map<String, Long>
        val before = started["$ce:NRML"] ?: 0L
        var atCancel: String? = "not seen"; var squareOffAtCancel: Boolean? = null
        var atPlace: String? = "not seen"; var squareOffAtPlace: Boolean? = null; var startedAtPlace = -1L
        kite.afterNext("/orders/regular/${p.stopOrderId}") {
            atCancel = SquareOffExit.exitClaimedBy("NFO", ce, "NRML")
            squareOffAtCancel = SquareOffExit.claimExit("NFO", ce, "NRML", "iraalgoflat")?.also { it.release() } != null
        }
        kite.afterNext("/orders/regular") {
            atPlace = SquareOffExit.exitClaimedBy("NFO", ce, "NRML")
            squareOffAtPlace = SquareOffExit.claimExit("NFO", ce, "NRML", "iraalgoexpiry")?.also { it.release() } != null
            startedAtPlace = started["$ce:NRML"] ?: 0L
        }
        m.sendPlan()
        assertEquals("COMPLETE", sent(m).single().status)
        assertTrue("Boss's while its stop came out: $atCancel", atCancel!!.startsWith("${SquareOffExit.BOSS_OWNER}#"))
        assertEquals("a square-off's pass refused then", false, squareOffAtCancel)
        assertEquals("still this send's while the leg went", atCancel, atPlace)
        assertEquals("and refused then", false, squareOffAtPlace)
        assertEquals("noted as starting before it went", before + 1, startedAtPlace)
        assertEquals("let go once placed", null, SquareOffExit.exitClaimedBy("NFO", ce, "NRML"))
        assertEquals(0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /**
     * Third round: with nothing resting on the contract, the leg is sized under the claim on fresh reads through the lag guard - a target's sale
     * that filled just before Boss confirmed, which the positions do not show yet, leaves nothing to sell: nothing is sent, and he is told why.
     */
    @Test fun anExitWithNothingRestingSubtractsAFillThePositionsDoNotShowYet() {
        kite.positionsTrailReads = 100_000
        val sold = runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(ce, com.optionslab.engine.Kite.Side.SELL, 75, 75, "NRML", "MARKET",
            null, 0.05, "NFO", "iraprotect"), exit = true) }                 // a protection's target sale: filled, the positions still show 75
        assertEquals("COMPLETE", kite.order(sold).status)
        val m = model()
        m.planSquareOff(position(ce))
        BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        val placed = kite.placed.size
        m.sendPlan()
        val why = refused(m)
        assertTrue(why, why.startsWith("Not sent: Zerodha shows $ce closed") && why.contains("nothing of it is left to sell"))
        assertTrue("what he can do", why.contains("If Kite still shows it open, close it there."))
        assertEquals("nothing sent", placed, kite.placed.size)
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        assertEquals(null, SquareOffExit.exitClaimedBy("NFO", ce, "NRML"))
    }

    /**
     * Only part of the reviewed exit is left (the rest sold a moment ago, not shown yet): nothing is sent. Fourth round (m2): the part that can
     * go is offered as a new exit for him to review - and it goes only once he confirms it.
     */
    @Test fun anExitWithOnlyPartLeftOffersThatPart() {
        kite.position(ce, 150, 90.0)
        kite.positionsTrailReads = 100_000
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(ce, com.optionslab.engine.Kite.Side.SELL, 75, 75, "NRML", "MARKET",
            null, 0.05, "NFO", "iraprotect"), exit = true) }
        val m = model()
        m.planSquareOff(position(ce))
        val plan = BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        assertEquals(150, plan.legs.sumOf { it.quantity })
        val placed = kite.placed.size
        m.sendPlan()
        val why = refused(m)
        assertTrue(why, why.contains("Zerodha holds only 75 of $ce now") && why.contains("Only 75 can go safely now"))
        assertTrue(why, why.contains("If it must all go now, close it in Kite."))
        assertEquals("nothing sent", placed, kite.placed.size)
        val offered = BrokerArea.await("the offer") { (m.plan.value as? Load.Done)?.value?.takeIf { p -> p.legs.sumOf { it.quantity } == 75 } }
        assertTrue(offered.title, offered.title.endsWith("the 75 that can go now"))
        assertEquals(75, kite.positions.getValue("NFO:$ce:NRML").first)
        m.sendPlan()                                                         // Boss confirms the offer
        assertEquals("COMPLETE", sent(m).single().status)
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /**
     * Review: another exit sale on the row, sent from the phone a moment ago with its reply lost and not in Zerodha's book yet: Boss's leg is not
     * sent beside it (as the square-offs hold back under the same claim), and he is told to send again in a few seconds.
     */
    @Test fun anExitIsNotSentBesideAnotherSaleNotInTheBookYet() {
        val m = model()
        m.planSquareOff(position(ce))
        BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        kite.trailReads = 1_000; kite.positionsTrailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        runCatching { runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(ce, com.optionslab.engine.Kite.Side.SELL, 75, 75, "NRML", "MARKET",
            null, 0.05, "NFO", "iraprotect"), exit = true) } }              // a protection's sale: its reply lost, not in the book
        val placed = kite.placed.size
        m.sendPlan()
        val why = refused(m)
        assertTrue(why, why.contains("is not in Zerodha's order book yet"))
        assertEquals("nothing sent", placed, kite.placed.size)
    }

    /** Review: two of Boss's own exits on one row at once (the screen and Jarvis, a second tap) never both go: each send has its own name. */
    @Test fun anotherOfBossesOwnExitsOnTheRowHoldsItBack() {
        val m = model()
        m.planSquareOff(position(ce))
        BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        val other = SquareOffExit.claimExit("NFO", ce, "NRML", "${SquareOffExit.BOSS_OWNER}#other")!!
        val placed = kite.placed.size
        try {
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.startsWith("Not sent: your exit from the app is already selling $ce"))
            assertEquals("nothing sent", placed, kite.placed.size)
        } finally { other.release() }
    }

    /** Review: the exit refused for margin is sent again only under the row's claim - another seller holding it then: not sent again, said. */
    @Test fun aMarginResendWaitsForTheRowAndIsNotSentBesideAnotherSeller() {
        val m = model()
        m.planSquareOff(position(ce))
        BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        kite.nextPlace(outcome = FakeKite.Outcome.REJECT, message = "Insufficient funds. Required margin is 95000.00 but available margin is 1200.00.")
        var flat: SquareOffExit.ExitClaim? = null
        // A square-off takes the row once the refused leg was placed and Boss let it go (as its fill is awaited).
        kite.afterNext("/orders/regular") {
            val id = kite.orders.keys.last()
            kite.afterNext("/orders/$id") { if (flat == null) flat = SquareOffExit.claimExit("NFO", ce, "NRML", "iraalgoflat") }
        }
        try {
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.contains("sent again was refused: the flat-by is already selling $ce"))
            assertEquals("sent once, never again beside it", 1, kite.placed.count { it.form["transaction_type"] == "SELL" })
        } finally { flat?.release() }
    }

    // ---- fourth round ---------------------------------------------------------------------------------------------------------------

    /**
     * M1: the protection's stop triggers and fills just as the takeover cancels it, the positions trailing: counted as sold - never a second
     * sale, never a short - and Boss is told his stop filled as it came out.
     */
    @Test fun aStopThatFillsAsTheTakeoverCancelsItIsNeverSoldAgain() {
        val m = model()
        val p = protectAndPlan(m)
        kite.positionsTrailReads = 100_000
        kite.afterNext("/orders/regular/${p.stopOrderId}") { kite.fill(p.stopOrderId!!, 85.0) }  // it triggered as the cancel went
        val placed = kite.placed.size
        m.sendPlan()
        val why = refused(m)
        assertTrue(why, why.startsWith("Not sent: your stop at Zerodha filled as it came out (75 sold)"))
        assertTrue(why, why.contains("nothing of it is left to sell"))
        assertEquals("no second sale", placed, kite.placed.size)
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /**
     * m2: a book's own exit sale on the row has not settled (75 of 150 may still be selling): the 75 that can go safely is offered for Boss to
     * confirm - nothing sent until he does - and the words say why, for about how long, and "close it in Kite".
     */
    @Test fun aBooksUnsettledSaleOffersTheSafeQuantity() {
        kite.position(ce, 150, 90.0)
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == ce) 75 else 0 }
        try {
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            val placed = kite.placed.size
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.contains("has not settled yet") && why.contains("within about 2 minutes") && why.contains("Only 75 can go safely now"))
            assertTrue(why, why.endsWith("If it must all go now, close it in Kite."))
            assertEquals("nothing sent", placed, kite.placed.size)
            val offered = BrokerArea.await("the offer") { (m.plan.value as? Load.Done)?.value?.takeIf { p -> p.legs.sumOf { it.quantity } == 75 } }
            assertEquals(listOf(75), offered.legs.map { it.quantity })
        } finally { SquareOffExit.lostPendingProviders.remove("test") }
    }

    /** m2 (review): an offer from a plan in freeze slices - the contract's slices cut to the safe quantity, never more. */
    @Test fun anOfferFromSlicesCutsThemToTheSafeQuantity() {
        kite.position(ce, 2700, 90.0)
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == ce) 900 else 0 }
        try {
            val m = model()
            m.planSquareOff(position(ce))
            val plan = BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            assertEquals(listOf(1800, 900), plan.legs.map { it.quantity })
            val placed = kite.placed.size
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.contains("Only 1800 can go safely now"))
            assertEquals("nothing sent", placed, kite.placed.size)
            val offered = BrokerArea.await("the offer") { (m.plan.value as? Load.Done)?.value?.takeIf { p -> p.legs.sumOf { it.quantity } == 1800 } }
            assertEquals(listOf(1800), offered.legs.map { it.quantity })
        } finally { SquareOffExit.lostPendingProviders.remove("test") }
    }

    /** m2 (review): a leg refused for margin whose re-send finds a book's sale unsettled: the safe part offered, nothing sent again. */
    @Test fun aMarginResendWithABooksUnsettledSaleOffersTheSafeQuantity() {
        kite.position(ce, 150, 90.0)
        val m = model()
        m.planSquareOff(position(ce))
        BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        kite.nextPlace(outcome = FakeKite.Outcome.REJECT, message = "Insufficient funds. Required margin is 95000.00 but available margin is 1200.00.")
        kite.afterNext("/orders/regular") { SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == ce) 75 else 0 } }
        try {
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.contains("refused for margin and not sent again") && why.contains("Only 75 can go safely now"))
            assertEquals("sent once, never again", 1, kite.placed.count { it.form["transaction_type"] == "SELL" })
            val offered = BrokerArea.await("the offer") { (m.plan.value as? Load.Done)?.value?.takeIf { p -> p.legs.sumOf { it.quantity } == 75 } }
            assertEquals(listOf(75), offered.legs.map { it.quantity })
        } finally { SquareOffExit.lostPendingProviders.remove("test") }
    }

    /** m2: a book that cannot tell the size of its own sale yet: refused (never offered), with how long and the Kite advice. */
    @Test fun aBookThatCannotTellYetRefusesWithHowLongAndTheKiteAdvice() {
        SquareOffExit.lostPendingProviders["test"] = { _, _ -> -1 }
        try {
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            val placed = kite.placed.size
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.contains("cannot tell yet") && why.contains("up to 2 minutes after the app starts"))
            assertTrue(why, why.endsWith("If it must go now, close it in Kite."))
            assertEquals("nothing sent", placed, kite.placed.size)
        } finally { SquareOffExit.lostPendingProviders.remove("test") }
    }

    /**
     * m1: Boss's own earlier exit whose answer was lost and which the book does not show yet: his retry reads the book again first, then is
     * refused naming it as his own earlier exit, with the seconds left and the Kite advice.
     */
    @Test fun bossesOwnEarlierLostSendIsNamedAndTheRetryReadsAgainFirst() {
        val m = model()
        m.planSquareOff(position(ce))
        BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        kite.trailReads = 1_000; kite.positionsTrailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT, outcome = FakeKite.Outcome.OPEN)
        runCatching { runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(ce, com.optionslab.engine.Kite.Side.SELL, 75, 75, "NRML", "LIMIT",
            110.0, 0.05, "NFO"), exit = true) } }                            // his own earlier exit: its answer lost
        kite.requests.clear()
        val placed = kite.placed.size
        m.sendPlan()
        val why = refused(m)
        assertTrue(why, why.contains("your own earlier exit of 75 on $ce") && why.contains("Zerodha's answer to it was lost"))
        assertTrue(why, why.contains("The app waits up to") && why.endsWith("If it must go now, close it in Kite."))
        assertTrue("read again first", kite.requests.count { it.method == "GET" && it.path == "/orders" } >= 3)
        assertEquals("nothing sent", placed, kite.placed.size)
    }

    /** m4: an exit in freeze slices - the next slice waits briefly for its own previous leg to show in Zerodha's book, never refused for it. */
    @Test fun theNextSliceIsNotRefusedForItsOwnPreviousLeg() {
        kite.position(ce, 2700, 90.0)
        val m = model()
        m.planSquareOff(position(ce))
        val plan = BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        assertEquals(listOf(1800, 900), plan.legs.map { it.quantity })
        kite.trailReads = 2                                                  // each order shows in the book two reads late
        m.sendPlan()
        assertEquals(listOf("COMPLETE", "COMPLETE"), sent(m).map { it.status })
        assertEquals("flat", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /** m5: only a stop going back at Zerodha holds the row: Boss is told so and to send again in a moment - never that a sale is going. */
    @Test fun aStopGoingBackIsSaidAsAStop() {
        val m = model()
        m.planSquareOff(position(ce))
        BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        val stop = SquareOffExit.claimExit("NFO", ce, "NRML", com.optionslab.app.data.ArmLive.STOP_CLAIM_OWNER)!!
        try {
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.startsWith("Not sent: an arm's stop going back at Zerodha on $ce") && why.contains("send it again in a moment"))
            assertTrue(why, why.endsWith("If it must go now, close it in Kite."))
            assertFalse(why, why.contains("already selling"))
        } finally { stop.release() }
    }

    /** The sale holding the row lets go within the wait: Boss's exit goes then, as reviewed. */
    @Test fun anExitWaitsBrieflyForASaleHoldingTheRowThenGoes() {
        val m = model()
        protectAndPlan(m)
        val claim = SquareOffExit.claimExit("NFO", ce, "NRML", Protections.CLAIM_OWNER)!!
        Thread { Thread.sleep(600); claim.release() }.start()
        m.sendPlan()
        assertEquals("COMPLETE", sent(m).single().status)
        assertEquals(0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /**
     * The lag guard: the protection's target filled a moment ago and Zerodha's positions do not show it yet - Boss's exit is not sent beside
     * it (turning the resting stop into a sell would open a short); he is told why.
     */
    @Test fun anExitIsNotSentBesideASaleThePositionsDoNotShowYet() {
        val m = model()
        val p = protectAndPlan(m)
        kite.positionsTrailReads = 100_000
        kite.fill(p.targetOrderId!!, 130.0)                                  // the target sold; the positions still show 75
        m.sendPlan()
        val why = refused(m)
        // (Third round: sized under the claim before anything is taken out - nothing is left once that fill is counted.)
        assertTrue(why, why.startsWith("Not sent: Zerodha shows $ce closed") && why.contains("nothing of it is left to sell"))
        assertTrue("its stop left resting", kite.order(p.stopOrderId!!).working)
        assertTrue("nothing modified into a sell", kite.writes.none { it.method == "PUT" })
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }
}
