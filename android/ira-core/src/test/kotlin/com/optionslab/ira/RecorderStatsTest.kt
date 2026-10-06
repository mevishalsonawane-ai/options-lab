package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "Market data" viewer's pure reading ([RecorderStats], [NewsImpact]) on synthetic day files written with the
 * recorder's own writers ([MarketRecord], [RecorderFeeds]): the move after a headline and whether it agreed with the
 * tone, the OI build-up, the book's imbalance, GIFT Nifty against the open, FII long %, and no lessons before 20 days.
 */
class RecorderStatsTest {
    private val day = LocalDate.of(2026, 10, 6)
    private fun t(h: Int, m: Int, s: Int = 0) = LocalTime.of(h, m, s)

    private fun news(at: LocalTime, title: String, tone: Double, word: String = when { tone >= 0.25 -> "positive"; tone <= -0.25 -> "negative"; else -> "neutral" }) =
        MarketRecord.news(MarketRecord.News(at, "Reuters", null, title, "https://x.test/$title", tone, word, listOf("NIFTY"), true))

    /** NIFTY (and BANKNIFTY at 2x) every minute from 09:15 to 15:29, starting at [start], moving [step] a minute. */
    private fun levels(start: Double = 25_000.0, step: (Int) -> Double = { 1.0 }): List<String> {
        val out = ArrayList<String>()
        var v = start
        for (m in 0 until 375) {
            val at = t(9, 15).plusMinutes(m.toLong()).plusSeconds(5)
            out += MarketRecord.spot(at, "NIFTY", v)
            out += MarketRecord.spot(at, "BANKNIFTY", v * 2)
            v += step(m)
        }
        return out
    }

    // ---- the move after a headline ------------------------------------------------------------------------------------

    @Test fun theMoveAfterAHeadlineIsMeasuredFromTheRecordedLevels() {
        val lines = listOf(MarketRecord.header(day)) + levels() + news(t(10, 0, 30), "Markets rally on rate cut hopes", 0.8)
        val d = RecorderStats.parse(lines)
        val i = NewsImpact.impacts(d.news, d.levels).single()
        val n = i.after.getValue("NIFTY")
        // 10:00:05 is minute 45 from 09:15:05: 25045. +5 min -> 10:05:05 = 25050 ...
        assertEquals(25_045.0, n.base)
        assertEquals(listOf(5, 15, 30), n.moves.map { it.minutes })
        assertEquals(5.0, n.at(5)!!.points)
        assertEquals(15.0, n.at(15)!!.points)
        assertEquals(30.0, n.at(30)!!.points)
        assertEquals(30.0 / 25_045 * 100, n.at(30)!!.pct, 1e-9)
        assertEquals(60.0, i.after.getValue("BANKNIFTY").at(30)!!.points)
        assertEquals(NewsImpact.Agreement.AGREED, i.agreement("NIFTY", 30))
        assertEquals("+30 (+0.12%)", NewsImpact.moveText(n.at(30)!!))
    }

    @Test fun agreementFollowsTheToneAndTheMove() {
        assertEquals(NewsImpact.Agreement.AGREED, NewsImpact.agreement(1, 12.0))
        assertEquals(NewsImpact.Agreement.AGREED, NewsImpact.agreement(-1, -3.0))
        assertEquals(NewsImpact.Agreement.AGAINST, NewsImpact.agreement(1, -12.0))
        assertEquals(NewsImpact.Agreement.AGAINST, NewsImpact.agreement(-1, 0.5))
        assertEquals(NewsImpact.Agreement.FLAT, NewsImpact.agreement(1, 0.0))
        assertEquals(NewsImpact.Agreement.NO_TONE, NewsImpact.agreement(0, 40.0))
        // A falling market after a positive headline: against.
        val lines = levels { -2.0 } + news(t(11, 0, 10), "Sensex surges", 0.9) + news(t(11, 1), "Markets steady", 0.0)
        val d = RecorderStats.parse(lines)
        val im = NewsImpact.impacts(d.news, d.levels)
        assertEquals(NewsImpact.Agreement.AGAINST, im[0].agreement("NIFTY", 30))
        assertEquals(NewsImpact.Agreement.NO_TONE, im[1].agreement("NIFTY", 30))
        assertEquals(NewsImpact.Tally(0, 1), NewsImpact.tally(im))
    }

    @Test fun noMoveIsInventedPastTheRecordingOrOverAGap() {
        // A headline at 15:10: +30 is past the day's last level (15:29:05).
        val late = RecorderStats.parse(levels() + news(t(15, 10), "Late news", 0.5))
        val a = NewsImpact.impacts(late.news, late.levels).single().after.getValue("NIFTY")
        assertEquals(listOf(5, 15), a.moves.map { it.minutes })
        // Before the first level (09:05, pre-open): no level then, no moves.
        val early = RecorderStats.parse(levels() + news(t(9, 5), "Early news", 0.5))
        assertTrue(NewsImpact.impacts(early.news, early.levels).single().after.isEmpty())
        // A 20-minute hole in the levels (10:10-10:30): the +15 lands in it and is left out.
        val holed = levels().filter { l ->
            val at = LocalTime.parse(MarketRecord.fields(l)[1])
            at.isBefore(t(10, 10)) || at.isAfter(t(10, 30))
        }
        val h = RecorderStats.parse(holed + news(t(10, 0, 10), "Holed", -0.6))
        val m = NewsImpact.impacts(h.news, h.levels).single().after.getValue("NIFTY")
        assertEquals(listOf(5, 30), m.moves.map { it.minutes })
        // levelAt: a level older than STALE_MIN does not stand for the moment.
        val s = listOf(NewsImpact.Level(t(10, 0), 1.0))
        assertNotNull(NewsImpact.levelAt(s, t(10, 3)))
        assertNull(NewsImpact.levelAt(s, t(10, 3, 1)))
        assertNull(NewsImpact.levelAt(s, t(9, 59)))
    }

    @Test fun aHeadlineIsReadBackFromItsRecordOnceADay() {
        val rec = news(t(10, 0), "Rates, \"cut\" hopes", -0.4)
        val h = NewsImpact.headline(rec)!!
        assertEquals("Rates, \"cut\" hopes", h.title)
        assertEquals("negative", h.toneWord)
        assertEquals(-1, h.direction)
        assertEquals(listOf("NIFTY"), h.markets)
        assertTrue(h.matters)
        assertNull(NewsImpact.headline("N,broken"))
        assertNull(NewsImpact.headline("S,10:00:00,NIFTY,1"))
        assertEquals(1, RecorderStats.parse(listOf(rec, rec)).news.size)
    }

    // ---- the futures --------------------------------------------------------------------------------------------------

    @Test fun oiBuildUpFollowsTheStandardDefinitions() {
        assertEquals(RecorderStats.BuildUp.LONG_BUILD_UP, RecorderStats.buildUp(10.0, 500))
        assertEquals(RecorderStats.BuildUp.SHORT_BUILD_UP, RecorderStats.buildUp(-10.0, 500))
        assertEquals(RecorderStats.BuildUp.LONG_UNWINDING, RecorderStats.buildUp(-10.0, -500))
        assertEquals(RecorderStats.BuildUp.SHORT_COVERING, RecorderStats.buildUp(10.0, -500))
        assertNull(RecorderStats.buildUp(0.0, 500))
        assertNull(RecorderStats.buildUp(10.0, 0))
    }

    private fun fut(at: LocalTime, last: Double, oi: Long, spot: Double?, name: String = "NIFTY", symbol: String = "NIFTY26OCTFUT") =
        MarketRecord.future(at, name, symbol, LocalDate.of(2026, 10, 27), MarketRecord.Quote(last, volume = 1000, oi = oi), spot)

    @Test fun eachFutureHasItsOiBasisAndBuildUp() {
        val lines = listOf(
            fut(t(9, 15, 5), 25_080.0, 10_000_000, 25_000.0),
            fut(t(9, 16, 5), 25_090.0, 10_100_000, 25_005.0),
            fut(t(15, 29, 5), 25_020.0, 10_600_000, 24_950.0),
            fut(t(9, 15, 5), 56_200.0, 2_000_000, 56_000.0, "BANKNIFTY", "BANKNIFTY26OCTFUT"),
            fut(t(15, 29, 5), 56_400.0, 1_800_000, null, "BANKNIFTY", "BANKNIFTY26OCTFUT"),
        )
        val f = RecorderStats.futures(RecorderStats.parse(lines))
        assertEquals(listOf("NIFTY", "BANKNIFTY"), f.map { it.name })
        val n = f[0]
        assertEquals(listOf(10_000_000L, 10_100_000L, 10_600_000L), n.oi)
        assertEquals(listOf(80.0, 85.0, 70.0), n.basis)
        assertEquals(RecorderStats.BuildUp.SHORT_BUILD_UP, n.buildUp)
        assertEquals(600_000L, n.oiChange)
        assertEquals(70.0, n.lastBasis)
        val b = f[1]
        assertEquals(RecorderStats.BuildUp.SHORT_COVERING, b.buildUp)
        assertEquals(listOf(200.0), b.basis)                    // the minute without the index's level has no basis
        // One minute only: no build-up.
        assertNull(RecorderStats.futures(RecorderStats.parse(lines.take(1))).single().buildUp)
        assertEquals(listOf(0, 49, 99), RecorderStats.thin((0..99).toList(), 3))
        assertEquals(listOf(1, 2), RecorderStats.thin(listOf(1, 2), 3))
    }

    // ---- the book -----------------------------------------------------------------------------------------------------

    @Test fun imbalanceIsBuyLessSellOverBoth() {
        assertEquals(0.5, RecorderStats.imbalance(300, 100)!!, 1e-12)
        assertEquals(-0.5, RecorderStats.imbalance(100, 300)!!, 1e-12)
        assertEquals(0.0, RecorderStats.imbalance(100, 100)!!, 1e-12)
        assertNull(RecorderStats.imbalance(0, 0))
        assertNull(RecorderStats.imbalance(null, 100))
        assertEquals("+0.50 buy-side", RecorderStats.imbalanceText(0.5))
        assertEquals("-0.25 sell-side", RecorderStats.imbalanceText(-0.25))
        assertEquals("even", RecorderStats.imbalanceText(0.001))
    }

    @Test fun theFuturesBookIsSummedPer15Minutes() {
        val sym = "NIFTY26OCTFUT"
        fun d(at: LocalTime, bid: Double, ask: Double, buy: Long, sell: Long, what: String = sym) =
            MarketRecord.depth(at, what, MarketRecord.Quote(25_000.0, bid = bid, bidQty = 75, ask = ask, askQty = 75, buyQty = buy, sellQty = sell))
        val lines = listOf(
            fut(t(9, 15, 5), 25_080.0, 1, 25_000.0),
            d(t(9, 15, 5), 25_079.0, 25_080.0, 300_000, 100_000),
            d(t(9, 29, 5), 25_079.0, 25_082.0, 100_000, 100_000),
            d(t(9, 30, 5), 25_079.0, 25_079.5, 100_000, 300_000),
            d(t(9, 30, 5), 120.0, 125.0, 9, 1, "NIFTY26OCT25000CE"),   // an option's book: not the future's
        )
        val slots = RecorderStats.depth(RecorderStats.parse(lines)).getValue("NIFTY")
        assertEquals(listOf(t(9, 15), t(9, 30)), slots.map { it.start })
        assertEquals(2.0, slots[0].avgSpread!!, 1e-9)
        assertEquals(200_000.0 / 600_000, slots[0].imbalance!!, 1e-12)
        assertEquals(2, slots[0].samples)
        assertEquals(0.5, slots[1].avgSpread!!, 1e-9)
        assertEquals(-0.5, slots[1].imbalance!!, 1e-12)
    }

    // ---- participants and GIFT Nifty ----------------------------------------------------------------------------------

    private fun participants(date: LocalDate, fiiLong: Long, fiiShort: Long): List<String> {
        val rows = RecorderFeeds.CLIENTS.map { c ->
            val f = MutableList(RecorderFeeds.COLUMNS.size) { 1000L }
            if (c == "FII") { f[0] = fiiLong; f[1] = fiiShort }
            RecorderFeeds.Participant(c, f)
        }
        val p = RecorderFeeds.Participants(date, rows)
        val at = date.atTime(18, 45)
        return rows.map { RecorderFeeds.participant(p, at, "oi", it) } + rows.map { RecorderFeeds.participant(p, at, "vol", it) }
    }

    @Test fun fiiLongPercentFromTheParticipantRecords() {
        val lines = participants(day, 30_000, 90_000) + participants(day.minusDays(1), 60_000, 60_000)
        val d = RecorderStats.parse(lines)
        val f = RecorderStats.fiiLong(d.participants)
        assertEquals(listOf(day.minusDays(1), day), f.map { it.date })
        assertEquals(50.0, f[0].longPct, 1e-9)
        assertEquals(25.0, f[1].longPct, 1e-9)
        assertEquals(1, RecorderStats.fiiLong(d.participants, 1).size)
    }

    private fun gift(at: LocalTime, last: Double) = RecorderFeeds.gift(at, RecorderFeeds.Gift("NIFTY", LocalDate.of(2026, 10, 27), last, null, null, null, null))

    @Test fun giftNiftyIsSetAgainstTheRecordedOpen() {
        val lines = listOf(gift(t(8, 45), 25_120.0), gift(t(9, 0), 25_150.0), gift(t(9, 20), 25_400.0)) + levels(25_100.0)
        val d = RecorderStats.parse(lines)
        assertEquals(25_150.0, RecorderStats.giftPreOpen(d))
        assertEquals(25_100.0, RecorderStats.open(d))
        assertEquals(25_100.0 + 374, RecorderStats.close(d))
        val prev = RecorderStats.Summary(day.minusDays(1), 10, NewsImpact.Tally(0, 0), 0, null, null, 25_000.0, emptyList())
        val g = RecorderStats.gaps(listOf(RecorderStats.summary(day, d), prev)).single()
        assertEquals(50.0, g.error)
        assertEquals(150.0, g.predictedGap)
        assertEquals(100.0, g.actualGap)
        // Recording started late (no level by 09:17:59): no open, no gap day.
        val late = RecorderStats.parse(lines.filter { !it.startsWith("S,09:1") })
        assertNull(RecorderStats.open(late))
        assertTrue(RecorderStats.gaps(listOf(RecorderStats.summary(day, late))).isEmpty())
    }

    // ---- empty, partial and the lessons -------------------------------------------------------------------------------

    @Test fun anEmptyOrBrokenDayIsReadWithoutFailing() {
        val e = RecorderStats.parse(emptyList())
        assertTrue(e.empty); assertFalse(e.partial); assertNull(e.date)
        val h = RecorderStats.parse(listOf(MarketRecord.header(day), MarketRecord.gap(t(10, 0), "quotes", "no quotes returned"), "S,xx", "F,10:00:00,NIFTY", "", "junk"), bad = 1)
        assertTrue(h.empty); assertTrue(h.partial)
        assertEquals(day, h.date)
        assertEquals("quotes", h.gaps.single().what)
        assertTrue(RecorderStats.futures(h).isEmpty())
        assertTrue(RecorderStats.depth(h).isEmpty())
        assertNull(RecorderStats.lessons(listOf(RecorderStats.summary(day, h))))
    }

    /** A synthetic day: a positive headline at 10:00 in a rising (or falling) market, GIFT 30 points over the open. */
    private fun syntheticDay(d: LocalDate, up: Boolean): RecorderStats.Summary {
        val lines = listOf(MarketRecord.header(d), gift(t(9, 0), 25_030.0)) + levels(25_000.0) { if (up) 1.0 else -1.0 } +
            news(t(10, 0, 10), "Story $d", 0.7)
        return RecorderStats.summary(d, RecorderStats.parse(lines))
    }

    @Test fun noLessonsBefore20RecordedDays() {
        val days = (0 until 19).map { syntheticDay(day.minusDays(it.toLong()), up = true) }
        assertNull(RecorderStats.lessons(days))
        assertEquals(19, RecorderStats.recordedDays(days))
        assertEquals("First lessons from 20 recorded days: 19 so far.", RecorderStats.waiting(19))
        // The same day twice is one day.
        assertNull(RecorderStats.lessons(days + days.first()))
    }

    @Test fun lessonsFrom20DaysGiveTheHitRateAndGapError() {
        val days = (0 until 20).map { i -> syntheticDay(day.minusDays(i.toLong()), up = i % 4 != 0) }
        val l = RecorderStats.lessons(days)!!
        assertEquals(20, l.days)
        assertEquals(NewsImpact.Tally(15, 20), l.tone)
        assertEquals(0.75, l.tone.rate!!, 1e-12)
        assertEquals(20, l.gapDays)
        assertEquals(30.0, l.gapMae!!, 1e-9)
        val lines = l.lines().toMap()
        assertEquals("75% went the tone's way (15 of 20 headlines)", lines["News tone vs NIFTY 30 min later"])
        assertEquals("off by 30 points on average (20 days)", lines["GIFT Nifty vs the open"])
        assertTrue(RecorderStats.LESSON_CAVEAT.startsWith("Needs months before it means anything"))
        assertTrue(abs(days.first().tone.n - 1.0) < 1e-9)
    }
}
