package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretsTest {
    @Test fun secretsAreNeverKept() {
        assertEquals("my password is [hidden]", Secrets.redact("my password is hunter2"))
        assertEquals("pin [hidden]", Secrets.redact("pin 4321"))
        assertEquals("the OTP: [hidden]", Secrets.redact("the OTP: 889911"))
        assertEquals("card [hidden] expires", Secrets.redact("card 4111 1111 1111 1111 expires"))
        assertEquals("aadhaar [hidden]", Secrets.redact("aadhaar 1234 5678 9012"))
        assertEquals("account [hidden]", Secrets.redact("account 50100234567890"))
        assertEquals("call [hidden]", Secrets.redact("call 9876543210"))
        assertEquals("pan [hidden]", Secrets.redact("pan ABCDE1234F"))
        assertEquals("pay [hidden]", Secrets.redact("pay vishal@okicici"))
        assertTrue(Secrets.hasSecret("cvv is 123"))
    }

    @Test fun marketWordsStay() {
        for (t in listOf("buy 2 lots banknifty 52000 ce", "alert me when nifty goes above 25000", "Nifty is at 24,612.40 (+0.21%)",
                "stop strategy 1", "backtest the breakout on BankNifty 15m", "how is my pnl today", "nifty 24000 ce expiry 2026-10-07"))
            assertFalse(Secrets.hasSecret(t), t)
    }
}
