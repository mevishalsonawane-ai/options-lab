package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineTest {
    private val today = LocalDate.of(2026, 10, 5)          // a Monday
    private fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, m)

    /** [key] asked at h:m on each of the [n] weekdays before [today]. */
    private fun weekdays(key: String, n: Int, h: Int, m: Int): List<Routine.Seen> {
        val out = ArrayList<Routine.Seen>()
        var d = today.minusDays(1)
        while (out.size < n) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += Routine.Seen(key, at(d, h, m + out.size % 3))
            d = d.minusDays(1)
        }
        return out
    }

    @Test fun keysAreQuestionsOnly() {
        assertEquals("BANKNIFTY|overview", Routine.key("how is banknifty doing"))
        assertEquals("ACCOUNT|pnl", Routine.key("what is my P&L today"))
        assertEquals("ACCOUNT|events", Routine.key("what are the events this week"))
        assertNull(Routine.key("buy 2 lots of nifty 25000 ce"))
        assertNull(Routine.key("stop all strategies"))
        assertNull(Routine.key("tell me a joke"))
        listOf("BANKNIFTY|overview", "NIFTY|levels", "ACCOUNT|pnl", "ACCOUNT|events").forEach { assertTrue(Routine.safe(it), it) }
        assertFalse(Routine.safe("ACCOUNT|orders"))
        assertFalse(Routine.safe("NIFTY|buy"))
    }

    @Test fun aDailyHabitIsFoundAroundItsTime() {
        var log = emptyList<Routine.Seen>()
        weekdays("BANKNIFTY|overview", 5, 9, 19).forEach { log = Routine.add(log, it) }
        val p = Routine.patterns(log, today).single()
        assertEquals(Routine.Kind.DAILY, p.kind)
        assertEquals(9 * 60 + 20, p.minute)
        assertEquals("Boss, you usually ask about how BankNifty is doing around 9:20 on trading days. Shall I tell you then, in words only?", Routine.offer(p))
        // Twice is not a habit.
        assertTrue(Routine.patterns(weekdays("NIFTY|levels", 2, 10, 0), today).isEmpty())
    }

    @Test fun oldAsksFade() {
        val old = weekdays("BANKNIFTY|overview", 5, 9, 19).map { it.copy(at = it.at.minusDays(40)) }
        assertTrue(Routine.patterns(old, today).isEmpty(), "asks over the window count for nothing")
        // Three asks three weeks ago weigh too little.
        val faded = (0 until 3).map { Routine.Seen("NIFTY|levels", at(today.minusDays(21L + it), 10, 0)) }
        assertTrue(Routine.patterns(faded, today).isEmpty())
    }

    @Test fun aWeekdayHabit() {
        // Four Mondays at 9:05, the week's events - and never on another day.
        val log = (1..4).map { Routine.Seen("ACCOUNT|events", at(today.minusWeeks(it.toLong()), 9, 5)) }
        val p = Routine.patterns(log, today).single()
        assertEquals(Routine.Kind.WEEKDAY, p.kind)
        assertEquals(DayOfWeek.MONDAY, p.day)
        assertTrue(Routine.offer(p).contains("the week's events on Mondays around 9:05"))
        // Asked as often on other days too: not a Monday habit.
        val spread = log + (1..4).map { Routine.Seen("ACCOUNT|events", at(today.minusWeeks(it.toLong()).plusDays(2), 14, 0)) }
        assertTrue(Routine.patterns(spread, today).none { it.kind == Routine.Kind.WEEKDAY })
    }

    @Test fun anAfterLossHabit() {
        val log = (1..3).map { Routine.Seen("ACCOUNT|pnl", at(today.minusDays(it.toLong()), 11 + it, 10 * it), afterLoss = true) }
        val p = Routine.patterns(log, today).single()
        assertEquals(Routine.Kind.AFTER_LOSS, p.kind)
        assertEquals("Boss, you usually ask about your P&L just after a losing trade. Shall I tell you then, in words only?", Routine.offer(p))
    }

    @Test fun offeredOnceAndKeptOnlyOnTheYes() {
        val log = weekdays("BANKNIFTY|overview", 5, 9, 19)
        val ps = Routine.patterns(log, today)
        val p = Routine.toOffer(ps, "BANKNIFTY|overview", emptyList(), emptyMap(), today)
        assertNotNull(p)
        assertNull(Routine.toOffer(ps, "NIFTY|levels", emptyList(), emptyMap(), today), "only the question just asked")
        assertNull(Routine.toOffer(ps, p.key, emptyList(), mapOf(p.slot to today.minusDays(3)), today), "not put again soon")
        assertNotNull(Routine.toOffer(ps, p.key, emptyList(), mapOf(p.slot to today.minusDays(40)), today))
        val k = Routine.keep(p, today)!!
        assertNull(Routine.toOffer(ps, p.key, listOf(k), emptyMap(), today), "kept already")
        assertTrue(Routine.kept(k).contains("words only"))
        assertEquals(k, Routine.decodeKept(Routine.encode(k)))
        val s = Routine.Seen("ACCOUNT|pnl", at(today, 9, 20), true)
        assertEquals(s, Routine.decode(Routine.encode(s)))
        assertNull(Routine.decode("2026-10-05T09:20#NIFTY|sell#0"))
    }

    @Test fun dueAtItsTimeOnceAndNotWhenAskedAlready() {
        val k = Routine.Kept("BANKNIFTY|overview", Routine.Kind.DAILY, 9 * 60 + 20, null, today.plusDays(10))
        assertEquals(k, Routine.due(listOf(k), at(today, 9, 16), emptySet(), null))
        assertNull(Routine.due(listOf(k), at(today, 9, 40), emptySet(), null))
        assertNull(Routine.due(listOf(k), at(today, 9, 18), setOf(Routine.token(k, null)), null), "once a day")
        assertNull(Routine.due(listOf(k), at(today, 9, 18), emptySet(), null, listOf(Routine.Seen(k.key, at(today, 9, 2)))), "he just asked")
        assertNull(Routine.due(listOf(k.copy(until = today.minusDays(1))), at(today, 9, 18), emptySet(), null), "lapsed")
        val w = Routine.Kept("ACCOUNT|events", Routine.Kind.WEEKDAY, 9 * 60 + 5, DayOfWeek.MONDAY, today.plusDays(10))
        assertEquals(w, Routine.due(listOf(w), at(today, 9, 5), emptySet(), null))
        assertNull(Routine.due(listOf(w), at(today.plusDays(1), 9, 5), emptySet(), null), "Mondays only")
        val l = Routine.Kept("ACCOUNT|pnl", Routine.Kind.AFTER_LOSS, -1, null, today.plusDays(10))
        assertNull(Routine.due(listOf(l), at(today, 12, 0), emptySet(), null))
        val loss = at(today, 11, 50)
        assertEquals(l, Routine.due(listOf(l), at(today, 12, 0), emptySet(), loss))
        assertNull(Routine.due(listOf(l), at(today, 12, 0), setOf(Routine.token(l, loss)), loss), "once a loss")
        assertNull(Routine.due(listOf(l), at(today, 12, 30), emptySet(), loss), "long after")
    }

    @Test fun theLastLoss() {
        val now = at(today, 12, 0)
        fun trip(close: LocalDateTime, net: Double) = Insights.Trip("NIFTY", close.minusMinutes(20), close, net, "Manual")
        assertEquals(at(today, 11, 45), Routine.lossAt(listOf(trip(at(today, 11, 45), -500.0)), now))
        assertNull(Routine.lossAt(listOf(trip(at(today, 11, 45), -500.0), trip(at(today, 11, 55), 200.0)), now), "a win since")
        assertNull(Routine.lossAt(listOf(trip(at(today, 10, 0), -500.0)), now), "too long ago")
    }

    @Test fun askedAndDescribed() {
        assertTrue(Routine.asked("What do I usually ask?"))
        assertTrue(Routine.asked("what are my habits with you"))
        assertTrue(Routine.asked("Jarvis, what have you noticed about my routine?"))
        assertFalse(Routine.asked("what are my trading habits"))
        assertFalse(Routine.asked("the usual"))
        assertTrue(Routine.forgetAsked("forget my routine"))
        assertFalse(Routine.forgetAsked("forget my trades"))
        assertTrue(Routine.describe(emptyList(), emptyList(), today).startsWith("I haven't noticed"))
        val ps = Routine.patterns(weekdays("BANKNIFTY|overview", 5, 9, 19), today)
        val k = Routine.keep(ps.single(), today)!!
        val said = Routine.describe(ps, listOf(k), today)
        assertTrue(said.contains("how BankNifty is doing around 9:20 on trading days (5 days) - I tell you then"), said)
        assertFalse(said.contains("Rs"))
        // Asking it himself keeps the routine going.
        assertEquals(today.plusDays(50 + Routine.KEEP_DAYS), Routine.renew(listOf(k), k.key, today.plusDays(50)).single().until)
    }
}
