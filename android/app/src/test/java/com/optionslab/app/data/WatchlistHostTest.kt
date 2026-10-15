package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.Watchlist
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The shadow Watchlist ([WatchlistHost], 15 Oct): R52 R4-8's Gann break on NIFTY's 15-minute bars is written down with its
 * exact contract, priced from that option's own minutes with the paper costs once its 30 minutes are up, and given its twins in
 * the evening - and not one order is placed anywhere, paper or live. Off, nothing more is recorded; the log is read back after
 * a restart; positions held are never slowed (the round places nothing and holds nothing).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class WatchlistHostTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")

    private fun at(t: LocalTime) { Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(20).atZone(IST).toInstant(), IST) }
    private fun now(t: LocalTime): LocalDateTime = day.atTime(t).plusSeconds(20)

    /** NIFTY's 15-minute bars today (o, h, l, c in points from 25,000 / 50): down two, up two, down two, quiet, a break at 11:45-11:59. */
    private val swing = listOf(doubleArrayOf(100.0, 100.0, 99.0, 99.5), doubleArrayOf(99.5, 99.0, 98.0, 98.2), doubleArrayOf(98.2, 98.0, 97.0, 97.2),
        doubleArrayOf(97.2, 99.0, 97.5, 98.8), doubleArrayOf(98.8, 100.0, 98.5, 99.8), doubleArrayOf(99.8, 99.5, 98.0, 98.2),
        doubleArrayOf(98.2, 99.0, 97.5, 97.8), doubleArrayOf(98.0, 99.0, 97.5, 98.0), doubleArrayOf(98.0, 99.0, 97.5, 98.0),
        doubleArrayOf(98.0, 99.0, 97.5, 98.0), doubleArrayOf(98.0, 101.0, 98.0, 100.5)) + List(14) { doubleArrayOf(98.0, 99.0, 97.5, 98.0) }
    private fun pts(x: Double) = 25_000.0 + (x - 100.0) * 50

    private fun indexMinutes(until: LocalTime) {
        val out = ArrayList<FakeUpstox.Candle>()
        for ((k, b) in swing.withIndex()) for (m in 0 until 15) {
            val t = LocalTime.of(9, 15).plusMinutes((15 * k + m).toLong())
            if (!t.isBefore(until)) break
            out += if (m == 0) FakeUpstox.Candle(t, pts(b[0]), pts(b[1]), pts(b[2]), pts(b[3])) else FakeUpstox.Candle(t, pts(b[3]), pts(b[3]), pts(b[3]), pts(b[3]))
        }
        upstox.minutes[niftyKey] = out
    }

    private var strike = 0
    private val optKey = "NSE_FO|WL_OPT"

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        day = AutomationSupport.tradingDayAt(9, 20).toLocalDate()
        at(LocalTime.of(9, 0))
        // The engine's own read of the same day says which contract the break buys (1-ITM of the 11:59 close).
        strike = PassedArms.itmStrike("NIFTY", 1, pts(100.5), 1)
        AutomationSupport.contracts(context, listOf(Upstox.Contract("NIFTY", day.plusDays(5), strike.toDouble(), Right.CE, 65, optKey, "NIFTY-WL-CE")))
        PassedArmsHost.testHistory = { u -> if (u == "NIFTY") (0 until 375).map { Bar(day.minusDays(1).atTime(9, 15).plusMinutes(it.toLong()), 24_900.0, 24_900.0, 24_900.0, 24_900.0) } else emptyList() }
        PassedArmsHost.testDaily = { emptyList() }
        runBlocking { PassedArmsHost.prefetchNow(day) }
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun option(until: LocalTime, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { val c = px(t); out += FakeUpstox.Candle(t, c, c + 0.5, c - 0.5, c); t = t.plusMinutes(1) }
        upstox.minutes[optKey] = out
    }

    @Test fun aGannBreakIsWrittenDownPricedAndTwinnedWithNoOrderAnywhere() {
        assertTrue(WatchlistHost.on())
        // 11:59: the break bar is not complete yet - nothing.
        indexMinutes(LocalTime.of(11, 59)); option(LocalTime.of(11, 59)) { 100.0 }
        at(LocalTime.of(11, 59)); runBlocking { WatchlistHost.round(now(LocalTime.of(11, 59))) }
        assertTrue(WatchlistHost.view.value.recs.isEmpty())
        // 12:00: the 11:45-11:59 bar closed through the swing top: a shadow record of the 1-ITM call at 12:00.
        indexMinutes(LocalTime.of(12, 0)); option(LocalTime.of(12, 0)) { 100.0 }
        at(LocalTime.of(12, 0)); runBlocking { WatchlistHost.round(now(LocalTime.of(12, 0))) }
        val r = WatchlistHost.view.value.recs.single()
        assertEquals(Watchlist.Rule.R4_8, r.s.rule); assertEquals(1, r.s.side); assertEquals(strike, r.s.strike)
        assertEquals(LocalTime.of(12, 0), r.s.entry.toLocalTime()); assertEquals("NIFTY-WL-CE", r.symbol); assertEquals(day.plusDays(5), r.expiry)
        assertFalse(r.final)
        // 12:31: its 30 minutes are up: priced from the option's own minutes (100 at 12:00, 110 from 12:10) with the paper costs.
        indexMinutes(LocalTime.of(12, 31)); option(LocalTime.of(12, 31)) { t -> if (t.isBefore(LocalTime.of(12, 10))) 100.0 else 110.0 }
        at(LocalTime.of(12, 31)); runBlocking { WatchlistHost.round(now(LocalTime.of(12, 31))) }
        val p = WatchlistHost.view.value.recs.single()
        assertEquals(WatchlistHost.DONE, p.state)
        assertEquals("time", p.p!!.why)
        assertEquals(Watchlist.paperBuy("NIFTY", 100.0), p.p!!.entryFill, 1e-9)
        assertEquals(Watchlist.paperSell("NIFTY", 110.0), p.p!!.exitFill, 1e-9)
        assertTrue(p.net!! > 0)
        // The evening: the day's twins (random minutes, same contract and exits).
        indexMinutes(LocalTime.of(15, 26)); option(LocalTime.of(15, 26)) { t -> 100.0 + (t.minute % 7) }
        at(LocalTime.of(15, 26)); runBlocking { WatchlistHost.round(now(LocalTime.of(15, 26))) }
        assertEquals(Watchlist.TWINS, WatchlistHost.view.value.recs.single().twins.size)
        val card = WatchlistHost.cards().single { it.rule == Watchlist.Rule.R4_8 }
        assertEquals(1, card.trades)
        assertTrue(WatchlistHost.diagLine().contains("R52 R4-8 Gann swing break: 1 shadow trade"))
        // Not one order: paper or live.
        assertTrue(Paper.state.orders.isEmpty())
        assertEquals(emptyList<FakeKite.Req>(), kite.placed)
        // A restart reads the log back.
        WatchlistHost.resetForTest()
        assertEquals(1, WatchlistHost.cards().single { it.rule == Watchlist.Rule.R4_8 }.trades)
    }

    @Test fun offNothingMoreIsRecorded() {
        assertTrue(WatchlistHost.setOn(false).contains("off"))
        indexMinutes(LocalTime.of(12, 0)); option(LocalTime.of(12, 0)) { 100.0 }
        at(LocalTime.of(12, 0)); runBlocking { WatchlistHost.watch() }
        assertTrue(WatchlistHost.view.value.recs.isEmpty())
        assertFalse(WatchlistHost.view.value.on)
        WatchlistHost.resetForTest()
        assertFalse(WatchlistHost.on())
        assertTrue(WatchlistHost.setOn(true).contains(Watchlist.NOTE))
    }
}
