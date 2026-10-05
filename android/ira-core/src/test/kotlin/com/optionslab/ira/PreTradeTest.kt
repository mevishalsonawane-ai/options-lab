package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreTradeTest {
    private val today = LocalDate.of(2026, 10, 5)
    private fun trip(day: LocalDate, h: Int, m: Int, held: Long, net: Double) =
        Insights.Trip("NIFTY25O0725000CE", day.atTime(h, m), day.atTime(h, m).plusMinutes(held), net, "Manual")
    private fun at(h: Int, m: Int): LocalDateTime = today.atTime(h, m)

    @Test fun nothingToSayIsNoNote() {
        assertTrue(PreTrade.reminders(emptyList(), at(11, 0)).isEmpty())
        assertNull(PreTrade.say(emptyList()))
        // A win just now: nothing.
        assertTrue(PreTrade.reminders(listOf(trip(today, 10, 0, 50, 300.0)), at(10, 55)).isEmpty())
    }

    @Test fun justAfterALossWithHisOwnRecord() {
        // Past days: right after a loss he lost 5 of 6; otherwise he wins.
        val past = ArrayList<Insights.Trip>()
        for (d in 1..6) {
            val day = today.minusDays(d.toLong() + 2)
            past += trip(day, 10, 0, 30, -200.0)                       // a loss closing 10:30
            past += trip(day, 10, 35, 20, if (d == 1) 100.0 else -150.0) // taken 5 minutes after it
            past += trip(day, 12, 0, 30, 400.0)
            past += trip(day, 13, 0, 30, 400.0)
        }
        val lost = trip(today, 11, 0, 20, -250.0)                     // closed 11:20
        val r = PreTrade.reminders(past + lost, at(11, 26))
        assertEquals(1, r.size)
        assertTrue(r[0].startsWith("Your last trade lost, 6 minutes ago."))
        assertTrue(r[0].contains("won 1 of 6 (16%)"))
        // No amounts: the note may sit on any screen.
        assertFalse(Overheard.holdsAccount(PreTrade.say(r)!!.replace("NIFTY25O0725000CE", "")))
        assertFalse(PreTrade.say(r)!!.contains("Rs"))
        assertTrue(PreTrade.say(r)!!.startsWith("Boss, a moment before you send this."))
        assertTrue(PreTrade.say(r)!!.endsWith("Your call - I haven't changed anything in the order."))
        // Long after the loss: nothing.
        assertTrue(PreTrade.reminders(past + lost, at(12, 30)).isEmpty())
    }

    @Test fun twoLossesInARow() {
        val a = trip(today, 10, 0, 10, -100.0); val b = trip(today, 10, 20, 10, -100.0)   // closes 10:30
        assertEquals(listOf("Your last two trades today both lost, the last one just now."), PreTrade.reminders(listOf(a, b), at(10, 30)))
        // Later than the after-loss window: the streak is still said.
        assertEquals(listOf("Your last two trades today both lost."), PreTrade.reminders(listOf(a, b), at(11, 30)))
        // A short record is not quoted.
        assertFalse(PreTrade.reminders(listOf(a, b), at(10, 35))[0].contains("won"))
    }

    @Test fun pastHisUsualDayAndHowBusyDaysEnded() {
        val past = ArrayList<Insights.Trip>()
        for (d in 1..6) {
            val day = today.minusDays(d.toLong() + 2)
            val n = if (d <= 3) 6 else 3                          // three busy days, three usual ones
            for (i in 0 until n) past += trip(day, 10 + i, 0, 20, if (d <= 3) -100.0 else 200.0)
        }
        assertEquals(3, PreTrade.usualDay(past, today))
        val mine = (0 until 3).map { trip(today, 10 + it, 0, 20, 50.0) }
        val r = PreTrade.reminders(past + mine, at(14, 0))
        assertEquals(listOf("This would be trade number 4 today; on a usual day you take 3 trades. Your days with more than that ended in the red 3 times out of 3."), r)
        // Under the usual: nothing.
        assertTrue(PreTrade.reminders(past + mine.take(2), at(14, 0)).isEmpty())
        // Too few past days: no "usual".
        assertNull(PreTrade.usualDay(past.filter { it.openedAt.toLocalDate().isAfter(today.minusDays(6)) }, today))
    }

    @Test fun hisOwnTradeGoalComesFirst() {
        val mine = (0 until 3).map { trip(today, 10 + it, 0, 20, 50.0) }
        assertEquals(listOf("Your own goal is no more than 3 trades a day; this would be number 4."), PreTrade.reminders(mine, at(14, 0), maxTrades = 3))
        assertTrue(PreTrade.reminders(mine, at(14, 0), maxTrades = 5).isEmpty())
    }

    @Test fun firstFiveMinutesAndHisRules() {
        val r = PreTrade.reminders(emptyList(), at(9, 17))
        assertEquals(listOf("It's the first five minutes of the session, when prices jump around most."), r)
        assertTrue(PreTrade.reminders(emptyList(), at(9, 20)).isEmpty())
        val rules = listOf("I don't trade BankNifty")
        assertEquals(listOf("You asked me to remember \"I don't trade BankNifty\"."),
            PreTrade.reminders(emptyList(), at(11, 0), rules, Market.BANKNIFTY))
        assertTrue(PreTrade.reminders(emptyList(), at(11, 0), rules, Market.NIFTY).isEmpty())
        // No market known: the rule cannot be matched, nothing said.
        assertTrue(PreTrade.reminders(emptyList(), at(11, 0), rules, null).isEmpty())
    }

    @Test fun theOrdersIndex() {
        assertEquals(Market.BANKNIFTY, PreTrade.marketOf("BANKNIFTY25O0855000PE"))
        assertEquals(Market.NIFTY, PreTrade.marketOf("NFO:NIFTY25O0725000CE"))
        assertEquals(Market.FINNIFTY, PreTrade.marketOf("FINNIFTY25OCT24000CE"))
        assertEquals(Market.SENSEX, PreTrade.marketOf("BFO:SENSEX25O0981000PE"))
        assertNull(PreTrade.marketOf("RELIANCE"))
        assertNull(PreTrade.marketOf("MIDCPNIFTY25OCT13000CE"))
    }
}
