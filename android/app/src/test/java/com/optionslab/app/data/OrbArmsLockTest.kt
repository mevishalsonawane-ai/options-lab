package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 19 Nov 2026 (audit batch 2: the phone showed "ORB arms max 9 min 40 s" and "bar-close entry check: ORB arms 10 min 11 s" - every index
 * candle read ran under the arms' one lock, and the open positions' stop checks waited behind it). Liquidity 15+5's paper day from
 * [LiquidityArmTest] (the 5-minute book buys the 54,000 call at 13:05, the index then holds at 54,150), with BANKNIFTY's candle feed made
 * to hang for minutes:
 *  - the regular stop look still runs the open position's exits (its 20-minute time stop) within seconds;
 *  - a pass stuck on that read never blocks the stream's exit look nor the screens' [OrbArms.view];
 *  - [OrbArms.view] never waits on the lock - it shows the book as last saved while a pass holds it;
 *  - candidate (f)'s record-only flag, read after the pass, is still written onto the trade.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbArmsLockTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|LOCKCE"
    private val peKey = "NSE_FO|LOCKPE"
    private val pe0Key = "NSE_FO|LOCKPE0"
    /** Released at the end of each test: a hung read never outlives it. */
    private val gate = CountDownLatch(1)
    /** [LiquidityArmTest]'s failed break: the 13:05 bar closes back at 54,090, so the position is sold at 13:10 ("failed_break"). */
    private var failDay = false

    /** The 5-minute bar k (09:15 + 5k) as (open, high, low, close): [LiquidityArmTest]'s day where the break holds. */
    private fun bar(k: Int): DoubleArray = when {
        k == 20 -> doubleArrayOf(54_050.0, 54_100.0, 54_040.0, 54_060.0)
        k == 26 -> doubleArrayOf(54_040.0, 54_070.0, 54_020.0, 54_030.0)
        k == 45 -> doubleArrayOf(54_020.0, 54_150.0, 54_015.0, 54_140.0)
        k == 46 && failDay -> doubleArrayOf(54_140.0, 54_145.0, 54_080.0, 54_090.0)
        k >= 46 -> doubleArrayOf(54_150.0, 54_150.0, 54_145.0, 54_150.0)
        else -> doubleArrayOf(54_000.0, 54_010.0, 53_990.0, 54_000.0)
    }

    private fun feed(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }
        .filter { !it.plusMinutes(1).isAfter(t) }
        .map { start ->
            val b = bar((start.hour * 60 + start.minute - (9 * 60 + 15)) / 5)
            Upstox.Bar(start.atZone(IST).toEpochSecond(), b[0], b[1], b[2], b[3], 1000, 0)
        }

    /** Released at the end of each test too: the arms' lock held by a test ([OrbArms.holdLockForTest]) and a slow history read. */
    private val release = CountDownLatch(1)
    private val landing = CountDownLatch(1)

    /** BANKNIFTY's feed hung: each read waits (for minutes) until the test ends. */
    private fun hang() { OrbArms.testIndexBars = { t -> gate.await(10, TimeUnit.MINUTES); feed(t) } }

    @Before fun up() {
        upstox = FakeUpstox()
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        day = AutomationSupport.tradingDayAt(12, 50).toLocalDate()
        at(LocalTime.of(12, 50))
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.CE, 30, ceKey, "BANKNIFTY-LOCK-54000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_200.0, Right.PE, 30, peKey, "BANKNIFTY-LOCK-54200PE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.PE, 30, pe0Key, "BANKNIFTY-LOCK-54000PE")))
        upstox.price(ceKey, 300.0)
        upstox.price(peKey, 280.0)
        OrbArms.testIndexBars = { t -> feed(t) }
        OrbArms.testHistoryBars = { emptyList() }
        runBlocking { OrbArms.setLiquidityLots(1, "test") }
    }

    @After fun down() {
        gate.countDown(); release.countDown(); landing.countDown()
        OrbArms.testMinutesInMemory = null
        OrbArms.testPassReadMs = null
        com.optionslab.ira.ArmExits.clear()
        AutomationSupport.realClocks()
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(t: LocalTime) {
        val z = day.atTime(t).atZone(IST)
        OrbArms.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    private fun tick(t: LocalTime) { at(t); runBlocking { OrbArms.tick() } }

    private fun passes(from: LocalTime, until: LocalTime) {
        var t = from
        while (!t.isAfter(until)) { tick(t); t = t.plusMinutes(1) }
    }

    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }

    /** The day up to 13:24: the call bought at 13:05 and held, +3% (short of the time stop's +5% at 20 minutes). */
    private fun heldTo1324(): OrbArms.Position {
        val msg = runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        assertTrue(msg, msg.startsWith("Liquidity 15+5 armed on paper"))
        passes(LocalTime.of(13, 0), LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue(p.open)
        upstox.price(ceKey, p.entry * 1.03)
        passes(LocalTime.of(13, 6), LocalTime.of(13, 24))
        assertTrue("held until its 20 minutes are up", row().today.single().open)
        return p
    }

    /**
     * 20 Nov 2026: how long a step may take on a loaded CI runner. Never what a test proves: each blocked read or held lock here is held
     * until the test lets it go (at its end at the latest, ten minutes at most), so a step that ends at all did not wait for it; how long
     * the look's own read waited is checked against its budget ([lookWaitedItsBudget]).
     */
    private val SLOW_RUNNER_MS = 60_000L

    /** The last read before the lock had [budget] and stopped at it: it waited no longer than the budget (plus a slow runner's slack). */
    private fun lookWaitedItsBudget(budget: Long) {
        val (b, took) = OrbArms.lastPrefetch!!
        assertEquals("the look's own read budget", budget, b)
        assertTrue("it stopped waiting at its budget ($took ms of $b)", took < b + 5_000)
    }

    /** [block] on its own thread; true when it ended within [ms] (it is left to end by itself otherwise - the gate frees it). */
    private fun endsWithin(ms: Long, block: () -> Unit): Boolean {
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val th = Thread { try { block() } catch (e: Throwable) { failure.set(e) } }.apply { isDaemon = true; start() }
        th.join(ms)
        failure.get()?.let { throw AssertionError("it failed", it) }
        return !th.isAlive
    }

    @Test fun aHungIndexReadNeverHoldsTheStopLookItsTimeStopStillSells() {
        heldTo1324()
        hang()
        at(LocalTime.of(13, 25))
        // (20 Nov 2026: the read hangs until the test ends - ten minutes at most - so a look that ends at all stopped waiting at its own
        // deadline; the bound is only what a slow runner may need, never what the read takes.)
        assertTrue("the 15-second stop look ended although the candle read hangs", endsWithin(SLOW_RUNNER_MS) { runBlocking { OrbArms.priceCheckOnly() } })
        lookWaitedItsBudget(OrbArms.LOOK_READ_MS)
        val closed = row().today.single()
        assertFalse("the time stop sold it although the index could not be read", closed.open)
        assertEquals("time_stop", closed.why)
    }

    @Test fun aPassStuckOnItsCandleReadNeverBlocksTheStreamLookOrTheScreens() {
        heldTo1324()
        hang()
        at(LocalTime.of(13, 24, 30))
        // (20 Nov 2026: the pass reads until the test lets its read go - never ending by its own deadline in the middle of the checks.)
        OrbArms.testPassReadMs = 10 * 60_000L
        val pass = Thread { runCatching { runBlocking { OrbArms.tick() } } }.apply { isDaemon = true; start() }
        Thread.sleep(300)
        assertTrue("the pass is still reading", pass.isAlive)
        assertTrue("the stream's exit look did not wait for the pass", endsWithin(SLOW_RUNNER_MS) { runBlocking { OrbArms.priceCheckOnly(fast = true, stream = true) } })
        assertTrue("the screens' view did not wait for the pass", endsWithin(SLOW_RUNNER_MS) { runBlocking { OrbArms.view() } })
        assertTrue("both while the pass was still reading", pass.isAlive)
        // The read comes back: the pass goes on - the position is still watched, nothing new was bought.
        gate.countDown()
        pass.join(SLOW_RUNNER_MS)
        assertFalse("the pass ended", pass.isAlive)
        assertTrue(row().today.single().open)
        assertEquals("one buy only", 1, Paper.state.orders.count { it.action == "BUY" })
    }

    /** The day up to 13:05 on the failed-break day: the call bought, its index exit due at 13:10. */
    private fun boughtOnTheFailDay(): OrbArms.Position {
        failDay = true
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        return row().today.single().also { assertTrue(it.open) }
    }

    /** Yesterday's session, flat at 54,000: the earlier sessions the levels read. */
    private fun yesterday(): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30).map { day.minusDays(1).atTime(it / 60, it % 60) }
        .map { Upstox.Bar(it.atZone(IST).toEpochSecond(), 54_000.0, 54_010.0, 53_990.0, 54_000.0, 1000, 0) }

    @Test fun aSlowHistoryReadStillLandsAndAnOpenPositionsIndexExitsNeverWaitForIt() {
        boughtOnTheFailDay()
        // A restart mid-day: the earlier sessions, on a slow link, take longer than a look may wait.
        // (20 Nov 2026, CI on 3d7f620d0: the look was timed with the wall clock, the paper sale and the runner's load included. Now the
        // read is held by a latch until the look has ended: a look that ends at all did not wait for it.)
        val past = yesterday()
        OrbArms.testHistoryBars = { landing.await(10, TimeUnit.MINUTES); past }
        at(LocalTime.of(13, 10))
        assertTrue("the look stopped waiting at its deadline", endsWithin(SLOW_RUNNER_MS) { runBlocking { OrbArms.priceCheckOnly() } })
        lookWaitedItsBudget(OrbArms.LOOK_READ_MS)
        // The failed break needs only today's minutes and the level the position stored: sold now, not after the history.
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals("failed_break", closed.why)
        assertEquals("not landed yet", 0, OrbArms.historyKeptForTest("BANKNIFTY"))
        // The read was not cut: once it comes back it lands by itself and is kept in memory.
        landing.countDown()
        val end = System.currentTimeMillis() + SLOW_RUNNER_MS
        while (OrbArms.historyKeptForTest("BANKNIFTY") == 0 && System.currentTimeMillis() < end) Thread.sleep(50)
        assertEquals("yesterday's session kept", past.size, OrbArms.historyKeptForTest("BANKNIFTY"))
    }

    @Test fun anEntryWaitsWhileTheEarlierSessionsAreStillRead() {
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        passes(LocalTime.of(12, 50), LocalTime.of(13, 4))
        // The 13:00 break is decided at 13:05 - with the earlier sessions still being read (slower than the pass may wait).
        val past = yesterday()
        OrbArms.testHistoryBars = { gate.await(10, TimeUnit.MINUTES); past }       // still reading until the test ends
        tick(LocalTime.of(13, 5))
        assertTrue("nothing bought on today's candles alone", row().today.isEmpty())
        assertTrue(row().status, row().status.contains(OrbArms.describe("liquidity_history_loading")))
    }

    @Test fun aFastLookStillRunsTheTimeStopWhenTheIndexMinutesAreNotInMemory() {
        heldTo1324()
        at(LocalTime.of(13, 25))
        runBlocking { OrbArms.priceCheckOnly(fast = true, stream = true) }
        val closed = row().today.single()
        assertFalse("sold on the stream's look, no candle read", closed.open)
        assertEquals("time_stop", closed.why)
    }

    @Test fun aFastLookJudgesTheIndexExitsFromTheMinutesInMemory() {
        boughtOnTheFailDay()
        OrbArms.testMinutesInMemory = { u, t -> if (u == "BANKNIFTY") feed(t) else null }
        hang()                                                              // the feed itself is never read on a fast look
        at(LocalTime.of(13, 10))
        assertTrue(endsWithin(SLOW_RUNNER_MS) { runBlocking { OrbArms.priceCheckOnly(fast = true, stream = true) } })
        val closed = row().today.single()
        assertFalse(closed.open)
        assertEquals("failed_break", closed.why)
    }

    @Test fun theStopRescuesFreshViewWaitsForTheBookItself() {
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        val held = CountDownLatch(1)
        val holder = Thread { runBlocking { OrbArms.holdLockForTest(release, held) } }.apply { isDaemon = true; start() }
        assertTrue(held.await(SLOW_RUNNER_MS, TimeUnit.MILLISECONDS))
        val got = java.util.concurrent.atomic.AtomicReference<OrbArms.View?>()
        val failed = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val returnedAt = java.util.concurrent.atomic.AtomicLong(0)
        val reader = Thread { try { got.set(runBlocking { OrbArms.view(fresh = true) }); returnedAt.set(System.nanoTime()) } catch (e: Throwable) { failed.set(e) } }
            .apply { isDaemon = true; start() }
        reader.join(2_000)
        val releasedAt = System.nanoTime()
        release.countDown()
        reader.join(SLOW_RUNNER_MS)
        failed.get()?.let { throw AssertionError("the fresh view failed", it) }
        assertTrue("it answered only once the lock was let go - never a copy", returnedAt.get() >= releasedAt)
        assertTrue(got.get()!!.arms.single { it.arm.source == "liquidity" }.armed)
        holder.join(SLOW_RUNNER_MS)
    }

    @Test fun theViewNeverWaitsOnTheLockItShowsTheBookAsLastSaved() {
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        assertTrue(row().armed)
        // (20 Nov 2026: the lock is held until the view has answered - an answer at all means it never waited for the lock.)
        val held = CountDownLatch(1)
        val holder = Thread { runBlocking { OrbArms.holdLockForTest(release, held) } }.apply { isDaemon = true; start() }
        assertTrue(held.await(SLOW_RUNNER_MS, TimeUnit.MILLISECONDS))
        val got = java.util.concurrent.atomic.AtomicReference<OrbArms.View?>()
        assertTrue("answered while the lock is held", endsWithin(SLOW_RUNNER_MS) { got.set(runBlocking { OrbArms.view() }) })
        assertTrue("the book as last saved", got.get()!!.arms.single { it.arm.source == "liquidity" }.armed)
        assertTrue("the lock was still held", holder.isAlive)
        release.countDown()
        holder.join(SLOW_RUNNER_MS)
        // Free again: the book itself, as before.
        runBlocking { OrbArms.setArmed("liquidity", false, automatic = true) }
        assertFalse(row().armed)
    }

    @Test fun withNoInstrumentListTodayTheBreakBuysNothingAndNothingIsDownloadedUnderTheLock() {
        java.io.File(context.filesDir, "contracts.json").delete()           // today's list not on the phone (not downloaded yet)
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        // 20 Nov 2026 (audit): the signal waits for the list within its fill window (its bar decided again by the next look) ...
        assertTrue(row().status, row().status.contains(OrbArms.describe(OrbArms.LIST_WAITING)))
        // ... and, the list still not there when that window ends, it is never bought late: no contract, as before.
        passes(LocalTime.of(13, 6), LocalTime.of(13, 8))
        assertTrue("no contract: nothing bought", row().today.isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
        assertTrue(row().status, row().status.contains(OrbArms.describe("no_contract")))
        // (NetworkGuard, checked after each test: no download was tried, under the lock or off it.)
    }

    @Test fun candidateFsFlagIsReadAfterThePassAndStillWrittenOntoTheTrade() {
        // The call's premium rising and the put's at the same strike falling (momentum), but the close only 7.4 bp past the swept level:
        // (f) would not have taken it - false, as before 19 Nov; now read after the pass, off the lock, and written on then.
        fun option(t: LocalDateTime, from: Double, step: Double): List<Upstox.Bar> = (12 * 60 + 30 until 15 * 60 + 30)
            .map { day.atTime(it / 60, it % 60) }.filter { !it.plusMinutes(1).isAfter(t) }
            .mapIndexed { i, start -> val c = from + step * i; Upstox.Bar(start.atZone(IST).toEpochSecond(), c, c, c, c, 1000, 0) }
        OrbArms.testOptionBars = { key, t -> when (key) { ceKey -> option(t, 200.0, 1.0); pe0Key -> option(t, 300.0, -1.0); else -> emptyList() } }
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue("traded exactly as before", p.open)
        assertEquals(false, p.strong)
        AutomationSupport.reloadFromDisk(OrbArms)
        assertEquals("saved with it", false, row().today.single().strong)
    }
}
