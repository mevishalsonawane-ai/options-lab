package com.optionslab.ira

import com.optionslab.engine.KiteTicks
import com.optionslab.ira.OrderFlow.Role
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The auction's live readings ([Auction], [MarketProfile]): the profile, the regime, delta and its divergence, VWAP, the TPO. */
class AuctionTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, s: Int = 0, day: Int = 9) = ZonedDateTime.of(2026, 10, day, h, m, s, 0, ist).toEpochSecond()
    private val open = 9 * 60 + 15

    // ---- the profile ----

    @Test fun binsAreAboutFiveBasisPointsInWholeTicks() {
        assertEquals(27.5, Auction.binSize(55_000.0), 1e-9)
        assertEquals(12.5, Auction.binSize(25_000.0), 1e-9)
        assertEquals(0.05, Auction.binSize(10.0), 1e-9)          // at least one tick
        assertEquals(0.05, Auction.binSize(0.0), 1e-9)
        assertEquals(0.05, Auction.binSize(Double.NaN, 0.0), 1e-9)
        assertEquals(3L, Auction.binOf(35.0, 10.0)); assertEquals(-1L, Auction.binOf(-0.5, 10.0))
        assertEquals(35.0, Auction.center(3, 10.0), 1e-9)
    }

    @Test fun pocValueAreaAndNodes() {
        val v = listOf(1, 2, 5, 10, 4, 2, 8, 3, 1, 1).mapIndexed { i, x -> i.toLong() to x.toDouble() }.toMap()
        val l = Auction.levels(v, 10.0)!!
        assertEquals(35.0, l.poc, 1e-9)
        // 70% of 37 = 25.9: down to 5 (15), up to 4 (19), a tie up to 2 (21), up to 8 (29).
        assertEquals(25.0, l.vaLow, 1e-9); assertEquals(65.0, l.vah, 1e-9)
        assertEquals(listOf(65.0), l.hvns)
        assertEquals(listOf(85.0, 55.0), l.lvns)
        assertEquals(37.0, l.volume, 1e-9); assertEquals(95.0, l.high, 1e-9); assertEquals(5.0, l.low, 1e-9)
        assertNull(Auction.levels(emptyMap(), 10.0))
        assertNull(Auction.levels(mapOf(1L to 0.0), 10.0))
        assertNull(Auction.levels(mapOf(1L to 5.0), 0.0))
        // A tie for the POC goes to the bin nearest the profile's middle; one bin is all its own value area.
        val tie = Auction.levels(mapOf(0L to 5.0, 1L to 1.0, 2L to 5.0, 3L to 1.0, 4L to 1.0), 1.0)!!
        assertEquals(2.5, tie.poc, 1e-9)
        val one = Auction.levels(mapOf(7L to 3.0), 2.0)!!
        assertEquals(15.0, one.poc, 1e-9); assertEquals(one.poc, one.vah); assertEquals(one.poc, one.vaLow)
        assertTrue(one.hvns.isEmpty() && one.lvns.isEmpty())
    }

    @Test fun thePriorDayFromCandlesSpreadsEachCandleOverItsBins() {
        val c = listOf(
            Auction.Candle(0, 100.0, 109.0, 100.0, 105.0, 100),      // bin 10 only
            Auction.Candle(60, 105.0, 119.0, 110.0, 115.0, 300),     // bin 11 only (low/high used as given)
            Auction.Candle(120, 115.0, 129.0, 100.0, 120.0, 30),     // bins 10, 11, 12: 10 each
            Auction.Candle(180, 0.0, 0.0, 0.0, 120.0, 50),           // no range: left out
            Auction.Candle(240, 120.0, 121.0, 120.0, 120.0, 0),      // no volume: left out
        )
        val l = Auction.fromCandles(c, 10.0)!!
        assertEquals(115.0, l.poc, 1e-9)
        assertEquals(430.0, l.volume, 1e-9)
        assertEquals(115.0, l.vaLow, 1e-9); assertEquals(115.0, l.vah, 1e-9)
        assertNull(Auction.fromCandles(emptyList()))
        // The default bin follows the last close (0.05% of 55,000 = 27.5).
        assertEquals(27.5, Auction.fromCandles(listOf(Auction.Candle(0, 55_000.0, 55_010.0, 54_990.0, 55_000.0, 10)))!!.bin, 1e-9)
    }

    // ---- the regime ----

    @Test fun theRegimeAgainstThePriorValueArea() {
        val prior = Auction.Levels(10.0, 105.0, 120.0, 100.0, emptyList(), emptyList(), 1.0, 130.0, 90.0)
        assertEquals(Auction.Regime.INSIDE, Auction.regime(prior, 110.0, listOf(125.0, 126.0)))
        assertEquals(Auction.Regime.INSIDE, Auction.regime(prior, 120.0, emptyList()))
        assertEquals(Auction.Regime.ABOVE_TESTING, Auction.regime(prior, 125.0, listOf(118.0, 126.0)))
        assertEquals(Auction.Regime.ABOVE_TESTING, Auction.regime(prior, 125.0, listOf(126.0)))
        assertEquals(Auction.Regime.ABOVE_ACCEPTED, Auction.regime(prior, 125.0, listOf(90.0, 121.0, 126.0)))
        assertEquals(Auction.Regime.BELOW_ACCEPTED, Auction.regime(prior, 95.0, listOf(99.0, 98.0)))
        assertEquals(Auction.Regime.BELOW_TESTING, Auction.regime(prior, 95.0, listOf(99.0, 101.0)))
        assertNull(Auction.regime(null, 110.0, emptyList()))
        assertNull(Auction.regime(prior, 0.0, emptyList()))
        assertEquals("inside", Auction.Regime.INSIDE.chip)
        assertEquals("outside above (accepted)", Auction.Regime.ABOVE_ACCEPTED.words)
    }

    private fun minute(sec: Long, o: Double, h: Double, l: Double, c: Double, buy: Long = 0, sell: Long = 0, signed: Boolean = true) =
        Auction.Minute(sec, o, h, l, c, buy, sell, buy + sell, c * (buy + sell), c * c * (buy + sell), signed)

    @Test fun thirtyMinuteClosesCountFromTheOpen() {
        val ms = listOf(minute(at(9, 15), 1.0, 1.0, 1.0, 101.0), minute(at(9, 44), 1.0, 1.0, 1.0, 102.0),
            minute(at(9, 45), 1.0, 1.0, 1.0, 103.0), minute(at(10, 14), 1.0, 1.0, 1.0, 104.0), minute(at(10, 15), 1.0, 1.0, 1.0, 105.0),
            minute(at(9, 10), 1.0, 1.0, 1.0, 99.0))
        assertEquals(listOf(102.0, 104.0), Auction.closes30(ms, at(10, 20), open))
        assertEquals(listOf(102.0), Auction.closes30(ms, at(10, 14, 59), open))
        assertEquals(0, Auction.periodOf(at(9, 15), open)); assertEquals(-1, Auction.periodOf(at(9, 14), open))
        assertEquals(555, Auction.minuteOfDay(at(9, 15)))
        assertEquals(Auction.dayOf(at(0, 0, 1)), Auction.dayOf(at(23, 59)))
    }

    // ---- delta divergence ----

    @Test fun aHigherHighOnFallingDeltaIsBearishAndTheMirrorBullish() {
        fun win(highs: List<Double>, lows: List<Double>, deltas: List<Long>) = highs.indices.map { i ->
            val d = deltas[i]; minute(at(10, 0) + i * 60L, 100.0, highs[i], lows[i], 100.0, if (d > 0) d else 0, if (d < 0) -d else 0)
        }
        val now = at(10, 14, 30)
        val flat = List(15) { 100.0 }
        val bear = win(flat.mapIndexed { i, h -> if (i == 13) 105.0 else h }, List(15) { 95.0 }, List(15) { -10L })
        assertEquals(Auction.Divergence.BEARISH, Auction.divergence(bear, now))
        val bull = win(flat, List(15) { i -> if (i == 12) 90.0 else 95.0 }, List(15) { 10L })
        assertEquals(Auction.Divergence.BULLISH, Auction.divergence(bull, now))
        // The new high with rising delta agrees: no divergence; the extreme in the earlier part: none either.
        assertNull(Auction.divergence(win(flat.mapIndexed { i, h -> if (i == 13) 105.0 else h }, List(15) { 95.0 }, List(15) { 10L }), now))
        assertNull(Auction.divergence(win(flat.mapIndexed { i, h -> if (i == 2) 105.0 else h }, List(15) { 95.0 }, List(15) { -10L }), now))
        // Too few streamed minutes (candles carry no delta): no reading.
        assertNull(Auction.divergence(bear.take(8), now))
        assertNull(Auction.divergence(bear.map { it.copy(signed = false) }, now))
        // Only the recent part: nothing earlier to beat.
        assertNull(Auction.divergence(bear.drop(10), at(10, 14, 30), window = 5, recent = 5))
    }

    // ---- VWAP ----

    @Test fun vwapItsBandsAndTheAnchoredOnes() {
        val ms = listOf(minute(0, 100.0, 101.0, 99.0, 100.0, 100, 0), minute(60, 100.0, 110.0, 100.0, 110.0, 0, 100), minute(120, 110.0, 110.0, 90.0, 90.0, 50, 50))
        val v = Auction.vwap(ms)!!
        assertEquals(100.0, v.vwap, 1e-9)
        assertEquals(Math.sqrt(200.0 / 3), v.sd, 1e-9)
        assertEquals(100.0, v.atHigh!!, 1e-9)       // from the 110 minute on: (110 + 90) / 2
        assertEquals(90.0, v.atLow!!, 1e-9)
        assertEquals(1.0, v.sdUnits(100.0 + v.sd)!!, 1e-9)
        assertEquals(100.0 - 2 * v.sd, v.band(2).first, 1e-9)
        assertNull(Auction.Vwap(100.0, 0.0, null, null).sdUnits(101.0))
        assertNull(Auction.vwap(listOf(minute(0, 1.0, 1.0, 1.0, 1.0))))
    }

    // ---- the day ----

    @Test fun aDayBuildsItsProfileDeltaAndFootprintFromSignedTrades() {
        val d = Auction.Day(open)
        val t0 = at(9, 30) * 1000
        d.trade(t0, 55_000.0, 100, 1)
        d.trade(t0 + 1_000, 55_000.0, 50, -1)
        d.trade(t0 + 61_000, 55_030.0, 40, 1)
        d.trade(t0 + 62_000, 55_030.0, 10, 0)
        d.trade(t0 + 62_000, 0.0, 10, 1)                      // ignored
        d.trade(t0 + 62_000, 55_030.0, 0, 1)                  // ignored
        val s = d.snapshot("BANKNIFTY", at(9, 31, 30), null)!!
        assertEquals(27.5, s.bin, 1e-9)
        assertEquals(55_000.0 - 55_000.0 % 27.5 + 13.75, s.today!!.poc, 1e-6)
        assertEquals(90L, s.sessionDelta); assertEquals(90L, s.delta15)
        assertEquals(listOf(at(9, 30) to 50L, at(9, 31) to 40L), s.deltas)
        assertEquals(listOf(Auction.Foot(55_030.0, 40, 0), Auction.Foot(55_000.0, 100, 50)), s.footprint)
        assertEquals(190L, s.footprint.sumOf { it.total })
        assertEquals(55_030.0, s.last)
        assertNull(s.regime)
        assertEquals(at(9, 30), s.streamedFrom); assertFalse(s.seeded)
        // The footprint keeps 15 minutes; the next IST day starts afresh.
        assertTrue(d.footprint(at(9, 50)).isEmpty())
        assertNull(d.snapshot("BANKNIFTY", at(9, 31, 30, day = 10), null))
        d.trade(at(9, 15, day = 10) * 1000, 55_100.0, 5, 1)
        assertEquals(5L, d.snapshot("BANKNIFTY", at(9, 16, day = 10), null)!!.sessionDelta)
        assertNull(Auction.Day().snapshot("X", 0, null))
    }

    @Test fun candlesFillTheMinutesBeforeTheStreamWithoutCountingTwice() {
        val d = Auction.Day(open)
        val c = listOf(Auction.Candle(at(9, 15), 100.0, 101.0, 99.0, 100.0, 1000), Auction.Candle(at(9, 16), 100.0, 102.0, 100.0, 102.0, 500),
            Auction.Candle(at(9, 17), 102.0, 103.0, 101.0, 103.0, 700), Auction.Candle(at(9, 15, day = 8), 1.0, 1.0, 1.0, 1.0, 9))
        d.seed(c, at(9, 17, 30))          // nothing streamed: the completed minutes only (9:15, 9:16)
        var s = d.snapshot("NIFTY", at(9, 17, 30), null)!!
        assertTrue(s.seeded); assertNull(s.streamedFrom)
        val seeded = s.today!!.volume
        assertEquals(1500.0, seeded, 40.0)                 // each candle spread over its bins (whole shares)
        assertEquals(0L, s.sessionDelta)
        assertEquals(1500L, d.minutes().sumOf { it.vol })
        // A trade in a seeded minute is not counted again; one after is.
        d.trade(at(9, 16, 30) * 1000, 102.0, 999, 1)
        d.trade(at(9, 17, 40) * 1000, 103.0, 10, 1)
        s = d.snapshot("NIFTY", at(9, 18), null)!!
        assertEquals(seeded + 10, s.today!!.volume, 1e-9)
        assertEquals(10L, s.sessionDelta)
        // Seeding again later only adds minutes before the first streamed one, never over them.
        d.seed(c, at(9, 20))
        assertEquals(seeded + 10, d.snapshot("NIFTY", at(9, 20), null)!!.today!!.volume, 1e-9)
        assertNotNull(s.vwap)
        // No candles of today: nothing.
        val e = Auction.Day(); e.seed(listOf(c.last()), at(9, 17)); assertNull(e.snapshot("NIFTY", at(9, 17), null))
    }

    @Test fun theDetailLinesAndJarvisWords() {
        val d = Auction.Day(open)
        for (i in 0 until 50) d.trade((at(9, 15) + i * 60L) * 1000, 100.0 + i, 10, if (i % 2 == 0) 1 else -1)
        val prior = Auction.Levels(10.0, 105.0, 120.0, 100.0, listOf(110.0), listOf(115.0), 1.0, 130.0, 90.0)
        val s = d.snapshot("BANKNIFTY", at(10, 16), prior)!!
        assertEquals(Auction.Regime.ABOVE_ACCEPTED, s.regime)
        val lines = Auction.lines(s).toMap()
        assertEquals("outside above (accepted)", lines["Auction (vs the prior day's value)"])
        assertEquals("POC 105 · VA 100–120 · HVN 110 · LVN 115", lines["Prior day's profile"])
        assertTrue("VWAP (future)" in lines && "Initial balance (first 60 min)" in lines, lines.keys.toString())
        assertEquals("not known yet", Auction.levelsLine(null))
        assertEquals("BN: accepted↑", Auction.homeWord("BANKNIFTY", s))
        assertEquals("NF: —", Auction.homeWord("NIFTY", null))
        val a = Auction.answer("BANKNIFTY", s, at(10, 16))
        assertTrue("BankNifty's future" in a && "outside above (accepted)" in a && "not a signal" in a, a)
        assertTrue("no live volume profile" in Auction.answer("BANKNIFTY", null, 0))
        assertTrue("no live volume profile" in Auction.answer("BANKNIFTY", s, at(10, 30)))
        assertEquals(Auction.Topic.PROFILE, Auction.topic("what's the volume profile on banknifty?"))
        assertEquals(Auction.Topic.GAMMA, Auction.topic("gamma regime on banknifty"))
        assertEquals(Auction.Topic.GAMMA, Auction.topic("BankNifty GEX?"))
        assertNull(Auction.topic("order flow on banknifty"))
        // OrderFlow's question carries these (the same live branch answers them), with the index named.
        assertEquals("BANKNIFTY", OrderFlow.asked("what's the volume profile on banknifty?"))
        assertEquals("BANKNIFTY", OrderFlow.asked("what's the gamma regime on banknifty?"))
        assertEquals("NIFTY", OrderFlow.asked("nifty value area"))
    }

    @Test fun theChartsLinesAreInTheIndexsPrices() {
        val prior = Auction.Levels(10.0, 105.0, 120.0, 100.0, emptyList(), emptyList(), 1.0, 130.0, 90.0)
        val s = Auction.Snapshot("NIFTY", 0, 110.0, 10.0, prior, prior, null, 0, 0, null, emptyList(), emptyList(),
            Auction.Vwap(110.0, 2.0, null, null), null, null, false)
        val l = Auction.chartLevels(s, 10.0)
        assertEquals(listOf("pPOC", "pVAH", "pVAL", "POC", "VAH", "VAL", "VWAP", "+1σ", "−1σ", "+2σ", "−2σ"), l.map { it.label })
        assertEquals(95.0, l.first().price); assertEquals(96.0, l.last().price)
        assertEquals(6, l.count { it.kind == Auction.ChartLevel.Kind.PROFILE })
        val bare = Auction.chartLevels(s.copy(prior = null, today = null, vwap = Auction.Vwap(110.0, 0.0, null, null)), 0.0)
        assertEquals(listOf("VWAP"), bare.map { it.label })
    }

    // ---- the market profile ----

    @Test fun openingTypes() {
        fun ms(vararg ohlc: Double) = ohlc.toList().chunked(4).mapIndexed { i, x -> minute(i * 60L, x[0], x[1], x[2], x[3]) }
        // Opened at the low, closed at the high, the next period never back through the open: a drive up.
        assertEquals(MarketProfile.OpenType.OPEN_DRIVE to 1, MarketProfile.openType(ms(100.0, 105.0, 100.0, 105.0, 105.0, 110.0, 104.0, 109.0), ms(109.0, 112.0, 101.0, 111.0)))
        assertEquals(MarketProfile.OpenType.OPEN_DRIVE to -1, MarketProfile.openType(ms(110.0, 110.0, 105.0, 105.0, 105.0, 106.0, 100.0, 101.0), emptyList()))
        // Back through the open in B: not a drive; the low came first and A closed at its top: a test-drive.
        assertEquals(MarketProfile.OpenType.OPEN_TEST_DRIVE to 1, MarketProfile.openType(ms(100.0, 105.0, 100.0, 105.0, 105.0, 110.0, 104.0, 109.0), ms(109.0, 109.0, 99.0, 100.0)))
        // Tested down first, then closed at the top: test-drive up; the mirror down.
        assertEquals(MarketProfile.OpenType.OPEN_TEST_DRIVE to 1, MarketProfile.openType(ms(105.0, 105.0, 100.0, 101.0, 101.0, 112.0, 101.0, 111.0), emptyList()))
        assertEquals(MarketProfile.OpenType.OPEN_TEST_DRIVE to -1, MarketProfile.openType(ms(105.0, 110.0, 105.0, 109.0, 109.0, 109.0, 98.0, 99.0), emptyList()))
        // Tested up, rejected back below the open, not at the low: rejection-reverse down; the mirror up.
        assertEquals(MarketProfile.OpenType.OPEN_REJECTION_REVERSE to -1, MarketProfile.openType(ms(105.0, 110.0, 105.0, 108.0, 108.0, 108.0, 101.0, 104.0), emptyList()))
        assertEquals(MarketProfile.OpenType.OPEN_REJECTION_REVERSE to 1, MarketProfile.openType(ms(105.0, 105.0, 100.0, 102.0, 102.0, 109.0, 102.0, 106.0), emptyList()))
        // Rotation around the open; no range; no minutes.
        assertEquals(MarketProfile.OpenType.OPEN_AUCTION to 0, MarketProfile.openType(ms(105.0, 110.0, 105.0, 106.0, 106.0, 106.0, 100.0, 106.0), emptyList()))
        assertEquals(MarketProfile.OpenType.OPEN_AUCTION to 0, MarketProfile.openType(ms(100.0, 100.0, 100.0, 100.0), emptyList()))
        assertNull(MarketProfile.openType(emptyList(), emptyList()))
    }

    @Test fun dayTypes() {
        assertEquals(MarketProfile.DayType.NORMAL, MarketProfile.dayType(110.0, 100.0, 111.0, 100.0, 105.0))
        assertEquals(MarketProfile.DayType.NORMAL_VARIATION, MarketProfile.dayType(110.0, 100.0, 115.0, 100.0, 105.0))
        assertEquals(MarketProfile.DayType.TREND, MarketProfile.dayType(110.0, 100.0, 125.0, 100.0, 124.0))
        assertEquals(MarketProfile.DayType.TREND, MarketProfile.dayType(110.0, 100.0, 110.0, 85.0, 86.0))
        assertEquals(MarketProfile.DayType.NORMAL_VARIATION, MarketProfile.dayType(110.0, 100.0, 125.0, 100.0, 105.0))   // back off the high
        assertEquals(MarketProfile.DayType.NEUTRAL, MarketProfile.dayType(110.0, 100.0, 112.0, 98.0, 105.0))
        assertNull(MarketProfile.dayType(100.0, 100.0, 101.0, 99.0, 100.0))
    }

    @Test fun theTpoReadsIbSinglePrintsAndPoorExtremes() {
        val ms = ArrayList<Auction.Minute>()
        // A: 100-110, B: 105-112, C: 130-140 (a gap of single prints 113-129 between B and C? untraded, so not prints), D: 112-135.
        fun period(p: Int, lo: Double, hi: Double) { for (k in 0 until 30) ms += minute(at(9, 15) + (p * 30 + k) * 60L, lo, hi, lo, if (k == 29) hi else lo) }
        period(0, 100.0, 110.0); period(1, 105.0, 112.0); period(2, 112.0, 140.0); period(3, 138.0, 140.0)
        val r = MarketProfile.read(ms, at(11, 20), open, 1.0)!!
        assertEquals(112.0, r.ibHigh!!, 1e-9); assertEquals(100.0, r.ibLow!!, 1e-9); assertTrue(r.ibDone)
        assertEquals(12.0, r.ibRange!!, 1e-9)
        assertEquals(4, r.periods)
        assertNotNull(r.openType)
        assertEquals(MarketProfile.DayType.TREND, r.dayType)
        // Single prints: A alone 101-104, B alone 111, C alone 113-137; the high (140) traded in C and D: a poor high; the low
        // (A only) is not.
        assertEquals(listOf(101.5 to 104.5, 111.5 to 111.5, 113.5 to 137.5), r.singles)
        assertTrue(r.poorHigh); assertFalse(r.poorLow)
        val lines = MarketProfile.lines(r).toMap()
        assertEquals("100–112 (12 pts)", lines["Initial balance (first 60 min)"])
        assertEquals("trend", lines["Day type so far"])
        assertTrue("poor high" in lines["Single prints / poor high, low"]!!)
        // The first period not done: no open type; the IB still forming; before the open: nothing.
        val early = MarketProfile.read(ms.take(10), at(9, 25), open, 1.0)!!
        assertNull(early.openType); assertFalse(early.ibDone); assertNull(early.dayType)
        assertEquals("after the first 30 minutes", MarketProfile.lines(early).toMap()["Opening type"])
        assertTrue(MarketProfile.lines(early).toMap()["Initial balance (first 60 min)"]!!.endsWith("still forming"))
        assertNull(MarketProfile.read(listOf(minute(at(9, 0), 1.0, 1.0, 1.0, 1.0)), at(9, 10), open, 1.0))
        assertNull(MarketProfile.read(ms, at(11, 20), open, 0.0))
    }

    // ---- the board ----

    private fun tick(token: Long, last: Double, volume: Long, bid: Double, ask: Double, bq: Long = 100, aq: Long = 100) = KiteTicks.Tick(token, last,
        volume = volume, bid = bid, ask = ask, bidQty = bq, askQty = aq, buyQty = 5000, sellQty = 5000, lastTradeTime = null,
        depth = KiteTicks.Depth(listOf(KiteTicks.Level(bid, bq, 3)), listOf(KiteTicks.Level(ask, aq, 3))))

    @Test fun theBoardKeepsTheFuturesAuctionTapeAndHeatmap() {
        val b = OrderFlow.Board()
        b.layout(listOf(OrderFlow.Board.Entry(1, "BANKNIFTY", Role.FUTURE), OrderFlow.Board.Entry(9, "BANKNIFTY", Role.WATCH, 55_000.0)))
        val t0 = at(9, 30) * 1000
        var vol = 0L; var px = 55_000.0
        for (s in 0 until 120) {
            vol += if (s == 100) 500 else 10
            px += if (s % 2 == 0) 0.5 else -0.25
            b.offer(tick(1, px, vol, px - 0.5, px + 0.5), t0 + s * 1000L)
            b.offer(tick(9, 200.0 + s % 3, vol, 199.0, 203.0), t0 + s * 1000L + 5)
        }
        val a = b.auction("BANKNIFTY", t0 + 120_000)!!
        assertTrue(a.today != null && a.vwap != null && a.footprint.isNotEmpty())
        assertNull(b.auction("NIFTY", t0))
        // The prior day's profile sets the regime.
        b.prior("BANKNIFTY", Auction.Levels(27.5, 54_000.0, 54_100.0, 53_900.0, emptyList(), emptyList(), 1.0, 54_200.0, 53_800.0))
        assertEquals(Auction.Regime.ABOVE_TESTING, b.auction("BANKNIFTY", t0 + 120_000)!!.regime)
        assertNotNull(b.prior("BANKNIFTY")); b.prior("BANKNIFTY", null); assertNull(b.prior("BANKNIFTY"))
        // Time & sales, the big print flagged; the charted option's own tape.
        val tape = b.tape("BANKNIFTY", watch = false)
        assertEquals(119, tape.size)
        assertTrue(tape.first { it.qty == 500L }.big)
        assertTrue(b.tape("BANKNIFTY", watch = true).isNotEmpty())
        assertTrue(b.tape("NIFTY", watch = false).isEmpty())
        // Heatmaps for the index future and the charted option, with the big print's dot.
        val f = b.heat("BANKNIFTY", watch = false, nowMs = t0 + 120_000)!!
        assertTrue(f.columns.isNotEmpty() && f.maxQty == 100L)
        assertTrue(f.dots.any { it.kind == TapeHeat.DotKind.BIG_BUY || it.kind == TapeHeat.DotKind.BIG_SELL })
        assertNotNull(b.heat("BANKNIFTY", watch = true, nowMs = t0 + 120_000))
        // The charted option is never part of the read's option side.
        assertTrue(b.read("BANKNIFTY", t0 + 120_000)!!.optionNet.isNaN())
        // Seeding needs a followed future.
        assertTrue(b.seed("BANKNIFTY", emptyList(), at(9, 32)))
        assertFalse(b.seed("NIFTY", emptyList(), at(9, 32)))
        // A layout without them drops the heatmap of what is no longer an index future.
        b.layout(listOf(OrderFlow.Board.Entry(1, "CRUDEOIL", Role.FUTURE)))
        assertNull(b.heat("CRUDEOIL", watch = false, nowMs = t0))
    }

    @Test fun aChartedOptionAlreadyFollowedAsAnAtmCallKeepsItsRoleAndGetsAHeatmap() {
        val b = OrderFlow.Board()
        b.layout(listOf(OrderFlow.Board.Entry(5, "NIFTY", Role.CE, 25_000.0)))
        assertNull(b.heat("NIFTY", watch = true, nowMs = 0))
        b.watch(5)
        b.offer(tick(5, 100.0, 10, 99.0, 101.0), at(10, 0) * 1000)
        b.offer(tick(5, 101.0, 20, 100.0, 102.0), at(10, 0, 1) * 1000)
        assertNotNull(b.heat("NIFTY", watch = true, nowMs = at(10, 0, 2) * 1000))
        assertEquals(1, b.tape("NIFTY", watch = true).size)
        assertEquals(Role.CE, b.entries().single().role)
        b.watch(null)
        assertNull(b.heat("NIFTY", watch = true, nowMs = 0))
    }

    @Test fun theAbsorptionNote() {
        val base = OrderFlow.Read("BANKNIFTY", 1, OrderFlow.Side.BALANCED, 50, 50, true, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, null, emptyMap())
        assertNull(OrderFlow.absorption(base)); assertNull(OrderFlow.absorption(null))
        assertEquals("absorption at 52,310 (sellers)", OrderFlow.absorption(base.copy(absorbAt = 52_310.0, absorbBy = -1)))
        assertEquals("absorption at 52,310.50 (buyers)", OrderFlow.absorption(base.copy(absorbAt = 52_310.5, absorbBy = 1)))
        assertEquals("none", OrderFlow.components(base).toMap()["Absorption (last minute)"])
    }
}
