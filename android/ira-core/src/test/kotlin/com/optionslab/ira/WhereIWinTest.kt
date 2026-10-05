package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhereIWinTest {
    private val today = LocalDate.of(2026, 10, 9)

    private fun t(sym: String, dir: Int, net: Double, owner: String = "Manual", day: LocalDate = today) =
        WhereIWin.Trade(sym, dir, day.atTime(11, 0), net, owner)

    @Test fun asksAreRead() {
        val yes = mapOf(
            "where do I make my money?" to WhereIWin.Cut.ALL, "where did I lose most of my money this month" to WhereIWin.Cut.ALL,
            "what kind of trades work for me" to WhereIWin.Cut.ALL, "which trades make me money" to WhereIWin.Cut.ALL,
            "am I better at calls or puts?" to WhereIWin.Cut.KIND, "my calls vs my puts" to WhereIWin.Cut.KIND,
            "do I make more buying or selling options?" to WhereIWin.Cut.SIDE, "am I any good at selling" to WhereIWin.Cut.SIDE,
            "which index do I make money on?" to WhereIWin.Cut.INDEX, "which index am I best at" to WhereIWin.Cut.INDEX,
            "my best index" to WhereIWin.Cut.INDEX, "am I better at banknifty or nifty" to WhereIWin.Cut.INDEX,
            "call mein zyada kamata hoon ya put mein" to WhereIWin.Cut.KIND, "kis index mein paisa banta hai" to WhereIWin.Cut.INDEX,
            "buying mein zyada kamata hoon ya selling mein" to WhereIWin.Cut.SIDE, "mujhe kaun se trade suit karte hain" to WhereIWin.Cut.ALL,
        )
        for ((s, c) in yes) assertEquals(c, WhereIWin.asked(s), s)
        for (s in listOf("should I buy calls or puts", "what is the difference between calls and puts", "am i net long or short",
            "which index is strongest", "which index do I usually trade", "my profit factor", "how are my bots doing",
            "what's my average win", "how was my month", "where is nifty", "buy nifty 25000 call", "what's my theta",
            "which of my positions is making money", "call kharidu ya put", "which strategy makes the most money",
            "how did my thursday expiry trades do this month", "am i long or short theta"))
            assertNull(WhereIWin.asked(s), s)
    }

    @Test fun groupsByIndexKindAndSide() {
        assertEquals("BankNifty", WhereIWin.index("BANKNIFTY26OCT54000CE"))
        assertEquals("Nifty", WhereIWin.index("NIFTY26O1325000PE"))
        assertEquals("Sensex", WhereIWin.index("SENSEX26OCT82000CE"))
        assertEquals("others", WhereIWin.index("RELIANCE"))
        assertEquals("calls", WhereIWin.kind("NIFTY26O1325000CE"))
        assertEquals("puts", WhereIWin.kind("NIFTY26O1325000PE"))
        assertEquals("futures", WhereIWin.kind("NIFTY26OCTFUT"))
        assertEquals("others", WhereIWin.kind("RELIANCE"))
    }

    @Test fun splitsHisOwnTradesWithTheAskedPartFirst() {
        val trades = listOf(
            t("NIFTY26O1325000CE", 1, 500.0), t("NIFTY26O1325100CE", 1, 300.0), t("NIFTY26O1325200CE", 1, -100.0),
            t("BANKNIFTY26OCT54000PE", 1, -400.0), t("BANKNIFTY26OCT54100PE", 1, -200.0), t("BANKNIFTY26OCT54200PE", -1, 100.0),
            t("NIFTY26O1324000PE", -1, 50.0, owner = "ORB"),
        )
        val ls = WhereIWin.lines("Paper", trades, MyNumbers.Span.ALL, today, WhereIWin.Cut.KIND)
        assertTrue(ls[0].startsWith("Paper, your own trades on record"), ls[0])
        assertTrue(ls[0].contains("6 trades, 3 won, net +Rs 200"), ls[0])
        assertTrue(ls[1].startsWith("Calls, puts and futures: calls 3 trades, 2 won, +Rs 700 (+Rs 233 a trade); puts 3 trades, 1 won, -Rs 500"), ls[1])
        assertTrue(ls[1].contains("A trade made most on calls"), ls[1])
        assertTrue(ls[2].startsWith("Bought first against sold first: "), ls[2])
        assertTrue(ls[2].contains("sold first 1 trade, 1 won, +Rs 100 (too few to judge)"), ls[2])
        assertTrue(ls[3].startsWith("By index: Nifty 3 trades"), ls[3])
        assertFalse(ls.any { it.startsWith("Options, side and kind together") }, "only one of the four has 3 trades")
        assertFalse(ls.any { it.contains("ORB") })
    }

    @Test fun theFourTogetherWhenTwoHaveEnough() {
        val trades = listOf(t("NIFTY26O1325000CE", 1, 100.0), t("NIFTY26O1325000CE", 1, 100.0), t("NIFTY26O1325000CE", 1, -50.0),
            t("NIFTY26O1324000PE", -1, 80.0), t("NIFTY26O1324000PE", -1, 80.0), t("NIFTY26O1324000PE", -1, 80.0))
        val ls = WhereIWin.lines("Paper", trades, MyNumbers.Span.ALL, today, WhereIWin.Cut.ALL)
        assertTrue(ls.last().startsWith("Options, side and kind together: sold puts 3 trades, 3 won, +Rs 240 (+Rs 80 a trade); bought calls"), ls.last())
        assertTrue(ls.none { it.startsWith("By index") }, "one index, not asked")
    }

    @Test fun theBotsOnlyWhenHeHasNoneAndSaySo() {
        val ls = WhereIWin.lines("Zerodha", listOf(t("NIFTY26O1325000CE", 1, 100.0, owner = "ORB")), MyNumbers.Span.ALL, today, WhereIWin.Cut.ALL)
        assertTrue(ls[0].contains("you have none of your own, so these are the bots'"), ls[0])
        assertEquals(1, ls.size)
    }

    @Test fun oneGroupAskedIsSaidAndNothingInSpanIsSaid() {
        val one = WhereIWin.lines("Paper", listOf(t("NIFTY26O1325000CE", 1, 100.0)), MyNumbers.Span.ALL, today, WhereIWin.Cut.INDEX)
        assertTrue(one[1].contains("all 1 trade were Nifty, so there is nothing to set beside them"), one[1])
        val none = WhereIWin.lines("Paper", listOf(t("NIFTY26O1325000CE", 1, 100.0, day = today.minusDays(40))), MyNumbers.Span.WEEK, today, WhereIWin.Cut.ALL)
        assertEquals(listOf("Paper: no closed trades this week, so nothing to split."), none)
    }
}
