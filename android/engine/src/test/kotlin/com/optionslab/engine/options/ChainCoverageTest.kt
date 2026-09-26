package com.optionslab.engine.options

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Chain analytics, the snapshot that bundles them, and template resolution at their edges. */
class ChainCoverageTest {
    private val expiry = LocalDate.of(2026, 9, 29)
    private val now = ZonedDateTime.of(2026, 9, 22, 10, 0, 0, 0, OptionMath.IST)
    private val t = OptionMath.timeToExpiryYears(now, expiry)

    /** A priced chain around F = 24000 at 14% IV. */
    private fun leg(type: OptionType, k: Double, f: Double = 24000.0, oi: Long = 1000, vol: Long = 50, lot: Int? = 65, prev: Long? = null) =
        OptLeg("NIFTY29SEP26${k.toInt()}${type}", OptionMath.price(type, f, k, t, 0.0, 0.14), oi = oi, volume = vol, prevOi = prev, lotSize = lot)

    private fun chain(f: Double = 24000.0) = (0..8).map { i ->
        val k = 23800.0 + 50 * i
        ChainRow(k, leg(OptionType.CE, k, f), leg(OptionType.PE, k, f, oi = 1500))
    }

    private fun near(a: Double, b: Double, tol: Double = 1e-9) = assertTrue(abs(a - b) <= tol, "$a vs $b")

    // ------------------------------------------------------------ strikes

    @Test fun `ATM, labels and offsets`() {
        assertNull(ChainAnalytics.atmStrike(100.0, emptyList())); assertNull(ChainAnalytics.atmStrike(Double.NaN, listOf(1.0)))
        assertNull(ChainAnalytics.atmStrike(Double.POSITIVE_INFINITY, listOf(1.0)))
        assertEquals(100.0, ChainAnalytics.atmStrike(125.0, listOf(100.0, 150.0)), "a tie keeps the earlier strike")
        val ks = listOf(100.0, 150.0, 200.0, 250.0)
        assertEquals(ks.map { StrikeLabel(it, "", "") }, ChainAnalytics.strikeLabels(ks, 175.0))
        assertEquals(listOf(StrikeLabel(100.0, "ITM1", "OTM1"), StrikeLabel(150.0, "ATM", "ATM"), StrikeLabel(200.0, "OTM1", "ITM1")),
            ChainAnalytics.strikeLabels(ks, 150.0, 1))
        assertNull(ChainAnalytics.offsetStrike(150.0, "ATM", OptionType.CE, emptyList()))
        assertNull(ChainAnalytics.offsetStrike(175.0, "ATM", OptionType.CE, ks))
        assertEquals(150.0, ChainAnalytics.offsetStrike(150.0, "atm", OptionType.PE, ks))
        assertEquals(100.0, ChainAnalytics.offsetStrike(150.0, "itm1", OptionType.CE, ks))
        assertEquals(200.0, ChainAnalytics.offsetStrike(150.0, "ITM1", OptionType.PE, ks))
        assertEquals(250.0, ChainAnalytics.offsetStrike(150.0, "OTM2", OptionType.CE, ks))
        assertEquals(100.0, ChainAnalytics.offsetStrike(150.0, "OTM1", OptionType.PE, ks))
        assertNull(ChainAnalytics.offsetStrike(150.0, "OTM3", OptionType.CE, ks), "off the ladder")
        assertNull(ChainAnalytics.offsetStrike(150.0, "OTMx", OptionType.CE, ks))
        assertNull(ChainAnalytics.offsetStrike(150.0, "XYZ1", OptionType.CE, ks))
        assertEquals(null to emptyList<ChainRow>(), ChainAnalytics.chainWindow(emptyList(), 100.0, 2))
        val (atm, win) = ChainAnalytics.chainWindow(chain(), 24010.0, 1)
        assertEquals(24000.0, atm); assertEquals(listOf(23950.0, 24000.0, 24050.0), win.map { it.strike })
    }

    @Test fun `forward from the chain needs both ATM legs priced`() {
        val c = chain()
        near(ChainAnalytics.forwardFromChain(c, 24000.0, 1.0), 24000.0, 1e-3)
        assertEquals(1.0, ChainAnalytics.forwardFromChain(c, 99.0, 1.0))
        assertEquals(1.0, ChainAnalytics.forwardFromChain(listOf(ChainRow(24000.0, null, leg(OptionType.PE, 24000.0))), 24000.0, 1.0))
        assertEquals(1.0, ChainAnalytics.forwardFromChain(listOf(ChainRow(24000.0, leg(OptionType.CE, 24000.0), null)), 24000.0, 1.0))
        assertEquals(1.0, ChainAnalytics.forwardFromChain(listOf(ChainRow(24000.0, leg(OptionType.CE, 24000.0).copy(ltp = 0.0), leg(OptionType.PE, 24000.0))), 24000.0, 1.0))
    }

    // ------------------------------------------------------------ OI

    @Test fun `PCR, OI data and lot size discovery`() {
        val rows = listOf(ChainRow(100.0, null, OptLeg("P", 1.0, oi = 300, volume = 10, lotSize = 0)),
            ChainRow(110.0, OptLeg("C", 1.0, oi = 200, volume = 0, lotSize = 0), OptLeg("P2", 1.0, oi = 100, lotSize = 25)))
        val p = ChainAnalytics.pcr(rows)
        assertEquals(Pcr(200, 400, 0, 10, 2.0, 0.0), p)
        val d = ChainAnalytics.oiData(rows)
        assertEquals(25, d.lotSize); assertNull(d.spotPrice)
        assertEquals(listOf(OiStrike(100.0, 0, 300), OiStrike(110.0, 200, 100)), d.chain)
        assertEquals(1, ChainAnalytics.oiData(listOf(ChainRow(1.0, null, null))).lotSize)
        assertEquals(40, ChainAnalytics.oiData(listOf(ChainRow(1.0, OptLeg("C", 1.0, lotSize = 40), null))).lotSize)
        assertEquals(1, ChainAnalytics.oiData(listOf(ChainRow(1.0, OptLeg("C", 1.0, lotSize = null), OptLeg("P", 1.0, lotSize = null)))).lotSize)
    }

    @Test fun `max pain picks the least total pain and skips non-positive strikes`() {
        assertNull(ChainAnalytics.maxPain(emptyList()))
        assertNull(ChainAnalytics.maxPain(listOf(ChainRow(0.0, OptLeg("C", 1.0, oi = 5), null))))
        val rows = listOf(
            ChainRow(90.0, OptLeg("C90", 1.0, oi = 100), OptLeg("P90", 1.0, oi = 0)),
            ChainRow(100.0, OptLeg("C100", 1.0, oi = 0), OptLeg("P100", 1.0, oi = 0)),
            ChainRow(110.0, OptLeg("C110", 1.0, oi = 0), OptLeg("P110", 1.0, oi = 100)),
        )
        val mp = ChainAnalytics.maxPain(rows, spotPrice = 100.0, futuresPrice = 101.0, atmStrike = 100.0)!!
        // Every candidate costs writers 2000: the tie goes to the first, as Python's min() does.
        assertEquals(listOf(2000.0, 2000.0, 2000.0), mp.painData.map { it.totalPain })
        assertEquals(90.0, mp.maxPainStrike)
        assertEquals(listOf(2000.0, 1000.0, 2000.0), listOf(mp.painData[0].pePain, mp.painData[1].cePain, mp.painData[2].cePain))
        val skew = ChainAnalytics.maxPain(rows + ChainRow(120.0, null, OptLeg("P120", 1.0, oi = 100)))!!
        assertEquals(110.0, skew.maxPainStrike)
        assertEquals(101.0, mp.oi.futuresPrice)
    }

    @Test fun `OI change reads a missing previous OI as zero and ignores unlisted legs`() {
        val rows = listOf(ChainRow(100.0, OptLeg("C", 1.0, oi = 500, prevOi = 300), OptLeg("P", 1.0, oi = 400)),
            ChainRow(110.0, OptLeg("", 1.0, oi = 50), OptLeg("P2", 1.0, oi = 0, prevOi = 10)), ChainRow(120.0, null, null))
        val p = ChainAnalytics.oiProfile(rows, 101.0, 100.0)
        assertEquals(listOf(200.0, 400.0), listOf(p.chain[0].ceOiChange, p.chain[0].peOiChange))
        assertEquals(listOf(0.0, 0.0), listOf(p.chain[1].ceOiChange, p.chain[1].peOiChange))
        assertEquals(OiProfileStrike(120.0, 0, 0, 0.0, 0.0), p.chain[2])
        assertEquals(p.chain, ChainAnalytics.oiChange(rows))
        assertEquals(101.0, p.spotPrice); assertEquals(1, p.lotSize)
    }

    // ------------------------------------------------------------ straddles

    private fun ts(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 9, day, h, m, 0, 0, OptionMath.IST).toEpochSecond()

    @Test fun `trading-date capping and days to expiry`() {
        val pts = listOf(TimeValue(ts(18, 10, 0), 1.0), TimeValue(ts(21, 10, 0), 2.0), TimeValue(ts(22, 10, 0), 3.0), TimeValue(ts(22, 11, 0), 4.0))
        assertEquals(pts, ChainAnalytics.capLastNTradingDates(pts, 0) { it.time })
        assertEquals(emptyList(), ChainAnalytics.capLastNTradingDates(emptyList<TimeValue>(), 3) { it.time })
        assertEquals(listOf(2.0, 3.0, 4.0), ChainAnalytics.capLastNTradingDates(pts, 2) { it.time }.map { it.value })
        assertEquals(7L, ChainAnalytics.daysToExpiry(now, expiry))
        assertEquals(0L, ChainAnalytics.daysToExpiry(now.plusDays(30), expiry))
        assertEquals(listOf(100.0, 200.0), ChainAnalytics.straddleStrikes(listOf(TimeValue(1, 110.0), TimeValue(2, 190.0), TimeValue(3, 90.0)), listOf(100.0, 200.0)))
        assertEquals(emptyList(), ChainAnalytics.straddleStrikes(listOf(TimeValue(1, 110.0)), emptyList()))
    }

    @Test fun `dynamic straddle drops candles without both legs`() {
        val t1 = ts(22, 9, 15); val t2 = ts(22, 9, 16); val t3 = ts(22, 9, 17)
        val closes = mapOf(100.0 to StrikeCloses(mapOf(t1 to 5.0, t2 to 6.0), mapOf(t1 to 4.0)), 200.0 to StrikeCloses(mapOf(t3 to 1.0), mapOf(t3 to 2.0)))
        val s = ChainAnalytics.straddle(listOf(TimeValue(t3, 190.0), TimeValue(t2, 101.0), TimeValue(t1, 100.4), TimeValue(t1 + 1, 150.0)),
            listOf(100.0, 150.0, 200.0), closes, 5, now, expiry)
        assertEquals(listOf(t1, t3), s.series.map { it.time })
        assertEquals(StraddlePoint(t1, 100.4, 100.0, 5.0, 4.0, 9.0, 101.0), s.series[0])
        assertEquals(199.0, s.series[1].syntheticFuture)
        assertEquals(7L, s.daysToExpiry)
        assertTrue(ChainAnalytics.straddle(listOf(TimeValue(t1, 100.0)), emptyList(), closes, 1, now, expiry).series.isEmpty())
    }

    @Test fun `custom straddle - entry, adjustment, gaps and exit`() {
        val d1 = listOf(ts(21, 9, 15), ts(21, 9, 16), ts(21, 9, 17), ts(21, 9, 18), ts(21, 9, 19))
        val d2 = listOf(ts(22, 9, 15), ts(22, 9, 16))
        val c100 = StrikeCloses(mapOf(d1[1] to 10.0, d1[2] to 12.0, d1[3] to 13.0, d2[0] to 8.0, d2[1] to 7.0),
            mapOf(d1[1] to 10.0, d1[2] to 6.0, d1[3] to 5.0, d2[0] to 8.0, d2[1] to 7.5))
        val c150 = StrikeCloses(mapOf(d1[3] to 9.0, d1[4] to 8.0), mapOf(d1[3] to 9.0, d1[4] to 7.0))
        val closes = mapOf(100.0 to c100, 150.0 to c150)
        val u = listOf(
            TimeValue(d1[0], 101.0),        // no leg prints yet: waits
            TimeValue(d1[1], 101.0),        // entry at 100: 10 + 10
            TimeValue(d1[2], 110.0),        // mark: (10-12)+(10-6) = 2
            TimeValue(d1[3], 150.0),        // drift 50: close 100 at 13/5 (+2), sell 150 at 9/9
            TimeValue(d1[4], 151.0),        // last: 150 at 8/7 -> +3
            TimeValue(d2[0], 5000.0),       // ATM 150 has no quote this day... ATM strike not in closes? it is; legs missing
            TimeValue(d2[1], 100.0),        // entry day 2 at 100: 8 + 7.5 ... last candle
        )
        val cs = ChainAnalytics.customStraddle(u, listOf(100.0, 150.0), closes, days = 2, adjustmentPoints = 50.0, lotSize = 1, lots = 2)
        assertEquals(2, cs.quantity)
        assertEquals(listOf(StraddleTradeType.ENTRY, StraddleTradeType.ADJUSTMENT, StraddleTradeType.EXIT, StraddleTradeType.ENTRY, StraddleTradeType.EXIT),
            cs.trades.map { it.type })
        val adj = cs.trades[1]
        assertEquals(100.0, adj.oldStrike); assertEquals(4.0, adj.legPnl)
        assertEquals(6.0, cs.trades[2].legPnl, "final 150 leg: (9-8)+(9-7) = 3 x 2")
        assertEquals(1, cs.totalAdjustments)
        assertEquals(10.0, cs.totalPnl, "day 1: 4 + 6; day 2 enters and exits on its only priced candle")
        assertTrue(cs.maxPnl >= cs.minPnl)
        // Days <= 0 still takes one session; no data at all -> zeros.
        val empty = ChainAnalytics.customStraddle(emptyList(), listOf(100.0), closes, days = 0)
        assertEquals(0.0, empty.totalPnl); assertEquals(0.0, empty.maxPnl); assertEquals(0.0, empty.minPnl)
    }

    @Test fun `custom straddle holds the last mark when the held legs stop printing`() {
        val d = listOf(ts(22, 9, 15), ts(22, 9, 16), ts(22, 9, 17))
        val closes = mapOf(100.0 to StrikeCloses(mapOf(d[0] to 10.0, d[1] to 8.0), mapOf(d[0] to 10.0, d[1] to 9.0)),
            200.0 to StrikeCloses(mapOf(d[2] to 1.0), mapOf(d[2] to 1.0)))
        // The drift to 200 cannot adjust (old legs missing at d[2]); the exit uses the last mark (+3).
        val cs = ChainAnalytics.customStraddle(listOf(TimeValue(d[0], 100.0), TimeValue(d[1], 100.0), TimeValue(d[2], 200.0)),
            listOf(100.0, 200.0), closes, lotSize = 1)
        assertEquals(0, cs.totalAdjustments)
        assertEquals(3.0, cs.trades.last().legPnl); assertEquals(0.0, cs.trades.last().cePrice)
        assertEquals(3.0, cs.totalPnl)
        assertEquals(201.0 - 1.0 + 1.0 - 1.0, cs.pnlSeries.last().syntheticFuture, 1e-9)
        // A candle whose ATM has no closes at all is skipped.
        val skip = ChainAnalytics.customStraddle(listOf(TimeValue(d[0], 300.0)), listOf(300.0), closes)
        assertTrue(skip.pnlSeries.isEmpty())
    }

    // ------------------------------------------------------------ volatility

    @Test fun `IV smile - ATM mean, one-sided ATM, skew and refusals`() {
        assertNull(ChainAnalytics.ivSmile(chain(), 0.0, 24000.0, t))
        val s = ChainAnalytics.ivSmile(chain(), 24000.0, 24000.0, t)!!
        near(s.atmIv!!, 14.0, 0.05)
        assertEquals(0.0, s.skew!!, 0.05)
        // ATM with only a put priced: its IV alone.
        val oneSided = chain().map { if (it.strike == 24000.0) it.copy(ce = null) else it }
        near(ChainAnalytics.ivSmile(oneSided, 24000.0, 24000.0, t)!!.atmIv!!, 14.0, 0.05)
        // Unlisted or unpriced legs have no IV; no ATM gives no skew.
        val bare = chain().map { it.copy(ce = it.ce!!.copy(symbol = ""), pe = it.pe!!.copy(ltp = 0.0)) }
        val b = ChainAnalytics.ivSmile(bare, 24000.0, null, t)!!
        assertTrue(b.chain.all { it.ceIv == null && it.peIv == null }); assertNull(b.atmIv); assertNull(b.skew)
        assertNull(ChainAnalytics.ivSmile(emptyList(), 24000.0, 24000.0, t)!!.skew)
        assertNull(ChainAnalytics.ivSmile(chain(), 24000.0, 0.0, t)!!.skew)
    }

    @Test fun `vol surface - shared strikes, fallback window and empty expiries`() {
        assertNull(ChainAnalytics.volSurface(24000.0, emptyList(), 2, now))
        assertNull(ChainAnalytics.volSurface(24000.0, listOf(SurfaceExpiry("29SEP26", expiry, emptyList())), 2, now))
        val e1 = SurfaceExpiry("29SEP26", expiry, chain())
        val e2 = SurfaceExpiry("06OCT26", expiry.plusDays(7), chain().drop(3))
        val v = ChainAnalytics.volSurface(24000.0, listOf(e1, e2), 2, now)!!
        assertEquals(listOf(23950.0, 24000.0, 24050.0, 24100.0), v.strikes, "the strikes both windows share")
        assertEquals(2, v.surface.size); assertEquals(24000.0, v.atmStrike)
        assertEquals(listOf(7.2, 14.2), v.expiries.map { it.dte })
        // Fewer than three shared: the first expiry's window. An unpriced leg is a gap.
        val e3 = SurfaceExpiry("13OCT26", expiry.plusDays(14), chain().drop(7).map { it.copy(ce = it.ce!!.copy(ltp = 0.0)) })
        val v2 = ChainAnalytics.volSurface(24000.0, listOf(e1, e3), 1, now, LocalTime.of(15, 30), 0.0)!!
        assertEquals(listOf(23950.0, 24000.0, 24050.0), v2.strikes)
        assertTrue(v2.surface[1].all { it == null })
    }

    // ------------------------------------------------------------ gamma

    @Test fun `GEX sums rounded rows and skips unpriced legs`() {
        assertNull(ChainAnalytics.gex(chain(), 0.0, 24000.0, t))
        val rows = chain().mapIndexed { i, r -> if (i == 0) r.copy(ce = r.ce!!.copy(oi = 0), pe = r.pe!!.copy(symbol = "")) else if (i == 1) r.copy(pe = null, ce = r.ce!!.copy(lotSize = null)) else r }
        val g = ChainAnalytics.gex(rows, 24000.0, 24000.0, t, futuresPrice = 24010.0)!!
        assertEquals(65, g.lotSize); assertEquals(24010.0, g.futuresPrice)
        assertEquals(0.0, g.chain[0].ceGex); assertEquals(0L, g.chain[0].peOi)
        assertTrue(g.chain[4].ceGamma > 0 && g.chain[4].netGex != 0.0 || g.chain[4].ceGex > 0)
        near(g.totalNetGex, g.totalCeGex - g.totalPeGex, 0.2)   // each row rounds on its own
        val none = ChainAnalytics.gex(listOf(ChainRow(1.0, null, null)), 24000.0, null, t)!!
        assertEquals(1, none.lotSize); assertEquals(0.0, none.pcrOi)
    }

    @Test fun `gamma density - forwards, IV fallbacks and bands`() {
        assertNull(ChainAnalytics.gammaDensity(chain(), 0.0, 24000.0, null, t, t * 365))
        assertNull(ChainAnalytics.gammaDensity(chain(), Double.NaN, 24000.0, null, t, t * 365))
        assertNull(ChainAnalytics.gammaDensity(emptyList(), 24000.0, 24000.0, null, t, t * 365))
        val g = ChainAnalytics.gammaDensity(chain(), 24000.0, 24000.0, 24000.0, t, t * 365)!!
        near(g.atmIv, 14.0, 0.05); assertEquals(24000.0, g.peakExpiryStrike)
        assertEquals(24000.0, g.forwardPrice)
        near(g.intradayBand.sigmaMove, 24000.0 * g.atmIv / 100 * kotlin.math.sqrt(1 / 365.0), 1.0)
        // No ATM given: the upper median IV; forward 0 means spot.
        val m = ChainAnalytics.gammaDensity(chain(), 24000.0, null, 0.0, t, t * 365)!!
        near(m.atmIv, 14.0, 0.05); assertEquals(24000.0, m.forwardPrice)
        // Nothing prices: 15% and zero density; expired time uses one day intraday.
        val unpriced = chain().map { it.copy(ce = it.ce!!.copy(ltp = 0.0), pe = null) } + ChainRow(0.0, null, null)
        val u = ChainAnalytics.gammaDensity(unpriced, 24000.0, 24000.0, null, 0.0, 0.0)!!
        assertEquals(15.0, u.atmIv); assertTrue(u.chain.all { it.densityExpiry == 0.0 && it.iv == null })
        assertEquals(9, u.chain.size)
        assertTrue(u.chain.any { it.densityIntraday > 0 }, "intraday horizon still has time")
        // An impossible price (above the forward) gives no IV for that side.
        val silly = listOf(ChainRow(24000.0, OptLeg("C", 99999.0, oi = 10), OptLeg("P", 100.0, oi = 10)))
        val sg = ChainAnalytics.gammaDensity(silly, 24000.0, 24000.0, null, t, t * 365)!!
        assertNotNull(sg.chain.single().iv)
    }

    @Test fun `synthetic future`() {
        assertNull(ChainAnalytics.syntheticFuture(emptyList(), 100.0))
        assertNull(ChainAnalytics.syntheticFuture(listOf(ChainRow(100.0, null, OptLeg("P", 1.0))), 100.0))
        assertNull(ChainAnalytics.syntheticFuture(listOf(ChainRow(100.0, OptLeg("C", 1.0), null)), 100.0))
        assertEquals(SyntheticFuture(99.0, 100.0, 101.0, 2.0), ChainAnalytics.syntheticFuture(listOf(ChainRow(100.0, OptLeg("C", 3.0), OptLeg("P", 2.0))), 99.0))
    }

    // ------------------------------------------------------------ calendar arbitrage

    @Test fun `futures expiry parsing and the arbitrage universe`() {
        assertEquals(LocalDate.MAX, ChainAnalytics.parseFutExpiry(null)); assertEquals(LocalDate.MAX, ChainAnalytics.parseFutExpiry("  "))
        assertEquals(LocalDate.MAX, ChainAnalytics.parseFutExpiry("2026-09-29"))
        assertEquals(LocalDate.of(2026, 9, 29), ChainAnalytics.parseFutExpiry("29-sep-26"))
        assertEquals(LocalDate.of(2026, 9, 29), ChainAnalytics.parseFutExpiry("29-SEP-2026"))
        assertNull(ChainAnalytics.arbitrage(emptyList(), listOf(" ", "NSE")))
        val cs = listOf(
            FutContract("NIFTY26OCTFUT", "NFO", "NIFTY", "27-OCT-26"), FutContract("NIFTY26SEPFUT", "nfo", "NIFTY", "29-SEP-26"),
            FutContract("NIFTY26NOVFUT", "NFO", "NIFTY", "24-NOV-26"), FutContract("NIFTY26DECFUT", "NFO", "NIFTY", "29-DEC-26"),
            FutContract("NIFTY26SEP24000CE", "NFO", "NIFTY", "29-SEP-26"), FutContract("XFUT", "NFO", "", "29-SEP-26"),
            FutContract("YFUT", "NFO", "Y", ""), FutContract("TCS26SEPFUT", "NFO", "TCS", "29-SEP-26"),
            FutContract("GOLD26OCTFUT", "MCX", "GOLD", "05-OCT-26"), FutContract("GOLD26NOVFUT", "MCX", "GOLD", "05-NOV-26"),
            FutContract("GOLD26NOVFUT", "MCX", "GOLD", "05-NOV-2026"),
        )
        val u = ChainAnalytics.arbitrage(cs)!!
        assertEquals(2, u.underlyingCount)
        assertEquals(listOf("NFO:NIFTY:near-next", "NFO:NIFTY:near-third", "MCX:GOLD:near-next"), u.pairs.map { it.id })
        assertEquals("NIFTY26SEPFUT", u.pairs[0].near.symbol); assertEquals("NIFTY26NOVFUT", u.pairs[1].far.symbol)
        assertEquals(5, u.symbols.size)
        assertEquals(0, ChainAnalytics.arbitrage(cs, listOf("cds"))!!.pairs.size)
    }

    @Test fun `spread rows, mid prices and ranking`() {
        assertNull(ChainAnalytics.midPrice(null))
        assertEquals(10.5, ChainAnalytics.midPrice(Quote(10.0, 11.0, 9.0)))
        assertEquals(9.0, ChainAnalytics.midPrice(Quote(0.0, 11.0, 9.0))); assertEquals(9.0, ChainAnalytics.midPrice(Quote(10.0, null, 9.0)))
        assertNull(ChainAnalytics.midPrice(Quote(null, null, -1.0)))
        val fc = FutContract("A", "NFO", "A", "29-SEP-26")
        val pair = CalendarPair("id", "A", "NFO", "near-next", fc, fc)
        val short = ChainAnalytics.spreadRow(pair, Quote(100.0, 100.5, 100.2, ts = 1000), Quote(102.0, 102.5, 102.2, ts = 2000), 7000)
        assertEquals(SpreadDirection.SHORT_SPREAD, short.direction); near(short.bestCredit!!, 1.5)
        assertTrue(short.fresh); assertTrue(short.liquid); near(short.rawSpread!!, 2.0)
        val long = ChainAnalytics.spreadRow(pair, Quote(103.0, 103.5, null, ts = 1000), Quote(101.0, 101.5, null), 8000)
        assertEquals(SpreadDirection.LONG_SPREAD, long.direction); near(long.bestCredit!!, 1.5); assertFalse(long.fresh)
        val onlyLong = ChainAnalytics.spreadRow(pair, Quote(103.0, null, null), Quote(null, 101.5, null), 0)
        assertEquals(SpreadDirection.LONG_SPREAD, onlyLong.direction); assertNull(onlyLong.spreadPct); assertFalse(onlyLong.liquid)
        val none = ChainAnalytics.spreadRow(pair, null, null, 0)
        assertNull(none.direction); assertNull(none.rawSpread); assertFalse(none.fresh)
        val ranked = ChainAnalytics.rankSpreads(listOf(none, short, none.copy(pair = pair.copy(id = "n2")), long))
        assertEquals(listOf(short.spreadPct, long.spreadPct, null, null), ranked.map { it.spreadPct })
        assertEquals(listOf("id", "n2"), ranked.drop(2).map { it.pair.id }, "rows without a spread keep their order")
    }

    @Test fun `multi-strike OI flags brokers without history`() {
        val pts = listOf(TimeValue(ts(21, 10, 0), 1.234), TimeValue(ts(22, 10, 0), 2.345))
        val m = ChainAnalytics.multiStrikeOi(pts, listOf(LegOiSeries("S", "BUY", 100.0, OptionType.CE, "29SEP26", pts),
            LegOiSeries("Z", "SELL", null, null, null, listOf(TimeValue(1, 0.0)))), 1)
        assertTrue(m.underlyingAvailable); assertEquals(listOf(2.35), m.underlyingSeries.map { it.value })
        assertTrue(m.legs[0].hasOi); assertFalse(m.legs[1].hasOi)
        assertFalse(ChainAnalytics.multiStrikeOi(null, emptyList(), 1).underlyingAvailable)
        assertFalse(ChainAnalytics.multiStrikeOi(emptyList(), emptyList(), 1).underlyingAvailable)
    }

    // ------------------------------------------------------------ snapshot

    @Test fun `snapshot rows from minute series and the whole bundle`() {
        fun s(k: Double, r: Right, oi: LongArray, vol: LongArray?) = Series(expiry, k, r, 65, IntArray(oi.size) { 555 + it }, DoubleArray(oi.size) { 10.0 + it }, null, null, null, vol, oi)
        val series = listOf(
            s(24000.0, Right.CE, longArrayOf(0, 100, 150), longArrayOf(1, 2, 3)),
            s(24000.0, Right.PE, longArrayOf(200), null),
            s(24050.0, Right.PE, LongArray(0), null),
            s(0.0, Right.IX, longArrayOf(1), null),
        )
        val rows = ChainSnapshot.rowsFrom(series, mapOf((24000.0 to Right.CE) to "C24000"), 65)
        assertEquals(listOf(24000.0, 24050.0), rows.map { it.strike })
        val ce = rows[0].ce!!
        assertEquals("C24000", ce.symbol); assertEquals(12.0, ce.ltp); assertEquals(150L, ce.oi); assertEquals(6L, ce.volume); assertEquals(100L, ce.prevOi)
        val pe = rows[0].pe!!
        assertEquals("", pe.symbol); assertNull(pe.prevOi, "a single reading has no baseline"); assertEquals(0L, pe.volume)
        assertNull(rows[1].pe, "an empty series is no leg"); assertNull(rows[1].ce)

        val snap = ChainSnapshot.of("NIFTY", expiry, 24000.0, 65, chain(), now)
        assertEquals(24000.0, snap.atm); near(snap.forward, 24000.0, 0.01)
        assertEquals("29SEP26", snap.expiryCode)
        near(snap.atmIv!!, 14.0, 0.05)
        assertNotNull(snap.gex); assertNotNull(snap.gammaDensity); assertEquals(9, snap.labels.size)
        val expired = ChainSnapshot.of("NIFTY", expiry, 24000.0, 65, chain(), now.plusDays(30))
        assertNull(expired.ivSmile); assertNull(expired.gex); assertNull(expired.gammaDensity); assertNull(expired.atmIv)
        val empty = ChainSnapshot.of("NIFTY", expiry, 24000.0, 65, emptyList(), now)
        assertNull(empty.atm); assertEquals(24000.0, empty.forward); assertTrue(empty.labels.isEmpty()); assertNull(empty.maxPain)
        assertNull(empty.atmIv, "no smile ATM and no density")
        // ATM IV falls back to the density's when the smile has none.
        val noSymbols = ChainSnapshot.of("NIFTY", expiry, 24000.0, 65, chain().map { it.copy(ce = it.ce!!.copy(symbol = ""), pe = it.pe!!.copy(symbol = "")) }, now)
        near(noSymbols.atmIv!!, 14.0, 0.05)
    }

    // ------------------------------------------------------------ templates

    @Test fun `templates by direction, strike offsets and expiry codes`() {
        assertEquals(StrategyTemplates.ALL, StrategyTemplates.byDirection(null))
        assertTrue(StrategyTemplates.byDirection(Direction.BULLISH).all { it.direction == Direction.BULLISH })
        assertNull(StrategyTemplates.byId("nope"))
        assertEquals(200.0, StrategyTemplates.resolveStrikeOffset(listOf(Double.NaN, 300.0, 100.0, 200.0, 200.0), 100.0, 1))
        assertNull(StrategyTemplates.resolveStrikeOffset(listOf(100.0), 150.0, 0)); assertNull(StrategyTemplates.resolveStrikeOffset(listOf(100.0), 100.0, -1))
        assertEquals("", StrategyTemplates.normalizeExpiryCode("")); assertEquals("04AUG26", StrategyTemplates.normalizeExpiryCode("04-aug-2026"))
        assertEquals("04AUG26", StrategyTemplates.normalizeExpiryCode("04aug26")); assertEquals("0408", StrategyTemplates.normalizeExpiryCode("04-08"))
        assertTrue(StrategyTemplates.canReuseChainContract("04-AUG-26", "04AUG26"))
        assertEquals("06OCT26", StrategyTemplates.resolveExpiryOffset(listOf("06OCT26", "29SEP26", "bad", "29XYZ26", "29SEP26"), "29SEP26", 1))
        assertNull(StrategyTemplates.resolveExpiryOffset(listOf("29SEP26"), "06OCT26", 0))
        assertNull(StrategyTemplates.resolveExpiryOffset(listOf("29SEP26"), "29SEP26", 1))
    }

    @Test fun `template resolution reports every problem`() {
        val c = chain()
        val expiries = listOf("29SEP26", "06OCT26")
        val condor = StrategyTemplates.byId("iron_condor") ?: StrategyTemplates.ALL.first { it.legs.size == 4 }
        val ok = StrategyTemplates.resolve(condor, c, 24000.0, "29SEP26", expiries, lotMultiplier = 2)
        assertTrue(ok.ok, ok.errors.toString()); assertTrue(ok.legs.all { it.lots % 2 == 0 && it.symbol != null && it.price > 0 })
        // Off the loaded ladder.
        val narrow = c.filter { it.strike in 23950.0..24050.0 }
        val bad = StrategyTemplates.resolve(condor, narrow, 24000.0, "29SEP26", expiries)
        assertTrue(bad.errors.contains("One or more template strikes are outside the loaded option chain."))
        assertTrue(bad.strikeErrorIndexes.isNotEmpty())
        // A missing contract at a resolved strike.
        val holes = c.map { if (it.strike == 24000.0) it.copy(ce = null, pe = null) else it }
        val straddle = StrategyTemplates.ALL.first { t -> t.legs.all { it.strikeOffset == 0 && it.expiryOffset == 0 } && t.legs.size == 2 }
        val h = StrategyTemplates.resolve(straddle, holes, 24000.0, "29SEP26", expiries)
        assertTrue(h.errors.any { it.startsWith("The required") && it.contains("24000") }, h.errors.toString())
        // Calendars need a later expiry; an unlisted selected expiry fails every leg.
        val cal = StrategyTemplates.ALL.first { t -> t.legs.any { it.expiryOffset > 0 } }
        val noLater = StrategyTemplates.resolve(cal, c, 24000.0, "06OCT26", expiries)
        assertTrue(noLater.expiryInvalid); assertTrue(noLater.errors.contains("A later expiry is required for ${cal.name}."))
        val unlisted = StrategyTemplates.resolve(straddle, c, 24000.0, "13OCT26", expiries)
        assertTrue(unlisted.errors.contains("The selected expiry cannot resolve every leg in ${straddle.name}."))
        // A calendar's far leg is not priced from the loaded (near) chain.
        val calOk = StrategyTemplates.resolve(cal, c, 24000.0, "29SEP26", expiries)
        assertTrue(calOk.legs.any { it.expiry == "06OCT26" && it.symbol == null && it.price == 0.0 })
        // Overrides that break the order.
        val ov = StrategyTemplates.resolve(condor, c, 24000.0, "29SEP26", expiries, overrides = mapOf(0 to 24200.0))
        assertTrue(ov.errors.any { it.startsWith("Distinct template offsets") || it.startsWith("Legs with the same offset") }, ov.errors.toString())
        assertEquals(condor.legs.indices.toSet(), ov.strikeErrorIndexes)
        val legs = StrategyTemplates.toStrategyLegs(calOk, "NIFTY", 65) { 12.0 }
        assertTrue(legs.all { it.iv == 12.0 && it.lotSize == 65 })
        assertTrue(legs.any { it.symbol.startsWith("NIFTY06OCT26") })
        assertEquals(StrategyTemplates.toStrategyLegs(ok, "NIFTY", 65).map { it.iv }.toSet(), setOf(0.0))
    }

    @Test fun `topology validation`() {
        assertEquals(emptyList(), StrategyTemplates.validateStrikeTopology(emptyList()))
        assertEquals(listOf("Legs with the same offset must resolve to the same strike."), StrategyTemplates.validateStrikeTopology(listOf(0 to 1.0, 0 to 2.0)))
        assertEquals(emptyList(), StrategyTemplates.validateStrikeTopology(listOf(0 to 1.0, 0 to 1.0, 1 to 2.0)))
        assertEquals(listOf("Distinct template offsets must resolve to distinct, ordered strikes."), StrategyTemplates.validateStrikeTopology(listOf(1 to 1.0, 0 to 1.0)))
    }

    @Test fun `every template draws a bounded preview`() {
        for (tp in StrategyTemplates.ALL) {
            val path = if (tp.illustrativePreview) tp.payoffPath else StrategyTemplates.previewPath(tp)
            assertTrue(path.startsWith("M"), tp.id)
            val ys = Regex("[ML]([-0-9.]+),([-0-9.]+)").findAll(StrategyTemplates.previewPath(tp)).map { it.groupValues[2].toDouble() }.toList()
            assertTrue(ys.all { it in 4.0..36.0 }, "${tp.id} $ys")
        }
        // A degenerate one-leg template with zero lots is flat on the zero line.
        val flat = StrategyTemplate("flat", "Flat", Direction.NON_DIRECTIONAL, "", listOf(TemplateLeg(Side.BUY, OptionType.CE, 0, 0)))
        assertTrue(StrategyTemplates.previewPath(flat).split(" ").all { it.endsWith(",20") }, StrategyTemplates.previewPath(flat))
        assertEquals(0.0, StrategyTemplates.previewValue(flat, -50.0))
    }
}
