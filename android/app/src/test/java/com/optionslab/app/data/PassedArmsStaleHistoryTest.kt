package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.StepTrail
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
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * 20 Nov 2026 (audit B5): R51 / R72's data and locks.
 * - Item 1: a history that does not reach the last trading day (yesterday missing, or read in part) is told ([PassedArmsHost.staleWhy]); a
 *   break whose 20-bar channel reads it is logged in shadow by default ("would have skipped (stale history)", taken as before), and with
 *   Bot settings' switch on (one tap; off takes the PIN) it waits while the history is read again and is let go when its window ends.
 * - Item 2: a missing, empty or stale history is read again on a back-off (1, 2, then 5 minutes), in the background.
 * - Items 5 and 6: a lock the walk earned is saved at once (a restart that lost the minutes still sells at it), and while the option's
 *   minutes stop coming the price now raises the same lock.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PassedArmsStaleHistoryTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")
    private val ce = "NSE_FO|SH25050CE"
    private val pe = "NSE_FO|SH25150PE"
    private val sym = "NIFTY26OCT25050CE"
    private val token = 26_000_001L

    private fun at(t: LocalTime, seconds: Long = 20) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime, seconds: Long = 20) { at(t, seconds); runBlocking { PassedArmsHost.tick() } }
    private fun manage(t: LocalTime) { at(t); runBlocking { PassedArmsHost.manageOnly() } }

    private fun minutes(key: String, until: LocalTime, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { val c = px(t); out += FakeUpstox.Candle(t, c, c + 1, c - 1, c); t = t.plusMinutes(1) }
        upstox.minutes[key] = out
    }

    private val prev: LocalDate get() = PassedArmsHost.previousSession(day)!!

    /** NIFTY flat at 25,000 on [d] (high +1, low -1) from 09:15 to [until] (excluded). */
    private fun session(d: LocalDate, until: LocalTime = LocalTime.of(15, 30)): List<Bar> =
        (0 until PassedArms.Session.N).map { d.atTime(9, 15).plusMinutes(it.toLong()) }.filter { it.toLocalTime().isBefore(until) }
            .map { Bar(it, 25_000.0, 25_001.0, 24_999.0, 25_000.0) }

    private fun sessions(vararg d: LocalDate): List<PassedArms.DayBars> = d.map { x -> PassedArms.DayBars.of(x, session(x)) }

    /** NIFTY's history read afresh (the book and memory wiped first): [bars], no daily candles unless [daily]. */
    private fun history(bars: List<Bar>, daily: List<Bar> = emptyList()) {
        PassedArmsHost.wipe()
        PassedArmsHost.testHistory = { u -> if (u == "NIFTY") bars else emptyList() }
        PassedArmsHost.testDaily = { u -> if (u == "NIFTY") daily else emptyList() }
        runBlocking { PassedArmsHost.prefetchNow(day) }
        for (s in PassedArmsHost.SOURCES) runBlocking { PassedArmsHost.setArmed(s, s == DonchianArm.SOURCE, log = false) }
    }

    /** NIFTY 25,000 until 09:18, then 25,100 (R51's 09:20 call break); the CE at [call]. */
    private fun market(until: LocalTime, call: (LocalTime) -> Double = { 100.0 }) {
        minutes(niftyKey, until) { t -> if (t.isBefore(LocalTime.of(9, 19))) 25_000.0 else 25_100.0 }
        minutes(ce, until, call)
        minutes(pe, until) { 90.0 }
    }

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
        day = AutomationSupport.tradingDayAt(9, 20).toLocalDate()
        at(LocalTime.of(9, 0))
        val exp = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("NIFTY", exp, 25_050.0, Right.CE, 65, ce, "NIFTY-SH-25050CE"),
            Upstox.Contract("NIFTY", exp, 25_150.0, Right.PE, 65, pe, "NIFTY-SH-25150PE"),
            Upstox.Contract("NIFTY", exp.plusDays(7), 25_000.0, Right.CE, 65, "NSE_FO|SHNEXT", "NIFTY-SH-NEXT")))
        history(session(prev))
        OrphanWatch.wipe()
        kite.requests.clear()
    }

    @After fun down() {
        com.optionslab.ira.ArmExits.clear()
        StepTrailExits.resetForTest(on = false)
        Market.testClock = null
        PassedArmsHost.testNoAutoOn = true
        if (Autopilot.enabled()) Autopilot.resetForTest()
        runBlocking { RuleHighs.settleForTest() }; RuleHighs.clear()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun view() = runBlocking { PassedArmsHost.refresh() }
    private fun real() = view().open.filter { it.shadow == null }
    private fun status() = view().status["${DonchianArm.SOURCE}|NIFTY"].orEmpty()

    // ---- item 1: what a stale history is (pure) ---------------------------------------------------------------------------------

    @Test fun aHistoryStandsOnlyWhenItReachesTheLastTradingDayWhole() {
        val before = PassedArmsHost.previousSession(prev)!!
        assertNull("whole", PassedArmsHost.staleWhy(sessions(before, prev), prev))
        assertEquals("its last session is $before, not $prev", PassedArmsHost.staleWhy(sessions(before), prev))
        // Read in part: the last 20 five-minute bars (13:50 - 15:30) are what today's first bars read.
        val half = listOf(PassedArms.DayBars.of(prev, session(prev, until = LocalTime.of(13, 0))))
        assertEquals("its $prev session lacks 20 of its last 20 five-minute bars", PassedArmsHost.staleWhy(half, prev))
        val short = listOf(PassedArms.DayBars.of(prev, session(prev, until = LocalTime.of(15, 20))))
        assertEquals("its $prev session lacks 2 of its last 20 five-minute bars", PassedArmsHost.staleWhy(short, prev))
        // A minute missing inside a five-minute bar leaves the bar there: still whole.
        val gap = listOf(PassedArms.DayBars.of(prev, session(prev).filterNot { it.start.toLocalTime() == LocalTime.of(15, 0) }))
        assertNull(PassedArmsHost.staleWhy(gap, prev))
        // Cannot be told: no previous trading day known, no session at all, or a session newer than the calendar's (the data knows better).
        assertNull(PassedArmsHost.staleWhy(sessions(before), null))
        assertNull(PassedArmsHost.staleWhy(emptyList(), prev))
        assertNull(PassedArmsHost.staleWhy(sessions(day.minusDays(1).takeIf { it.isAfter(prev) } ?: day), prev))
    }

    @Test fun overAWeekendOrAHolidayTheLastTradingDayIsTheOneBeforeIt() {
        // A Monday's last trading day is the Friday before (or earlier, over a holiday): Saturday and Sunday are never asked for.
        var monday = day
        while (monday.dayOfWeek != java.time.DayOfWeek.MONDAY || !Market.isTradingDay(monday)) monday = monday.plusDays(1)
        val friday = PassedArmsHost.previousSession(monday)!!
        assertTrue(friday.dayOfWeek !in setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY))
        assertTrue(Market.isTradingDay(friday))
        assertNull(PassedArmsHost.staleWhy(sessions(friday), friday))
    }

    @Test fun onlyABarWhoseChannelReadsAnEarlierSessionCanBeHeld() {
        val series = PassedArms.bars5(PassedArms.DayBars.of(prev, session(prev))) +
            PassedArms.bars5(PassedArms.DayBars.of(day, (0 until 150).map { Bar(day.atTime(9, 15).plusMinutes(it.toLong()), 1.0, 1.0, 1.0, 1.0) }))
        assertTrue("09:20's bar reads yesterday's 20", PassedArmsHost.readsHistory(series, day, 0))
        assertTrue(PassedArmsHost.readsHistory(series, day, 19))
        assertFalse("from 11:00 (20 bars of today) never", PassedArmsHost.readsHistory(series, day, 20))
        // A five-minute bar of today missing: the channel reaches back one bar further.
        val gappy = series.filterNot { it.date == day && it.b == 3 }
        assertTrue(PassedArmsHost.readsHistory(gappy, day, 20))
        assertFalse(PassedArmsHost.readsHistory(gappy, day, 21))
    }

    // ---- item 1: the break on a stale history ------------------------------------------------------------------------------------

    @Test fun offByDefaultAStaleHistorysBreakIsTakenAsBeforeAndLoggedOnce() {
        history(session(PassedArmsHost.previousSession(prev)!!))
        assertNotNull(PassedArmsHost.historyStale("NIFTY", day))
        assertFalse("off by default", view().staleOn)
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        assertEquals("taken as before", 1, real().size)
        market(LocalTime.of(9, 21)); tick(LocalTime.of(9, 21))
        val v = view()
        assertEquals(1, v.log.count { PassedArmsHost.STALE_MARK in it })
        assertTrue(v.log.last { PassedArmsHost.STALE_MARK in it }, v.log.any { PassedArmsHost.STALE_MARK in it && "NIFTY's earlier sessions are not whole" in it })
        assertEquals(1, v.staleShadowToday)
        assertTrue("paper only here: nothing at Zerodha", kite.placed.isEmpty())
    }

    @Test fun aWholeHistoryLogsNothing() {
        assertNull(PassedArmsHost.historyStale("NIFTY", day))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        assertEquals(1, real().size)
        assertEquals(0, view().staleShadowToday)
        // Nor does no history at all: R51 then has no break before 20 bars of today, as always.
        history(emptyList())
        assertNull(PassedArmsHost.historyStale("NIFTY", day))
        assertTrue(PassedArmsHost.historyWanted("NIFTY", day))
    }

    @Test fun onAStaleHistorysBreakWaitsAndIsBoughtOnceAWholeOneIsRead() {
        history(session(PassedArmsHost.previousSession(prev)!!))
        assertTrue(runBlocking { PassedArmsHost.setStaleSkip(true) }.startsWith("On"))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        assertTrue("held", real().isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
        assertTrue(status(), status().contains("waiting") && status().contains("read again"))
        // The read again brings the last trading day: the next look inside the window buys it.
        PassedArmsHost.testHistory = { u -> if (u == "NIFTY") session(prev) else emptyList() }
        runBlocking { PassedArmsHost.prefetchNow(day) }
        assertNull(PassedArmsHost.historyStale("NIFTY", day))
        market(LocalTime.of(9, 21)); tick(LocalTime.of(9, 21))
        assertEquals(1, real().size)
        assertEquals(0, view().staleShadowToday)
    }

    @Test fun onABreakWhoseHistoryNeverComesWholeIsLetGoWhenItsWindowEnds() {
        history(session(prev, until = LocalTime.of(13, 0)))
        assertTrue(PassedArmsHost.historyStale("NIFTY", day)!!.contains("lacks 20 of its last 20"))
        runBlocking { PassedArmsHost.setStaleSkip(true) }
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        market(LocalTime.of(9, 25)); tick(LocalTime.of(9, 25))
        assertTrue(real().isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
        assertTrue(status(), status().startsWith("09:20 call break not bought: the history was not ready") && status().contains("end of its fill window"))
        // Decided: a later look never buys it.
        market(LocalTime.of(9, 26)); tick(LocalTime.of(9, 26))
        assertTrue(real().isEmpty())
    }

    @Test fun onABreakLetGoHoldsItsSideAndCountsInTheCapsSoItNeverFreesAPlace() {
        history(session(PassedArmsHost.previousSession(prev)!!))
        runBlocking { PassedArmsHost.setStaleSkip(true) }
        // 10:50-10:55 breaks up (its channel still reads the stale session: held), 10:55-11:00 breaks up again on today's 20 bars alone.
        fun nifty(until: LocalTime) = minutes(niftyKey, until) { t -> when {
            t.isBefore(LocalTime.of(10, 50)) -> 25_000.0
            t.isBefore(LocalTime.of(10, 55)) -> 25_100.0
            else -> 25_200.0 } }
        nifty(LocalTime.of(10, 55)); tick(LocalTime.of(10, 55))
        assertTrue(status(), status().contains("waiting"))
        nifty(LocalTime.of(10, 58)); tick(LocalTime.of(10, 58))
        assertTrue(status(), status().contains("end of its fill window"))
        // The 11:00 break is skipped by the cap, as it would have been behind the 10:55 trade (its 30-minute time stop not reached).
        nifty(LocalTime.of(11, 0)); tick(LocalTime.of(11, 0))
        assertTrue(status(), status().contains("11:00 break skipped: one position a side"))
        assertTrue(real().isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
    }

    @Test fun onAHeldBreakStaysHeldAcrossARestartEvenWhenTheHistoryIsWholeByThen() {
        history(session(PassedArmsHost.previousSession(prev)!!))
        runBlocking { PassedArmsHost.setStaleSkip(true) }
        fun nifty(until: LocalTime) = minutes(niftyKey, until) { t -> when {
            t.isBefore(LocalTime.of(10, 50)) -> 25_000.0
            t.isBefore(LocalTime.of(10, 55)) -> 25_100.0
            else -> 25_200.0 } }
        nifty(LocalTime.of(10, 55)); tick(LocalTime.of(10, 55))
        assertTrue(status(), status().contains("waiting"))
        // A restart (memory gone, the book read back) after a whole read: the hold is still known at the window's end.
        assertTrue(PassedArmsHost.awaitWrites())
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        PassedArmsHost.testHistory = { u -> if (u == "NIFTY") session(prev) else emptyList() }
        runBlocking { PassedArmsHost.prefetchNow(day) }
        assertNull(PassedArmsHost.historyStale("NIFTY", day))
        nifty(LocalTime.of(10, 58)); tick(LocalTime.of(10, 58))
        nifty(LocalTime.of(11, 0)); tick(LocalTime.of(11, 0))
        assertTrue(status(), status().contains("11:00 break skipped: one position a side"))
        assertTrue(real().isEmpty())
    }

    @Test fun aWorseStaleReadNeverReplacesABetterStaleOne() {
        val before = PassedArmsHost.previousSession(prev)!!
        // Yesterday read but for its last bar: kept over a read that misses yesterday altogether.
        history(session(prev, until = LocalTime.of(15, 25)))
        PassedArmsHost.testHistory = { u -> if (u == "NIFTY") session(before) else emptyList() }
        runBlocking { PassedArmsHost.prefetchNow(day, listOf("NIFTY")) }
        assertEquals(prev, PassedArmsHost.history("NIFTY", day)!!.sessions.last().date)
        assertTrue(PassedArmsHost.historyStale("NIFTY", day)!!.contains("lacks 1 of"))
        // A fuller one of the same day does replace it.
        PassedArmsHost.testHistory = { u -> if (u == "NIFTY") session(prev, until = LocalTime.of(15, 30)) else emptyList() }
        runBlocking { PassedArmsHost.prefetchNow(day, listOf("NIFTY")) }
        assertNull(PassedArmsHost.historyStale("NIFTY", day))
    }

    @Test fun offABreakTakenAsBeforeLeavesTheCapsAsBefore() {
        // The same day with the check off (shadow): nothing is marked held; the 10:55 break goes to the buy as before.
        history(session(PassedArmsHost.previousSession(prev)!!))
        minutes(niftyKey, LocalTime.of(10, 55)) { t -> if (t.isBefore(LocalTime.of(10, 50))) 25_000.0 else 25_100.0 }
        minutes(ce, LocalTime.of(10, 55)) { 100.0 }
        tick(LocalTime.of(10, 55))
        assertEquals(1, real().size)
        assertEquals(1, view().staleShadowToday)
    }

    @Test fun onIsOneTapOffTakesThePinAndTheSwitchIsKept() {
        assertTrue(runBlocking { PassedArmsHost.setStaleSkip(true) }.startsWith("On"))
        assertTrue(view().staleOn)
        assertTrue(runBlocking { PassedArmsHost.setStaleSkip(false) }.contains("needs your PIN"))
        assertTrue("unchanged without the PIN", view().staleOn)
        assertTrue(PassedArmsHost.awaitWrites())
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertTrue("kept after a restart", view().staleOn)
        assertTrue(runBlocking { PassedArmsHost.setStaleSkip(false, pinConfirmed = true) }.startsWith("Off"))
        assertFalse(view().staleOn)
        assertTrue(runBlocking { PassedArmsHost.setStaleSkip(false, pinConfirmed = true) }.contains("already off"))
    }

    // ---- item 2: read again --------------------------------------------------------------------------------------------------------

    @Test fun theReadsBackOffOneTwoThenFiveMinutes() {
        assertEquals(60_000L, PassedArmsHost.backoffMs(1)); assertEquals(120_000L, PassedArmsHost.backoffMs(2))
        assertEquals(300_000L, PassedArmsHost.backoffMs(3)); assertEquals(300_000L, PassedArmsHost.backoffMs(PassedArmsHost.SLOW_AFTER_TRIES - 1))
        // After about an hour of reads (a day the calendar wrongly counts as trading, say) every 15 minutes, never every 5 all day.
        assertEquals(900_000L, PassedArmsHost.backoffMs(PassedArmsHost.SLOW_AFTER_TRIES)); assertEquals(900_000L, PassedArmsHost.backoffMs(40))
        assertEquals(600_000L, PassedArmsHost.backoffMs(4, PassedArmsHost.PICK_BACKOFF_MS))
        val t = PassedArmsHost.Tried(day, 1_000_000L, 2)
        assertTrue("none yet", PassedArmsHost.readDue(null, day, 0L, PassedArmsHost.HIST_BACKOFF_MS))
        assertFalse(PassedArmsHost.readDue(t, day, 1_000_000L + 119_000L, PassedArmsHost.HIST_BACKOFF_MS))
        assertTrue(PassedArmsHost.readDue(t, day, 1_000_000L + 120_000L, PassedArmsHost.HIST_BACKOFF_MS))
        assertTrue("a new day", PassedArmsHost.readDue(t, day.plusDays(1), 1_000_001L, PassedArmsHost.HIST_BACKOFF_MS))
        assertTrue("a clock set back never stops the reads", PassedArmsHost.readDue(t, day, 999_000L, PassedArmsHost.HIST_BACKOFF_MS))
    }

    @Test fun aStaleOrEmptyHistoryIsReadAgainInTheBackground() {
        history(session(PassedArmsHost.previousSession(prev)!!))
        assertTrue(PassedArmsHost.historyWanted("NIFTY", day))
        val reads = java.util.concurrent.atomic.AtomicInteger()
        PassedArmsHost.testHistory = { u -> if (u == "NIFTY") { reads.incrementAndGet(); session(prev) } else emptyList() }
        PassedArmsHost.prefetch(day.atTime(9, 0), listOf("NIFTY"))
        val until = System.currentTimeMillis() + 10_000
        while (PassedArmsHost.historyStale("NIFTY", day) != null && System.currentTimeMillis() < until) Thread.sleep(20)
        assertNull("read again and whole now", PassedArmsHost.historyStale("NIFTY", day))
        assertTrue(reads.get() >= 1)
        // Whole: never read again.
        val n = reads.get()
        runBlocking { PassedArmsHost.prefetchNow(day, listOf("NIFTY")) }
        assertEquals(n, reads.get())
        // Daily candles alone (no session) are read again too.
        history(emptyList(), daily = listOf(Bar(prev.atStartOfDay(), 1.0, 2.0, 0.5, 1.5)))
        assertNotNull(PassedArmsHost.history("NIFTY", day))
        assertTrue(PassedArmsHost.historyWanted("NIFTY", day))
    }

    @Test fun aReadAgainNeverPutsAWorseHistoryOverWhatIsKept() {
        val before = PassedArmsHost.previousSession(prev)!!
        val daily = listOf(Bar(before.atStartOfDay(), 1.0, 2.0, 0.5, 1.5))
        history(session(before), daily)
        // A read that brings nothing but daily candles keeps the stale sessions (they are better than none).
        PassedArmsHost.testHistory = { emptyList() }
        runBlocking { PassedArmsHost.prefetchNow(day, listOf("NIFTY")) }
        assertEquals(before, PassedArmsHost.history("NIFTY", day)!!.sessions.last().date)
        // A whole read whose daily candles failed keeps the daily candles in hand.
        PassedArmsHost.testHistory = { u -> if (u == "NIFTY") session(prev) else emptyList() }
        PassedArmsHost.testDaily = { emptyList() }
        runBlocking { PassedArmsHost.prefetchNow(day, listOf("NIFTY")) }
        val h = PassedArmsHost.history("NIFTY", day)!!
        assertEquals(prev, h.sessions.last().date)
        assertEquals(1, h.daily.size)
    }

    // ---- items 5 and 6: the locks ----------------------------------------------------------------------------------------------

    private fun listed() {
        kite.instruments += FakeKite.Ins(token, sym, "NIFTY", day.plusDays(7), 25_050.0, "CE", 65)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        File(context.filesDir, "kite_instruments.json").delete()
        runBlocking { Broker.instruments() }
        kite.requests.clear()
    }

    private fun sec(t: LocalTime): Long = day.atTime(t).atZone(IST).toEpochSecond()

    private fun fed(last: Double, tradedAt: LocalTime) =
        KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(token, last, bid = last - 0.05, ask = last + 0.05,
            exchangeTime = System.currentTimeMillis() / 1000, bidQty = 65, askQty = 65, lastTradeTime = sec(tradedAt))))

    /** R51's 09:20 break bought at about [px] with Boss's rule and its step lock on. */
    private fun bought(px: Double): PassedArmsHost.Pos {
        StepTrailExits.resetForTest(on = true, lock = true)
        market(LocalTime.of(9, 20)) { px }
        tick(LocalTime.of(9, 20))
        return real().single()
    }

    private fun lv(entry: Double, best: Double): Double =
        StepTrail.levelOf(entry, 65, best, StepTrail.TICK, StepTrail.Ladder(entry, 65, 65, StepTrail.Lock.L500, StepTrail.TICK))!!

    private fun whyAt(level: Double, e: Double): String? = StepTrail.whyAt(level, e, 65, ladder = StepTrail.Ladder(e, 65, 65, StepTrail.Lock.L500))

    private fun popupStop(p: PassedArmsHost.Pos): Double? = com.optionslab.ira.ArmExits.of(false, p.symbol).single().stop

    @Test fun anEarnedLockIsSavedAtOnceSoARestartThatLostTheMinutesStillSellsAtIt() {
        listed()
        val p = bought(200.0)
        val e = p.entry
        // 09:21 trades at e + 22 (+₹1,000 a lot): the step lock from 09:22. Nothing else changes in the book on this look.
        market(LocalTime.of(9, 23)) { t -> if (t.isBefore(LocalTime.of(9, 21))) 200.0 else e + 21.0 }
        manage(LocalTime.of(9, 23))
        val locked = lv(e, e + 22.0)
        assertEquals(locked, popupStop(real().single())!!, 1e-9)
        // A restart: the book read back from the phone, and the option's minutes gone (the feed lost them).
        assertTrue(PassedArmsHost.awaitWrites())
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        upstox.minutes.remove(ce)
        fed(locked - 2.0, LocalTime.of(9, 24))
        manage(LocalTime.of(9, 24))
        val sold = view().closed.single { it.shadow == null }
        assertEquals("sold at the lock it had earned", whyAt(locked, e), sold.why)
        assertTrue("${sold.exit} at or under $locked, above its own -15%", sold.exit!! <= locked + 1e-9 && sold.exit!! > e * 0.85)
    }

    @Test fun whileTheMinutesStopComingThePriceNowRaisesTheSameLock() {
        listed()
        val p = bought(200.0)
        val e = p.entry
        // The option's minutes stop at the entry minute; the stream shows it trading at e + 22 at 09:26.
        assertNull(StepTrail.stopLevel(e, 65))
        fed(e + 22.0, LocalTime.of(9, 26))
        manage(LocalTime.of(9, 26))
        val locked = lv(e, e + 22.0)
        assertEquals("the same level a minute's high would give", locked, popupStop(real().single())!!, 1e-9)
        // It never goes down, and the price back under it sells the trade at the step lock.
        fed(e + 5.0, LocalTime.of(9, 27))
        manage(LocalTime.of(9, 27))
        val sold = view().closed.single { it.shadow == null }
        assertEquals(whyAt(locked, e), sold.why)
    }

    @Test fun withTheMinutesCurrentTheWalkIsTheResearchsOwn() {
        listed()
        val p = bought(200.0)
        val e = p.entry
        // Minutes up to 09:25 at the fill; the stream's price now is e + 22 but the minute it is in has not closed: no lock yet (as before).
        market(LocalTime.of(9, 26)) { 200.0 }
        fed(e + 22.0, LocalTime.of(9, 26))
        manage(LocalTime.of(9, 26))
        assertEquals("its own -15%", e * 0.85, popupStop(real().single())!!, 1e-9)
    }
}
