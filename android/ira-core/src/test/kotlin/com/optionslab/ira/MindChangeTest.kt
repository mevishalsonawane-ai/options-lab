package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MindChangeTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val prior = LocalDate.of(2026, 10, 2)

    private fun day(minutes: Int, prevClose: Double = 23_950.0, path: (Int) -> Double): List<Candle> {
        val out = ArrayList<Candle>()
        out += Candle(prior.atTime(15, 29), prevClose, prevClose + 1, prevClose - 1, prevClose)
        var px = path(0)
        for (i in 0 until minutes) {
            val o = px; px = path(i + 1)
            out += Candle(today.atTime(9, 15).plusMinutes(i.toLong()), o, maxOf(o, px) + 0.5, minOf(o, px) - 0.5, px)
        }
        return out
    }

    /** A rising day in waves for [upTo] minutes, then a steady fall. */
    private fun upThenDown(upTo: Int, minutes: Int) = day(minutes) { i ->
        if (i <= upTo) 24_000.0 + i * 0.5 + 30 * sin(2 * PI * i / 90)
        else 24_000.0 + upTo * 0.5 + 30 * sin(2 * PI * upTo / 90) - (i - upTo) * 2.0
    }
    private val up = day(270) { i -> 24_000.0 + i * 0.5 + 30 * sin(2 * PI * i / 90) }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        listOf("What would change your mind?", "what would change your mind jarvis", "what could change your view",
            "what would make you wrong?", "what would prove that wrong", "what would invalidate that read?",
            "what would invalidate this", "when would that stop being true", "where would that no longer be true",
            "what level would invalidate your read", "where would you be wrong", "how would you know you're wrong",
            "what would it take to change your mind", "aapka view kab badlega", "kya hoga to ye galat", "ye kab galat hoga",
            "What would change your read on BankNifty?").forEach { assertTrue(MindChange.asked(it), it) }
        listOf("what would change my mind", "what needs to happen for my put to work", "what would make my trade wrong",
            "should I change my mind", "change the theme", "what changed today", "what's the structure today",
            "why do you think that", "what would nifty do tomorrow", "you are wrong", "what if nifty falls 1%").forEach {
            assertFalse(MindChange.asked(it), it)
        }
    }

    @Test fun theKindThresholdsMatchTheReadsOwnMeasure() {
        val o = 24_000.0; val hi = 24_100.0; val lo = 23_950.0
        val u = MindChange.trendUpAt(o, hi, lo)
        // Just above the line reads trend-like up; just below it no longer does.
        assertTrue(u > o)
        fun kindAt(p: Double): Int? {
            val h = maxOf(hi, p); val l = minOf(lo, p); val r = h - l
            val share = kotlin.math.abs(p - o) / r; val place = (p - l) / r
            return when {
                share >= Structure.TREND_SHARE && p > o && place >= 0.75 -> 1
                share >= Structure.TREND_SHARE && p < o && place <= 0.25 -> -1
                share <= Structure.RANGE_SHARE -> 0
                else -> null
            }
        }
        assertEquals(1, kindAt(u + 0.01)); assertTrue(kindAt(u - 0.01) != 1)
        val d = MindChange.trendDownAt(o, hi, lo)
        assertEquals(-1, kindAt(d - 0.01)); assertTrue(kindAt(d + 0.01) != -1)
        val (rd, ru) = MindChange.rangeEdges(o, hi, lo)
        assertEquals(0, kindAt(ru - 0.01)); assertTrue(kindAt(ru + 0.01) != 0)
        assertEquals(0, kindAt(rd + 0.01)); assertTrue(kindAt(rd - 0.01) != 0)
        // The open near the high: the range-like line above needs a new high, worked out past it.
        val (_, ru2) = MindChange.rangeEdges(24_090.0, hi, lo)
        assertTrue(ru2 > hi)
        val h2 = ru2 + 0.01
        assertTrue((h2 - 24_090.0) / (h2 - lo) > Structure.RANGE_SHARE)
    }

    @Test fun aReadStillHoldingSaysTheLinesAndThatNoneIsCrossed() {
        val said = MindChange.said(Market.NIFTY, up, today)
        assertNotNull(said)
        val a = MindChange.answer(null, said, mapOf(Market.NIFTY to up), today)
        assertTrue(a.startsWith("What would change my read of Nifty's structure as of"), a)
        assertTrue("none of these lines has been crossed" in a, a)
        assertTrue("prior close of 23,950.00" in a, a)
        assertTrue(MindChange.NOTE in a)
        assertFalse(ADVICE.containsMatchIn(a.replace(MindChange.NOTE, "")), a)
    }

    @Test fun whatChangedSinceTheReadIsSaidWithItsMinute() {
        val all = upThenDown(200, 290)
        // The read was given at 11:45 (the 150th minute), while the day still rose.
        val cut = today.atTime(9, 15).plusMinutes(150)
        val said = MindChange.said(Market.NIFTY, all.filter { !it.t.isAfter(cut) }, today)!!
        val then = Structure.read(Market.NIFTY, all.filter { !it.t.isAfter(said.lastBar) }, today)!!
        val tests = MindChange.tests(then, all.filter { it.t.toLocalDate() == today }, Structure.read(Market.NIFTY, all, today))
        assertTrue(tests.isNotEmpty())
        // The steady fall after 12:35 crossed at least one line, each after the read.
        val broken = tests.filter { it.brokenAt != null }
        assertTrue(broken.isNotEmpty(), tests.toString())
        assertTrue(broken.all { it.brokenAt!!.isAfter(said.lastBar) })
        val a = MindChange.answer(Market.NIFTY, said, mapOf(Market.NIFTY to all), today)
        assertTrue("already changed" in a, a)
        assertTrue("no longer hold" in a, a)
        // The same read with nothing after it: nothing has been crossed.
        val quiet = MindChange.tests(then, all.filter { it.t.toLocalDate() == today && !it.t.isAfter(said.lastBar) }, then)
        assertTrue(quiet.all { it.brokenAt == null }, quiet.toString())
    }

    @Test fun withoutAReadOfHisTodaysIsTestedAndSaidSo() {
        val a = MindChange.answer(null, null, mapOf(Market.NIFTY to up), today)
        assertTrue(a.startsWith("I haven't given you a read of Nifty's structure today"), a)
        assertFalse("Since then" in a, a)
        // A read from another day, or of another index than the one asked: today's, said so.
        val old = MindChange.Said(Market.NIFTY, LocalDateTime.of(prior, java.time.LocalTime.of(11, 0)))
        assertTrue(MindChange.answer(null, old, mapOf(Market.NIFTY to up), today).startsWith("I haven't given you"))
        val bank = MindChange.Said(Market.BANKNIFTY, today.atTime(11, 0))
        assertTrue(MindChange.answer(Market.NIFTY, bank, mapOf(Market.NIFTY to up), today).startsWith("I haven't given you a read of Nifty's"))
    }

    @Test fun tooFewCandlesOrNoIndex() {
        assertTrue("no Nifty candles for today" in MindChange.answer(null, null, emptyMap(), today))
        assertTrue("too few" in MindChange.answer(null, null, mapOf(Market.NIFTY to up.take(10)), today))
        assertEquals(Structure.NOT_HERE, MindChange.answer(Market.GOLD, null, emptyMap(), today))
        assertNull(MindChange.said(Market.NIFTY, emptyList(), today))
    }
}
