package com.optionslab.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootUtilitiesCoverageTest {
    private val d = LocalDate.of(2026, 1, 6)
    private fun near(a: Double, b: Double, tol: Double = 1e-9) = assertTrue(abs(a - b) <= tol, "$a vs $b")

    // ---------------------------------------------------------------- Units / Manifest / Provenance / Upstox

    @Test fun `units firewall converts whole lots and refuses everything else`() {
        assertContentEquals(longArrayOf(0, 2, 3), Units.toContracts(longArrayOf(0, 130, 195), 65))
        assertFailsWith<Units.NoLotSize> { Units.toContracts(longArrayOf(1), null) }
        assertFailsWith<Units.NoLotSize> { Units.toContracts(longArrayOf(1), 0) }
        assertFailsWith<Units.UnitsMismatch> { Units.toContracts(longArrayOf(65, -65), 65) }
        val e = assertFailsWith<Units.UnitsMismatch> { Units.toContracts(longArrayOf(65, 66, 1, 2, 3), 65) }
        assertTrue(e.message!!.contains("[66, 1, 2]"))
        assertContentEquals(LongArray(0), Units.toContracts(LongArray(0), 65))
    }

    @Test fun `manifest entries enforce scope honesty`() {
        assertFailsWith<IllegalArgumentException> { Manifest.Entry(d, 1, 1, "other", d) }
        assertFailsWith<Manifest.ScopeMismatch> { Manifest.Entry(d, 1, 1, Manifest.SAME_DAY, d.plusDays(1)) }
        Manifest.Entry(d, 1, 1, Manifest.SAME_DAY, d)
        Manifest.Entry(d, 1, 1, Manifest.BACKFILL, d.plusDays(3))
        assertEquals(Manifest.SAME_DAY, Manifest.scopeFor(d, d, 0))
        assertEquals(Manifest.BACKFILL, Manifest.scopeFor(d, d.plusDays(1), 0))
        assertEquals(Manifest.BACKFILL, Manifest.scopeFor(d, d, 1))
        assertEquals(Manifest.BACKFILL, Manifest.scopeFor(d, d, 0, nContracts = 0))
    }

    @Test fun `manifest csv round trips and upsert replaces by session`() {
        val a = Manifest.Entry(d.plusDays(2), 2, 40, Manifest.BACKFILL, d.plusDays(5))
        val b = Manifest.Entry(d, 1, 20, Manifest.SAME_DAY, d)
        val csv = Manifest.toCsv(listOf(a, b))
        assertEquals("session,n_expiries,n_contracts,scope,collected_on\n2026-01-06,1,20,same_day,2026-01-06\n2026-01-08,2,40,backfill,2026-01-11\n", csv)
        assertEquals(listOf(b, a), Manifest.parseCsv(csv + "\n  \n"))
        val b2 = b.copy(nContracts = 99, scope = Manifest.BACKFILL)
        assertEquals(listOf(b2, a), Manifest.upsert(listOf(a, b), b2))
        val c = Manifest.Entry(d.plusDays(1), 1, 1, Manifest.BACKFILL, d)
        assertEquals(listOf(b, c, a), Manifest.upsert(listOf(a, b), c))
    }

    @Test fun `provenance detects missing, changed and unrecorded sessions`() {
        val s1 = Session(d, null, listOf(
            Chains.ser(100.0, Right.CE, intArrayOf(899, 900, 929, 930), DoubleArray(4) { 1.0 }),
            Chains.ser(100.0, Right.PE, intArrayOf(900, 915), DoubleArray(2) { 1.0 }),
            Chains.ser(110.0, Right.PE, intArrayOf(901), DoubleArray(1) { 1.0 }),
            Chains.index(intArrayOf(900, 905, 910), 1.0)))
        val fp = Provenance.fingerprint(s1)
        assertEquals(Provenance.Fingerprint(d, 10, 2, 4), fp)
        val csv = "session,a,b,n_rows,n_strikes,c,d,n_settle,oi_lot,e,source\n" +
            "$d,x,x,10,2,x,x,4,65,x,upstox\n" +
            "${d.plusDays(1)},x,x,1,1,x,x,1,,x,kite\n"
        val rec = Provenance.parseCsv(csv)
        assertEquals(Provenance.Recorded(d, 10, 2, 4, 65, "upstox"), rec[0])
        assertNull(rec[1].oiLot)
        val s3 = Session(d.plusDays(2), null, emptyList())
        val drift = Provenance.verify(listOf(s1, s3), rec)
        assertEquals(listOf("missing" to d.plusDays(1), "unrecorded" to d.plusDays(2)), drift.map { it.kind to it.session })
        // Each fingerprint field on its own counts as a change.
        for (r in listOf(rec[0].copy(nRows = 9), rec[0].copy(nStrikes = 3), rec[0].copy(nSettlementBars = 5))) {
            assertEquals(listOf("changed"), Provenance.verify(listOf(s1), listOf(r)).map { it.kind })
        }
        assertEquals(emptyList(), Provenance.verify(listOf(s1), listOf(rec[0])))
    }

    @Test fun `upstox urls, chunks, bars and contracts`() {
        assertEquals("NSE_INDEX%7CNifty%2050", Upstox.quote("NSE_INDEX|Nifty 50"))
        assertEquals("a%2Ab~c", Upstox.quote("a*b~c"))
        assertEquals("${Upstox.BASE}/NSE_INDEX%7CNifty%20Bank/minutes/1/2026-02-01/2026-01-01",
            Upstox.candleUrl(Upstox.INDEX_KEYS.getValue("BANKNIFTY"), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)))
        assertEquals("${Upstox.BASE}/intraday/NSE_INDEX%7CIndia%20VIX/minutes/1", Upstox.intradayUrl(Upstox.INDEX_KEYS.getValue("INDIAVIX")))
        assertFailsWith<IllegalArgumentException> { Upstox.monthChunks(d, d.minusDays(1)) }
        assertEquals(listOf(d to d), Upstox.monthChunks(d, d))
        val ch = Upstox.monthChunks(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 10))
        assertEquals(listOf(LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 3, 3),
            LocalDate.of(2026, 3, 4) to LocalDate.of(2026, 3, 10)), ch)

        val c = Upstox.Contract("NIFTY", d, 23800.5, Right.PE, 65, "NSE_FO|1", "NIFTY26JAN23800PE")
        assertEquals("NIFTY|2026-01-06|23800.5|PE", c.contractId)
        assertFalse(c.isExpired(d)); assertTrue(c.isExpired(d.plusDays(1)))
        val live = c.copy(expiry = d.plusDays(7))
        assertEquals(listOf(live), Upstox.contractsToRefresh(listOf(c, live), d.plusDays(1)))
        assertTrue(Upstox.UpstoxError("x") is RuntimeException)
    }

    private fun bar(h: Int, m: Int, close: Double, vol: Long = 130, oi: Long = 650, day: LocalDate = d) =
        Upstox.Bar(ZonedDateTime.of(day.atTime(h, m), IST).toEpochSecond(), close - 1, close + 1, close - 2, close, vol, oi)

    @Test fun `bars become a series of today's minutes with a units firewall`() {
        val b = bar(9, 15, 100.0)
        assertEquals(d, b.istDate); assertEquals(555, b.istMinute)
        val bars = listOf(bar(9, 16, 101.0), b, bar(9, 16, 102.0), bar(9, 15, 7.0, day = d.minusDays(1)))
        val c = Upstox.Contract("NIFTY", d, 23800.0, Right.CE, 65, "k", "t")
        val s = Upstox.toSeries(c, bars, d)
        assertContentEquals(intArrayOf(555, 556), s.minutes)
        assertContentEquals(doubleArrayOf(100.0, 102.0), s.close)      // last duplicate wins
        assertContentEquals(longArrayOf(2, 2), s.volume)                // contracts
        assertContentEquals(longArrayOf(10, 10), s.oi)
        assertContentEquals(doubleArrayOf(99.0, 101.0), s.open)
        assertContentEquals(doubleArrayOf(101.0, 103.0), s.high)
        assertContentEquals(doubleArrayOf(98.0, 100.0), s.low)
        assertEquals(Right.CE, s.right); assertEquals(65, s.lot); assertEquals(d, s.expiry)
        val raw = Upstox.toSeries(c, bars, d, contracts = false)
        assertContentEquals(longArrayOf(130, 130), raw.volume)
        assertContentEquals(longArrayOf(650, 650), raw.oi)
        val ix = Upstox.toSeries(null, listOf(bar(9, 15, 24000.0, 7, 3)), d)
        assertEquals(Right.IX, ix.right); assertEquals(0.0, ix.strike); assertEquals(1, ix.lot); assertNull(ix.expiry)
        assertContentEquals(longArrayOf(7), ix.volume)
        assertFailsWith<Units.UnitsMismatch> { Upstox.toSeries(c, listOf(bar(9, 15, 1.0, vol = 3)), d) }
    }

    // ---------------------------------------------------------------- Monitor

    private fun row(credit: Double = 5.0, settle: Double = 24100.0, strike: Double = 23800.0, fwd: Double = 24000.0,
                    lot: Int = 65, day: LocalDate = d, intrinsic: Double? = null, otm: Double? = null) =
        Monitor.Row(day, strike, credit, settle, fwd, lot, intrinsic, otm)

    @Test fun `monitor refuses an empty ledger everywhere`() {
        val e = emptyList<Monitor.Row>()
        for (f in listOf<() -> Any>({ Monitor.checkCredit(e) }, { Monitor.observedLot(e) }, { Monitor.checkMarginOfSafety(e) },
            { Monitor.checkRegime(e, 0.01) }, { Monitor.checkVariancePremium(e) }, { Monitor.runChecks(e, 0.01, 65) },
            { Monitor.healthOf(e, 0, 0.01, 65) })) assertFailsWith<Monitor.EmptyLedger> { f() }
        assertFailsWith<IllegalArgumentException> { Monitor.verdict(emptyList()) }
    }

    @Test fun `credit check thresholds`() {
        assertEquals(Monitor.Status.FAIL, Monitor.checkCredit(listOf(row(credit = 1.99))).status)
        assertEquals(Monitor.Status.WARN, Monitor.checkCredit(listOf(row(credit = 2.0))).status)
        assertEquals(Monitor.Status.WARN, Monitor.checkCredit(listOf(row(credit = 2.99))).status)
        val c = Monitor.checkCredit(listOf(row(credit = 3.0)))
        assertEquals(Monitor.Status.PASS, c.status)
        assertEquals("median Rs 3.00/unit", c.measured)
        assertTrue(c.format().startsWith("[PASS] credit level"))
    }

    @Test fun `lot size check fails on a change or a mixed window`() {
        assertEquals(Monitor.Status.PASS, Monitor.checkLotSize(65).status)
        assertEquals(Monitor.Status.FAIL, Monitor.checkLotSize(75).status)
        val mixed = Monitor.checkLotSize(65, trades = listOf(row(lot = 75), row(lot = 65)))
        assertEquals(Monitor.Status.FAIL, mixed.status)
        assertEquals("65 and 75", mixed.measured)
        assertTrue(mixed.threshold.endsWith("one lot across the window"))
        val same = Monitor.checkLotSize(25, assumed = 25, trades = listOf(row(lot = 25)))
        assertEquals(Monitor.Status.PASS, same.status); assertEquals("25", same.measured)
        assertEquals(75, Monitor.observedLot(listOf(row(lot = 65), row(lot = 75, day = d.plusDays(7)))))
    }

    @Test fun `margin of safety thresholds`() {
        // margin = settle - (strike - credit) = settle - 23795
        assertEquals(Monitor.Status.FAIL, Monitor.checkMarginOfSafety(listOf(row(settle = 23809.0))).status)
        assertEquals(Monitor.Status.WARN, Monitor.checkMarginOfSafety(listOf(row(settle = 23810.0))).status)
        assertEquals(Monitor.Status.WARN, Monitor.checkMarginOfSafety(listOf(row(settle = 23834.0))).status)
        assertEquals(Monitor.Status.PASS, Monitor.checkMarginOfSafety(listOf(row(settle = 23835.0), row(settle = 24500.0))).status)
    }

    @Test fun `regime check uses each trade's own OTM distance`() {
        // fall = (F - S)/F. otm from the row, else the default.
        assertEquals(Monitor.Status.PASS, Monitor.checkRegime(listOf(row(settle = 24000.0)), 0.01).status)
        assertEquals(Monitor.Status.WARN, Monitor.checkRegime(listOf(row(settle = 23800.0)), 0.01).status)  // 0.83
        assertEquals(Monitor.Status.FAIL, Monitor.checkRegime(listOf(row(settle = 23700.0)), 0.01).status)  // 1.25
        assertEquals(Monitor.Status.FAIL, Monitor.checkRegime(listOf(row(settle = 23870.0, otm = 0.005)), 0.01).status)
        // Non-positive distances give no ratio -> infinite -> FAIL; NaN ratios are dropped.
        val inf = Monitor.checkRegime(listOf(row(otm = 0.0), row(otm = -0.01)), 0.01)
        assertEquals(Monitor.Status.FAIL, inf.status)
        assertTrue(inf.measured.contains("Infinity"), inf.measured)
        val nanRow = row(fwd = Double.NaN)
        assertEquals(Monitor.Status.FAIL, Monitor.checkRegime(listOf(nanRow), 0.01).status)
    }

    @Test fun `variance premium compares payout with credit`() {
        assertEquals(Monitor.Status.PASS, Monitor.checkVariancePremium(listOf(row(credit = 10.0, settle = 24000.0))).status)
        assertEquals(Monitor.Status.WARN, Monitor.checkVariancePremium(listOf(row(credit = 10.0, intrinsic = 6.0))).status)
        assertEquals(Monitor.Status.PASS, Monitor.checkVariancePremium(listOf(row(credit = 10.0, intrinsic = 5.0))).status)
        assertEquals(Monitor.Status.FAIL, Monitor.checkVariancePremium(listOf(row(credit = 10.0, settle = 23789.0))).status)
        assertEquals(Monitor.Status.WARN, Monitor.checkVariancePremium(listOf(row(credit = 10.0, intrinsic = 10.0))).status)
    }

    @Test fun `verdict is the worst check and healthOf windows the ledger`() {
        val rows = (0 until 5).map { row(credit = 5.0, day = d.plusDays(it * 7L), settle = 24200.0) } +
            row(credit = 1.0, day = d.minusDays(7), settle = 23000.0)
        val (recent, checks) = Monitor.healthOf(rows, 5, 0.0075, null)
        assertEquals(5, recent.size); assertEquals(d, recent.first().session)
        assertEquals(Monitor.Status.PASS, Monitor.verdict(checks))
        val (all, checksAll) = Monitor.healthOf(rows, 0, 0.0075, 75)
        assertEquals(6, all.size)
        assertEquals(Monitor.Status.FAIL, Monitor.verdict(checksAll))
        assertEquals(listOf("credit level", "lot size", "margin of safety", "regime", "variance premium"), checksAll.map { it.name })
        assertEquals(Monitor.Status.WARN, Monitor.verdict(listOf(checks[0].copy(status = Monitor.Status.WARN), checks[1])))
    }

    @Test fun `monitor rows come from settled trades`() {
        val t = ExpiryPut.settleTrade(23800.0, 5.0, 23900.0, 65).copy(session = d)
        val r = Monitor.rows(listOf(t, t.copy(otmRealised = 0.01)))
        assertNull(r[0].otmRealised); assertEquals(0.01, r[1].otmRealised)
        assertEquals(0.0, r[0].intrinsic)
    }

    // ---------------------------------------------------------------- Sizing

    @Test fun `sizing - loss arithmetic, capital and plans`() {
        near(Sizing.lossAtMove(0.0), 0.0)
        // strike 23820, settle 22560 -> 1260 * 65 - 315
        near(Sizing.lossAtMove(-0.06), 1260.0 * 65 - 315.0, 1e-6)
        near(Sizing.lossAtMove(-0.06, lots = 2), 2 * (1260.0 * 65 - 315.0), 1e-6)
        near(Sizing.capitalNeeded(-0.06), Sizing.EXCHANGE_MARGIN_RS + Sizing.lossAtMove(-0.06), 1e-6)
        assertFailsWith<IllegalArgumentException> { Sizing.planPosition(1e7, surviveMovePct = 0.0) }
        val e = assertFailsWith<Sizing.InsufficientCapital> { Sizing.planPosition(100_000.0) }
        assertTrue(e.message!!.contains("not enough to hold one lot"))
        val one = Sizing.planPosition(Sizing.capitalNeeded(-0.06) * 1.5)
        assertEquals(1, one.lots)
        val capped = Sizing.planPosition(1e8)
        assertEquals(Sizing.MAX_LOTS_ON_DEPTH, capped.lots)
        near(capped.expectedAnnualRs, 394.0 * 52 * 2)
        near(capped.expectedAnnualPct, capped.expectedAnnualRs / 1e8)
        near(capped.worstCaseRs, Sizing.lossAtMove(-0.06, lots = 2), 1e-6)
        assertEquals(3, Sizing.planPosition(1e8, maxLots = 3).lots)
        val tail = Sizing.tailTable()
        assertEquals(4, tail.size)
        near(tail[2].third, tail[2].second / Sizing.MEDIAN_CREDIT_RS)
        assertEquals(listOf(Triple(0.0, 0.0, 0.0)), Sizing.tailTable(listOf(0.0)))
    }

    // ---------------------------------------------------------------- Costs

    @Test fun `costs - spread law, regimes and floors`() {
        near(Costs.spreadPerUnit(100.0), 3.0 * (0.162 + 0.292))
        near(Costs.spreadPerUnit(-50.0, "roll"), 0.162)             // negative premium floored at zero
        near(Costs.spreadPerUnit(0.0, "stress"), 0.162 * 0.02 / 0.003)
        assertTrue(Costs.spreadPerUnit(0.0, "roll") >= Costs.TICK_SIZE)
        assertFailsWith<Costs.UnknownRegime> { Costs.spreadPerUnit(1.0, "zero") }
        assertFailsWith<Costs.UnknownRegime> { Costs.roundTrip(1.0, 65, regime = "") }
        assertFailsWith<Costs.UnknownRegime> { Costs.sellToSettle(1.0, 65, regime = "x") }
        assertFailsWith<Costs.UnknownRegime> { Costs.buyToSettle(1.0, 65, regime = "x") }
        assertEquals(listOf("roll", "quoted", "stress"), Costs.REGIMES)
    }

    @Test fun `costs - explicit, round trip and breakeven in index points`() {
        val ex = Costs.explicit(100.0, 65)
        near(ex.spread, 0.0); near(ex.brokerage, 40.0); near(ex.premiumNotional, 6500.0)
        near(ex.stt, 0.0015 * 6500); near(ex.exchange, 0.0003553 * 13000); near(ex.sebi, 10.0 * 13000 / 1e7)
        near(ex.stamp, 0.00003 * 6500); near(ex.gst, 0.18 * (40.0 + ex.exchange + ex.sebi))
        near(ex.total, ex.items().sumOf { it.second })
        assertEquals(listOf("Brokerage", "STT", "Exchange", "SEBI", "Stamp", "GST", "Spread"), ex.items().map { it.first })
        near(ex.fractionOfPremium, ex.total / 6500.0)
        val zero = Costs.explicit(0.0, 65, lots = 2)
        assertEquals(0.0, zero.fractionOfPremium)
        near(zero.total, 40.0 * 1.18)
        val rt = Costs.roundTrip(100.0, 65, 2, "quoted")
        near(rt.spread, Costs.spreadPerUnit(100.0) * 130)
        near(Costs.breakevenIndexPoints(100.0, 65, 2, "quoted", -0.5), rt.total / (130 * 0.5))
        assertFailsWith<IllegalArgumentException> { Costs.breakevenIndexPoints(100.0, 65, regime = "quoted", delta = 0.0) }
        near(Costs.breakevenIndexPoints(100.0, 65, regime = "roll", delta = 1.0), Costs.roundTrip(100.0, 65, regime = "roll").total / 65)
    }

    @Test fun `costs - sell and buy to settle`() {
        val s = Costs.sellToSettle(10.0, 65, regime = "quoted")
        near(s.brokerage, 20.0); near(s.stamp, 0.0); near(s.stt, 0.0015 * 650)
        near(s.spread, Costs.spreadPerUnit(10.0) / 2 * 65)
        val b = Costs.buyToSettle(10.0, 65, 2, "roll", intrinsic = 100.0)
        near(b.stt, 0.0015 * 100.0 * 130); near(b.stamp, 0.00003 * 1300)
        near(Costs.buyToSettle(10.0, 65, regime = "roll").stt, 0.0)
        near(Costs.buyToSettle(10.0, 65, regime = "roll", intrinsic = -5.0).stt, 0.0)
    }

    // ---------------------------------------------------------------- Lots

    @Test fun `lot history is dated and refuses the unknown`() {
        assertEquals(50, Lots.lotSizeOn("NIFTY", LocalDate.of(2024, 1, 1)))
        assertEquals(50, Lots.lotSizeOn("nifty", LocalDate.of(2024, 4, 25)))
        assertEquals(25, Lots.lotSizeOn("NIFTY", LocalDate.of(2024, 4, 26)))
        assertEquals(75, Lots.lotSizeOn("NIFTY", LocalDate.of(2025, 12, 30)))
        assertEquals(65, Lots.lotSizeOn("NIFTY", LocalDate.of(2030, 1, 1)))
        assertEquals(15, Lots.lotSizeOn("BankNifty", LocalDate.of(2025, 1, 30)))
        assertFailsWith<Lots.NoLotSize> { Lots.lotSizeOn("NIFTY", LocalDate.of(2023, 12, 31)) }
        val e = assertFailsWith<Lots.NoLotSize> { Lots.lotSizeOn("FINNIFTY", d) }
        assertTrue(e.message!!.contains("known: BANKNIFTY, NIFTY"))
        assertEquals(LocalDate.of(2024, 1, 1), Lots.coverageStart("BANKNIFTY"))
        assertFailsWith<Lots.NoLotSize> { Lots.coverageStart("X") }
    }

    private fun oiSeries(vararg oi: Long, right: Right = Right.PE) =
        Chains.ser(1.0, right, IntArray(oi.size) { it }, DoubleArray(oi.size), oi)

    @Test fun `lot from chain reads the gcd of common OI moves`() {
        assertNull(Lots.lotFromChain(emptyList()))
        assertNull(Lots.lotFromChain(listOf(oiSeries(5, 5, 5))))                       // no moves
        assertNull(Lots.lotFromChain(listOf(oiSeries(0, 130, 260, right = Right.IX))))  // index ignored
        assertEquals(65, Lots.lotFromChain(listOf(oiSeries(0, 130, 65, 260), oiSeries(10))))
        assertNull(Lots.lotFromChain(listOf(oiSeries(0, 7, 20))))                     // gcd 1
        // 65 x 5 moves plus 1 odd move of 13 in the top five sizes -> gcd 13; all divisible.
        assertEquals(13, Lots.lotFromChain(listOf(oiSeries(0, 65, 130, 195, 260, 325, 338))))
        // Six distinct sizes: the rarest drops out of the top five, then fails agreement.
        val steps = List(19) { 130L } + listOf(260L, 390L, 520L, 650L, 7L)
        val moves = steps.runningFold(0L) { a, b -> a + b }.toLongArray()
        assertEquals(130, Lots.lotFromChain(listOf(oiSeries(*moves))))
        assertNull(Lots.lotFromChain(listOf(oiSeries(*moves)), minAgreement = 0.99))
    }

    // ---------------------------------------------------------------- Olx and helpers

    private fun fullSeries(exp: LocalDate?, strike: Double, right: Right, withOhl: Boolean, withVol: Boolean) = Series(
        exp, strike, right, 65, intArrayOf(555, 556, 600), doubleArrayOf(10.05, 9.5, 11.25),
        if (withOhl) doubleArrayOf(10.0, 9.6, 11.0) else null, if (withOhl) doubleArrayOf(10.5, 9.9, 11.5) else null,
        if (withOhl) doubleArrayOf(9.9, 9.0, 11.0) else null, if (withVol) longArrayOf(1, 2, 300) else null, longArrayOf(650, 0, 1300))

    private fun roundTrip(ss: List<Session>, f32: Boolean = false): List<Session> {
        val out = ByteArrayOutputStream(); Olx.write(out, ss)
        return Olx.read(ByteArrayInputStream(out.toByteArray()), f32)
    }

    @Test fun `olx round trips every column combination`() {
        for ((ohl, vol) in listOf(true to true, true to false, false to true, false to false)) {
            val s = Session(d, if (vol) 64 else null, listOf(
                fullSeries(d.plusDays(1), 23850.5, Right.PE, ohl, vol),
                fullSeries(null, 0.0, Right.IX, ohl, vol),
                fullSeries(d.plusDays(1), 23800.0, Right.CE, ohl, vol)))
            val back = roundTrip(listOf(s, Session(d.plusDays(1), 0, emptyList())))
            assertEquals(2, back.size)
            val b = back[0]
            assertEquals(if (vol) 64 else null, b.lotHint)
            assertEquals(0, back[1].lotHint); assertEquals(0, back[1].rowCount)
            assertEquals(listOf(Right.IX, Right.CE, Right.PE), b.series.map { it.right })  // null expiry sorts first
            assertNull(b.series[0].expiry)
            val pe = b.series[2]
            assertEquals(23850.5, pe.strike); assertContentEquals(intArrayOf(555, 556, 600), pe.minutes)
            assertContentEquals(doubleArrayOf(10.05, 9.5, 11.25), pe.close)
            assertContentEquals(longArrayOf(650, 0, 1300), pe.oi)
            if (ohl) assertContentEquals(doubleArrayOf(9.9, 9.0, 11.0), pe.low) else assertNull(pe.open)
            if (vol) assertContentEquals(longArrayOf(1, 2, 300), pe.volume) else assertNull(pe.volume)
            assertEquals(b.index, b.series[0]); assertEquals(2, b.options.size); assertEquals(9, b.rowCount)
        }
        // Mixed: one series without OHL drops the flag for the whole file.
        val mixed = Session(d, null, listOf(fullSeries(d, 1.0, Right.PE, true, true), fullSeries(d, 2.0, Right.PE, false, true)))
        val m = roundTrip(listOf(mixed))[0]
        assertNull(m.series[0].open); assertTrue(m.series[0].volume != null)
    }

    @Test fun `olx float32 prices match the PC cache and readers stop early`() {
        val s = Session(d, null, listOf(fullSeries(d, 23850.7, Right.PE, false, false)))
        val back = roundTrip(listOf(s, s, s), f32 = true)
        assertEquals(23850.7f.toDouble(), back[0].series[0].strike)
        assertEquals(10.05f.toDouble(), back[0].series[0].close[0])
        assertEquals(1.1f.toDouble(), Olx.price(110, true)); assertEquals(1.1, Olx.price(110, false))
        val out = ByteArrayOutputStream(); Olx.write(out, listOf(s, s, s))
        val bytes = out.toByteArray()
        var n = 0
        Olx.forEachUntil(ByteArrayInputStream(bytes)) { n++; n < 2 }
        assertEquals(2, n)
        n = 0; Olx.forEach(ByteArrayInputStream(bytes)) { n++ }
        assertEquals(3, n)
        assertEquals(3, Olx.sequence(ByteArrayInputStream(bytes)).count())
        val r = Olx.Reader(ByteArrayInputStream(bytes), false)
        repeat(3) { r.next() }
        assertFalse(r.hasNext())
        assertFailsWith<NoSuchElementException> { r.next() }
    }

    @Test fun `olx refuses foreign, truncated and overflowing files`() {
        fun gz(b: ByteArray): ByteArray { val o = ByteArrayOutputStream(); GZIPOutputStream(o).use { it.write(b) }; return o.toByteArray() }
        assertFailsWith<Olx.BadFile> { Olx.read(ByteArrayInputStream(gz("NOPE\u0000\u0000".toByteArray()))) }
        val out = ByteArrayOutputStream(); Olx.write(out, listOf(Session(d, 1, listOf(fullSeries(d, 1.0, Right.PE, true, true)))))
        val raw = java.util.zip.GZIPInputStream(ByteArrayInputStream(out.toByteArray())).readBytes()
        assertFailsWith<EOFException> { Olx.read(ByteArrayInputStream(gz(raw.copyOf(raw.size - 3)))) }
        val over = DataInputStream(ByteArrayInputStream(ByteArray(11) { 0xFF.toByte() }))
        assertFailsWith<Olx.BadFile> { Olx.readVarint(over) }
        assertEquals(-3L, Olx.readZigzag(DataInputStream(ByteArrayInputStream(byteArrayOf(5)))))
        assertEquals(300L, Olx.readVarint(DataInputStream(ByteArrayInputStream(byteArrayOf(0xAC.toByte(), 0x02)))))
        // A lot hint below -1 cannot be encoded.
        assertFailsWith<IllegalArgumentException> { Olx.write(ByteArrayOutputStream(), listOf(Session(d, -5, emptyList()))) }
    }

    @Test fun `series lookups and contract ids`() {
        val s = Chains.ser(23800.0, Right.PE, intArrayOf(10, 20, 30), doubleArrayOf(1.0, 2.0, 3.0))
        assertEquals(-1, s.lastAtOrBefore(9)); assertEquals(0, s.lastAtOrBefore(10)); assertEquals(1, s.lastAtOrBefore(29))
        assertEquals(2, s.lastAtOrBefore(999)); assertEquals(-1, s.indexOf(25)); assertEquals(1, s.indexOf(20)); assertEquals(-1, s.indexOf(5))
        assertEquals("NIFTY|2026-01-06|23800|PE", s.contractId("NIFTY"))
        assertEquals("NIFTY", Chains.index(intArrayOf(1), 1.0).contractId("NIFTY"))
        assertEquals(-1, Chains.ser(1.0, Right.CE, IntArray(0), DoubleArray(0)).lastAtOrBefore(5))
    }

    @Test fun `fmtG, hhmm and minuteText`() {
        assertEquals("23800", fmtG(23800.0)); assertEquals("-5", fmtG(-5.0)); assertEquals("23800.5", fmtG(23800.5))
        assertEquals("0.05", fmtG(0.05)); assertEquals("1000000000000000", fmtG(1e15))
        assertFailsWith<NumberFormatException> { fmtG(Double.NaN) }   // no strike is NaN; documented, not handled
        assertEquals(555, hhmm(" 09:15 ")); assertEquals(0, hhmm("0:0")); assertEquals(1439, hhmm("23:59:59"))
        for (bad in listOf("915", "24:00", "-1:00", "12:60", "12:-1")) assertFailsWith<IllegalArgumentException>(bad) { hhmm(bad) }
        assertEquals("09:05", minuteText(545)); assertEquals("00:00", minuteText(0))
    }
}
