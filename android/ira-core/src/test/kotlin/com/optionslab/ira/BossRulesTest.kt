package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BossRulesTest {
    @Test fun notesBecomeRules() {
        assertEquals(BossRules.Kind.AVOID_MARKET, BossRules.of("I don't trade BankNifty")?.kind)
        assertEquals(Market.BANKNIFTY, BossRules.of("I don't trade bank nifty")?.market)
        assertEquals(BossRules.Kind.AVOID_EXPIRY, BossRules.of("skip expiry days")?.kind)
        BossRules.of("no trades before 9:30")!!.let { assertEquals(BossRules.Kind.NOT_BEFORE, it.kind); assertEquals(570, it.minute) }
        BossRules.of("no new trades after 2 pm")!!.let { assertEquals(BossRules.Kind.NOT_AFTER, it.kind); assertEquals(840, it.minute) }
        assertNull(BossRules.of("I want to book at 25,000"))
        assertNull(BossRules.of("my son's birthday is in May"))
        // Memory keeps the note; the rule comes from the same words.
        assertEquals("I don't trade BankNifty", Memory.toKeep("Jarvis, remember that I don't trade BankNifty"))
    }

    @Test fun rulesHoldTradesBack() {
        val notes = listOf("I don't trade BankNifty", "no trades before 9:30", "I want to book at 25,000")
        assertTrue(BossRules.blocks(notes, Market.BANKNIFTY, false, 600)!!.startsWith("you asked me to remember"))
        assertTrue(BossRules.blocks(notes, Market.NIFTY, false, 560) != null)
        assertNull(BossRules.blocks(notes, Market.NIFTY, false, 600))
        assertTrue(BossRules.blocks(listOf("skip expiry days"), Market.NIFTY, true, 600) != null)
        assertTrue(BossRules.saidBack("I don't trade BankNifty").endsWith("no BankNifty trades offered or taken."))
        assertEquals("", BossRules.saidBack("I want to book at 25,000"))
    }
}
