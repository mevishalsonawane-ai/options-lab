package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals

class AddressTest {
    @Test fun jarvisCallsTheOwnerBoss() {
        assertEquals("Boss, BankNifty is at 52,140.25.", Address.boss("BankNifty is at 52,140.25."))
        assertEquals("Boss, the kill switch is off.", Address.boss("The kill switch is off."))
        assertEquals("Boss, tap Confirm to stop ORB 15.", Address.boss("Tap Confirm to stop ORB 15."))
        assertEquals("Boss, I can tell you about Nifty.", Address.boss("I can tell you about Nifty."))
        assertEquals("Hello Boss. Ask me about Nifty.", Address.boss("Hello. Ask me about Nifty."))
        assertEquals("Good morning Boss. All set.", Address.boss("Good morning. All set."))
        assertEquals("Boss, 2 of 3 strategies are on.", Address.boss("2 of 3 strategies are on."))
        assertEquals("Boss, already said.", Address.boss("Boss, already said."))
        assertEquals("Boss, NIFTY 24000 CE is listed.", Address.boss("NIFTY 24000 CE is listed."))
    }
}
