package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Review, 5 Oct: "more" on a locked phone said the whole last answer aloud - the account and unasked notes too. */
class MoreAnswerTest {
    private val pnl = MoreAnswer.Last(AppFacts.pnl("Zerodha", -8200.0, -8000.0, -200.0) + " Paper: 2 open positions.", "what's my pnl", unasked = false)
    private val market = MoreAnswer.Last("Nifty is at 24,612.40 (-0.42% on the day) as of 14:05. The 15-minute trend is down since 13:30.", "how is nifty", unasked = false)
    private val note = MoreAnswer.Last("Boss, the order watch stopped: no check since 09:31, with 2 positions open.", null, unasked = true)
    private val lockedWhy = LockRule.refuse(true, false, true, false)!!

    @Test fun unlockedSaysTheWholeAnswer() {
        for (l in listOf(pnl, market, note)) {
            val r = MoreAnswer.reply(l, locked = false) { error("the voice is never checked on an unlocked phone") }
            assertIs<MoreAnswer.Reply.Say>(r); assertEquals(l.text, r.text)
        }
        assertEquals(MoreAnswer.Reply.Say(MoreAnswer.NONE, false), MoreAnswer.reply(null, locked = true) { false })
    }

    @Test fun lockedRefusesTheAccountUnlessBossesVoice() {
        assertEquals(MoreAnswer.Reply.Refused(lockedWhy), MoreAnswer.reply(pnl, locked = true) { false })
        // The same voice check an account question passes on a locked phone.
        assertEquals(MoreAnswer.Reply.Say(pnl.text, true), MoreAnswer.reply(pnl, locked = true) { true })
        // A voice check that fails to run is not Boss's voice.
        assertEquals(MoreAnswer.Reply.Refused(lockedWhy), MoreAnswer.reply(pnl, locked = true) { throw IllegalStateException() })
    }

    @Test fun lockedNeverSaysAnUnaskedNote() {
        assertEquals(MoreAnswer.Reply.Refused(lockedWhy), MoreAnswer.reply(note, locked = true) { true })
        assertEquals(MoreAnswer.Reply.Refused(lockedWhy), MoreAnswer.reply(note.copy(text = "Nifty crossed 24,600."), locked = true) { true })
    }

    @Test fun lockedMarketAnswerIsSaidWithoutTheVoiceCheck() {
        var asked = false
        val r = MoreAnswer.reply(market, locked = true) { asked = true; false }
        assertEquals(MoreAnswer.Reply.Say(market.text, false), r)
        assertFalse(asked)
    }

    @Test fun accountIsReadFromTheQuestionOrTheWords() {
        assertTrue(MoreAnswer.account(pnl))
        assertFalse(MoreAnswer.account(market))
        // A market question whose answer named an amount or a contract of Boss's: the account.
        assertTrue(MoreAnswer.account(market.copy(text = "Nifty is at 24,612. Your NIFTY24500PE is down Rs 2,250.")))
        // No question: may hold anything.
        assertTrue(MoreAnswer.account(market.copy(question = null)))
    }

    @Test fun anUnaskedLineIsRecheckedWhenFinallySaid() {
        val t = "Your P&L is +Rs 2,575 now."
        assertEquals(t, Overheard.atSay(t, locked = false, quiet = false))
        assertEquals(Overheard.SAID, Overheard.atSay(t, locked = true, quiet = false))
        assertEquals(null, Overheard.atSay(t, locked = false, quiet = true))
        assertEquals("Nifty crossed 24,600.", Overheard.atSay("Nifty crossed 24,600.", locked = true, quiet = false))
    }
}
