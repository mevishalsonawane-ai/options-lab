package com.optionslab.app.data

import com.optionslab.app.ira.IraHub
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.ArmParams
import com.optionslab.ira.OrderReview
import com.optionslab.ira.PositionSync
import com.optionslab.ira.VariantLab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * The order review's host ([OrderReviewHost]): due once a trading day after 15:45 (and the next morning for MCX's late trades),
 * reviewed from the stores (here the test's inputs), kept in its own small file and read back; a recurring parameter-like flaw
 * asked of the strategy lab (never applied), a bug-like one flagged; only patterns go to Jarvis's lessons; Jarvis's answers
 * never on a locked phone. It never sends anything anywhere: no network at all.
 */
class OrderReviewHostTest : RobolectricTest() {
    // A fixed Monday (12 Oct 2026 is a trading day); never the real date.
    private val day = LocalDate.of(2026, 10, 12)
    private fun at(d: LocalDate, h: Int, m: Int): ZonedDateTime = d.atTime(h, m).atZone(com.optionslab.engine.IST)
    private fun ms(d: LocalDate, h: Int, m: Int) = at(d, h, m).toInstant().toEpochMilli()

    @Before fun up() {
        assumeTrue("the order review is in the Jarvis builds only", OrderReviewHost.enabled())
        OrderReviewHost.resetForTest()
        VariantRunner.resetForTest()
        com.optionslab.app.work.BackgroundBudget.resetForTest()
        com.optionslab.app.work.BackgroundBudget.testPhone = com.optionslab.ira.BudgetRule.Phone(80, false, 30.0, false)
    }

    @After fun down() {
        OrderReviewHost.resetForTest()
        AppState.resetForTest()
        VariantRunner.resetForTest()
        com.optionslab.app.work.BackgroundBudget.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** A trade on [d] that gave back most of its best gain (a parameter-like flaw for ORB Fresh). */
    private fun gaveBack(d: LocalDate, id: String) = OrderReview.Trade(id, OrderReview.Venue.PAPER, "ORB Fresh", "BANKNIFTY26OCT55000CE", 30,
        100.0, ms(d, 10, 30), 105.0, ms(d, 11, 0), best = 130.0, stop = 90.0, target = 140.0, exitWhy = "profit_lock", net = 110.0, charges = 40.0)

    private fun inputs(withFlaw: Boolean, sync: Boolean = false): (LocalDate, Long, Long) -> OrderReview.Inputs = { d, _, now ->
        OrderReview.Inputs(d, trades = if (withFlaw) listOf(gaveBack(d, "t$d")) else emptyList(),
            sync = if (sync) listOf(PositionSync.Mismatch(PositionSync.Kind.STOP_MISSING, "X", "ORB", 30, 30)) else emptyList(), nowMs = now)
    }

    @Test fun dueOnceAfterTheCloseAndTheNextMorningForMcx() {
        val b = OrderReview.Book()
        assertNull("not before 15:45", OrderReviewHost.due(at(day, 15, 30), b))
        assertEquals(day to false, OrderReviewHost.due(at(day, 15, 45), b))
        val ran = b.copy(lastRun = day, cutoffMs = ms(day, 15, 50))
        assertNull("once a day", OrderReviewHost.due(at(day, 20, 0), ran))
        // The next morning (07:00-09:00): the evening's MCX trades closed after the review are read into that day.
        assertEquals(day to true, OrderReviewHost.due(at(day.plusDays(1), 7, 30), ran))
        assertNull(OrderReviewHost.due(at(day.plusDays(1), 9, 30), ran))
        assertNull("already caught up", OrderReviewHost.due(at(day.plusDays(1), 7, 30), ran.copy(cutoffMs = ms(day.plusDays(1), 7, 0))))
        // A weekend is not a trading day.
        assertNull(OrderReviewHost.due(at(LocalDate.of(2026, 10, 17), 16, 0), b))
    }

    @Test fun theReviewIsKeptInItsFileAndReadBack() = runBlocking {
        OrderReviewHost.testInputs = inputs(withFlaw = true)
        assertTrue(OrderReviewHost.runIfDue(context, at(day, 15, 50)))
        assertFalse("once a day", OrderReviewHost.runIfDue(context, at(day, 16, 30)))
        val b = OrderReviewHost.book.value
        assertEquals(day, b.lastRun)
        assertEquals(OrderReview.Category.GAVE_BACK, b.last!!.flaws.single().category)
        assertTrue(OrderReviewHost.cardLine(b, day).startsWith("Order review · Today: 1 trade · 1 flaw"))
        assertTrue(OrderReviewHost.diagLine().startsWith("Order review: last 2026-10-12"))
        assertFalse(OrderReviewHost.diagLine().contains("₹"))
        // A new process reads the file back.
        OrderReviewHost.reloadForTest()
        OrderReviewHost.ensureLoaded()
        assertEquals(b.last, OrderReviewHost.book.value.last)
        assertEquals(b.logs, OrderReviewHost.book.value.logs)
    }

    @Test fun aOneOffIsNeverALessonOrALabAsk() = runBlocking {
        OrderReviewHost.testInputs = inputs(withFlaw = true)
        assertTrue(OrderReviewHost.runIfDue(context, at(day, 15, 50)))
        assertTrue(OrderReviewHost.lessonInputs().isEmpty())
        assertTrue(OrderReviewHost.book.value.requests.isEmpty())
    }

    @Test fun aRecurringParameterFlawIsAskedOfTheLabNeverApplied() = runBlocking {
        OrderReviewHost.testInputs = inputs(withFlaw = true)
        val days = listOf(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), day)
        for (d in days) assertTrue("$d", OrderReviewHost.runIfDue(context, at(d, 15, 50)))
        val b = OrderReviewHost.book.value
        val p = OrderReview.patterns(b.logs).single()
        assertEquals(OrderReview.Category.GAVE_BACK, p.category); assertEquals(3, p.count)
        // ORB Fresh has no profit-lock knob in the lab: its give-back becomes a lower target, asked as ONE shadow variant.
        val r = b.requests.single()
        assertEquals(OrderReview.QUEUED, r.status)
        assertEquals(OrderReview.LabAsk("orb_fresh", ArmParams.TARGET_PTS, -1, r.ask.why), r.ask)
        // Nothing applied: the lab's book has no change and no tune from it.
        assertTrue(VariantRunner.variants.value.changes.isEmpty() && VariantRunner.variants.value.tunes.isEmpty())
        // Jarvis's lessons get the pattern's days.
        val ins = OrderReviewHost.lessonInputs()
        assertEquals(3, ins.size); assertTrue(ins.all { it.hits == 1 })
        // The lab's own day pass takes it through its own checks (born today, first compared on the next session).
        val pass = VariantLab.day(VariantLab.Book(runs = mapOf("orb_fresh" to VariantLab.Run.PAPER)), day, mapOf("orb_fresh" to VariantLab.Run.PAPER),
            emptyMap(), emptyList(), com.optionslab.ira.DiscoveryStats.Rng(1),
            seeds = listOf(VariantLab.Seed("s", r.ask.labKey, r.ask.param, r.ask.dir, r.ask.why)))
        assertEquals(VariantLab.Status.ALIVE, pass.book.variants.first { it.id == pass.seeded.single().variant }.status)
    }

    @Test fun aRecurringBugLikeFlawIsFlaggedNotFixedByGuessing() = runBlocking {
        OrderReviewHost.testInputs = inputs(withFlaw = false, sync = true)
        for (d in listOf(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), day)) assertTrue(OrderReviewHost.runIfDue(context, at(d, 15, 50)))
        val imp = OrderReviewHost.improvements().single()
        assertEquals(OrderReview.Route.NEEDS_FIX, imp.route)
        assertTrue(OrderReviewHost.book.value.requests.isEmpty())
        assertTrue(Diag.lines().any { it.contains("needs a fix") })
        // The evening's notice is noted once a day (its words never carry rupees).
        assertTrue(OrderReviewHost.book.value.told.contains("notice:$day"))
        val v = OrderReviewHost.view(OrderReviewHost.book.value, VariantRunner.variants.value, day)
        assertTrue(v.warnings.any { it.startsWith("Needs a fix: ") })
        assertTrue(v.fixes.isNotEmpty())
    }

    @Test fun jarvisAnswersOnlyOnAnUnlockedPhone() {
        fun ask(q: String): String {
            val start = IraHub.state.value.messages.maxOfOrNull { it.id } ?: 0L
            IraHub.ask(q)
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && IraHub.state.value.messages.none { it.fromIra && it.id > start }) Thread.sleep(10)
            return IraHub.state.value.messages.last().text
        }
        val km = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
        km.setKeyguardLocked(true)
        try {
            for (q in listOf("review my orders", "what went wrong today", "flaws in orb today", "how much did slippage cost me"))
                assertEquals(q, "Unlock the phone for that, Boss.", ask(q))
        } finally { km.setKeyguardLocked(false) }
        val a = ask("review my orders")
        assertTrue(a, a.startsWith("I have not reviewed any orders yet"))
    }
    // ---- 15 Nov: a missed day is reviewed, a failed review is said, the day's live orders survive a restart ---------------

    @Test fun aMissedDayIsCaughtUpTheMostRecentOneFirst() {
        // Last reviewed Thursday 8 Oct; the app then did not run on Friday 9 or Monday 12 evening (two evenings missed).
        val ran = OrderReview.Book(lastRun = LocalDate.of(2026, 10, 8), cutoffMs = ms(LocalDate.of(2026, 10, 8), 15, 50))
        val tue = LocalDate.of(2026, 10, 13)
        // Any hour of the next day: the most recent missed trading day (Monday), not the older one.
        assertEquals(day to false, OrderReviewHost.due(at(tue, 10, 0), ran))
        // In the evening too, before the day's own review (which would otherwise take its place).
        assertEquals(day to false, OrderReviewHost.due(at(tue, 16, 0), ran))
        // Once it is reviewed, the day's own review is next; then nothing.
        val caught = ran.copy(lastRun = day, cutoffMs = ms(day.plusDays(1), 0, 0) - 1)
        assertNull(OrderReviewHost.due(at(tue, 10, 0), caught))
        assertEquals(tue to false, OrderReviewHost.due(at(tue, 16, 0), caught))
        // A caught-up day is never taken for MCX's morning catch-up (it was read to its end).
        assertNull(OrderReviewHost.due(at(tue, 7, 30), caught))
        // A weekend: Friday missed is reviewed on Saturday.
        val thu = OrderReview.Book(lastRun = LocalDate.of(2026, 10, 15), cutoffMs = ms(LocalDate.of(2026, 10, 15), 15, 50))
        assertEquals(LocalDate.of(2026, 10, 16) to false, OrderReviewHost.due(at(LocalDate.of(2026, 10, 17), 12, 0), thu))
        // A review that never ran has missed nothing.
        assertNull(OrderReviewHost.due(at(tue, 10, 0), OrderReview.Book()))
    }

    @Test fun oneEveningRunReviewsTheMissedDayThenTodayEachToItsOwnEnd() = runBlocking {
        val calls = ArrayList<Triple<LocalDate, Long, Long>>()
        val base = inputs(withFlaw = true)
        OrderReviewHost.testInputs = { d, from, to -> calls += Triple(d, from, to); base(d, from, to) }
        val thu = LocalDate.of(2026, 10, 8)
        assertTrue(OrderReviewHost.runIfDue(context, at(thu, 15, 50)))
        calls.clear()
        // Friday 9 Oct missed; Monday 12 Oct's evening run reviews Friday (to its own end), then Monday (to now).
        assertTrue(OrderReviewHost.runIfDue(context, at(day, 15, 50)))
        val fri = LocalDate.of(2026, 10, 9)
        val friEnd = ms(day.minusDays(2), 0, 0) - 1
        assertEquals(listOf(fri, day), calls.map { it.first })
        assertEquals(friEnd, calls[0].third)
        assertEquals("Monday's window starts where Friday's ended", friEnd, calls[1].second)
        assertEquals(ms(day, 15, 50), calls[1].third)
        val b = OrderReviewHost.book.value
        assertEquals(day, b.lastRun)
        assertTrue(b.logs.map { it.day }.toString(), b.logs.map { it.day }.containsAll(listOf(thu, fri, day)))
        assertTrue(Diag.lines().any { it.contains("reviewed $fri (a missed day, caught up)") })
        // Nothing more that evening.
        assertFalse(OrderReviewHost.runIfDue(context, at(day, 16, 30)))
    }

    @Test fun aReviewThatDoesNotCompleteSaysSoOnceWithTheClassOnly() = runBlocking {
        OrderReviewHost.testInputs = { _, _, _ -> throw IllegalStateException("order 251012000123456 for AB1234 at Rs 4,000") }
        assertFalse(OrderReviewHost.runIfDue(context, at(day, 15, 50)))
        val said = Diag.lines().filter { it.contains("did not complete") }
        assertEquals(said.toString(), 1, said.size)
        assertTrue(said.single(), said.single().contains("the review of $day did not complete: its records could not be read (IllegalStateException)"))
        for (part in listOf("251012000123456", "AB1234", "4,000")) assertFalse(said.single(), said.single().contains(part))
        // Tried again after ten minutes, failing the same way: not said again.
        assertFalse(OrderReviewHost.runIfDue(context, at(day, 15, 55)))
        assertFalse(OrderReviewHost.runIfDue(context, at(day, 16, 5)))
        assertEquals(1, Diag.lines().count { it.contains("did not complete") })
        assertNull(OrderReviewHost.book.value.lastRun)
        // Once the records read again, the day is reviewed.
        OrderReviewHost.testInputs = inputs(withFlaw = false)
        assertTrue(OrderReviewHost.runIfDue(context, at(day, 16, 20)))
        assertEquals(day, OrderReviewHost.book.value.lastRun)
    }

    private val td: LocalDate get() = Market.today()

    private fun row(id: String, d: LocalDate, status: String, filled: Int = 0) = Broker.OrderRow(id = id, symbol = "BANKNIFTY26OCT55000CE", side = "BUY",
        qty = 30, filled = filled, price = 100.0, avg = if (filled > 0) 100.5 else 0.0, status = status, message = "", placedAt = "$d 10:0$id:00")

    private fun liveIn(d: LocalDate): List<OrderReview.Order> =
        OrderReviewHost.ordersIn(emptyMap(), ms(d, 0, 0), ms(d.plusDays(1), 0, 0) - 1).filter { it.venue == OrderReview.Venue.LIVE }

    private fun snapFile(d: LocalDate) = java.io.File(context.noBackupFilesDir, "orderreview/orders-$d.vault")

    @Test fun theDaysLiveOrdersAreKeptSealedAndReadAfterARestart() {
        // Empty: nothing kept, no file.
        assertEquals(0, OrderReviewHost.snapshotOrders(emptyList()))
        assertTrue(liveIn(td).isEmpty())
        assertFalse(snapFile(td).exists())
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("1", td, "OPEN"), row("2", td, "COMPLETE", 30))))
        // Sealed with the vault's key: no symbol or status in plain text.
        val raw = String(snapFile(td).readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("BANKNIFTY") || raw.contains("COMPLETE"))
        // The same book again writes nothing.
        assertEquals(0, OrderReviewHost.snapshotOrders(listOf(row("1", td, "OPEN"), row("2", td, "COMPLETE", 30))))
        // A restart (a low-memory kill): nothing in memory, the broker's book not read yet.
        OrderReviewHost.reloadForTest()
        AppState.resetForTest()
        val got = liveIn(td)
        assertEquals(listOf("kite:1", "kite:2"), got.map { it.id }.sorted())
        assertEquals("COMPLETE", got.first { it.id == "kite:2" }.status)
        // Another day reads none of them.
        assertTrue(liveIn(td.minusDays(1)).isEmpty())
    }

    @Test fun memoryAndTheKeptOrdersMergeByIdTheNewestStateWins() {
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("1", td, "OPEN"), row("2", td, "OPEN"))))
        OrderReviewHost.reloadForTest()
        // After the restart the broker's book shows order 1 filled and a new order 3 (order 2 is not in this read).
        AppState.publishOrders(listOf(row("1", td, "COMPLETE", 30), row("3", td, "OPEN")), System.currentTimeMillis())
        val got = liveIn(td)
        assertEquals(listOf("kite:1", "kite:2", "kite:3"), got.map { it.id }.sorted())
        assertEquals("COMPLETE", got.single { it.id == "kite:1" }.status)
        // Kept: order 1's newest state, order 2 still there.
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("1", td, "COMPLETE", 30), row("3", td, "OPEN"))))
        OrderReviewHost.reloadForTest()
        AppState.resetForTest()
        val kept = liveIn(td)
        assertEquals(listOf("kite:1", "kite:2", "kite:3"), kept.map { it.id }.sorted())
        assertEquals("COMPLETE", kept.single { it.id == "kite:1" }.status)
    }

    @Test fun oldDaysArePrunedAndADamagedFileIsNeverAnError() {
        val old = td.minusDays(OrderReviewHost.SNAP_KEEP_DAYS + 3)
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("1", old, "COMPLETE", 30)), today = old))
        assertTrue(snapFile(old).exists())
        val junk = java.io.File(context.noBackupFilesDir, "orderreview/orders-junk.vault").apply { writeText("x") }
        // A damaged day: garbage where the sealed file should be.
        val bad = td.minusDays(1)
        snapFile(bad).writeBytes(ByteArray(64) { it.toByte() })
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("2", td, "OPEN"))))
        assertFalse("older than the days kept: deleted", snapFile(old).exists())
        assertFalse(junk.exists())
        assertTrue(snapFile(td).exists())
        // The damaged day reads as no orders, never an error.
        assertTrue(liveIn(bad).isEmpty())
    }

    @Test fun eraseEverythingDeletesTheKeptOrders() {
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("1", td, "OPEN"))))
        liveIn(td)
        assertTrue(OrderReviewHost.holdsForErase())
        assertTrue(OrderReviewHost.filesForErase().any { it.name == "orders-$td.vault" && it.exists() })
        OrderReviewHost.wipe()
        assertFalse(snapFile(td).exists())
        assertFalse(OrderReviewHost.holdsForErase())
        assertTrue(OrderReviewHost.filesForErase().none { it.exists() })
        assertTrue(liveIn(td).isEmpty())
    }

    @Test fun theWatcherKeepsTheBookTheBrokerReadsOffTheCallersThread() {
        OrderReviewHost.snapshotsOn = true
        // As the broker's read does: published into memory; the watcher (its own thread) writes the day's file.
        AppState.publishOrders(listOf(row("1", td, "OPEN")), System.currentTimeMillis())
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && !snapFile(td).exists()) Thread.sleep(20)
        assertTrue("the watcher kept the day's live orders", snapFile(td).exists())
        OrderReviewHost.snapshotsOn = false
        OrderReviewHost.reloadForTest()
        AppState.resetForTest()
        assertEquals(listOf("kite:1"), liveIn(td).map { it.id })
    }
}
