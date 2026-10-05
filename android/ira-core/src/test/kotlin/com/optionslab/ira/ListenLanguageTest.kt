package com.optionslab.ira

import com.optionslab.ira.ListenLanguage.State
import com.optionslab.ira.ListenLanguage.Why
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListenLanguageTest {
    private val L = ListenLanguage
    private val day = "2026-10-05"

    @Test fun startsInTheEnglishThatLastGaveWords() {
        val s = L.gaveWords(State(), "en-IN", "2026-10-01")
        assertEquals("en-IN", L.start(s, day))
        assertEquals("en-US", L.start(State(), day))
        // Too long ago: afresh.
        assertEquals("en-US", L.start(s, "2026-10-20"))
    }

    @Test fun boss5OctTheWorkingEnglishIsNeverLeftForAClearRoom() {
        // en-US gave words yesterday; this morning, just after listening started, a loud room four turns running.
        val s = L.gaveWords(State(), "en-US", "2026-10-04")
        val d = L.clearNoWords(s, "en-US", day, "08:53", 4)
        assertNull(d.to)
        assertTrue(d.held)
        assertEquals(1, d.state.held)
        assertTrue(L.spoken(d.state, "en-US", day).contains("held off switching 1 time today"))
    }

    @Test fun needsFourClearTurnsInARow() {
        assertNull(L.clearNoWords(State(), "en-US", day, "09:00", 3).to)
        assertEquals("en-IN", L.clearNoWords(State(), "en-US", day, "09:00", 4).to)
    }

    @Test fun atMostOneSwitchByItselfADay() {
        val first = L.clearNoWords(State(), "en-US", day, "08:53", 4)
        assertEquals("en-IN", first.to)
        val second = L.clearNoWords(first.state, "en-IN", day, "10:00", 4)
        assertNull(second.to)
        assertTrue(L.spoken(second.state, "en-IN", day).contains("I won't switch by myself again today."))
        // The next day it may once more.
        assertEquals("en-US", L.clearNoWords(second.state, "en-IN", "2026-10-06", "09:00", 4).to)
    }

    @Test fun aRefusedEnglishIsNotTriedAgainTheSameDay() {
        val s = L.forced(State(), "en-IN", "en-US", day, "08:54", Why.REFUSED)
        assertEquals("en-IN", s.refused)
        assertEquals(1, s.forced)
        assertNull(L.clearNoWords(s, "en-US", day, "09:30", 4).to)
        // A switch then a forced way back: no further flips that day.
        val a = L.clearNoWords(State(), "en-US", day, "08:53", 4)
        val b = L.forced(a.state, "en-IN", "en-US", day, "08:53", Why.MISSING)
        assertNull(L.clearNoWords(b, "en-US", day, "11:00", 4).to)
    }

    @Test fun pickPrefersTheWorkingEnglishWhenThePhoneHasIt() {
        val s = L.gaveWords(State(), "en-IN", day)
        assertEquals("en-IN", L.pick(listOf("en_US", "en-in", "hi-IN"), s, day, google = false))
        assertEquals("en-US", L.pick(listOf("en-us", "hi-in"), s, day, google = false))
        assertEquals("en-US", L.pick(listOf("en-us", "en-in"), State(), day, google = false))
        assertEquals("en-GB", L.pick(listOf("hi-in", "en-gb"), State(), day, google = false))
        assertNull(L.pick(listOf("hi-in"), State(), day, google = false))
        assertEquals("en-IN", L.pick(emptyList(), s, day, google = true))
        assertNull(L.pick(emptyList(), State(), day, google = true))
    }

    @Test fun gaveWordsSparesTheWriteWhenKnown() {
        val s = L.gaveWords(State(), "en-US", day)
        assertTrue(s === L.gaveWords(s, "en-US", day))
    }

    @Test fun savesAndLoadsCodesAndCountsOnly() {
        val a = L.clearNoWords(L.gaveWords(State(), "en-US", "2026-09-20"), "en-US", day, "08:53", 4).state
        val b = L.forced(a, "en-IN", "en-US", day, "08:54", Why.MISSING)
        val raw = L.save(b)
        assertEquals(b, L.load(raw))
        assertEquals(State(), L.load(null))
        assertEquals(State(), L.load("garbage"))
        assertEquals(State(), L.load("1|en-US|2026-10-05|2026-10-05|-1|0|0|-|-|-|-|-|-"))
        // A damaged last switch is dropped, the rest kept.
        assertEquals("en-US", L.load("1|en-US|2026-10-05|2026-10-05|0|0|0|-|x|-|-|-|-").worked)
        assertFalse(raw.contains(' '))
    }

    @Test fun explainsTheSwitch() {
        val d = L.clearNoWords(State(), "en-US", day, "08:53", 4)
        val sw = d.state.last!!
        assertEquals("Listening switched to English (India): no words were found in clear speech 4 turns in a row in English (US). " +
            "I switch by myself at most once a day, and never away from an English that has given words.", L.line(sw))
        val said = L.spoken(d.state, "en-IN", day)
        assertTrue(said.startsWith("I'm listening in English (India); no English has given words lately"), said)
        assertTrue(said.contains("At 08:53 I switched from English (US) to English (India), because no words were found in clear speech 4 turns in a row."), said)
        assertTrue(L.say(d.state, "en-IN", day).contains("last 2026-10-05 08:53 en-US>en-IN CLEAR_NO_WORDS"))
        // Yesterday's switch is not today's.
        assertFalse(L.spoken(d.state, "en-IN", "2026-10-06").contains("08:53"))
    }

    // --- Round 20: what is said is what is used (Boss, 5 Oct: activity "en-IN", diagnostics "language: en-US"). ---

    @Test fun boss5OctTodaysSwitchIsKeptAcrossARestart() {
        val d = L.clearNoWords(State(), "en-US", day, "08:53", 4)
        assertEquals("en-IN", d.to)
        // Listening restarts (the service, the app): it goes on in English (India), not afresh in English (US).
        val restored = L.load(L.save(d.state))
        assertEquals("en-IN", L.start(restored, day))
        // The phone has it: the start-up pick keeps it (it used to put English (US) back without a word).
        assertEquals("en-IN", L.pick(listOf("en-US", "en-IN"), restored, day, google = false))
        // The next day the switch is yesterday's: afresh.
        assertEquals("en-US", L.start(restored, "2026-10-06"))
    }

    @Test fun aRestartInAMissingEnglishIsMovedAndThatMoveIsRecorded() {
        val d = L.clearNoWords(State(), "en-US", day, "08:53", 4)
        val pick = L.pick(listOf("en-US", "hi-IN"), d.state, day, google = false)
        assertEquals("en-US", pick)
        val s = L.forced(d.state, L.start(d.state, day), pick!!, day, "09:10", Why.MISSING)
        assertEquals("en-US", L.start(s, day))
        assertEquals("Listening switched to English (US): English (India) isn't on this phone for on-device listening.", L.line(s.last!!))
        assertTrue(L.say(s, "en-US", day, listOf("en-US", "hi-IN")).contains("last 2026-10-05 09:10 en-IN>en-US MISSING"))
    }

    @Test fun neverSwitchesByItselfToAnEnglishThePhoneLacks() {
        // A Pixel with English (US) and Hindi on-device: four clear turns with no words keep English (US), said plainly.
        val d = L.clearNoWords(State(), "en-US", day, "08:53", 4, installed = listOf("en-US", "hi-IN"))
        assertNull(d.to)
        assertTrue(d.held)
        assertTrue(d.missing)
        assertEquals(0, d.state.soft)
        assertEquals(1, d.state.held)
        assertNull(d.state.last)
        assertEquals("Kept listening in English (US): English (India) isn't on this phone for on-device listening, so switching to it " +
            "would not change what I hear.", L.heldLine("en-US"))
        val said = L.spoken(d.state, "en-US", day, listOf("en-US", "hi-IN"), google = false)
        assertTrue(said.contains("English (India) isn't on this phone for on-device listening, so I stay in English (US)"), said)
        assertFalse(said.contains("Google"), said)
        // The phone cannot say (older Android, or an empty list): as before.
        assertEquals("en-IN", L.clearNoWords(State(), "en-US", day, "08:53", 4, installed = null).to)
        assertEquals("en-IN", L.clearNoWords(State(), "en-US", day, "08:53", 4, installed = emptyList()).to)
        // The phone has it: switched.
        assertEquals("en-IN", L.clearNoWords(State(), "en-US", day, "08:53", 4, installed = listOf("en_us", "en_in")).to)
        // With Google's speech service already on (Boss's own choice in Settings), both are there.
        assertEquals("en-IN", L.clearNoWords(State(), "en-US", day, "08:53", 4, installed = listOf("en-US"), google = true).to)
    }

    @Test fun canTryAndMissing() {
        assertTrue(L.canTry("en-IN", null, google = false))
        assertTrue(L.canTry("en-IN", listOf("en-in"), google = false))
        assertFalse(L.canTry("en-IN", listOf("en-US"), google = false))
        assertTrue(L.canTry("en-IN", listOf("en-US"), google = true))
        assertNull(L.missing("en-US", listOf("en-US", "en-IN"), google = false))
        assertNull(L.missing("en-US", null, google = false))
        assertNull(L.missing("en-US", listOf("en-US"), google = true))
        assertNull(L.missing(null, listOf("en-US"), google = false))
        assertTrue(L.missing("en-IN", listOf("en-IN"), google = false)!!.startsWith("English (US) isn't on this phone"))
        assertTrue(L.say(State(), "en-US", day, listOf("en_US", "hi_IN")).contains("on-device has en-US,hi-IN"))
        assertTrue(L.say(State(), "en-US", day).contains("on-device has -"))
    }

    @Test fun refusedAndMissingLinesNameWhatHappened() {
        val r = L.forced(State(), "en-IN", "en-US", day, "08:54", Why.REFUSED)
        assertEquals("Listening switched to English (US): the phone's speech service refused English (India).", L.line(r.last!!))
    }
}
