package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsMovesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 10, 5)
    private val today = day

    /** A full session of 1-minute bars on [d] from 09:15, the close at minute i given by [px]. */
    private fun bars(d: LocalDate, px: (Int) -> Double) = List(375) { i -> val c = px(i); Candle(d.atTime(9, 15).plusMinutes(i.toLong()), c, c, c, c) }

    private fun headline(title: String, at: LocalDateTime, source: String = "Mint") =
        Headline(title, "https://example.com/" + title.hashCode(), source, at.atZone(zone).toInstant(), 0.0, emptyList())

    /** [n] measured RBI notes on Nifty, the newest [daysAgo] days ago, [big] of them moving 0.5% within the hour (the rest 0.1%). */
    private fun notes(tag: NewsDesk.Tag, n: Int, big: Int, daysAgo: Long = 1) = List(n) { i ->
        val mv = if (i < big) 0.5 else 0.1
        NewsMoves.Note(tag, Market.NIFTY, today.minusDays(daysAgo + i).atTime(11, 0), 100.0, mapOf(30 to mv, 60 to mv))
    }

    @Test fun aStoryInTheSessionIsNotedOncePerThemeAndEpisode() {
        // Price 100 until 11:00 (minute 105), then 0.01 up a minute: +0.3% at 11:30, +0.6% at 12:00.
        val b = bars(day) { i -> if (i <= 105) 100.0 else 100.0 + 0.01 * (i - 105) }
        val news = listOf(
            headline("RBI keeps repo rate unchanged at 6.5%", day.atTime(11, 0)),
            headline("RBI keeps repo rate unchanged, governor says", day.atTime(11, 2), "ET"),
            // Same theme 20 minutes later: the same episode.
            headline("Reserve Bank governor on liquidity outlook", day.atTime(11, 20)),
            // Before the open: not timed.
            headline("Fed's Powell speaks on US rates", day.atTime(7, 0)),
            // Gold only: not timed.
            Headline("Gold slips as Fed holds", "https://e/x", "Reuters", day.atTime(12, 0).atZone(zone).toInstant(), 0.0, listOf(Market.GOLD)),
        )
        val log = NewsMoves.update(emptyList(), news, mapOf(Market.NIFTY to b), zone, today)
        val rbi = log.filter { it.tag == NewsDesk.Tag.RBI }
        assertEquals(1, rbi.size, log.toString())
        val n = rbi.single()
        assertEquals(day.atTime(11, 0), n.at)
        assertEquals(100.0, n.price, 1e-9)
        assertTrue(n.settled)
        assertEquals(0.29, n.moved[30]!!, 1e-6)    // the 11:29 candle's close: the window's last minute
        assertEquals(0.59, n.moved[60]!!, 1e-6)
        assertTrue(log.none { it.tag == NewsDesk.Tag.FED })
        // Noting again adds nothing.
        assertEquals(log, NewsMoves.update(log, news, mapOf(Market.NIFTY to b), zone, today))
    }

    @Test fun waitsForCandlesAndNeverGuessesPastTheClose() {
        val b = bars(day) { 100.0 }
        val early = b.filter { !it.t.isAfter(day.atTime(11, 10)) }
        val n = NewsMoves.candidates(emptyList(), listOf(headline("Fed holds rates", day.atTime(11, 0))), mapOf(Market.NIFTY to early), zone).single()
        val part = NewsMoves.settle(listOf(n), mapOf(Market.NIFTY to early)).single()
        assertTrue(part.moved.isEmpty())
        assertFalse(part.settled)
        // 15:00: the hour runs past the 15:30 close - not measurable, never guessed; 30 minutes is.
        val late = NewsMoves.update(emptyList(), listOf(headline("Fed holds rates", day.atTime(15, 0))), mapOf(Market.NIFTY to b), zone, today).single()
        assertNotNull(late.moved[30])
        assertTrue(60 in late.moved); assertNull(late.moved[60])
        // A headline the candles don't reach yet: not noted (tried again next time).
        assertTrue(NewsMoves.candidates(emptyList(), listOf(headline("Fed holds rates", day.atTime(12, 0))), mapOf(Market.NIFTY to early), zone).isEmpty())
    }

    @Test fun recordCountsTheNewestAndFades() {
        val log = notes(NewsDesk.Tag.RBI, 9, 4)
        val r = NewsMoves.record(log, NewsDesk.Tag.RBI, Market.NIFTY, today)
        assertEquals(9, r.n); assertEquals(4, r.big)
        // The newest four moved: counted more, so faded is above the plain rate.
        assertTrue(r.faded > r.rate)
        assertEquals(0.1, r.median!!, 1e-9)
        // Only the newest LAST count.
        assertEquals(NewsMoves.LAST, NewsMoves.record(notes(NewsDesk.Tag.RBI, 30, 0), NewsDesk.Tag.RBI, Market.NIFTY, today).n)
        // Unmeasurable notes don't count.
        val none = NewsMoves.Note(NewsDesk.Tag.RBI, Market.NIFTY, day.atTime(15, 0), 100.0, mapOf(30 to 0.1, 60 to null))
        assertEquals(0, NewsMoves.record(listOf(none), NewsDesk.Tag.RBI, Market.NIFTY, today).n)
    }

    @Test fun theLineIsTimingOnlyAndHonestWhenFew() {
        val line = NewsMoves.line(notes(NewsDesk.Tag.RBI, 9, 4), NewsDesk.Tag.RBI, Market.NIFTY, today, usual = 0.2)!!
        assertEquals("RBI headlines on this phone: Nifty moved 0.3% or more within an hour after 4 of the last 9 " +
            "(in any hour on this phone it moves that much about 20% of the time) - timing only, not cause.", line)
        val few = NewsMoves.line(notes(NewsDesk.Tag.FED, 2, 1), NewsDesk.Tag.FED, Market.NIFTY, today)!!
        assertTrue(few.startsWith("Fed headlines on this phone"), few)
        assertTrue(few.contains("too few to go by"))
        assertNull(NewsMoves.line(emptyList(), NewsDesk.Tag.RBI, Market.NIFTY, today))
        assertNull(NewsMoves.line(notes(NewsDesk.Tag.RBI, 9, 4), NewsDesk.Tag.NIFTY, Market.NIFTY, today))
    }

    @Test fun answersAreFactsNotForecasts() {
        val log = notes(NewsDesk.Tag.RBI, 9, 4) + notes(NewsDesk.Tag.FED, 6, 1) + notes(NewsDesk.Tag.INFLATION, 2, 2)
        val rbi = NewsMoves.say(log, NewsMoves.Ask(NewsDesk.Tag.RBI, Market.NIFTY), today, 0.25)
        assertTrue(rbi.startsWith("RBI headlines: Nifty moved 0.3% or more within an hour after 4 of the last 9 on this phone, Boss"), rbi)
        assertTrue(rbi.contains(NewsMoves.TIMING) && rbi.contains(NewsMoves.NOT_FORECAST))
        assertTrue(rbi.contains("about 25% of the time"))
        val none = NewsMoves.say(log, NewsMoves.Ask(NewsDesk.Tag.BUDGET, Market.NIFTY), today)
        assertTrue(none.startsWith("I haven't timed any budget headline"), none)
        val all = NewsMoves.say(log, NewsMoves.Ask(null, Market.NIFTY), today)
        assertTrue(all.contains("RBI 4 of 9; Fed 1 of 6"), all)
        assertTrue(all.contains("Too few to go by yet: inflation 2 of 2"), all)
        for (s in listOf(rbi, none, all)) assertFalse(Regex("(?i)\\b(buy|sell|should|will)\\b").containsMatchIn(s), s)
    }

    @Test fun mentionedBesideTheNewsDesk() {
        val log = notes(NewsDesk.Tag.RBI, 9, 4)
        val now = day.atTime(13, 0).atZone(zone).toInstant()
        val news = listOf(headline("RBI keeps repo rate unchanged", day.atTime(10, 0)))
        val on = NewsMoves.forDesk(NewsDesk.Ask.On(NewsDesk.Tag.RBI), news, log, now, zone)!!
        assertTrue(on.startsWith("RBI headlines on this phone"), on)
        assertTrue(NewsMoves.forDesk(NewsDesk.Ask.Main, news, log, now, zone)!!.contains("4 of the last 9"))
        assertNull(NewsMoves.forDesk(NewsDesk.Ask.On(NewsDesk.Tag.FED), news, log, now, zone))
        assertNull(NewsMoves.forDesk(NewsDesk.Ask.Moved, news, log, now, zone))
    }

    @Test fun asked() {
        assertEquals(NewsMoves.Ask(NewsDesk.Tag.RBI, Market.NIFTY), NewsMoves.asked("How does the market react to RBI news?"))
        assertEquals(NewsMoves.Ask(NewsDesk.Tag.FED, Market.NIFTY), NewsMoves.asked("do Fed headlines move Nifty?"))
        assertEquals(NewsMoves.Ask(NewsDesk.Tag.INFLATION, Market.BANKNIFTY), NewsMoves.asked("does Bank Nifty react to inflation data?"))
        assertEquals(NewsMoves.Ask(NewsDesk.Tag.BANKS, Market.NIFTY), NewsMoves.asked("how does nifty react to bank news"))
        assertEquals(NewsMoves.Ask(NewsDesk.Tag.EARNINGS, Market.NIFTY), NewsMoves.asked("market reaction to earnings"))
        assertEquals(NewsMoves.Ask(null, Market.NIFTY), NewsMoves.asked("which news moves the market most?"))
        // Not this: a forecast, advice, one day, the news itself, or a plain question about the market.
        assertNull(NewsMoves.asked("will RBI news move Nifty tomorrow?"))
        assertNull(NewsMoves.asked("should I buy before the Fed decision?"))
        assertNull(NewsMoves.asked("how did the market react to RBI news today?"))
        assertNull(NewsMoves.asked("any news on RBI?"))
        assertNull(NewsMoves.asked("what news moved the market today?"))
        assertNull(NewsMoves.asked("how does the market look?"))
        assertNull(NewsMoves.asked("how does bank nifty move"))
        // The news desk still answers its own.
        assertEquals(NewsDesk.Ask.On(NewsDesk.Tag.RBI), NewsDesk.asked("any news on RBI?"))
    }

    @Test fun usualCountsQuarterHourWindows() {
        val days = (0 until 3).map { day.minusDays(it.toLong()) }.sorted()
        // Flat days: never 0.3% in an hour.
        assertEquals(0.0, NewsMoves.usual(Market.NIFTY, days.flatMap { d -> bars(d) { 100.0 } })!!, 1e-9)
        // A steady climb of 0.01 a minute: every full hour moves 0.59% or so.
        assertEquals(1.0, NewsMoves.usual(Market.NIFTY, days.flatMap { d -> bars(d) { i -> 100.0 + 0.01 * i } })!!, 1e-9)
        assertNull(NewsMoves.usual(Market.NIFTY, bars(day) { 100.0 }.take(30)))
    }

    @Test fun savesAndLoads() {
        val log = notes(NewsDesk.Tag.RBI, 3, 1) + NewsMoves.Note(NewsDesk.Tag.FED, Market.BANKNIFTY, day.atTime(15, 0), 50000.0, mapOf(30 to 0.2, 60 to null)) +
            NewsMoves.Note(NewsDesk.Tag.BANKS, Market.NIFTY, day.atTime(12, 0), 100.0)
        val back = NewsMoves.load(NewsMoves.save(log))
        assertEquals(log.size, back.size)
        assertEquals(log.map { it.key }, back.map { it.key })
        assertEquals(log.map { it.moved.keys }, back.map { it.moved.keys })
        assertNull(back[3].moved[60])
        assertTrue(NewsMoves.load("garbage\n").isEmpty())
    }

    @Test fun inTheLearningsLedger() {
        val i = Learnings.Inputs(news = notes(NewsDesk.Tag.RBI, 9, 4))
        val now = day.atTime(16, 0)
        val item = Learnings.items(i, now).single { it.area == Learnings.Area.NEWS }
        assertEquals("RBI headlines: Nifty moved 0.3% or more within an hour after 4 of the last 9", item.what)
        assertFalse(item.personal)
        val said = Learnings.say(Learnings.items(i, now), Learnings.Ask.ALL, today, locked = true)
        assertTrue(said.contains("News and the index on this phone"), said)
        assertFalse(Learnings.say(Learnings.items(i, now), Learnings.Ask.CHANGED, today, locked = false).contains("RBI headlines"))
    }

    @Test fun askedWhetherTheNewsMovedItIsTimingOnly() {
        // Round 9: "was it the news that moved Nifty?" - the record by theme, said as timing, never a cause.
        assertEquals(NewsMoves.Ask(null, Market.NIFTY, cause = true), NewsMoves.asked("was it the news that moved nifty"))
        assertEquals(NewsMoves.Ask(null, Market.NIFTY, cause = true), NewsMoves.asked("Was it the news that moved the market?"))
        assertEquals(NewsMoves.Ask(NewsDesk.Tag.RBI, Market.BANKNIFTY, cause = true), NewsMoves.asked("was it rbi news that moved banknifty"))
        assertEquals(NewsMoves.Ask(null, Market.NIFTY, cause = true), NewsMoves.asked("kya news se nifty gira"))
        // Hinglish habits: how it reacts to a theme.
        assertEquals(NewsMoves.Ask(NewsDesk.Tag.RBI, Market.NIFTY), NewsMoves.asked("rbi news pe nifty kaise react karta hai"))
        assertEquals(NewsMoves.Ask(NewsDesk.Tag.FED, Market.NIFTY), NewsMoves.asked("fed ki news se market hilta hai kya"))
        // Today's own move stays the news desk's; a forecast or Boss's book is never this.
        assertNull(NewsMoves.asked("was it the news that moved nifty today"))
        assertNull(NewsMoves.asked("will the rbi news move nifty"))
        assertNull(NewsMoves.asked("was it the news that moved my positions"))
        val log = notes(NewsDesk.Tag.RBI, 9, 4)
        val said = NewsMoves.say(log, NewsMoves.Ask(null, Market.NIFTY, cause = true), today)
        assertTrue(said.startsWith(NewsMoves.causeHead(Market.NIFTY)), said)
        assertTrue(said.contains(NewsMoves.TIMING) && said.contains(NewsMoves.NOT_FORECAST), said)
        assertFalse(Regex("(?i)\\b(because|due to|caused by|buy|sell|should|will)\\b").containsMatchIn(said), said)
        val none = NewsMoves.say(emptyList(), NewsMoves.Ask(NewsDesk.Tag.FED, Market.NIFTY, cause = true), today)
        assertTrue(none.startsWith("I can't say the news moved Nifty, Boss") && none.contains(NewsMoves.TIMING), none)
    }
}
