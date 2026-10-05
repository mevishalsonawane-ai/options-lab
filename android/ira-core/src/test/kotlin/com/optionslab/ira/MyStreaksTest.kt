package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MyStreaksTest {
    private val today = LocalDate.of(2026, 10, 5)            // a Monday

    private fun t(date: LocalDate, net: Double, hour: Int = 10, owner: String = "Manual") =
        LocalDateTime.of(date, LocalTime.of(hour, 0)).let { Insights.Trip("NIFTY24500CE", it, it.plusMinutes(20), net, owner) }

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    @Test fun asked() {
        for (q in listOf("am I on a winning streak?", "how many green days in a row have I had", "my losing streak", "Jarvis, what's my streak",
                "have I lost three days in a row", "how many trades in a row did I lose", "what's my best weekday", "which day of the week do I lose most",
                "my worst day of the week", "which day do I make the most", "my weekday breakdown", "how many consecutive losses have I had",
                "lagatar kitne din loss hua mera", "mera konsa din best hai", "my longest winning streak", "am I on a losing run"))
            assertTrue(MyStreaks.asked(q), q)
        for (q in listOf("how many days in a row has Nifty risen", "nifty streak", "BankNifty losing streak", "how was my month", "what is my p&l",
                "is the market on a winning streak", "best time to trade", "what's my best trade", "how are my bots doing", "how did I do this week",
                "what is a streak", "are you on a streak"))
            assertFalse(MyStreaks.asked(q), q)
        assertTrue(MyStreaks.weekdayAsked("what's my best weekday"))
        assertFalse(MyStreaks.weekdayAsked("am I on a winning streak"))
    }

    @Test fun routedToTheAccount() {
        for (q in listOf("am I on a winning streak?", "what's my best weekday", "how many green days in a row have I had", "my losing streak"))
            assertEquals(setOf(Section.STREAKS), AppAnswers.sections(q), q)
        assertTrue(Topic.ACCOUNT in Ask.parse("am I on a winning streak?").topics)
        assertTrue(Topic.ACCOUNT in Ask.parse("my losing streak").topics)
    }

    @Test fun runs() {
        val days = listOf(MyStreaks.Day(d(9, 28), 100.0, 1), MyStreaks.Day(d(9, 29), 50.0, 1), MyStreaks.Day(d(9, 30), 0.0, 1),
            MyStreaks.Day(d(10, 1), -20.0, 1), MyStreaks.Day(d(10, 2), -30.0, 2))
        val r = MyStreaks.runs(days)
        assertEquals(listOf(true, false), r.map { it.green })
        assertEquals(listOf(2, 2), r.map { it.days.size })
        assertEquals(-50.0, MyStreaks.current(days)!!.net, 0.01)
        // A flat last day: no run now.
        assertNull(MyStreaks.current(days + MyStreaks.Day(d(10, 5), 0.0, 1)))
    }

    @Test fun tradeRun() {
        val trips = listOf(t(d(10, 1), 50.0), t(d(10, 2), -10.0, 10), t(d(10, 2), -20.0, 11), t(d(10, 5), -5.0))
        assertEquals(false to 3, MyStreaks.tradeRun(trips))
        assertEquals(true to 1, MyStreaks.tradeRun(trips + t(d(10, 5), 40.0, 12)))
        assertNull(MyStreaks.tradeRun(emptyList()))
    }

    @Test fun lines() {
        // Three green days to Friday 2 Oct, red before; Mondays lose, Wednesdays win.
        val trips = listOf(
            t(d(9, 7), -300.0), t(d(9, 9), 400.0), t(d(9, 14), -200.0), t(d(9, 16), 500.0), t(d(9, 21), -100.0), t(d(9, 23), 300.0),
            t(d(9, 28), -50.0), t(d(9, 30), 200.0), t(d(10, 1), 150.0), t(d(10, 2), 100.0, 10), t(d(10, 2), 50.0, 11),
            t(d(10, 2), 9999.0, 12, owner = "ORB 5"),
        )
        val l = MyStreaks.lines("Paper", trips, today, sessionOpen = false)
        val all = l.joinToString(" ")
        assertTrue(l[0].startsWith("Paper, your own trades: 10 days with closed trades on record (Mon 7 Sep to Fri 2 Oct)"), l[0])
        assertTrue(all.contains("You're on 3 green days in a row: Wed 30 Sep to Fri 2 Oct, +Rs 500 together."), all)
        assertTrue(all.contains("Longest runs on record: green 3 days (Wed 30 Sep to Fri 2 Oct, +Rs 500); red 1 day (Mon 28 Sep, -Rs 50)."), all)
        assertTrue(all.contains("your last 4 trades were wins in a row"), all)
        assertTrue(all.contains("By weekday, your best is Wednesday (4 of 4 green, +Rs 1,400 net, +Rs 350 a day) and your worst Monday (0 of 4 green, -Rs 650 net, -Rs 163 a day)."), all)
        assertTrue(all.contains("Too few days to count yet: Thursday, Friday."), all)
        assertFalse(all.contains("9,999"), all)       // the bot's trade is not his
        // Asked for the weekdays: they lead.
        assertTrue(MyStreaks.lines("Paper", trips, today, false, weekdayFirst = true)[1].startsWith("By weekday"))
    }

    @Test fun todaySoFarAndNoOwnTrades() {
        val trips = listOf(t(d(10, 1), -100.0), t(d(10, 2), -40.0), t(today, -10.0))
        val all = MyStreaks.lines("Paper", trips, today, sessionOpen = true).joinToString(" ")
        assertTrue(all.contains("You're on 3 red days in a row, counting today so far: Thu 1 Oct to Mon 5 Oct, -Rs 150 together."), all)
        assertTrue(all.contains("Not enough days yet to compare weekdays"), all)
        val bots = MyStreaks.lines("Zerodha", listOf(t(d(10, 2), 80.0, owner = "ORB 5")), today, false).joinToString(" ")
        assertTrue(bots.contains("you have none of your own, so these are the bots'"), bots)
        assertTrue(bots.contains("Your last trading day, Fri 2 Oct, was green (+Rs 80)."), bots)
        assertEquals(listOf("Paper: no closed trades on record yet, so no streaks to tell."), MyStreaks.lines("Paper", emptyList(), today, false))
        assertFalse(MyStreaks.CLOSING.contains("buy") || MyStreaks.CLOSING.contains("sell"))
    }
}
