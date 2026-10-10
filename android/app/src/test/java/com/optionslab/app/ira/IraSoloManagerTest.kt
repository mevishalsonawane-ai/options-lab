package com.optionslab.app.ira

import com.optionslab.app.data.Market
import com.optionslab.app.data.Paper
import com.optionslab.app.data.TradeManagerHost
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.ira.Auction
import com.optionslab.ira.Candle
import com.optionslab.ira.OrderFlow
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import com.optionslab.ira.Market as IraMarket

/**
 * The trade manager on Solo's paper trade ([TradeManagerHost], [IraSolo]; paper acts by default). Solo buys the BANKNIFTY
 * 51,700 CE at 12:01 at 400 (as [IraSoloTest]'s day). At 12:02 strong executed selling, the future back under VWAP, holds
 * for 60 s: the manager wants it out, and Solo's next look sells it on paper through its own exit path, said as the
 * manager's. Solo's own rules are followed on as the counterfactual: BANKNIFTY closes through its index stop at 12:30, when
 * the option's minute closed at 350 - so the early exit saved (400 - 350) x 30. Jarvis says why. Zerodha is never asked.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class IraSoloManagerTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|SOLOBN51700CE"
    @Volatile private var now: LocalDateTime = LocalDateTime.MIN

    private fun price(m: Int): Double = when {
        m < 12 * 60 -> 52_000.0 + 100.0 * (m - (9 * 60 + 15)) / (11 * 60 + 59 - (9 * 60 + 15))
        m < 12 * 60 + 30 -> 52_100.0 + (m - 12 * 60) % 10
        else -> 52_040.0
    }

    private fun bank(): List<Candle> = (9 * 60 + 15 until 15 * 60 + 30).map { day.atTime(it / 60, it % 60) }
        .filter { !it.isAfter(now) }.map { s -> val p = price(s.hour * 60 + s.minute); Candle(s, p, p, p, p) }

    private fun daily(): List<Upstox.Bar> = (1..20).map { k ->
        Upstox.Bar(day.minusDays(k.toLong()).atStartOfDay(IST).toEpochSecond(), 52_000.0, 52_050.0, 51_950.0, 52_000.0, 0, 0)
    }

    private fun ms(t: LocalDateTime): Long = t.atZone(IST).toInstant().toEpochMilli()

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        TradeFixtures.paperSettings()
        IraSolo.resetForTest()
        day = AutomationSupport.tradingDayAt(12, 0).toLocalDate()
        at(LocalTime.of(11, 0))
        val expiry = day.plusDays(2)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 51_700.0, Right.CE, 30, ceKey, "BANKNIFTY-SOLO-51700CE"),
            Upstox.Contract("BANKNIFTY", expiry, 52_500.0, Right.PE, 30, "NSE_FO|SOLOBN52500PE", "BANKNIFTY-SOLO-52500PE")))
        IraHub.testLive = { m -> if (m == IraMarket.BANKNIFTY) bank() else emptyList() }
        IraSolo.testDaily = { _ -> daily() }
        IraSolo.testCheck = com.optionslab.ira.TradeCheck.Level.GO
        TradeManagerHost.testNoWake = true
        // The option's minutes: 400 until 12:29, then 350 (the counterfactual is priced on them).
        TradeManagerHost.testBars = { _ ->
            (12 * 60 until 13 * 60).map { m -> val t = day.atTime(m / 60, m % 60); val p = if (m < 12 * 60 + 30) 400.0 else 350.0
                TradeManager.Bar(ms(t), p, p + 2, p - 2, p) }
        }
    }

    @After fun down() {
        TradeManagerHost.resetForTest()
        IraSolo.testDaily = null; IraSolo.testCheck = null
        IraHub.testLive = { emptyList() }
        Market.testClock = null
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(t: LocalTime, seconds: Long = 0) {
        now = day.atTime(t).plusSeconds(seconds)
        Market.testClock = Clock.fixed(now.atZone(IST).toInstant(), IST)
    }

    private fun pass(t: LocalTime, seconds: Long = 10) { at(t, seconds); runBlocking { IraSolo.passAt(now) } }

    @Test fun aPaperSoloTradeIsExitedEarlyInActAndTheOriginalRulesAreFollowedOn() {
        upstox.price(ceKey, 400.0)
        IraSolo.on = true
        pass(LocalTime.of(12, 1))
        val t = IraSolo.all().single()
        assertFalse(t.closed)
        pass(LocalTime.of(12, 2))                        // handed to the manager on Solo's look
        val id = IraSolo.managerId(t)
        val rec = TradeManagerHost.records.value.single { it.trade.tradeId == id }
        assertEquals(TradeManager.Mode.ACT, rec.mode)
        assertEquals(TradeManager.Account.PAPER, rec.trade.account)
        assertNull("Solo has no target: nothing to extend", rec.trade.originalTarget)

        // Strong sellers with executed volume behind them, the future under VWAP: 60 s of it.
        TradeManagerHost.testSnapshot = { _, atMs ->
            val read = OrderFlow.Read(name = "BANKNIFTY", atSec = atMs / 1000, side = OrderFlow.Side.SELLERS, strength = 72, buyers = 28, warm = true,
                mid = 52_060.0, ofi10 = 0.0, ofi60 = 0.0, ofi300 = 0.0, cvd10 = 0.0, cvd60 = -7_000.0, cvd300 = 0.0, depth = 0.0, queue = 0.0,
                ratio = Double.NaN, ce60 = 0.0, pe60 = 0.0, optionNet = Double.NaN, buildUp = null, oiChange = null, z = emptyMap())
            TradeManager.Snapshot(atMs, 400.0, flow = read, futPrice = 52_060.0, vwap = Auction.Vwap(52_090.0, 30.0, null, null),
                valueHigh = 52_120.0, valueLow = 52_070.0)
        }
        val t0 = ms(day.atTime(12, 2, 20))
        for (i in 0..59) TradeManagerHost.evaluate(t0 + i * 1000L)
        assertNull("not before 60 s", TradeManagerHost.exitDue(id))
        TradeManagerHost.evaluate(t0 + 60_000L)
        val due = TradeManagerHost.exitDue(id)
        assertNotNull(due)
        assertTrue(due!!, "sellers took over" in due)

        // Solo's next look sells it on paper, through its own exit path.
        pass(LocalTime.of(12, 3), seconds = 30)
        val done = IraSolo.all().single()
        assertTrue(done.closed)
        assertTrue(done.exit!!, done.exit!!.startsWith("Out early: the trade manager: sellers took over"))
        assertTrue(Paper.state.positions.none { it.symbol == t.symbol && it.quantity != 0 })
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        val closed = TradeManagerHost.records.value.single { it.trade.tradeId == id }
        assertTrue(closed.earlyExit)
        assertTrue(closed.notes.single { it.kind == TradeManager.EXIT_EARLY }.acted)
        assertEquals(done.exitPrice!!, closed.actual!!.price, 1e-9)
        assertEquals(closed.actual, closed.manager)
        assertNull("the original rules are still being followed", closed.original)

        // 12:40: Solo's own rules would have sold on the 12:30 index stop, when the option's minute closed at 350.
        at(LocalTime.of(12, 40))
        runBlocking { TradeManagerHost.settleNow(ms(now)) }
        val settled = TradeManagerHost.records.value.single { it.trade.tradeId == id }
        val o = settled.original!!
        assertEquals("STOP", o.why)
        assertEquals(ms(day.atTime(12, 30)), o.atMs)
        assertEquals(350.0, o.price, 1e-9)
        assertEquals((done.exitPrice!! - 350.0) * 30, settled.vsOriginal!!, 1e-6)
        assertTrue(TradeManagerHost.recordLines().first(), TradeManagerHost.recordLines().first().startsWith("Solo paper: 1 trade, 1 early exit"))

        // Jarvis: why it exited early, with the evidence and against the original rules.
        val why = TradeManagerHost.answer(TradeManager.asked("why did Solo exit early?")!!)
        assertTrue(why, why.startsWith("Solo exited early at 12:03: sellers took over"))
        assertTrue(why, "Against the original rules: +₹" in why && "stop at 350" in why)
        assertTrue(TradeManagerHost.answer(TradeManager.asked("how is the trade manager doing")!!).contains("Solo paper: 1 trade"))
        // The record survives a restart (its file).
        TradeManagerHost.save()
        assertTrue(java.io.File(context.noBackupFilesDir, "trademanager/records.json").readText().contains(id))
    }
}
