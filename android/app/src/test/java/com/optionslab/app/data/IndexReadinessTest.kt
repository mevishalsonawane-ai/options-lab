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

/**
 * 3 Nov (Boss: "check all the symbols like MIDCPNIFTY which work on paper also work on live"): the morning's check that each
 * index the arms trade has an at-the-money call Zerodha prices for 1 lot - and that it only ever READS from Zerodha.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class IndexReadinessTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val expiry get() = Market.today().plusDays(6)

    @Before fun up() {
        kite = FakeKite()
        SecurePrefs.put("kite.apiSecretSealed", "sealed-test-not-real")
        kite.login()
        BrokerArea.listNifty(kite, listOf(Market.today(), expiry), strikes = listOf(24_400.0, 24_500.0), lot = 65)
        BrokerArea.listNifty(kite, listOf(expiry), strikes = listOf(55_000.0, 55_100.0), lot = 30, name = "BANKNIFTY")
        BrokerArea.listNifty(kite, listOf(expiry), strikes = listOf(25_000.0, 25_050.0), lot = 60, name = "FINNIFTY")
        BrokerArea.listNifty(kite, listOf(expiry), strikes = listOf(13_400.0, 13_425.0), lot = 120, name = "MIDCPNIFTY")
        var t = 40_000_000L
        for (k in listOf(76_000.0, 76_100.0)) for (r in listOf("CE", "PE"))
            kite.instruments += FakeKite.Ins(t++, BrokerArea.symbol("SENSEX", expiry, k, r), "SENSEX", expiry, k, r, 20, exchange = "BFO")
        kite.quote("NSE:NIFTY 50", 24_480.0); kite.quote("NSE:NIFTY BANK", 55_090.0); kite.quote("NSE:NIFTY FIN SERVICE", 25_010.0)
        kite.quote("NSE:NIFTY MID SELECT", 13_420.0); kite.quote("BSE:SENSEX", 76_080.0)
        for (i in kite.instruments) kite.quote("${i.exchange}:${i.symbol}", 100.0, 99.95, 100.05)
        kite.marginPerOrder = 12_000.0
        Broker.resetBfoForTest()
        java.io.File(context.filesDir, "kite_instruments.json").delete()
    }

    @After fun down() {
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun everyIndexTheArmsTradeIsPricedForOneLotAndNothingIsPlaced() = runBlocking {
        val lines = IndexReadiness.check(available = 50_000.0, mcxArmsLive = false)
        assertEquals(IndexReadiness.INDICES, lines.map { it.text.substringBefore(":") })
        assertTrue(lines.joinToString("\n") { it.text }, lines.all { it.ok })
        assertTrue("no amount in any line (the morning is spoken)", lines.none { it.text.contains("₹") })
        // The ATM call of the nearest expiry that is not today (the arms never buy today's expiry).
        assertTrue(lines[0].text, lines[0].text.startsWith("NIFTY: " + BrokerArea.symbol("NIFTY", expiry, 24_500.0, "CE") + " orderable at Zerodha (1 lot priced by its margin check)"))
        assertTrue(lines[3].text, lines[3].text.startsWith("MIDCPNIFTY: " + BrokerArea.symbol("MIDCPNIFTY", expiry, 13_425.0, "CE")))
        assertTrue(lines[4].text, lines[4].text.startsWith("SENSEX: " + BrokerArea.symbol("SENSEX", expiry, 76_100.0, "CE")))
        val asked = kite.requests.filter { it.path == "/margins/orders" }
        assertEquals("one margin read per index", 5, asked.size)
        assertTrue("MIS MARKET, as the arms send", asked.all { it.body.contains("\"MARKET\"") && it.body.contains("\"MIS\"") })
        assertTrue("reads only: nothing placed, changed or cancelled", kite.placed.isEmpty() && kite.writes.isEmpty())
    }

    @Test fun aLotTheFreeMoneyDoesNotCoverIsSaidOnceWithNoAmount() = runBlocking {
        val lines = IndexReadiness.check(available = 10_000.0, mcxArmsLive = false)
        assertEquals("the five indices are still orderable", 5, lines.count { it.ok })
        assertEquals(IndexReadiness.Line(false, "1 lot of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX needs more than the free money in the account"), lines.last())
        assertTrue(lines.none { it.text.contains("₹") })
    }

    @Test fun anIndexNotListedTodayFailsAloneAndTheRestAreChecked() = runBlocking {
        kite.instruments.removeAll { it.name == "FINNIFTY" }
        java.io.File(context.filesDir, "kite_instruments.json").delete()
        val lines = IndexReadiness.check(available = 50_000.0, mcxArmsLive = false)
        assertEquals("FINNIFTY: no options listed at Zerodha today", lines[2].text)
        assertFalse(lines[2].ok)
        assertEquals(4, lines.count { it.ok })
    }

    @Test fun theLineSaysALowOpenInterest() {
        assertEquals(IndexReadiness.Line(true, "NIFTY: NIFTY26N1023600CE orderable at Zerodha (1 lot priced by its margin check)"),
            IndexReadiness.line("NIFTY", "NIFTY26N1023600CE", 65, 1_000_000))
        val thin = IndexReadiness.line("FINNIFTY", "FINNIFTY26NOV25050CE", 60, 3_000)
        assertTrue(thin.ok); assertTrue(thin.text, thin.text.contains("low open interest: Zerodha blocks MARKET orders on it, the arms send a LIMIT"))
        assertFalse("an unknown open interest says nothing", IndexReadiness.line("X", "S", 60, 0).text.contains("open interest"))
        assertFalse(IndexReadiness.line("X", "S", 60, null).text.contains("open interest"))
    }
}
