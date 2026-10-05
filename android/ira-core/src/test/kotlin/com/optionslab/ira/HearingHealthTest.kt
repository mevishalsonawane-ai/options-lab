package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HearingHealthTest {
    private fun day(date: String, heard: Int, noMatch: Int, loud: Int = 0, hour: Int = 10): List<Hearing.Day> {
        var d = emptyList<Hearing.Day>()
        repeat(heard) { d = Hearing.turn(d, date, hour, Hearing.Kind.HEARD, name = true) }
        repeat(noMatch) { i -> d = Hearing.turn(d, date, hour, Hearing.Kind.NO_MATCH, loud = i < loud) }
        return d
    }

    /** Four ordinary days (10% no words) and [today]. */
    private fun week(today: List<Hearing.Day>): List<Hearing.Day> =
        (1..4).flatMap { day("2026-10-0$it", 18, 2) } + today

    @Test fun counts() {
        var d = emptyList<Hearing.Day>()
        d = Hearing.turn(d, "2026-10-05", 9, Hearing.Kind.HEARD, name = true)
        d = Hearing.turn(d, "2026-10-05", 9, Hearing.Kind.HEARD, name = true, saved = true)
        d = Hearing.turn(d, "2026-10-05", 9, Hearing.Kind.NO_MATCH, loud = true)
        d = Hearing.turn(d, "2026-10-05", 10, Hearing.Kind.ERROR, code = 11)
        d = Hearing.turn(d, "2026-10-05", 10, Hearing.Kind.SILENT)
        d = Hearing.turn(d, "2026-10-05", 10, Hearing.Kind.LOST)
        d = Hearing.restart(d, "2026-10-05")
        d = Hearing.firstWords(d, "2026-10-05", 600)
        d = Hearing.firstWords(d, "2026-10-05", 60_000)            // a stall is not a reading time
        val t = d.single()
        assertEquals(2, t.heard); assertEquals(1, t.noMatch); assertEquals(1, t.noMatchLoud); assertEquals(1, t.silent)
        assertEquals(1, t.errors); assertEquals(1, t.restarts); assertEquals(1, t.lost)
        assertEquals(1, t.nameHeard); assertEquals(1, t.nameSaved); assertEquals(600L, t.firstAvg)
        assertEquals(mapOf(7 to 1, 11 to 1), t.codes); assertEquals(mapOf(9 to 1, 10 to 2), t.hours)
        // A new day starts its own count; only the last 8 days are kept.
        var many = d
        for (i in 6..20) many = Hearing.turn(many, "2026-10-%02d".format(i), 9, Hearing.Kind.HEARD)
        assertEquals(Hearing.KEEP, many.size)
        assertEquals("2026-10-20", many.last().date)
    }

    @Test fun keptAsNumbers() {
        var d = week(day("2026-10-05", 5, 3, loud = 2))
        d = Hearing.firstWords(Hearing.restart(d, "2026-10-05"), "2026-10-05", 800)
        val s = Hearing.save(d)
        assertEquals(d, Hearing.load(s))
        assertFalse(s.contains("jarvis", ignoreCase = true))
        assertEquals(emptyList(), Hearing.load(null))
        assertEquals(emptyList(), Hearing.load("garbage;2026-10-05|1|2"))
        // One damaged day is dropped, the rest kept.
        assertEquals(1, Hearing.load("x|1|1|1|1|1|1|1|1|1|1|1||;" + Hearing.save(day("2026-10-05", 3, 1))).size)
    }

    @Test fun noWordsDoubledIsAProblem() {
        // Usual 10%; today 8 of 20 (40%) with faint sound: move closer.
        val faint = week(day("2026-10-05", 12, 8, loud = 1))
        assertEquals(listOf(Hearing.Problem.NO_WORDS_FAINT), Hearing.problems(faint, "2026-10-05"))
        // The same with clear sound: the speech pack, and Google's speech offered as Boss's own switch.
        val clear = week(day("2026-10-05", 12, 8, loud = 6))
        assertEquals(listOf(Hearing.Problem.NO_WORDS_CLEAR), Hearing.problems(clear, "2026-10-05"))
        val n = Hearing.nudge(clear, "2026-10-05", google = false)
        assertNotNull(n)
        assertTrue(n.startsWith(Hearing.NUDGE))
        assertTrue(n.contains("8 of 20") && n.contains("40%") && n.contains("usually 10%"), n)
        assertTrue(n.contains("Use Google's speech service") && n.contains("only you can switch it"), n)
        // Google speech already on: not suggested again.
        assertFalse(Hearing.nudge(clear, "2026-10-05", google = true)!!.contains("Google's speech"))
    }

    @Test fun ordinaryDaysAreQuiet() {
        // Today like usual, or too few turns to judge: nothing said.
        assertNull(Hearing.nudge(week(day("2026-10-05", 18, 2)), "2026-10-05", false))
        assertNull(Hearing.nudge(week(day("2026-10-05", 3, 4)), "2026-10-05", false))
        assertNull(Hearing.nudge(emptyList(), "2026-10-05", false))
        // Barely worse than usual (15% against 10%) is not a problem.
        assertEquals(emptyList(), Hearing.problems(week(day("2026-10-05", 17, 3)), "2026-10-05"))
        // With no days to compare, only clearly bad counts.
        assertEquals(emptyList(), Hearing.problems(day("2026-10-05", 14, 6), "2026-10-05"))
        assertEquals(listOf(Hearing.Problem.NO_WORDS_FAINT), Hearing.problems(day("2026-10-05", 8, 8), "2026-10-05"))
    }

    @Test fun serviceLostSlowAndName() {
        var d = week(day("2026-10-05", 10, 1))
        repeat(4) { d = Hearing.turn(d, "2026-10-05", 11, Hearing.Kind.ERROR, code = 5) }
        repeat(3) { d = Hearing.restart(d, "2026-10-05") }
        repeat(3) { d = Hearing.turn(d, "2026-10-05", 11, Hearing.Kind.LOST) }
        assertEquals(listOf(Hearing.Problem.SERVICE, Hearing.Problem.LOST), Hearing.problems(d, "2026-10-05"))
        assertTrue(Hearing.fix(Hearing.Problem.SERVICE, false).contains("Speech Services by Google"))

        // First words slow against usual.
        var s = (1..3).flatMap { i -> day("2026-10-0$i", 10, 1).let { x -> (1..5).fold(x) { a, _ -> Hearing.firstWords(a, "2026-10-0$i", 500) } } }
        s = s + day("2026-10-05", 10, 1)
        repeat(5) { s = Hearing.firstWords(s, "2026-10-05", 1_800) }
        assertEquals(listOf(Hearing.Problem.SLOW_START), Hearing.problems(s, "2026-10-05"))

        // The name caught only from partial words half the time.
        var n = week(emptyList())
        repeat(5) { n = Hearing.turn(n, "2026-10-05", 9, Hearing.Kind.HEARD, name = true) }
        repeat(5) { n = Hearing.turn(n, "2026-10-05", 9, Hearing.Kind.HEARD, name = true, saved = true) }
        assertEquals(listOf(Hearing.Problem.NAME), Hearing.problems(n, "2026-10-05"))
    }

    @Test fun spokenAnswer() {
        var d = week(day("2026-10-05", 12, 8, loud = 1))
        repeat(3) { d = Hearing.turn(d, "2026-10-05", 10, Hearing.Kind.ERROR, code = 8) }
        val s = Hearing.spoken(d, "2026-10-05", google = false)
        assertTrue(s.startsWith("Today, Boss: words found in 12 of 20"), s)
        assertTrue(s.contains("usually 10%"), s)
        assertTrue(s.contains("Most common failure: the service busy (3)"), s)
        assertTrue(s.contains("Most trouble around 10:00"), s)
        assertTrue(s.contains("What would help: Your voice reaches me faint"), s)
        assertTrue(Hearing.spoken(week(day("2026-10-05", 18, 2)), "2026-10-05", false).endsWith("Nothing worse than usual."))
        assertTrue(Hearing.spoken(week(emptyList()), "2026-10-05", false).startsWith("I haven't heard a turn from you yet today, Boss. Usually"))
        assertTrue(Hearing.say(d, "2026-10-05").startsWith("Hearing today: heard 12"))
    }

    @Test fun asked() {
        for (q in listOf("how well are you hearing me?", "Jarvis, how well can you hear me today", "are you having trouble hearing?",
                "are you having any trouble hearing me", "how is your hearing", "how are your ears today", "hearing report",
                "mujhe theek se sun rahe ho kya")) assertTrue(Hearing.asked(q), q)
        for (q in listOf("can you hear me", "what did you hear", "how well is nifty doing", "are you having trouble placing orders"))
            assertFalse(Hearing.asked(q), q)
        // Never a command: it only asks.
        for (q in listOf("how well are you hearing me", "are you having trouble hearing")) assertNull(Commands.parse(q), q)
    }
}
