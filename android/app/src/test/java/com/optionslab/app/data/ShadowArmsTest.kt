package com.optionslab.app.data

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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The shadow tracker ([ShadowArms]) records a virtual trade and never orders: O08 (Midday momentum, NIFTY) over a synthetic
 * day - NIFTY climbs from 24,000 at 09:15 to 24,100 at 11:59 (ATR14 100 from the daily candles: a move of 1 ATR, closing at
 * the day's high), so at 12:00 it buys the 23,900 CE (4 strikes in the money) in the shadow; from 12:30 NIFTY falls to 24,040,
 * through its index stop (0.3 x ATR below the 12:00 price), so the 12:31 pass closes it. The paper account's orders stay as
 * they were, Zerodha gets no request at all, and the record survives a reload from its encrypted file both times.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ShadowArmsTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|SHADOWO08CE"
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")
    private val bankKey = Upstox.INDEX_KEYS.getValue("BANKNIFTY")
    private val bankCe = "NSE_FO|SHADOWBNCE"

    /** NIFTY at the minute of day [m]: the climb to 11:59, 24,110 - 24,120 after it, 24,040 from 12:30. */
    private fun price(m: Int): Double = when {
        m < 12 * 60 -> 24_000.0 + 100.0 * (m - (9 * 60 + 15)) / (11 * 60 + 59 - (9 * 60 + 15))
        m < 12 * 60 + 30 -> 24_110.0 + (m - 12 * 60) % 10
        else -> 24_040.0
    }

    /** NIFTY's 1-minute bars the feed has at [t]: every minute that has finished by then. */
    private fun nifty(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }.filter { !it.plusMinutes(1).isAfter(t) }
        .map { s -> val p = price(s.hour * 60 + s.minute); Upstox.Bar(s.atZone(IST).toEpochSecond(), p, p, p, p, 1_000, 0) }

    /** 20 sessions before today, each a 100-point day with no gap: ATR14 = 100. */
    private fun daily(): List<Upstox.Bar> = (1..20).map { k ->
        Upstox.Bar(day.minusDays(k.toLong()).atStartOfDay(IST).toEpochSecond(), 24_000.0, 24_050.0, 23_950.0, 24_000.0, 0, 0)
    }

    @Before fun up() {
        upstox = FakeUpstox()                               // (clears Market.testClock: set it after)
        kite = FakeKite()
        TradeFixtures.paperSettings()
        day = AutomationSupport.tradingDayAt(11, 0).toLocalDate()
        at(LocalTime.of(11, 0))
        val expiry = day.plusDays(2)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("NIFTY", expiry, 23_900.0, Right.CE, 75, ceKey, "NIFTY-SHADOW-23900CE"),
            Upstox.Contract("NIFTY", expiry, 24_300.0, Right.PE, 75, "NSE_FO|SHADOWO08PE", "NIFTY-SHADOW-24300PE"),
            Upstox.Contract("BANKNIFTY", expiry, 51_900.0, Right.CE, 30, bankCe, "BANKNIFTY-SHADOW-51900CE"),
            Upstox.Contract("BANKNIFTY", expiry, 51_900.0, Right.PE, 30, "NSE_FO|SHADOWBNPE", "BANKNIFTY-SHADOW-51900PE")))
        upstox.price(ceKey, 250.0)
        ShadowArms.testFeed = { key, t -> if (key == niftyKey) nifty(t) else emptyList() }
        ShadowArms.testDaily = { _ -> daily() }
    }

    @After fun down() {
        ShadowArms.testNow = null; ShadowArms.testFeed = null; ShadowArms.testDaily = null
        Market.testClock = null
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(t: LocalTime, seconds: Long = 0) {
        val z = day.atTime(t).plusSeconds(seconds).atZone(IST)
        ShadowArms.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    private fun tick(t: LocalTime, seconds: Long = 5) { at(t, seconds); runBlocking { ShadowArms.tick() } }
    private fun momo() = runBlocking { ShadowArms.rows() }.single { it.variant == ShadowRules.MOMO_O08 }

    /** Nothing ordered anywhere: the paper account's orders as they were, no order (or any write) sent to Zerodha. */
    private fun assertNoOrders(paperOrders: Int) {
        assertEquals("the paper account was not touched", paperOrders, Paper.state.orders.size)
        assertTrue("no paper position", Paper.state.positions.none { it.symbol == "NIFTY-SHADOW-23900CE" })
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    @Test fun aShadowRecordsAVirtualTradeWithoutAnyOrderAndSurvivesAReload() {
        val paperOrders = Paper.state.orders.size
        tick(LocalTime.of(11, 59))
        assertTrue("before 12:00 nothing", momo().trades.isEmpty())

        tick(LocalTime.of(12, 0))
        val open = momo().trades.single()
        assertTrue(open.open)
        assertEquals(ShadowRules.MOMO_O08.id, open.variant)
        assertEquals("CE", open.right); assertEquals(23_900, open.strike); assertEquals(75, open.qty)
        assertEquals("the LTP plus the paper fill's 5 bps", ShadowRules.entryFill(250.0), open.entry, 1e-9)
        assertEquals(day.atTime(12, 0), open.signalBar)
        // The 12:00 index price stands in by the 11:59 close (24,100) until that minute prints: stop 0.3 x 100 below it.
        assertEquals(24_070.0, open.indexStop!!, 1e-6)
        assertTrue(open.paper.not()); assertNull(open.orderId)
        assertNoOrders(paperOrders)

        // A restart: the open shadow position is read back from its file.
        AutomationSupport.reloadFromDisk(ShadowArms)
        assertEquals(open, momo().trades.single())

        tick(LocalTime.of(12, 10))
        assertTrue("above its stop: held", momo().trades.single().open)

        tick(LocalTime.of(12, 31))
        val done = momo().trades.single()
        assertEquals("index_stop", done.why)
        val last = done.last!!
        assertEquals(ShadowRules.exitFill("index_stop", done.entry, last), done.exit!!, 1e-9)
        assertEquals(ShadowRules.charges(done.entry, done.exit!!, 75), done.charges, 1e-9)
        assertNoOrders(paperOrders)

        AutomationSupport.reloadFromDisk(ShadowArms)
        val row = momo()
        assertEquals(done, row.trades.single())
        assertEquals(1, row.summary.trades)
        assertEquals(done.net!!, row.summary.net, 1e-9)
        assertEquals(day, row.since)
        assertTrue(row.line, row.line.startsWith("Shadow (O08, no orders): 1 trade since "))
        // The other shadows decided nothing (no BANKNIFTY bars in this feed) and ordered nothing either.
        assertTrue(runBlocking { ShadowArms.rows() }.filter { it.variant != ShadowRules.MOMO_O08 }.all { it.trades.isEmpty() })
        // Jarvis's words carry the same figures.
        assertTrue(runBlocking { ShadowArms.answer() }.contains("Midday momentum (NIFTY): " + row.line))
        // And the arms' view hands the rows to the screen.
        assertTrue(runBlocking { OrbArms.view() }.shadows.any { it.variant == ShadowRules.MOMO_O08 && it.trades.size == 1 })
    }

    @Test fun nothingRunsOutsideTheMarketWindow() {
        tick(LocalTime.of(9, 10))
        assertTrue("no record before the first pass in the window", runBlocking { ShadowArms.rows() }.isEmpty())
        tick(LocalTime.of(15, 13))
        assertTrue(runBlocking { ShadowArms.rows() }.isEmpty())
        assertEquals(emptyList<String>(), upstox.requests.toList())
    }

    // ---- the losing-trades study's shadows (OP10 / FP10): priced and walked on the option's own candles ---------------

    /**
     * BANKNIFTY at the minute of day [m]: 51,900 (odd minutes 5 bp higher: lively, so the last half hour's volatility is
     * not the quietest) to 10:04 - the opening range 51,900 - 51,925.95, the 09:20 bar closing at 51,900 (ATM 51,900) -
     * then 52,100 from 10:05: the 10:05 bar closes above the range, a fresh break up.
     */
    private fun bank(m: Int): Double = (if (m < 10 * 60 + 5) 51_900.0 else 52_100.0) * (if (m % 2 == 1) 1.0005 else 1.0)

    /** The 51,900 CE's candles: 300 at 10:10, a low of 289 at 10:11 (the LIMIT 290 fills), 302 at 10:12, back to 289 at 10:13. */
    private fun ce(m: Int): Upstox.Bar? {
        val s = day.atTime(m / 60, m % 60).atZone(IST).toEpochSecond()
        val (o, h, l) = when (m) {
            10 * 60 + 10 -> Triple(300.0, 301.0, 295.0)
            10 * 60 + 11 -> Triple(296.0, 296.0, 289.0)
            10 * 60 + 12 -> Triple(297.0, 302.0, 296.0)
            10 * 60 + 13 -> Triple(295.0, 295.0, 289.0)
            else -> if (m < 10 * 60 + 10) return null else Triple(280.0, 280.0, 280.0)
        }
        return Upstox.Bar(s, o, h, l, o, 1_000, 0)
    }

    /** What the feed has at [t]: the minutes that have finished by then. */
    private fun upTo(t: LocalDateTime, f: (Int) -> Upstox.Bar?): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .filter { m -> !day.atTime(m / 60, m % 60).plusMinutes(1).isAfter(t) }.mapNotNull(f)

    private fun bankFeed() {
        ShadowArms.testFeed = { key, t -> when (key) {
            bankKey -> upTo(t) { m -> val p = bank(m); Upstox.Bar(day.atTime(m / 60, m % 60).atZone(IST).toEpochSecond(), p, p, p, p, 1_000, 0) }
            bankCe -> upTo(t) { ce(it) }
            else -> emptyList()
        } }
    }

    private fun row(v: ShadowRules.Variant) = runBlocking { ShadowArms.rows() }.single { it.variant == v }

    @Test fun aStudyShadowIsPricedOnItsCandlesWalkedToItsExitAndNeverOrders() {
        bankFeed()
        upstox.price(bankCe, 300.0)
        val paperOrders = Paper.state.orders.size
        // 10:10: the 10:05 bar broke up; ORB's and ORB Fresh's study shadows decide, and wait for the option's candles.
        tick(LocalTime.of(10, 10))
        for (v in listOf(ShadowRules.ORB_P10, ShadowRules.ORBF_P10)) {
            assertTrue(v.id, row(v).trades.isEmpty())
            assertEquals(v.id, "pricing", row(v).status)
        }
        assertEquals("no sweep, no fade: nothing", emptyList<ShadowArms.Trade>(),
            listOf(ShadowRules.SWEEP_H1, ShadowRules.SWEEP_14, ShadowRules.FADE_P10).flatMap { row(it).trades })
        // A restart: the decisions waiting for their price are read back.
        AutomationSupport.reloadFromDisk(ShadowArms)
        tick(LocalTime.of(10, 11))
        assertTrue("10:10's low 295 is above the LIMIT 290: still waiting", row(ShadowRules.ORB_P10).trades.isEmpty())
        tick(LocalTime.of(10, 12))
        for (v in listOf(ShadowRules.ORB_P10, ShadowRules.ORBF_P10)) {
            val open = row(v).trades.single()
            assertTrue(open.open); assertEquals(v.id, open.variant)
            assertEquals("the LIMIT 10 under 10:10's open, filled at 10:11", 290.0, open.entry, 1e-9)
            assertEquals(day.atTime(10, 11), open.entryTime); assertEquals(day.atTime(10, 5), open.signalBar)
            assertEquals("CE", open.right); assertEquals(51_900, open.strike); assertEquals(30, open.qty)
            assertTrue(!open.paper); assertNull(open.orderId)
        }
        assertNoBankOrders(paperOrders)
        AutomationSupport.reloadFromDisk(ShadowArms)
        assertEquals(290.0, row(ShadowRules.ORB_P10).trades.single().entry, 1e-9)

        // 10:14: the walk reads 10:11 - 10:13: +12 at 10:12 locks the price paid, 10:13's low 289 sells at the lock.
        tick(LocalTime.of(10, 14))
        val done = row(ShadowRules.ORB_P10).trades.single()
        assertEquals("profit_lock", done.why); assertEquals(day.atTime(10, 13), done.exitTime)
        assertEquals(ShadowRules.exitFill("profit_lock", 290.0, 290.0), done.exit!!, 1e-9)
        assertEquals(ShadowRules.charges(290.0, done.exit!!, 30), done.charges, 1e-9)
        assertEquals("best move while held", 12.0, done.mfe!!, 1e-9)
        assertEquals("worst move while held", 1.0, done.mae!!, 1e-9)
        assertEquals("FAILED_LOCK", done.group)
        assertNoBankOrders(paperOrders)

        // 15:10: the day's read settles the move to the close; the record survives a reload, and Jarvis names why it lost.
        tick(LocalTime.of(15, 10), seconds = 30)
        AutomationSupport.reloadFromDisk(ShadowArms)
        val settled = row(ShadowRules.ORB_P10).trades.single()
        assertTrue(settled.settled); assertEquals(12.0, settled.dayMfe!!, 1e-9)
        assertEquals(listOf(com.optionslab.engine.orb.ShadowStudy.LossGroup.FAILED_LOCK), row(ShadowRules.ORB_P10).losses)
        assertEquals(1, row(ShadowRules.ORBF_P10).summary.trades)
        val words = runBlocking { ShadowArms.answer() }
        assertTrue(words, words.contains("ORB: Shadow (OP10, no orders): 1 trade since "))
        assertTrue(words, words.contains("Its losers: mostly failed lock (back to the price paid) (1 of 1 loser)."))
        assertNoBankOrders(paperOrders)
    }

    private fun assertNoBankOrders(paperOrders: Int) {
        assertEquals("the paper account was not touched", paperOrders, Paper.state.orders.size)
        assertTrue("no paper position", Paper.state.positions.none { it.symbol.startsWith("BANKNIFTY-SHADOW") })
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }
}
