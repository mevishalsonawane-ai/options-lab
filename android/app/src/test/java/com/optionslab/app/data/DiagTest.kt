package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Alerts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The diagnostics diary: keeps what the app said, never a key or token, and reports it for the owner to paste. */
class DiagTest : RobolectricTest() {
    @Test fun anythingLikeAKeyOrTokenIsRedacted() {
        val token = "a1B2c3D4e5F6g7H8i9J0k1L2m3N4"
        assertFalse(Diag.redact("session token $token refused").contains(token))
        assertTrue(Diag.redact("Order 250928000123 rejected: RMS margin").contains("250928000123"))
        assertTrue("the option stays readable", Diag.redact("tradingsymbol: BANKNIFTY26SEP54000CE exchange: NFO").contains("BANKNIFTY26SEP54000CE"))
    }

    @Test fun bannersAndSelfTestStepsReachTheReport() = runBlocking {
        Alerts.error("Order refused: Markets are closed right now", title = "Kite")
        Diag.record("self-test", "FAILED Funds: timeout")
        val r = Diag.report()
        assertTrue(r, r.contains("[error] Kite: Order refused: Markets are closed right now"))
        assertTrue(r, r.contains("[self-test] FAILED Funds: timeout"))
        assertTrue(r, r.startsWith("IraAlgo diagnostics"))
        assertTrue("newest first, so a cut-off paste keeps today", r.indexOf("[self-test] FAILED Funds") < r.indexOf("Kite: Order refused"))
        assertFalse("no identities in the header", r.contains("Chavan"))
    }

    @Test fun aSecretInABannerNeverReachesTheDiary() = runBlocking {
        val secret = "zQ9xW8vU7tS6rR5qP4oN3mM2lL1kK0jJ"
        Alerts.post("login failed for key $secret")
        assertFalse(Diag.report().contains(secret))
    }

    @Test fun theGoldSectionShowsBothArmsAndTheirTrades() = runBlocking {
        val t = java.time.LocalDateTime.of(2026, 9, 29, 12, 5)
        GoldPaper.testNow = t
        try {
            GoldPaper.replaceForTest(GoldPaper.Book(armed = true, status = "No liquidity break on the 11:00 UTC (16:30 IST) candle", price = 4210.5, priceAt = t.minusMinutes(11),
                trades = listOf(GoldPaper.Trade(4180.0, 4190.0, t.minusDays(1), t.minusHours(20), 0.01, "next_liquidity", 9.93))))
            GoldTrendPaper.replaceForTest(GoldTrendPaper.Book(armed = true, status = "Holding the buy from 4150.00: trend up", up = true, line = 4120.25,
                position = GoldTrendPaper.Position(4150.0, t.minusDays(2), 0.01, 30.0, 4215.0, t.minusMinutes(12)),
                trades = listOf(GoldPaper.Trade(4000.0, 4100.0, t.minusDays(9), t.minusDays(5), 0.01, "giveback", 99.93))))
            val g = Diag.gold()
            assertTrue(g, g.contains("-- Gold --"))
            assertTrue(g, g.contains("Price 4210.50"))
            assertTrue(g, g.contains("Liquidity 1h: armed true · status \"No liquidity break"))
            assertTrue(g, g.contains("Trend 4h: armed true · status \"Holding the buy from 4150.00"))
            assertTrue(g, g.contains("trend up · line 4120.25 · waiting for a flip after a lock sale: false"))
            assertTrue("the lock: 4215 - 4 x 30", g.contains("lock 4095.00"))
            assertTrue(g, g.contains("realised +$109.86"))
            assertTrue("newest trade first", g.indexOf("Liquidity 1h: 12:05") < g.indexOf("Trend 4h: 12:05"))
            assertTrue(g, g.contains("giveback · +$99.93"))
        } finally {
            GoldPaper.testNow = null
            GoldPaper.replaceForTest(GoldPaper.Book()); GoldTrendPaper.replaceForTest(GoldTrendPaper.Book())
        }
    }
}
