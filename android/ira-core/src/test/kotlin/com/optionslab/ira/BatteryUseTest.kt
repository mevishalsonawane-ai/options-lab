package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryUseTest {
    private val idle = ListenSaver.Now(on = true, screenOn = false, marketOpen = false, talkHour = false, night = false,
        emptyInRow = EmptyTurns.SLOW_AFTER, expected = false, batteryLow = false)

    @Test fun offIsExactlyAsBefore() {
        for (base in listOf(EmptyTurns.QUICK_MS, EmptyTurns.CALM_MS, EmptyTurns.SLOW_MS))
            assertEquals(base, ListenSaver.gap(base, idle.copy(on = false)))
        assertFalse(ListenSaver.resting(idle.copy(on = false)))
    }

    @Test fun restsOnlyWhenEverythingIsQuiet() {
        assertEquals(ListenSaver.REST_MS, ListenSaver.gap(EmptyTurns.SLOW_MS, idle))
        assertEquals(ListenSaver.NIGHT_MS, ListenSaver.gap(EmptyTurns.SLOW_MS, idle.copy(night = true)))
        assertEquals(ListenSaver.REST_MS * 2, ListenSaver.gap(EmptyTurns.SLOW_MS, idle.copy(batteryLow = true)))
        assertEquals(ListenSaver.MAX_MS, ListenSaver.gap(EmptyTurns.SLOW_MS, idle.copy(night = true, batteryLow = true)))
        // Any one of these and listening is as before.
        for (n in listOf(idle.copy(screenOn = true), idle.copy(marketOpen = true), idle.copy(talkHour = true), idle.copy(expected = true),
            idle.copy(emptyInRow = EmptyTurns.SLOW_AFTER - 1))) {
            assertFalse(ListenSaver.resting(n), "$n")
            assertEquals(EmptyTurns.SLOW_MS, ListenSaver.gap(EmptyTurns.SLOW_MS, n))
        }
    }

    private val snap = BatteryUse.Snapshot(listening = true, listenSaver = false, resting = false, watch = true, watchStepSec = 60,
        stream = "LIVE", streamTokens = 5, modelLoaded = false, marketOpen = true, batteryPercent = 60, charging = false)

    @Test fun theLineAndTheAnswer() {
        assertEquals("Battery: listening on (battery saver for listening off) · order watch every 60 s · live stream LIVE (5 instruments) · " +
            "AI model not loaded · phone 60%, not charging", BatteryUse.line(snap))
        val a = BatteryUse.answer(snap)
        assertTrue(a.startsWith("Here's what runs in the background now, biggest first, Boss: Listening"), a)
        assertTrue("Battery saver for listening is off" in a && "never slow it" in a && "live price stream (5 instruments)" in a, a)
        assertTrue(a.indexOf("Listening") < a.indexOf("live price stream") && a.indexOf("live price stream") < a.indexOf("order watch"), a)
        val low = BatteryUse.answer(snap.copy(listenSaver = true, resting = true, batteryPercent = 15))
        assertTrue("resting between turns now" in low && "Battery at 15%" in low, low)
        val none = BatteryUse.answer(snap.copy(listening = false, watch = false, stream = "OFF", marketOpen = false, batteryPercent = null))
        assertEquals("Boss, very little of mine runs in the background now: not listening, no live stream, no order watch (the market is shut).", none)
        val one = BatteryUse.answer(snap.copy(listening = false, stream = "OFF"))
        assertTrue(one.startsWith("One thing of mine runs in the background now, Boss: The order watch, every 60 seconds"), one)
        assertTrue(BatteryUse.line(snap.copy(listening = false, stream = "OFF", watch = false, charging = true)).contains("listening off · order watch not running · live stream OFF · "))
        assertTrue(BatteryUse.line(snap.copy(wordsQuiet = true)).contains("order watch every 60 s (Jarvis's words quiet: slow checks every 3 min, news every 10 min) · "))
        assertTrue(BatteryUse.line(snap.copy(wordsQuiet = false)).contains("order watch every 60 s (Jarvis's words every round) · "))
    }

    @Test fun listeningCost() {
        val c = snap.copy(listenMinutes = 440, turnsLastHour = 212)
        assertEquals("running 7 h 20 min, 212 recognizer turns in the last hour (about one every 16 s)", BatteryUse.listenCost(c))
        assertTrue(BatteryUse.line(c).startsWith("Battery: listening on (battery saver for listening off; running 7 h 20 min, 212 recognizer turns"), BatteryUse.line(c))
        val a = BatteryUse.answer(c)
        assertTrue("that is usually the biggest (running 7 h 20 min, 212 recognizer turns in the last hour (about one every 16 s)). Battery saver" in a, a)
        assertEquals("running 5 min", BatteryUse.listenCost(snap.copy(listenMinutes = 5)))
        assertEquals("1 recognizer turn in the last hour", BatteryUse.listenCost(snap.copy(turnsLastHour = 1)))
        assertEquals(null, BatteryUse.listenCost(c.copy(listening = false)))
        assertEquals(null, BatteryUse.listenCost(snap))
    }

    @Test fun saverHintOnceAndOnlyWords() {
        assertEquals(ListenSaver.HINT, ListenSaver.hint(listening = true, saverOn = false, told = false))
        assertEquals(null, ListenSaver.hint(listening = true, saverOn = false, told = true))
        assertEquals(null, ListenSaver.hint(listening = true, saverOn = true, told = false))
        assertEquals(null, ListenSaver.hint(listening = false, saverOn = false, told = false))
    }

    private val ASKED = listOf("why is the app using so much battery", "why is the app eating battery", "battery kyun kha raha hai",
        "battery kyu kha raha hai", "jarvis battery kyun kha raha hai", "app itni battery kyun kha raha hai", "why is iraalgo draining my battery",
        "what is eating my battery", "is jarvis draining the battery", "how much battery does the app use", "what is running in the background",
        "battery usage", "why does the phone keep draining battery", "kyun battery itni jaldi khatam ho rahi hai", "app kitni battery khata hai",
        "background mein kya chal raha hai", "Jarvis, why are you using so much battery?", "why is my battery draining so fast")
    private val NOT = listOf("how is nifty", "is iraalgo battery optimized", "set iraalgo battery to unrestricted", "battery saver on",
        "why did the stream drop", "what is the battery level", "close all positions")

    @Test fun asked() {
        for (s in ASKED) assertTrue(BatteryUse.asked(s), s)
        for (s in NOT) assertFalse(BatteryUse.asked(s), s)
    }
}

class WordsPaceTest {
    @Test fun onlyQuietWhenScreenOffNothingHeldNothingArmed() {
        assertTrue(WordsPace.quiet(screenOn = false, held = false, armed = false))
        assertFalse(WordsPace.quiet(screenOn = true, held = false, armed = false))
        assertFalse(WordsPace.quiet(screenOn = false, held = true, armed = false))
        assertFalse(WordsPace.quiet(screenOn = false, held = false, armed = true))
        assertFalse(WordsPace.quiet(screenOn = false, held = false, armed = null), "unknown counts as armed")
    }

    @Test fun notQuietIsEveryRoundAsBefore() {
        val now = 10_000_000L
        assertTrue(WordsPace.slowDue(false, now, now - 1_000))
        assertTrue(WordsPace.newsDue(false, now, now - 1_000))
    }

    @Test fun quietSlowsOnlyTheSlowGroupAndTheNews() {
        val now = 10_000_000L
        assertFalse(WordsPace.slowDue(true, now, now - 60_000))
        assertTrue(WordsPace.slowDue(true, now, now - WordsPace.QUIET_SLOW_MS))
        assertTrue(WordsPace.slowDue(true, now, 0L), "never run yet")
        assertTrue(WordsPace.slowDue(true, now, now + 5_000), "clock went back")
        assertFalse(WordsPace.newsDue(true, now, now - 5 * 60_000))
        assertTrue(WordsPace.newsDue(true, now, now - WordsPace.QUIET_NEWS_MS))
        assertTrue(WordsPace.newsDue(true, now, null))
    }
}

class NightNewsPaceTest {
    private val weekday: (java.time.LocalDate) -> Boolean = { it.dayOfWeek.value <= 5 }
    private fun at(d: Int, h: Int, m: Int = 0) = java.time.LocalDateTime.of(2026, 10, d, h, m)   // 2 Oct 2026 is a Friday

    @Test fun weekdayNightsAsBefore() {
        // Tuesday 6 Oct after the close: Wednesday's open is under 18 hours away.
        assertEquals(at(7, 9, 15), NightNewsPace.nextOpen(at(6, 16), weekday))
        assertTrue(NightNewsPace.due(at(6, 16), NightNewsPace.nextOpen(at(6, 16), weekday)))
        assertTrue(NightNewsPace.due(at(7, 3), NightNewsPace.nextOpen(at(7, 3), weekday)))
        assertEquals(at(7, 9, 15), NightNewsPace.nextOpen(at(7, 3), weekday), "before the open: today's")
    }

    @Test fun notAllWeekend() {
        val sat = at(3, 12)
        assertEquals(at(5, 9, 15), NightNewsPace.nextOpen(sat, weekday))
        assertFalse(NightNewsPace.due(sat, NightNewsPace.nextOpen(sat, weekday)))
        assertFalse(NightNewsPace.due(at(2, 20), NightNewsPace.nextOpen(at(2, 20), weekday)), "Friday night")
        assertTrue(NightNewsPace.due(at(4, 15, 15), NightNewsPace.nextOpen(at(4, 15, 15), weekday)), "Sunday from 15:15")
        assertFalse(NightNewsPace.due(at(4, 15, 14), NightNewsPace.nextOpen(at(4, 15, 14), weekday)))
        val holiday: (java.time.LocalDate) -> Boolean = { weekday(it) && it != java.time.LocalDate.of(2026, 10, 5) }
        assertFalse(NightNewsPace.due(at(4, 20), NightNewsPace.nextOpen(at(4, 20), holiday)), "Monday a holiday")
    }

    @Test fun noSessionFoundReadsAsBefore() {
        assertEquals(null, NightNewsPace.nextOpen(at(3, 12)) { false })
        assertTrue(NightNewsPace.due(at(3, 12), null))
    }

    private val lockSnap = BatteryUse.Snapshot(listening = true, listenSaver = false, resting = false, watch = true, watchStepSec = 60,
        stream = "LIVE", streamTokens = 5, modelLoaded = false, marketOpen = true, batteryPercent = 60, charging = false)

    @Test fun lockedPhoneHintsAtNoPosition() {
        val a = BatteryUse.answer(lockSnap.copy(watchStepSec = 15), locked = true)
        assertFalse("15 seconds" in a || "every 15" in a, a)
        assertFalse("instrument" in a, a)
        assertTrue("order watch" in a && "live price stream" in a && "Boss" in a, a)
        assertTrue("every 15 seconds" in BatteryUse.answer(lockSnap.copy(watchStepSec = 15)))
    }
}

class StudyPaceTest {
    private val weekday: (java.time.LocalDate) -> Boolean = { it.dayOfWeek.value <= 5 }
    private fun at(d: Int, h: Int, m: Int = 0) = java.time.LocalDateTime.of(2026, 10, d, h, m)   // 2 Oct 2026 is a Friday
    private fun pace(now: java.time.LocalDateTime, last: java.time.LocalDateTime?, card: Boolean = false, day: (java.time.LocalDate) -> Boolean = weekday) =
        StudyPace.everyHours(now, NightNewsPace.nextOpen(now, day), last, StudyPace.lastClose(now, day), card)

    @Test fun lastClose() {
        assertEquals(at(1, 15, 30), StudyPace.lastClose(at(2, 15, 29), weekday))
        assertEquals(at(2, 15, 30), StudyPace.lastClose(at(2, 15, 30), weekday))
        assertEquals(at(2, 15, 30), StudyPace.lastClose(at(4, 20), weekday), "Sunday: Friday's")
        assertEquals(null, StudyPace.lastClose(at(4, 20)) { false })
    }

    @Test fun weekdayStudiesAsBefore() {
        // Monday 5 Oct: after the close, and again twelve hours later.
        assertTrue(StudyPace.studyDue(at(5, 3, 45), StudyPace.lastClose(at(5, 15, 45), weekday)))
        assertTrue(StudyPace.studyDue(at(5, 15, 45), StudyPace.lastClose(at(6, 3, 45), weekday)))
        assertTrue(StudyPace.studyDue(null, at(5, 15, 30)))
        assertTrue(StudyPace.studyDue(at(5, 1), null), "no close found: as before")
        for (h in listOf(16, 20, 23)) assertEquals(1L, pace(at(5, h), at(5, 15, 45)), "weeknight $h")
        assertEquals(1L, pace(at(6, 4), at(6, 3, 45)), "next open within the window")
    }

    @Test fun weekendRestsAfterTheCardAndTheSecondStudy() {
        // Friday's close: studied 15:45 and 03:45 Saturday; nothing new until Monday.
        assertTrue(StudyPace.studyDue(at(2, 15, 45), StudyPace.lastClose(at(3, 3, 45), weekday)))
        assertFalse(StudyPace.studyDue(at(3, 3, 45), StudyPace.lastClose(at(3, 15, 45), weekday)), "Saturday afternoon: same candles")
        assertFalse(StudyPace.studyDue(at(3, 3, 45), StudyPace.lastClose(at(4, 3, 45), weekday)), "Sunday night")
        assertEquals(1L, pace(at(2, 20), at(2, 15, 45)), "Friday night: second study pending")
        assertEquals(1L, pace(at(3, 5), at(3, 3, 45)), "Saturday before the card")
        assertEquals(StudyPace.SLOW_HOURS, pace(at(3, 10), at(3, 3, 45), card = true))
        assertEquals(StudyPace.SLOW_HOURS, pace(at(4, 9, 14), at(3, 3, 45), card = true))
        assertEquals(1L, pace(at(4, 9, 15), at(3, 3, 45), card = true), "Sunday from 09:15: the night news window comes within six hours")
    }

    @Test fun unknownReadsAsBefore() {
        assertEquals(1L, pace(at(3, 12), at(3, 3, 45), card = true) { false })
    }
}
