package com.optionslab.app.data

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
import java.time.LocalDate

/**
 * [LiveSelfTest] against the fake Kite: which steps pass and fail and why, and that it only ever READS:
 * no POST, PUT or DELETE may reach Zerodha from it, whatever the account holds.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveSelfTestTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val names = listOf("Session", "Funds", "Positions", "Order book", "Holdings", "GTT triggers", "Quote (NIFTY 50)", "Contract list", "Static IP for orders")

    @Before fun up() {
        kite = FakeKite()
        BrokerArea.phoneIp(null)
        BrokerArea.listNifty(kite, listOf(LocalDate.now().plusDays(9)))
        kite.quote("NSE:NIFTY 50", 24_480.0)
    }

    @After fun down() {
        kite.close()
        BrokerArea.phoneIp(null)
        assertEquals("the self-test reached the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun keysAndLogin() {
        // Configured = key plus a sealed secret; the seal itself is not what is tested here.
        SecurePrefs.put("kite.apiSecretSealed", "sealed-test-not-real")
        kite.login()
    }

    private fun run(): Pair<List<LiveSelfTest.Step>, List<LiveSelfTest.Step>> {
        val seen = ArrayList<LiveSelfTest.Step>()
        val out = runBlocking { LiveSelfTest.run { seen += it } }
        return out to seen
    }

    private fun onlyReads() {
        assertTrue("the self-test may only read: ${kite.requests.filter { it.method != "GET" }.map { it.method + " " + it.path }}",
            kite.requests.all { it.method == "GET" })
        assertTrue(kite.writes.isEmpty())
        assertTrue(kite.orders.isEmpty() && kite.gtts.isEmpty())
    }

    @Test fun allNineStepsPassWithTheRegisteredIp() {
        keysAndLogin()
        StaticIp.registered = "13.235.10.20"
        BrokerArea.phoneIp("13.235.10.20")
        // Something in every book, so each read has rows to count.
        kite.position("NIFTY26OCT24500CE", 75, 100.0)
        val (out, seen) = run()
        assertEquals(names, out.map { it.name })
        assertEquals("every step is reported as it finishes, in order", out, seen)
        assertTrue(out.joinToString { "${it.name}: ${it.detail}" }, out.all { it.ok })
        assertEquals("logged in", out[0].detail)
        assertEquals("1 open or closed today", out[2].detail)
        assertEquals("0 orders today", out[3].detail)
        assertEquals("price received", out[6].detail)
        assertEquals("6 contracts", out[7].detail)
        assertEquals("orders would leave from the registered IP", out[8].detail)
        assertTrue(out.all { it.ms >= 0 })
        onlyReads()
    }

    @Test fun neverWritesEvenWithWorkingOrdersAndGttsAtZerodha() {
        keysAndLogin()
        runBlocking {
            kite.nextPlace(outcome = FakeKite.Outcome.OPEN)
            Broker.placeOrder(com.optionslab.engine.Kite.Order("NIFTY26OCT24500CE", com.optionslab.engine.Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 1.0), exit = true)
        }
        kite.gtts[5L] = org.json.JSONObject().put("id", 5).put("type", "single").put("status", "active").put("created_at", "2026-01-01 10:00:00")
            .put("condition", org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", "X").put("trigger_values", org.json.JSONArray().put(1.0)))
            .put("orders", org.json.JSONArray())
        kite.requests.clear()
        val (out, _) = run()
        assertEquals("1 orders today", out.first { it.name == "Order book" }.detail)
        assertEquals("1 triggers", out.first { it.name == "GTT triggers" }.detail)
        assertTrue(kite.requests.isNotEmpty())
        assertTrue(kite.requests.all { it.method == "GET" })
        assertEquals("the working order is untouched", "OPEN", kite.orders.values.single().status)
        assertEquals(1, kite.gtts.size)
    }

    @Test fun notSetUpSkipsTheAccountReads() {
        val (out, _) = run()
        assertEquals(listOf("Session", "Static IP for orders"), out.map { it.name })
        assertFalse(out[0].ok); assertEquals("Zerodha is not set up on this phone", out[0].detail)
        assertFalse(out[1].ok); assertEquals("no static IP registered in the app (More → Zerodha → Static IP)", out[1].detail)
        assertTrue("nothing asked of Zerodha", kite.requests.isEmpty())
    }

    @Test fun keysButNoLoginToday() {
        SecurePrefs.put("kite.apiKey", "testkey")
        SecurePrefs.put("kite.apiSecretSealed", "sealed-test-not-real")
        val (out, _) = run()
        assertEquals(listOf("Session", "Static IP for orders"), out.map { it.name })
        assertEquals("not logged in today: log in first", out[0].detail)
        assertTrue(kite.requests.isEmpty())
    }

    @Test fun aFailedReadDoesNotStopTheRest() {
        keysAndLogin()
        kite.down += "/portfolio/holdings"
        val (out, _) = run()
        assertEquals(names, out.map { it.name })
        val holdings = out.first { it.name == "Holdings" }
        assertFalse(holdings.ok)
        assertEquals("Kite's own reason is shown", "Gateway timed out", holdings.detail)
        assertTrue(out.filter { it.name != "Holdings" && it.name != "Static IP for orders" }.all { it.ok })
        onlyReads()
    }

    @Test fun noNiftyPriceIsAFailedStep() {
        keysAndLogin()
        kite.quotes.clear()
        val (out, _) = run()
        val q = out.first { it.name == "Quote (NIFTY 50)" }
        assertFalse(q.ok); assertEquals("no price returned", q.detail)
    }

    @Test fun aSessionEndedAtZerodhaFailsEveryReadWithTheReason() {
        keysAndLogin()
        kite.sessionExpired = true
        val (out, _) = run()
        assertTrue(out[0].ok)
        val funds = out.first { it.name == "Funds" }
        assertFalse(funds.ok)
        assertTrue(funds.detail, funds.detail.startsWith("Zerodha session ended"))
        // The session was dropped, so the later reads stop at the app without asking Zerodha again.
        val later = out.filter { it.name in listOf("Positions", "Order book", "Holdings", "GTT triggers", "Contract list") }
        assertTrue(later.joinToString { it.detail }, later.none { it.ok })
        assertTrue(later.all { it.detail == "Log in to Zerodha for today first" })
        assertFalse(Broker.loggedIn)
        onlyReads()
    }

    @Test fun theWrongIpFailsTheStaticIpStepWithBothAddresses() {
        keysAndLogin()
        StaticIp.registered = "13.235.10.20"
        BrokerArea.phoneIp("49.36.1.2")
        val (out, _) = run()
        val ip = out.last()
        assertEquals("Static IP for orders", ip.name)
        assertFalse(ip.ok)
        assertTrue(ip.detail, ip.detail.startsWith("Orders would reach Zerodha from 49.36.1.2, not your registered static IP 13.235.10.20"))
        onlyReads()
    }

    @Test fun resultsCarryNoTokenKeyOrSecret() {
        keysAndLogin()
        kite.sessionExpired = true
        val (out, _) = run()
        val text = out.joinToString("\n") { it.name + " " + it.detail }
        for (secret in listOf("test-token-not-real", "testkey", "sealed-test-not-real", "api.kite.trade", "127.0.0.1", "localhost"))
            assertFalse("'$secret' in the self-test output", text.contains(secret))
    }
}
