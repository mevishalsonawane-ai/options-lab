package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallTradesTest {
    private val today: LocalDate = LocalDate.of(2026, 10, 5)
    private val now: LocalDateTime = today.atTime(15, 35)

    /** A trade opened at [h]:[m] on [day], closed 10 minutes later, with [gross] before charges and Rs 60 charges. */
    private fun t(day: LocalDate, owner: String, gross: Double, h: Int = 11, m: Int = 0, charges: Double = 60.0) =
        Charges.Trip(day.atTime(h, m), day.atTime(h, m).plusMinutes(10), gross, charges, owner)

    /** [n] trades by [owner], spread over [days] days back from today, [small] of them small (moved Rs 50 against Rs 60). */
    private fun many(owner: String, n: Int, small: Int, days: Int, h: Int = 11): List<Charges.Trip> =
        (0 until n).map { i -> t(today.minusDays((i % days).toLong()), owner, if (i < small) 50.0 else 1_000.0, h, i % 50) }

    @Test fun learnedOnlyAfterEnoughTradesDaysAndShare() {
        val orb = many("ORB", 40, 18, 7)
        val r = SmallTrades.learned(listOf(SmallTrades.Book("Paper", orb)), SmallTrades.Log(), now)
        val src = r.single { it.by == SmallTrades.By.SOURCE }
        assertEquals("ORB", src.name); assertEquals(40, src.trades); assertEquals(18, src.small); assertEquals(7, src.days)
        assertEquals("over the last 10 days, 18 of ORB's 40 Paper trades moved less than twice their own charges, on 7 days", src.say())
        // All opened 11:00-11:49: the time of day is learned too, as its own fact.
        assertEquals(SmallTrades.Band.MORNING.name, r.single { it.by == SmallTrades.By.TIME }.name)

        // Too few trades, too few days, too few small, too small a share: nothing.
        for (trips in listOf(many("ORB", 19, 19, 7), many("ORB", 40, 30, 4), many("ORB", 20, 7, 7), many("ORB", 40, 15, 7)))
            assertTrue(SmallTrades.learned(listOf(SmallTrades.Book("Paper", trips)), SmallTrades.Log(), now).isEmpty())
        // Trades older than the window, or that paid no charges, never count.
        val old = orb.map { it.copy(openedAt = it.openedAt.minusDays(10), closedAt = it.closedAt.minusDays(10)) }
        assertTrue(SmallTrades.learned(listOf(SmallTrades.Book("Paper", old)), SmallTrades.Log(), now).isEmpty())
        val free = orb.map { it.copy(charges = 0.0) }
        assertTrue(SmallTrades.learned(listOf(SmallTrades.Book("Paper", free)), SmallTrades.Log(), now).isEmpty())
    }

    @Test fun bySourceAndTimeOfDayEachBookApart() {
        val manual = many("Manual", 25, 20, 5, h = 9).map { it.copy(openedAt = it.openedAt.withMinute(20 + it.openedAt.minute % 30)) }
        val pine = many("Pine X", 25, 2, 5, h = 13)
        val r = SmallTrades.learned(listOf(SmallTrades.Book("Zerodha", manual + pine)), SmallTrades.Log(), now)
        assertEquals(listOf("Manual", "OPEN"), r.map { it.name }.sorted())
        assertTrue(r.none { it.name == "Pine X" || it.name == "MIDDAY" })
        assertTrue(r.first { it.name == "Manual" }.fact().startsWith("20 of your 25 manual Zerodha trades"))
        assertTrue(r.first { it.name == "OPEN" }.fact().contains("opened in the first 45 minutes (9:15 to 10:00)"))
    }

    @Test fun saidOnceThenForgottenOnTheUndo() {
        val books = listOf(SmallTrades.Book("Paper", many("ORB", 40, 18, 7)))
        var log = SmallTrades.Log()
        val first = SmallTrades.next(SmallTrades.learned(books, log, now), log)!!
        val line = SmallTrades.wrapLine(first)
        assertTrue(line.contains("moved less than twice their own charges") && line.contains(SmallTrades.UNDO))
        // Facts only: no advice to stop, cut, change or avoid anything.
        for (w in listOf("should", "stop orb", "avoid", "cut", "reduce", "fewer", "consider")) assertFalse(line.lowercase().contains(w), w)
        log = SmallTrades.told(log, first)
        val second = SmallTrades.next(SmallTrades.learned(books, log, now), log)!!
        assertTrue(second.key != first.key)
        log = SmallTrades.told(log, second)
        assertNull(SmallTrades.next(SmallTrades.learned(books, log, now), log))
        // The next day it is not said again.
        assertNull(SmallTrades.next(SmallTrades.learned(books, log, now.plusDays(1)), log))
        // "Stop mentioning my small trades": nothing before it counts, so nothing is learned until new trades come in.
        log = SmallTrades.reset(now)
        assertTrue(SmallTrades.learned(books, log, now.plusHours(1)).isEmpty())
        assertTrue(log.told.isEmpty())
    }

    @Test fun askedAndItsUndoNeverAct() {
        val which = listOf("what have you learned about my charges", "which trades move less than twice their charges",
            "which strategies make the most small trades", "who makes the most small trades", "where do my small trades come from",
            "what's my small trades record", "charges ke baare mein kya seekha", "chhote trades kaun karta hai", "what did you notice about my charges")
        val reset = listOf(SmallTrades.UNDO, "stop telling me about my small trades", "don't mention my small trades",
            "forget what you learned about my charges", "reset my small trades count", "chhote trades mat batao")
        for (q in which) assertEquals(SmallTrades.Request.WHICH, SmallTrades.asked(q), q)
        for (q in reset) assertEquals(SmallTrades.Request.RESET, SmallTrades.asked(q), q)
        for (q in which + reset) {
            val p = Ask.parse(q); assertNull(p.order, q); assertNull(p.command, q)
            assertFalse(Bundle.acts(q), q)
        }
        // Not its words: the charges themselves, why they are high, a strategy stopped, Boss's own notes.
        for (q in listOf("how much did i pay in charges this week", "why are my charges so high", "stop orb", "what have you learned about me",
            "forget my target", "my trades today", "how are my small caps doing", "stop all strategies"))
            assertNull(SmallTrades.asked(q), q)
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("stop orb").command?.kind)
    }

    @Test fun theLedgerShowsItWithItsUndoAndALockedPhoneHearsNone() {
        val i = Learnings.Inputs(smallBooks = listOf(SmallTrades.Book("Paper", many("ORB", 40, 18, 7))))
        val items = Learnings.items(i, now).filter { it.area == Learnings.Area.SMALL_TRADES }
        assertEquals(2, items.size)
        assertTrue(items.all { it.undo == SmallTrades.UNDO && it.personal })
        assertTrue(items.any { it.what.startsWith("18 of ORB's 40 Paper trades") })
        assertFalse(Learnings.say(items, Learnings.Ask.ALL, today, locked = true).contains("ORB"))
        // A fact of his record: "undo everything you learned this week" leaves it.
        assertTrue(Learnings.undo(i, now).empty)
    }

    @Test fun theAnswersSayTheThresholds() {
        val none = SmallTrades.say(emptyList())
        assertTrue(none.contains("${SmallTrades.MIN_TRADES} trades") && none.contains("${SmallTrades.MIN_DAYS} days") && none.contains(SmallTrades.ONLY_FACTS))
        val r = SmallTrades.learned(listOf(SmallTrades.Book("Paper", many("ORB", 40, 18, 7))), SmallTrades.Log(), now)
        assertTrue(SmallTrades.say(r).contains("18 of ORB's 40 Paper trades") && SmallTrades.say(r).contains(SmallTrades.UNDO))
        assertFalse(SmallTrades.RESET_LOCKED.contains("ORB"))
    }
}
