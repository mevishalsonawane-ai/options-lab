package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StopNoiseTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    /** One session of 1-minute candles from 09:15 to 15:29, from [open] moving [step] points every minute. */
    private fun session(d: LocalDate, open: Double, step: Double): List<Candle> {
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15); var px = open
        while (!t.isAfter(LocalTime.of(15, 29))) {
            val c = px + step
            out += Candle(d.atTime(t), px, maxOf(px, c), minOf(px, c), c)
            px = c; t = t.plusMinutes(1)
        }
        return out
    }

    private fun weekdays(n: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < n) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d; d = d.minusDays(1) }
        return out.reversed()
    }

    /** [n] days of Nifty from 20,000 falling one point a minute. */
    private fun falling(n: Int): List<Candle> = weekdays(n).flatMap { session(it, 20000.0, -1.0) }

    private fun call(stop: Double?, delta: Double? = 0.5, qty: Int = 75, ltp: Double = 100.0, sym: String = "NIFTY24O1020000CE") =
        Exposure.Leg("Paper", sym, qty, 110.0, ltp, "NIFTY", delta, 0.0, stop = stop)

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|widen|loosen|tighten|move your|too tight|too close|will (rise|fall|go|hit))\\b")

    @Test fun theQuestions() {
        for (s in listOf("is my stop too tight", "Is my stop too tight?", "are my stops too close", "isn't my SL a bit tight",
            "is my stop loss too tight", "is my nifty call stop too tight", "my stop is too tight", "my stops look too close",
            "is my stop inside the noise", "are my stops within normal swings", "will normal noise hit my stop",
            "can ordinary swings take out my sl", "how much room does my stop have", "my stop noise check", "check my stops against the noise",
            "mera stop bahut tight hai kya", "meri sl zyada paas hai", "mera sl tight to nahi", "is my stop set too tight",
            "is the stop on my put too tight".replace("the stop on my put", "my put stop")))
            assertTrue(StopNoise.asked(s), s)
        for (s in listOf("move my stop to 80", "set my stop to 80", "set stop loss at 80", "should i widen my stop", "is my stop too tight should i move it",
            "where should i put my stop", "tighten my stop", "trail my stop loss", "what are my stops", "where is my stop loss", "did my stop loss hit",
            "is the orb stop too tight", "is my gold stop too tight", "what is a stop loss", "was my stop too tight", "are my stops close to being hit",
            "what if all my stops get hit", "is nifty too close to the high", "my stop", "is my target too far", "how often does nifty move 50 points in 30 minutes",
            "mera stop 80 pe kar do", "stop all strategies", "is my position healthy"))
            assertFalse(StopNoise.asked(s), s)
    }

    @Test fun moveMyStopTo80ReadsAsBefore() {
        // The detector never reads a stop change, and the parse of it is the parser's alone, as before this round: no
        // order, no command, no acting topic (coverage-actions.txt's lines are unchanged too).
        for (s in listOf("move my stop to 80", "move my stop loss to 80", "mera stop 80 pe kar do")) {
            assertFalse(StopNoise.asked(s), s)
            assertTrue(CoverageTest().feature(s) != "StopNoise", s)
        }
        val p = Ask.parse("move my stop to 80")
        assertEquals(null, p.command, "$p")
        assertEquals(null, p.order, "$p")
        assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, "$p")
    }

    @Test fun theRecordReadsFallsAndRisesByWindow() {
        val r = StopNoise.record(falling(12), today)
        assertEquals(12, r.days)
        // 23 starts (9:30 to 15:00) a session for 15 minutes; 22 with 30 minutes left before the last candle (15:29).
        assertEquals(12 * 23, r.falls.getValue(15).size)
        assertEquals(12 * 22, r.falls.getValue(30).size)
        // One point a minute down: 15 and 30 points of fall, no rise.
        val f15 = r.falls.getValue(15).first(); val f30 = r.falls.getValue(30).first()
        assertTrue(f15 > 0.07 && f15 < 0.08, "$f15")
        assertTrue(f30 > 0.14 && f30 < 0.16, "$f30")
        assertTrue(r.rises.getValue(30).all { it == 0.0 })
        // Today's session is never counted.
        assertEquals(12, StopNoise.record(falling(12) + session(today, 20000.0, -1.0), today).days)
    }

    @Test fun pointsToTheStopFromDelta() {
        // Delta 0.5, no gamma: 10 of premium is 20 points of fall.
        val x = StopNoise.pointsTo(call(90.0), 90.0, -1, 20000.0)
        assertNotNull(x)
        assertTrue(kotlin.math.abs(x - 20.0) < 0.01, "$x")
        // A put's stop is reached by a rise.
        val put = Exposure.Leg("Paper", "NIFTY24O1020000PE", 75, 110.0, 100.0, "NIFTY", -0.4, 0.0, stop = 80.0)
        assertTrue(kotlin.math.abs(StopNoise.pointsTo(put, 80.0, 1, 20000.0)!! - 50.0) < 0.01)
        // Held at the bottom of the curve above the stop: no index move alone gets there.
        val held = Exposure.Leg("Paper", "NIFTY24O1020000CE", 75, 110.0, 100.0, "NIFTY", 0.5, 0.01, stop = 80.0)
        assertNull(StopNoise.pointsTo(held, 80.0, -1, 20000.0))
    }

    @Test fun eachStopAgainstTheRecord() {
        val rec = mapOf(Market.NIFTY to StopNoise.record(falling(12), today))
        val spots = mapOf(Market.NIFTY to 20000.0)
        val out = StopNoise.answer(listOf(call(90.0)), rec, spots)
        val all = out.joinToString(" ")
        // 20 points of fall: never within 15 minutes (15 points), every time within 30 (30 points).
        assertTrue(all.contains("about 20 Nifty points of fall"), all)
        assertTrue(all.contains("within 15 minutes on 0% of 276") && all.contains("within 30 minutes on 100% of 264"), all)
        assertTrue(all.contains("median fall within 30 minutes was about 30 points"), all)
        assertTrue(all.contains("10% of the premium"), all)
        assertTrue(all.contains("12 whole Nifty sessions") && all.contains("small record"), all)
        assertTrue(out.last() == StopNoise.CLOSING)
        assertFalse(ADVICE.containsMatchIn(all), all)
    }

    @Test fun theOddCasesSaidPlainly() {
        val rec = mapOf(Market.NIFTY to StopNoise.record(falling(12), today))
        val spots = mapOf(Market.NIFTY to 20000.0)
        // Already at or below the stop.
        assertTrue(StopNoise.answer(listOf(call(105.0)), rec, spots).first().contains("already at or below its 105.00 stop"))
        // No delta: no points said.
        assertTrue(StopNoise.answer(listOf(call(90.0, delta = null)), rec, spots).first().contains("can't put that in Nifty points"))
        // Too few sessions.
        val few = StopNoise.answer(listOf(call(90.0)), mapOf(Market.NIFTY to StopNoise.record(falling(5), today)), spots)
        assertTrue(few.first().contains("only 5 whole Nifty sessions"), few.first())
        // No options, no stops, shorts.
        assertTrue(StopNoise.answer(emptyList(), rec, spots).first().contains("no open options"))
        val none = StopNoise.answer(listOf(call(null)), rec, spots)
        assertTrue(none.first().contains("has a stop the app keeps") && none.any { it.startsWith("No stop kept on: Paper NIFTY") }, "$none")
        val short = StopNoise.answer(listOf(call(90.0), call(130.0, qty = -75, sym = "NIFTY24O1020500CE")), rec, spots)
        assertTrue(short.any { it.startsWith("Not read here: your short option") }, "$short")
        // Closest stop first.
        val two = StopNoise.answer(listOf(call(60.0, sym = "A24500CE"), call(95.0, sym = "B24500CE")), rec, spots)
        assertTrue(two[0].contains("B24500CE") && two[1].contains("A24500CE"), "$two")
        assertEquals(listOf(Market.NIFTY), StopNoise.markets(listOf(call(90.0), call(null))))
    }
}
