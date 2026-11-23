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

    /** Fifth round: a part sent - what went, said. Sixth round: a success (its fills), the words beside it ([AppModel.sendNote]). */
    private fun partSent(m: AppModel): String = BrokerArea.await("the part sent", 40_000) {
        (m.sending.value as? Load.Done)?.let { m.sendNote.value ?: throw AssertionError("all sent, nothing said: $it") }
            ?: (m.sending.value as? Load.Failed)?.let { throw AssertionError(it.why) }
    }
    private fun sells() = kite.placed.filter { it.form["transaction_type"] == "SELL" }

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
        assertTrue("closed: no Kite advice (sixth round): $why", why.endsWith("so nothing of it is left to sell and nothing was sent."))
        assertEquals("nothing sent", placed, kite.placed.size)
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        assertEquals(null, SquareOffExit.exitClaimedBy("NFO", ce, "NRML"))
    }

    /**
     * Only part of the reviewed exit is left (the rest sold a moment ago, not shown yet). Fifth round: the part that can go goes at once under
     * the same confirmation - never more - and Boss is told what went and why the rest did not.
     */
    @Test fun anExitWithOnlyPartLeftSendsThatPartAndSaysSo() {
        kite.position(ce, 150, 90.0)
        kite.positionsTrailReads = 100_000
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(ce, com.optionslab.engine.Kite.Side.SELL, 75, 75, "NRML", "MARKET",
            null, 0.05, "NFO", "iraprotect"), exit = true) }
        val m = model()
        m.planSquareOff(position(ce))
        val plan = BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        assertEquals(150, plan.legs.sumOf { it.quantity })
        val before = sells().size
        m.sendPlan()
        val why = partSent(m)
        assertTrue(why, why.startsWith("Sent 75 of 150 on $ce; the other 75 are not held at Zerodha any more (Zerodha holds 75;"))
        assertFalse("no later legs to speak of", why.contains("remaining legs"))
        assertTrue("nothing left to close, so no Kite advice: $why", why.endsWith("before its positions show it)."))
        assertEquals("the part sent, once", listOf("75"), sells().drop(before).map { it.form["quantity"] })
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        assertEquals(null, SquareOffExit.exitClaimedBy("NFO", ce, "NRML"))
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
     * m2, fifth round: a book's own exit sale on the row has not settled (75 of 150 may still be selling): the 75 that can go safely goes under
     * the same confirmation, and the words say what went, why the rest did not, for about how long, and "close it in Kite".
     */
    @Test fun aBooksUnsettledSaleSendsTheSafeQuantity() {
        kite.position(ce, 150, 90.0)
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == ce) 75 else 0 }
        try {
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            val before = sells().size
            m.sendPlan()
            val why = partSent(m)
            assertTrue(why, why.startsWith("Sent 75 of 150 on $ce; the other 75 may still be selling by ") &&
                why.contains("(75 not settled yet - found or let go within about 2 minutes)"))
            assertTrue(why, why.endsWith("If it must go now, close it in Kite."))
            assertEquals("the safe part only", listOf("75"), sells().drop(before).map { it.form["quantity"] })
            assertEquals("what may still be selling is left", 75, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { SquareOffExit.lostPendingProviders.remove("test") }
    }

    /** m2 (review), fifth round: a plan in freeze slices - the contract's slices cut to the safe quantity and sent, never more. */
    @Test fun aPartFromSlicesCutsThemToTheSafeQuantity() {
        kite.position(ce, 2700, 90.0)
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == ce) 900 else 0 }
        try {
            val m = model()
            m.planSquareOff(position(ce))
            val plan = BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            assertEquals(listOf(1800, 900), plan.legs.map { it.quantity })
            val before = sells().size
            m.sendPlan()
            val why = partSent(m)
            assertTrue(why, why.startsWith("Sent 1800 of 2700 on $ce; the other 900 may still be selling"))
            assertEquals("one freeze slice, never the second", listOf("1800"), sells().drop(before).map { it.form["quantity"] })
            assertEquals(900, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { SquareOffExit.lostPendingProviders.remove("test") }
    }

    /** m2 (review), fifth round: a leg refused for margin whose re-send finds a book's sale unsettled: the safe part goes again, said. */
    @Test fun aMarginResendWithABooksUnsettledSaleSendsTheSafeQuantity() {
        kite.position(ce, 150, 90.0)
        val m = model()
        m.planSquareOff(position(ce))
        BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        kite.nextPlace(outcome = FakeKite.Outcome.REJECT, message = "Insufficient funds. Required margin is 95000.00 but available margin is 1200.00.")
        kite.afterNext("/orders/regular") { SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == ce) 75 else 0 } }
        try {
            val before = sells().size
            m.sendPlan()
            val why = partSent(m)
            assertTrue(why, why.startsWith("Sent 75 of 150 on $ce; the other 75 may still be selling by "))
            assertEquals("the refused 150, then the safe 75 - never the whole again", listOf("150", "75"), sells().drop(before).map { it.form["quantity"] })
            assertEquals(75, kite.positions.getValue("NFO:$ce:NRML").first)
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
            assertTrue(why, why.startsWith("Not sent: an arm's stop going back at Zerodha on $ce") && why.contains("plan the exit again in a moment"))
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

    // ---- fifth round ----------------------------------------------------------------------------------------------------------------

    /**
     * A plan of several contracts - a short put bought back first, then its long hedge sold. Only part of the short can go safely (a protection's
     * buy-back of 75 filled a moment ago, the positions not showing it yet): that part goes. Sixth round: the short will be flat then, so its
     * hedge's sale goes too - and Boss is told what went on each, with no Kite advice (nothing is left to close).
     */
    @Test fun aShortThatWillBeFlatLetsItsHedgeSaleGo() {
        val pe = BrokerArea.symbol("NIFTY", expiry, 24_500.0, "PE")
        kite.quote("NFO:$pe", 50.0, 49.95, 50.05)
        kite.position(pe, -150, 60.0)
        kite.positionsTrailReads = 100_000
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(pe, com.optionslab.engine.Kite.Side.BUY, 75, 75, "NRML", "MARKET",
            null, 0.05, "NFO", "iraprotect"), exit = true) }                 // a protection's buy-back: filled, the positions still show -150
        val m = model()
        m.planSquareOffList(listOf(position(pe), position(ce)), "Square off")
        val plan = BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        assertEquals("the short first, then the hedge", listOf(pe to 150, ce to 75), plan.legs.map { it.tradingSymbol to it.quantity })
        val mine = kite.placed.size
        m.sendPlan()
        val why = partSent(m)
        assertTrue(why, why.startsWith("Sent 75 of 150 on $pe; the other 75 are not held at Zerodha any more"))
        assertTrue(why, why.endsWith(". $ce: all 75 sent."))
        val sentNow = kite.placed.drop(mine)
        assertEquals("the short's safe part, then the hedge", listOf(Triple(pe, "BUY", "75"), Triple(ce, "SELL", "75")),
            sentNow.map { Triple(it.form["tradingsymbol"], it.form["transaction_type"], it.form["quantity"]) })
        assertEquals("the short closed, never turned long", 0, kite.positions.getValue("NFO:$pe:NRML").first)
        assertEquals("the hedge sold", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        assertEquals(null, SquareOffExit.exitClaimedBy("NFO", pe, "NRML"))
    }

    /**
     * #6: the protection's day GTT triggered and its sale is not in Zerodha's order book yet: Boss's exit is not sent beside it (it would sell
     * the 75 twice), and he is told why, for how long, and Kite. The GTT is never deleted as if it were still waiting.
     */
    @Test fun aTriggeredGttsSaleNotInTheBookYetHoldsTheExitBack() {
        com.optionslab.app.data.DayOco.resetForTest(on = true)
        try {
            val said = runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0) }
            assertTrue(said, said.contains("as one GTT"))
            val gid = kite.gtts.keys.single()
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            kite.quote("NFO:$ce", 70.0, 69.95, 70.05)                          // through the stop: it triggers; its sale rests above the bid
            val oid = kite.triggerGtt(gid)
            assertEquals("OPEN", kite.order(oid).status)
            kite.hideFromBook(oid, 1_000)                                      // not in the order book yet
            val mine = kite.placed.size
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.contains("its protection's GTT on $ce triggered and its sale (order $oid) is not in Zerodha's order book yet") &&
                why.contains("plan the exit again in a few seconds"))
            assertTrue(why, why.endsWith("If it must go now, close it in Kite."))
            assertEquals("nothing sent beside it", mine, kite.placed.size)
            assertTrue("never deleted", kite.requests.none { it.method == "DELETE" && it.path == "/gtt/triggers/$gid" })
            assertEquals(75, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { com.optionslab.app.data.DayOco.resetForTest() }
    }

    /**
     * #6: the GTT triggered and its sale rests in the book: it is that sale that is taken over (turned into Boss's exit) - one sale, never the
     * full 75 beside it, and the triggered GTT is not deleted.
     */
    @Test fun aTriggeredGttsRestingSaleIsTakenOverNeverSoldBeside() {
        com.optionslab.app.data.DayOco.resetForTest(on = true)
        try {
            runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0) }
            val gid = kite.gtts.keys.single()
            val m = model()
            kite.quote("NFO:$ce", 70.0, 69.95, 70.05)
            val oid = kite.triggerGtt(gid)
            assertEquals("OPEN", kite.order(oid).status)
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            kite.afterNext("/orders/regular/$oid") { kite.fill(oid) }       // turned into a sale at the bid: it fills
            val mine = kite.placed.size
            m.sendPlan()
            assertEquals("its sale is the exit", listOf(oid), sent(m).map { it.orderId })
            assertEquals("no second sale", mine, kite.placed.size)
            assertTrue("never deleted", kite.requests.none { it.method == "DELETE" && it.path == "/gtt/triggers/$gid" })
            assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { com.optionslab.app.data.DayOco.resetForTest() }
    }

    /** #6: the protection's GTT could not be deleted and is still active: nothing is sent beside it, said; its taken-over mark ends. */
    @Test fun aGttWhoseDeleteFailsHoldsTheExitBack() {
        com.optionslab.app.data.DayOco.resetForTest(on = true)
        try {
            runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0) }
            val gid = kite.gtts.keys.single()
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            kite.gttDeleteFailures = 5
            val mine = kite.placed.size
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.startsWith("Not sent: its protection's GTT on $ce could not be deleted (") && why.contains("it could still fire as your exit fills"))
            assertTrue(why, why.endsWith("If it must go now, close it in Kite."))
            assertEquals("nothing sent", mine, kite.placed.size)
            assertTrue("the GTT still there", gid in kite.gtts.keys)
            assertFalse("its mark ended, so Boss's own delete of it later is his", Protections.isTakenOver("gtt:$gid"))
            assertEquals(75, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { com.optionslab.app.data.DayOco.resetForTest(); kite.gttDeleteFailures = 0 }
    }

    /**
     * #5: a read that fails while the resting exits are taken over (here the GTT list, read just after the room check's own read, under the
     * row's claim) is caught and said - what could not be read, that nothing went or was taken out, and Kite - never Zerodha's bare words.
     */
    @Test fun aReadFailingDuringTheTakeoverIsSaidWithTheKiteAdvice() {
        com.optionslab.app.data.DayOco.resetForTest(on = true)
        try {
            runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0) }
            val gid = kite.gtts.keys.single()
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            // After the first GTT read made under Boss's claim (the room check's), the next ones fail (the takeover's).
            fun arm() {
                kite.afterNext("/gtt/triggers") {
                    if (SquareOffExit.exitClaimedBy("NFO", ce, "NRML")?.startsWith("${SquareOffExit.BOSS_OWNER}#") == true) kite.gttReadFailures = 5 else arm()
                }
            }
            arm()
            val mine = kite.placed.size
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.startsWith("Not sent: Zerodha's GTT list could not be read just before your exit on $ce went (") &&
                why.contains("so it was not sent and nothing resting on it was taken out: plan the exit again in a moment."))
            assertTrue(why, why.endsWith("If it must go now, close it in Kite."))
            assertEquals("nothing sent", mine, kite.placed.size)
            assertTrue("the GTT left as it was", gid in kite.gtts.keys && kite.requests.none { it.method == "DELETE" && it.path == "/gtt/triggers/$gid" })
        } finally { com.optionslab.app.data.DayOco.resetForTest(); kite.gttReadFailures = 0 }
    }

    /**
     * #11: the note names whose resting exit filled as it came out, from its tag - an arm's exit here, not "your stop". Its 75 of the 150 sold as
     * the takeover cancelled it: the 75 left goes under the same confirmation, said as a part.
     */
    @Test fun theFilledNoteNamesWhoseExitItWas() {
        kite.position(ce, 150, 90.0)
        val stop = runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(ce, com.optionslab.engine.Kite.Side.SELL, 75, 75, "NRML", "SL-M",
            null, 0.05, "NFO", "iraarm", triggerPrice = 80.0), exit = true) }  // an arm's stop resting on part of it
        val m = model()
        m.planSquareOff(position(ce))
        BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        kite.positionsTrailReads = 100_000
        kite.afterNext("/orders/regular/$stop") { kite.fill(stop, 80.0) }   // it triggered as the cancel went
        val before = sells().size
        m.sendPlan()
        val why = partSent(m)
        assertEquals("Sent 75 of 150 on $ce; the other 75 were sold as an arm's exit at Zerodha filled as it came out.", why)
        assertEquals("only what was left", listOf("75"), sells().drop(before).map { it.form["quantity"] })
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /**
     * Review (fifth round): the part is said as sent only once it filled - Zerodha refused it here, so Boss is told only that part could go
     * safely, never "Sent", and what to do.
     */
    @Test fun aPartRefusedByZerodhaIsNeverSaidAsSent() {
        kite.position(ce, 150, 90.0)
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == ce) 75 else 0 }
        try {
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            kite.nextPlace(outcome = FakeKite.Outcome.REJECT, message = "RMS: blocked for this instrument.")
            m.sendPlan()
            val why = refused(m)
            assertFalse(why, why.contains("Sent 75"))
            assertTrue(why, why.startsWith("Leg 1 rejected: RMS: blocked for this instrument. On $ce, 0 of 150 went (only 75 could go safely; the other " +
                "75 may still be selling by "))
            assertTrue(why, why.endsWith(". If it must go now, close it in Kite."))
            assertEquals(150, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { SquareOffExit.lostPendingProviders.remove("test") }
    }

    /** Review (fifth round): Jarvis says a part sent as it is - never "Zerodha refused"; sixth round: the words before the fills. */
    @Test fun jarvisSaysAPartSentAsItIs() {
        kite.position(ce, 150, 90.0)
        SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == ce) 75 else 0 }
        try {
            val m = model()
            com.optionslab.app.ira.IraActions.attach(m)
            val said = java.util.concurrent.atomic.AtomicReference<String?>()
            Thread { said.set(runBlocking { com.optionslab.app.ira.IraActions.closeLiveSymbol(ce) }) }.start()
            val answer = BrokerArea.await("Jarvis's answer", 40_000) { said.get() }
            assertTrue(answer, answer.startsWith("Sent 75 of 150 on $ce; the other 75 may still be selling by ") && answer.contains("Sent to Zerodha: "))
            assertEquals(listOf("75"), sells().map { it.form["quantity"] })
        } finally { SquareOffExit.lostPendingProviders.remove("test") }
    }

    /**
     * Review (fifth round): a protection's GTT that triggered in an earlier session (its order unknown to Zerodha now) is no sale on its way:
     * Boss's exit is never held back by it.
     */
    @Test fun aGttTriggeredInAnEarlierSessionNeverHoldsTheExitBack() {
        com.optionslab.app.data.DayOco.resetForTest(on = true)
        try {
            runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0) }
            val gid = kite.gtts.keys.single()
            kite.quote("NFO:$ce", 70.0, 69.95, 70.05)
            val oid = kite.triggerGtt(gid)
            kite.orders.remove(oid)                                            // yesterday's order: not in today's book, unknown to Zerodha
            kite.quote("NFO:$ce", 100.0, 99.95, 100.05)
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            m.sendPlan()
            assertEquals("COMPLETE", sent(m).single().status)
            assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { com.optionslab.app.data.DayOco.resetForTest() }
    }

    /**
     * Second review: a triggered GTT is a sale on its way only while one may be going - not when Zerodha could not place its order, nor when
     * its order was refused; one with no order from Zerodha yet holds the exit back, said.
     */
    @Test fun aTriggeredGttHoldsTheExitBackOnlyWhileASaleMayBeGoing() {
        com.optionslab.app.data.DayOco.resetForTest(on = true)
        try {
            runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0) }
            val gid = kite.gtts.keys.single()
            val g = kite.gtts.getValue(gid)
            g.put("status", "triggered")                                       // triggered, Zerodha's order not given yet
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            val mine = kite.placed.size
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.startsWith("Not sent: its protection's GTT on $ce triggered and Zerodha has not given its sale's order yet") &&
                why.endsWith("If it must go now, close it in Kite."))
            assertEquals("nothing sent beside it", mine, kite.placed.size)
            // Zerodha could not place it: no sale - the exit goes.
            g.getJSONArray("orders").getJSONObject(0).put("result", org.json.JSONObject().put("order_result",
                org.json.JSONObject().put("order_id", "").put("status", "failed").put("rejection_reason", "Insufficient funds")))
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan again") { (m.plan.value as? Load.Done)?.value?.takeIf { m.sending.value == Load.Idle } }
            m.sendPlan()
            assertEquals("COMPLETE", sent(m).single().status)
            assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { com.optionslab.app.data.DayOco.resetForTest() }
    }

    /** Second review: a triggered GTT's order that Zerodha refused (not in the book read yet) is no sale: the exit goes. */
    @Test fun aTriggeredGttsRefusedOrderNeverHoldsTheExitBack() {
        com.optionslab.app.data.DayOco.resetForTest(on = true)
        try {
            runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0) }
            val gid = kite.gtts.keys.single()
            kite.quote("NFO:$ce", 70.0, 69.95, 70.05)
            val oid = kite.triggerGtt(gid)
            kite.reject(oid, "RMS: price band")
            kite.hideFromBook(oid, 1_000)
            kite.quote("NFO:$ce", 100.0, 99.95, 100.05)
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            m.sendPlan()
            assertEquals("COMPLETE", sent(m).single().status)
            assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { com.optionslab.app.data.DayOco.resetForTest() }
    }

    // ---- sixth round ----------------------------------------------------------------------------------------------------------------

    /**
     * #1: the protection's GTT reads as waiting when the takeover looks, fires just after (its sale resting, not in the order book yet), and
     * the delete still goes through. Its row, read again after the delete, carries the order - a sale on its way: Boss's exit is never sent
     * beside it, and he is told why.
     */
    @Test fun aGttThatFiresAsTheTakeoverDeletesItIsNeverSoldBeside() {
        com.optionslab.app.data.DayOco.resetForTest(on = true)
        try {
            runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0) }
            val gid = kite.gtts.keys.single()
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            kite.keepDeletedGtts = true                                        // Kite lists a deleted GTT, with its fired order's result
            // (The takeover's own read of the GTT list is the second made under Boss's claim: it fires right after that read.)
            var claimed = 0
            val oid = java.util.concurrent.atomic.AtomicReference<String?>()
            fun arm() {
                kite.afterNext("/gtt/triggers") {
                    if (SquareOffExit.exitClaimedBy("NFO", ce, "NRML")?.startsWith("${SquareOffExit.BOSS_OWNER}#") == true && ++claimed == 2) {
                        kite.quote("NFO:$ce", 70.0, 69.95, 70.05)
                        oid.set(kite.triggerGtt(gid).also { kite.hideFromBook(it, 1_000) })
                    } else arm()
                }
            }
            arm()
            val mine = kite.placed.size
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, oid.get() != null && why.startsWith("Not sent: its protection's GTT on $ce triggered and its sale (order ${oid.get()}) is not " +
                "in Zerodha's order book yet") && why.contains("plan the exit again"))
            assertTrue(why, why.endsWith("If it must go now, close it in Kite."))
            assertEquals("nothing sold beside it", mine, kite.placed.size)
            assertEquals(75, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { com.optionslab.app.data.DayOco.resetForTest(); kite.keepDeletedGtts = false }
    }

    /** #2: a GTT result's JSON nulls are never an order id "null", nor a refusal - and an order not given yet still holds the exit back. */
    @Test fun aGttResultsJsonNullsAreNeverAnOrderOrARefusal() {
        com.optionslab.app.data.DayOco.resetForTest(on = true)
        try {
            runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0) }
            val gid = kite.gtts.keys.single()
            val g = kite.gtts.getValue(gid)
            g.put("status", "triggered")
            fun result(id: Any, status: Any, reason: Any) = g.getJSONArray("orders").getJSONObject(0).put("result", org.json.JSONObject().put("order_result",
                org.json.JSONObject().put("order_id", id).put("status", status).put("rejection_reason", reason)))
            fun row() = runBlocking { Broker.gtts() }.single { it.id == gid }
            val nul = org.json.JSONObject.NULL
            result(nul, "success", nul)
            row().let { assertEquals("no order \"null\"", emptyList<String>(), it.orderIds); assertFalse("a null reason is no refusal", it.placeFailed) }
            result(nul, "failed", nul)
            row().let { assertEquals(emptyList<String>(), it.orderIds); assertTrue("Zerodha could not place it", it.placeFailed) }
            result("250926000000777", "success", nul)
            row().let { assertEquals(listOf("250926000000777"), it.orderIds); assertFalse(it.placeFailed) }
            result(nul, nul, nul)
            val m = model()
            m.planSquareOff(position(ce))
            BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
            val mine = kite.placed.size
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.contains("triggered and Zerodha has not given its sale's order yet"))
            assertEquals("never let through beside it", mine, kite.placed.size)
        } finally { com.optionslab.app.data.DayOco.resetForTest() }
    }

    /** An exit in two freeze slices, its protection's GTT fired (order [oid]) and that order gone from Zerodha; [yesterday]: its result dated so. */
    private fun slicedExitBesideAGoneGttOrder(yesterday: Boolean): Triple<List<Broker.Fill>, Long, String> {
        kite.position(ce, 2700, 90.0)
        runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0) }
        val gid = kite.gtts.keys.single()
        kite.quote("NFO:$ce", 70.0, 69.95, 70.05)
        val oid = kite.triggerGtt(gid)
        kite.orders.remove(oid)                                                // unknown to Zerodha now
        if (yesterday) kite.gtts.getValue(gid).getJSONArray("orders").getJSONObject(0).getJSONObject("result")
            .put("timestamp", "${com.optionslab.app.data.Market.today().minusDays(1)} 15:05:00")
        kite.quote("NFO:$ce", 100.0, 99.95, 100.05)
        val m = model()
        m.planSquareOff(position(ce))
        val plan = BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        assertEquals(listOf(1800, 900), plan.legs.map { it.quantity })
        val t0 = System.nanoTime()
        m.sendPlan()
        val fills = sent(m)
        return Triple(fills, (System.nanoTime() - t0) / 1_000_000, oid)
    }

    /**
     * #4: an exit in two freeze slices, a protection's GTT on it fired in an earlier session (its result dated yesterday, its order unknown to
     * Zerodha now): no sale - never asked about, never waited for; neither slice waits its 2 s.
     */
    @Test fun aSlicedExitNeverWaitsForAnEarlierSessionsGttOrder() {
        com.optionslab.app.data.DayOco.resetForTest(on = true)
        try {
            val (fills, ms, oid) = slicedExitBesideAGoneGttOrder(yesterday = true)
            assertEquals(listOf(1800, 900), fills.map { it.filled })
            assertEquals("never asked about", 0, kite.requests.count { it.method == "GET" && it.path == "/orders/$oid" })
            assertTrue("never waited for it (each wait was 2 s): $ms ms", ms < 3_500)
            assertEquals(0, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { com.optionslab.app.data.DayOco.resetForTest() }
    }

    /**
     * #4 (review): its date not given, an order Zerodha does not know may only not show yet - waited for once, asked again after the wait, then
     * taken as an earlier session's for the rest of the send: the second slice neither asks nor waits.
     */
    @Test fun aSlicedExitWaitsOnceForAnUnknownGttOrderThenNeverAgain() {
        com.optionslab.app.data.DayOco.resetForTest(on = true)
        try {
            val (fills, _, oid) = slicedExitBesideAGoneGttOrder(yesterday = false)
            assertEquals(listOf(1800, 900), fills.map { it.filled })
            assertEquals("asked before and after the one wait - never for the second slice", 2,
                kite.requests.count { it.method == "GET" && it.path == "/orders/$oid" })
            assertEquals(0, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { com.optionslab.app.data.DayOco.resetForTest() }
    }

    /**
     * #5: a short whose buy-back is refused (another seller holds its row) may stay short: the later buy-back of another short still goes (it
     * never adds a short); the hedge's sale is held back. Each contract said.
     */
    @Test fun aShortThatMayStayShortHoldsBackOnlyTheHedgeSale() {
        val pe = BrokerArea.symbol("NIFTY", expiry, 24_500.0, "PE")
        val ce6 = BrokerArea.symbol("NIFTY", expiry, 24_600.0, "CE")
        kite.quote("NFO:$pe", 50.0, 49.95, 50.05)
        kite.quote("NFO:$ce6", 40.0, 39.95, 40.05)
        kite.position(pe, -150, 60.0)
        kite.position(ce6, -75, 45.0)
        val m = model()
        m.planSquareOffList(listOf(position(pe), position(ce6), position(ce)), "Square off")
        val plan = BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        assertEquals(listOf(pe to "BUY", ce6 to "BUY", ce to "SELL"), plan.legs.map { it.tradingSymbol to it.side.name })
        val flat = SquareOffExit.claimExit("NFO", pe, "NRML", "iraalgoflat")!!
        try {
            val mine = kite.placed.size
            m.sendPlan()
            val why = partSent(m)
            assertTrue(why, why.startsWith("$pe: not sent - the flat-by is already selling $pe at Zerodha"))
            assertTrue(why, why.contains(". $ce6: all 75 sent. $ce: not sent - held back while $pe may stay short."))
            assertTrue(why, why.endsWith(" If it must go now, close it in Kite."))
            assertEquals("only the other short's buy-back", listOf(Triple(ce6, "BUY", "75")),
                kite.placed.drop(mine).map { Triple(it.form["tradingsymbol"], it.form["transaction_type"], it.form["quantity"]) })
            assertEquals(-150, kite.positions.getValue("NFO:$pe:NRML").first)
            assertEquals(0, kite.positions.getValue("NFO:$ce6:NRML").first)
            assertEquals("the hedge kept while the short may stay", 75, kite.positions.getValue("NFO:$ce:NRML").first)
        } finally { flat.release() }
    }

    /** #6: a stuck part's words stay with the answer when the rest cannot go yet - naming only what went. */
    @Test fun aStuckPartsWordsStayWhenTheRestCannotGoYet() {
        kite.position(ce, 150, 90.0)
        val m = model()
        m.planSquareOff(position(ce))
        val plan = BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        val id = runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(ce, com.optionslab.engine.Kite.Side.SELL, 75, 75, "NRML", "LIMIT", 130.0,
            0.05, "NFO", "iraalgo"), exit = true) }                            // the part's leg, resting at Zerodha
        val k = AppModel.PartSent.keyOf(plan.legs[0])
        val part = AppModel.PartSent.of(plan.legs).copy(cuts = mapOf(k to AppModel.PartSent.Cut(75, "may still be selling by a test", open = true)))
        m.stuck.value = AppModel.StuckLeg(plan.copy(legs = listOf(plan.legs[0].copy(quantity = 75))), 0, id, listOf(Broker.Fill(id, "OPEN", 0.0, 0, "")), "OPEN", part)
        m.continueAfterStuck()
        val said = BrokerArea.await("the answer") { m.message.value?.takeIf { it.startsWith("Leg 1 is open") } }
        assertTrue(said, said.endsWith("the rest are sent only after it has completely filled. On $ce, 0 of 150 went (only 75 could go safely; the other 75 " +
            "may still be selling by a test). If it must go now, close it in Kite."))
    }

    /**
     * Review (sixth round): a leg refused after its resting exits were taken out ends the send - even when the legs after it would be safe -
     * so its stops go back at once, never left out while later legs fill. Said so.
     */
    @Test fun aLegRefusedAfterItsStopsWereTakenOutEndsTheSend() {
        val pe = BrokerArea.symbol("NIFTY", expiry, 24_500.0, "PE")
        kite.quote("NFO:$pe", 50.0, 49.95, 50.05)
        kite.position(pe, 75, 40.0)
        val said = runBlocking { Protections.protectLive(ce, "NFO", "NRML", 75, 100.0, 85.0, null, 130.0) }
        assertTrue(said, said.startsWith("Protected"))
        val stop = runBlocking { Protections.active() }.single().stopOrderId!!
        val m = model()
        m.planSquareOffList(listOf(position(ce), position(pe)), "Square off")
        val plan = BrokerArea.await("the exit plan") { (m.plan.value as? Load.Done)?.value }
        assertEquals(listOf(ce, pe), plan.legs.map { it.tradingSymbol })
        // As its stop comes out, a book cannot tell yet whether a sale of its own on it is still going: refused after the takeover.
        kite.afterNext("/orders/regular/$stop") { SquareOffExit.lostPendingProviders["test"] = { s, _ -> if (s == ce) -1 else 0 } }
        try {
            val mine = kite.placed.size
            m.sendPlan()
            val why = refused(m)
            assertTrue(why, why.startsWith("Not sent: ") && why.contains("cannot tell yet"))
            assertTrue(why, why.contains("The legs after it were not sent: its stops, taken out for it, go back first."))
            assertEquals("nothing sold - not even the safe leg after it", mine, kite.placed.size)
            assertEquals(75, kite.positions.getValue("NFO:$pe:NRML").first)
        } finally { SquareOffExit.lostPendingProviders.remove("test") }
    }
}
