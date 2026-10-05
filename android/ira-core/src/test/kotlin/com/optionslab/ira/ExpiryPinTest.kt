package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpiryPinTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val after = LocalDateTime.of(2026, 10, 5, 18, 0)

    /**
     * One expiry chain on [day]: strikes 24,000 to 25,000 (every 50), each priced so put-call parity gives [f930] at 09:30,
     * [f1430] at 14:30 and [settle] through 15:00 to 15:29; open interest puts max pain at [mp] (calls and puts of 1,000
     * there, 10 everywhere else) and the most open interest at [top] (calls of 5,000) when it is not [mp].
     */
    private fun chain(day: LocalDate, f930: Double, settle: Double, mp: Double, top: Double = mp, f1430: Double = settle): Session {
        val mins = intArrayOf(570, 870) + (900 until 930).toList().toIntArray()
        val series = ArrayList<Series>()
        var k = 24000.0
        while (k <= 25000.0) {
            for (r in listOf(Right.CE, Right.PE)) {
                val close = DoubleArray(mins.size) { i ->
                    val f = when { i == 0 -> f930; i == 1 -> f1430; else -> settle }
                    20.0 + if (r == Right.CE) maxOf(f - k, 0.0) else maxOf(k - f, 0.0)
                }
                val oi = when {
                    r == Right.CE && k == top && top != mp -> 5000L
                    k == mp -> 1000L
                    else -> 10L
                }
                series += Series(day, k, r, 75, mins, close, null, null, null, null, LongArray(mins.size) { oi })
            }
            k += 50.0
        }
        return Session(day, 75, series)
    }

    @Test fun theQuestionsAreItsAndTheirNeighboursAreNot() {
        for (s in listOf("how often does nifty close near max pain on expiry", "does nifty pin to max pain on expiry day", "expiry pin record",
            "how often does nifty settle at the biggest oi strike on expiry", "expiry pe nifty max pain ke paas band hota hai kya",
            "does max pain work on expiry days", "how close to max pain does nifty close on expiries", "max pain pin stats",
            "how often does nifty settle within 100 points of max pain on expiry"))
            assertNotNull(ExpiryPin.asked(s), s)
        assertEquals(100.0, ExpiryPin.asked("how often does nifty settle within 100 points of max pain on expiry")!!.within)
        assertEquals(ExpiryPin.DEFAULT_PTS, ExpiryPin.asked("expiry pin record")!!.within)
        for (s in listOf("what is max pain", "where is max pain", "how has max pain moved today", "where will nifty pin tomorrow",
            "how is expiry going", "are expiry days wider than other days", "is nifty near max pain", "max pain and call wall since the open",
            "what is the max pain for this expiry", "should i sell at max pain on expiry", "has max pain shifted since morning", "gold expiry pin record"))
            assertNull(ExpiryPin.asked(s), s)
    }

    @Test fun onlyNifty() {
        assertEquals(Market.NIFTY, ExpiryPin.market(emptyList()))
        assertEquals(Market.NIFTY, ExpiryPin.market(listOf(Market.NIFTY)))
        assertNull(ExpiryPin.market(listOf(Market.BANKNIFTY)))
    }

    @Test fun oneExpiryIsReadFromItsOwnChain() {
        val d = ExpiryPin.day(chain(LocalDate.of(2026, 9, 29), f930 = 24510.0, settle = 24480.0, mp = 24500.0, top = 24700.0, f1430 = 24470.0))!!
        assertEquals(24510.0, d.at930, 0.01)
        assertEquals(24480.0, d.settle, 0.01)
        assertEquals(24500.0, d.maxPain)
        assertEquals(24700.0, d.oiStrike)
        assertEquals(24470.0, d.at1430!!, 0.01)
        assertEquals(24500.0, d.lateMaxPain)
        // Another expiry's contracts are not this day's chain.
        assertNull(ExpiryPin.day(Session(LocalDate.of(2026, 9, 30), 75, chain(LocalDate.of(2026, 9, 29), 24500.0, 24500.0, 24500.0).series)))
    }

    @Test fun theRecordAgainstWhereNiftyStoodThatMorning() {
        // 12 weekly expiries: 8 settle 20 points from max pain having opened 300 away; 4 settle 300 from max pain, 20 from the morning.
        val sessions = (0 until 12).map { i ->
            val day = LocalDate.of(2026, 7, 7).plusWeeks(i.toLong())
            if (i < 8) chain(day, f930 = 24800.0, settle = 24520.0, mp = 24500.0, f1430 = 24510.0)
            else chain(day, f930 = 24780.0, settle = 24800.0, mp = 24500.0, top = 24800.0, f1430 = 24790.0)
        }
        // Today's own expiry before the close, and one in the future, are not counted.
        val days = ExpiryPin.days((sessions + chain(today, 24500.0, 24500.0, 24500.0) + chain(today.plusDays(7), 24500.0, 24500.0, 24500.0)).asSequence(),
            today, LocalDateTime.of(2026, 10, 5, 11, 0))
        assertEquals(12, days.size)
        val a = ExpiryPin.answer(ExpiryPin.Q(), days, null, today)
        assertTrue("last 12 Nifty expiries" in a, a)
        assertTrue("within 50 points of max pain as it stood at 09:30 on 8 (67%)" in a, a)
        assertTrue("most open interest on 12 (100%)" in a, a)
        assertTrue("where it already stood at 09:30 on 4 (33%)" in a, a)
        assertTrue("more often than" in a, a)
        assertTrue("Read again at 14:30 (12 expiries)" in a, a)
        assertTrue("The farthest:" in a && "300 points away" in a, a)
        assertTrue(ExpiryPin.NOTE in a && ExpiryPin.METHOD in a, a)
        // Asked within 400 points: every one.
        assertTrue("within 400 points of max pain as it stood at 09:30 on 12 (100%)" in ExpiryPin.answer(ExpiryPin.Q(400.0), days, null, today))
    }

    @Test fun tooFewIsSaidPlainly() {
        val days = ExpiryPin.days(sequenceOf(chain(LocalDate.of(2026, 9, 29), 24500.0, 24520.0, 24500.0)), today, after)
        val a = ExpiryPin.answer(ExpiryPin.Q(), days, null, today)
        assertTrue("only 1 past Nifty expiry day" in a && "too few" in a, a)
        assertTrue("The newest on the phone, 29 Sep 2026" in a, a)
        assertTrue("no past Nifty expiry day" in ExpiryPin.answer(ExpiryPin.Q(), emptyList(), null, today))
    }

    @Test fun maxPainIsWhereWritersLoseLeast() {
        assertEquals(100.0, ExpiryPin.maxPain(mapOf(90.0 to (0L to 50L), 100.0 to (100L to 100L), 110.0 to (50L to 0L))))
        assertNull(ExpiryPin.maxPain(mapOf(100.0 to (0L to 0L))))
    }
}
