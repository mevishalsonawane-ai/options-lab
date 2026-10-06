package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime

/**
 * Liquidity 15+5 in Live against the fake Kite, with the ORB's rules: arming in Live takes the PIN, an approval in
 * Live takes the PIN, an approved entry buys one lot at Zerodha and rests its stop 15% below the fill (an SL order,
 * as the ORB's), the kill switch refuses it, and a paper arm finding the app in Live asks instead of buying.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiquidityArmLiveTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private lateinit var now: LocalDateTime

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_002, "BANKNIFTY26OCT52000PE", "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        // The app's own contract list (the paper account's), which names the strike the arm buys.
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|LIQLIVECE", "BANKNIFTY-LIQ-52000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.PE, 30, "NSE_FO|LIQLIVEPE", "BANKNIFTY-LIQ-52000PE")))
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

    /** The arms' saved state: both liquidity books armed in Live, the 5-minute book with a signal waiting for approval. */
    private fun state(expires: LocalDateTime = now.plusMinutes(9)) {
        val books = listOf("liquidity15", "liquidity5")
        fun flags(v: Boolean) = JSONObject().apply { books.forEach { put(it, v) } }
        val bar = maxOf(now.minusMinutes(1), now.toLocalDate().atStartOfDay())
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", flags(true)).put("auto", flags(false)).put("liveOk", flags(true))
            // Saved after the 06 Oct update (its one-time switch-off already done): armed again by Boss.
            .put("migrated", JSONArray().put(OrbArms.OFF_LOSERS))
            .put("positions", JSONArray())
            .put("pending", JSONObject().put("liquidity5", JSONObject().put("right", "CE").put("bar", bar.toString())
                .put("expires", expires.toString()).put("strike", 52_000).put("level", 52_050.0))))
    }

    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }

    @Test fun armingInLiveTakesThePin() {
        val refused = runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, pinConfirmed = false) }
        assertEquals("The app is in Live: arm it with your PIN or fingerprint.", refused)
        assertFalse(row().armed)
        val armed = runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, pinConfirmed = true) }
        assertTrue(armed, armed.startsWith("Liquidity 15+5 armed on ZERODHA (live), fully automatic"))
        assertTrue(row().armed); assertTrue(row().liveOk)
        assertTrue("arming sends nothing", kite.placed.isEmpty())
    }

    @Test fun anApprovedLiveEntryBuysOneLotAndRestsA15PercentStop() {
        state()
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        val (buy, stop) = kite.placed
        val keys = setOf("tradingsymbol", "transaction_type", "order_type", "quantity", "product", "price", "trigger_price")
        assertEquals(mapOf("tradingsymbol" to sym, "transaction_type" to "BUY", "order_type" to "MARKET", "quantity" to "30", "product" to "MIS"),
            buy.form.filterKeys { it in keys })
        // 15% below the 200 fill: trigger 170, limit 5% under it (as the ORB's stop order).
        assertEquals(mapOf("tradingsymbol" to sym, "transaction_type" to "SELL", "order_type" to "SL", "quantity" to "30", "product" to "MIS",
            "price" to "161.50", "trigger_price" to "170.00"), stop.form.filterKeys { it in keys })
        val p = row().open!!
        assertTrue(p.live); assertEquals("liquidity5", p.arm)
        assertEquals(170.0, p.stopTrigger!!, 0.0); assertEquals(52_050.0, p.level!!, 0.0)
        assertNull("the approval is used up", row().pending)
    }

    @Test fun inLiveAnApprovalWithoutThePinSendsNothing() {
        state()
        assertEquals("The app is in Live: approve with your PIN on Home → Strategies.", runBlocking { OrbArms.approve("liquidity", pinConfirmed = false) })
        assertTrue(kite.requests.isEmpty())
        assertTrue("still waiting for the PIN", row().pending != null)
    }

    @Test fun theKillSwitchRefusesTheEntry() {
        SecurePrefs.put("g.kill", true)
        state()
        assertEquals("Refused: the kill switch is on", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun anExpiredSignalIsNotEntered() {
        state(expires = now.minusMinutes(1))
        val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) }
        assertTrue(msg, msg.startsWith("The Liquidity 15+5 signal expired at "))
        assertTrue(kite.placed.isEmpty())
    }
}
