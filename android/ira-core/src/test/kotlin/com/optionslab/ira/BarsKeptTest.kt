package com.optionslab.ira

import java.security.MessageDigest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Readings of whole candle histories kept by their candles ([BarsKept], speed round 9): given back only for the very
 * same candles and key, never for a list changed in place, a candle changed in the middle or another key; and every
 * kept reader gives exactly what reading afresh gives, through a run of histories as a background pass sees them.
 */
class BarsKeptTest {
    private val month = Fixtures.indexDays(25, seed = 7)
    private val bank = Fixtures.indexDays(25, start = 52_000.0, step = 12.0, seed = 9)

    /** A middle candle's close changed: the same size, the same last candle. */
    private fun touched(b: List<Candle>) = b.toMutableList().also { val i = it.size / 2; it[i] = it[i].copy(c = it[i].c + 50, h = it[i].h + 50) }

    /** A sharp last half hour (the watch's costly path). */
    private fun sharp(b: List<Candle>) = b.dropLast(30) + b.takeLast(30).mapIndexed { i, c -> c.copy(c = c.c + i * 12, h = c.h + i * 12 + 2) }

    /** What a background pass sees over a day: the same history, a copy of it, a new minute, a revised candle, back again. */
    private fun passes(b: List<Candle>): List<List<Candle>> {
        val more = b + b.last().copy(t = b.last().t.plusMinutes(1), c = b.last().c + 3)
        return listOf(b, ArrayList(b), b, more, touched(b), b, sharp(b), sharp(b), touched(sharp(b)), b.dropLast(200), b)
    }

    @Test fun keepsOnlyTheSameCandles() {
        val k = BarsKept<Int>(2)
        var reads = 0
        val a = month.take(500)
        assertEquals(1, k.of(a, "x") { ++reads })
        assertEquals(1, k.of(ArrayList(a), "x") { ++reads })          // equal candles, another list: kept
        assertEquals(2, k.of(a, "y") { ++reads })                     // another key
        assertEquals(3, k.of(touched(a), "x") { ++reads })            // same size and last candle, one changed inside
        assertEquals(4, k.of(a, "x") { ++reads })                     // the oldest went (two kept)
        assertEquals(2, k.size)
        val live = a.toMutableList()
        assertEquals(5, k.of(live, "z") { ++reads })
        live[100] = live[100].copy(c = 1.0)                           // changed in place after it was read
        assertEquals(6, k.of(live, "z") { ++reads })
        assertEquals(6, k.of(live, "z") { ++reads })
        assertEquals(0, BarsKept<Int?>(1).of(emptyList(), null) { 0 })
        assertTrue(runCatching { k.of(a, "t") { error("x") } }.isFailure)
        assertEquals(99, k.of(a, "t") { 99 })                          // a reader that threw kept nothing
    }

    @Test fun foldKeptAsFolded() {
        for (b in passes(month)) for (m in listOf(5, 15, 60)) {
            val kept = Candles.fold(b, m, Market.NIFTY)
            assertEquals(Candles.foldNow(b, m, Market.NIFTY), kept)
            assertSame(kept, Candles.fold(ArrayList(b), m, Market.NIFTY))
        }
        assertNotSame(Candles.fold(month, 15, Market.NIFTY), Candles.fold(month, 15, Market.BANKNIFTY))
        val tiny = month.take(40)
        assertEquals(Candles.foldNow(tiny, 15, Market.NIFTY), Candles.fold(tiny, 15, Market.NIFTY))
    }

    @Test fun foldInOneWalkAsGrouped() {
        val odd = month.take(2000).map { it.copy(t = it.t.minusMinutes(560)) }      // minutes before the open and past midnight
        val gold = month.take(3000)
        val shuffled = month.take(4000).shuffled(java.util.Random(3))
        val dupes = month.take(800).flatMap { listOf(it, it.copy(c = it.c + 1)) }
        for (b in listOf(month, odd, gold, shuffled, dupes, emptyList(), month.take(1)))
            for (mk in listOf(Market.NIFTY, Market.GOLD, Market.SENSEX)) for (m in listOf(2, 5, 15, 60, 240)) {
                val anchor = mk.open?.let { it.hour * 60 + it.minute } ?: 0
                assertEquals(Candles.foldGrouped(b, m, anchor), Candles.foldNow(b, m, mk), "$mk $m ${b.size}")
            }
    }

    @Test fun brainKeptAsRead() {
        for (b in passes(month) + passes(bank)) for (mk in listOf(Market.NIFTY, Market.BANKNIFTY)) {
            val h = History(mk, b)
            assertEquals(Brain.readNow(h), Brain.read(h))
            assertEquals(Brain.readNow(h), Brain.read(History(mk, ArrayList(b))))
        }
    }

    @Test fun watchAndReadersKeptAsRead() {
        for (b in passes(month)) {
            val last = b.last().t.toLocalDate()
            for (day in listOf(last, last.minusDays(3), LocalDate.of(2027, 1, 1))) {
                assertEquals(SharpMove.usualOf(b.filter { it.t.toLocalDate() < day }), SharpMove.usual(b, day))
                assertEquals(DayClock.pastNow(b, day), DayClock.past(b, day))
                for (mk in listOf(Market.NIFTY, Market.BANKNIFTY)) assertEquals(Structure.readNow(mk, b, day), Structure.read(mk, b, day))
            }
            for (h in listOf(15, 30, 60)) assertEquals(NewsMoves.usualNow(Market.NIFTY, b, h), NewsMoves.usual(Market.NIFTY, b, h))
            assertEquals(NewsMoves.usualNow(Market.BANKNIFTY, b, NewsMoves.QUOTE), NewsMoves.usual(Market.BANKNIFTY, b))
        }
    }

    /** Everything the kept readers say over [passes], as one digest (pinned below to the code before they were kept). */
    private fun said(): String {
        val out = StringBuilder()
        for (b in passes(month) + passes(bank)) {
            val day = b.last().t.toLocalDate()
            val now = b.last().t.plusMinutes(1)
            out.append(Candles.fold(b, 15, Market.NIFTY).hashCode()).append(Candles.fold(b, 60, Market.NIFTY).hashCode())
            out.append(Brain.read(History(Market.NIFTY, b))).append(SharpMove.latest(Market.NIFTY, b)).append(SharpMove.usual(b, day))
            out.append(Structure.read(Market.NIFTY, b, day)).append(NewsMoves.usual(Market.NIFTY, b))
            DayClock.Ask.entries.forEach { out.append(DayClock.answer(it, Market.NIFTY, b, day, now)) }
            out.append('\n')
        }
        return MessageDigest.getInstance("SHA-256").digest(out.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test fun everyReadingAsBeforeTheyWereKept() = assertEquals(BEFORE, said())

    /** [said] on the code before the readings were kept (2026-10-05). */
    private val BEFORE = "00db9462581cbbfefd3f01aae107893ab0a5c2f7b09d5e0ffb02809d07a782be"
}
