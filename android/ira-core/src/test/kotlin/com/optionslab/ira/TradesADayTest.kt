package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TradesADayTest {
    private val today = LocalDate.of(2026, 10, 9)

    private fun t(day: LocalDate, hour: Int, net: Double, owner: String = "Manual") =
        TradesADay.Trade(day.atTime(hour, 0), day.atTime(hour, 30), net, owner)

    @Test fun asksAreRead() {
        val yes = mapOf(
            "do I do better when I trade less?" to TradesADay.Part.DAYS, "do I lose more on days I trade a lot?" to TradesADay.Part.DAYS,
            "do I make more money when I take fewer trades" to TradesADay.Part.DAYS, "does trading more hurt me" to TradesADay.Part.DAYS,
            "how many trades a day work best for me?" to TradesADay.Part.DAYS, "what's my best number of trades a day" to TradesADay.Part.DAYS,
            "my results by number of trades" to TradesADay.Part.DAYS, "my quiet days vs my busy days" to TradesADay.Part.DAYS,
            "do I do better when I trade less this month" to TradesADay.Part.DAYS,
            "how does my first trade of the day do?" to TradesADay.Part.PLACE, "is my first trade my best" to TradesADay.Part.PLACE,
            "do my later trades lose?" to TradesADay.Part.PLACE, "do I lose after my second trade" to TradesADay.Part.PLACE,
            "my first trade vs my later trades" to TradesADay.Part.PLACE, "din ka pehla trade kaisa jaata hai" to TradesADay.Part.PLACE,
            "jarvis do I do better when I trade less" to TradesADay.Part.DAYS,
        )
        for ((s, p) in yes) assertEquals(p, TradesADay.asked(s), s)
        for (s in listOf("how many trades did I take today", "how many trades do I have left", "am I overtrading",
            "how many trades should I take a day", "do my bots do better when they trade less", "where do I make my money",
            "my profit factor", "how many trades a day is my limit", "what was my first trade today", "why did my last trade lose", "how are my bots doing",
            "do you do better when you trade less", "buy nifty 25000 call", "what's my win rate"))
            assertNull(TradesADay.asked(s), s)
    }

    @Test fun groupsDaysAndPlaces() {
        val d = (1..9).map { today.minusDays(it.toLong()) }
        val trades = listOf(
            // three 1-trade days, all green
            t(d[0], 10, 300.0), t(d[1], 10, 200.0), t(d[2], 10, 100.0),
            // three 4-trade days, red
            t(d[3], 10, 200.0), t(d[3], 11, -300.0), t(d[3], 12, -200.0), t(d[3], 13, -100.0),
            t(d[4], 10, 100.0), t(d[4], 11, -100.0), t(d[4], 12, -200.0), t(d[4], 13, -300.0),
            t(d[5], 10, 50.0), t(d[5], 11, -50.0), t(d[5], 12, -100.0), t(d[5], 13, -100.0),
        )
        val days = TradesADay.dayGroups(trades)
        assertEquals(listOf("1-trade days", "4-5-trade days"), days.map { it.name })
        assertEquals(3, days[0].green)
        assertEquals(-1100.0 / 3, days[1].average, 1e-9)
        val places = TradesADay.placeGroups(trades)
        assertEquals(listOf("1st trades", "2nd trades", "3rd trades", "4th and later"), places.map { it.name })
        assertEquals(6, places[0].count)
        assertEquals(950.0, places[0].net, 1e-9)

        val lines = TradesADay.lines("Paper", trades, MyNumbers.Span.ALL, today, TradesADay.Part.DAYS)
        assertTrue(lines[0].startsWith("Paper, your own trades on record"), lines[0])
        assertTrue(lines[0].contains("15 closed trades over 6 trading days"), lines[0])
        assertTrue(lines[1].startsWith("By trades in a day:"), lines[1])
        assertTrue(lines[1].contains("A day made most on your 1-trade days (+Rs 200, over 3 days) and least on your 4-5-trade days (-Rs 367, over 3 days)"), lines[1])
        assertTrue(lines[2].startsWith("By place in the day:"), lines[2])
        val placeFirst = TradesADay.lines("Paper", trades, MyNumbers.Span.ALL, today, TradesADay.Part.PLACE)
        assertTrue(placeFirst[1].startsWith("By place in the day:"), placeFirst[1])
        assertTrue(placeFirst[1].contains("most as one of your 1st trades"), placeFirst[1])
    }

    @Test fun ownTradesFirstAndTooFewSaid() {
        val d1 = today.minusDays(1)
        val bots = listOf(t(d1, 10, 100.0, "ORB"), t(d1, 11, -50.0, "ORB"))
        val l = TradesADay.lines("Paper", bots, MyNumbers.Span.ALL, today, TradesADay.Part.DAYS)
        assertTrue(l[0].contains("the bots'"), l[0])
        assertTrue(l[1].contains("every day on record was one of your 2-trade days"), l[1])
        assertTrue(l[2].contains("too few to judge"), l[2])
        val mixed = bots + t(d1, 12, 70.0)
        val m = TradesADay.lines("Paper", mixed, MyNumbers.Span.ALL, today, TradesADay.Part.PLACE)
        assertTrue(m[0].contains("1 closed trade over 1 trading day"), m[0])
        assertTrue(m[1].contains("every trade was a day's first"), m[1])
        assertEquals(listOf("Zerodha: no closed trades this week, so nothing to count by day."),
            TradesADay.lines("Zerodha", emptyList(), MyNumbers.Span.WEEK, today, TradesADay.Part.DAYS))
        assertTrue(TradesADay.CLOSING.contains("not a forecast"))
    }
}
