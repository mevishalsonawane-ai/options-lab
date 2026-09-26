package com.optionslab.engine

import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic chains whose put-call parity forward is known exactly. */
internal object Chains {
    fun ser(strike: Double, right: Right, minutes: IntArray, close: DoubleArray, oi: LongArray = LongArray(minutes.size), lot: Int = 65) =
        Series(LocalDate.of(2026, 1, 6), strike, right, lot, minutes, close, null, null, null, null, oi)

    /**
     * Strikes [lo]..[hi] step 50. At every minute in [minutes] the chain prices
     * off forward [fwd](minute): CE = max(F-K,0)+tv, PE = max(K-F,0)+tv with
     * tv = 20 + (K-lo)/10, so K + CE - PE == F exactly.
     */
    fun chain(minutes: IntArray, fwd: (Int) -> Double = { 24000.0 }, lo: Int = 23500, hi: Int = 24500,
              skip: (Double, Right) -> Boolean = { _, _ -> false },
              override: (Double, Right, Int) -> Double? = { _, _, _ -> null }): List<Series> {
        val out = ArrayList<Series>()
        for (k in lo..hi step 50) {
            val kk = k.toDouble()
            val tv = 20.0 + (kk - lo) / 10.0
            for (r in listOf(Right.CE, Right.PE)) {
                if (skip(kk, r)) continue
                val c = DoubleArray(minutes.size) { i ->
                    val f = fwd(minutes[i])
                    override(kk, r, minutes[i]) ?: (if (r == Right.CE) maxOf(f - kk, 0.0) else maxOf(kk - f, 0.0)) + tv
                }
                out += ser(kk, r, minutes, c)
            }
        }
        return out
    }

    val DAY_MINUTES = intArrayOf(600, 660, 900, 910, 929)
    fun index(minutes: IntArray, px: Double) = ser(0.0, Right.IX, minutes, DoubleArray(minutes.size) { px })
}

class ExpiryLiveCoverageTest {
    private val day = LocalDate.of(2026, 1, 6)
    private fun near(a: Double, b: Double, tol: Double = 1e-9) = assertTrue(abs(a - b) <= tol, "$a vs $b")

    // ---------------------------------------------------------------- Live

    @Test fun `liveSnapshot refuses before the entry minute exists`() {
        assertFailsWith<Live.TooEarly> { Live.liveSnapshot(emptyList(), 660) }
        // Only the index and empty series: nothing to price.
        val empty = Chains.ser(24000.0, Right.PE, IntArray(0), DoubleArray(0))
        assertFailsWith<Live.TooEarly> { Live.liveSnapshot(listOf(Chains.index(intArrayOf(700), 1.0), empty), 660) }
        // Latest bar 10:00, entry 11:00 -> stale.
        val e = assertFailsWith<Live.TooEarly> { Live.liveSnapshot(Chains.chain(intArrayOf(600)), 660) }
        assertTrue(e.message!!.contains("10:00") && e.message!!.contains("11:00"))
        // Bars exist after entry but none at/before it -> snapshot empty.
        assertFailsWith<Live.TooEarly> { Live.liveSnapshot(Chains.chain(intArrayOf(700)), 660) }
    }

    @Test fun `liveSnapshot returns the latest quote at or before entry`() {
        val chain = Chains.chain(intArrayOf(600, 660, 700), fwd = { if (it == 700) 25000.0 else 24000.0 })
        val snap = Live.liveSnapshot(chain, 660)
        assertEquals(42, snap.size)
        near(ExpiryPut.parityForward(snap), 24000.0)
    }

    @Test fun `naked ticket prices the nearest OTM put and scales margin by quantity`() {
        val t = Live.buildTicket(Chains.chain(intArrayOf(600, 660)), day, "NIFTY", lotSize = 65, lots = 2)
        near(t.forward, 24000.0)
        near(t.strike, 23800.0)                  // target 23820 -> nearest listed 23800
        near(t.credit, 50.0)                     // PE(23800) = 0 + 20 + 30
        assertEquals(130, t.qty)
        assertEquals(day, t.expiry)             // defaults to the session
        near(t.breakeven, 23750.0)
        near(t.margin, Sizing.EXCHANGE_MARGIN_RS * 2)
        assertNull(t.maxLoss); assertNull(t.wingStrike); assertNull(t.wingDebit)
        assertEquals("SELL", t.side); assertEquals("PE", t.right)
        val f = t.format()
        assertTrue(f.contains("UNBOUNDED"), f)
        assertTrue(f.contains("NIFTY 23800 PE  x2 lot (65)"), f)
        assertFalse(f.contains("BUY"))
    }

    @Test fun `wing ticket is a bounded spread whose margin is its max loss`() {
        val exp = LocalDate.of(2026, 1, 13)
        val t = Live.buildTicket(Chains.chain(intArrayOf(660)), day, "NIFTY", 65, expiry = exp, wingPct = 0.01)
        near(t.wingStrike!!, 23600.0)          // target 23580 -> 23600
        near(t.wingDebit!!, 30.0)
        near(t.credit, 20.0)                    // 50 - 30 net
        near(t.maxLoss!!, (23800.0 - 23600.0 - 20.0) * 65)
        near(t.margin, t.maxLoss!!)
        assertEquals(exp, t.expiry)
        val f = t.format()
        assertTrue(f.contains("BUY       NIFTY 23600 PE") && f.contains("(bounded)") && !f.contains("UNBOUNDED"), f)
    }

    @Test fun `ticket refuses a missing or zero put and a wing not below the short`() {
        // Wing that resolves to the short strike (zero width) is refused.
        assertFailsWith<ExpiryPut.SessionSkipped> {
            Live.buildTicket(Chains.chain(intArrayOf(660)), day, "NIFTY", 65, wingPct = 0.0)
        }
        // Short strike has a CE but no PE.
        val noPe = Chains.chain(intArrayOf(660), skip = { k, r -> k == 23800.0 && r == Right.PE })
        assertFailsWith<ExpiryPut.SessionSkipped> { Live.buildTicket(noPe, day, "NIFTY", 65) }
        // Short strike PE priced at zero.
        val zeroPe = Chains.chain(intArrayOf(660), override = { k, r, _ -> if (k == 23800.0 && r == Right.PE) 0.0 else null })
        assertFailsWith<ExpiryPut.SessionSkipped> { Live.buildTicket(zeroPe, day, "NIFTY", 65) }
        // Wing PE missing.
        val noWing = Chains.chain(intArrayOf(660), skip = { k, r -> k == 23600.0 && r == Right.PE })
        assertFailsWith<ExpiryPut.SessionSkipped> { Live.buildTicket(noWing, day, "NIFTY", 65, wingPct = 0.01) }
    }

    @Test fun `settling a paper row rebuilds the short credit and nets the wing once`() {
        val chain = Chains.chain(intArrayOf(660))
        val naked = Live.LedgerRow(Live.buildTicket(chain, day, "NIFTY", 65), "open")
        assertTrue(naked.paper)
        assertNull(naked.toMonitorRow())
        val sn = Live.settle(naked, 23700.0)
        assertEquals("settled", sn.status)
        assertEquals(23700.0, sn.settlement)
        val tn = sn.trade!!
        val expect = ExpiryPut.settleTrade(23800.0, 50.0, 23700.0, 65)
        near(tn.netPnl, expect.netPnl)
        near(tn.grossPnl, (50.0 - 100.0) * 65)
        assertEquals(day, tn.session)
        near(tn.otmRealised, 200.0 / 24000.0)
        val mr = sn.toMonitorRow()!!
        assertEquals(day, mr.session); near(mr.intrinsic!!, 100.0); near(mr.forward, 24000.0)

        val hedged = Live.LedgerRow(Live.buildTicket(chain, day, "NIFTY", 65, wingPct = 0.01), "open")
        val th = Live.settle(hedged, 23500.0, regime = "roll").trade!!
        // Short leg costed on its OWN premium (50), wing debit 30 bought separately.
        val ref = ExpiryPut.settleTrade(23800.0, 50.0, 23500.0, 65, 1, "roll", 23600.0, 30.0)
        near(th.credit, 20.0)
        near(th.intrinsic, 300.0 - 100.0)
        near(th.cost, ref.cost)
        near(th.netPnl, ref.netPnl)
        near(th.netPnl, (20.0 - 200.0) * 65 - ref.cost)
    }

    @Test fun `mark to market buys the position back at the live quote`() {
        val chain = Chains.chain(intArrayOf(660, 700), fwd = { if (it == 700) 23900.0 else 24000.0 })
        val naked = Live.buildTicket(chain, day, "NIFTY", 65)
        // At 11:40 F = 23900: PE(23800) = 0 + 50 -> unchanged; the fall has not reached the strike.
        near(Live.markToMarket(naked, chain, 700)!!, 0.0)
        val fell = Chains.chain(intArrayOf(660, 700), fwd = { if (it == 700) 23700.0 else 24000.0 })
        near(Live.markToMarket(naked, fell, 700)!!, (50.0 - 150.0) * 65)
        val hedged = Live.buildTicket(fell, day, "NIFTY", 65, wingPct = 0.01)
        // short 150, wing max(23600-23700,0)+30 = 30 -> (20 - 120) * 65
        near(Live.markToMarket(hedged, fell, 700)!!, (20.0 - 120.0) * 65)
        // Missing quotes -> null, never zero.
        assertNull(Live.markToMarket(naked, fell, 500))
        val noWing = Chains.chain(intArrayOf(700), skip = { k, r -> k == 23600.0 && r == Right.PE })
        assertNull(Live.markToMarket(hedged, noWing, 700))
        assertNotNull(Live.markToMarket(naked, noWing, 700))
    }

    // ------------------------------------------------------------ ExpiryPut

    @Test fun `parity forward rejects duplicates and thin chains and ignores the index`() {
        val q = listOf(ExpiryPut.Quote(100.0, Right.CE, 1.0), ExpiryPut.Quote(100.0, Right.CE, 2.0),
            ExpiryPut.Quote(200.0, Right.PE, 1.0), ExpiryPut.Quote(200.0, Right.PE, 1.0))
        val e = assertFailsWith<ExpiryPut.NotASnapshot> { ExpiryPut.parityForward(q) }
        assertTrue(e.message!!.contains("[100.0, 200.0]") && e.message!!.contains("4 duplicated"), e.message)
        // Zero-priced sides and one-sided strikes are unusable.
        val thin = listOf(
            ExpiryPut.Quote(100.0, Right.CE, 5.0), ExpiryPut.Quote(100.0, Right.PE, 0.0),
            ExpiryPut.Quote(110.0, Right.CE, 0.0), ExpiryPut.Quote(110.0, Right.PE, 3.0),
            ExpiryPut.Quote(120.0, Right.CE, 2.0),
            ExpiryPut.Quote(130.0, Right.CE, 4.0), ExpiryPut.Quote(130.0, Right.PE, 1.0),
            ExpiryPut.Quote(0.0, Right.IX, 999.0),
        )
        assertFailsWith<ExpiryPut.ThinChain> { ExpiryPut.parityForward(thin) }
        near(ExpiryPut.parityForward(thin, nStrikes = 1), 133.0)
    }

    @Test fun `strike selection ties break low and an empty ladder is refused`() {
        assertFailsWith<IllegalArgumentException> { ExpiryPut.selectStrike(emptyList(), 100.0) }
        assertEquals(100.0, ExpiryPut.selectStrike(listOf(110.0, 100.0), 105.0, 0.0))
        assertEquals(110.0, ExpiryPut.selectStrike(listOf(110.0, 100.0), 106.0, 0.0))
        assertEquals(23800.0, ExpiryPut.selectStrike(listOf(23800.0, 23850.0), 24000.0))
    }

    @Test fun `settlement price averages only the 15_00-15_29 window`() {
        near(ExpiryPut.settlementPrice(mapOf(899 to 1.0, 900 to 10.0, 929 to 20.0, 930 to 1000.0)), 15.0)
        assertFailsWith<IllegalArgumentException> { ExpiryPut.settlementPrice(mapOf(899 to 1.0, 930 to 2.0)) }
        assertFailsWith<IllegalArgumentException> { ExpiryPut.settlementPrice(emptyMap()) }
    }

    @Test fun `settleTrade naked and spread, in and out of the money`() {
        val otm = ExpiryPut.settleTrade(23800.0, 50.0, 24100.0, 65)
        near(otm.intrinsic, 0.0); near(otm.grossPnl, 50.0 * 65)
        near(otm.cost, Costs.sellToSettle(50.0, 65, 1, "quoted").total)
        assertTrue(otm.won); assertNull(otm.maxLoss); assertNull(otm.session)
        assertTrue(otm.forward.isNaN())
        val itm = ExpiryPut.settleTrade(23800.0, 50.0, 23000.0, 65, lots = 3)
        near(itm.grossPnl, (50.0 - 800.0) * 195); assertFalse(itm.won)
        val sp = ExpiryPut.settleTrade(23800.0, 50.0, 23000.0, 65, 1, "stress", 23600.0, 30.0)
        near(sp.wingIntrinsic, 600.0); near(sp.intrinsic, 200.0)
        val cost = Costs.sellToSettle(50.0, 65, 1, "stress").total + Costs.buyToSettle(30.0, 65, 1, "stress", 600.0).total
        near(sp.cost, cost)
        near(sp.maxLoss!!, (200.0 - 20.0) * 65 + cost)
        near(sp.netPnl, -sp.maxLoss!!, 1e-6)   // settles below the wing: loses exactly the max
        // Wing OTM at settlement: no exercise STT.
        val sp2 = ExpiryPut.settleTrade(23800.0, 50.0, 23700.0, 65, 1, "quoted", 23600.0, 30.0)
        near(sp2.wingIntrinsic, 0.0)
        assertFailsWith<Costs.UnknownRegime> { ExpiryPut.settleTrade(1.0, 1.0, 1.0, 1, regime = "free") }
    }

    @Test fun `snapshot and minute slice skip the index`() {
        val chain = listOf(Chains.index(intArrayOf(600, 660), 24000.0),
            Chains.ser(100.0, Right.PE, intArrayOf(600, 700), doubleArrayOf(1.0, 2.0)),
            Chains.ser(100.0, Right.CE, intArrayOf(650), doubleArrayOf(3.0)),
            Chains.ser(90.0, Right.PE, intArrayOf(700), doubleArrayOf(4.0)))
        assertEquals(listOf(ExpiryPut.Quote(100.0, Right.CE, 3.0), ExpiryPut.Quote(100.0, Right.PE, 1.0)),
            ExpiryPut.snapshot(chain, 660))
        assertEquals(listOf(ExpiryPut.Quote(100.0, Right.PE, 2.0), ExpiryPut.Quote(90.0, Right.PE, 4.0)),
            ExpiryPut.minuteSlice(chain, 700))
        assertEquals(emptyList(), ExpiryPut.minuteSlice(chain, 660))
    }

    @Test fun `dated lot prefers the hint, then the chain, then the table`() {
        assertEquals(40, ExpiryPut.datedLot(Session(day, 40, emptyList())))
        val oi = longArrayOf(0, 75, 150, 300, 225)
        val s = Chains.ser(100.0, Right.PE, intArrayOf(1, 2, 3, 4, 5), DoubleArray(5) { 1.0 }, oi)
        assertEquals(75, ExpiryPut.datedLot(Session(day, null, listOf(s))))
        assertEquals(65, ExpiryPut.datedLot(Session(day, null, emptyList())))
        assertEquals(30, ExpiryPut.datedLot(Session(day, null, emptyList()), "BANKNIFTY"))
    }

    @Test fun `runSession prices a full expiry and settles on the window forward`() {
        val chain = Chains.chain(Chains.DAY_MINUTES, fwd = { if (it >= 900) 23700.0 else 24000.0 }) +
            Chains.index(Chains.DAY_MINUTES, 24000.0)
        val t = ExpiryPut.runSession(day, chain, 65, ExpiryPut.Params())
        assertEquals(day, t.session)
        near(t.strike, 23800.0); near(t.credit, 50.0); near(t.settlement, 23700.0)
        near(t.forward, 24000.0); near(t.otmRealised, 200.0 / 24000.0)
        val w = ExpiryPut.runSession(day, chain, 65, ExpiryPut.Params(wingPct = 0.01, lots = 2))
        near(w.wingStrike!!, 23600.0); near(w.wingDebit, 30.0); assertEquals(130, w.qty)
    }

    @Test fun `runSession skips every broken session with a reason`() {
        val p = ExpiryPut.Params()
        // No bars at/before entry (and an empty series).
        val late = Chains.chain(intArrayOf(700, 900)) + Chains.ser(1.0, Right.PE, IntArray(0), DoubleArray(0))
        assertFailsWith<ExpiryPut.SessionSkipped> { ExpiryPut.runSession(day, late, 65, p) }
        // Short strike PE missing / zero.
        assertFailsWith<ExpiryPut.SessionSkipped> {
            ExpiryPut.runSession(day, Chains.chain(Chains.DAY_MINUTES, skip = { k, r -> k == 23800.0 && r == Right.PE }), 65, p)
        }
        assertFailsWith<ExpiryPut.SessionSkipped> {
            ExpiryPut.runSession(day, Chains.chain(Chains.DAY_MINUTES, override = { k, r, _ -> if (k == 23800.0 && r == Right.PE) -1.0 else null }), 65, p)
        }
        // No settlement window at all.
        val e = assertFailsWith<ExpiryPut.SessionSkipped> { ExpiryPut.runSession(day, Chains.chain(intArrayOf(600, 660, 930)), 65, p) }
        assertTrue(e.message!!.contains("settlement window"))
        // Window exists but every minute is too thin to price.
        val thinWindow = Chains.chain(intArrayOf(660)) + Chains.ser(24000.0, Right.CE, intArrayOf(905), doubleArrayOf(5.0))
        assertEquals("settlement window too thin to price",
            assertFailsWith<ExpiryPut.SessionSkipped> { ExpiryPut.runSession(day, thinWindow, 65, p) }.message)
        // A negative wing would buy ABOVE the short strike.
        assertFailsWith<IllegalArgumentException> { ExpiryPut.runSession(day, Chains.chain(Chains.DAY_MINUTES), 65, p.copy(wingPct = -0.01)) }
        // Zero-width wing.
        assertFailsWith<ExpiryPut.SessionSkipped> { ExpiryPut.runSession(day, Chains.chain(Chains.DAY_MINUTES), 65, p.copy(wingPct = 0.0)) }
        // Wing missing / zero.
        assertFailsWith<ExpiryPut.SessionSkipped> {
            ExpiryPut.runSession(day, Chains.chain(Chains.DAY_MINUTES, skip = { k, r -> k == 23600.0 && r == Right.PE }), 65, p.copy(wingPct = 0.01))
        }
        assertFailsWith<ExpiryPut.SessionSkipped> {
            ExpiryPut.runSession(day, Chains.chain(Chains.DAY_MINUTES, override = { k, r, m -> if (k == 23600.0 && r == Right.PE && m == 660) 0.0 else null }), 65, p.copy(wingPct = 0.01))
        }
    }

    @Test fun `a duplicated minute in the window is skipped not fatal`() {
        // One minute where a contract appears twice would throw NotASnapshot; the loop ignores it.
        val chain = Chains.chain(Chains.DAY_MINUTES) + Chains.ser(24000.0, Right.CE, intArrayOf(910), doubleArrayOf(20.0))
        val t = ExpiryPut.runSession(day, chain, 65, ExpiryPut.Params())
        near(t.settlement, 24000.0)
    }

    @Test fun `runBacktest records every kind of skip`() {
        val good = Session(day, null, Chains.chain(Chains.DAY_MINUTES))
        val skipped = Session(day.plusDays(1), null, Chains.chain(intArrayOf(700)))
        val thin = Session(day.plusDays(2), null, Chains.chain(Chains.DAY_MINUTES, lo = 23800, hi = 24100))
        val dup = Session(day.plusDays(3), null, Chains.chain(Chains.DAY_MINUTES) + Chains.ser(24000.0, Right.CE, intArrayOf(660), doubleArrayOf(1.0)))
        val (trades, skips) = ExpiryPut.runBacktest(listOf(good, skipped, thin, dup), ExpiryPut.Params())
        assertEquals(1, trades.size)
        assertEquals(listOf(day.plusDays(1), day.plusDays(2), day.plusDays(3)), skips.map { it.day })
        assertTrue(skips[1].why.contains("strikes quote both sides"))
        assertTrue(skips[2].why.length <= 80)
        // Dated lot before NSE coverage -> NoLotSize skip.
        val old = Session(LocalDate.of(2020, 1, 1), null, Chains.chain(Chains.DAY_MINUTES))
        val (t2, s2) = ExpiryPut.runBacktest(listOf(old, good), ExpiryPut.Params(lot = ExpiryPut.LotChoice.Dated))
        assertEquals(1, t2.size); assertEquals(65, t2[0].lotSize)
        assertTrue(s2.single().why.startsWith("NIFTY lot history starts"))
    }

    @Test fun `split seals the most recent sessions chronologically`() {
        val days = (0..4).map { day.plusDays(it.toLong()) }.shuffled(java.util.Random(1)) + day
        val (train, hold) = ExpiryPut.splitSessions(days, 2)
        assertEquals(days.toSortedSet().toList().take(3), train)
        assertEquals(listOf(day.plusDays(3), day.plusDays(4)), hold)
        assertFailsWith<IllegalArgumentException> { ExpiryPut.splitSessions(days, 5) }
        assertFailsWith<IllegalArgumentException> { ExpiryPut.splitSessions(emptyList()) }
    }
}
