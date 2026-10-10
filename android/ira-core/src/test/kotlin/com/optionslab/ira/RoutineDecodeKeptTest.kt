package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Speed round 8: the routine log's lines are decoded once and kept ([Routine.decode]) - the app decodes the whole log
 * (up to [Routine.LOG_MAX] lines) twice per answer for the next-question offer and again when the question is noted.
 * Work is counted by the kept readings handed back (the very same objects: nothing read again), never by the clock.
 */
class RoutineDecodeKeptTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val keys = listOf("NIFTY|levels", "BANKNIFTY|levels", "ACCOUNT|pnl", "NIFTY|overview", "ACCOUNT|events", "SENSEX|trend")

    /** A full log as the app keeps it: [Routine.LOG_MAX] asks over the last weeks, in order, as written. */
    private fun lines(from: Int = 0): List<String> = (from until from + Routine.LOG_MAX).map { i ->
        Routine.encode(Routine.Seen(keys[i % keys.size], today.minusDays(40L - i / 8).atTime(9, 15).plusMinutes(i % 8 * 5L), i % 7 == 0))
    }

    @BeforeTest @AfterTest fun forget() = Routine.forgetDecoded()

    @Test fun aWholeLogReadAgainIsNotDecodedAgain() {
        val log = lines()
        val first = log.map { Routine.decode(it) }
        assertEquals(Routine.LOG_MAX, Routine.decodedKept)
        // Exactly what decoding gives (the log's own asks, in order).
        assertEquals(log, first.map { Routine.encode(it!!) })
        // Read again (the offer's second read, the next answer's): every line handed back as kept, nothing decoded again.
        val again = log.map { Routine.decode(it) }
        for (i in log.indices) assertSame(first[i], again[i])
        assertEquals(Routine.LOG_MAX, Routine.decodedKept)
    }

    @Test fun aNewAskDecodesOnlyItsOwnLine() {
        val before = lines()
        val first = before.map { Routine.decode(it) }
        // One ask added, the oldest dropped (the log at its cap): only the new line is read.
        val after = lines(from = 1)
        val next = after.map { Routine.decode(it) }
        for (i in 0 until after.size - 1) assertSame(first[i + 1], next[i])
        assertEquals(after.last(), Routine.encode(next.last()!!))
        assertEquals(Routine.LOG_MAX + 1, Routine.decodedKept)
        // Many such reads over the day: never more than twice the log is kept.
        for (k in 2..Routine.LOG_MAX * 3) Routine.decode(lines(from = k).last())
        assertEquals(Routine.LOG_MAX * 2, Routine.decodedKept)
    }

    @Test fun badLinesStayNullAndTheResetHookForgets() {
        assertNull(Routine.decode("2026-10-05T09:20#NIFTY|sell#0"))
        assertNull(Routine.decode("2026-10-05T09:20#NIFTY|sell#0"))
        assertNull(Routine.decode("not a line"))
        assertNull(Routine.decode("never-a-time#NIFTY|levels#0"))
        val line = lines().first()
        val kept = Routine.decode(line)
        assertSame(kept, Routine.decode(line))
        Routine.forgetDecoded()
        assertEquals(0, Routine.decodedKept)
        val fresh = Routine.decode(line)
        assertNotSame(kept, fresh)
        assertEquals(kept, fresh)
    }
}
