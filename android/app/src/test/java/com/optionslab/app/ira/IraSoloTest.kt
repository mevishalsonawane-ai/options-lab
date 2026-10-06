package com.optionslab.app.ira

import com.optionslab.app.data.Market
import com.optionslab.app.data.Paper
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.ShadowRules
import com.optionslab.engine.orb.SoloMidday
import com.optionslab.ira.Candle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Solo (midday) ([IraSolo], rules in [SoloMidday]) trades on the paper account only, never at Zerodha. A synthetic day:
 * BANKNIFTY climbs from 52,000 at 09:15 to 52,100 at 11:59 (ATR14 100 from the daily candles: 1 ATR, closing at the high);
 * the other indices have no minutes. The 12:01 pass buys the 51,700 CE (4 strikes in the money of the 12:00 price, 52,100)
 * on paper; the trade survives a reload; from 12:30 BANKNIFTY falls to 52,040, a 1-minute close through its index stop
 * (52,070), so the 12:31 pass sells it. Zerodha gets no request at all, and the card shows the forward test. Also: a trade
 * closed while the app was away settled the next day with its result, no order after 12:03, the slow reads done before
 * noon, and the drawdown judged from Boss's switch-on after Solo switched itself off.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class IraSoloTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|SOLOBN51700CE"
    private val ceSymbol get() = Paper.contractFor("BANKNIFTY", day.plusDays(2), 51_700.0, Right.CE)!!.symbol
    @Volatile private var now: LocalDateTime = LocalDateTime.MIN

    /** BANKNIFTY at the minute of day [m]: the climb to 11:59, 52,100 - 52,109 after it, 52,040 from 12:30. */
    private fun price(m: Int): Double = when {
        m < 12 * 60 -> 52_000.0 + 100.0 * (m - (9 * 60 + 15)) / (11 * 60 + 59 - (9 * 60 + 15))
        m < 12 * 60 + 30 -> 52_100.0 + (m - 12 * 60) % 10
        else -> 52_040.0
    }

    /** BANKNIFTY's minutes the feed has at [now] (the forming one included, as Upstox sends it). */
    private fun bank(): List<Candle> = (9 * 60 + 15 until 15 * 60 + 30).map { day.atTime(it / 60, it % 60) }
        .filter { !it.isAfter(now) }.map { s -> val p = price(s.hour * 60 + s.minute); Candle(s, p, p, p, p) }

    /** 20 sessions before today, each a 100-point day with no gap: ATR14 = 100. */
    private fun daily(): List<Upstox.Bar> = (1..20).map { k ->
        Upstox.Bar(day.minusDays(k.toLong()).atStartOfDay(IST).toEpochSecond(), 52_000.0, 52_050.0, 51_950.0, 52_000.0, 0, 0)
    }

    @Before fun up() {
        upstox = FakeUpstox()                               // (clears Market.testClock: set it after)
        kite = FakeKite()
        TradeFixtures.paperSettings()
        day = AutomationSupport.tradingDayAt(12, 0).toLocalDate()
        at(LocalTime.of(11, 0))
        val expiry = day.plusDays(2)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 51_700.0, Right.CE, 30, ceKey, "BANKNIFTY-SOLO-51700CE"),
            Upstox.Contract("BANKNIFTY", expiry, 52_500.0, Right.PE, 30, "NSE_FO|SOLOBN52500PE", "BANKNIFTY-SOLO-52500PE")))
        IraHub.testLive = { m -> if (m == IraMarket.BANKNIFTY) bank() else emptyList() }
        IraSolo.testDaily = { _ -> daily() }
        IraSolo.testCheck = com.optionslab.ira.TradeCheck.Level.GO
    }

    @After fun down() {
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

    /** Nothing at Zerodha: no order placed, nothing written. */
    private fun assertNoKite() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    private fun reload() {
        SecurePrefs.reload()
        AutomationSupport.reloadFromDisk(Paper)
    }

    @Test fun aSoloTradeGoesToPaperOnlySurvivesAReloadAndTheCardShowsTheForwardTest() {
        upstox.price(ceKey, 400.0)
        IraSolo.on = true
        val orders = Paper.state.orders.size
        pass(LocalTime.of(11, 59))
        assertTrue("before 12:00 nothing", IraSolo.all().isEmpty())
        // 12:00: the other indices' 11:59 minutes are waited for until 12:01.
        pass(LocalTime.of(12, 0))
        assertTrue(IraSolo.all().isEmpty())

        pass(LocalTime.of(12, 1))
        val t = IraSolo.all().single()
        assertFalse(t.closed); assertTrue(t.midday)
        assertEquals(SoloMidday.TAG, t.tag)
        assertEquals("BANKNIFTY", t.market); assertTrue(t.call)
        assertEquals(ceSymbol, t.symbol)
        assertEquals(30, t.qty)
        assertEquals("the 12:00 price", 52_100.0, t.index, 1e-9)
        assertEquals("0.3 ATR under it", 52_070.0, t.level, 1e-9)
        assertEquals(100.0, t.atr!!, 1e-9)
        assertEquals(12 * 60 + 1 - (9 * 60 + 15), t.entryMinute)
        assertTrue(t.why, t.why.startsWith("BANKNIFTY is up 100 points from the open, 1.00 x its daily ATR (100)"))
        // On paper: one more paper order and the position held; Zerodha untouched.
        assertEquals(orders + 1, Paper.state.orders.size)
        assertTrue(Paper.state.positions.any { it.symbol == t.symbol && it.quantity == 30 })
        assertNoKite()
        assertTrue(IraSolo.exposure().single().symbol == t.symbol)

        // A restart: the trade is read back.
        reload()
        assertEquals(t, IraSolo.all().single())
        // One trade a day, one at a time: no second entry.
        pass(LocalTime.of(12, 2))
        assertEquals(1, IraSolo.all().size)

        pass(LocalTime.of(12, 10))
        assertFalse("above its stop: held", IraSolo.all().single().closed)

        upstox.price(ceKey, 380.0)
        pass(LocalTime.of(12, 31), seconds = 5)
        val done = IraSolo.all().single()
        assertTrue(done.closed)
        val px = done.exitPrice!!
        assertEquals((px - done.entry) * 30 - ShadowRules.charges(done.entry, px, 30), done.net!!, 1e-6)
        assertTrue(done.exit!!, done.exit!!.startsWith("Out by the index stop (BANKNIFTY closed through 52,070)."))
        assertTrue(Paper.state.positions.none { it.symbol == t.symbol && it.quantity != 0 })
        assertNoKite()

        reload()
        assertEquals(done, IraSolo.all().single())
        val card = IraSolo.card()
        assertEquals("Not proven: paper only, never Zerodha.", card[0])
        assertTrue(card[1], card[1].startsWith("Trades since start ("))
        assertEquals("1 of 60 trades of the forward test.", card[2])
        assertEquals(SoloMidday.RESEARCH, card[4])
        assertTrue(IraSolo.status(), IraSolo.status().contains("Forward test: 1 of 60 trades"))
        assertTrue(IraSolo.record().startsWith("Solo (midday) so far: 1 trade, 0 won"))
        assertTrue(IraSolo.on)
        assertNotNull(IraSolo.provenWhy())
    }

    @Test fun aDrawdownBeyondTheBarSwitchesSoloOff() {
        // A 1,000 premium sold at 100: about -27,000 on one lot of 30 - beyond the -25,000 set in advance.
        upstox.price(ceKey, 1_000.0)
        IraSolo.on = true
        pass(LocalTime.of(12, 1))
        assertFalse(IraSolo.all().single().closed)
        upstox.price(ceKey, 100.0)
        pass(LocalTime.of(12, 31), seconds = 5)
        assertTrue(IraSolo.all().single().closed)
        assertFalse("switched itself off", IraSolo.on)
        assertTrue(IraSolo.paused!!, IraSolo.paused!!.contains("so it has switched itself off"))
        // Off: no new trade the next day's 12:00 either (Boss switches it back on).
        assertNoKite()
    }

    @Test fun aTradeClosedWhileTheAppWasAwayIsSettledTheNextDayWithItsResult() {
        upstox.price(ceKey, 400.0)
        IraSolo.on = true
        pass(LocalTime.of(12, 1))
        val t = IraSolo.all().single()
        // Sold by hand at 13:00 with no Solo pass after it (the app away), at 430.
        upstox.price(ceKey, 430.0)
        at(LocalTime.of(13, 0))
        val sold = runBlocking { Paper.close(t.symbol, "MIS") }
        val px = sold.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().single().price
        // The next day's first pass: the sell is found in the paper account's whole history, whatever its day.
        now = day.plusDays(1).atTime(10, 0)
        Market.testClock = Clock.fixed(now.atZone(IST).toInstant(), IST)
        runBlocking { IraSolo.passAt(now) }
        val done = IraSolo.all().single()
        assertTrue(done.closed)
        assertEquals(px, done.exitPrice!!, 1e-9)
        assertEquals((px - t.entry) * t.qty - ShadowRules.charges(t.entry, px, t.qty), done.net!!, 1e-6)
        assertEquals(sold.orderId, done.exitOrderId)
        assertEquals("closed outside Solo", done.exit)
        assertEquals(1, IraSolo.forward().trades)
        // The pure read: the first sell after the entry (quantity-weighted), none for another symbol.
        val found = IraSolo.exitOf(Paper.state.trades, t)!!
        assertEquals(px, found.first, 1e-9); assertEquals(sold.orderId, found.second)
        assertEquals(null, IraSolo.exitOf(Paper.state.trades, t.copy(symbol = "NONE")))
        assertNoKite()
    }

    @Test fun neverAnOrderAfter1203EvenWhenTheDecisionStartedInTime() {
        upstox.price(ceKey, 400.0)
        IraSolo.on = true
        // Decided at 12:03:50 (in time), but the clock reads 12:04 when the order would go in: nothing is placed.
        at(LocalTime.of(12, 4))
        runBlocking { IraSolo.passAt(day.atTime(12, 3, 50)) }
        assertTrue(IraSolo.all().isEmpty())
        assertTrue(Paper.state.positions.none { it.quantity != 0 })
        assertTrue(IraSolo.status(), IraSolo.status().contains("the 12:00-12:03 entry window closed before the order could go in"))
        assertNoKite()
    }

    @Test fun theSlowReadsAreDoneBeforeNoonAndTheDecisionReadsThemFromMemory() {
        upstox.price(ceKey, 400.0)
        IraSolo.on = true
        // The morning's read (the first pass from 09:15 starts it in the background).
        runBlocking { IraSolo.prefetchNow(day) }
        // At 12:01 the daily candles are not read again: a read now would be counted (and fail).
        var reads = 0
        IraSolo.testDaily = { _ -> reads++; error("read at 12:00") }
        pass(LocalTime.of(12, 1))
        assertEquals(0, reads)
        assertEquals("BANKNIFTY", IraSolo.all().single().market)
    }

    @Test fun afterSwitchingItselfOffSwitchingBackOnJudgesTheDrawdownFromThen() {
        upstox.price(ceKey, 1_000.0)
        IraSolo.on = true
        assertTrue(IraSolo.onState.value)
        pass(LocalTime.of(12, 1))
        upstox.price(ceKey, 100.0)
        pass(LocalTime.of(12, 31), seconds = 5)
        assertFalse("switched itself off", IraSolo.on)
        assertFalse("the card's state follows", IraSolo.onState.value)
        assertEquals(SoloMidday.Baseline(), IraSolo.baseline())
        // Boss switches it back on: the drawdown counts from here (1 trade in, its net the peak).
        IraSolo.on = true
        assertTrue(IraSolo.onState.value)
        val net = IraSolo.all().single().net!!
        assertEquals(SoloMidday.Baseline(1, net), IraSolo.baseline())
        assertEquals(SoloMidday.Verdict.RUNNING, SoloMidday.verdict(listOf(net), IraSolo.baseline()))
        // Switched off and on by hand (not by itself): the baseline stays.
        IraSolo.on = false; IraSolo.on = true
        assertEquals(SoloMidday.Baseline(1, net), IraSolo.baseline())
        assertNoKite()
    }

    @Test fun offTakesNothing() {
        upstox.price(ceKey, 400.0)
        IraSolo.on = false
        pass(LocalTime.of(12, 1))
        assertTrue(IraSolo.all().isEmpty())
        assertNoKite()
    }
}
