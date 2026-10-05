package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnswerWindowTest {
    private val usual = MorningAsks.Usual("NIFTY|levels", 4, 5, java.time.LocalDate.of(2026, 10, 2))

    @Test fun aLineEndingInAnInvitationOpensTheWindow() {
        assertTrue(AnswerWindow.invites("Good morning, Boss. We are set for today's trading. " + MorningAsks.line(usual)))
        assertTrue(AnswerWindow.invites("Boss, Nifty broke out. Shall I buy 1 lot of the Nifty call? Yes or no?"))
        assertTrue(AnswerWindow.invites("Before you answer, Boss: you turned down 3 of my ideas lately as too late in the day."))
        assertTrue(AnswerWindow.invites(AnswerWindow.HOLD), "the hold line asks for the request's answer again")
    }

    @Test fun aYesSaidEarlierOrInPassingIsNoInvitation() {
        assertFalse(AnswerWindow.invites(null)); assertFalse(AnswerWindow.invites(""))
        assertFalse(AnswerWindow.invites("Nifty is at 24,100, Boss. Yes, it is up 0.4% today."))
        assertFalse(AnswerWindow.invites(MorningAsks.line(usual) + " Have a good day."), "the invitation must end the line")
        assertFalse(AnswerWindow.invites("I said yes or no earlier. Nothing is waiting now."))
    }

    @Test fun aYesIsForTheRequestOnlyWhenItsQuestionWasTheLastInvitation() {
        assertEquals(AnswerWindow.For.REQUEST, AnswerWindow.yesFor(true, AnswerWindow.Invite.QUESTION))
        assertEquals(AnswerWindow.For.REQUEST, AnswerWindow.yesFor(true, null))
        // The 09:00 offer said over a waiting trade question: the yes is taken for neither.
        assertEquals(AnswerWindow.For.HOLD, AnswerWindow.yesFor(true, AnswerWindow.Invite.OFFER))
        assertEquals(AnswerWindow.For.WORDS, AnswerWindow.yesFor(false, AnswerWindow.Invite.OFFER))
        assertEquals(AnswerWindow.For.WORDS, AnswerWindow.yesFor(false, AnswerWindow.Invite.QUESTION))
    }

    @Test fun theOffersYesNeverConfirmsAndIsNeverTakenWhileSomethingWaits() {
        val at = LocalDateTime.of(2026, 10, 5, 9, 0)
        assertNotNull(MorningAsks.taken("NIFTY|levels", at, "yes", waiting = false, now = at.plusMinutes(1)))
        assertNull(MorningAsks.taken("NIFTY|levels", at, "yes", waiting = true, now = at.plusMinutes(1)), "an action or order waits")
        assertNull(MorningAsks.taken("NIFTY|levels", at, "yes buy it", waiting = false, now = at.plusMinutes(1)))
        assertNull(MorningAsks.taken("NIFTY|levels", at, "yes", waiting = false, now = at.plusMinutes(MorningAsks.YES_MINUTES + 1)))
        // What a yes asks is a plain market question: never an order or a command.
        val q = MorningAsks.taken("NIFTY|levels", at, "haan batao", waiting = false, now = at)!!
        val p = Ask.parse(q)
        assertNull(p.order, q); assertNull(p.command, q)
        assertFalse(Topic.ORDER in p.topics || Topic.COMMAND in p.topics, q)
    }

    @Test fun theHoldLineNeverSaysABareYesThatCouldBeHeardBack() {
        // Jarvis's own words heard back without his name are never a yes for anything (a turn begun while he spoke
        // needs the name); the hold line holds no lone yes word either.
        assertFalse(Regex("\\byes\\b(?! or no)").containsMatchIn(AnswerWindow.HOLD.lowercase()))
    }
}
