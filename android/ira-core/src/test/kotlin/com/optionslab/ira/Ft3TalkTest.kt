package com.optionslab.ira

import com.optionslab.engine.orb.Ft3Rules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 21 Oct (Boss's choice): Jarvis on FT3 near-ATM morning - what is asked (and what is not: an action, another forward test), the
 * answer always labelled as not passing research and saying how it goes live, and Boss's own change logged as theirs.
 */
class Ft3TalkTest {
    @Test fun itsQuestionsAreReadAndOthersAreNot() {
        for (q in listOf("how is FT3 doing", "what did ft3 do today", "ft3 kaisa chal raha hai", "FT-3 today?", "how is forward test 3",
            "tell me about the near ATM morning", "near-ATM morning ticket", "ft 3 status"))
            assertTrue(Ft3Talk.asked(q), q)
        for (q in listOf("switch off ft3", "turn on FT3", "ft3 band karo", "close the ft3 ticket", "buy ft3", "how is ft1 doing",
            "how is the forward test", "how is the hero ticket doing", "forward check", "how is nifty", ""))
            assertFalse(Ft3Talk.asked(q), q)
    }

    /** A reader's answer: a Boolean, or a parsed request (null: not taken). */
    private fun claimed(r: Any?): Boolean = r != null && r != false

    /** The readers the hub asks before FT3's (CollisionTest's HUB_ORDER): none of them takes an FT3 question. */
    @Test fun noEarlierReaderTakesAnFt3Question() {
        val earlier = listOf<Pair<String, (String) -> Boolean>>(
            "Conditional" to { q: String -> claimed(Conditional.asked(q)) },
            "DayJournal" to { q: String -> claimed(DayJournal.asked(q)) },
            "SelfTalk" to { q: String -> claimed(SelfTalk.asked(q)) },
            "AlertSense" to { q: String -> claimed(AlertSense.asked(q)) },
            "Airtime" to { q: String -> claimed(Airtime.asked(q)) },
            "Hearing" to { q: String -> claimed(Hearing.asked(q)) },
            "PatternCalls" to { q: String -> claimed(PatternCalls.asked(q)) },
            "TrendReads" to { q: String -> claimed(TrendReads.asked(q)) },
            "OutsideApp" to { q: String -> claimed(OutsideApp.asked(q)) },
            "Clarity" to { q: String -> claimed(Clarity.asked(q)) },
            "WordFit" to { q: String -> claimed(WordFit.asked(q)) },
            "AskedAgain" to { q: String -> claimed(AskedAgain.asked(q)) },
            "FigureFirst" to { q: String -> claimed(FigureFirst.asked(q)) },
            "WrongThing" to { q: String -> claimed(WrongThing.asked(q)) },
            "ArmHabits" to { q: String -> claimed(ArmHabits.asked(q)) },
            "MorningSense" to { q: String -> claimed(MorningSense.asked(q)) },
            "HonestStars" to { q: String -> claimed(HonestStars.asked(q)) },
            "TalkHours" to { q: String -> claimed(TalkHours.asked(q)) },
            "MorningAsks" to { q: String -> claimed(MorningAsks.asked(q)) },
            "TurnDowns" to { q: String -> claimed(TurnDowns.asked(q)) },
            "TopicLength" to { q: String -> claimed(TopicLength.asked(q)) },
            "OutlookCheck" to { q: String -> claimed(OutlookCheck.asked(q)) },
            "UsualIndex" to { q: String -> claimed(UsualIndex.asked(q)) },
            "Nicknames" to { q: String -> claimed(Nicknames.asked(q)) },
            "LeadIndex" to { q: String -> claimed(LeadIndex.asked(q)) },
            "LeadPart" to { q: String -> claimed(LeadPart.asked(q)) },
            "NextAsk" to { q: String -> claimed(NextAsk.asked(q)) },
            "MoreAfter" to { q: String -> claimed(MoreAfter.asked(q)) },
            "SmallTrades" to { q: String -> claimed(SmallTrades.asked(q)) },
            "DayIndex" to { q: String -> claimed(DayIndex.asked(q)) },
            "CheckTimes" to { q: String -> claimed(CheckTimes.asked(q)) },
            "CondNeeds" to { q: String -> claimed(CondNeeds.asked(q)) },
            "NewsMoves" to { q: String -> claimed(NewsMoves.asked(q)) },
            "Learnings" to { q: String -> claimed(Learnings.asked(q)) },
            "PreMarket" to { q: String -> claimed(PreMarket.asked(q)) },
            "Headroom" to { q: String -> claimed(Headroom.asked(q)) },
            "ArmFit" to { q: String -> claimed(ArmFit.asked(q)) },
            "WeakLink" to { q: String -> claimed(WeakLink.asked(q)) },
            "ArmChange" to { q: String -> claimed(ArmChange.asked(q)) },
            "PnlGap" to { q: String -> claimed(PnlGap.asked(q)) },
            "ArmDay" to { q: String -> claimed(ArmDay.asked(q)) },
            "BookDecay" to { q: String -> claimed(BookDecay.asked(q)) },
            "WhereIWin" to { q: String -> claimed(WhereIWin.asked(q)) },
            "TradesADay" to { q: String -> claimed(TradesADay.asked(q)) },
            "AfterLoss" to { q: String -> claimed(AfterLoss.asked(q)) },
            "StopNoise" to { q: String -> claimed(StopNoise.asked(q)) },
            "DayScore" to { q: String -> claimed(DayScore.asked(q)) },
            "RequestBook" to { q: String -> claimed(RequestBook.asked(q)) },
            "NetLean" to { q: String -> claimed(NetLean.asked(q)) },
            "ExpiryEve" to { q: String -> claimed(ExpiryEve.asked(q)) },
            "BeforeTomorrow" to { q: String -> claimed(BeforeTomorrow.asked(q)) },
            "LiquidityWhyNot" to { q: String -> claimed(LiquidityWhyNot.asked(q)) },
            "NtfyTalk" to { q: String -> claimed(NtfyTalk.asked(q)) },
            "TrendTalk" to { q: String -> claimed(TrendTalk.asked(q)) },
            "PassedArmsTalk" to { q: String -> claimed(PassedArmsTalk.asked(q)) },
            "SoloDay" to { q: String -> claimed(SoloDay.asked(q)) })
        for (q in listOf("how is FT3 doing", "what did ft3 do today", "ft3 kaisa chal raha hai", "how is forward test 3",
            "tell me about the near ATM morning", "ft 3 status"))
            for ((n, f) in earlier) assertFalse(f(q), "$n takes \"$q\"")
    }

    private fun facts(armed: Boolean? = true, today: List<Ft3Talk.Ticket> = emptyList(), status: Map<String, String> = emptyMap(),
                      closed: Int = 0, net: Double = 0.0, won: Int = 0) = Ft3Talk.Facts(armed, today, status, closed, net, won)

    @Test fun theAnswerAlwaysSaysItDidNotPassResearchAndHowItGoesLive() {
        val a = Ft3Talk.answer(facts(status = mapOf("NIFTY" to "not its expiry day", "SENSEX" to "its expiry day: scans from 11:01")))
        assertTrue(a.startsWith("FT3 near-ATM morning: on, on paper (live only the standard way: Live with your PIN"), a)
        assertTrue(Ft3Rules.RECORD in a && Ft3Rules.JUDGED in a && Ft3Rules.MEASURED in a && Ft3Rules.SIGNAL_SOURCE in a, a)
        assertTrue("No ticket today (NIFTY not its expiry day; SENSEX its expiry day: scans from 11:01)." in a, a)
        assertTrue("no closed ticket yet" in a, a)
        assertTrue(Ft3Talk.answer(facts(armed = false)).startsWith("FT3 near-ATM morning: off."))
        assertTrue("could not be read" in Ft3Talk.answer(facts(armed = null)))
        assertTrue("No ticket today." in Ft3Talk.answer(facts()))
    }

    @Test fun theShadowAndLiveSizeIsSaidApartFromThePaperTicket() {
        for (w in listOf("at most your lot cap", "cap off", "smaller than the ₹5,000 paper ticket", "charges weigh more", "own record"))
            assertTrue(w in Ft3Talk.SIZE_NOTE, w)
        assertTrue(Ft3Talk.SIZE_NOTE.startsWith(" "))
    }

    @Test fun todaysTicketsWithTheirPnlAndTheRecord() {
        val a = Ft3Talk.answer(facts(closed = 12, won = 2, net = 41_250.0, today = listOf(
            Ft3Talk.Ticket("NIFTY", "NIFTY27OCT2622750CE", 650, 7.05, mark = 9.0),
            Ft3Talk.Ticket("SENSEX", "S1", 500, 8.0, exit = 0.5, why = Ft3Rules.EXIT_WHY, net = -3_820.0),
            Ft3Talk.Ticket("SENSEX", "S2", 500, 8.0, exit = 6.0, why = "operator_stop", net = -1_050.0),
            Ft3Talk.Ticket("SENSEX", "S3", 500, 8.0, exit = 6.0, why = "closed_elsewhere", net = -1_050.0),
            Ft3Talk.Ticket("SENSEX", "S4", 500, 8.0, exit = 6.0, net = -1_050.0),
            Ft3Talk.Ticket("SENSEX", "S5", 500, 8.0, exit = 6.0, why = "kill", net = -1_050.0),
            Ft3Talk.Ticket("FINNIFTY", "F1", 60, 9.0))))
        assertTrue("NIFTY27OCT2622750CE 650 @ 7.05, now 9.00 (+₹1,268 before charges)" in a, a)
        assertTrue("S1 bought @ 8.00, sold @ 0.50 at 15:05, −₹3,820 after charges" in a, a)
        assertTrue("on your stop for today" in a && "closed by the paper book (its square-off, the loss limit or you)" in a && "closed elsewhere (kill)" in a && "S4 bought @ 8.00, sold @ 6.00 sold" in a, a)
        // 22 Oct (review): the paper book's own close is said in words, never its code.
        assertFalse("closed_elsewhere" in a, a)
        assertTrue("F1 60 @ 9.00, held" in a, a)
        assertTrue("12 tickets, 2 won, +₹41,250 after charges" in a, a)
        assertTrue("1 ticket, 0 won, −₹50 after charges" in Ft3Talk.answer(facts(closed = 1, net = -50.0)))
    }

    @Test fun bossTurningItOnIsLoggedAsTheirOwnPaperChangeUndoneInOneTap() {
        val c = AutoChange.ft3On()
        assertEquals(AutoChange.Kind.PAPER_ARM_ON, c.kind)
        assertEquals(AutoChange.FT3_TARGET, c.target); assertEquals("paperarm:ft3", c.target)
        assertTrue(c.target != AutoChange.TICKET_TARGET)
        assertEquals(AutoChange.SWITCHED_OFF, c.from); assertEquals(AutoChange.ON, c.to)
        assertEquals(AutoChange.Scope.PAPER, c.scope)
        assertTrue(AutoChange.bossOwn(c.kind))
        assertFalse(AutoChange.never(c), "Boss's own paper change is never on the denylist")
        for (w in listOf("your choice (21 Oct)", "on paper", "did not pass research (R98e", "SPA p 0.96", "own record", "your PIN", "Shadow"))
            assertTrue(w in c.reason, w)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(c), "a paper arm on adds paper trades")
        val back = AutoChange.inverse(c, "undone by you")
        assertEquals(AutoChange.SWITCHED_OFF, back.to)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(back))
        assertFalse(AutoChange.undoNeedsPin(c))
        val book = AutoChange.record(AutoChange.Book(), c, AutoChange.Status.KEPT, 0L, java.time.LocalDate.of(2026, 10, 21), by = "your choice", note = "x").first
        val e = book.entries.single()
        assertTrue(e.applied)
        assertEquals(AutoChange.SWITCHED_OFF, AutoChange.undo(book, e.id)!!.inverse.to)
        assertEquals(null, AutoChange.lastUndoable(book), "never counted as Autopilot's own change")
    }
}
