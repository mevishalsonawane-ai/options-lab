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

    /** 18 Oct, review: broker tokens (test values only, never real ones). */
    @Test fun tokensAreNeverKept() {
        assertEquals("remember my zerodha access token is [hidden]", Secrets.redact("remember my zerodha access token is a1b2c3d4e5f6"))
        assertEquals("the request token: [hidden]", Secrets.redact("the request token: Zx81testvalue"))
        assertEquals("enctoken [hidden]", Secrets.redact("enctoken abc123"))
        assertEquals("Authorization: Bearer [hidden]", Secrets.redact("Authorization: Bearer abc123def"))
        assertEquals("my dhan token [hidden]. ok", Secrets.redact("my dhan token abc12345. ok"))
        // The word alone, or with ordinary words after it, keeps the question's meaning.
        for (q in listOf("why did my zerodha token expire today, and can I log in", "is the token still valid", "token"))
            assertEquals(q, Secrets.redact(q))
        // A JWT on its own, and a 32-character letter-and-digit key on its own.
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJlLXRlc3Q"
        assertEquals("use [hidden] now", Secrets.redact("use $jwt now"))
        assertEquals("my dhan token [hidden]", Secrets.redact("my dhan token $jwt"))
        assertEquals("key [hidden] here", Secrets.redact("key a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6 here"))
        assertTrue(Secrets.hasSecret(jwt))
        // Edge: exactly 24 mixed is hidden, 23 is not; letters only or digits-only runs are not this rule's.
        assertEquals("[hidden]", Secrets.redact("a1b2c3d4e5f6a7b8c9d0e1f2"))
        assertEquals("a1b2c3d4e5f6a7b8c9d0e1f", Secrets.redact("a1b2c3d4e5f6a7b8c9d0e1f"))
        assertFalse(Secrets.hasSecret("supercalifragilisticexpialidocious"))
        assertEquals("", Secrets.redact(""))
    }

    @Test fun tradingSymbolsAreNotKeys() {
        for (t in listOf("buy BANKNIFTY26OCT52000CE", "sell MIDCPNIFTY26OCT13000CE at 120", "NIFTY2610724500PE stop 80", "CRUDEOILM26NOV6500CE",
                "a long one: ULTRACEMCOLTDX26OCT1100000CE"))
            assertFalse(Secrets.hasSecret(t), t)
    }

    @Test fun marketWordsStay() {
        for (t in listOf("buy 2 lots banknifty 52000 ce", "alert me when nifty goes above 25000", "Nifty is at 24,612.40 (+0.21%)",
                "stop strategy 1", "backtest the breakout on BankNifty 15m", "how is my pnl today", "nifty 24000 ce expiry 2026-10-07"))
            assertFalse(Secrets.hasSecret(t), t)
    }
}
