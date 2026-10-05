package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SameInputTest {
    private val memo = SameInput<List<Int>, List<Int>>()
    private var made = 0
    private fun doubled(l: List<Int>): List<Int> { made++; return l.map { it * 2 } }

    @Test fun anEqualInputGivesTheKeptResult() {
        val first = memo.of(listOf(1, 2, 3), ::doubled)
        // A new list with the same items (a fresh copy of an unchanged trade book): not rebuilt.
        val again = memo.of(ArrayList(listOf(1, 2, 3)), ::doubled)
        assertSame(first, again)
        assertEquals(1, made)
    }

    @Test fun aChangedInputIsMadeAfresh() {
        assertEquals(listOf(2, 4), memo.of(listOf(1, 2), ::doubled))
        assertEquals(listOf(2, 4, 6), memo.of(listOf(1, 2, 3), ::doubled))    // a trade added
        assertEquals(listOf(2, 8), memo.of(listOf(1, 4), ::doubled))          // same size, a different item
        assertEquals(listOf(2, 4), memo.of(listOf(1, 2), ::doubled))          // back again: made again, never mixed up
        assertEquals(4, made)
    }

    @Test fun aSnapshotIsUnaffectedByLaterChangesToTheSource() {
        val source = mutableListOf(1, 2)
        memo.of(ArrayList(source), ::doubled)
        source += 3                                                           // added to in place, as the Zerodha book is
        assertEquals(listOf(2, 4, 6), memo.of(ArrayList(source), ::doubled))
        assertEquals(2, made)
    }

    @Test fun aFailureKeepsNothing() {
        memo.of(listOf(1), ::doubled)
        assertFailsWith<IllegalStateException> { memo.of(listOf(5)) { error("unreadable") } }
        // The old result stands for its own input only; the failed input is tried again.
        assertEquals(listOf(10), memo.of(listOf(5), ::doubled))
        assertEquals(listOf(2), memo.of(listOf(1), ::doubled))
        assertEquals(3, made)
    }

    @Test fun forgetMakesItAfresh() {
        memo.of(listOf(1), ::doubled)
        memo.forget()
        memo.of(listOf(1), ::doubled)
        assertEquals(2, made)
    }

    @Test fun manyThreadsBuildItOnce() {
        val input = (1..1000).toList()
        val threads = (1..8).map { Thread { repeat(50) { memo.of(ArrayList(input), ::doubled) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, made)
    }
}
