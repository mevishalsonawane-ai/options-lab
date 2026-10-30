package com.optionslab.app.ira

import com.optionslab.app.data.Market
import com.optionslab.app.data.OffShadow
import com.optionslab.app.data.Paper
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.orb.SoloMidday
import com.optionslab.ira.Candle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only"): Solo (midday) switched OFF still
 * makes its 12:00 decision on the same synthetic day as [IraSoloTest] (BANKNIFTY up 1 ATR into 11:59), and the pick - the 51,700 CE -
 * goes to the switched-off shadow ([OffShadow], key [LiveMirror.SOLO_MIDDAY]) with Solo's index stop (52,070) and 14:30: no paper
 * order, no Solo trade, nothing told to its live path, nothing at Zerodha. Switched on, the paper path is unchanged.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class IraSoloOffShadowTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|SOLOOFFBN51700CE"
    private val ceSymbol get() = Paper.contractFor("BANKNIFTY", day.plusDays(2), 51_700.0, Right.CE)!!.symbol
    @Volatile private var now: LocalDateTime = LocalDateTime.MIN
    private val heard = java.util.Collections.synchronizedList(ArrayList<String>())

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

    @Before fun up() {
        upstox = FakeUpstox()                               // (clears Market.testClock: set it after)
        kite = FakeKite()
        TradeFixtures.paperSettings()
        IraSolo.resetForTest()
        day = AutomationSupport.tradingDayAt(12, 0).toLocalDate()
        at(LocalTime.of(11, 0))
        val expiry = day.plusDays(2)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 51_700.0, Right.CE, 30, ceKey, "BANKNIFTY-SOLOOFF-51700CE"),
            Upstox.Contract("BANKNIFTY", expiry, 52_500.0, Right.PE, 30, "NSE_FO|SOLOOFFBN52500PE", "BANKNIFTY-SOLOOFF-52500PE")))
        IraHub.testLive = { m -> if (m == IraMarket.BANKNIFTY) bank() else emptyList() }
        IraSolo.testDaily = { _ -> daily() }
        IraSolo.testCheck = com.optionslab.ira.TradeCheck.Level.GO
        upstox.price(ceKey, 400.0)
        OffShadow.init(context)
        OffShadow.wipe()
        OffShadow.testSession = true
        OffShadow.testPrice = { _ -> Triple(400.0, 399.9, 400.1) }
        com.optionslab.app.data.ArmLive.testHeard = heard
    }

    @After fun down() {
        com.optionslab.app.data.ArmLive.testHeard = null
        OffShadow.wipe()
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

    private fun assertNoKite() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    private fun shadowOpen() = OffShadow.view.value.openOf(LiveMirror.SOLO_MIDDAY)

    @Test fun switchedOffItsTwelveOClockPickIsAWouldBeTradeAndNothingIsPlaced() {
        IraSolo.on = false
        val orders = Paper.state.orders.size
        pass(LocalTime.of(11, 59))
        assertTrue("before 12:00 nothing", shadowOpen().isEmpty())
        pass(LocalTime.of(12, 1))
        assertTrue("no Solo trade", IraSolo.all().isEmpty())
        assertEquals("no paper order", orders, Paper.state.orders.size)
        assertTrue("no paper position", Paper.state.positions.none { it.symbol == ceSymbol && it.quantity != 0 })
        assertTrue(heard.toString(), synchronized(heard) { heard.none { it.startsWith("entered|") } })
        assertNoKite()
        val w = shadowOpen().single()
        assertEquals(LiveMirror.SOLO_MIDDAY, w.key)
        assertEquals(IraSolo.SHADOW_LABEL, w.label)
        assertEquals(ceSymbol, w.symbol)
        assertEquals(1, w.view)
        assertEquals(30, w.qty)
        assertEquals("Solo's index stop, 0.3 ATR under the 12:00 price", 52_070.0, w.exits.indexStop!!, 1e-9)
        assertEquals(SoloMidday.EXIT, w.exits.exitAt)
        assertNull("no premium stop (Solo has none)", w.exits.stop)
        assertNull(w.exits.target)
        // One decision a day: the next pass records nothing more.
        pass(LocalTime.of(12, 2))
        assertEquals(1, shadowOpen().size)
        assertTrue(IraSolo.all().isEmpty())
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(IraSolo.status(), IraSolo.status().startsWith("Solo is off, Boss: it runs in shadow"))
        assertFalse(IraSolo.on)
    }

    @Test fun switchedOnAfterItsShadowDecisionItStillMakesItsOwnPaperTrade() {
        IraSolo.on = false
        pass(LocalTime.of(12, 1))
        assertEquals(1, shadowOpen().size)
        IraSolo.on = true
        val orders = Paper.state.orders.size
        pass(LocalTime.of(12, 2))
        val t = IraSolo.all().single()
        assertEquals(ceSymbol, t.symbol)
        assertEquals(orders + 1, Paper.state.orders.size)
        assertTrue(heard.toString(), synchronized(heard) { heard.any { it.startsWith("entered|${LiveMirror.SOLO_MIDDAY}|") } })
        assertEquals("the shadow keeps only its own would-be trade", 1, shadowOpen().size)
        assertNoKite()
    }

    @Test fun switchedOnThePaperPathIsUnchangedAndNothingGoesToTheShadow() {
        IraSolo.on = true
        val orders = Paper.state.orders.size
        pass(LocalTime.of(12, 1))
        assertEquals(ceSymbol, IraSolo.all().single().symbol)
        assertEquals(orders + 1, Paper.state.orders.size)
        assertTrue(shadowOpen().isEmpty())
        assertTrue(OffShadow.view.value.closedOf(LiveMirror.SOLO_MIDDAY).isEmpty())
        assertNoKite()
    }

    @Test fun switchedOffTheKillSwitchHoldsTheWouldBeTradeBackToo() {
        IraSolo.on = false
        val s = com.optionslab.app.data.AppSettings.load()
        com.optionslab.app.data.AppSettings.save(s.copy(guardKill = true))
        try {
            pass(LocalTime.of(12, 1))
            assertTrue(shadowOpen().isEmpty())
            assertTrue(IraSolo.all().isEmpty())
        } finally { com.optionslab.app.data.AppSettings.save(s) }
    }
}
