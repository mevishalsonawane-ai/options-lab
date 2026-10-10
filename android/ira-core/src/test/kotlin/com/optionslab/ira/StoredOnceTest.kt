package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Speed round 12: a stored record (the routine log, the mistakes list) is read out of its text once and kept while the
 * stored text is the same ([StoredOnce]). Work is counted by the reads made, never by the clock.
 */
class StoredOnceTest {
    private val today = LocalDate.of(2026, 10, 6)
    private val keys = listOf("NIFTY|levels", "BANKNIFTY|levels", "ACCOUNT|pnl", "NIFTY|overview")

    /** The log's stored text, as the app writes it (one encoded line per ask, joined). */
    private fun stored(n: Int): String = (0 until n).joinToString("\n") { i ->
        Routine.encode(Routine.Seen(keys[i % keys.size], today.minusDays(30L - i / 10).atTime(9, 15).plusMinutes(i % 10 * 5L), false))
    }

    private fun fresh(text: String?): List<Routine.Seen> = text.orEmpty().split("\n").mapNotNull { Routine.decode(it) }

    private val log = StoredOnce { text -> fresh(text) }

    @BeforeTest fun forget() { log.forget(); Routine.forgetDecoded() }

    @Test fun theSameTextIsReadOnceForManyAnswers() {
        val text = stored(Routine.LOG_MAX)
        val first = log.of(text)
        assertEquals(fresh(text), first)
        // The offer's second read in the same answer, and the next ten answers: nothing read again.
        repeat(21) { assertSame(first, log.of(text)) }
        assertEquals(1, log.readCount)
        // Equal text in another instance (a reload of the vault): still the same reading, not read again.
        assertSame(first, log.of(String(text.toCharArray())))
        assertEquals(1, log.readCount)
    }

    @Test fun otherTextIsReadAfresh() {
        val before = stored(10)
        val after = stored(11)                            // a question noted
        val a = log.of(before)
        val b = log.of(after)
        assertNotSame(a, b)
        assertEquals(fresh(after), b)
        assertEquals(2, log.readCount)
        // A "forget" or a wipe: nothing stored - read as nothing, exactly as reading it gives.
        assertEquals(fresh(null), log.of(null))
        assertEquals(emptyList(), log.of(null))
        assertEquals(3, log.readCount)
        // Back to an older text: read again (only the last text is kept), the same reading as before.
        assertEquals(a, log.of(before))
        assertEquals(4, log.readCount)
    }

    @Test fun aReadThatFailsKeepsNothing() {
        var calls = 0
        val bad = StoredOnce<Int> { text -> calls++; text!!.toInt() }
        assertFailsWith<NumberFormatException> { bad.of("x") }
        assertFailsWith<NumberFormatException> { bad.of("x") }
        assertEquals(2, calls)
        assertEquals(7, bad.of("7"))
        assertEquals(7, bad.of("7"))
        assertEquals(3, calls)
    }

    @Test fun forgetDropsTheReadingAndTheCount() {
        val text = stored(5)
        log.of(text)
        log.forget()
        assertEquals(0, log.readCount)
        log.of(text)
        assertEquals(1, log.readCount)
    }
}
