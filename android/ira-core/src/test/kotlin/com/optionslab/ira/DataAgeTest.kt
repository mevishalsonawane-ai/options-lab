package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataAgeTest {
    private val now = LocalDateTime.of(2026, 10, 5, 11, 0, 0)
    private val answer = "Nifty is at 24,612.40, up 0.45% today. It is 120.50 points above the open. The nearest level above is 24,700.00 and below 24,500.00."

    private fun prices(ageSec: Long, live: Boolean = true, tried: Boolean = false) =
        DataAge.prices("Nifty", now.minusSeconds(ageSec), now.minusSeconds(ageSec), now, live, tried)!!

    @Test fun priceAgeIsTheEarlierOfFetchAndBarEnd() {
        // Fetched 10 seconds ago, but the last bar is from 10:50 (ended 10:51): the price is 9 minutes old.
        val c = DataAge.prices("Nifty", now.minusSeconds(10), LocalDateTime.of(2026, 10, 5, 10, 50), now, true)!!
        assertEquals(540, c.ageSec); assertEquals(DataAge.Level.STALE, c.level)
        // The forming bar ends after the fetch: the fetch decides.
        val f = DataAge.prices("Nifty", now.minusSeconds(30), LocalDateTime.of(2026, 10, 5, 10, 59), now, true)!!
        assertEquals(30, f.ageSec); assertEquals(DataAge.Level.FRESH, f.level)
        assertNull(DataAge.prices("Nifty", null, null, now, true))
        assertEquals(60, DataAge.prices("Nifty", null, now.minusMinutes(2), now, true)!!.ageSec)
    }

    @Test fun levelsByThreshold() {
        assertEquals(DataAge.Level.FRESH, prices(89).level)
        assertEquals(DataAge.Level.OLD, prices(90).level)
        assertEquals(DataAge.Level.OLD, prices(299).level)
        assertEquals(DataAge.Level.STALE, prices(300).level)
        // A closed market's prices are never "old".
        assertEquals(DataAge.Level.FRESH, prices(5000, live = false).level)
    }

    @Test fun freshAnswersAreUntouched() {
        val d = DataAge.dress(answer, listOf(prices(20)), now)
        assertEquals(answer, d.text); assertNull(d.note); assertFalse(d.withheld)
        assertEquals(answer, DataAge.dress(answer, emptyList(), now).text)
    }

    @Test fun oldPricesAreSaidFirstAndMarked() {
        val d = DataAge.dress(answer, listOf(prices(95)), now)
        assertTrue(d.text.startsWith("My prices are 95 seconds old, Boss. "), d.text)
        assertFalse(d.withheld)
        // The first price of each sentence is marked; percentages and point moves are not prices.
        assertTrue("24,612.40 (as of 10:58)" in d.text, d.text)
        assertTrue("24,700.00 (as of 10:58)" in d.text, d.text)
        assertFalse("24,500.00 (as of" in d.text, d.text)
        assertFalse("0.45% (as of" in d.text); assertFalse("120.50 (as of" in d.text)
        assertEquals(2, d.marked)
        // The answer itself is all there.
        assertTrue(d.text.endsWith("below 24,500.00."))
    }

    @Test fun markingSkipsTimesDatesAndSmallNumbers() {
        val (t, n) = DataAge.mark("At 10:30 on 2026-10-05 it was 45.20 then 1234.50 and \$4,214.70.", now.minusMinutes(2), now)
        assertEquals(1, n)
        assertEquals("At 10:30 on 2026-10-05 it was 45.20 then 1234.50 (as of 10:58) and \$4,214.70.", t)
        // Each sentence, including after a new line.
        assertEquals(2, DataAge.mark("Nifty 24,612.40\nBankNifty 52,100.10", now.minusMinutes(2), now).second)
        // Marked text is not marked twice.
        val once = DataAge.mark("Nifty 24,612.40.", now.minusMinutes(2), now).first
        assertEquals(once, DataAge.mark(once, now.minusMinutes(2), now).first)
    }

    @Test fun stalePricesAreNotQuoted() {
        val d = DataAge.dress(answer, listOf(prices(420, tried = true)), now)
        assertTrue(d.withheld)
        assertFalse("24,612.40" in d.text, d.text)
        assertTrue(d.text.startsWith("My Nifty prices are 7 minutes old, Boss (the last from 10:53), too old to quote as now"), d.text)
        assertTrue(d.text.endsWith("the live feed is behind."), d.text)
        // Not fetched lately: he says he is fetching.
        val f = DataAge.dress(answer, listOf(prices(420)), now)
        assertTrue("I'm fetching fresh ones: ask me again in a moment." in f.text, f.text)
    }

    @Test fun staleWithoutWithholdingStillQuotesWithTheAge() {
        val d = DataAge.dress(answer, listOf(prices(420, tried = true)), now, withhold = false)
        assertFalse(d.withheld)
        assertTrue(d.text.startsWith("My Nifty prices are 7 minutes old, Boss (the last from 10:53), so take these figures as from then"), d.text)
        assertTrue("24,612.40 (as of 10:53)" in d.text, d.text)
        assertFalse("too old to quote" in d.text)
    }

    @Test fun newsAgeAndOldHeadline() {
        val fresh = DataAge.news(now.minusMinutes(3), now.minusMinutes(10), now, true)!!
        assertNull(DataAge.note(fresh, now))
        val quiet = DataAge.news(now.minusMinutes(3), now.minusMinutes(40), now, true)!!
        assertEquals("The last headline I have is from 40 minutes ago, Boss.", DataAge.note(quiet, now))
        val old = DataAge.news(now.minusMinutes(20), now.minusMinutes(40), now, true)!!
        assertEquals("I last read the news 20 minutes ago, and the last headline I have is from 40 minutes ago, Boss.", DataAge.note(old, now))
        val stale = DataAge.news(now.minusMinutes(50), null, now, true)!!
        assertEquals("I last read the news 50 minutes ago - the feeds have not answered since, Boss.", DataAge.note(stale, now))
        // Never read: nothing to say about it.
        assertNull(DataAge.news(null, null, now, true))
        // A closed market: nothing said.
        assertNull(DataAge.note(DataAge.news(now.minusHours(5), now.minusHours(6), now, false)!!, now))
    }

    @Test fun candlesAndChain() {
        val c = DataAge.candles("BankNifty", now.minusMinutes(5), now, true)!!
        assertEquals("My last BankNifty candle closed at 10:56, Boss, 4 minutes ago.", DataAge.note(c, now))
        val s = DataAge.candles("BankNifty", now.minusMinutes(15), now, true)!!
        assertTrue(DataAge.note(s, now)!!.endsWith("the chart may have moved since."))
        assertNull(DataAge.note(DataAge.candles("BankNifty", now.minusMinutes(1), now, true)!!, now))
        val ch = DataAge.chain("NIFTY", now.minusMinutes(12), now, true)!!
        assertEquals("The NIFTY option chain I have is from 10:48, Boss, 12 minutes old.", DataAge.note(ch, now))
        assertNull(DataAge.note(DataAge.chain("NIFTY", now.minusMinutes(2), now, true)!!, now))
    }

    @Test fun oldNewsDoesNotWithholdPrices() {
        val news = DataAge.news(now.minusMinutes(20), null, now, true)!!
        val d = DataAge.dress(answer, listOf(prices(10), news), now)
        assertFalse(d.withheld)
        assertTrue(d.text.startsWith("I last read the news 20 minutes ago, Boss. Nifty is at 24,612.40,"), d.text)
        assertEquals(0, d.marked)
    }

    @Test fun sourcesByTopic() {
        assertEquals(setOf(DataAge.Source.PRICES), DataAge.sources(setOf(Topic.OVERVIEW)))
        assertEquals(setOf(DataAge.Source.CANDLES), DataAge.sources(setOf(Topic.PATTERNS)))
        assertEquals(setOf(DataAge.Source.PRICES), DataAge.sources(setOf(Topic.PATTERNS, Topic.LEVELS)))
        assertEquals(setOf(DataAge.Source.PRICES, DataAge.Source.NEWS), DataAge.sources(setOf(Topic.WHY)))
        assertEquals(setOf(DataAge.Source.NEWS), DataAge.sources(setOf(Topic.NEWS)))
        assertTrue(DataAge.sources(setOf(Topic.ACCOUNT, Topic.GREETING)).isEmpty())
    }

    @Test fun ageWords() {
        assertEquals("45 seconds", DataAge.ageText(45))
        assertEquals("119 seconds", DataAge.ageText(119))
        assertEquals("2 minutes", DataAge.ageText(120))
        assertEquals("over an hour", DataAge.ageText(3700))
        assertEquals("3 hours", DataAge.ageText(3 * 3600 + 5))
    }

    // ---- the record ------------------------------------------------------------------------------------------------

    private val open = LocalDateTime.of(2026, 10, 5, 9, 30)

    /** Prices [ageSec] old, checked at [at]. */
    private fun at(at: LocalDateTime, ageSec: Long, tried: Boolean = true) =
        DataAge.prices("Nifty", at.minusSeconds(ageSec), at.minusSeconds(ageSec), at, true, tried)!!

    @Test fun spellsJoinWhileOldAndSplitWhenFresh() {
        var log = DataAge.Log()
        // 11:00-11:06 old (data stuck at 10:58:30, so old from 11:00), then fresh, then old again at 13:00 for a check.
        for (m in 0..6L) {
            val t = LocalDateTime.of(2026, 10, 5, 11, 0).plusMinutes(m)
            log = DataAge.observe(log, listOf(DataAge.prices("Nifty", LocalDateTime.of(2026, 10, 5, 10, 58, 30), null, t, true)!!), t)
        }
        log = DataAge.observe(log, listOf(at(LocalDateTime.of(2026, 10, 5, 11, 20), 10)), LocalDateTime.of(2026, 10, 5, 11, 20))
        log = DataAge.observe(log, listOf(at(LocalDateTime.of(2026, 10, 5, 13, 0), 150)), LocalDateTime.of(2026, 10, 5, 13, 0))
        val d = log.day(now.toLocalDate())!!
        assertEquals(9, d.checks); assertEquals(8, d.old)
        assertEquals(2, d.spells.size)
        val first = d.spells.first()
        assertEquals(LocalDateTime.of(2026, 10, 5, 11, 0), first.from)
        assertEquals(LocalDateTime.of(2026, 10, 5, 11, 6), first.to)
        assertEquals(6 * 60, first.seconds)
        assertEquals(450, first.worstSec)
        // The second spell began when the data turned old (12:57:30 + 90 seconds).
        assertEquals(LocalDateTime.of(2026, 10, 5, 12, 59), d.spells[1].from)
        val lines = DataAge.dayLines(log, now.toLocalDate())
        assertEquals("my prices were old twice, 7 minutes in all (the longest 6 minutes, from 11:00), at worst 7 minutes old", lines.single())
    }

    @Test fun closedMarketChecksAreNotCounted() {
        val c = DataAge.prices("Nifty", now.minusHours(3), now.minusHours(3), now, false)!!
        assertEquals(DataAge.Log(), DataAge.observe(DataAge.Log(), listOf(c), now))
        assertEquals(DataAge.Log(), DataAge.answered(DataAge.Log(), listOf(c), warned = false, withheld = false, now = now))
    }

    @Test fun answersAreCountedAndReviewed() {
        var log = DataAge.Log()
        log = DataAge.answered(log, listOf(at(open, 10)), warned = false, withheld = false, now = open)
        log = DataAge.answered(log, listOf(at(open.plusHours(1), 100)), warned = true, withheld = false, now = open.plusHours(1))
        log = DataAge.answered(log, listOf(at(open.plusHours(2), 400)), warned = true, withheld = true, now = open.plusHours(2))
        val d = log.day(open.toLocalDate())!!
        assertEquals(3, d.answers); assertEquals(2, d.warned); assertEquals(1, d.withheld)
        val r = DataAge.review(log, open.toLocalDate()).single()
        assertTrue(r.startsWith("today my prices were old twice"), r)
        assertTrue(r.endsWith("2 of my 3 market answers carried an age warning, and 1 I would not quote"), r)
        // It reads in the evening review.
        val said = SelfReview.say(SelfReview.Facts(3, 3, emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList(), freshness = listOf(r)))!!
        assertTrue("today my prices were old twice" in said, said)
    }

    @Test fun aFreshDayIsNotInTheReview() {
        var log = DataAge.Log()
        repeat(5) { i -> log = DataAge.observe(log, listOf(at(open.plusMinutes(i.toLong()), 20)), open.plusMinutes(i.toLong())) }
        assertTrue(DataAge.review(log, open.toLocalDate()).isEmpty())
        assertTrue(DataAge.review(DataAge.Log(), open.toLocalDate()).isEmpty())
        // But asked, he says it was fresh.
        val said = DataAge.say(listOf(at(open.plusMinutes(5), 20)), log, open.plusMinutes(5), trading = true)
        assertTrue("My data has been fresh every time I checked today." in said, said)
    }

    @Test fun onlyTheLastTwoWeeksAreKept() {
        var log = DataAge.Log()
        for (d in 0..20L) { val t = open.plusDays(d); log = DataAge.observe(log, listOf(at(t, 200)), t) }
        assertEquals(DataAge.KEEP_DAYS, log.days.size)
        assertEquals(open.plusDays(20).toLocalDate(), log.days.last().day)
    }

    @Test fun spellsAreBounded() {
        var log = DataAge.Log()
        // Old checks 11 minutes apart never join.
        for (i in 0 until DataAge.MAX_SPELLS + 10) {
            val t = open.toLocalDate().atStartOfDay().plusMinutes(i * 11L)
            log = DataAge.observe(log, listOf(DataAge.prices("Nifty", t.minusSeconds(100), null, t, true)!!), t)
        }
        assertEquals(DataAge.MAX_SPELLS, log.day(open.toLocalDate())!!.spells.size)
        // A spell never starts before its day.
        val t = open.toLocalDate().atStartOfDay().plusMinutes(5)
        val y = DataAge.observe(DataAge.Log(), listOf(DataAge.prices("Nifty", t.minusHours(20), null, t, true)!!), t)
        assertEquals(open.toLocalDate().atStartOfDay(), y.day(open.toLocalDate())!!.spells.single().from)
    }

    @Test fun asked() {
        listOf("is your data fresh?", "Jarvis, how old are your prices", "are your prices live", "how fresh is your data",
            "is the news up to date", "when did you last get prices", "when did you last read the news?", "are your prices stale",
            "how old is the option chain", "how recent are your candles").forEach { assertTrue(DataAge.asked(it), it) }
        listOf("how is nifty", "what is the news", "buy nifty 25000 ce", "are you there", "fresh start", "how old are you").forEach {
            assertFalse(DataAge.asked(it), it) }
    }

    @Test fun saidWhenAsked() {
        var log = DataAge.Log()
        log = DataAge.observe(log, listOf(at(open, 200)), open)
        val t = open.plusMinutes(30)
        val checks = listOfNotNull(at(t, 40), DataAge.candles("Nifty", t.minusSeconds(100), t, true),
            DataAge.news(t.minusMinutes(3), t.minusMinutes(25), t, true), DataAge.chain("NIFTY", t.minusMinutes(20), t, true))
        val s = DataAge.say(checks, log, t, trading = true)
        assertTrue(s.startsWith("Boss, my Nifty prices are 40 seconds old (from 09:59) - fresh;"), s)
        assertTrue("my last Nifty candle closed at 09:59 - fresh" in s, s)
        assertTrue("I read the news 3 minutes ago - fresh, the newest headline from 09:35" in s, s)
        assertTrue("the NIFTY option chain I last read is from 09:40 - stale" in s, s)
        assertTrue("Today my prices were old once, for" in s, s)
        assertTrue(s.endsWith("I never quote stale prices as now."))
        // Closed: said so; no data: said so.
        val closed = DataAge.say(listOf(DataAge.prices("Nifty", null, open.minusDays(3), open, false)!!), DataAge.Log(), open, trading = false)
        assertTrue("(from 09:31 on 2 Oct)" in closed, closed)
        assertTrue("The market is not trading now" in closed)
        assertFalse(" - fresh" in closed)
        assertTrue(DataAge.say(emptyList(), DataAge.Log(), open, true).startsWith("I have no market data on this phone yet, Boss."))
    }
}
