package com.optionslab.app.work

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.ExitCoordinator
import com.optionslab.app.data.ExpirySquareOff
import com.optionslab.app.data.FlatBySquareOff
import com.optionslab.app.data.LossBreaker
import com.optionslab.app.data.Market
import com.optionslab.app.data.SquareOffExit
import com.optionslab.app.data.Strategies
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Outcome
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/**
 * 19 Nov 2026 (second review of the clock lane, "merge after fixes"): the clock lane ([ClockLane]) against the fake Kite, with the
 * REAL expiry square-off, flat-by and daily loss limit (no stand-in work): a square-off never sells twice while the positions trail
 * its fill, nor runs again back to back; the loss limit runs first in a round and its sales take the square-offs' turn; a slow
 * square-off never starves the flat-by, and a starved step is said late.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClockLaneSquareOffTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private lateinit var today: LocalDate
    /** Expires today (NRML): the expiry square-off's. */
    private val ce = "NIFTY26SEP24500CE"
    /** A week out, MIS, an arm's: the flat-by's at 15:25. */
    private val mis = "NIFTY26NOV24600CE"
    /** A week out, NRML, Boss's own: what makes the day's loss. */
    private val other = "NIFTY26NOV24000PE"

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        today = Market.today()
        kite.instruments += FakeKite.Ins(1_101, ce, "NIFTY", today, 24_500.0, "CE", 75)
        kite.instruments += FakeKite.Ins(1_102, mis, "NIFTY", today.plusDays(7), 24_600.0, "CE", 75)
        kite.instruments += FakeKite.Ins(1_103, other, "NIFTY", today.plusDays(7), 24_000.0, "PE", 75)
        AutomationSupport.niftyOptions(kite)           // a strategy's leg (a week and more out)
        listOf(ce, mis, other).forEach { kite.quote("NFO:$it", 20.0, 19.95, 20.05) }
        kite.quote("NFO:${AutomationSupport.NIFTY_CE}", 100.0, 99.95, 100.05)
        runBlocking { Broker.instruments() }
        kite.requests.clear()
        AutomationSupport.resetExpiryTold()
        Background.clearAlerts()
        Alerts.forgetPosted()
        Background.grantNotifications(context)
        ExitCoordinator.resetForTest()                 // the lag guard's switch in shadow, as it ships
        ClockLane.resetForTest()
        WatchWorkers.resetForTest()
        FlatBySquareOff.resetForTest()
        FlatBySquareOff.liveBooksReady = { true }
        FlatBySquareOff.liveOwner = { if (it == mis) "ORB" else null }
        SquareOffExit.resetForTest()                   // the contract's gap after an exit on, as it ships
        at(15, 6)
    }

    @After fun down() {
        FlatBySquareOff.liveOwner = { sym -> com.optionslab.app.data.ArmLive.ownerOf(sym) }
        FlatBySquareOff.liveBooksReady = { com.optionslab.app.data.ArmLive.view.value.let { it.loaded && !it.unreadable } }
        ClockLane.resetForTest()
        WatchWorkers.resetForTest()
        ExitCoordinator.resetForTest()
        SquareOffExit.resetForTest(gap = 0L)
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(h: Int, m: Int) {
        val t = today.atTime(h, m).atZone(IST)
        ExpirySquareOff.testNow = t
        FlatBySquareOff.testNow = t
    }
    private fun epoch(h: Int, m: Int, s: Int = 0) = today.atTime(h, m, s).atZone(IST).toInstant().toEpochMilli()
    private fun sent() = kite.placed.map { Triple(it.form["tradingsymbol"], it.form["transaction_type"], it.form["quantity"]) }
    private fun gets(path: String) = kite.requests.filter { it.method == "GET" && it.path == path }
    private fun lateAlerts() = Alerts.posted.filter { it.title == "Safety check late" }

    /**
     * Item 1 (a): the lane's run sold the expiring option with a MARKET order; the pass's run a moment later reads the orders (the
     * sale COMPLETE) and then positions that still trail the fill. With the lag guard's switch in shadow (as it ships), that second
     * run sold the trailing 75 again and left the account short on expiry day. The square-offs' guard now always acts.
     */
    @Test fun aSecondRunWhileThePositionsTrailTheSaleNeverSellsAgain() {
        SquareOffExit.resetForTest(gap = 0L)           // the guard alone here (the contract's gap is the next test's)
        kite.position(ce, 75, 20.0)
        kite.positionsTrailReads = 50                  // the positions trail the exit's fill for many reads
        repeat(2) { runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) } }
        assertEquals("one sale", listOf(Triple(ce, "SELL", "75")), sent())
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /**
     * Item 1 (b): the lane's run placed the expiry exit. The pass right after skips its own run (one completed just now stands for
     * it). The next lane round reads the books again, and Zerodha's orders read misses the exit placed a moment ago (its book a moment
     * behind): the position still shows and no exit is working, yet nothing more is sent inside the contract's gap.
     */
    @Test fun aSquareOffIsNeverRunTwiceBackToBackNorSentAgainInsideItsGap() = runBlocking {
        var now = epoch(15, 6, 1)
        ClockLane.clock = { now }
        SquareOffExit.clock = { now }
        kite.position(ce, 75, 20.0)
        kite.nextPlace(outcome = Outcome.OPEN)         // a thin book: the exit rests
        val run = WatchWorkers.begin(this)
        try {
            val s = AppSettings.load()
            val sup = WatchWorkers.supervisor!!
            suspend fun laneRound() {
                withContext(Dispatchers.IO) { sup.kick(ClockLane.GROUP, listOf(ClockLane.step(context, ClockLane.SQUARE_OFFS, s))) }
                Background.await("the round's square-offs", 20_000) { !sup.busy(ClockLane.SQUARE_OFFS) }
            }
            laneRound()
            assertEquals(listOf(Triple(ce, "SELL", "75")), sent())
            val reads = gets("/orders").size
            // The pass a second later: the lane's run completed just now stands for its own.
            withContext(Dispatchers.IO) { Tasks.clockStep(context, s, ClockLane.SQUARE_OFFS, inOrder = false) }
            assertEquals("the pass ran no second square-off", reads, gets("/orders").size)
            // The next lane round, 5 s on, with an orders read that misses the resting exit.
            kite.orders.clear()
            now += 5_000
            laneRound()
            assertTrue("it read the books again", gets("/orders").size > reads)
            assertEquals("and sent nothing more inside the contract's gap", 1, kite.placed.size)
        } finally {
            WatchWorkers.end(run)
        }
    }

    /** A hand-made position losing [loss] rupees today, past the default Rs 2,000 limit when [loss] is bigger. */
    private fun losing(loss: Double) {
        kite.position(other, 75, 100.0)
        kite.quote("NFO:$other", 100.0 - loss / 75)
    }

    /** A running LIVE strategy holding one NIFTY CE leg (bought at the start), as the loss limit's test makes one. */
    private fun runningStrategy(): Long = runBlocking {
        AutomationSupport.security(compromised = false)
        assertEquals(null, Strategies.save(AutomationSupport.strategy("Clock lane test", com.optionslab.engine.strategy.Position.B to com.optionslab.engine.strategy.OptionType.CE)))
        val id = Strategies.all().single().def.id
        Strategies.setLiveEnabled(id, true)
        Strategies.start(id, com.optionslab.engine.strategy.RunMode.LIVE, "manual", confirmedByOwner = true, compromised = false)
        assertEquals("BUY", kite.placed.single().form["transaction_type"])
        // The weekly leg the resolver picked (the nearest expiry on the list).
        legSymbol = kite.placed.single().form["tradingsymbol"]!!
        id
    }
    private var legSymbol = ""

    /** Another running LIVE strategy named [name] (its BUY placed). */
    private fun anotherStrategy(name: String, right: com.optionslab.engine.strategy.OptionType = com.optionslab.engine.strategy.OptionType.CE,
                                strike: Double = 24_500.0) = runBlocking {
        val def = AutomationSupport.strategy(name, com.optionslab.engine.strategy.Position.B to right)
        assertEquals(null, Strategies.save(def.copy(legs = def.legs.map { it.copy(strike = strike) })))
        val id = Strategies.all().single { it.def.name == name }.def.id
        Strategies.setLiveEnabled(id, true)
        Strategies.start(id, com.optionslab.engine.strategy.RunMode.LIVE, "manual", confirmedByOwner = true, compromised = false)
    }
    private fun strategySells() = kite.placed.count { it.form["tradingsymbol"] == legSymbol && it.form["transaction_type"] == "SELL" }

    /**
     * Item 2 (second review): the real daily loss limit trips while a square-off holds the turn; the running strategy's exit send waits
     * for the turn, never beside the square-off's sales on the same legs; once the turn is free it goes, once.
     */
    @Test fun aRunningStrategysExitSendWaitsForTheSquareOffsTurn() = runBlocking {
        AutomationSupport.liveSettings()
        runningStrategy()
        losing(3_000.0)
        val release = CompletableDeferred<Unit>()
        val hold = launch(Dispatchers.IO) { ClockLane.holdTurnForTest(ClockLane.SQUARE_OFFS, release) }
        try {
            Background.await("a square-off holds the turn", 5_000) { ClockLane.squareOffRunning() }
            val check = launch(Dispatchers.IO) { LossBreaker.check(context) }
            Background.await("the limit tripped", 10_000) { LossBreaker.trippedToday() }
            Thread.sleep(1_500)
            assertEquals("its exit waits for the square-off", 0, strategySells())
            release.complete(Unit)
            check.join()
            assertEquals("then it goes, once", 1, strategySells())
        } finally {
            release.complete(Unit)
            hold.join()
        }
    }

    /**
     * Final check (M1 c): the expiry square-off sold a strategy's expiring leg and the positions still trail that sale; the strategy's
     * own exit (its stop for the day) reads the orders and what is held with the lag guard always on, so it never sells it again.
     */
    @Test fun aStrategysExitNeverSellsAgainWhileThePositionsTrailASquareOffsSale() = runBlocking {
        AutomationSupport.liveSettings()
        val id = runningStrategy()
        assertEquals("the leg expires today", ce, legSymbol)
        kite.positionsTrailReads = 50
        withContext(Dispatchers.IO) { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
        assertEquals("the square-off sold it", 1, kite.placed.count { it.form["transaction_type"] == "SELL" && it.form["tag"] == "iraalgoexpiry" })
        withContext(Dispatchers.IO) { Strategies.stop(id, "test", compromised = false) }
        assertEquals("one sale, never two", 1, kite.placed.count { it.form["tradingsymbol"] == ce && it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" })
        assertEquals("flat, never short", 0, kite.positions.getValue("NFO:$ce:NRML").first)
    }

    /**
     * Final review, items 1 and 6: the loss limit's stop of three running strategies waits for a turn held by a hung square-off once
     * in all (not once each: 30, 60, 90 s), then their exits go - and the diary says they went without the turn.
     */
    @Test fun threeStrategiesShareOneWaitForTheTurn() = runBlocking {
        AutomationSupport.liveSettings()
        runningStrategy()
        anotherStrategy("Clock lane test 2", com.optionslab.engine.strategy.OptionType.PE)
        anotherStrategy("Clock lane test 3", strike = 24_600.0)
        assertEquals("three running", 3, kite.placed.count { it.form["transaction_type"] == "BUY" })
        ClockLane.salesTurnWaitMs = 4_000L
        val release = CompletableDeferred<Unit>()
        val hold = launch(Dispatchers.IO) { ClockLane.holdTurnForTest(ClockLane.SQUARE_OFFS, release) }
        try {
            Background.await("a square-off holds the turn", 5_000) { ClockLane.squareOffRunning() }
            val t0 = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                Strategies.stopForToday(stopRunning = true, compromised = false, why = com.optionslab.ira.DayStop.Why.LOSS, salesInTurn = true)
            }
            val ms = System.currentTimeMillis() - t0
            // One 4 s wait in all, then the exits: within 1.5 times the wait (before: one each, 12 s of waits and more).
            assertTrue("one wait for all three: $ms ms", ms < 6_000L)
            // Each leg bought is sold, contract by contract (the account guard may refuse an entry: counted from the BUYs).
            val orders = kite.placed.map { "${it.form["transaction_type"]} ${it.form["quantity"]} ${it.form["tradingsymbol"]}" }
            val bought = kite.placed.filter { it.form["transaction_type"] == "BUY" }.groupingBy { it.form["tradingsymbol"] }.eachCount()
            val sold = kite.placed.filter { it.form["transaction_type"] == "SELL" }.groupingBy { it.form["tradingsymbol"] }.eachCount()
            assertEquals("three bought: $orders", 3, bought.values.sum())
            assertEquals("every leg bought is sold: $orders", bought, sold)
            assertTrue(com.optionslab.app.data.Diag.lines().takeLast(40).joinToString("\n"),
                com.optionslab.app.data.Diag.lines().any { it.contains("exits went without it") })
        } finally {
            release.complete(Unit)
            hold.join()
        }
    }

    /**
     * Final review: the shared wait counts only waiting - once the square-off lets the turn go, every strategy's exits go in the turn
     * (each had it), none without it.
     */
    @Test fun aTurnFreedPartwayLetsEveryStrategySellInIt() = runBlocking {
        AutomationSupport.liveSettings()
        runningStrategy()
        anotherStrategy("Clock lane test 2", com.optionslab.engine.strategy.OptionType.PE)
        ClockLane.salesTurnWaitMs = 3_000L
        val release = CompletableDeferred<Unit>()
        val hold = launch(Dispatchers.IO) { ClockLane.holdTurnForTest(ClockLane.SQUARE_OFFS, release) }
        try {
            Background.await("a square-off holds the turn", 5_000) { ClockLane.squareOffRunning() }
            val before = com.optionslab.app.data.Diag.lines().size
            val stop = launch(Dispatchers.IO) {
                Strategies.stopForToday(stopRunning = true, compromised = false, why = com.optionslab.ira.DayStop.Why.LOSS, salesInTurn = true)
            }
            Thread.sleep(500)
            release.complete(Unit)
            stop.join()
            val said = com.optionslab.app.data.Diag.lines().drop(before)
            assertTrue(said.joinToString("\n"), said.none { it.contains("went without it") || it.contains("the turn not had") })
            val bought = kite.placed.filter { it.form["transaction_type"] == "BUY" }.groupingBy { it.form["tradingsymbol"] }.eachCount()
            val sold = kite.placed.filter { it.form["transaction_type"] == "SELL" }.groupingBy { it.form["tradingsymbol"] }.eachCount()
            assertEquals("every leg bought is sold", bought, sold)
        } finally {
            release.complete(Unit)
            hold.join()
        }
    }

    /**
     * Item 1 (c), third review: tripped with no strategy running there is nothing to sell, so the turn is never waited for - the day's
     * stop is written at once even while a square-off holds the turn (before, the whole stop waited up to 30 s for it).
     */
    @Test fun aTrippedLimitWithNothingRunningNeverWaitsForTheTurn() = runBlocking {
        AutomationSupport.liveSettings()
        losing(3_000.0)
        val release = CompletableDeferred<Unit>()
        val hold = launch(Dispatchers.IO) { ClockLane.holdTurnForTest(ClockLane.SQUARE_OFFS, release) }
        try {
            Background.await("a square-off holds the turn", 5_000) { ClockLane.squareOffRunning() }
            val t0 = System.currentTimeMillis()
            withContext(Dispatchers.IO) { LossBreaker.check(context) }
            val ms = System.currentTimeMillis() - t0
            assertTrue("the check took $ms ms", ms < 8_000L)
            assertTrue(LossBreaker.trippedToday())
            assertTrue("stopped for the day at once", Strategies.stoppedToday())
        } finally {
            release.complete(Unit)
            hold.join()
        }
    }

    /**
     * Item 1 (b), third review: the stop's sales take the turn only once the strategies' lock is held - never while waiting for it (a
     * pass held that lock across Zerodha calls for minutes, and the square-offs waited behind the queued sale all that time). The
     * stop tries the lock in the turn, lets both go, and waits for the lock alone.
     */
    @Test fun theTurnIsNeverQueuedWhileTheStopWaitsForTheStrategiesLock() = runBlocking {
        AutomationSupport.liveSettings()
        val id = runningStrategy()
        val release = CompletableDeferred<Unit>()
        val lock = launch(Dispatchers.IO) { Strategies.holdLockForTest(release) }
        try {
            Background.await("the strategies' lock held", 5_000) { Strategies.lockHeldForTest() }
            val stop = launch(Dispatchers.IO) { Strategies.stop(id, "daily loss limit", compromised = false, salesInTurn = true) }
            Thread.sleep(300)                          // past its one try in the turn: now it waits for the lock alone
            repeat(15) {
                assertFalse("the turn is free while the stop waits for the lock", ClockLane.salesQueuedForTest())
                Thread.sleep(100)
            }
            assertEquals(0, strategySells())
            release.complete(Unit)
            stop.join()
            assertEquals("sold once the lock was had", 1, strategySells())
        } finally {
            release.complete(Unit)
            lock.join()
        }
    }

    /**
     * Item 1 (b), third review (review round): nor is the strategies' lock held while the stop's sales wait for the turn - everything
     * else on that book (the strategies' own stops and targets, the protections' and the arms' order labels) goes on meanwhile.
     */
    @Test fun theStrategiesLockIsNeverHeldWhileTheStopWaitsForTheTurn() = runBlocking {
        AutomationSupport.liveSettings()
        runningStrategy()
        val release = CompletableDeferred<Unit>()
        val hold = launch(Dispatchers.IO) { ClockLane.holdTurnForTest(ClockLane.SQUARE_OFFS, release) }
        try {
            Background.await("a square-off holds the turn", 5_000) { ClockLane.squareOffRunning() }
            val stop = launch(Dispatchers.IO) {
                Strategies.stopForToday(stopRunning = true, compromised = false, why = com.optionslab.ira.DayStop.Why.LOSS, salesInTurn = true)
            }
            Background.await("its sales wait for the turn", 10_000) { ClockLane.salesQueuedForTest() }
            val t0 = System.currentTimeMillis()
            withContext(Dispatchers.IO) { Strategies.owners() }
            val ms = System.currentTimeMillis() - t0
            assertTrue("the book was free while the sales waited: $ms ms", ms < 2_000L)
            assertEquals(0, strategySells())
            release.complete(Unit)
            stop.join()
            assertEquals("sold once the turn was free", 1, strategySells())
        } finally {
            release.complete(Unit)
            hold.join()
        }
    }

    /**
     * Item 1 (b), third review: the square-offs never wait for the strategies' lock (their order labels go in once it is free), so a
     * stop holding it while it waits for the turn can never hold a square-off too. The real expiry square-off, the lock held throughout.
     */
    @Test fun aSquareOffNeverWaitsForTheStrategiesLock() = runBlocking {
        kite.position(ce, 75, 20.0)
        val release = CompletableDeferred<Unit>()
        val lock = launch(Dispatchers.IO) { Strategies.holdLockForTest(release) }
        try {
            Background.await("the strategies' lock held", 5_000) { Strategies.lockHeldForTest() }
            val t0 = System.currentTimeMillis()
            withContext(Dispatchers.IO) { ExpirySquareOff.maybeRun(context, AppSettings.load()) }
            val ms = System.currentTimeMillis() - t0
            assertTrue("the square-off took $ms ms with the lock held", ms < 8_000L)
            assertEquals(listOf(Triple(ce, "SELL", "75")), sent())
            release.complete(Unit)
            lock.join()
            val id = kite.orders.values.single { it.symbol == ce }.id
            assertEquals("its label went in once the lock was free", com.optionslab.app.data.Origins.EXPIRY, Strategies.owners()["kite:$id"])
        } finally {
            release.complete(Unit)
            lock.join()
        }
    }

    /**
     * Items 1 (a) and 2, third review: a loss-limit sale stuck in the turn (at Zerodha, for minutes) never keeps the real expiry
     * square-off or the flat-by back past the sales' wait: each waits that long (worked out at every look, the flat-by too), then goes
     * and the diary says so. (Before, each round gave up at once behind it.)
     */
    @Test fun aStuckLossSaleNeverKeepsTheSquareOffsBackPastItsWait() = runBlocking {
        ClockLane.salesTurnWaitMs = 3_000L
        at(15, 25)
        kite.position(ce, 75, 20.0)
        kite.position(mis, 75, 20.0, product = "MIS")
        val release = CompletableDeferred<Unit>()
        val sale = launch(Dispatchers.IO) { ClockLane.sales(ClockLane.LOSS) { release.await() } }
        try {
            Background.await("the sale holds the turn", 5_000) { ClockLane.salesQueuedForTest() }
            val t0 = System.currentTimeMillis()
            withContext(Dispatchers.IO) { ClockLane.runSquareOffs(context, AppSettings.load(), StopLane.Mode.NSE) }
            val ms = System.currentTimeMillis() - t0
            assertEquals("both went, in order", listOf(ce, mis), kite.placed.map { it.form["tradingsymbol"] })
            assertTrue("within the waits: $ms ms", ms < 20_000L)
            assertFalse("the sale is still stuck", sale.isCompleted)
        } finally {
            release.complete(Unit)
            sale.join()
        }
    }

    /**
     * Item 2: in a lane round the real loss limit runs first and the square-offs start only after it ends (its positions read is slow
     * here): the expiry square-off's first read of the orders comes after the loss limit's read of the positions has answered.
     */
    @Test fun inALaneRoundTheSquareOffsStartAfterTheLossLimitEnds() = runBlocking {
        kite.position(ce, 75, 20.0)
        kite.slowMs["/portfolio/positions"] = 1_500L
        val run = WatchWorkers.begin(this)
        val ctl = StopLane.Control().apply { mode = StopLane.Mode.NSE }
        try {
            val lane = launch(Dispatchers.IO) {
                ClockLane.runLane(context, stop = { ctl.ended }, mode = { ctl.mode }, held = { true }, supervisor = { WatchWorkers.supervisor })
            }
            Background.await("the expiry exit", 30_000) { kite.placed.isNotEmpty() }
            val positions = gets("/portfolio/positions").first().atNanos
            val orders = gets("/orders").first().atNanos
            assertTrue("the square-off read the orders ${(orders - positions) / 1_000_000} ms after the loss limit's positions read began",
                orders - positions >= 1_300_000_000L)
            ctl.ended = true; ClockLane.poke(); lane.join()
        } finally {
            WatchWorkers.end(run)
        }
    }

    /**
     * Item 3: at 15:25 on an expiry day the expiry square-off's order is slow at Zerodha. The flat-by is never starved behind it: it
     * runs right after it in the same step. While it waits, it is judged by its own due time and said late (before, the step holding
     * the turn hid it).
     */
    @Test fun aSlowExpirySquareOffNeverStarvesTheFlatByAndAStarvedStepIsSaidLate() = runBlocking {
        at(15, 25)
        var now = epoch(15, 25, 1)
        ClockLane.clock = { now }
        kite.position(ce, 75, 20.0)
        kite.position(mis, 75, 20.0, product = "MIS")
        kite.slowMs["/orders/regular"] = 6_000L
        val run = WatchWorkers.begin(this)
        val ctl = StopLane.Control().apply { mode = StopLane.Mode.NSE }
        try {
            val lane = launch(Dispatchers.IO) {
                ClockLane.runLane(context, stop = { ctl.ended }, mode = { ctl.mode }, held = { true }, supervisor = { WatchWorkers.supervisor })
            }
            Background.await("the expiry exit on its way", 15_000) { kite.placed.any { it.form["tradingsymbol"] == ce } }
            now += 120_000
            ClockLane.poke()
            Background.await("the flat-by said late", 5_000) { lateAlerts().any { it.text.contains("The ${ClockLane.FLAT_BY} check") } }
            Background.await("the flat-by's exit after it", 30_000) { kite.placed.any { it.form["tradingsymbol"] == mis } }
            assertEquals("the pass's old order: the expiry square-off, then the flat-by",
                listOf(ce, mis), kite.placed.map { it.form["tradingsymbol"] }.distinct())
            ctl.ended = true; ClockLane.poke(); lane.join()
        } finally {
            WatchWorkers.end(run)
        }
    }
}
