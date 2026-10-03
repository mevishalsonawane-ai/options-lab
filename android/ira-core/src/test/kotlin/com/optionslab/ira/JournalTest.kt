package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalTest {
    private val today = LocalDate.of(2026, 10, 15)   // a Thursday

    @Test fun searchIsReadAndAnswered() {
        val f = TradeSearch.parse("How did my Thursday expiry trades do this month?", today)
        assertEquals(DayOfWeek.THURSDAY, f.weekday); assertTrue(f.expiryOnly)
        assertEquals(LocalDate.of(2026, 10, 1), f.from); assertEquals(today, f.to)
        assertEquals("Thursday expiry-day trades this month", f.label)
        val trips = listOf(TradeSearch.Trip("NIFTY24000CE", LocalDateTime.of(2026, 10, 8, 10, 0), 500.0),
            TradeSearch.Trip("NIFTY24000PE", LocalDateTime.of(2026, 10, 15, 13, 0), -200.0),
            TradeSearch.Trip("NIFTY24000PE", LocalDateTime.of(2026, 10, 14, 10, 0), 900.0),      // Wednesday
            TradeSearch.Trip("NIFTY24000PE", LocalDateTime.of(2026, 9, 24, 10, 0), 900.0))       // last month
        assertEquals("2 Thursday expiry-day trades this month: 1 won, 1 lost, net +Rs 300.00 (best +Rs 500.00, worst -Rs 200.00).",
            TradeSearch.answer(trips, f, { true }, { Market.NIFTY }))
        assertTrue(TradeSearch.asked("How did my Thursday expiry trades do this month?"))
        assertFalse(TradeSearch.asked("show my trades"))
        assertEquals(setOf(Section.SEARCH), AppAnswers.sections("How did my Thursday expiry trades do this month?"))
        assertTrue(Topic.ACCOUNT in Ask.parse("How did my Thursday expiry trades do this month?").topics)
        assertEquals("No BankNifty trades in the morning found.", TradeSearch.answer(trips, TradeSearch.parse("my banknifty trades in the morning", today), { false }, { Market.NIFTY }))
    }

    @Test fun timeOfDay() {
        fun t(h: Int, net: Double) = TradeSearch.Trip("X", LocalDateTime.of(2026, 10, 1, h, 30), net)
        val l = TimeOfDay.line(List(3) { t(9, 100.0) } + List(3) { t(13, -200.0) })!!
        assertEquals("Best time: 09:15-10:00 (+Rs 300.00 over 3). Worst: 13:00-14:00 (-Rs 600.00 over 3).", l)
        assertNull(TimeOfDay.line(List(3) { t(9, 100.0) }))
        assertEquals(setOf(Section.TIMEOFDAY), AppAnswers.sections("What is my best time of day to trade?"))
    }

    @Test fun staleTrades() {
        val open = LocalDateTime.of(2026, 10, 1, 10, 0)
        assertTrue(StaleTrade.stale(open, open.plusMinutes(45), 100.0, 102.0))
        assertFalse(StaleTrade.stale(open, open.plusMinutes(44), 100.0, 102.0))
        assertFalse(StaleTrade.stale(open, open.plusMinutes(60), 100.0, 110.0))
        assertFalse(StaleTrade.stale(open, LocalDateTime.of(2026, 10, 1, 15, 0), 100.0, 100.0))
        assertTrue(StaleTrade.say("X", 45, 100.0, 102.0).endsWith("Shall I close it?"))
    }

    @Test fun positionNews() {
        val held = listOf(PositionNews.Held("NIFTY24000CE", Market.NIFTY, call = true, long = true), PositionNews.Held("BANKNIFTY51000PE", Market.BANKNIFTY, call = false, long = true))
        val s = PositionNews.say("Nifty rallies on strong data", 0.6, held, listOf(Market.NIFTY))!!
        assertTrue(s.contains("NIFTY24000CE: good for it"), s)
        assertFalse(s.contains("BANKNIFTY"))
        assertTrue(PositionNews.say("Bank stocks fall", -0.6, held, listOf(Market.BANKNIFTY))!!.contains("BANKNIFTY51000PE: good for it"))
        assertNull(PositionNews.say("x", 0.1, held, listOf(Market.NIFTY)))
        assertNull(PositionNews.say("x", 0.9, held, listOf(Market.GOLD)))
    }

    @Test fun targetsAndNotes() {
        assertTrue(DayTarget.reached(3000.0, 3000.0)); assertFalse(DayTarget.reached(2999.0, 3000.0)); assertFalse(DayTarget.reached(5000.0, null))
        val c = Commands.parse("Jarvis, my target today is 3000")!!
        assertEquals(Command.Kind.TARGET_SET, c.kind); assertEquals(3000.0, c.level)
        assertEquals(5000.0, Commands.parse("set my daily target to 5k")?.level)
        assertEquals(Command.Kind.TARGET_CLEAR, Commands.parse("clear my target")?.kind)
        assertNull(Commands.parse("what is my target today?"))
        // An order with a target is not the day's target.
        assertTrue(Commands.parse("buy 1 lot nifty 24000 call with target 140")?.kind != Command.Kind.TARGET_SET)
        val n = Commands.parse("Jarvis, note: I bought because of the hammer at support")!!
        assertEquals(Command.Kind.NOTE, n.kind); assertEquals("I bought because of the hammer at support", n.target)
        assertEquals("I didn't wait for the close", Commands.parse("note: I didn't wait for the close")?.target)
        assertEquals("pattern", TradeReasons.kind("the hammer at support"))
        assertEquals("breakout", TradeReasons.kind("range breakout"))
        assertEquals("gut feel", TradeReasons.kind("just a feeling"))
        val r = TradeReasons.lines(List(3) { "hammer" to 100.0 } + List(3) { "gut feel" to -50.0 } + listOf("news" to 5.0))
        assertEquals(listOf("Trades taken for \"pattern\": 3, 3 won, net +Rs 300.00.", "Trades taken for \"gut feel\": 3, 0 won, net -Rs 150.00."), r)
        assertEquals(setOf(Section.REASONS), AppAnswers.sections("Which of my reasons work?"))
    }

    @Test fun backupAndOnce() {
        assertTrue(BackupNudge.due(null, today)); assertTrue(BackupNudge.due(today.minusDays(7), today)); assertFalse(BackupNudge.due(today.minusDays(6), today))
        val o = Once(30); val t0 = LocalDateTime.of(2026, 10, 1, 10, 0)
        assertTrue(o.allow("a", t0)); assertFalse(o.allow("a", t0.plusMinutes(29))); assertTrue(o.allow("a", t0.plusMinutes(30))); assertTrue(o.allow("b", t0))
    }
}
