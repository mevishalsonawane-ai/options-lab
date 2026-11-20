package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime

/**
 * 20 Nov 2026 (Boss's exception: "try cheaper options increment it 1 by 1"): the cheaper strike on Liquidity 15+5's own live entry
 * ([OrbArms]) against the fake Kite. Liquidity has no paper twin while live: its live trade is booked on the cheaper contract (the app's
 * name and Zerodha's), so its stop, its checks and a restart follow that option. A strike missing from the app's own list is skipped and
 * counted; Zerodha's funds refusal steps once; the switch off and the kill switch hold.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class CheaperStrikeLiquidityTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val s52100 = "BANKNIFTY26OCT52100CE"
    private val s52200 = "BANKNIFTY26OCT52200CE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private lateinit var now: LocalDateTime

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(22_000_000, "BANKNIFTY26OCT51900CE", "BANKNIFTY", expiry, 51_900.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_002, s52100, "BANKNIFTY", expiry, 52_100.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_003, s52200, "BANKNIFTY", expiry, 52_200.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_009, "BANKNIFTY26OCT52000PE", "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        kite.quote("NFO:$s52100", 150.0, 149.95, 150.05)
        kite.quote("NFO:$s52200", 100.0, 99.95, 100.05)
        runBlocking { Broker.instruments() }
        contracts(listOf(52_000.0, 52_100.0, 52_200.0))
        CheaperStrike.resetForTest(on = true)
        kite.requests.clear()
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        now = t.toLocalDateTime().withNano(0)
    }

    @After fun down() {
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** The app's own contract list (the paper account's): the calls at [strikes], and the arm's put. */
    private fun contracts(strikes: List<Double>) = AutomationSupport.contracts(context, strikes.map {
        Upstox.Contract("BANKNIFTY", expiry, it, Right.CE, 30, "NSE_FO|LIQ${it.toInt()}CE", "BANKNIFTY-LIQ-${it.toInt()}CE") } +
        Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.PE, 30, "NSE_FO|LIQLIVEPE", "BANKNIFTY-LIQ-52000PE"))

    /** Both liquidity books armed in Live and cleared by Boss's switch; the 5-minute book's 52000 call waiting for approval. */
    private fun state() {
        val books = listOf("liquidity15", "liquidity5")
        fun flags(v: Boolean) = JSONObject().apply { books.forEach { put(it, v) } }
        val bar = maxOf(now.minusMinutes(1), now.toLocalDate().atStartOfDay())
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", flags(true)).put("auto", flags(false)).put("liveOk", flags(true))
            .put("migrated", JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION).put(com.optionslab.engine.orb.RetiredArms.UNRETIRE)
                .put(OrbArms.LIQ_SWITCH_CLEAR))
            .put("positions", JSONArray())
            .put("pending", JSONObject().put("liquidity5", JSONObject().put("right", "CE").put("bar", bar.toString())
                .put("expires", now.plusMinutes(9).toString()).put("strike", 52_000).put("level", 52_050.0)))
            .put("liqLots", 1).put("liqLotsChosen", true))
    }

    /** The app's own name for the call at [k] ([Paper.symbolOf]). */
    private fun paperSym(k: Double) = Paper.symbolOf(Upstox.Contract("BANKNIFTY", expiry, k, Right.CE, 30, "NSE_FO|LIQ${k.toInt()}CE", ""))
    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }
    private fun approve() = runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) }
    private fun buys() = kite.placed.filter { it.form["transaction_type"] == "BUY" }
    private fun pass() = runBlocking { OrbArms.priceCheckOnly() }

    @Test fun shortOfOneLotItBuysOneLotOfTheNextStrikeBookedOnThatContract() {
        kite.cash = 5_000.0
        state()
        assertEquals("Entered at Zerodha (live).", approve())
        val (buy, stop) = kite.placed
        assertEquals(s52100, buy.form["tradingsymbol"]); assertEquals("30", buy.form["quantity"]); assertEquals("LIMIT", buy.form["order_type"])
        assertEquals(s52100, stop.form["tradingsymbol"]); assertEquals("SL", stop.form["order_type"])
        val p = row().open!!
        assertTrue(p.live); assertEquals("liquidity5", p.arm)
        assertEquals(paperSym(52_100.0), p.symbol); assertEquals(s52100, p.kite)
        // 15% below its own fill.
        assertEquals(com.optionslab.engine.Kite.onTick(p.entry * 0.85, 0.05, com.optionslab.engine.Kite.Side.SELL), p.stopTrigger!!, 1e-9)
        assertEquals(52_050.0, p.level!!, 0.0)
        assertEquals("labelled with the strike its signal chose", paperSym(52_000.0), p.stepFrom)
        // Its contract is known to the app (its expiry square-off and strike are read from it).
        assertNotNull(Paper.contractOf(paperSym(52_100.0)))
        val r = CheaperStrike.recsOf().single()
        assertTrue(r.bought); assertEquals("BANKNIFTY 52000 CE", r.from); assertEquals("52100 CE", r.to)
        assertTrue(FundsCheck.recsOf().isEmpty())
    }

    /**
     * Boss's choice (20 Nov): in approve mode the strike approved with the PIN may be swapped for a cheaper one without asking again; the
     * approval notice says so beforehand and Boss is alerted after the fill with the strike bought.
     */
    @Test fun anApprovedEntryBuysTheCheaperStrikeWithoutAskingAgainAndAlertsAfterTheFill() {
        com.optionslab.app.work.Alerts.forgetPosted()
        kite.cash = 5_000.0
        state()                                                         // approve mode ("auto" off), the signal waiting
        assertEquals("one PIN approval, no second ask", "Entered at Zerodha (live).", approve())
        assertEquals(s52100, buys().single().form["tradingsymbol"])
        assertNull("the approval used up", row().pending)
        val said = com.optionslab.app.work.Alerts.posted.map { it.text }
        assertTrue(said.toString(), said.any { it.endsWith("funds short for BANKNIFTY 52000 CE, bought 52100 CE (1 lot)") })
        assertTrue(OrbArms.CHEAPER_ON_APPROVAL.contains("up to 300 points cheaper"))
    }

    /** The approval notice says it before Boss approves (in Live, while it is on); not in Paper, nor while it is off. */
    @Test fun theApprovalNoticeSaysACheaperStrikeMayBeBought() {
        val bar = now.withSecond(0)
        val live = OrbArms.approvalText("BANKNIFTY", 5, bar, 1, true, "1 lot", bar.plusMinutes(5), cheaperOn = true)
        assertTrue(live, live.contains("approve it on Home in the app. If funds are short, a strike up to 300 points cheaper may be bought. Approve by"))
        assertFalse(OrbArms.approvalText("BANKNIFTY", 5, bar, 1, true, "1 lot", bar.plusMinutes(5), cheaperOn = false).contains("cheaper"))
        assertFalse(OrbArms.approvalText("BANKNIFTY", 5, bar, 1, false, "1 lot", bar.plusMinutes(5), cheaperOn = true).contains("cheaper"))
    }

    /** The app's contract list not in memory: Liquidity (booked on that list) does not step this time; the entry goes as before. */
    @Test fun aColdContractListMeansNoStep() {
        kite.cash = 5_000.0
        CheaperStrike.testColdContracts = true
        try {
            state()
            assertEquals("Entered at Zerodha (live).", approve())
            assertEquals(sym, buys().single().form["tradingsymbol"])
            assertTrue(CheaperStrike.recsOf().single().why.contains("not in memory"))
        } finally { CheaperStrike.testColdContracts = false }
    }

    /** The order flow, asked again for the cheaper contract, skips it: the stepped buy never goes; the entry goes on as before. */
    @Test fun aFlowSkipOnTheCheaperContractLetsTheEntryGoOn() {
        kite.cash = 5_000.0
        OrbArms.testCheaperFlowSkip = true
        try {
            state()
            assertEquals("Entered at Zerodha (live).", approve())
            assertEquals(listOf(sym), buys().map { it.form["tradingsymbol"] })
            val r = CheaperStrike.recsOf().single()
            assertFalse(r.bought); assertTrue(r.why, r.why.startsWith("refused before it went"))
        } finally { OrbArms.testCheaperFlowSkip = false }
    }

    @Test fun aStrikeNotInTheAppsOwnListIsSkippedAndCounted() {
        contracts(listOf(52_000.0, 52_200.0))                          // 52100 listed at Zerodha, not in the app's list
        kite.cash = 5_000.0
        state()
        assertEquals("Entered at Zerodha (live).", approve())
        assertEquals(s52200, buys().single().form["tradingsymbol"])
        assertEquals(2, CheaperStrike.recsOf().single().steps)
        assertEquals(paperSym(52_200.0), row().open!!.symbol)
    }

    @Test fun aZerodhaFundsRefusalStepsOnceAndALostReplyNever() {
        state()
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Insufficient funds. Required margin is 6000.00 but available margin is 5500.00.")
        assertEquals("Entered at Zerodha (live).", approve())
        assertEquals(listOf(sym, s52100), buys().map { it.form["tradingsymbol"] })
        assertEquals(s52100, row().open!!.kite)
    }

    @Test fun aLostReplyNeverSteps() {
        state()
        kite.nextPlace(FakeKite.Reply.NETWORK_EXCEPTION)
        approve()
        assertTrue(buys().none { it.form["tradingsymbol"] != sym })
        assertTrue(CheaperStrike.recsOf().isEmpty())
    }

    @Test fun theSwitchOffAndTheKillSwitchHold() {
        CheaperStrike.set(false, log = false)
        kite.cash = 5_000.0
        state()
        assertEquals("Entered at Zerodha (live).", approve())
        assertEquals("as before", sym, buys().single().form["tradingsymbol"])
        assertTrue(CheaperStrike.recsOf().isEmpty())
        kite.flat(); kite.orders.values.filter { it.working }.forEach { it.status = "CANCELLED" }
        kite.requests.clear()
        CheaperStrike.set(true, pinConfirmed = true, log = false)
        com.optionslab.app.security.SecurePrefs.put("g.kill", true)
        state()
        assertEquals("Refused: the kill switch is on", approve())
        assertTrue(kite.placed.isEmpty()); assertTrue(CheaperStrike.recsOf().isEmpty())
    }

    /** Its stop missing at Zerodha is put back on the cheaper option; a restart restores the trade on it. */
    @Test fun itsStopAndARestartFollowTheCheaperOption() {
        kite.cash = 5_000.0
        state()
        assertEquals("Entered at Zerodha (live).", approve())
        val sid = row().open!!.stopOrderId!!
        kite.reject(sid)                                                // the stop refused by Zerodha's risk system (not Boss): put back
        kite.requests.clear()
        AutomationSupport.reloadFromDisk(OrbArms)
        val p = row().open!!
        assertEquals(paperSym(52_100.0), p.symbol); assertEquals(s52100, p.kite)
        assertEquals("its label survives the restart", paperSym(52_000.0), p.stepFrom)
        runBlocking { ArmLive.refresh() }
        val u = com.optionslab.app.testing.FakeUpstox()
        try { pass() } finally { u.close() }
        val back = kite.placed.filter { it.form["order_type"] == "SL" }
        assertTrue(kite.requests.toString(), back.isNotEmpty())
        assertTrue(back.all { it.form["tradingsymbol"] == s52100 })
        assertNull("the arm's own strike never touched", kite.positions["NFO:$sym:MIS"])
        assertFalse(row().open!!.unconfirmed)
    }
}
