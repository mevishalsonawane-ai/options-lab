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
