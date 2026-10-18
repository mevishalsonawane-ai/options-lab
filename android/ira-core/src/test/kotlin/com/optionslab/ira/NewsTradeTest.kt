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
        val undated = h("Bank Nifty surges to record high as banks rally")
        val up = (1..15).map { i -> bars.last().let { b -> Candle(b.t.plusMinutes(i.toLong()), b.c, b.c, b.c, b.c * (1 + 0.0002 * i)) } }
        val all = bars + up
        val s = Brain.read(History(Market.BANKNIFTY, all))!!
        val t = all.last().t.withHour(11).withMinute(0)
        fun dated(x: Headline) = x.copy(at = t.atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant().minusSeconds(120))
        val good = dated(undated)
        assertNull(NewsTrade.idea(undated, s, all, t, TradeCheck.Level.GO, 0), "undated news is never fresh")
        val i = NewsTrade.idea(good, s, all, t, TradeCheck.Level.GO, 0)
        assertNotNull(i, "trend ${s.trend(15)?.up}")
        assertEquals(Market.BANKNIFTY, i.market); assertTrue(i.call)
        assertTrue(i.why.contains("the candles agree"))
        assertNull(NewsTrade.idea(good, s, all, t, TradeCheck.Level.STOP, 0), "the trade check says stop")
        assertNull(NewsTrade.idea(good, s, all, t, TradeCheck.Level.GO, 2), "two a day at most")
        assertNull(NewsTrade.idea(good, s, all, t.withHour(15), TradeCheck.Level.GO, 0), "past 14:30")
        assertNull(NewsTrade.idea(dated(h("Bank Nifty slumps as banks tumble")), s, all, t, TradeCheck.Level.GO, 0), "candles disagree with bad news")
        assertNull(NewsTrade.idea(dated(h("Banks see steady day")), s, all, t, TradeCheck.Level.GO, 0), "not strong")
    }

    // 18 Oct, review: after a stalled feed the "last 15 minutes" were hours old. Both ends of the window must be recent.
    @Test fun staleCandlesMakeNoIdea() {
        val day = bars.last().t.toLocalDate()
        val morning = bars.filter { it.t.isBefore(day.atTime(10, 45)) }
        val up = (1..15).map { i -> morning.last().let { b -> Candle(b.t.plusMinutes(i.toLong()), b.c, b.c, b.c, b.c * (1 + 0.0002 * i)) } }
        val all = morning + up                                  // the newest candle at 10:59
        val s = Brain.read(History(Market.BANKNIFTY, all))!!
        val lastAt = all.last().t
        fun dated(at: LocalDateTime) = h("Bank Nifty surges to record high as banks rally")
            .copy(at = at.atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant().minusSeconds(120))
        fun idea(now: LocalDateTime, b: List<Candle> = all) = NewsTrade.idea(dated(now), s, b, now, TradeCheck.Level.GO, 0)
        // Fresh: the candles end just now (and up to 5 minutes ago).
        assertNotNull(idea(lastAt.plusMinutes(1)), "trend ${s.trend(15)?.up}")
        assertNotNull(idea(lastAt.plusMinutes(NewsTrade.MAX_BAR_AGE_MINUTES)), "5 minutes old is still recent")
        // Stale: the feed stopped and strong news came later - no idea, the old move is never put as "the last 15 minutes".
        assertNull(idea(lastAt.plusMinutes(NewsTrade.MAX_BAR_AGE_MINUTES + 1)), "6 minutes old")
        assertNull(idea(lastAt.plusHours(2)), "two hours old, the same day")
        // A long gap inside the window: the newest candle is recent, but the one 15 back is 30 minutes old.
        val gapped = all.dropLast(15).filter { it.t.isBefore(lastAt.minusMinutes(30)) } + all.takeLast(1)
        assertTrue(gapped.size >= 16)
        assertNull(idea(lastAt.plusMinutes(1), gapped), "a gap in the feed")
        // Edge: too few candles still makes none.
        assertNull(idea(lastAt.plusMinutes(1), all.takeLast(15)))
    }
}
