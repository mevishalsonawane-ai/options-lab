package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThinkingTest {
    private fun at(h: Int, m: Int, day: Int = 5) = LocalDateTime.of(2026, 10, day, h, m)

    private val cond = SelfCalibration.Conditions(at(11, 5), Market.NIFTY, true, "pattern: hammer", Regime.Kind.DOWN, 0.5)
    private val badSlice = SelfCalibration.Slice(listOf(SelfCalibration.Tag(SelfCalibration.Dim.TREND, "AGAINST", "against the trend")), 9, 2, -38.0)

    private fun satOut() = Thinking.paper(at(11, 5), cond, Thinking.Paper.ASKED, SelfCalibration.Judgment(SelfCalibration.Action.SIT_OUT, badSlice),
        stars = 4, bar = 3, switchOn = true, goesLive = false, badHour = "my trades entered between 11:00 and 12:00 have lost: 9 trades, -Rs 4,210.50")

    @Test fun cleanCutsRupeesAndSecrets() {
        assertEquals("my trades have lost: 9 trades", Thinking.clean("my trades have lost: 9 trades, -Rs 4,210.50"))
        assertEquals("lost (in all) today", Thinking.clean("lost ₹ 1,200 (in all) today"))
        assertFalse(Thinking.clean("my pin is 4321 and stuff").contains("4321"))
        assertTrue(Thinking.clean("x".repeat(500)).length <= Thinking.FACT_LEN)
        // Index levels and points stay.
        assertEquals("Nifty at 24,612, -38.0 points a unit", Thinking.clean("Nifty at 24,612, -38.0 points a unit"))
    }

    @Test fun satOutIdeaIsWalkedFactByFact() {
        val s = satOut()
        assertEquals(Thinking.Kind.PAPER_ASKED, s.kind)
        assertTrue(s.facts.none { it.text.contains("Rs") }, s.facts.toString())
        val q = Thinking.asked("Jarvis, why didn't you take that trade?")
        assertNotNull(q)
        assertEquals(false, q.did)
        val a = Thinking.answer(q, listOf(s), at(11, 30), locked = false)!!
        assertTrue(a.startsWith("At 11:05 I looked at my Nifty call idea"), a)
        assertTrue(a.contains("against the trend") && a.contains("9 ideas, 2 worked") && a.contains("confidence 4/5"), a)
        assertTrue(a.contains("The rule:") && a.contains("So I did not take it by myself"), a)
        assertTrue(a.contains("nothing added since"), a)
        assertFalse(a.contains("Rs"), a)
        // A trade taken is not what "why didn't you" is about.
        val took = Thinking.paper(at(12, 0), cond.copy(at = at(12, 0)), Thinking.Paper.TOOK, SelfCalibration.Judgment(SelfCalibration.Action.NORMAL, null),
            stars = 4, bar = 3, switchOn = true, goesLive = false)
        assertEquals(s, Thinking.find(q, listOf(s, took), at(12, 30)))
        assertEquals(took, Thinking.find(Thinking.asked("why did you take that trade")!!, listOf(s, took), at(12, 30)))
    }

    @Test fun neverInventsAReason() {
        val q = Thinking.asked("why didn't you take the banknifty trade")!!
        assertEquals(Market.BANKNIFTY, q.market)
        assertNull(Thinking.answer(q, listOf(satOut()), at(12, 0), locked = false))
        assertNull(Thinking.answer(Thinking.asked("why were you quiet at 11")!!, emptyList(), at(12, 0), locked = false))
        assertTrue(Thinking.nothing(Thinking.asked("why were you quiet at 11")!!).contains("between 11:00 and 12:00"))
        // A step written later than "now" (a clock change) is not used.
        assertNull(Thinking.answer(Thinking.asked("why didn't you take that trade")!!, listOf(satOut()), at(10, 0), locked = false))
    }

    @Test fun addIsBoundedAndSkipsRepeats() {
        var t = emptyList<Thinking.Step>()
        val s = satOut()
        t = Thinking.add(t, s)
        t = Thinking.add(t, s.copy(at = s.at.plusMinutes(5)))
        assertEquals(1, t.size)
        repeat(Thinking.MAX_A_DAY + 20) { i -> t = Thinking.add(t, s.copy(at = at(9, 15).plusMinutes(i.toLong()), subject = "idea $i")) }
        assertEquals(Thinking.MAX_A_DAY, t.size)
        // Days older than the window are dropped.
        val old = s.copy(at = at(11, 0, day = 1))
        t = Thinking.add(listOf(old), s)
        assertEquals(listOf(s), t)
        assertTrue(s.facts.size <= Thinking.MAX_FACTS)
    }

    @Test fun quietAtElevenWalksTheAirtimeDecision() {
        val t0 = at(10, 40)
        var st = Airtime.State()
        // Four lines spoken in the hour, then a fifth move at 11:02.
        for (i in 0 until Airtime.PER_HOUR) {
            st = Airtime.offer(st, Airtime.Alert(Airtime.Source.ORB, t0.plusMinutes(i * 4L), Market.values()[i % 4], i % 2 == 0, "line $i", "brief $i")).first
            st = Airtime.flush(st, t0.plusMinutes(i * 4L)).state
        }
        st = Airtime.offer(st, Airtime.Alert(Airtime.Source.GAP, at(11, 2), Market.NIFTY, false, "Nifty fell", "Nifty fell 0.4%")).first
        val out = Airtime.flush(st, at(11, 3))
        assertNull(out.say)
        assertEquals(1, out.decided.size)
        assertEquals(Airtime.How.OVER_LIMIT, out.decided[0].how)
        assertTrue(out.decided[0].because!!.contains("4 market lines"), out.decided[0].because)
        val steps = Thinking.alerts(at(11, 3), out.decided)
        assertEquals(Thinking.Kind.ALERT_OVER_LIMIT, steps.single().kind)
        val q = Thinking.asked("why were you quiet at 11?")!!
        assertEquals(Thinking.Area.ALERT, q.area)
        assertEquals(11, q.hour)
        val a = Thinking.answer(q, steps, at(12, 0), locked = false)!!
        assertTrue(a.contains("Nifty fell 0.4%") && a.contains("4 market lines aloud") && a.contains("kept it to the chat"), a)
        // 11:20 is near a decision at 11:03? no: more than ten minutes away.
        assertNull(Thinking.answer(Thinking.asked("why were you quiet at 11:20")!!, steps, at(12, 0), locked = false))
        assertNotNull(Thinking.answer(Thinking.asked("why were you quiet at 11:05")!!, steps, at(12, 0), locked = false))
    }

    @Test fun sameMoveAndMergedAndLearned() {
        var st = Airtime.State()
        st = Airtime.offer(st, Airtime.Alert(Airtime.Source.SHARPMOVE, at(11, 0), Market.NIFTY, true, "Nifty jumped", "Nifty jumped")).first
        st = Airtime.offer(st, Airtime.Alert(Airtime.Source.ORB, at(11, 0), Market.NIFTY, true, "Nifty broke its range", "Nifty broke its range")).first
        val o1 = Airtime.flush(st, at(11, 0))
        assertEquals(Thinking.Kind.ALERT_MERGED, Thinking.alerts(at(11, 0), o1.decided).single().kind)
        st = Airtime.offer(o1.state, Airtime.Alert(Airtime.Source.MOMENTS, at(11, 4), Market.NIFTY, true, "Nifty at its high", "Nifty at yesterday's high")).first
        st = Airtime.offer(st, Airtime.Alert(Airtime.Source.VIX, at(11, 4), Market.VIX, true, "VIX up", "VIX up 6%")).first
        val o2 = Airtime.flush(st, at(11, 4)) { it.source != Airtime.Source.VIX }
        val steps = Thinking.alerts(at(11, 4), o2.decided, mapOf(Airtime.Source.VIX to "fear (VIX) spikes (you followed up 0 of my last 6)"))
        val same = steps.first { it.kind == Thinking.Kind.ALERT_SAME_MOVE }
        assertTrue(same.facts.single().text.contains("told you about the same move aloud at 11:00"), same.facts.toString())
        val learned = steps.first { it.kind == Thinking.Kind.ALERT_LEARNED }
        assertTrue(learned.facts.single().text.contains("you followed up 0 of my last 6"), learned.facts.toString())
    }

    @Test fun checkMeIsExplainedFromTheRecordThen() {
        val tag = SelfDoubt.Tag(SelfDoubt.Dim.TOPIC, "topic:LEVELS", "answers on the levels")
        val c = SelfDoubt.Caution(SelfDoubt.Level.CHECK, SelfDoubt.Record(tag, 4, 12), "the levels on Nifty")
        val s = Thinking.caution(at(10, 15), c)!!
        assertNull(Thinking.caution(at(10, 15), SelfDoubt.NONE))
        val q = Thinking.asked("what made you say check me?")!!
        assertEquals(Thinking.Area.CAUTION, q.area)
        val a = Thinking.answer(q, listOf(s, satOut()), at(11, 30), locked = true)!!
        assertTrue(a.contains("I answered your question on the levels on Nifty") && a.contains("you marked 4 of my 12 answers on the levels wrong"), a)
        assertTrue(Thinking.asked("why did you ask me to check you") != null)
        // An account question's caution is not walked on a locked phone.
        val acc = Thinking.caution(at(10, 20), c.copy(record = SelfDoubt.Record(tag.copy(key = "topic:ACCOUNT", phrase = "answers on your account"), 4, 10), reading = "your account"))!!
        assertTrue(Thinking.answer(q, listOf(acc), at(11, 0), locked = true)!!.contains("unlock the phone"))
    }

    @Test fun agendaPrivateOnALockedPhone() {
        val goal = Agenda.Item("goal", Agenda.Kind.GOAL, 10 * 60, "your goals")
        val done = Thinking.agenda(at(10, 2), goal, done = true)
        assertTrue(done.private)
        val q = Thinking.asked("why did you skip that check")!!
        assertEquals(Thinking.Area.AGENDA, q.area)
        val event = Agenda.Item("ev", Agenda.Kind.EVENT, 9 * 60 + 15, "RBI policy", words = listOf("RBI policy"))
        val missed = Thinking.agenda(at(10, 30), event, done = false)
        val a = Thinking.answer(q, listOf(done, missed), at(11, 0), locked = true)!!
        assertTrue(a.contains("RBI policy") && a.contains("75 minutes past its time") && a.contains("So I skipped it"), a)
        val g = Thinking.answer(Thinking.asked("why did you do the goal check")!!, listOf(done), at(11, 0), locked = true)!!
        assertTrue(g.contains("unlock the phone") && !g.contains("What I had"), g)
        assertTrue(Thinking.answer(Thinking.asked("why did you do the goal check")!!, listOf(done), at(11, 0), locked = false)!!.contains("checked your goals"))
    }

    @Test fun soloShadowAndThin() {
        val c = SoloCalibration.conditions(at(13, 0).toLocalDate(), 13 * 60 - 9 * 60 - 15, Market.BANKNIFTY, false)
        val d = SoloCalibration.Decision(false, SelfCalibration.Action.SIT_OUT, badSlice)
        val s = Thinking.solo(at(13, 0), c, d)
        assertEquals(Thinking.Kind.SOLO_SHADOWED, s.kind)
        val q = Thinking.asked("why didn't solo trade at 1 pm")!!
        assertEquals(Thinking.Area.SOLO, q.area)
        assertEquals(13, q.hour)
        val a = Thinking.answer(q, listOf(s), at(14, 0), locked = false)!!
        assertTrue(a.contains("Solo's BankNifty put idea") && a.contains("followed on paper as if taken"), a)
        val thin = Thinking.soloThin(at(13, 10), Market.NIFTY, true, StrikeLiquidity.problem(10.0, 14.0, 0, 75)!!)
        assertTrue(thin.facts.single().text.contains("thin"))
    }

    @Test fun genericAndDayQuestions() {
        val s = satOut()
        val q = Thinking.asked("why did you do that?")!!
        assertTrue(q.latest)
        assertNotNull(Thinking.answer(q, listOf(s), at(11, 10), locked = false))
        // Something he did after it (in the activity log) is what "that" is about: not this.
        assertNull(Thinking.answer(q, listOf(s), at(11, 30), locked = false, newerElsewhere = at(11, 20)))
        assertNotNull(Thinking.asked("explain your thinking"))
        val day = Thinking.asked("what did you decide today")!!
        assertTrue(day.day)
        assertTrue(Thinking.answer(day, listOf(s), at(12, 0), locked = false)!!.startsWith("Today, Boss - my ideas: 0 taken, 1 not"))
        assertTrue(Thinking.answer(Thinking.asked("what did you decide yesterday")!!, listOf(s), at(12, 0, day = 6), locked = false)!!.startsWith("Yesterday"))
    }

    @Test fun asksOnlyItsOwnQuestions() {
        listOf("why is the market down", "why so quiet", "why were you so quiet today", "make the case", "talk me through it",
            "why did you park ORB 5", "buy 1 lot nifty", "what is the trend", "which alerts do you hold back").forEach {
            assertNull(Thinking.asked(it), it)
        }
        // Its questions are not the others'.
        listOf("why didn't you take that trade", "why were you quiet at 11", "what made you say check me", "walk me through your thinking",
            "why did you skip that check", "why only one lot").forEach {
            assertNotNull(Thinking.asked(it), it)
            assertFalse(TradeCase.asked(it), it)
            assertFalse(Airtime.asked(it), it)
            assertNull(AlertSense.asked(it), it)
            // Reached in the app only as a question: never read as an order or a command.
            val p = Ask.parse(it)
            assertNull(p.order, it)
            assertNull(p.command, it)
        }
    }
}
