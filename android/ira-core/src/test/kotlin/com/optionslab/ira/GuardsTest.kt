package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuardsTest {
    private val day = LocalDate.of(2026, 10, 5)
    private fun at(h: Int, m: Int) = day.atTime(h, m)

    @Test fun theGuardSetsAStopAloneOnlyOnABoughtOptionWithItsSwitchOn() {
        val long = Rescue.Open("NIFTY24000CE", true, 75, 100.0, 98.0)
        val stop = Rescue.stopFor(long)
        assertTrue(Rescue.setAlone(true, long, stop))
        assertFalse(Rescue.setAlone(false, long, stop))                  // switched off: only offered
        val short = Rescue.Open("NIFTY24000CE", true, -75, 100.0, 98.0)
        assertFalse(Rescue.setAlone(true, short, 115.0))                 // never on a short
        assertFalse(Rescue.setAlone(true, long, null))                   // already under the stop: told, not set
        assertTrue(Rescue.saySet(long, 85.0, "Protected.").startsWith("NIFTY24000CE (Zerodha, 75) had no stop, so I set one at 85.00"))
    }

    @Test fun coolOffAfterTwoLosses() {
        val two = listOf(at(10, 0) to -500.0, at(10, 40) to -300.0)
        assertEquals(at(11, 10), CoolOff.until(two, at(10, 50)))
        assertNull(CoolOff.until(two, at(11, 15)))
        assertNull(CoolOff.until(listOf(at(10, 0) to -500.0, at(10, 40) to 200.0), at(10, 50)))
        assertNull(CoolOff.until(listOf(at(10, 40) to -300.0), at(10, 50)))
        assertNull(CoolOff.until(listOf(at(10, 0).minusDays(1) to -1.0, at(10, 40) to -3.0), at(10, 50)))
        assertTrue(CoolOff.say(at(11, 10)).contains("until 11:10"))
    }

    @Test fun rescueStop() {
        assertEquals(85.0, Rescue.stopFor(Rescue.Open("X", false, 75, 100.0, 98.0)))
        assertNull(Rescue.stopFor(Rescue.Open("X", false, 75, 100.0, 80.0)))
        assertNull(Rescue.stopFor(Rescue.Open("X", false, -75, 100.0, 98.0)))
        assertTrue(Rescue.say(Rescue.Open("NIFTY24000CE", false, 75, 100.0, 98.0), 85.0).contains("Shall I set one at 85.00"))
    }

    @Test fun expiryPreview() {
        assertTrue(ExpiryPreview.lines(emptyList(), true).isEmpty())
        assertTrue(ExpiryPreview.lines(listOf("paper NIFTY 24000 CE"), true).single().startsWith("At 15:05 the expiry square-off will close 1 position"))
        assertTrue(ExpiryPreview.lines(listOf("a", "b"), false).single().contains("square-off is OFF"))
    }

    @Test fun confidence() {
        val good = Confidence.score(true, 0.65, Regime.Kind.UP, 0.2, TradeCheck.Level.GO)
        assertEquals(5, good.stars)
        val bad = Confidence.score(true, 0.45, Regime.Kind.DOWN, 0.8, TradeCheck.Level.CAREFUL)
        assertEquals(1, bad.stars)
        assertTrue(bad.text().startsWith("Confidence 1/5 (the pattern worked only 45% of the time"), bad.text())
        assertEquals(3, Confidence.score(false, null, null, null, null).stars)
    }

    @Test fun whatIfTimes() {
        assertEquals(10 * 60 + 30, WhatIf.minute("what if I had taken the 10:30 suggestion?"))
        assertEquals(14 * 60, WhatIf.minute("what if I took the 2 pm trade"))
        assertNull(WhatIf.minute("what if I had taken it"))
        assertTrue(WhatIf.asked("What if I had taken the 10:30 suggestion?"))
        assertEquals(setOf(Section.WHATIF), AppAnswers.sections("What if I had taken the 10:30 suggestion?"))
    }

    @Test fun quietHours() {
        assertTrue(Quiet.now(LocalTime.of(23, 0)))
        assertTrue(Quiet.now(LocalTime.of(6, 59)))
        assertFalse(Quiet.now(LocalTime.of(7, 0)))
        assertFalse(Quiet.now(LocalTime.of(12, 0)))
        assertTrue(Quiet.now(LocalTime.of(13, 0), 12 * 60, 14 * 60))
    }

    @Test fun moveAlarms() {
        val c = Commands.parse("Tell me if BankNifty falls 1% from here")!!
        assertEquals(Command.Kind.ALARM_ADD, c.kind); assertEquals(Market.BANKNIFTY, c.market); assertEquals(false, c.above); assertEquals(1.0, c.pct)
        val u = Commands.parse("alert me if nifty rises 0.5 percent")!!
        assertEquals(true, u.above); assertEquals(0.5, u.pct)
        assertEquals(51480.0, MoveAlarm.level(52000.0, MoveAlarm.Move(1.0, false)))
        // The level form still works.
        assertEquals(25000.0, Commands.parse("alert me when nifty goes above 25000")?.level)
    }

    @Test fun feedHealth() {
        assertTrue(FeedHealth.stale(at(10, 0), at(10, 3), true))
        assertFalse(FeedHealth.stale(at(10, 2), at(10, 3), true))
        assertFalse(FeedHealth.stale(at(10, 0), at(10, 3), false))
        assertFalse(FeedHealth.stale(null, at(9, 16), true))
        assertTrue(FeedHealth.say(at(10, 0)).contains("since 10:00"))
    }

    @Test fun settingsHistory() {
        val ch = listOf(SettingsHistory.Change(at(10, 0), SettingsTalk.Key.MAX_LOTS, 2.0, 5.0, "Jarvis"),
            SettingsHistory.Change(at(10, 0).minusDays(20), SettingsTalk.Key.MAX_LOTS, 1.0, 2.0, "Settings screen"))
        val l = SettingsHistory.lines(ch, day)
        assertEquals("1 change in the last 7 days:", l.first())
        assertEquals("2026-10-05 10:00: max lots per instrument 2 to 5 (Jarvis), more risk.", l[1])
        assertEquals(setOf(Section.CHANGES), AppAnswers.sections("What did I change this week?"))
    }

    @Test fun reportCard() {
        val s = listOf(JarvisTrades.Suggestion(at(10, 0), Market.NIFTY, true, 24000.0, "news", "approved", 12.0),
            JarvisTrades.Suggestion(at(11, 0), Market.NIFTY, false, 24000.0, "pattern", "rejected", -5.0))
        val l = ReportCard.lines(1500.0, 4, s, listOf(ReportCard.ArmWeek("ORB 5", 2000.0, 3), ReportCard.ArmWeek("Liquidity", -500.0, 1)), "Exits too early.")
        assertEquals("This week: +Rs 1,500.00 over 4 trades.", l[0])
        assertEquals("My suggestions: 2; you took 1, +12.0 points, skipped 1, -5.0 points.", l[1])
        assertEquals("Best arm: ORB 5 +Rs 2,000.00.", l[2]); assertEquals("Worst arm: Liquidity -Rs 500.00.", l[3])
        assertEquals("One habit to fix: Exits too early.", l[4])
        // Jarvis's own paper trades are told apart, never counted as Boss's answers.
        val mine = s + JarvisTrades.Suggestion(at(12, 0), Market.NIFTY, true, 24000.0, "pattern", JarvisTrades.SELF, 7.0)
        val l2 = ReportCard.lines(null, 0, mine, emptyList(), null)
        assertEquals("My suggestions: 2; you took 1, +12.0 points, skipped 1, -5.0 points.", l2[1])
        assertEquals("I took 1 on paper by myself, +7.0 points.", l2[2])
        val card = JarvisTrades.scorecard(day, mine)
        assertTrue(card.first().endsWith("your answer was the better choice on 2 of 2."), card.first())
        assertTrue(card.last().contains(": taken by me on paper, made +7.0 points"), card.last())
    }

    @Test fun undoQuietAndChart() {
        assertEquals(Command.Kind.UNDO, Commands.parse("Jarvis, undo")?.kind)
        assertEquals(Command.Kind.UNDO, Commands.parse("undo the last change")?.kind)
        assertEquals(Command.Kind.QUIET_ON, Commands.parse("turn on quiet hours")?.kind)
        assertEquals(Command.Kind.QUIET_OFF, Commands.parse("quiet hours off")?.kind)
        val q = Ask.parse("Describe the BankNifty chart")
        assertEquals(listOf(Market.BANKNIFTY), q.markets)
        assertTrue(Topic.LEVELS in q.topics && Topic.PATTERNS in q.topics)
    }
}
