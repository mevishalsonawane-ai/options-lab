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
}
