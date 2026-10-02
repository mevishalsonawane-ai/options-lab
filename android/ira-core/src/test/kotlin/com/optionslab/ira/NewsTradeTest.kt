package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsTradeTest {
    private val bars = Fixtures.indexDays(25, drift = 0.6, seed = 2)
    private val snap = Brain.read(History(Market.BANKNIFTY, bars))!!
    private val now = bars.last().t
    private fun h(t: String) = News.parse("<item><title>$t</title><link>https://e.com/1</link></item>", "ET").single()

    @Test fun strongNewsAndAgreeingCandlesMakeAnIdea() {
        val good = h("Bank Nifty surges to record high as banks rally")
        val up = (1..15).map { i -> bars.last().let { b -> Candle(b.t.plusMinutes(i.toLong()), b.c, b.c, b.c, b.c * (1 + 0.0002 * i)) } }
        val all = bars + up
        val s = Brain.read(History(Market.BANKNIFTY, all))!!
        val t = all.last().t.withHour(11).withMinute(0)
        val i = NewsTrade.idea(good, s, all, t, TradeCheck.Level.GO, 0)
        assertNotNull(i, "trend ${s.trend(15)?.up}")
        assertEquals(Market.BANKNIFTY, i.market); assertTrue(i.call)
        assertTrue(i.why.contains("the candles agree"))
        assertNull(NewsTrade.idea(good, s, all, t, TradeCheck.Level.STOP, 0), "the trade check says stop")
        assertNull(NewsTrade.idea(good, s, all, t, TradeCheck.Level.GO, 2), "two a day at most")
        assertNull(NewsTrade.idea(good, s, all, t.withHour(15), TradeCheck.Level.GO, 0), "past 14:30")
        assertNull(NewsTrade.idea(h("Bank Nifty slumps as banks tumble"), s, all, t, TradeCheck.Level.GO, 0), "candles disagree with bad news")
        assertNull(NewsTrade.idea(h("Banks see steady day"), s, all, t, TradeCheck.Level.GO, 0), "not strong")
    }
}
