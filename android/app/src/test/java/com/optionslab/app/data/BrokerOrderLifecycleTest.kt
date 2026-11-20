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

    @Test fun anOrderCountThatCannotBeReadIsUnknownAndRefusesEntriesOnly() = runBlocking {
        kite.position(sym, 75, 100.0)
        kite.down += "/orders"
        val a = Broker.accountNow()!!
        assertFalse("unknown, never 0", a.ordersKnown)
        val entry = Guard.check(Guard.liveOrder(order(symbol = "NIFTY26OCT24600CE")), a)
        assertTrue("$entry", entry.any { it.contains("order count could not be read") })
        assertEquals(emptyList<String>(), Guard.check(Guard.liveOrder(order(side = Kite.Side.SELL)), a, exit = true))
        assertEquals("an exit is never refused for it", emptyList<String>(),
            com.optionslab.engine.risk.AccountGuard.refusals(Guard.liveOrder(order(side = Kite.Side.SELL)), a, AppSettings.load().guardLimits()))
        kite.down.clear()
        assertTrue(Broker.accountNow()!!.ordersKnown)
    }

    // ---- item 9: the kill switch -----------------------------------------------------------------------------------------------

    @Test fun theKillSwitchCancelsTheAppsWorkingEntriesAndNeverAnExitAStopAHedgeOrBossesOwn() = runBlocking {
        kite.position("BANKNIFTY26OCT52000PE", -75, 50.0)          // a short BANKNIFTY put: a BUY on BANKNIFTY may be its hedge
        kite.nextPlace(outcome = Outcome.OPEN)
        val entry = order(price = 100.0)
        val entryId = Broker.placeOrder(entry, verdict = Cleared.live(entry))
        // (Review: a working BUY the app sent as an entry on the short's underlying - a strategy's protective leg - is never cancelled.)
        kite.nextPlace(outcome = Outcome.OPEN)
        val hedge = order(price = 20.0, symbol = "BANKNIFTY26OCT51000PE")
        val hedgeId = Broker.placeOrder(hedge, verdict = Cleared.live(hedge))
        val stopId = Broker.placeOrder(order(type = "SL", price = 79.0, trigger = 80.0, side = Kite.Side.SELL), exit = true)
        kite.nextPlace(outcome = Outcome.OPEN)
        val exitId = Broker.placeOrder(order(price = 200.0, side = Kite.Side.SELL), exit = true)
        // Orders this phone does not know (sent before a restart): an app BUY opening a long is an entry; an app sale, an app BUY that
        // covers a short, and Boss's own order in Kite are not.
        val strayEntry = kite.restingInKite("NIFTY26OCT24600CE", "BUY", 75, 50.0, tag = "iraorb", product = "NRML")
        val straySale = kite.restingInKite("NIFTY26OCT24600CE", "SELL", 75, 90.0, tag = "iraorb", product = "NRML")
        val cover = kite.restingInKite("BANKNIFTY26OCT52000PE", "BUY", 75, 40.0, tag = "iraorb", product = "NRML")
        val boss = kite.restingInKite("NIFTY26OCT24700CE", "BUY", 75, 30.0, product = "NRML")
        val gone = Broker.cancelWorkingEntries("test")
        assertEquals(setOf(entryId, strayEntry), gone.toSet())
        assertEquals("CANCELLED", kite.order(entryId).status)
        assertEquals("CANCELLED", kite.order(strayEntry).status)
        for (id in listOf(hedgeId, stopId, exitId, straySale, cover, boss)) assertTrue("$id still working", kite.order(id).working)
        assertTrue("only cancels were sent", kite.writes.filter { it.method == "DELETE" }.size == 2)
    }

    @Test fun withNoPositionsReadNoBuyIsCancelled() = runBlocking {
        kite.nextPlace(outcome = Outcome.OPEN)
        val entry = order(price = 100.0)
        val id = Broker.placeOrder(entry, verdict = Cleared.live(entry))
        kite.down += "/portfolio/positions"
        assertTrue("a BUY may be a hedge: never cancelled blind", Broker.cancelWorkingEntries("test").isEmpty())
        assertTrue(kite.order(id).working)
        kite.down.clear()
        assertEquals(listOf(id), Broker.cancelWorkingEntries("test"))
    }

    @Test fun aLostEntryReplyFoundInTheBookIsKeptAsAnEntryForTheKillSwitch() = runBlocking {
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT, outcome = Outcome.OPEN)
        val o = order(price = 100.0)
        try { Broker.placeOrder(o, verdict = Cleared.live(o)); fail() } catch (_: Exception) {}
        val found = Broker.findRecentRetrying(o, emptyList(), tries = 1, waitMs = 10)
        assertNotNull(found)
        kite.restingInKite(sym, "SELL", 75, 300.0, tag = "iratest", product = "NRML")   // a sale it does not know: never cancelled
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
        val entry = order(price = 100.0)
        val id = Broker.placeOrder(entry, verdict = Cleared.live(entry))
        kite.down += "/orders"
        assertTrue(Broker.cancelWorkingEntries("test").isEmpty())
        assertTrue(kite.order(id).working)
    }

    @Test fun turningTheKillSwitchOnCancelsWorkingEntriesInTheBackground() = runBlocking {
        kite.nextPlace(outcome = Outcome.OPEN)
        val entry = order(price = 100.0)
        val id = Broker.placeOrder(entry, verdict = Cleared.live(entry))
        val stopId = Broker.placeOrder(order(type = "SL", price = 79.0, trigger = 80.0, side = Kite.Side.SELL), exit = true)
        Broker.killSweepOn = true
        Broker.killSweepAgainMs = 50
        val s = AppSettings.load()
        AppSettings.saveChanged(s, s.copy(guardKill = true))
        Background.await("the entry cancelled", 10_000) { kite.order(id).status == "CANCELLED" }
        assertTrue("the stop is left resting", kite.order(stopId).working)
        // The guard's own switch-on (a live drawdown) does the same.
        kite.nextPlace(outcome = Outcome.OPEN)
        AppSettings.saveChanged(AppSettings.load(), AppSettings.load().copy(guardKill = false))
        val again = order(price = 101.0)
        val id2 = Broker.placeOrder(again, verdict = Cleared.live(again))
        AppSettings.killSoon()
        Background.await("the second entry cancelled", 10_000) { kite.order(id2).status == "CANCELLED" }
        AppSettings.awaitKillWriter()
        Broker.killSweepOn = false
    }
}
