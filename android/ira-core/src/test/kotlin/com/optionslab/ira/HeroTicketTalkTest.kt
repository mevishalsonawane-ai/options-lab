package com.optionslab.ira

import com.optionslab.engine.orb.HeroTicketRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 15 Oct (Boss's choice): Jarvis on the Hero ₹1,000 ticket - what is asked (and what is not: an action, the expiry ticket, the
 * expiry-day Hero arm), the answer always labelled as not passing research, and Boss's own change logged as theirs.
 */
class HeroTicketTalkTest {
    @Test fun itsQuestionsAreReadAndOthersAreNot() {
        for (q in listOf("how is the hero ticket doing", "what did the hero ticket do today", "how did the 1000 ticket do",
            "hero ticket kaisa chal raha hai", "tell me about the hero 1000 ticket", "How's the ₹1,000 ticket?", "hero tickets today",
            "thousand rupee ticket kya hua"))
            assertTrue(HeroTicketTalk.asked(q), q)
        for (q in listOf("switch off the hero ticket", "turn on hero ticket", "hero ticket band karo", "close the hero ticket",
            "what did hero do today", "is today a hero day", "how is my expiry ticket", "show the ticket", "buy a 1000 ticket",
            "set the hero ticket to 2x", "how is nifty", ""))
            assertFalse(HeroTicketTalk.asked(q), q)
    }

    private fun facts(armed: Boolean? = true, target: HeroTicketRules.Target = HeroTicketRules.Target.TRAIL_ONLY,
                      today: List<HeroTicketTalk.Ticket> = emptyList(), status: Map<String, String> = emptyMap(), closed: Int = 0, net: Double = 0.0) =
        HeroTicketTalk.Facts(armed, target, today, status, closed, net)

    @Test fun theAnswerAlwaysSaysItDidNotPassResearchAndKeepsItsOwnRecord() {
        val a = HeroTicketTalk.answer(facts(status = mapOf("NIFTY" to "not its expiry day", "SENSEX" to "not its expiry day")))
        assertTrue(a.startsWith("Hero ₹1,000 ticket: on, paper only. ${HeroTicketRules.RECORD}."), a)
        // 25 Oct: the default is the trail from the fill (R55), said with its note.
        assertTrue("your 25% trail (from the fill, so it is also a 25% stop), else 15:05 (no fixed target)" in a, a)
        assertTrue(HeroTicketRules.TRAIL_START_NOTE in a, a)
        val two = HeroTicketTalk.answer(HeroTicketTalk.Facts(true, HeroTicketRules.Target.TRAIL_ONLY, emptyList(), emptyMap(), 0, 0.0, HeroTicketRules.TrailStart.AFTER_2X))
        assertTrue("once it has closed at 2× its price, no stop before that" in two && HeroTicketRules.TRAIL_START_NOTE !in two, two)
        assertTrue("closed at 3× its price" in HeroTicketTalk.answer(HeroTicketTalk.Facts(true, HeroTicketRules.Target.TRAIL_ONLY, emptyList(), emptyMap(), 0, 0.0, HeroTicketRules.TrailStart.AFTER_3X)))
        assertTrue("No ticket today (NIFTY not its expiry day; SENSEX not its expiry day)." in a, a)
        assertTrue("no closed ticket yet" in a && "plain-rules record for 26 Oct" in a, a)
        assertTrue(HeroTicketRules.MEASURED in a && HeroTicketRules.SIGNAL_SOURCE in a, a)
        assertTrue(HeroTicketTalk.answer(facts(armed = false)).startsWith("Hero ₹1,000 ticket: off."))
        assertTrue("could not be read" in HeroTicketTalk.answer(facts(armed = null)))
        assertTrue("No ticket today." in HeroTicketTalk.answer(facts()))
    }

    @Test fun todaysTicketsWithTheirPnlAndTheExitBossPicked() {
        val a = HeroTicketTalk.answer(facts(target = HeroTicketRules.Target.X2, closed = 3, net = -812.4, today = listOf(
            HeroTicketTalk.Ticket("SENSEX", "SENSEX15OCT2672500CE", 20, 24.95, mark = 30.0),
            HeroTicketTalk.Ticket("SENSEX", "SENSEX15OCT2672400PE", 20, 22.0, exit = 16.0, why = "ticket_trail", net = -150.0),
            HeroTicketTalk.Ticket("SENSEX", "X", 20, 10.0, exit = 20.0, why = "ticket_target", net = 180.0),
            HeroTicketTalk.Ticket("SENSEX", "Y", 20, 10.0, exit = 8.0, why = "ticket_1505", net = -60.0),
            HeroTicketTalk.Ticket("SENSEX", "Z", 20, 10.0, exit = 9.0, why = "closed_elsewhere", net = -30.0),
            HeroTicketTalk.Ticket("SENSEX", "W", 20, 10.0, exit = 9.0, net = -30.0),
            HeroTicketTalk.Ticket("NIFTY", "V", 65, 10.0))))
        assertTrue("a 2× target or your 25% trail (from the fill, so it is also a 25% stop), whichever comes first, else 15:05" in a, a)
        assertTrue("SENSEX15OCT2672500CE 20 @ 24.95, now 30.00 (+₹101 before charges)" in a, a)
        assertTrue("SENSEX15OCT2672400PE bought @ 22.00, sold @ 16.00 on the 25% trail, −₹150 after charges" in a, a)
        assertTrue("sold @ 20.00 at its target" in a && "at 15:05" in a && "closed elsewhere (closed_elsewhere)" in a && "W bought @ 10.00, sold @ 9.00 sold" in a, a)
        assertTrue("V 65 @ 10.00, held" in a, a)
        assertTrue("3 tickets, −₹812 after charges" in a, a)
        assertTrue("1 ticket, +₹50 after charges" in HeroTicketTalk.answer(facts(closed = 1, net = 50.0)))
        assertTrue("a 3× target" in HeroTicketTalk.answer(facts(target = HeroTicketRules.Target.X3)))
    }

    @Test fun bossTurningItOnIsLoggedAsTheirOwnPaperChangeUndoneInOneTap() {
        val c = AutoChange.heroTicketOn()
        assertEquals(AutoChange.Kind.PAPER_ARM_ON, c.kind)
        assertEquals(AutoChange.TICKET_TARGET, c.target)
        assertEquals(AutoChange.SWITCHED_OFF, c.from); assertEquals(AutoChange.ON, c.to)
        assertEquals(AutoChange.Scope.PAPER, c.scope)
        assertTrue(AutoChange.bossOwn(c.kind))
        assertFalse(AutoChange.never(c), "Boss's own paper change is never on the denylist")
        assertFalse(c.kind.never)
        for (w in listOf("your choice (15 Oct)", "paper only", "did not pass research (R50", "₹525", "own record", "26 Oct")) assertTrue(w in c.reason, w)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(c), "a paper arm on adds paper trades")
        // One tap undoes it: the arm off, on paper - lower risk, never the PIN.
        val back = AutoChange.inverse(c, "undone by you")
        assertEquals(AutoChange.SWITCHED_OFF, back.to)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(back))
        assertFalse(AutoChange.undoNeedsPin(c))
        val book = AutoChange.record(AutoChange.Book(), c, AutoChange.Status.KEPT, 0L, java.time.LocalDate.of(2026, 10, 15), by = "your choice", note = "x").first
        val e = book.entries.single()
        assertTrue(e.applied)
        assertEquals(AutoChange.SWITCHED_OFF, AutoChange.undo(book, e.id)!!.inverse.to)
        // Never counted as Autopilot's own change ("undo the last change" leaves it alone).
        assertEquals(null, AutoChange.lastUndoable(book))
    }
}
