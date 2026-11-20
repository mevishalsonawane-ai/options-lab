package com.optionslab.app.data

import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Cleared
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Outcome
import com.optionslab.app.testing.FakeKite.Reply
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.ira.RateGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 20 Nov 2026 (audit B5, the order lifecycle): the app's real Zerodha code ([Broker]) against [FakeKite] for the order path's
 * remaining audit items - how long a write may hang, "not sent" told from "reply lost", the exits' lane in the request budget, the
 * lost-reply search's matching, a failed status read, an unknown order count, and the kill switch's cancel of working entries.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class BrokerOrderLifecycleTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500PE"
    private fun order(qty: Int = 75, type: String = "LIMIT", price: Double? = 120.0, trigger: Double? = null, side: Kite.Side = Kite.Side.BUY,
                      tag: String = "iratest", symbol: String = sym) =
        Kite.Order(symbol, side, qty, 75, "NRML", type, price, 0.05, "NFO", tag, triggerPrice = trigger)

    @Before fun up() { kite = FakeKite(); kite.login() }
    @After fun down() { kite.close(); assertEquals("a test reached the internet", emptyList<String>(), NetworkGuard.blocked.toList()) }

    // ---- item 1: a write's whole limit ----------------------------------------------------------------------------------------

    @Test fun anOrderZerodhaNeverAnswersEndsWithinTheWriteLimitAsALostReplyNeverAsNotSent() = runBlocking {
        kite.hang += "/orders/regular"
        val t0 = System.nanoTime()
        val e = try { Broker.placeOrder(order(), exit = true); null } catch (e: Exception) { e }
        val took = (System.nanoTime() - t0) / 1_000_000
        assertNotNull(e)
        assertTrue("ended by the write limits, not 80 s: $took ms", took < Broker.WRITE_BUDGET_MS + 2_000)
        assertTrue("the answer was waited for: $took ms", took >= Broker.WRITE_READ_MS - 1_000)
        assertFalse("the request left: its fate is unknown until the book is read", Broker.definite(e!!))
        assertFalse(e is Broker.NotSent)
        assertEquals("sent once", 1, kite.placed.size)
    }

    @Test fun theWriteLimitsAreBounded() {
        assertTrue(Broker.WRITE_CONNECT_MS + Broker.WRITE_READ_MS <= Broker.WRITE_BUDGET_MS)
        assertTrue(Broker.WRITE_BUDGET_MS <= 15_000L)
        // A read keeps its own budget; a write has none of a read's.
        assertNull(Broker.readBudgetMs("POST", "/orders/regular", readOnly = false))
        assertEquals(Broker.READ_BUDGET_MS, Broker.readBudgetMs("GET", "/orders/1", readOnly = false))
    }

    // ---- item 2: not sent vs reply lost -----------------------------------------------------------------------------------------

    @Test fun aConnectionRefusedBeforeTheOrderLeftIsNotSentAndDefinite() = runBlocking {
        val was = Broker.testEndpoint!!
        val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }   // closed again: nothing listens
        Broker.testEndpoint = Broker.TestEndpoint("https://127.0.0.1:$port", was.ssl)
        try {
            val e = try { Broker.placeOrder(order(), exit = true); null } catch (e: Exception) { e }
            assertTrue("a refused connection: nothing reached Zerodha ($e)", e is Broker.NotSent)
            assertTrue(Broker.definite(e!!))
            val c = try { Broker.cancel("1"); null } catch (x: Exception) { x }
            assertTrue("a cancel that could not connect is not sent either ($c)", c is Broker.NotSent)
            // (Review: a GTT that never left is not a refusal - nothing rests, and its protection simply tries again.)
            val g = Kite.Gtt("single", "NFO", sym, 200.0, listOf(160.0), listOf(order(type = "LIMIT", price = 158.0, side = Kite.Side.SELL)))
            assertTrue(Broker.placeGttSure(g, emptySet()) is Broker.GttSent.Unknown)
        } finally {
            Broker.testEndpoint = was
        }
        assertTrue("nothing reached the fake Zerodha", kite.requests.isEmpty())
    }

    @Test fun aRequestDroppedAfterItLeftStaysALostReply() = runBlocking {
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        val e = try { Broker.placeOrder(order(), exit = true); null } catch (e: Exception) { e }
        assertNotNull(e)
        assertFalse("after the request left, any doubt is a lost reply", e is Broker.NotSent)
        assertFalse(Broker.definite(e!!))
        assertEquals(kite.orders.keys.single(), Broker.findRecentRetrying(order(), emptyList(), tries = 1, waitMs = 10))
    }

    // ---- item 3: the exits' lane ------------------------------------------------------------------------------------------------

    @Test fun exitsAndStopChangesTakeTheTopLaneAndEntriesGoBelowTheStopChecks() {
        assertEquals(RateGate.Priority.EXIT, Broker.writePriority(true, kotlin.coroutines.EmptyCoroutineContext))
        assertEquals(RateGate.Priority.EXIT, Broker.writePriority(true, Broker.Lane(RateGate.Priority.ANALYSIS)))
        assertEquals(RateGate.Priority.ENTRY, Broker.entryWritePriorityForTest())
        assertEquals(RateGate.Priority.ENTRY, Broker.writePriority(false, kotlin.coroutines.EmptyCoroutineContext))
        assertEquals(RateGate.Priority.SAFETY, Broker.writePriority(false, Broker.Lane(RateGate.Priority.SAFETY)))
        assertTrue("an exit is ahead of every other lane", RateGate.Priority.values().all { it == RateGate.Priority.EXIT ||
            it.reserve > RateGate.Priority.EXIT.reserve })
    }

    // ---- item 4: the lost-reply search ------------------------------------------------------------------------------------------

    @Test fun anIdenticalOrderInTheBookBeforeThisOneIsNeverTakenForItsLostReply() = runBlocking {
        val old = kite.restingInKite(sym, "BUY", 75, 120.0, tag = "iratest", product = "NRML")
        Broker.orders()                                   // the book as read before the send: the old order is in it
        kite.drop += "/orders/regular"                    // the POST is read and dropped: nothing placed
        val o = order()
        val e = try { Broker.placeOrder(o, exit = true); null } catch (e: Exception) { e }
        assertFalse(Broker.definite(e!!))
        kite.drop.clear()
        assertNull("the order of before is not this one's ($old)", Broker.findRecentRetrying(o, emptyList(), tries = 1, waitMs = 10))
    }

    @Test fun anIdenticalOrderFromBeforeTheSendIsNeverTakenByItsTimeEither() = runBlocking {
        kite.dated = true
        Broker.orders()                                   // the phone's clock against Zerodha's measured (the read's Date header)
        kite.orders["777"] = FakeKite.Order("777", sym, "NFO", "BUY", 75, "NRML", "LIMIT", 120.0, 0.0, "iratest", "OPEN",
            at = Market.now().toLocalDateTime().minusSeconds(40))
        kite.drop += "/orders/regular"
        val o = order()
        try { Broker.placeOrder(o, exit = true); fail() } catch (_: Exception) {}
        kite.drop.clear()
        assertNull("placed 40 s before the send: never this one's", Broker.findRecentRetrying(o, emptyList(), tries = 1, waitMs = 10))
    }

    /**
     * 20 Nov 2026 (coordinator, review): the phone's clock stepped between the send and the search (the offset to Zerodha's clock moved
     * by more than 2 s): the tight window is not trusted and the old three minutes are searched, so the order's own lost reply - stamped
     * by Zerodha's clock 30 s before the send as the phone saw it - is still found, never sent a second time.
     */
    @Test fun aClockSteppedAfterTheSendFallsBackToTheWideWindow() = runBlocking {
        kite.dated = true
        Broker.orders()                                   // the offset measured before the send (about 0)
        kite.drop += "/orders/regular"
        val o = order()
        try { Broker.placeOrder(o, exit = true); fail() } catch (_: Exception) {}
        kite.drop.clear()
        kite.orders["888"] = FakeKite.Order("888", sym, "NFO", "BUY", 75, "NRML", "LIMIT", 120.0, 0.0, "iratest", "OPEN",
            at = Market.now().toLocalDateTime().minusSeconds(30))
        // Unstepped, 30 s before the send is outside the tight window: not taken.
        assertNull(Broker.findRecentRetrying(o, emptyList(), tries = 1, waitMs = 10))
        // Stepped by 30 s since the send (the search's own read measures it): the wide window, and it is found.
        kite.dateShiftMs = -30_000
        assertEquals("888", Broker.findRecentRetrying(o, emptyList(), tries = 1, waitMs = 10))
    }

    @Test fun anOrderAtAnotherPriceIsNeverTakenForALostReply() = runBlocking {
        kite.drop += "/orders/regular"
        val o = order()
        try { Broker.placeOrder(o, exit = true); fail() } catch (_: Exception) {}
        kite.drop.clear()
        kite.restingInKite(sym, "BUY", 75, 119.0, tag = "iratest", product = "NRML")
        assertNull(Broker.findRecentRetrying(o, emptyList(), tries = 1, waitMs = 10))
        val same = kite.restingInKite(sym, "BUY", 75, 120.0, tag = "iratest", product = "NRML")
        assertEquals("the same order, placed after the send, is found", same, Broker.findRecentRetrying(o, emptyList(), tries = 1, waitMs = 10))
    }

    // ---- item 5: a failed status read ------------------------------------------------------------------------------------------

    @Test fun oneFailedStatusReadIsReadAgainNotTakenAsTheEnd() = runBlocking {
        kite.nextPlace(FakeKite.Scenario(outcome = Outcome.FILL_AFTER_POLLS, polls = 2))
        val id = Broker.placeOrder(order(), exit = true)
        kite.down += "/orders/$id"
        kite.afterNext("/orders/$id") { kite.down.remove("/orders/$id") }
        val f = Broker.awaitOrder(id, 5_000)
        assertEquals("one failed read did not end the wait", "COMPLETE", f.status)
        assertEquals(75, f.filled)
    }

    @Test fun onlyAWholeWaitOfFailedReadsIsUnknownAndItWaitsTheWholeWait() = runBlocking {
        kite.nextPlace(outcome = Outcome.OPEN)
        val id = Broker.placeOrder(order(), exit = true)
        kite.down += "/orders/$id"
        val t0 = System.nanoTime()
        val e = try { Broker.awaitOrder(id, 1_500); null } catch (e: Exception) { e }
        val took = (System.nanoTime() - t0) / 1_000_000
        assertNotNull("no read answered: unknown", e)
        assertTrue("read again until the wait was over: $took ms", took >= 1_400)
        assertTrue("and no longer: $took ms", took < 1_500 + Broker.READ_BUDGET_MS)
        assertTrue("still working at Zerodha: nothing cancelled it", kite.order(id).working)
    }

    // ---- item 6: an unknown order count -----------------------------------------------------------------------------------------

    /**
     * Coordinator, 20 Nov 2026 (CLAUDE.md: a new live skip starts in shadow): with the order count check off (its default) an entry with
     * the count unknown goes as before, logged and counted once; on, it is refused. An exit never looks at it either way.
     */
    @Test fun anOrderCountThatCannotBeReadIsUnknownAndOnlyLoggedInShadow() = runBlocking {
        kite.position(sym, 75, 100.0)
        kite.down += "/orders"
        val a = Broker.accountNow()!!.copy(minuteOfDay = 10 * 60)     // (not the real clock: before the entry cut-off)
        assertFalse("unknown, never 0", a.ordersKnown)
        val buy = Guard.liveOrder(order(symbol = "NIFTY26OCT24600CE"))
        assertEquals("shadow: the entry goes as before", emptyList<String>(), Guard.check(buy, a))
        assertEquals(emptyList<String>(), Guard.check(buy, a))
        assertEquals("logged and counted once", 1 to 0, OrderCountCheck.today())
        assertTrue(OrderCountCheck.line(), OrderCountCheck.line().contains("1 would have been refused"))
        assertEquals("on takes no PIN", false, OrderCountCheck.set(true).contains("PIN"))
        val entry = Guard.check(buy, a)
        assertTrue("$entry", entry.any { it.contains("order count could not be read") })
        assertEquals(1 to 1, OrderCountCheck.today())
        assertEquals(emptyList<String>(), Guard.check(Guard.liveOrder(order(side = Kite.Side.SELL)), a, exit = true))
        assertEquals("an exit is never refused for it", emptyList<String>(),
            com.optionslab.engine.risk.AccountGuard.refusals(Guard.liveOrder(order(side = Kite.Side.SELL)), a, AppSettings.load().guardLimits()))
        assertTrue("off takes the PIN", OrderCountCheck.set(false).contains("PIN"))
        assertTrue(OrderCountCheck.isOn())
        OrderCountCheck.set(false, pinConfirmed = true)
        assertEquals(emptyList<String>(), Guard.check(Guard.liveOrder(order(symbol = "NIFTY26OCT24700CE")), a))
        kite.down.clear()
        assertTrue(Broker.accountNow()!!.ordersKnown)
    }

    /** An entry another limit refuses anyway is not counted (the count decided nothing); a count that was read is judged as ever. */
    @Test fun theOrderCountCheckCountsOnlyWhatItAloneWouldRefuse() {
        val book = Broker.Positions(emptyList(), emptyList())
        val unknown = Guard.liveAccount(book, Broker.Funds(available = 400_000.0, used = 0.0, net = 400_000.0, openingBalance = 400_000.0), null)
            .copy(minuteOfDay = 10 * 60)
        val l = AppSettings.load().guardLimits()
        val huge = Guard.liveOrder(order(qty = 75 * 1_000, price = 120.0))
        assertTrue(Guard.countShadow(huge, unknown, l).isNotEmpty())
        assertEquals("refused anyway: not counted", 0 to 0, OrderCountCheck.today())
        Guard.countShadow(Guard.liveOrder(order()), unknown.copy(ordersKnown = true), l)
        assertEquals(0 to 0, OrderCountCheck.today())
    }

    // ---- item 9: the kill switch -----------------------------------------------------------------------------------------------

    /** The bots' and arms' orders (told apart by tag, as the door does); a strategy's here. */
    private val bot = "iraalgostrat"

    @Test fun theKillSwitchCancelsTheBotsWorkingEntriesAndNeverAnExitAStopAHedgeOrBossesOwn() = runBlocking {
        kite.position("BANKNIFTY26OCT52000PE", -75, 50.0)          // a short BANKNIFTY put: a BUY on BANKNIFTY may be its hedge
        kite.nextPlace(outcome = Outcome.OPEN)
        val entry = order(price = 100.0, tag = bot)
        val entryId = Broker.placeOrder(entry, verdict = Cleared.live(entry))
        // (Review: a working BUY the app sent as an entry on the short's underlying - a strategy's protective leg - is never cancelled.)
        kite.nextPlace(outcome = Outcome.OPEN)
        val hedge = order(price = 20.0, symbol = "BANKNIFTY26OCT51000PE", tag = bot)
        val hedgeId = Broker.placeOrder(hedge, verdict = Cleared.live(hedge))
        // (Coordinator: Boss's own entry sent from the app - the app's tag, not a bot's - is never cancelled.)
        kite.nextPlace(outcome = Outcome.OPEN)
        val mine = order(price = 90.0, symbol = "NIFTY26OCT24800CE", tag = "iraalgo")
        val mineId = Broker.placeOrder(mine, verdict = Cleared.live(mine))
        val stopId = Broker.placeOrder(order(type = "SL", price = 79.0, trigger = 80.0, side = Kite.Side.SELL, tag = bot), exit = true)
        kite.nextPlace(outcome = Outcome.OPEN)
        val exitId = Broker.placeOrder(order(price = 200.0, side = Kite.Side.SELL, tag = bot), exit = true)
        // Orders this phone does not know (sent before a restart): a bot's BUY opening a long is an entry; a bot's sale, a bot's BUY that
        // covers a short, Boss's own order in Kite and his own from the app are not.
        val strayEntry = kite.restingInKite("NIFTY26OCT24600CE", "BUY", 75, 50.0, tag = "iraorb", product = "NRML")
        val straySale = kite.restingInKite("NIFTY26OCT24600CE", "SELL", 75, 90.0, tag = "iraorb", product = "NRML")
        val cover = kite.restingInKite("BANKNIFTY26OCT52000PE", "BUY", 75, 40.0, tag = "iraorb", product = "NRML")
        val boss = kite.restingInKite("NIFTY26OCT24700CE", "BUY", 75, 30.0, product = "NRML")
        val bossApp = kite.restingInKite("NIFTY26OCT24900CE", "BUY", 75, 30.0, tag = "iraalgo", product = "NRML")
        // Logged only (shadow): what it would cancel, and nothing cancelled.
        assertEquals(setOf(entryId, strayEntry), Broker.cancelWorkingEntries("test", act = false).toSet())
        assertTrue("nothing cancelled in shadow", kite.writes.none { it.method == "DELETE" })
        val gone = Broker.cancelWorkingEntries("test")
        assertEquals(setOf(entryId, strayEntry), gone.toSet())
        assertEquals("CANCELLED", kite.order(entryId).status)
        assertEquals("CANCELLED", kite.order(strayEntry).status)
        for (id in listOf(hedgeId, mineId, stopId, exitId, straySale, cover, boss, bossApp)) assertTrue("$id still working", kite.order(id).working)
        assertTrue("only cancels were sent", kite.writes.filter { it.method == "DELETE" }.size == 2)
    }

    @Test fun withNoPositionsReadNoBuyIsCancelled() = runBlocking {
        kite.nextPlace(outcome = Outcome.OPEN)
        val entry = order(price = 100.0, tag = bot)
        val id = Broker.placeOrder(entry, verdict = Cleared.live(entry))
        kite.down += "/portfolio/positions"
        assertTrue("a BUY may be a hedge: never cancelled blind", Broker.cancelWorkingEntries("test").isEmpty())
        assertTrue(kite.order(id).working)
        kite.down.clear()
        assertEquals(listOf(id), Broker.cancelWorkingEntries("test"))
    }

    @Test fun aLostEntryReplyFoundInTheBookIsKeptAsAnEntryForTheKillSwitch() = runBlocking {
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT, outcome = Outcome.OPEN)
        val o = order(price = 100.0, tag = bot)
        try { Broker.placeOrder(o, verdict = Cleared.live(o)); fail() } catch (_: Exception) {}
        val found = Broker.findRecentRetrying(o, emptyList(), tries = 1, waitMs = 10)
        assertNotNull(found)
        kite.restingInKite(sym, "SELL", 75, 300.0, tag = bot, product = "NRML")   // a sale it does not know: never cancelled
        assertEquals(listOf(found), Broker.cancelWorkingEntries("test"))
    }

    @Test fun aRelayThatCannotConnectInTimeIsNotSentAndOneConnectIsShared() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val slow = { calls.incrementAndGet(); Thread.sleep(800); null as java.net.Proxy? }
        val t0 = System.nanoTime()
        val e = try { Broker.relayWithin(100, on = { true }, proxy = slow); null } catch (x: Exception) { x }
        assertTrue("not connected in time: nothing left ($e)", e is Broker.NotSent)
        assertTrue("given up on time", (System.nanoTime() - t0) / 1_000_000 < 700)
        // The next write joins the connect still running, never a second one beside it; it ends with the relay's answer.
        assertNull(Broker.relayWithin(5_000, on = { true }, proxy = slow))
        assertEquals(1, calls.get())
        // A relay that refuses: not sent. The relay off: no proxy, nothing started.
        val r = try { Broker.relayWithin(1_000, on = { true }, proxy = { throw java.io.IOException("Relay: cannot reach") }); null } catch (x: Exception) { x }
        assertTrue("$r", r is Broker.NotSent && Broker.definite(r))
        assertNull(Broker.relayWithin(1_000, on = { false }, proxy = { fail(); null }))
    }

    @Test fun anUnreadableBookCancelsNothing() = runBlocking {
        kite.nextPlace(outcome = Outcome.OPEN)
        val entry = order(price = 100.0, tag = bot)
        val id = Broker.placeOrder(entry, verdict = Cleared.live(entry))
        kite.down += "/orders"
        assertTrue(Broker.cancelWorkingEntries("test").isEmpty())
        assertTrue(kite.order(id).working)
    }

    /**
     * 20 Nov 2026 (deep review): a sweep still due after Boss's kill switch is cleared never cancels a bot's entry sent after it - each
     * sweep reads the switch first, and clearing it ends the sweeps at once.
     */
    @Test fun aClearedKillSwitchCancelsNothingMore() = runBlocking {
        kite.nextPlace(outcome = Outcome.OPEN)
        val first = order(price = 100.0, tag = bot)
        val id1 = Broker.placeOrder(first, verdict = Cleared.live(first))
        Broker.killSweepOn = true
        Broker.killSweepAgainMs = 1_500
        val s = AppSettings.load()
        AppSettings.saveChanged(s, s.copy(guardKill = true))
        Broker.killEntriesSoon("your kill switch", act = true)
        Background.await("the first entry cancelled", 10_000) { kite.order(id1).status == "CANCELLED" }
        // Cleared behind the sweep's back (the vault alone): the next sweep reads it and cancels nothing.
        com.optionslab.app.security.SecurePrefs.put(AppSettings.KILL_KEY, false)
        com.optionslab.app.security.SecurePrefs.flush()
        kite.nextPlace(outcome = Outcome.OPEN)
        val second = order(price = 101.0, tag = bot)
        val id2 = Broker.placeOrder(second, verdict = Cleared.live(second))
        Thread.sleep(2_500)
        assertTrue("sent after the clear: never cancelled", kite.order(id2).working)
        assertTrue(Broker.killSweepRunningForTest())
        // Cleared the usual way (Boss's switch, Jarvis): the sweeps end at once.
        AppSettings.saveChanged(AppSettings.load().copy(guardKill = true), AppSettings.load().copy(guardKill = false))
        assertFalse(Broker.killSweepRunningForTest())
        Broker.killSweepOn = false
    }

    /** 20 Nov 2026 (deep review): a write that never left the phone is said as such - never as Zerodha's refusal. */
    @Test fun aWriteThatNeverLeftIsSaidAsNotSentNeverAsRefused() {
        val w = Broker.notSentWords("exit of $sym")
        assertEquals("The exit of $sym was not sent (no connection to Zerodha); trying again.", w)
        assertFalse(w.contains("refused"))
    }

    /**
     * 20 Nov 2026 (deep review): a lost reply not found yet is UNKNOWN ([Broker.lookLost]): still looking within the window, found once the
     * book shows it, and after the window given up on with Boss told.
     */
    @Test fun aLostReplyIsLookedForUntilFoundOrGivenUpWithBossTold() = runBlocking {
        Broker.lostLookGapMsForTest = 0
        kite.drop += "/orders/regular"
        val o = order()
        try { Broker.placeOrder(o, exit = true); fail() } catch (_: Exception) {}
        kite.drop.clear()
        assertNull("nothing kept under another key", Broker.lookLost("other"))
        Broker.noteLost("k", o, emptyList(), "Test: its stop")
        assertTrue(Broker.lostPending("k"))
        assertEquals(Broker.LostLook.Searching, Broker.lookLost("k"))
        val id = kite.restingInKite(sym, "BUY", 75, 120.0, tag = "iratest", product = "NRML")
        assertEquals(Broker.LostLook.Found(id, 75), Broker.lookLost("k"))
        assertFalse(Broker.lostPending("k"))
        // Never found: given up on after the window, Boss told.
        val o2 = order(price = 130.0)
        Broker.noteLost("k2", o2, emptyList(), "Test: its entry")
        Broker.lostSearchMsForTest = 0
        assertEquals(Broker.LostLook.GaveUp, Broker.lookLost("k2"))
        assertTrue(com.optionslab.app.testing.AutomationSupport.alerts().toString(),
            com.optionslab.app.testing.AutomationSupport.alerts().any { "Test: its entry" in it && "not in its order book" in it })
        assertNull(Broker.lookLost("k2"))
    }

    /**
     * Coordinator, 20 Nov 2026: the kill switch turned on by anything but Boss's own switch (the guard tripping, Jarvis, voice,
     * Autopilot - all through [AppSettings.killSoon] or [AppSettings.saveChanged]) only LOGS the bots' entries it would cancel; Boss's own
     * switch cancels them ([Broker.killEntriesSoon] with act), and a logging request never replaces his.
     */
    @Test fun onlyBossesOwnSwitchCancelsTheGuardAndJarvisOnlyLog() = runBlocking {
        kite.nextPlace(outcome = Outcome.OPEN)
        val entry = order(price = 100.0, tag = bot)
        val id = Broker.placeOrder(entry, verdict = Cleared.live(entry))
        val stopId = Broker.placeOrder(order(type = "SL", price = 79.0, trigger = 80.0, side = Kite.Side.SELL, tag = bot), exit = true)
        Broker.killSweepOn = true
        Broker.killSweepAgainMs = 50
        // Jarvis / voice / Autopilot (a settings change) and the guard's own trip: logged only.
        val s = AppSettings.load()
        AppSettings.saveChanged(s, s.copy(guardKill = true))
        assertEquals(listOf("the kill switch" to false), Broker.killRequestsForTest.toList())
        AppSettings.saveChanged(AppSettings.load(), AppSettings.load().copy(guardKill = false))
        AppSettings.killSoon()
        assertEquals(listOf("the kill switch" to false, "the guard" to false), Broker.killRequestsForTest.toList())
        AppSettings.awaitKillWriter()
        Thread.sleep(500)
        assertTrue("never cancelled by them", kite.order(id).working)
        assertTrue(kite.writes.none { it.method == "DELETE" })
        // Boss's own switch: cancelled (never the stop); a later logging request does not undo his.
        Broker.killEntriesSoon("your kill switch", act = true)
        Broker.killEntriesSoon("the guard", act = false)
        Background.await("the entry cancelled", 10_000) { kite.order(id).status == "CANCELLED" }
        assertTrue("the stop is left resting", kite.order(stopId).working)
        Broker.killSweepOn = false
    }
}
