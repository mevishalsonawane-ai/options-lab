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
        assertTrue(why, why.contains("filled a moment ago and Zerodha's positions do not show it yet"))
        assertTrue("its stop left resting", kite.order(p.stopOrderId!!).working)
        assertTrue("nothing modified into a sell", kite.writes.none { it.method == "PUT" })
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }
}
