package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 18 Oct, review: a request's result and a late answer are checked against the lock when they are said, not when asked. */
class LockRuleAtSayTest {
    private val filled = "Boss, bought 30 BANKNIFTY25OCT55000CE at ₹212.50."

    @Test fun aResultIsSaidWholeOnAnUnlockedPhone() {
        assertEquals(filled, LockRule.resultAloud(filled, lockedNow = false))
    }

    @Test fun aResultOnAPhoneThatLockedMeanwhileSaysOnlyThatItIsReady() {
        val said = LockRule.resultAloud(filled, lockedNow = true)
        assertEquals(LockRule.RESULT_LOCKED, said)
        assertFalse(Overheard.holdsAccount(said))
        // Even a result with no figures in it (a rejection): nothing about the request is said.
        assertEquals(LockRule.RESULT_LOCKED, LockRule.resultAloud("Rejected. Nothing was placed.", lockedNow = true))
    }

    @Test fun anAnswerReadyAfterThePhoneLockedHidesTheAccount() {
        val pnl = "Your P&L today is +₹4,200 on 2 trades."
        assertEquals(LockRule.ANSWER_LOCKED, LockRule.answerAloud(pnl, lockedAsked = false, lockedNow = true))
        assertEquals(pnl, LockRule.answerAloud(pnl, lockedAsked = false, lockedNow = false), "unlocked still: whole")
        assertEquals(pnl, LockRule.answerAloud(pnl, lockedAsked = true, lockedNow = true), "asked locked: judged when asked")
        val plain = "Good morning, Boss."
        assertEquals(plain, LockRule.answerAloud(plain, lockedAsked = false, lockedNow = true), "nothing about the account: said as it is")
    }
}
