package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.MarketClockRule
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Alerts
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 20 Nov 2026 (audit, the protections): a malformed or partial positions reply is never read as "flat", and closed counts only on two
 * agreeing reads; one look reads Zerodha's books once; the readers never wait on a look; an intraday stop and target (two orders)
 * take the other out robustly and say so loudly when they cannot; Boss's own Protect reads the position again before placing; a
 * stop GTT's limit has 10% room.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProtectionsAuditTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500CE"
    private val sym2 = "NIFTY26OCT24600CE"
    private val sym3 = "NIFTY26OCT24700CE"

    @Before fun up() {
        kite = FakeKite()
        for ((i, s) in listOf(sym, sym2, sym3).withIndex())
            kite.instruments += FakeKite.Ins(12_345_678L + i, s, "NIFTY", Market.today().plusDays(20), 24_500.0 + 100 * i, "CE", 75)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        kite.quote("NFO:$sym2", 100.0, 99.95, 100.05)
        kite.quote("NFO:$sym3", 100.0, 99.95, 100.05)
        kite.position(sym, 75, 100.0)
        kite.login()
        Alerts.forgetPosted()
        com.optionslab.app.testing.Background.clearAlerts()
        DayOco.resetForTest()
    }

    @After fun down() {
        Protections.resetFollowUpForTest()
        com.optionslab.app.security.Vault.testFailWrites = emptySet()
        Market.testClock = null; kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(h: Int, m: Int) { Market.testClock = MarketClockRule.clockAt(h, m) }
    private fun tick() = runBlocking { Protections.tick() }
    private fun active() = runBlocking { Protections.active() }

    private fun protect(s: String = sym, product: String = "NRML", stop: Double? = 90.0, target: Double? = 130.0): String = runBlocking {
        Protections.protectLive(s, "NFO", product, 75, 100.0, stop, null, target, recheck = true)
    }

    private fun working(id: String?) = id != null && kite.order(id).working

    // ---- item 1: a malformed or partial positions reply is never "flat"; two agreeing reads -----------------------------------------

    @Test fun aPositionsReplyWithNoNetListEndsNothing() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.positionsBody = JSONObject().put("day", JSONArray())          // no "net" at all
        repeat(3) { tick(); at(12, 6 + it) }
        val now = active().single()
        assertEquals(p.id, now.id)
        assertTrue("its stop still rests", working(p.stopOrderId))
        assertTrue("its target still rests", working(p.targetOrderId))
    }

    @Test fun aPositionsRowWithNoQuantityIsAFailedReadNotAPositionAtZero() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.positionsBody = JSONObject().put("net", JSONArray().put(JSONObject().put("tradingsymbol", sym).put("exchange", "NFO")
            .put("product", "NRML"))).put("day", JSONArray())
        repeat(3) { tick(); at(12, 6 + it) }
        assertEquals(p.id, active().single().id)
        assertTrue(working(p.stopOrderId))
        // The source itself (deep review): the row is left out and named, never read as a position at 0.
        val book = runBlocking { Broker.positionBook() }
        assertTrue(book.partial); assertTrue(book.unknown(sym)); assertTrue(book.net.none { it.symbol == sym })
    }

    /** Deep review: one malformed row never fails the read - the other rows are read, the row left out is named. */
    @Test fun aMalformedRowIsLeftOutAndTheRestIsRead() {
        kite.extraNetRows = listOf(JSONObject().put("tradingsymbol", sym2).put("exchange", "NFO").put("product", "NRML"))
        val book = runBlocking { Broker.positionBook() }
        assertEquals(75, book.net.single { it.symbol == sym }.qty)
        assertTrue(book.partial); assertTrue(book.unknown(sym2)); assertFalse(book.unknown(sym))
        // An exit: only the contract's own symbol and product row left out blocks it (final review).
        assertTrue(book.unknownForExit(sym2, "NRML")); assertFalse(book.unknownForExit(sym2, "MIS")); assertFalse(book.unknownForExit(sym, "NRML"))
        // A row with no symbol at all: every contract's absence is then not known for an ending check, but never for an exit.
        kite.extraNetRows = listOf(JSONObject().put("quantity", 0), "not a row")
        val any = runBlocking { Broker.positionBook() }
        assertEquals(75, any.net.single { it.symbol == sym }.qty)
        assertTrue(any.unknown(sym)); assertTrue(any.unknown("ANYTHING"))
        assertFalse(any.unknownForExit(sym, "NRML"))
        assertTrue("a contract with no row read at all may be the nameless row", any.unknownForExit("NIFTY26OCT99999CE", "NRML"))
        // A quantity given as a number in a string is read, as Kite's optInt always did.
        kite.extraNetRows = listOf(JSONObject().put("tradingsymbol", sym2).put("exchange", "NFO").put("product", "NRML").put("quantity", "75"))
        val str = runBlocking { Broker.positionBook() }
        assertFalse(str.partial); assertEquals(75, str.net.single { it.symbol == sym2 }.qty)
        kite.extraNetRows = emptyList()
        assertFalse(runBlocking { Broker.positionBook() }.partial)
    }

    /** Final review: a fast look never waits for its follow-up on the fast lane; the follow-up, made apart, still confirms and ends it. */
    @Test fun aFastLooksFollowUpIsMadeApartAndStillConfirms() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.position(sym, 0, 0.0)                                         // sold by hand
        val t0 = System.nanoTime()
        runBlocking { Protections.tick(fast = true) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("the fast look returned in $ms ms", ms < 1_500)
        assertEquals("one read ends nothing", 1, active().size)
        val until = System.nanoTime() + 8_000_000_000L
        while (active().isNotEmpty() && System.nanoTime() < until) Thread.sleep(50)
        assertTrue("the follow-up confirmed it", active().isEmpty())
        assertFalse(working(p.stopOrderId)); assertFalse(working(p.targetOrderId))
    }

    /** Final review: a fast look's follow-up whose save fails (a disk fault) is logged, never thrown; its flag is cleared. */
    @Test fun aFollowUpWhoseSaveFailsNeverCrashesAndClearsItsFlag() {
        protect()
        at(12, 5)
        kite.position(sym, 0, 0.0)                                         // sold by hand: the fast look asks a follow-up
        val crashes = java.util.concurrent.atomic.AtomicInteger()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> crashes.incrementAndGet() }
        try {
            runBlocking { Protections.tick(fast = true) }
            assertTrue("a follow-up is waiting", Protections.followUpPendingForTest())
            com.optionslab.app.security.Vault.testFailWrites = setOf("protections.vault")
            val until = System.nanoTime() + 15_000_000_000L
            while (Protections.followUpPendingForTest() && System.nanoTime() < until) Thread.sleep(50)
            assertFalse("the flag is cleared", Protections.followUpPendingForTest())
            assertEquals("nothing thrown out of the worker", 0, crashes.get())
            assertTrue("the follow-up reached the failing save and said so", Diag.lines().any { it.contains("a fast look's follow-up failed") })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
            com.optionslab.app.security.Vault.testFailWrites = emptySet()
        }
    }

    /** Final review: Boss is told (throttled) while a positions read stays partial. */
    @Test fun aPartialPositionsReadIsSaidToBoss() {
        Broker.resetPartialSaidForTest()
        fun said() = Alerts.posted.count { it.kind == Alerts.Kind.ERROR && it.text.contains("unreadable row") }
        kite.extraNetRows = listOf(JSONObject().put("tradingsymbol", sym2).put("exchange", "NFO").put("product", "NRML"))
        runBlocking { Broker.positionBook() }
        assertEquals(1, said())
        assertTrue(Alerts.posted.last { it.text.contains("unreadable row") }.text.contains(sym2))
        runBlocking { Broker.positionBook() }
        assertEquals("throttled", 1, said())
        Broker.resetPartialSaidForTest()
    }

    /** Deep review: a protected contract whose row a read left out as malformed is never ended, whatever the reads say after. */
    @Test fun aSkippedRowNeverEndsAProtection() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.positions.clear()
        kite.extraNetRows = listOf(JSONObject().put("tradingsymbol", sym).put("exchange", "NFO").put("product", "NRML").put("quantity", JSONObject.NULL))
        repeat(3) { tick(); com.optionslab.app.testing.SecondRead.clockOn() }
        assertEquals(p.id, active().single().id)
        assertTrue(working(p.stopOrderId)); assertTrue(working(p.targetOrderId))
    }

    /** Deep review: a look never waits while holding the lock - with four protections closing in one look, Protect gets in at once. */
    @Test fun fourProtectionsClosingInOneLookNeverHoldTheLockWhileTheyWait() {
        val more = listOf("NIFTY26OCT24800CE", "NIFTY26OCT24900CE")
        for ((i, s) in more.withIndex()) {
            kite.instruments += FakeKite.Ins(12_345_700L + i, s, "NIFTY", Market.today().plusDays(20), 24_800.0 + 100 * i, "CE", 75)
            kite.quote("NFO:$s", 100.0, 99.95, 100.05)
        }
        val four = listOf(sym, sym2, sym3, more[0])
        for (s in four) { kite.position(s, 75, 100.0); protect(s) }
        assertEquals(4, active().size)
        at(12, 5)
        for (s in four) kite.position(s, 0, 0.0)                           // all four sold by hand: each look's first closed read
        kite.position(more[1], 75, 100.0)
        val reads = kite.positionReads
        runBlocking {
            val look = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { Protections.tick() }
            try {
                kotlinx.coroutines.withTimeout(5_000) { while (kite.positionReads == reads) kotlinx.coroutines.delay(5) }
                kotlinx.coroutines.delay(300)                              // the first look done; its follow-up waits 2.1 s
                assertTrue("the look is still waiting for its follow-up", look.isActive)
                val t0 = System.nanoTime()
                val said = Protections.protectLive(more[1], "NFO", "NRML", 75, 100.0, 90.0, null, null)
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertTrue(said, said.startsWith("Protected"))
                assertTrue("Protect waited $ms ms for the lock", ms < 1_500)
            } finally { look.join() }
        }
        assertEquals("the four ended on their second reads; the new one stays", listOf(more[1]), active().map { it.symbol })
    }

    /** Deep review: a stream look whose stop was cancelled with the position closed says nothing; the regular look ends it quietly. */
    @Test fun aStreamLookDefersACancelledStopOnAClosedPositionQuietly() {
        kite.position(sym, 0, 0.0); kite.position(sym, 75, 100.0, product = "MIS")
        protect(product = "MIS")
        val p = active().single()
        at(12, 5)
        assertNull("intraday: the target is the app's own (22 Nov 2026)", p.targetOrderId)
        kite.order(p.stopOrderId!!).status = "CANCELLED"
        kite.position(sym, 0, 0.0, product = "MIS")                        // sold by hand
        fun warned() = Alerts.posted.any { it.kind == Alerts.Kind.ERROR && (it.text.contains("not protected", true) || it.text.contains("cancelled by you")) }
        runBlocking { Protections.tick(fast = true, stream = true) }
        assertFalse(warned())
        assertEquals(1, active().size)
        tick()
        assertTrue(active().isEmpty())
        assertFalse(warned())
    }

    @Test fun aReadWithNoRowForAContractProtectedTodayIsNotFlat() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.positions.clear()                                             // a partial reply: the contract's row missing
        repeat(3) { tick(); at(12, 6 + it) }
        assertEquals(p.id, active().single().id)
        assertTrue(working(p.stopOrderId)); assertTrue(working(p.targetOrderId))
    }

    @Test fun closedEndsOnlyOnASecondAgreeingRead() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.position(sym, 0, 0.0)                                         // one read shows it closed ...
        kite.afterNext("/portfolio/positions") { kite.position(sym, 75, 100.0) }   // ... the look's second read shows it held
        tick()
        assertEquals("one read ends nothing", p.id, active().single().id)
        assertTrue(working(p.stopOrderId)); assertTrue(working(p.targetOrderId))
        // Sold by hand for real: the look's two separate reads agree, so it ends in that same look, its stop and target cancelled.
        at(12, 6)
        kite.position(sym, 0, 0.0)
        val before = kite.positionReads
        tick()
        assertTrue("two separate reads", kite.positionReads - before >= 2)
        assertTrue("two agreeing reads end it", active().isEmpty())
        assertFalse(working(p.stopOrderId)); assertFalse(working(p.targetOrderId))
    }

    @Test fun aHeldReadBetweenStartsTheCountAgain() {
        protect()
        val p = active().single()
        repeat(3) { n ->
            at(12, 5 + n)
            kite.position(sym, 0, 0.0)                                     // a bad zero each look, a held read straight after
            kite.afterNext("/portfolio/positions") { kite.position(sym, 75, 100.0) }
            tick()
        }
        assertEquals("never two agreeing reads: kept", p.id, active().single().id)
        assertTrue(working(p.stopOrderId))
    }

    // ---- item 2: one read of each book per look; the readers never wait on a look -------------------------------------------------

    @Test fun oneLookReadsTheOrdersAndPositionsOnceForEveryProtection() {
        kite.position(sym2, 75, 100.0); kite.position(sym3, 75, 100.0)
        protect(sym); protect(sym2); protect(sym3)
        assertEquals(3, active().size)
        at(12, 5)
        kite.requests.clear()
        tick()
        val gets = kite.requests.filter { it.method == "GET" }.map { it.path }
        assertEquals(gets.toString(), 1, gets.count { it == "/orders" })
        assertEquals(gets.toString(), 1, gets.count { it == "/portfolio/positions" })
        assertTrue("the orders first", gets.indexOf("/orders") < gets.indexOf("/portfolio/positions"))
    }

    @Test fun afterAChangeAtZerodhaTheNextProtectionReadsTheBooksAgain() {
        kite.position(sym2, 75, 100.0)
        protect(sym); protect(sym2)
        val first = active().first { it.symbol == sym }
        at(12, 5)
        kite.fill(first.stopOrderId!!)                                     // the first's stop filled: its target is cancelled
        kite.requests.clear()
        tick()
        val gets = kite.requests.filter { it.method == "GET" }.map { it.path }
        assertTrue(gets.toString(), gets.count { it == "/orders" } >= 2)
        assertEquals(1, active().size)
    }

    @Test fun theReadersNeverWaitForALook() = runBlocking {
        protect()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val holder = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { Protections.holdLockForTest(release) }
        try {
            kotlinx.coroutines.delay(300)                                  // the lock taken, as by a look waiting on Zerodha
            val read = kotlinx.coroutines.withTimeout(2_000) { Protections.active() }
            assertEquals(1, read.size)
            assertNotNull(kotlinx.coroutines.withTimeout(2_000) { Protections.forSymbol(true, sym) })
            assertEquals(1, kotlinx.coroutines.withTimeout(2_000) { Protections.since(0L) }.size)
        } finally { release.complete(Unit); holder.join() }
    }

    // ---- item 3: an intraday stop and target, two orders ------------------------------------------------------------------------

    /** 22 Nov 2026 (B6 review): an intraday stop and target never rest as two orders: Boss is told the target is the app's own. */
    @Test fun anIntradayProtectionSaysTheTargetIsTheApps() {
        kite.position(sym, 0, 0.0); kite.position(sym, 75, 100.0, product = "MIS")
        val msg = protect(product = "MIS")
        assertTrue(msg, msg.contains(Protections.TARGET_BY_APP))
        assertEquals(listOf("SL"), kite.placed.map { it.form["order_type"] })
        // A carried one is not told so (its stop and target rest as two orders, or one GTT).
        kite.position(sym2, 75, 100.0)
        val carried = protect(sym2)
        assertFalse(carried, carried.contains(Protections.TARGET_BY_APP))
    }

    /** (22 Nov 2026, B6 review: two resting orders are a carried protection's now - the day GTT off - so it is tested on one.) */
    @Test fun aTargetThatCannotBeCancelledAfterTheStopFilledIsSaidLoudlyAndTriedAgain() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.fill(p.stopOrderId!!)
        kite.cancelsLost = 4                                               // the look's three tries, and the next look's one, are lost
        fun said() = Alerts.posted.any { it.kind == Alerts.Kind.ERROR && it.text.contains("could not be cancelled") && it.text.contains("cancel it in Kite now") }
        tick()
        assertEquals("kept while its target may rest", 1, active().size)
        assertTrue(working(p.targetOrderId))
        assertEquals("three tries in the look", 3, kite.requests.count { it.method == "DELETE" && it.path.endsWith(p.targetOrderId!!) })
        // Review: Zerodha took every cancel (it may still be on its way out): not said on the first look, only when the next still finds it.
        assertFalse(said())
        at(12, 6)
        tick()
        assertEquals(1, active().size)
        assertTrue(said())
        // The next look takes it out and finishes.
        at(12, 7)
        tick()
        assertFalse(working(p.targetOrderId))
        assertTrue(active().isEmpty())
    }

    // ---- item 5: Boss's own Protect reads the position again --------------------------------------------------------------------

    @Test fun bossesProtectOfAPositionSoldSinceIsRefusedAndNothingIsSent() {
        kite.position(sym, 0, 0.0)
        val msg = protect()
        assertTrue(msg, msg.startsWith("Not protected: Zerodha shows no long"))
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun bossesProtectOfAPositionPartSoldSinceCoversOnlyWhatIsHeld() {
        kite.position(sym, 0, 0.0); kite.position(sym, 75, 100.0)
        val msg = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 150, 100.0, 90.0, null, 130.0, recheck = true) }
        assertTrue(msg, msg.contains("only 75 is protected"))
        assertTrue(kite.placed.all { it.form["quantity"] == "75" })
        assertEquals(75, active().single().qty)
    }

    /** Review: a bracket's or news trade's stop straight after its fill is never refused on a positions read that trails the fill. */
    @Test fun aFreshFillsStopIsPlacedEvenWhileThePositionsTrailIt() {
        kite.position(sym, 0, 0.0)                                         // Zerodha's positions not showing the fill yet
        val msg = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0, recheck = false) }
        assertTrue(msg, msg.startsWith("Protected"))
        assertTrue(kite.placed.any { it.form["order_type"] == "SL" && it.form["quantity"] == "75" } || kite.gtts.isNotEmpty())
        kite.down += "/portfolio/positions"                                // nor on a read that fails
        val msg2 = runBlocking { Protections.protectLive(sym2, "NFO", "NRML", 75, 100.0, 90.0, null, null, recheck = false) }
        assertTrue(msg2, msg2.startsWith("Protected"))
    }

    /** Every other caller (no recheck) as before: a position the positions read does not show yet still gets its stop. */
    @Test fun withoutARecheckThePositionIsNotReadFirst() {
        kite.position(sym, 0, 0.0)
        val msg = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 75, 100.0, 90.0, null, null) }
        assertTrue(msg, msg.startsWith("Protected"))
    }

    @Test fun bossesProtectWithThePositionsUnreadPlacesNothing() {
        kite.down += "/portfolio/positions"
        val msg = protect()
        assertTrue(msg, msg.startsWith("Not protected: Zerodha's positions could not be read"))
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun aManualGttIsCheckedAgainstThePositionJustBeforeItGoes() = runBlocking {
        val spec = com.optionslab.engine.Kite.Spec("NFO", sym, 12_345_678L, 75, 0.05)
        val (g, _) = com.optionslab.engine.Kite.protect(spec, "NRML", 75, 100.0, 90.0, 130.0)
        assertNull(Protections.gttStillFits(g!!))
        kite.position(sym, 0, 0.0)
        assertTrue(Protections.gttStillFits(g)!!.contains("would not close a position"))
        kite.position(sym, 50, 100.0)
        assertTrue(Protections.gttStillFits(g)!!.contains("less than the GTT's 75"))
        kite.down += "/portfolio/positions"
        assertTrue(Protections.gttStillFits(g)!!.contains("could not be read"))
    }

    // ---- item 4: a stop GTT's limit has 10% room -----------------------------------------------------------------------------

    @Test fun aCarriedStopRestingOvernightAsAGttSellsWithTenPercentRoom() {
        protect(stop = 90.0, target = null)
        val p = active().single()
        at(15, 45)
        kite.order(p.stopOrderId!!).status = "CANCELLED"                  // the session's DAY stop ended at the close
        tick()
        val g = kite.gtts.values.single()
        val legs = g.getJSONArray("orders")
        assertEquals(81.0, legs.getJSONObject(0).getDouble("price"), 1e-9)   // 90 less 10%, not 85.50 (5%)
    }
}
