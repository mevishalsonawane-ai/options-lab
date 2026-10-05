package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AfterLossTest {
    private val today = LocalDate.of(2026, 10, 9)

    private fun t(day: LocalDate, h: Int, m: Int, mins: Long, net: Double, owner: String = "Manual") =
        TradesADay.Trade(day.atTime(h, m), day.atTime(h, m).plusMinutes(mins), net, owner)

    @Test fun asksAreRead() {
        val yes = mapOf(
            "how do I trade after a loss?" to AfterLoss.Side.LOSS, "do I revenge trade?" to AfterLoss.Side.LOSS,
            "am I a revenge trader" to AfterLoss.Side.LOSS, "do I chase my losses" to AfterLoss.Side.LOSS,
            "how does my next trade do after a losing trade" to AfterLoss.Side.LOSS, "what happens after I take a loss" to AfterLoss.Side.LOSS,
            "do I lose more after a loss" to AfterLoss.Side.LOSS, "my trades after a loss" to AfterLoss.Side.LOSS,
            "how did I do after a loss last month" to AfterLoss.Side.LOSS, "loss ke baad mera agla trade kaisa jaata hai" to AfterLoss.Side.LOSS,
            "kya main revenge trade karta hoon" to AfterLoss.Side.LOSS, "jarvis do I revenge trade" to AfterLoss.Side.LOSS,
            "do I get careless after a win" to AfterLoss.Side.WIN, "how do I trade after a winning trade" to AfterLoss.Side.WIN,
            "do my trades do worse after a win" to AfterLoss.Side.WIN,
        )
        for ((s, p) in yes) assertEquals(p, AfterLoss.asked(s), s)
        for (s in listOf("why did my last trade lose", "should I stop trading after a loss", "remind me not to trade after a loss",
            "how do my bots do after a loss", "how did I do today after the loss", "how does nifty do after a losing day",
            "what's my win rate", "do I do better when I trade less", "do i lose after my second trade", "my profit factor",
            "how do you trade after a loss", "buy nifty 25000 call", "my losing streak", "how much did I lose"))
            assertNull(AfterLoss.asked(s), s)
    }

    @Test fun placesEachTradeByTheOneBefore() {
        val d = (1..6).map { today.minusDays(it.toLong()) }
        val trades = ArrayList<TradesADay.Trade>()
        // Each day: a loss at 10:00 (closed 10:10), then a quick loser at 10:15, then a win at 11:00, then a trade after the win.
        for (day in d) {
            trades += t(day, 10, 0, 10, -100.0)
            trades += t(day, 10, 15, 10, -50.0)
            trades += t(day, 11, 0, 10, 200.0)
            trades += t(day, 11, 30, 10, 100.0)
        }
        val ps = AfterLoss.placed(TradesADay.grouped(trades))
        assertEquals(24, ps.size)
        assertEquals(6, ps.count { it.before == null })
        val lines = AfterLoss.lines("Paper", trades, MyNumbers.Span.ALL, today, AfterLoss.Side.LOSS)
        // After a loss: the 10:15 trade (lost) and the 11:00 (after the 10:15 loss, won) - 12 trades, 6 won, +Rs 75 a trade.
        assertTrue(lines[0].startsWith("Paper, your own trades on record: after a losing trade you won 6 of 12 (50%), +Rs 75 a trade; after a winning one you won 6 of 6 (100%), +Rs 100 a trade."), lines[0])
        assertTrue(lines[1].contains("24 closed trades"), lines[1])
        assertTrue(lines[1].contains("a trade made less after a loss"), lines[1])
        assertTrue(lines[1].contains("Is that gap more than chance?"), lines[1])
        assertTrue(lines[2].startsWith("After a loss: 6 trades within 15 minutes of it, 0 won, -Rs 300"), lines[2])
        assertTrue(lines[2].contains("6 trades after two losses in a row, 6 won, +Rs 1,200"), lines[2])
        assertTrue(lines[2].contains("5 minutes after a loss in the middle case and 20 minutes after a win"), lines[2])
        assertTrue(lines[3].startsWith("For reference, a day's first trades"), lines[3])
        val winFirst = AfterLoss.lines("Paper", trades, MyNumbers.Span.ALL, today, AfterLoss.Side.WIN)
        assertTrue(winFirst[0].contains("after a winning trade you won 6 of 6 (100%), +Rs 100 a trade; after a losing one"), winFirst[0])
    }

    @Test fun aTradeCarriedOvernightCountsAsBefore() {
        val d1 = today.minusDays(2); val d2 = today.minusDays(1)
        // Opened the day before at 15:00, carried overnight, closed at 09:30 for a loss; the 10:00 trade comes after it.
        val carried = TradesADay.Trade(d1.atTime(15, 0), d2.atTime(9, 30), -300.0, "Manual")
        val next = t(d2, 10, 0, 10, 50.0)
        val ps = AfterLoss.placed(listOf(carried, next))
        val p = ps.single { it.trade === next }
        assertEquals(carried, p.before)
        // The carried trade itself: nothing closed on its opening day before it.
        assertNull(ps.single { it.trade === carried }.before)
        // Closed after the next trade opened: never "before".
        val late = TradesADay.Trade(d1.atTime(15, 0), d2.atTime(10, 5), -300.0, "Manual")
        assertNull(AfterLoss.placed(listOf(late, next)).single { it.trade === next }.before)
    }

    @Test fun botsAndTooFewAndNothingSaid() {
        val d1 = today.minusDays(1)
        val bots = listOf(t(d1, 10, 0, 10, -100.0, "ORB"), t(d1, 10, 30, 10, 50.0, "ORB"))
        val l = AfterLoss.lines("Paper", bots, MyNumbers.Span.ALL, today, AfterLoss.Side.LOSS)
        assertTrue(l[0].contains("the bots'"), l[0])
        assertTrue(l[0].contains("after a winning one you never opened a trade the same day") || l[0].contains("you never opened a trade the same day after a winning one"), l[0])
        assertTrue(l[1].contains("not set against each other yet"), l[1])
        assertEquals(listOf("Zerodha: no closed trades this week, so nothing to say about after a loss."),
            AfterLoss.lines("Zerodha", emptyList(), MyNumbers.Span.WEEK, today, AfterLoss.Side.LOSS))
        assertTrue(AfterLoss.CLOSING.contains("not a forecast"))
    }
}
